# FridaGate 2.0

**Herramienta Android de instrumentación dinámica y diagnóstico con Frida.** Administra el entorno de Frida, scripts JavaScript, registros y pruebas directamente desde el dispositivo.

[![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-Android-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4)](https://developer.android.com/jetpack/compose)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

> Proyecto en desarrollo activo. Las funciones y los resultados de diagnóstico deben validarse en cada dispositivo y aplicación de destino.

## ¿Qué es FridaGate 2.0?

FridaGate 2.0 es una aplicación Android nativa creada con Kotlin y Jetpack Compose para facilitar el trabajo con [Frida](https://frida.re). Reúne la administración del entorno de Frida, la gestión de scripts, el diagnóstico del runtime y herramientas de apoyo en una interfaz móvil.

El proyecto está orientado a pruebas autorizadas, análisis de seguridad, depuración y aprendizaje sobre aplicaciones propias o en las que tengas permiso para realizar pruebas.

## Funciones

### Entorno de Frida
- Consultar el estado de root, frida-server y el entorno de ejecución.
- Descargar, instalar, iniciar, detener y actualizar frida-server.
- Consultar versiones disponibles y la versión detectada en el dispositivo.
- Gestionar frida-inject desde las herramientas de la aplicación.
- Ver registros de las operaciones y del estado del entorno.

### Gestor de scripts
- Crear y administrar scripts JavaScript.
- Importar archivos `.js` desde el almacenamiento del dispositivo.
- Seleccionar una aplicación de destino cuando corresponda.
- Ejecutar scripts y consultar sus registros.
- Exportar scripts y resultados para revisarlos fuera de la aplicación.
- Usar scripts de diagnóstico para identificar señales de Android nativo, Java, Unity/IL2CPP u otros runtimes compatibles. La detección es orientativa y depende de la aplicación y del proceso.

### Terminal integrada
- Ejecutar comandos en el shell de Android desde la aplicación.
- Utilizar el modo root cuando el dispositivo y la configuración lo permitan.
- Consultar la salida de los comandos, el código de salida y el historial reciente.
- Copiar los registros y exportar la salida de la terminal.

**Precaución:** los comandos se ejecutan en el dispositivo y pueden modificarlo. El acceso root amplía considerablemente sus efectos. Ejecuta únicamente comandos que entiendas y necesites.

### Navegador integrado
- Abrir páginas web dentro de la aplicación mediante Android WebView.
- Consultar el progreso de carga, volver a la página anterior y recargar.
- Abrir una página en el navegador externo cuando lo necesites.

### Diagnóstico y registros
- Scripts de diagnóstico de runtime, Java y Unity/IL2CPP incluidos o generados durante la compilación.
- Registro de operaciones para facilitar la investigación de errores.
- Exportación de logs para análisis posterior.
- Pantalla de información con comprobación de publicaciones de GitHub cuando la conexión está disponible.

## Requisitos

- Dispositivo Android compatible.
- Para instalar o iniciar frida-server y usar las operaciones privilegiadas, normalmente se necesita root y un binario de Frida compatible con la arquitectura y la versión del dispositivo.
- Una versión de frida-server compatible con las herramientas cliente/runtime que se utilicen.
- Conexión a Internet para consultar publicaciones o descargar componentes.

## Instalar la aplicación

### Opción A: descargar una build de GitHub

1. Abre [GitHub Actions](https://github.com/Tinchodoko/Fridagate/actions) o las [publicaciones](https://github.com/Tinchodoko/Fridagate/releases).
2. Elige una compilación que haya terminado correctamente.
3. Descarga el artefacto APK o el APK adjunto a la publicación.
4. Instálalo en tu dispositivo Android. Android puede solicitar permiso para instalar aplicaciones de esa fuente.

Las publicaciones automáticas usan etiquetas con el formato `build-N`, donde `N` corresponde al número de ejecución de GitHub Actions. La disponibilidad de una build depende de que el workflow haya terminado correctamente.

### Opción B: compilar desde el código

El entorno de compilación requiere JDK 11 y Node.js 20 para generar los bundles de diagnóstico.

```bash
git clone https://github.com/Tinchodoko/Fridagate.git
cd Fridagate
chmod +x gradlew
./gradlew assembleDebug
```

APK de depuración:

```
app/build/outputs/apk/debug/app-debug.apk
```

Los pasos de compilación automatizados se encuentran en [`.github/workflows/build.yml`](.github/workflows/build.yml). El workflow construye los agentes de diagnóstico, compila el APK y publica artefactos; las publicaciones automáticas se generan en pushes exitosos a `main`.

## Número de build y actualizaciones

- En GitHub Actions, cada ejecución tiene un número de build.
- El proyecto utiliza `GITHUB_RUN_NUMBER` para identificar la ejecución y generar el `versionCode` de Android.
- Las publicaciones automáticas se etiquetan como `build-N`.
- Las builds locales se identifican como `LOCAL` en el valor de diagnóstico correspondiente.
- Consulta [Actions](https://github.com/Tinchodoko/Fridagate/actions) para ver el historial y [Releases](https://github.com/Tinchodoko/Fridagate/releases) para ver las builds publicadas.

El número de build no es lo mismo que la versión funcional de la aplicación. La versión declarada actualmente en Gradle es `2.0.0`; el número de ejecución de CI cambia con cada build.

## Estructura del proyecto

- `app/src/main/java/`: aplicación Android, interfaz y lógica.
- `app/src/main/java/com/hackpuntes/fridagate/ui/terminal/`: terminal integrada.
- `app/src/main/java/com/hackpuntes/fridagate/ui/about/`: pantalla de información y navegador integrado.
- `app/src/main/assets/scripts/`: scripts de diagnóstico empaquetados.
- `tools/`: fuentes y configuración de los agentes de diagnóstico que se generan durante la compilación.
- `.github/workflows/build.yml`: compilación, verificación y publicación de APK.

### Nota sobre nombres internos

El identificador de aplicación y algunos paquetes de código conservan el espacio de nombres heredado `com.hackpuntes.fridagate`. Se mantienen deliberadamente para evitar romper la identidad de instalación y las actualizaciones de la app. Esto no significa que la interfaz actual conserve la descripción o las funciones antiguas.

## Uso responsable

Usa la herramienta únicamente en dispositivos, aplicaciones y entornos que sean tuyos o para los que tengas autorización explícita. La instrumentación dinámica, los comandos root y los scripts pueden alterar el comportamiento de la aplicación objetivo o del dispositivo. Revisa cada script antes de ejecutarlo y conserva copias de los datos importantes.

## Licencia y atribución

El fork se distribuye bajo la licencia MIT. Consulta [LICENSE](LICENSE) para ver el texto completo y los avisos de copyright del proyecto original y de las modificaciones del fork.

FridaGate 2.0 reconoce el trabajo previo de Javier Olmedo en el proyecto original. Las modificaciones, la integración y el mantenimiento de este fork corresponden a Tinchodoko.

## Soporte y contribuciones

- [Reportar problemas o proponer mejoras](https://github.com/Tinchodoko/Fridagate/issues)
- [Ver el código fuente](https://github.com/Tinchodoko/Fridagate)
- [Ver compilaciones](https://github.com/Tinchodoko/Fridagate/actions)
- [Ver publicaciones](https://github.com/Tinchodoko/Fridagate/releases)

Al informar un problema, incluye el número de build, versión de Android, arquitectura del dispositivo, versión de Frida y los logs relevantes. No publiques claves, tokens, contraseñas ni datos privados.
