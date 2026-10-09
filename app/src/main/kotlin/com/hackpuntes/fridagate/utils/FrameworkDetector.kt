package com.hackpuntes.fridagate.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Best-effort static detector. It inspects APK entry names and native library names;
 * it does not execute the target app and cannot identify every framework with certainty.
 */
data class FrameworkInfo(
    val name: String,
    val badge: String,
    val category: String
)

object FrameworkDetector {
    private val unknown = FrameworkInfo("Android nativo / no identificado", "A", "native")

    fun detect(context: Context, packageName: String): FrameworkInfo {
        val appInfo = try {
            context.packageManager.getApplicationInfo(packageName, 0)
        } catch (_: Exception) {
            return unknown
        }

        val apkPaths = buildList {
            if (appInfo.sourceDir.isNullOrBlank().not()) add(appInfo.sourceDir)
            appInfo.splitSourceDirs?.filterNotNull()?.forEach { add(it) }
        }.distinct()

        val entries = mutableSetOf<String>()
        for (path in apkPaths) {
            try {
                ZipFile(path).use { zip ->
                    val enumeration = zip.entries()
                    while (enumeration.hasMoreElements()) {
                        val entry = enumeration.nextElement()
                        entries += entry.name.lowercase(Locale.ROOT)
                    }
                }
            } catch (_: Exception) {
                // Some package paths may not be readable; continue with the remaining APKs.
            }
        }

        if (entries.isEmpty()) return unknown

        fun has(vararg needles: String) = needles.any { needle ->
            entries.any { it.contains(needle) }
        }

        return when {
            has("libflutter.so", "assets/flutter_assets/") ->
                FrameworkInfo("Flutter", "F", "flutter")

            has("libil2cpp.so", "assets/bin/data/globalgamemanagers", "data.unity3d") ||
                (has("libunity.so") && has("assets/bin/data/")) ->
                FrameworkInfo("Unity (IL2CPP)", "U", "unity")

            has("libunity.so", "assets/bin/data/") ->
                FrameworkInfo("Unity", "U", "unity")

            has("libunreal.so", "libue4.so", "libue5.so", "ue4game/", "ue5game/", "engine/binaries/android/") ->
                FrameworkInfo("Unreal Engine", "UE", "unreal")

            has("libgodot_android.so", "libgodot.so", ".pck") ->
                FrameworkInfo("Godot", "G", "godot")

            has("libreactnative.so", "libhermes.so", "assets/index.android.bundle", "index.android.bundle") ->
                FrameworkInfo("React Native", "RN", "react")

            has("assets/www/cordova.js", "assets/www/cordova_plugins.js") ->
                FrameworkInfo("Cordova / JavaScript", "JS", "javascript")

            has("assets/capacitor.config.json", "assets/public/capacitor.js", "capacitor.config.json") ->
                FrameworkInfo("Capacitor / JavaScript", "JS", "javascript")

            has("libcocos2djs.so", "libcocos2dcpp.so", "assets/src/") ->
                FrameworkInfo("Cocos2d-x", "C2", "cocos")

            has("libgdx.so", "com/badlogic/gdx/") ->
                FrameworkInfo("libGDX", "GX", "libgdx")

            has("libmonodroid.so", "libxamarin-app.so", "assemblies/") ->
                FrameworkInfo("Xamarin / .NET", ".N", "dotnet")

            has("assets/index.html", "assets/www/", "assets/public/") ->
                FrameworkInfo("WebView / posible JavaScript", "JS", "javascript")

            else -> unknown
        }
    }
}
