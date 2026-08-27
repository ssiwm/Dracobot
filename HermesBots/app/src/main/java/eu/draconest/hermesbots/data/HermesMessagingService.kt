package eu.draconest.hermesbots.data

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * FCM: pobiera token urzadzenia, rejestruje go na mostku (serwer),
 * i pokazuje powiadomienia push od botow.
 */
class HermesMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        registerTokenOnBridge(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: "Hermes Bots"
        val body = message.notification?.body ?: message.data["body"] ?: ""
        showNotification(title, body, notificationTargetFromPayload(message.data))
    }

    private fun showNotification(title: String, body: String, target: NotificationTarget?) {
        val ctx = applicationContext
        val manager = androidx.core.app.NotificationManagerCompat.from(ctx)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                android.app.NotificationChannel(
                    CHANNEL_ID, "Odpowiedzi botów",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
        val intent = android.content.Intent(ctx, eu.draconest.hermesbots.MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            target?.let {
                putExtra(NOTIFICATION_PROFILE_EXTRA, it.profileName)
                putExtra(NOTIFICATION_STORED_SESSION_EXTRA, it.storedSessionId)
            }
        }
        val notificationId = target?.let(::notificationIdFor) ?: NOTIF_ID
        val pending = android.app.PendingIntent.getActivity(
            ctx, notificationId, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notif = androidx.core.app.NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            androidx.core.app.NotificationManagerCompat.from(ctx).notify(notificationId, notif)
        } catch (e: SecurityException) {
            Log.w(TAG, "brak uprawnien POST_NOTIFICATIONS", e)
        }
    }

    companion object {
        private const val TAG = "HermesFCM"
        private const val CHANNEL_ID = "bot_replies"
        private const val NOTIF_ID = 1001

        /** Pobiera token i wysyla go na mostek; wywolywac po starcie apki.
         * Firebase moze byc niezainicjalizowany (brak google-services.json) — wtedy push milczy. */
        fun registerTokenOnBridge(context: Context) {
            try {
                FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                    registerTokenOnBridge(context, token)
                }.addOnFailureListener {
                    Log.w(TAG, "nie udalo sie pobrac tokenu FCM", it)
                }
            } catch (e: IllegalStateException) {
                // "Default FirebaseApp is not initialized" — push nieaktywny w tym buildzie
                Log.w(TAG, "Firebase nie zainicjalizowany — powiadomienia wylaczone", e)
            }
        }

        /** Rejestruje token na mostku przez publiczny /register (Caddy -> :8765). */
        fun registerTokenOnBridge(context: Context, token: String) {
            Thread {
                try {
                    val prefs = context.getSharedPreferences("hermes_bots_plain", Context.MODE_PRIVATE)
                    val base = prefs.getString("bridge_base", "https://bots.draconest.eu")
                        ?: "https://bots.draconest.eu"
                    val user = prefs.getString("proxy_user", "") ?: ""
                    val pass = prefs.getString("proxy_pass", "") ?: ""
                    val json = "{\"token\":\"$token\"}"
                    val builder = Request.Builder()
                        .url("$base/register")
                        .post(json.toRequestBody("application/json".toMediaType()))
                        .header("User-Agent", "HermesBots/0.6")
                    if (user.isNotBlank()) {
                        builder.header("Authorization", okhttp3.Credentials.basic(user, pass))
                    }
                    OkHttpClient().newCall(builder.build()).execute().use { resp ->
                        Log.i(TAG, "rejestracja tokenu na mostku: HTTP ${resp.code}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "rejestracja tokenu nieudana", e)
                }
            }.start()
        }
    }
}
