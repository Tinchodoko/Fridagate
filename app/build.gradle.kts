plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hackpuntes.fridagate"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.hackpuntes.fridagate"
        minSdk = 24
        targetSdk = 36
        // Each GitHub Actions build gets a higher versionCode so Android can install it
        // as an update. Local builds use 4 as a safe starting value above the original v2.0.0.
        versionCode = providers.environmentVariable("GITHUB_RUN_NUMBER")
            .orNull
            ?.toIntOrNull()
            ?.plus(3)
            ?: 4
        versionName = "2.0.0"
        buildConfigField(
            "String",
            "BUILD_NUMBER",
            "\"${providers.environmentVariable("GITHUB_RUN_NUMBER").orNull ?: "LOCAL"}\""
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // --- Core Android ---
    // Basic Kotlin extensions for Android (adds useful shortcuts)
    implementation(libs.androidx.core.ktx)
    // Lifecycle-aware coroutine scope (auto-cancels when activity is destroyed)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Lets us use Compose inside an Activity
    implementation(libs.androidx.activity.compose)

    // --- Jetpack Compose ---
    // BOM = Bill of Materials: manages all Compose library versions automatically
    // so we don't have to specify a version number for each Compose library
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui) // Core UI components
    implementation(libs.androidx.compose.ui.graphics) // Drawing/graphics support
    implementation(libs.androidx.compose.ui.tooling.preview) // Preview in Android Studio
    implementation(libs.androidx.compose.material3) // Material Design 3 components

    // Material Icons Extended: includes ALL Material icons (Refresh, KeyboardArrowDown, etc.)
    // This already contains material-icons-core, so we only need this one dependency
    implementation("androidx.compose.material:material-icons-extended")

    // --- Navigation ---
    // Handles navigation between screens (tabs) in Compose
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // --- ViewModel ---
    // ViewModel survives screen rotations and holds UI state
    // The -compose variant adds special Compose integration
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    // --- Coroutines ---
    // Allows running async code (network, file I/O) without blocking the UI thread
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // --- Networking ---
    // OkHttp: HTTP client used to download files and call APIs
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // --- JSON Parsing ---
    // Gson: converts JSON strings into Kotlin data classes and vice versa
    implementation("com.google.code.gson:gson:2.10.1")

    // --- Compression ---
    // XZ: decompresses .xz files (Frida server binaries are distributed as .xz)
    implementation("org.tukaani:xz:1.9")

    // --- Persistent Storage ---
    // DataStore: modern replacement for SharedPreferences
    // Used to save app settings across restarts
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // --- Testing ---
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// --- Frida agent bundle ---
// Builds tools/unity-diagnostics (esbuild) into app/src/main/assets/scripts/ before every build,
// so FridaGate_Unity_IL2CPP_Diagnostics.bundle.js always exists (local builds included).
val unityDiagnosticsDir = rootProject.layout.projectDirectory.dir("tools/unity-diagnostics")
val isWindows = System.getProperty("os.name").lowercase().contains("win")

val buildUnityDiagnostics by tasks.registering(Exec::class) {
    group = "frida"
    description = "Bundles the Unity IL2CPP Frida agent with esbuild"
    workingDir = unityDiagnosticsDir.asFile
    val cmd = "npm install --no-audit --no-fund && npm run build"
    if (isWindows) commandLine("cmd", "/c", cmd) else commandLine("sh", "-c", cmd)
    inputs.files(
        unityDiagnosticsDir.file("index.ts"),
        unityDiagnosticsDir.file("package.json"),
        unityDiagnosticsDir.file("tsconfig.json")
    )
    outputs.file(layout.projectDirectory.file("src/main/assets/scripts/FridaGate_Unity_IL2CPP_Diagnostics.bundle.js"))
}

val runtimeDiagnosticsDir = rootProject.layout.projectDirectory.dir("tools/runtime-diagnostics")

val buildRuntimeDiagnostics by tasks.registering(Exec::class) {
    group = "frida"
    description = "Bundles runtime diagnostics with frida-java-bridge"
    workingDir = runtimeDiagnosticsDir.asFile
    val cmd = "npm install --no-audit --no-fund && npm run build"
    if (isWindows) commandLine("cmd", "/c", cmd) else commandLine("sh", "-c", cmd)
    inputs.files(
        runtimeDiagnosticsDir.file("index.ts"),
        runtimeDiagnosticsDir.file("package.json")
    )
    outputs.file(layout.projectDirectory.file("src/main/assets/scripts/FridaGate_Runtime_Diagnostics.bundle.js"))
}

tasks.named("preBuild") {
    dependsOn(buildUnityDiagnostics)
    dependsOn(buildRuntimeDiagnostics)
}
