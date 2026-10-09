# 🪝 Fridagate

**Herramienta Android de pentesting con Frida** | Gestor de scripts, inyección de código y análisis de logs en tiempo real

[![GitHub](https://img.shields.io/badge/GitHub-Tinchodoko/Fridagate-181717?logo=github)](https://github.com/Tinchodoko/Fridagate)
[![License](https://img.shields.io/badge/License-GPLv3-green)](#licencia)
[![Android](https://img.shields.io/badge/Android-8.0+-green?logo=android)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9+-purple?logo=kotlin)]()

---

## 📖 Descripción

**Fridagate** es una aplicación Android nativa desarrollada en **Kotlin + Jetpack Compose** que proporciona una interfaz gráfica completa para trabajar con [Frida](https://frida.re) en pentesting móvil.

Pensada para investigadores de seguridad, desarrolladores y profesionales de análisis dinámico que necesitan:
- ✅ Crear, editar y ejecutar scripts Frida directamente en el dispositivo
- ✅ Inyectar código en procesos objetivo sin reiniciar
- ✅ Capturar y exportar logs en tiempo real
- ✅ Soporte completo para **IL2CPP** (juegos y apps con Mono/Unity)
- ✅ Gestión de almacenamiento persistente

---

## 🚀 Características

### 📝 Gestor de Scripts
- **Crear**: Genera nuevos scripts Frida desde templates predefinidos
- **Editar**: Editor de código completo con syntax highlighting
- **Ejecutar**: Inyecta scripts en procesos vivos sin reiniciar
- **Guardar**: Almacenamiento persistente en `/Documentos/Fridagate2.0/Scripts/`
- **Importar**: Carga scripts .js desde el almacenamiento externo
- **Exportar**: Descarga scripts y logs en formato TXT

### 🛡️ Scripts Predefinidos
- **Root Detection Bypass**: Evita detecciones de root
- **SSL Pinning Bypass**: Intercepta certificados pinned para análisis HTTPS

### 🔗 Soporte IL2CPP
Detección automática y soporte para juegos/apps basadas en:
- Mono/.NET Framework
- Unity Engine
- Aplicaciones IL2CPP compiladas

### 📊 Sistema de Logs
- Captura en tiempo real con timestamps
- Colorización automática (errores, advertencias, info)
- Exportación a TXT para análisis posterior
- Limpieza manual de logs

### 📱 Target App Selector
- Lista de aplicaciones instaladas
- Selección de PID objetivo
- Integración con ADB para gestión remota

---

## 🛠️ Requisitos

### Hardware
- **Dispositivo Android 8.0+** con acceso root
- **Frida server** compilado para ARM/ARM64
- Conexión ADB local o remota

### Software
- **Android Studio** 4.0+
- **Kotlin** 1.9+
- **Jetpack Compose** latest
- **JDK 11+**

### Permisos Android
La app requiere:
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
```

---

## 💻 Instalación

### 1️⃣ Compilación desde código

```bash
# Clonar el repositorio
git clone https://github.com/Tinchodoko/Fridagate.git
cd Fridagate

# Compilar APK debug
./gradlew assembleDebug

# APK generado en: app/build/outputs/apk/debug/app-debug.apk
```

### 2️⃣ Instalación en dispositivo

```bash
# Conectar dispositivo via ADB
adb devices

# Instalar APK
adb install app-debug.apk

# O con GitHub Actions (compilación automática)
# Descarga el APK desde Artifacts
```

### 3️⃣ Configurar Frida Server

```bash
# En tu PC (servidor Frida)
frida-server -l 0.0.0.0:27042 &

# En el dispositivo con root
# Asegúrate de que Frida está corriendo:
su
frida-server -l 127.0.0.1:27042 &
```

### 4️⃣ Conectar dispositivo

```bash
# Forwarding local
adb forward tcp:27042 tcp:27042

# O configurar conexión remota en la app
```

---

## 📚 Guía de Uso

### Crear un Script

1. Abre pestaña **Scripts**
2. Presiona **+ Nuevo Script**
3. Nombra tu script
4. Ve a pestaña **Editor**
5. Elige un template o escribe código Frida
6. Presiona **💾 Guardar**

### Ejecutar un Script

1. Selecciona el target app en **📱 Target App**
2. Selecciona tu script de la lista
3. Ve a pestaña **Logs**
4. Presiona **▶️ Ejecutar**
5. Monitorea logs en tiempo real

### Exportar Resultados

1. En pestaña **Logs**, presiona **💾 Exportar**
2. Se guardará en: `/Documentos/Fridagate2.0/Logs/`
3. Comparte o analiza offline

---

## 🧬 Estructura del Proyecto

```
Fridagate/
├── app/src/main/kotlin/com/hackpuntes/fridagate/
│   ├── data/
│   │   ├── models/FridaScript.kt
│   │   └── repository/ScriptRepository.kt
│   ├── core/
│   │   ├── FridaInjectionService.kt
│   │   └── FilePickerHelper.kt
│   ├── ui/
│   │   ├── viewmodels/ScriptsViewModel.kt
│   │   └── screens/
│   │       ├── scripts/ScriptsScreen.kt
│   │       └── AboutScreen.kt
│   └── MainActivity.kt
├── app/src/main/AndroidManifest.xml
├── build.gradle.kts
├── .github/workflows/build.yml (CI/CD)
└── README.md
```

---

## 📦 Almacenamiento de Scripts

### Estructura de directorios

```
/Documentos/Fridagate2.0/
├── Scripts/
│   ├── script_1.js
│   ├── script_1.meta
│   ├── script_2.js
│   ├── script_2.meta
│   └── ...
└── Logs/
    ├── export_20241009_120530.txt
    └── ...
```

### Archivo .meta (Metadatos)

```
name=Mi Script
description=Detecta root en la app
createdAt=1728516000000
updatedAt=1728516000000
supportsIL2CPP=true
tags=bypass,root,security
```

---

## 🔧 Ejemplos de Scripts

### Script Básico (Detección de Frida)

```javascript
// Detección de Frida en tiempo real
if (Process.arch === 'arm64' || Process.arch === 'arm') {
    console.log('[*] Arquitectura: ' + Process.arch);
    
    // Buscar módulos sospechosos
    Module.enumerateModules().forEach(function(module) {
        if (module.name.includes('frida')) {
            console.log('[!] ⚠️ Detectado: ' + module.name);
        }
    });
}
```

### Script IL2CPP

```javascript
// Soporte para frida-il2cpp-bridge
if (typeof Il2Cpp !== 'undefined') {
    console.log('[*] IL2CPP Bridge detectado');
    
    const app = Il2Cpp.Image.findClassByName('Assembly-CSharp', 'MyApp');
    if (app) {
        console.log('[+] Clase encontrada: MyApp');
        const method = app.method('IsRoot');
        if (method) {
            method.implementation = function() {
                console.log('[!] IsRoot() llamado - retornando false');
                return false;
            };
        }
    }
} else {
    console.log('[-] IL2CPP Bridge no disponible');
}
```

### Bypass de Root Detection

```javascript
// Template predefinido: Root Detection Bypass
function bypassRootDetection() {
    // Buscar métodos de verificación de root comunes
    const methods = [
        'isDeviceRooted',
        'checkRoot',
        'isRoot',
        'hasRoot',
        'detectRoot'
    ];
    
    methods.forEach(method => {
        try {
            const target = Java.use('java.lang.ProcessBuilder');
            console.log('[+] Hooked: ' + method);
        } catch(e) {
            // Ignorar si no existe
        }
    });
}

bypassRootDetection();
console.log('[✓] Root Detection Bypass activo');
```

---

## 🔌 Integración con Frida

### Arquitectura de Inyección

```
Fridagate (Android App)
    ↓
FridaInjectionService (Socket localhost:27042)
    ↓
Frida Server (root)
    ↓
Proceso Objetivo
```

### Conexión Automática
La app intenta conexión en este orden:
1. Socket local (127.0.0.1:27042) — Recomendado
2. Fallback a shell script (rooted device)
3. Error si Frida no está disponible

---

## 📊 Captura de Pantallas

### Pestaña Scripts
- Selector de Target App en la parte superior
- Lista de scripts creados
- Scripts predefinidos (toggles activables)
- Botones: Nuevo Script, Importar .js

### Pestaña Editor
- Editor de código con syntax highlighting
- Templates predefinidos (Básico, IL2CPP)
- Guardado automático de metadatos

### Pestaña Logs
- Captura en tiempo real con timestamps
- Colores: ✅ Verde (éxito), ❌ Rojo (error), ⚠️ Amarillo (warn)
- Exportar a TXT
- Limpiar logs

---

## 🐛 Troubleshooting

### "Frida Server no responde"
```bash
# En el dispositivo (root):
su
frida-server -l 127.0.0.1:27042 &

# En tu PC:
adb forward tcp:27042 tcp:27042
```

### "Permisos de almacenamiento rechazados"
1. Abre Configuración → Aplicaciones → Fridagate
2. Permisos → Archivos: Permitir
3. Reinicia la app

### "Script no inyecta en la app"
1. Verifica que el Target App esté seleccionado
2. Comprueba que el proceso está vivo: `adb shell ps | grep -i "app_name"`
3. Asegúrate de que Frida Server está corriendo

### "IL2CPP Bridge no detectado"
- Solo funciona en apps con IL2CPP compilado
- Requiere frida-il2cpp-bridge en el servidor Frida
- Verifica: `pip install frida-il2cpp-bridge`

---

## 🤝 Contribuir

### Fork + Pull Request
```bash
# 1. Fork el repositorio
# 2. Crea una rama:
git checkout -b feature/tu-feature

# 3. Commit cambios:
git commit -m "Add: descripción clara del cambio"

# 4. Push:
git push origin feature/tu-feature

# 5. Abre Pull Request
```

### Reporte de Bugs
Abre un issue en: https://github.com/Tinchodoko/Fridagate/issues

---

## 📜 Licencia

Este proyecto está bajo licencia **GPLv3**. Lee el archivo [LICENSE](LICENSE) para más detalles.

```
Fridagate - Android Frida GUI
Copyright (C) 2024 Tinchodoko

Este software es libre: puedes redistribuirlo y/o modificarlo bajo los 
términos de la Licencia Pública General GNU tal como está publicada por la 
Free Software Foundation, ya sea la versión 3 de la Licencia o cualquier 
versión posterior.
```

---

## 🙏 Créditos

### Trabajo Original
**Javier Olmedo** - Creador original de Fridagate  
Repositorio: https://github.com/JavierOlmedo/Fridagate

### Fork Actual
**Tinchodoko** - Mejoras, Script Manager, IL2CPP support  
Repositorio: https://github.com/Tinchodoko/Fridagate

### Tecnologías
- [Frida](https://frida.re) - Dynamic Instrumentation Toolkit
- [Kotlin](https://kotlinlang.org) - Language
- [Jetpack Compose](https://developer.android.com/jetpack/compose) - UI Framework
- [Android NDK](https://developer.android.com/ndk) - Native Development Kit

---

## 📞 Contacto & Soporte

- **GitHub Issues**: [Reportar bug](https://github.com/Tinchodoko/Fridagate/issues)
- **GitHub Discussions**: [Q&A](https://github.com/Tinchodoko/Fridagate/discussions)
- **Owner Original**: [@JavierOlmedo](https://github.com/JavierOlmedo)

---

## ⚠️ Disclaimer Legal

**Fridagate** es una herramienta de pentesting. Su uso está restringido a:
- Testing de aplicaciones **de tu propiedad**
- Análisis de seguridad **autorizado**
- Investigación académica **con consentimiento**

**El uso no autorizado de esta herramienta es ilegal.** El autor no es responsable de usos maliciosos o ilegales.

---

## 📅 Changelog

### v2.0 (Actual)
- ✅ Script Manager completo (CRUD)
- ✅ Almacenamiento en `/Fridagate2.0/Scripts/`
- ✅ Importación de scripts .js
- ✅ Toggles para scripts predefinidos
- ✅ Target App selector mejorado
- ✅ Logs exportables a TXT
- ✅ Soporte IL2CPP bridge
- ✅ UI rediseñada en Jetpack Compose

### v1.0 (Original)
- Interfaz básica de Frida
- Ejecución de scripts simple
- Captura de output

---

**Hecho en Argentina 🇦🇷 por Tinchodoko 👿**

*Última actualización: Octubre 2024*
