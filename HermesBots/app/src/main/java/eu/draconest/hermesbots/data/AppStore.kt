package eu.draconest.hermesbots.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/** JSON representation kept inside encrypted preferences; malformed legacy data is safely ignored. */
internal object QueuedPromptSnapshotCodec {
    fun encode(entries: List<QueuedPrompt>): String = JSONArray().apply {
        entries.forEach { entry ->
            put(JSONObject()
                .put("id", entry.id)
                .put("stored_session_id", entry.storedSessionId)
                .put("text", entry.text)
                .put("delivery_state", entry.deliveryState.name))
        }
    }.toString()

    fun decode(serialized: String?): List<QueuedPrompt> = try {
        val array = JSONArray(serialized ?: return emptyList())
        buildList {
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                val id = entry.optString("id").trim()
                val storedSessionId = entry.optString("stored_session_id").trim()
                if (id.isEmpty() || storedSessionId.isEmpty()) continue
                val deliveryState = runCatching {
                    QueuedPromptDeliveryState.valueOf(entry.optString("delivery_state"))
                }.getOrDefault(QueuedPromptDeliveryState.Pending)
                add(QueuedPrompt(
                    id = id,
                    storedSessionId = storedSessionId,
                    text = entry.optString("text"),
                    deliveryState = deliveryState
                ))
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

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

    /** Offline prompt content remains encrypted at rest alongside the durable session binding. */
    internal var queuedPrompts: List<QueuedPrompt>
        get() = QueuedPromptSnapshotCodec.decode(prefs.getString(KEY_QUEUED_PROMPTS, null))
        set(value) = prefs.edit()
            .putString(KEY_QUEUED_PROMPTS, QueuedPromptSnapshotCodec.encode(value))
            .apply()

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
        const val KEY_QUEUED_PROMPTS = "queued_prompts"
    }
}
