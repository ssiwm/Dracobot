package eu.draconest.hermesbots.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Trwały stan apki: logowanie (zaszyfrowane), ostatni bot/rozmowa.
 */
class AppStore(private val context: Context) {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "hermes_bots_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var url: String
        get() = prefs.getString(KEY_URL, "https://bots.draconest.eu") ?: "https://bots.draconest.eu"
        set(v) = prefs.edit().putString(KEY_URL, v).apply()

    var username: String
        get() = prefs.getString(KEY_USER, "") ?: ""
        set(v) = prefs.edit().putString(KEY_USER, v).apply()

    var password: String
        get() = prefs.getString(KEY_PASS, "") ?: ""
        set(v) = prefs.edit().putString(KEY_PASS, v).apply()

    val hasCredentials: Boolean
        get() = url.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    var lastBotName: String
        get() = prefs.getString(KEY_LAST_BOT, "") ?: ""
        set(v) = prefs.edit().putString(KEY_LAST_BOT, v).apply()

    var lastSessionId: String
        get() = prefs.getString(KEY_LAST_SESSION, "") ?: ""
        set(v) = prefs.edit().putString(KEY_LAST_SESSION, v).apply()

    fun clearCredentials() {
        prefs.edit().remove(KEY_PASS).putString(KEY_USER, "").apply()
    }

    /** Dane dla mostka FCM (zwykly prefs — mostek czyta je przy rejestracji tokenu). */
    fun saveProxyForBridge(user: String, pass: String) {
        context.getSharedPreferences("hermes_bots_plain", Context.MODE_PRIVATE)
            .edit()
            .putString("bridge_base", url)
            .putString("proxy_user", user)
            .putString("proxy_pass", pass)
            .apply()
    }

    fun context(): Context = context

    private companion object {
        const val KEY_URL = "url"
        const val KEY_USER = "username"
        const val KEY_PASS = "password"
        const val KEY_LAST_BOT = "last_bot"
        const val KEY_LAST_SESSION = "last_session"
    }
}
