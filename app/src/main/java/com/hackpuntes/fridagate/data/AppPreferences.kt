package com.hackpuntes.fridagate.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hackpuntes.fridagate.utils.FridaStealthConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "fridagate_settings"
)

class AppPreferences(private val context: Context) {

    private object Keys {
        // ── Selección de versión (existente) ─────────────────────────────────
        val FRIDA_SELECTED_VERSION = stringPreferencesKey("frida_selected_version")

        // ── Configuración de camuflaje (nuevo) ───────────────────────────────
        val STEALTH_INSTALL_DIR = stringPreferencesKey("stealth_install_dir")
        val STEALTH_SERVER_ALIAS = stringPreferencesKey("stealth_server_alias")
        val STEALTH_INJECT_ALIAS = stringPreferencesKey("stealth_inject_alias")
        val STEALTH_PORT = intPreferencesKey("stealth_port")
    }

    // ── Frida version selection (existente, sin cambios) ─────────────────────

    val fridaSelectedVersion: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.FRIDA_SELECTED_VERSION] ?: ""
    }

    suspend fun saveFridaSelectedVersion(version: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.FRIDA_SELECTED_VERSION] = version
        }
    }

    // ── Configuración de camuflaje (nuevo) ───────────────────────────────────

    /**
     * Flow que emite la configuración de camuflaje actual.
     * Si no hay valores guardados, devuelve los defaults (sin camuflaje).
     */
    val stealthConfig: Flow<FridaStealthConfig> = context.dataStore.data.map { prefs ->
        FridaStealthConfig(
            installDir = prefs[Keys.STEALTH_INSTALL_DIR] ?: FridaStealthConfig.DEFAULT.installDir,
            serverAlias = prefs[Keys.STEALTH_SERVER_ALIAS] ?: FridaStealthConfig.DEFAULT.serverAlias,
            injectAlias = prefs[Keys.STEALTH_INJECT_ALIAS] ?: FridaStealthConfig.DEFAULT.injectAlias,
            port = prefs[Keys.STEALTH_PORT] ?: FridaStealthConfig.DEFAULT.port
        )
    }

    /** Guarda la configuración de camuflaje en DataStore. */
    suspend fun saveStealthConfig(config: FridaStealthConfig) {
        context.dataStore.edit { prefs ->
            prefs[Keys.STEALTH_INSTALL_DIR] = config.installDir
            prefs[Keys.STEALTH_SERVER_ALIAS] = config.serverAlias
            prefs[Keys.STEALTH_INJECT_ALIAS] = config.injectAlias
            prefs[Keys.STEALTH_PORT] = config.port
        }
    }

    /** Resetea la configuración de camuflaje a los defaults originales. */
    suspend fun resetStealthConfig() {
        saveStealthConfig(FridaStealthConfig.DEFAULT)
    }

    companion object {}
}
