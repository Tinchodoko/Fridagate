# Inicio rápido — FridaGate 2.0

Esta guía corresponde al estado actual del proyecto. Para el inventario de funciones, requisitos, estructura y licencia, consulta el [README](README.md).

## Obtener una APK

1. Abre [GitHub Actions](https://github.com/Tinchodoko/Fridagate/actions) y elige una ejecución exitosa de **Build FridaGate 2.0 APK**.
2. Descarga el artefacto `app-debug`, o revisa [Releases](https://github.com/Tinchodoko/Fridagate/releases) para las publicaciones automáticas con etiqueta `build-N`.
3. Instala el APK en tu dispositivo Android. Puede ser necesario autorizar la instalación desde la fuente elegida.

## Compilar

Se requiere JDK 11 y Node.js 20 para generar los agentes de diagnóstico.

```bash
git clone https://github.com/Tinchodoko/Fridagate.git
cd Fridagate
chmod +x gradlew
./gradlew assembleDebug
```

APK generado: `app/build/outputs/apk/debug/app-debug.apk`.

## Primeras comprobaciones

1. Abre FridaGate 2.0 y revisa el estado del dispositivo y del entorno de Frida.
2. Comprueba que frida-server sea compatible con la arquitectura y la versión instalada.
3. Selecciona una aplicación de prueba autorizada y revisa los diagnósticos disponibles.
4. Importa o crea un script JavaScript y léelo antes de ejecutarlo.
5. Revisa los logs; si necesitas comandos del dispositivo, utiliza la terminal integrada con precaución.
6. Para abrir páginas de ayuda, utiliza el navegador integrado cuando esté disponible.

## Número de build

Las builds de GitHub Actions se identifican mediante el número de ejecución y las publicaciones usan etiquetas `build-N`. Consulta el historial de [Actions](https://github.com/Tinchodoko/Fridagate/actions) para encontrar una compilación concreta.

## Ayuda

Si algo falla, informa el número de build, versión de Android, arquitectura, versión de Frida y el mensaje de error exacto. No incluyas secretos ni información privada en los logs compartidos.
