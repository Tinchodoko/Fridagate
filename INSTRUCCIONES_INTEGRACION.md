# Guía de integración — FridaGate 2.0

> **Aviso:** las instrucciones antiguas de este archivo describían una integración inicial del gestor de scripts y ya no corresponden a la estructura actual del repositorio. No copies aquellos archivos sobre el código vigente ni agregues nuevamente componentes duplicados.

## Estado actual

FridaGate 2.0 ya contiene una aplicación Android basada en Kotlin y Jetpack Compose, con pestañas y componentes para el entorno de Frida, scripts, registros, terminal integrada, navegador integrado y diagnóstico.

La implementación actual se encuentra en:

- `app/src/main/java/com/hackpuntes/fridagate/`: código de la aplicación.
- `app/src/main/java/com/hackpuntes/fridagate/ui/terminal/`: terminal integrada.
- `app/src/main/java/com/hackpuntes/fridagate/ui/about/`: información de la app y navegador.
- `app/src/main/assets/scripts/`: scripts de diagnóstico empaquetados.
- `tools/`: fuentes para construir algunos agentes de diagnóstico.
- `.github/workflows/build.yml`: compilación y publicación automática de APK.

El paquete interno `com.hackpuntes.fridagate` se conserva por compatibilidad con la identidad de instalación. No lo renombres como parte de una actualización de documentación.

## Compilar y verificar

Requisitos: JDK 11 y Node.js 20.

```bash
git clone https://github.com/Tinchodoko/Fridagate.git
cd Fridagate
chmod +x gradlew
./gradlew assembleDebug
```

El APK de depuración se genera en `app/build/outputs/apk/debug/app-debug.apk`. También puedes usar el workflow **Build FridaGate 2.0 APK** de GitHub Actions.

## Reglas para cambios futuros

1. Inspecciona primero la implementación actual y sus llamadas antes de editar.
2. Evita añadir archivos que dupliquen funciones ya existentes.
3. Mantén la compatibilidad de la aplicación instalada salvo que una migración se planifique explícitamente.
4. Ejecuta la compilación automatizada después de los cambios.
5. Documenta solo funciones verificadas en el código y distingue las funciones experimentales de las comprobadas en dispositivo.

Para la guía de usuario actual, consulta [README.md](README.md). Para obtener una APK, consulta [QUICKSTART.md](QUICKSTART.md).
