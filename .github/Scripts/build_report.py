#!/usr/bin/env python3
"""Arma el reporte en Markdown a partir de los resultados de Android Lint, Detekt y ktlint.

Uso: build_report.py <carpeta_con_resultados> <archivo_de_salida.md>

Espera (todos opcionales) dentro de la carpeta:
  lint/lint-results*.xml   -> Android Lint (formato XML de AGP)
  detekt/detekt.xml        -> Detekt (reporte "xml", formato checkstyle)
  ktlint/ktlint.txt        -> ktlint (reporter "plain")

Solo usa la biblioteca estándar de Python.
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from datetime import datetime, timezone

MAX_BODY = 60000  # el límite de GitHub para un issue es 65.536 caracteres
DETALLE_POR_HERRAMIENTA = 25
EJEMPLOS_POR_REGLA = 3
TOP_REGLAS = 10

REPO = os.environ.get("GITHUB_REPOSITORY", "")
SHA = os.environ.get("GITHUB_SHA", "")
RUN_URL = ""
if REPO and os.environ.get("GITHUB_RUN_ID"):
    RUN_URL = f"https://github.com/{REPO}/actions/runs/{os.environ['GITHUB_RUN_ID']}"
WORKSPACE = (os.environ.get("GITHUB_WORKSPACE") or os.getcwd()).rstrip("/") + "/"


def rel(path: str) -> str:
    """Convierte una ruta absoluta del runner en una ruta relativa al repo."""
    path = path.replace("\\", "/")
    for base in (WORKSPACE, os.getcwd().rstrip("/") + "/"):
        if path.startswith(base):
            return path[len(base):]
    return path


def link(path: str, line: str) -> str:
    texto = f"`{path}:{line}`" if line else f"`{path}`"
    if REPO and SHA and path and not path.startswith("/"):
        anchor = f"#L{line}" if line else ""
        return f"[{texto}](https://github.com/{REPO}/blob/{SHA}/{path}{anchor})"
    return texto


def limpiar(msg: str) -> str:
    msg = msg.replace(WORKSPACE, "")
    msg = re.sub(r"\s+", " ", msg).replace("|", "\\|").strip()
    return msg if len(msg) <= 180 else msg[:177] + "..."


# ----------------------------------------------------------------- Android Lint
def leer_lint(carpeta):
    archivos = sorted(glob.glob(os.path.join(carpeta, "lint", "lint-results*.xml")))
    if not archivos:
        return None
    hallazgos = []
    for archivo in archivos:
        try:
            raiz = ET.parse(archivo).getroot()
        except ET.ParseError:
            continue
        for issue in raiz.iter("issue"):
            loc = issue.find("location")
            hallazgos.append({
                "regla": issue.get("id", "?"),
                "severidad": issue.get("severity", "?"),
                "mensaje": limpiar(issue.get("message", "")),
                "archivo": rel(loc.get("file", "")) if loc is not None else "",
                "linea": (loc.get("line", "") if loc is not None else ""),
            })
    return hallazgos


# ----------------------------------------------------------------------- Detekt
def leer_detekt(carpeta):
    archivo = os.path.join(carpeta, "detekt", "detekt.xml")  # formato checkstyle
    if not os.path.exists(archivo):
        return None
    try:
        raiz = ET.parse(archivo).getroot()
    except ET.ParseError:
        return None
    hallazgos = []
    for f in raiz.iter("file"):
        ruta = rel(f.get("name", ""))
        for e in f.iter("error"):
            hallazgos.append({
                "regla": (e.get("source") or "?").split(".")[-1],
                "severidad": "warning",
                "mensaje": limpiar(e.get("message", "")),
                "archivo": ruta,
                "linea": e.get("line", ""),
            })
    return hallazgos


# ----------------------------------------------------------------------- ktlint
KTLINT_RE = re.compile(r"^(.+?):(\d+):(\d+): (.*) \(([^()]+)\)$")


def leer_ktlint(carpeta):
    archivo = os.path.join(carpeta, "ktlint", "ktlint.txt")
    if not os.path.exists(archivo):
        return None
    hallazgos = []
    with open(archivo, encoding="utf-8", errors="replace") as f:
        for linea in f:
            m = KTLINT_RE.match(linea.strip())
            if not m:
                continue
            hallazgos.append({
                "regla": m.group(5),
                "severidad": "warning",
                "mensaje": limpiar(m.group(4)),
                "archivo": rel(m.group(1)),
                "linea": m.group(2),
            })
    return hallazgos


# ---------------------------------------------------------------------- Reporte
ORDEN_SEVERIDAD = {"Fatal": 0, "Error": 1, "Warning": 2, "Informational": 3, "warning": 2}


def seccion(titulo, hallazgos, nota_vacio):
    out = [f"## {titulo}", ""]
    if hallazgos is None:
        out += [f"_No se generó el reporte de {titulo} en esta ejecución (el paso falló antes de terminar)._", ""]
        return out
    if not hallazgos:
        out += [nota_vacio, ""]
        return out

    por_regla = Counter(h["regla"] for h in hallazgos)
    out += [f"**{len(hallazgos)} hallazgos.** Reglas más frecuentes:", "", "| Regla | Cantidad |", "|---|---:|"]
    for regla, n in por_regla.most_common(TOP_REGLAS):
        out.append(f"| `{regla}` | {n} |")
    out.append("")

    ejemplos_por_regla = Counter()
    elegidos = []
    ordenados = sorted(
        hallazgos,
        key=lambda h: (ORDEN_SEVERIDAD.get(h["severidad"], 9), -por_regla[h["regla"]], h["regla"], h["archivo"], int(h["linea"] or 0)),
    )
    for h in ordenados:
        if len(elegidos) >= DETALLE_POR_HERRAMIENTA:
            break
        if ejemplos_por_regla[h["regla"]] < EJEMPLOS_POR_REGLA:
            ejemplos_por_regla[h["regla"]] += 1
            elegidos.append(h)

    out += [f"<details><summary>Ejemplos ({len(elegidos)} de {len(hallazgos)}, hasta {EJEMPLOS_POR_REGLA} por regla)</summary>", ""]
    for h in elegidos:
        etiqueta = f"**{h['severidad']}** " if h["severidad"] not in ("warning",) else ""
        ubic = link(h["archivo"], h["linea"]) if h["archivo"] else ""
        out.append(f"- {etiqueta}`{h['regla']}` {ubic} — {h['mensaje']}")
    resto = len(hallazgos) - len(elegidos)
    if resto > 0:
        out.append(f"- … y {resto} más (el listado completo está en los artefactos de la ejecución).")
    out += ["", "</details>", ""]
    return out


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)
    carpeta, salida = sys.argv[1], sys.argv[2]

    lint = leer_lint(carpeta)
    detekt = leer_detekt(carpeta)
    ktlint = leer_ktlint(carpeta)

    ahora = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    commit = f"`{SHA[:7]}`" if SHA else "(desconocido)"
    lineas = ["# Reporte de análisis automático", "",
              f"Actualizado: {ahora} · Commit {commit}"
              + (f" · [Ver ejecución]({RUN_URL})" if RUN_URL else ""), ""]

    def total(h):
        return "sin datos" if h is None else str(len(h))

    lineas += ["| Herramienta | Hallazgos |", "|---|---:|",
               f"| Android Lint | {total(lint)} |",
               f"| Detekt | {total(detekt)} |",
               f"| ktlint | {total(ktlint)} |", ""]
    lineas += seccion("Android Lint", lint, "Sin hallazgos. ✅")
    lineas += seccion("Detekt", detekt, "Sin hallazgos. ✅")
    lineas += seccion("ktlint", ktlint, "Sin hallazgos de estilo. ✅")
    lineas += ["---",
               "_Este issue se actualiza solo en cada ejecución del workflow "
               "`Análisis de código`. No lo cierres: si lo cerrás, se crea uno nuevo._"]

    cuerpo = "\n".join(lineas)
    if len(cuerpo) > MAX_BODY:
        cuerpo = cuerpo[:MAX_BODY] + "\n\n_(reporte truncado por el límite de tamaño de GitHub)_"
    with open(salida, "w", encoding="utf-8") as f:
        f.write(cuerpo + "\n")
    print(f"Reporte escrito en {salida} ({len(cuerpo)} caracteres)")


if __name__ == "__main__":
    main()
