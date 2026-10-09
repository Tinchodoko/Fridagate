# 🚀 QUICKSTART - Script Manager para Fridagate

## ¿Qué tienes?
- ✅ 5 archivos Kotlin listos para copiar
- ✅ Script bash para automatizar el push a GitHub
- ✅ Instrucciones de integración detalladas

## ¿Sin Android Studio? Sin problema

### **OPCIÓN A: Usar Git + GitHub Web (Recomendado)**

#### Paso 1: Clonar tu fork localmente
```bash
git clone https://github.com/Tinchodoko/Fridagate.git
cd Fridagate
```

#### Paso 2: Crear rama de features
```bash
git checkout -b feature/script-manager
```

#### Paso 3: Copiar archivos

**En Linux/Mac:**
```bash
# Copiar los archivos a sus ubicaciones correctas
cp FridaScript.kt app/src/main/kotlin/com/hackpuntes/fridagate/data/models/
cp ScriptRepository.kt app/src/main/kotlin/com/hackpuntes/fridagate/data/repository/
cp ScriptsViewModel.kt app/src/main/kotlin/com/hackpuntes/fridagate/ui/viewmodels/
cp ScriptsScreen.kt app/src/main/kotlin/com/hackpuntes/fridagate/ui/screens/scripts/
cp FridaInjectionService.kt app/src/main/kotlin/com/hackpuntes/fridagate/core/frida/
```

**En Windows (Command Prompt):**
```bash
REM Crear carpetas si no existen
mkdir app\src\main\kotlin\com\hackpuntes\fridagate\data\models
mkdir app\src\main\kotlin\com\hackpuntes\fridagate\data\repository
mkdir app\src\main\kotlin\com\hackpuntes\fridagate\ui\viewmodels
mkdir app\src\main\kotlin\com\hackpuntes\fridagate\ui\screens\scripts
mkdir app\src\main\kotlin\com\hackpuntes\fridagate\core\frida

REM Copiar archivos
copy FridaScript.kt app\src\main\kotlin\com\hackpuntes\fridagate\data\models\
copy ScriptRepository.kt app\src\main\kotlin\com\hackpuntes\fridagate\data\repository\
copy ScriptsViewModel.kt app\src\main\kotlin\com\hackpuntes\fridagate\ui\viewmodels\
copy ScriptsScreen.kt app\src\main\kotlin\com\hackpuntes\fridagate\ui\screens\scripts\
copy FridaInjectionService.kt app\src\main\kotlin\com\hackpuntes\fridagate\core\frida\
```

#### Paso 4: Hacer commit y push
```bash
git add .
git commit -m "feat: Add script manager with editor and logs"
git push origin feature/script-manager
```

#### Paso 5: Crear Pull Request
- Ve a https://github.com/Tinchodoko/Fridagate
- Verás un botón "Compare & pull request"
- Abre el PR contra `main`

---

### **OPCIÓN B: GitHub CLI (más rápido)**

Si tienes `gh` (GitHub CLI) instalado:

```bash
# Autenticarte
gh auth login

# Clonar y cambiar rama
gh repo clone Tinchodoko/Fridagate
cd Fridagate
git checkout -b feature/script-manager

# Copiar archivos (ver paso 3 arriba)

# Commit y push
git add .
git commit -m "feat: Add script manager"
gh pr create --title "Add script manager" --body "Script editor, execution, and logs"
```

---

### **OPCIÓN C: Usar el script bash (automático)**

```bash
# Necesitas un GitHub Personal Access Token
# 1. Ve a https://github.com/settings/tokens/new
# 2. Selecciona "repo" (acceso completo)
# 3. Copia el token

# Luego ejecuta:
bash push_to_github.sh ghp_XXXXXXXXXXXX Tinchodoko Fridagate
```

---

## ✅ Después: Compilar y Testear

Una vez que los archivos estén en GitHub, necesitarás compilar:

### Opción 1: Android Studio (Recomendado)
```
1. Descarga: https://developer.android.com/studio
2. File → Open → Tu carpeta Fridagate
3. Espera a que sincronice
4. Build → Build Bundle(s) / APK(s)
5. Instala en tu Moto G15 con: adb install app-debug.apk
```

### Opción 2: Compilar sin Android Studio (Gradle CLI)
```bash
# En la carpeta raíz de Fridagate
./gradlew build

# Generar APK
./gradlew assembleDebug

# Instalar en el teléfono
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## 🧪 Testear en tu Moto G15

1. **Abre Fridagate en el teléfono**
2. **Presiona "📝 Script Manager"**
3. **Pestaña "Scripts" → "Nuevo Script"**
4. **Presiona "📄 Básico" para cargar template**
5. **Presiona "💾 Guardar"**
6. **Pestaña "Logs" → "▶️ Ejecutar"**
7. **Deberías ver logs de ejecución**

---

## 📋 Checklist Final

- [ ] Archivos copiados a las carpetas correctas
- [ ] `build.gradle.kts` tiene las dependencias Compose
- [ ] Código compilado sin errores
- [ ] APK generado exitosamente
- [ ] APK instalado en Moto G15
- [ ] Frida server corriendo: `adb shell /data/local/tmp/frida-server -D`
- [ ] Script Manager abre desde el Dashboard
- [ ] Puedes crear/editar/guardar scripts
- [ ] Los logs aparecen en la pestaña Logs

---

## 🆘 Ayuda Rápida

### Error: "No se encuentra FridaScript"
→ Verifica el paquete: `package com.hackpuntes.fridagate.data.models`

### Error: "Gradle sync failed"
→ Ve a Android Studio → Sync → Try Again

### Error: "Cannot connect to Frida"
→ Asegúrate: `adb shell /data/local/tmp/frida-server -D`

### APK no instala
→ Desinstala la versión anterior: `adb uninstall com.hackpuntes.fridagate`

---

**¿Listo? ¡Empecemos! 🎯**

Próximo paso: Ejecuta el Paso 3 (Copiar archivos) y reporta cualquier error.
