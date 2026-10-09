# 📋 INSTRUCCIONES DE INTEGRACIÓN - Fridagate Script Manager

## 🎯 Objetivo
Agregar un **Script Manager completo** al fork de Fridagate con:
- ✅ File Manager (Crear/Leer/Editar/Borrar .js)
- ✅ Editor integrado con syntax hints
- ✅ Ejecución de scripts en procesos objetivo
- ✅ Logs en tiempo real + exportación
- ✅ Soporte frida-il2cpp-bridge

---

## 📁 Estructura de Carpetas

Tu fork actual está en:
```
Tinchodoko/Fridagate/
├── app/src/main/kotlin/com/hackpuntes/fridagate/
│   ├── data/
│   ├── ui/
│   └── core/
```

---

## 🔧 PASO 1: Agregar los archivos Kotlin

Copia estos 5 archivos a las siguientes ubicaciones:

### 1️⃣ **FridaScript.kt** → Modelo de datos
```
app/src/main/kotlin/com/hackpuntes/fridagate/data/models/FridaScript.kt
```

### 2️⃣ **ScriptRepository.kt** → CRUD de archivos
```
app/src/main/kotlin/com/hackpuntes/fridagate/data/repository/ScriptRepository.kt
```

### 3️⃣ **ScriptsViewModel.kt** → Estado reactivo
```
app/src/main/kotlin/com/hackpuntes/fridagate/ui/viewmodels/ScriptsViewModel.kt
```

### 4️⃣ **ScriptsScreen.kt** → UI Compose
```
app/src/main/kotlin/com/hackpuntes/fridagate/ui/screens/scripts/ScriptsScreen.kt
```

### 5️⃣ **FridaInjectionService.kt** → Inyección Frida
```
app/src/main/kotlin/com/hackpuntes/fridagate/core/frida/FridaInjectionService.kt
```

---

## 🔌 PASO 2: Agregar dependencias a `build.gradle.kts`

En la raíz de tu proyecto, abre:
```
build.gradle.kts
```

Asegúrate de que tenga estas dependencias (probablemente ya las tiene):

```kotlin
dependencies {
    // Compose (debe estar)
    implementation("androidx.compose.ui:ui:1.6.0")
    implementation("androidx.compose.material3:material3:1.1.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.1")
    
    // DataStore para persistencia
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
```

Si falta alguna, agrégala. Luego haz clic en "Sync Now".

---

## 📍 PASO 3: Integrar en la navegación existente

Tu app probablemente tiene un `MainActivity.kt` o una composición de navegación.

Busca el archivo que tenga la navegación (puede ser `NavGraph.kt` o `MainActivity.kt`):

### Agregá esto en el ViewModel del Dashboard:

```kotlin
// En DashboardViewModel o donde guardes el estado global
val scriptsViewModel: ScriptsViewModel by lazy {
    ScriptsViewModelFactory(context).create(ScriptsViewModel::class.java)
}
```

### Agregá un botón en tu Dashboard Screen:

```kotlin
// En DashboardScreen.kt

var showScriptManager by remember { mutableStateOf(false) }

if (showScriptManager) {
    ScriptsScreen(
        viewModel = scriptsViewModel,
        onBack = { showScriptManager = false }
    )
} else {
    // Dashboard existente
    Button(
        onClick = { showScriptManager = true },
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Icon(Icons.Default.Code, null, modifier = Modifier.padding(end = 8.dp))
        Text("📝 Script Manager")
    }
}
```

---

## 🚀 PASO 4: Conectar con Frida (Configuración)

En el `MainActivity` o donde inicialices la app, agrega:

```kotlin
// Variables globales o en un AppContainer
val fridaInjectionService = FridaInjectionService(
    context = applicationContext,
    fridaHost = "localhost",
    fridaPort = 27042,
    logCallback = { log ->
        // Pasar logs al ViewModel
        scriptsViewModel.addLog(log)
    }
)
```

---

## 📱 PASO 5: Configurar permiso de escritura

En `AndroidManifest.xml`, asegúrate de tener:

```xml
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.INTERNET" />
```

---

## 🔄 PASO 6: Compilar y Testear

**Opción A: Con Android Studio (si lo instalas después)**
```bash
./gradlew build
./gradlew installDebug
```

**Opción B: Sin Android Studio - Compilar directamente**

En la carpeta raíz de tu fork:

```bash
# En Linux/Mac
./gradlew build

# En Windows
gradlew.bat build
```

Si no tienes gradlew, descarga el proyecto con:
```bash
git clone https://github.com/Tinchodoko/Fridagate.git
cd Fridagate
```

---

## 🧪 PASO 7: Testear el Script Manager en tu Moto G15

1. **Instala la app compilada:**
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

2. **Asegúrate de que Frida Server esté corriendo:**
   ```bash
   adb shell /data/local/tmp/frida-server -D
   ```

3. **Abre Fridagate en el teléfono**

4. **Presiona "📝 Script Manager"**

5. **Crea tu primer script:**
   - Nombre: "Hello Frida"
   - Presiona "📄 Básico" para cargar un template
   - Presiona "💾 Guardar"

6. **Prueba la ejecución:**
   - Ve a la pestaña "Logs"
   - Presiona "▶️ Ejecutar"
   - Deberías ver logs en la pantalla

---

## 🐛 Troubleshooting

### ❌ "Error de compilación: No se encuentra FridaScript"
**Solución:** Verifica que el paquete en el archivo .kt coincida:
```kotlin
package com.hackpuntes.fridagate.data.models
```

### ❌ "Cannot connect to Frida"
**Solución:** Verifica que Frida server esté corriendo:
```bash
adb shell "ps aux | grep frida-server"
```

### ❌ "Permission denied when writing scripts"
**Solución:** La app crea automáticamente `/data/data/app/files/frida_scripts/`. Si no funciona, verifica:
```bash
adb shell "ls -la /data/data/com.hackpuntes.fridagate/files/"
```

---

## 📝 Próximos Pasos Opcionales

### Mejorar la ejecución (requiere Frida CLI):
```bash
pip install frida-tools
frida-ps -U  # Listar procesos
```

### Agregar soporte para scripts con argumentos:
Modificar `FridaInjectionService.kt` para pasar parámetros.

### Agregar guardado en la nube:
Integrar con Google Drive o Firebase.

---

## 🎯 ¿Necesitas ayuda?

Si hay errores de compilación o integración:

1. **Comparte el error exacto** (pantalla de compilación)
2. **Verifica las rutas de paquetes** en tus archivos
3. **Confirma que el `build.gradle.kts` tiene las dependencias correctas**

---

**¡Listo para empezar! 🚀**
