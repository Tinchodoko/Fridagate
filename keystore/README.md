# Firma de APK — FridaGate 2.0

Esta carpeta contiene las instrucciones para crear una clave de firma local para builds de FridaGate 2.0. **Nunca subas claves privadas, archivos de keystore ni contraseñas al repositorio.** Los archivos `.jks` y `.keystore` están excluidos por `.gitignore`.

## Crear un keystore local

Ejecuta el siguiente comando en un entorno de desarrollo de confianza y guarda la contraseña en un gestor seguro:

```bash
keytool -genkey -v \
  -keystore fridagate-release.jks \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -alias fridagate
```

También puedes usar **Android Studio → Build → Generate Signed Bundle / APK → Create new keystore**.

## Nombre de archivo y alias

| Elemento | Valor |
|---|---|
| Archivo local sugerido | `keystore/fridagate-release.jks` |
| Alias de clave sugerido | `fridagate` |
| Control de versiones | No se deben subir el keystore ni las credenciales |

El nombre de archivo y el alias se conservan por compatibilidad con la configuración existente. No los cambies sin revisar primero los workflows de firma y la configuración local.

## Seguridad y builds de GitHub Actions

- No pegues contraseñas ni claves en issues, comentarios, commits o logs.
- No subas el archivo de keystore a GitHub.
- Las builds de depuración automatizadas usan el mecanismo de firma configurado en el workflow. Esta guía no significa que exista una clave de publicación configurada.
- Guarda una copia segura del keystore y sus credenciales fuera del repositorio si lo necesitas para firmar builds de manera consistente.
