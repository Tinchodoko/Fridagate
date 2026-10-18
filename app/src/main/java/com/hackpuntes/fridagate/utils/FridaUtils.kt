package com.hackpuntes.fridagate.utils

import android.content.Context
import android.os.Build
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.stream.JsonReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.tukaani.xz.XZInputStream

/**
 * FridaUtils - Toda la lógica relacionada con el binario frida-server.
 *
 * CAMBIO RESPECTO A LA VERSIÓN ORIGINAL:
 *  Las rutas y el puerto ya NO son constantes hardcodeadas. Son configurables
 *  a través de FridaStealthConfig, que se establece con configure().
 *
 *  Uso:
 *    FridaUtils.configure(miConfig)   // llamar desde el ViewModel al iniciar
 *    FridaUtils.startFridaServer()    // usa automáticamente la config activa
 *
 *  Si configure() nunca se llama, se usa FridaStealthConfig.DEFAULT
 *  que mantiene exactamente el comportamiento original (mismas rutas y puerto).
 */
object FridaUtils {

    // ── Configuración activa ──────────────────────────────────────────────────

    @Volatile
    private var activeConfig: FridaStealthConfig = FridaStealthConfig.DEFAULT

    /**
     * Establece la configuración de camuflaje.
     * Llamar desde FridaViewModel antes de cualquier operación.
     */
    fun configure(config: FridaStealthConfig) {
        activeConfig = config
    }

    /** Devuelve la configuración activa (para que el ViewModel pueda leerla). */
    fun getActiveConfig(): FridaStealthConfig = activeConfig

    // ── Propiedades de ruta (dinámicas, dependen de la config activa) ─────────

    private val FRIDA_BINARY_PATH: String get() = activeConfig.serverBinaryPath
    private val FRIDA_VERSION_FILE: String get() = activeConfig.serverVersionFile

    // ── Constante de API (no cambia) ──────────────────────────────────────────

    private const val FRIDA_GITHUB_API = "https://api.github.com/repos/frida/frida/releases"

    // ── Data classes (sin cambios respecto al original) ───────────────────────

    data class FridaRelease(val version: String, val releaseDate: String, val assets: List<FridaAsset>)

    data class FridaAsset(val name: String, val downloadUrl: String, val architecture: String, val size: Long)

    data class GithubRelease(
        @SerializedName("tag_name") val tagName: String,
        @SerializedName("published_at") val publishedAt: String,
        @SerializedName("assets") val assets: List<GithubAsset>
    )

    data class GithubAsset(
        @SerializedName("name") val name: String,
        @SerializedName("browser_download_url") val browserDownloadUrl: String,
        @SerializedName("size") val size: Long
    )

    // ── Network (sin cambios respecto al original) ────────────────────────────

    suspend fun getAvailableFridaReleases(): List<FridaRelease> {
        return withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url(FRIDA_GITHUB_API)
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()

                val releases = mutableListOf<FridaRelease>()
                val gson = Gson()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()

                    JsonReader(response.body?.charStream() ?: return@withContext emptyList()).use { reader ->
                        reader.beginArray()

                        while (reader.hasNext()) {
                            val githubRelease = gson.fromJson<GithubRelease>(reader, GithubRelease::class.java)
                            val fridaAssets = mutableListOf<FridaAsset>()

                            for (asset in githubRelease.assets) {
                                val name = asset.name
                                if (name.startsWith("frida-server-") &&
                                    (name.endsWith(".xz") || name.endsWith(".zip"))
                                ) {
                                    val archPattern = "android-(arm64|arm|x86_64|x86)(?=[.-])".toRegex()
                                    val matchResult = archPattern.find(name)
                                    val architecture = matchResult?.groupValues?.get(1) ?: "unknown"

                                    fridaAssets.add(
                                        FridaAsset(
                                            name = name,
                                            downloadUrl = asset.browserDownloadUrl,
                                            architecture = architecture,
                                            size = asset.size
                                        )
                                    )
                                }
                            }

                            if (fridaAssets.isNotEmpty()) {
                                releases.add(
                                    FridaRelease(
                                        version = githubRelease.tagName,
                                        releaseDate = githubRelease.publishedAt.split("T")[0],
                                        assets = fridaAssets
                                    )
                                )
                            }
                        }

                        reader.endArray()
                    }
                }

                return@withContext releases
            } catch (e: Exception) {
                return@withContext emptyList()
            }
        }
    }

    /**
     * Comprueba que GitHub publique el binario solicitado antes de iniciar una instalación.
     * Se usa para validar server e inject de la misma versión y evitar actualizaciones parciales.
     */
    suspend fun isFridaAssetAvailable(version: String, architecture: String, component: String): Boolean {
        if (!isValidVersionFormat(version) || architecture !in setOf("arm", "arm64", "x86", "x86_64")) {
            return false
        }
        if (component !in setOf("server", "inject")) return false
        return withContext(Dispatchers.IO) {
            try {
                val assetName = "frida-$component-$version-android-$architecture.xz"
                val url = "https://github.com/frida/frida/releases/download/$version/$assetName"
                val request = Request.Builder().url(url).head().build()
                OkHttpClient.Builder()
                    .followRedirects(true)
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()
                    .newCall(request)
                    .execute()
                    .use { response -> response.isSuccessful }
            } catch (_: Exception) {
                false
            }
        }
    }

    suspend fun getFridaServerUrl(version: String, architecture: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val releases = getAvailableFridaReleases()
                val release = releases.find { it.version == version }

                if (release != null) {
                    val asset = release.assets.find { it.architecture == architecture }
                    if (asset != null) return@withContext asset.downloadUrl

                    val fallback = release.assets.find { it.name.contains("-android-$architecture") }
                    if (fallback != null) return@withContext fallback.downloadUrl
                }

                return@withContext buildCustomVersionUrl(version, architecture)
            } catch (e: Exception) {
                return@withContext null
            }
        }
    }

    private suspend fun buildCustomVersionUrl(version: String, architecture: String): String? {
        if (!isValidVersionFormat(version)) return null
        if (architecture !in setOf("arm", "arm64", "x86", "x86_64")) return null
        return "https://github.com/frida/frida/releases/download/$version/frida-server-$version-android-$architecture.xz"
    }

    fun isValidVersionFormat(version: String): Boolean {
        val pattern = Regex("^\\d+\\.\\d+\\.\\d+(-[a-zA-Z0-9]+)?$")
        return pattern.matches(version)
    }

    // ── Download y descompresión (sin cambios respecto al original) ───────────

    suspend fun downloadFridaServerFromUrl(context: Context, url: String): File? {
        return withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .followRedirects(true)
                    .connectTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()

                val downloadRequest = Request.Builder().url(url).build()
                val fridaFile = File(context.filesDir, "frida-server")

                client.newCall(downloadRequest).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null

                    response.body?.let { body ->
                        when {
                            url.endsWith(".xz") -> {
                                val compressedFile = File(context.filesDir, "frida-server.xz")

                                FileOutputStream(compressedFile).use { output ->
                                    body.byteStream().use { input ->
                                        val buffer = ByteArray(8192)
                                        var bytesRead: Int
                                        while (input.read(buffer).also { bytesRead = it } != -1) {
                                            output.write(buffer, 0, bytesRead)
                                        }
                                    }
                                }

                                try {
                                    XZInputStream(FileInputStream(compressedFile)).use { xzInput ->
                                        FileOutputStream(fridaFile).use { output ->
                                            val buffer = ByteArray(8192)
                                            var bytesRead: Int
                                            while (xzInput.read(buffer).also { bytesRead = it } != -1) {
                                                output.write(buffer, 0, bytesRead)
                                            }
                                        }
                                    }
                                    compressedFile.delete()
                                } catch (e: Exception) {
                                    compressedFile.delete()
                                    if (fridaFile.exists()) fridaFile.delete()
                                    throw IOException("Failed to decompress .xz file", e)
                                }
                            }

                            url.endsWith(".zip") -> {
                                ZipInputStream(body.byteStream()).use { zipStream ->
                                    var entry = zipStream.nextEntry
                                    while (entry != null) {
                                        if (entry.name.contains("frida-server")) {
                                            FileOutputStream(fridaFile).use { output ->
                                                val buffer = ByteArray(8192)
                                                var bytesRead: Int
                                                while (zipStream.read(buffer).also { bytesRead = it } != -1) {
                                                    output.write(buffer, 0, bytesRead)
                                                }
                                            }
                                            break
                                        }
                                        zipStream.closeEntry()
                                        entry = zipStream.nextEntry
                                    }
                                }
                            }

                            else -> {
                                FileOutputStream(fridaFile).use { output ->
                                    body.byteStream().use { input ->
                                        val buffer = ByteArray(8192)
                                        var bytesRead: Int
                                        while (input.read(buffer).also { bytesRead = it } != -1) {
                                            output.write(buffer, 0, bytesRead)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                fridaFile.setExecutable(true)
                return@withContext fridaFile
            } catch (e: Exception) {
                return@withContext null
            }
        }
    }

    // ── Instalación ───────────────────────────────────────────────────────────

    /**
     * Instala frida-server en la ruta configurada.
     *
     * CAMBIO: Crea el directorio de instalación si no existe
     * y usa activeConfig.serverBinaryPath en vez de /data/local/tmp/frida-server.
     */
    suspend fun installFridaServer(fridaFile: File, version: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Crear directorio si no existe (ej: /data/local/tmp/.sys/)
                RootUtils.executeSuCommand("mkdir -p ${activeConfig.installDir}")

                // Copiar y dar permisos
                RootUtils.executeSuCommand("cp ${fridaFile.absolutePath} $FRIDA_BINARY_PATH")
                RootUtils.executeSuCommand("chmod 755 $FRIDA_BINARY_PATH")

                // Guardar versión
                saveInstalledVersion(version)

                return@withContext isFridaServerInstalled()
            } catch (e: Exception) {
                return@withContext false
            }
        }
    }

    suspend fun saveInstalledVersion(version: String): Boolean = withContext(Dispatchers.IO) {
        try {
            RootUtils.executeSuCommand("echo '$version' > $FRIDA_VERSION_FILE")
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getInstalledFridaVersion(): String? {
        return withContext(Dispatchers.IO) {
            try {
                val binaryVersion = RootUtils.executeSuCommand("$FRIDA_BINARY_PATH --version").trim()
                if (Regex("^\\d+\\.\\d+\\.\\d+([.-][A-Za-z0-9.-]+)?$").matches(binaryVersion)) {
                    return@withContext binaryVersion
                }

                val versionResult = RootUtils.executeSuCommand("cat $FRIDA_VERSION_FILE 2>/dev/null").trim()
                versionResult.takeIf { Regex("^\\d+\\.\\d+\\.\\d+([.-][A-Za-z0-9.-]+)?$").matches(it) }
            } catch (e: Exception) {
                null
            }
        }
    }

    // ── Control del servidor ──────────────────────────────────────────────────

    suspend fun startFridaServer(): Boolean = startFridaServerWithFlags("")

    /**
     * Inicia frida-server con flags opcionales.
     *
     * CAMBIO: Si la configuración tiene un puerto custom (distinto de 27042),
     * agrega automáticamente -l 127.0.0.1:PUERTO. El usuario puede sobreescribir
     * esto con flags manuales si lo desea.
     */
    suspend fun startFridaServerWithFlags(flags: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (isFridaServerRunning()) return@withContext true

                // Construir el comando con el puerto configurado
                val portFlag = activeConfig.portFlag // "-l 127.0.0.1:37285" o ""
                val effectiveFlags = buildString {
                    if (portFlag.isNotEmpty()) append(portFlag)
                    if (flags.isNotBlank()) {
                        if (isNotEmpty()) append(" ")
                        append(flags)
                    }
                }

                val command = if (effectiveFlags.isBlank()) {
                    "nohup $FRIDA_BINARY_PATH > /dev/null 2>&1 &"
                } else {
                    "nohup $FRIDA_BINARY_PATH $effectiveFlags > /dev/null 2>&1 &"
                }

                RootUtils.executeSuCommand(command)
                Thread.sleep(1500)

                return@withContext isFridaServerRunning()
            } catch (e: Exception) {
                return@withContext false
            }
        }
    }

    /**
     * Detiene frida-server.
     *
     * CAMBIO: Usa el alias configurado en vez de "frida-server" hardcodeado.
     * Las 4 estrategias de kill se mantienen iguales, pero buscan por
     * el alias (ej: "thermal_svc") y por la ruta completa del binario.
     */
    suspend fun stopFridaServer(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val alias = activeConfig.serverAlias
                val binaryPath = FRIDA_BINARY_PATH

                // Estrategia 1: ps -A + kill por ruta completa (más preciso)
                RootUtils.executeSuCommand(
                    "kill -9 \$(ps -A | grep \"$binaryPath\" | grep -v grep | awk '{ print \$2 }')"
                )
                Thread.sleep(500)
                if (!isFridaServerRunning()) return@withContext true

                // Estrategia 2: pidof con el alias
                RootUtils.executeSuCommand("kill -9 \$(pidof $alias)")
                Thread.sleep(300)
                if (!isFridaServerRunning()) return@withContext true

                // Estrategia 3: ps sin -A (Android más antiguo)
                RootUtils.executeSuCommand(
                    "kill -9 \$(ps | grep \"$binaryPath\" | grep -v grep | awk '{ print \$2 }')"
                )
                Thread.sleep(300)
                if (!isFridaServerRunning()) return@withContext true

                // Estrategia 4: pkill por alias (fallback)
                RootUtils.executeSuCommand("pkill -9 -x $alias")
                Thread.sleep(500)

                return@withContext !isFridaServerRunning()
            } catch (e: Exception) {
                return@withContext false
            }
        }
    }

    // ── Checks de estado ──────────────────────────────────────────────────────

    suspend fun isFridaServerInstalled(): Boolean = withContext(Dispatchers.IO) {
        try {
            RootUtils.executeSuCommand("test -x $FRIDA_BINARY_PATH && echo FRIDA_SERVER_EXECUTABLE")
                .contains("FRIDA_SERVER_EXECUTABLE")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Verifica si el puerto configurado está escuchando.
     *
     * CAMBIO: Usa activeConfig.port en vez del 27042 hardcodeado.
     */
    suspend fun isFridaServerPortListening(): Boolean = withContext(Dispatchers.IO) {
        try {
            val port = activeConfig.port
            val output = RootUtils.executeSuCommand(
                "ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null"
            )
            output.lineSequence().any { line ->
                line.contains("LISTEN", ignoreCase = true) &&
                    Regex("(^|[:.])$port(\\s|$)").containsMatchIn(line)
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Verifica si el proceso de frida-server está corriendo.
     *
     * CAMBIO: Busca por activeConfig.serverAlias en vez de "frida-server" hardcodeado.
     * Esto evita que el check devuelva false si el proceso fue renombrado.
     */
    suspend fun isFridaServerRunning(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val alias = activeConfig.serverAlias

                // Preferir pidof: no puede coincidir con su propia línea de comando
                val pidResult = RootUtils.executeSuCommand("pidof $alias").trim()
                if (pidResult.split(Regex("\\s+")).any { it.isNotBlank() && it.all(Char::isDigit) }) {
                    return@withContext true
                }

                // Fallback: buscar en ps por la ruta completa del binario
                val binaryPath = FRIDA_BINARY_PATH
                val processLists = listOf(
                    RootUtils.executeSuCommand("ps -A"),
                    RootUtils.executeSuCommand("ps")
                )
                processLists.any { output ->
                    output.lineSequence().any { line ->
                        line.contains(binaryPath) &&
                            !line.contains("grep") &&
                            !line.contains("sh -c") &&
                            !line.contains("toybox")
                    }
                }
            } catch (e: Exception) {
                false
            }
        }
    }

    suspend fun uninstallFridaServer(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (isFridaServerRunning()) stopFridaServer()

                RootUtils.executeSuCommand("rm -f $FRIDA_BINARY_PATH")
                RootUtils.executeSuCommand("rm -f $FRIDA_VERSION_FILE")

                val result = RootUtils.executeSuCommand("ls -la $FRIDA_BINARY_PATH")
                val stillExists = result.contains(FRIDA_BINARY_PATH) && !result.contains("No such file")
                return@withContext !stillExists
            } catch (e: Exception) {
                false
            }
        }
    }

    fun getDeviceArchitecture(): String = when (Build.SUPPORTED_ABIS[0]) {
        "arm64-v8a" -> "arm64"
        "armeabi-v7a" -> "arm"
        "x86_64" -> "x86_64"
        "x86" -> "x86"
        else -> "arm"
    }
}
