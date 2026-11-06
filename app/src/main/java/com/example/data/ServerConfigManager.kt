package com.example.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class ServerConfigManager(context: Context) {
    private val prefs: SharedPreferences = createSecurePreferences(context)


    companion object {
        private const val LEGACY_PREFS_NAME = "music_assistant_prefs"
        private const val SECURE_PREFS_NAME = "music_assistant_secure_prefs"

        private fun createSecurePreferences(context: Context): SharedPreferences {
            val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val secure = EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            // Migrate existing plaintext preferences once. Delete the legacy copy only
            // after the encrypted transaction has been committed successfully.
            if (secure.all.isEmpty() && legacy.all.isNotEmpty()) {
                val editor = secure.edit()
                legacy.all.forEach { (key, value) ->
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                }
                if (editor.commit()) {
                    legacy.edit().clear().commit()
                }
            }
            return secure
        }

        private const val KEY_HOST = "server_host"
        private const val KEY_USERNAME = "server_username"
        private const val KEY_PASSWORD = "server_password"
        private const val KEY_TOKEN = "server_token"
        private const val KEY_PORT = "server_port"
        private const val KEY_USE_DEMO = "use_demo_mode"
        private const val KEY_ACTIVE_PLAYER_ID = "active_player_id"
        private const val KEY_ACCENT_COLOR = "app_accent_color"
        private const val KEY_SENDSPIN_CLIENT_ID = "sendspin_client_id"
        private const val KEY_LOCAL_PLAYER_ENABLED = "local_player_enabled"
        private const val KEY_LOCAL_PLAYER_NAME = "local_player_name"
        private const val KEY_SENDSPIN_PORT = "sendspin_port"
        private const val KEY_SENDSPIN_DELAY_MS = "sendspin_delay_ms"
    }

    var host: String
        get() = prefs.getString(KEY_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOST, value.trim()).apply()

    var username: String
        get() = prefs.getString(KEY_USERNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USERNAME, value.trim()).apply()

    var password: String
        get() = prefs.getString(KEY_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PASSWORD, value.trim()).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    var port: String
        get() = prefs.getString(KEY_PORT, "8095") ?: "8095"
        set(value) = prefs.edit().putString(KEY_PORT, value.trim()).apply()

    var useDemoMode: Boolean
        get() = prefs.getBoolean(KEY_USE_DEMO, true) // Default to true so they immediately see a stunning music library
        set(value) = prefs.edit().putBoolean(KEY_USE_DEMO, value).apply()

    var activePlayerId: String
        get() = prefs.getString(KEY_ACTIVE_PLAYER_ID, "default_player") ?: "default_player"
        set(value) = prefs.edit().putString(KEY_ACTIVE_PLAYER_ID, value).apply()

    var accentColor: String
        get() = prefs.getString(KEY_ACCENT_COLOR, "#0EA5E9") ?: "#0EA5E9"
        set(value) = prefs.edit().putString(KEY_ACCENT_COLOR, value).apply()

    /** Stable, persistent client_id used to identify this phone to the Sendspin server across reconnects/restarts. */
    val sendspinClientId: String
        get() {
            var id = prefs.getString(KEY_SENDSPIN_CLIENT_ID, null)
            if (id.isNullOrEmpty()) {
                id = "ma-android-${java.util.UUID.randomUUID()}"
                prefs.edit().putString(KEY_SENDSPIN_CLIENT_ID, id).apply()
            }
            return id
        }

    var localPlayerEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCAL_PLAYER_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_LOCAL_PLAYER_ENABLED, value).apply()

    var localPlayerName: String
        get() = prefs.getString(KEY_LOCAL_PLAYER_NAME, "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim().ifEmpty { "Dispositivo" }) ?: "Dispositivo"
        set(value) = prefs.edit().putString(KEY_LOCAL_PLAYER_NAME, value.trim()).apply()

    var sendspinPort: String
        get() = prefs.getString(KEY_SENDSPIN_PORT, "8927") ?: "8927"
        set(value) = prefs.edit().putString(KEY_SENDSPIN_PORT, value.trim()).apply()

    var sendspinDelayMs: Int
        get() = prefs.getInt(KEY_SENDSPIN_DELAY_MS, 0)
        set(value) = prefs.edit().putInt(KEY_SENDSPIN_DELAY_MS, value).apply()

    fun clear() {
        prefs.edit().clear().apply()
    }
}
