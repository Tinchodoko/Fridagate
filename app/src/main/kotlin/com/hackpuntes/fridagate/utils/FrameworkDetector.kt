package com.hackpuntes.fridagate.utils

import android.content.Context
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Detector estático de tecnologías a partir de los nombres de archivos del APK
 * y de cadenas visibles dentro de classes*.dex. No ejecuta la aplicación.
 */
data class FrameworkInfo(
    val name: String,
    val badge: String,
    val category: String,
    val confidence: String = "media",
    val evidence: List<String> = emptyList()
)

object FrameworkDetector {
    private const val MAX_DEX_BYTES = 12L * 1024L * 1024L

    private data class Signature(
        val name: String,
        val badge: String,
        val category: String,
        val markers: List<String>,
        val threshold: Int = 55
    )

    // Firmas inspiradas en detectores públicos de APK. Los indicadores se pueden ampliar
    // sin incorporar un motor externo pesado a la aplicación Android.
    private val signatures = listOf(
        Signature("Flutter", "F", "flutter",
            listOf("libflutter.so", "assets/flutter_assets/", "io.flutter", "flutter_assets/kernel_blob.bin")),
        Signature("React Native", "RN", "react",
            listOf("libreactnativejni.so", "assets/index.android.bundle", "com.facebook.react", "libhermes.so", "libjscexecutor.so")),
        Signature("Cordova / JavaScript", "JS", "javascript",
            listOf("assets/www/cordova.js", "assets/www/cordova_plugins.js", "org.apache.cordova")),
        Signature("Capacitor / JavaScript", "JS", "javascript",
            listOf("assets/capacitor.config.json", "assets/public/capacitor.js", "com.getcapacitor")),
        Signature("Ionic", "I", "javascript",
            listOf("ionic.bundle.js", "assets/www/build/", "@ionic")),
        Signature("Unreal Engine", "UE", "unreal",
            listOf("libue4.so", "libunreal.so", "libue5.so", "ue4game/", "ue5game/", "globalshadercache")),
        Signature("Godot", "G", "godot",
            listOf("libgodot_android.so", "libgodot.so", "org.godotengine", "godotengine")),
        Signature("Cocos2d-x", "C2", "cocos",
            listOf("libcocos2dcpp.so", "libcocos2djs.so", "org.cocos2dx")),
        Signature("libGDX", "GX", "libgdx",
            listOf("com/badlogic/gdx/", "com.badlogic.gdx", "libgdx.so")),
        Signature("Xamarin / .NET MAUI", ".N", "dotnet",
            listOf("libmonodroid.so", "libxamarin-app.so", "assemblies/", "libmonosgen-2.0.so", "microsoft.maui")),
        Signature("NativeScript", "NS", "nativescript",
            listOf("org.nativescript", "libnativescript.so", "tns_modules/")),
        Signature("Qt for Android", "Qt", "qt",
            listOf("libqt6core.so", "libqt5core.so", "assets/qt/", "org.qtproject")),
        Signature("Kivy / Python", "Py", "python",
            listOf("libpython3.", "python-for-android", "org.kivy")),
        Signature("Solar2D", "S2", "solar2d",
            listOf("libcorona.so", "com.ansca.corona", "resource.car")),
        Signature("Defold", "D", "defold",
            listOf("libdlib.so", "dmengine", "game.project")),
        Signature("Adobe AIR", "AIR", "air",
            listOf("com.adobe.air", "application.xml", "libstagefright_android.so"))
    )

    fun detect(context: Context, packageName: String): FrameworkInfo {
        val appInfo = try {
            context.packageManager.getApplicationInfo(packageName, 0)
        } catch (_: Exception) {
            return FrameworkInfo("No se pudo analizar", "?", "unknown", "baja")
        }

        val apkPaths = buildList<String> {
            appInfo.sourceDir?.takeIf { it.isNotBlank() }?.let { add(it) }
            appInfo.splitSourceDirs?.filterNotNull()?.forEach { add(it) }
        }.distinct()

        val entries = linkedSetOf<String>()
        val dexStrings = StringBuilder()

        for (path in apkPaths) {
            try {
                ZipFile(path).use { zip ->
                    val iterator = zip.entries()
                    while (iterator.hasMoreElements()) {
                        val entry = iterator.nextElement()
                        val name = entry.name.lowercase(Locale.ROOT)
                        entries.add(name)

                        if (!entry.isDirectory &&
                            Regex("classes(\\d*)\\.dex").matches(name) &&
                            entry.size in 1..MAX_DEX_BYTES
                        ) {
                            runCatching {
                                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                                dexStrings.append(String(bytes, Charsets.ISO_8859_1).lowercase(Locale.ROOT))
                                dexStrings.append('\n')
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Sigue con los demás APKs divididos si alguno no se puede leer.
            }
        }

        if (entries.isEmpty()) {
            return FrameworkInfo("No identificado (APK no legible)", "?", "unknown", "baja")
        }

        val dex = dexStrings.toString()
        val all = buildString {
            entries.forEach { append(it); append('\n') }
            append(dex)
        }.lowercase(Locale.ROOT)

        fun has(marker: String) = all.contains(marker.lowercase(Locale.ROOT))
        fun entryHas(marker: String) = entries.any { it.contains(marker.lowercase(Locale.ROOT)) }

        // Unity requiere tratamiento explícito: libil2cpp.so distingue IL2CPP de Unity Mono.
        if (entryHas("libil2cpp.so") || has("libil2cpp.so")) {
            val evidence = listOf("libil2cpp.so") +
                listOf("libunity.so", "global-metadata.dat", "assets/bin/data/")
                    .filter { has(it) }
            return FrameworkInfo("Unity (IL2CPP)", "U", "unity", "alta", evidence.distinct())
        }
        if (has("libunity.so") && has("assets/bin/data/")) {
            val monoEvidence = listOf("libunity.so", "assets/bin/data/") +
                listOf("libmono.so", "managed/", "assembly-csharp.dll").filter { has(it) }
            return FrameworkInfo("Unity (Mono)", "U", "unity", "alta", monoEvidence.distinct())
        }

        data class Match(val signature: Signature, val score: Int, val evidence: List<String>)
        val matches = signatures.mapNotNull { signature ->
            val evidence = signature.markers.filter { has(it) }
            if (evidence.isEmpty()) null
            else {
                // Una señal distintiva da 55 puntos; las adicionales elevan la confianza.
                val score = (55 + (evidence.size - 1) * 15).coerceAtMost(100)
                Match(signature, score, evidence)
            }
        }.sortedByDescending { it.score }

        val best = matches.firstOrNull()
        val runnerUp = matches.getOrNull(1)
        if (best != null && best.score >= best.signature.threshold &&
            (runnerUp == null || best.score - runnerUp.score >= 15)
        ) {
            val confidence = when {
                best.score >= 85 -> "alta"
                else -> "media"
            }
            val evidence = best.evidence.toMutableList()
            if (runnerUp != null && runnerUp.score >= 55) {
                evidence.add("Posibles indicios adicionales: ${runnerUp.signature.name}")
            }
            return FrameworkInfo(
                best.signature.name, best.signature.badge, best.signature.category,
                confidence, evidence
            )
        }

        // Detecta WebView como capa integrada, no como prueba de que toda la app sea JavaScript.
        val webEvidence = listOf(
            "assets/www/" to "assets/www/",
            "assets/public/" to "assets/public/",
            "assets/index.html" to "assets/index.html",
            "cordova.js" to "cordova.js",
            "android.webkit.webview" to "android.webkit.WebView"
        ).filter { has(it.first) }.map { it.second }.distinct()

        if (webEvidence.size >= 2 || (webEvidence.isNotEmpty() && has("javascriptinterface"))) {
            return FrameworkInfo(
                "WebView (contenido web integrado)",
                "WV",
                "webview",
                if (webEvidence.size >= 2) "media" else "baja",
                webEvidence
            )
        }

        // Kotlin y Jetpack Compose se buscan en DEX; no se infieren solo por el nombre de la app.
        val kotlinEvidence = listOf(
            "kotlin.jvm.internal" ,
            "kotlin/metadata",
            ".kotlin_module"
        ).filter { has(it) }
        val composeEvidence = listOf(
            "androidx.compose.",
            "androidx.compose.runtime",
            "androidx.compose.ui"
        ).filter { has(it) }.distinct()

        if (composeEvidence.isNotEmpty()) {
            return FrameworkInfo(
                "Android nativo (Jetpack Compose probable)", "JC", "native", "media",
                (composeEvidence + kotlinEvidence).distinct()
            )
        }
        if (kotlinEvidence.isNotEmpty()) {
            return FrameworkInfo(
                "Android nativo (Kotlin probable)", "K", "native", "baja", kotlinEvidence
            )
        }

        val javaEvidence = listOf(
            "android.app.activity",
            "androidx.appcompat",
            "android.view.",
            "androidx.fragment.app"
        ).filter { has(it) }

        val hasDex = entries.any { Regex("classes(\\d*)\\.dex").matches(it) }
        val nativeLibraries = entries.filter { Regex("lib/[^/]+/[^/]+\\.so").matches(it) }

        if (javaEvidence.isNotEmpty()) {
            return FrameworkInfo(
                "Android nativo (Java probable)", "J", "native", "baja", javaEvidence
            )
        }
        if (hasDex && nativeLibraries.isNotEmpty()) {
            return FrameworkInfo(
                "Android (Java/Kotlin y código nativo posible)", "NDK", "native", "baja",
                listOf("classes.dex", "bibliotecas nativas .so")
            )
        }
        if (hasDex) {
            return FrameworkInfo(
                "Android nativo probable (framework no identificado)", "A", "native", "baja",
                listOf("classes.dex", "sin firmas conocidas de framework")
            )
        }

        return FrameworkInfo(
            "No identificado (sin firmas conocidas)", "?", "unknown", "baja",
            listOf("No se encontraron indicadores suficientes")
        )
    }
}
