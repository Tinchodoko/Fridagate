package com.hackpuntes.fridagate.utils

/**
 * FridaStealthConfig — Configuración de camuflaje para frida-server y frida-inject.
 *
 * Permite cambiar:
 *  - El directorio de instalación (default: /data/local/tmp)
 *  - El nombre del binario de server (default: frida-server)
 *  - El nombre del binario de inject (default: frida-inject)
 *  - El puerto de escucha (default: 27042, conocido y detectable)
 *
 * Rutas válidas:
 *  - /data/local/tmp           → ruta default (detectable)
 *  - /data/local/tmp/.sys      → subdirectorio semi-oculto
 *  - /data/data/com.hackpuntes.fridagate/files/svc → privado a la app (más oculto)
 *
 * NOTA: /sdcard/ tiene flag noexec — los binarios no se pueden ejecutar desde allí.
 */
data class FridaStealthConfig(
    val installDir: String = "/data/local/tmp",
    val serverAlias: String = "frida-server",
    val injectAlias: String = "frida-inject",
    val port: Int = 27042
) {
    /** Ruta completa del binario frida-server */
    val serverBinaryPath: String
        get() = "$installDir/$serverAlias"

    /** Ruta del archivo de versión del server */
    val serverVersionFile: String
        get() = "$installDir/$serverAlias-version.txt"

    /** Ruta completa del binario frida-inject */
    val injectBinaryPath: String
        get() = "$installDir/$injectAlias"

    /** Ruta del archivo de versión del inject */
    val injectVersionFile: String
        get() = "$installDir/$injectAlias-version.txt"

    /** Flag de puerto para pasar a frida-server. Solo se agrega si es distinto del default. */
    val portFlag: String
        get() = if (port != 27042) "-l 127.0.0.1:$port" else ""

    /** Validaciones básicas */
    fun validate(): String? {
        if (installDir.startsWith("/sdcard")) {
            return "No se puede usar /sdcard/ — está montado con noexec."
        }
        if (serverAlias.isBlank()) {
            return "El nombre del server no puede estar vacío."
        }
        if (injectAlias.isBlank()) {
            return "El nombre del inject no puede estar vacío."
        }
        if (port !in 1024..65535) {
            return "El puerto debe estar entre 1024 y 65535."
        }
        if (serverAlias == injectAlias) {
            return "El nombre del server y del inject no pueden ser iguales."
        }
        return null // null = válido
    }

    companion object {
        /** Valores por defecto sin camuflaje (comportamiento original) */
        val DEFAULT = FridaStealthConfig()

        /** Preset semi-oculto sugerido para uso con DIVA y aprendizaje */
        val STEALTH = FridaStealthConfig(
            installDir = "/data/local/tmp/.sys",
            serverAlias = "thermal_svc",
            injectAlias = "msm_inject",
            port = 37285
        )
    }
}
