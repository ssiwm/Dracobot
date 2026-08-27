package eu.draconest.hermesbots.data

internal const val NOTIFICATION_PROFILE_EXTRA = "eu.draconest.hermesbots.profile"
internal const val NOTIFICATION_STORED_SESSION_EXTRA = "eu.draconest.hermesbots.stored_session_id"

/** A notification may open a chat only when the bridge supplied both stable routing keys. */
data class NotificationTarget(
    val profileName: String,
    val storedSessionId: String,
    val messageId: String? = null
)

private val PROFILE_NAME_PATTERN = Regex("[a-z0-9][a-z0-9_-]{0,63}")
private const val MAX_STORED_SESSION_ID_LENGTH = 512
private const val MAX_MESSAGE_ID_LENGTH = 128

internal fun notificationTargetFromPayload(payload: Map<String, String>): NotificationTarget? {
    val profileName = payload["profile"]?.trim().orEmpty()
    val storedSessionId = payload["stored_session_id"]?.trim().orEmpty()
    val messageId = payload["message_id"]?.trim()?.takeIf {
        it.isNotEmpty() && it.length <= MAX_MESSAGE_ID_LENGTH && it.none(Char::isISOControl)
    }
    return NotificationTarget(profileName, storedSessionId, messageId).takeIf {
        PROFILE_NAME_PATTERN.matches(it.profileName) &&
            it.storedSessionId.length <= MAX_STORED_SESSION_ID_LENGTH &&
            it.storedSessionId.isNotEmpty() &&
            it.storedSessionId.none(Char::isISOControl)
    }
}

internal fun notificationTargetFromIntentExtras(
    profileName: String?,
    storedSessionId: String?
): NotificationTarget? = notificationTargetFromPayload(
    mapOf(
        "profile" to profileName.orEmpty(),
        "stored_session_id" to storedSessionId.orEmpty()
    )
)

/** Stable per message when supplied, with a conversation fallback for legacy pushes. */
internal fun notificationIdFor(target: NotificationTarget): Int =
    ("hermesbots:" + target.profileName + '\u0000' + target.storedSessionId + '\u0000' +
        (target.messageId ?: "conversation")).hashCode()