package eu.draconest.hermesbots.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationTargetTest {
    @Test
    fun notificationTargetRequiresBothProfileAndDurableSessionKey() {
        assertNull(notificationTargetFromPayload(emptyMap()))
        assertNull(notificationTargetFromPayload(mapOf("profile" to "bot-a")))
        assertNull(notificationTargetFromPayload(mapOf("stored_session_id" to "stored-a")))
        assertNull(notificationTargetFromPayload(mapOf("profile" to " ", "stored_session_id" to "stored-a")))
    }

    @Test
    fun notificationTargetRejectsOversizedOrMalformedExternalExtras() {
        assertNull(notificationTargetFromPayload(mapOf(
            "profile" to "InvalidUppercase",
            "stored_session_id" to "stored-a"
        )))
        assertNull(notificationTargetFromPayload(mapOf(
            "profile" to "a".repeat(65),
            "stored_session_id" to "stored-a"
        )))
        assertNull(notificationTargetFromPayload(mapOf(
            "profile" to "bot-a",
            "stored_session_id" to "s".repeat(513)
        )))
        assertNull(notificationTargetFromPayload(mapOf(
            "profile" to "bot-a",
            "stored_session_id" to "stored\u0000a"
        )))
    }

    @Test
    fun intentExtrasUseTheSameCompleteRouteValidationForColdAndWarmLaunches() {
        assertEquals(
            NotificationTarget(profileName = "architect", storedSessionId = "stored-successor"),
            notificationTargetFromIntentExtras("architect", "stored-successor")
        )
        assertNull(notificationTargetFromIntentExtras("architect", null))
        assertNull(notificationTargetFromIntentExtras(null, "stored-successor"))
    }

    @Test
    fun differentMessageIdsInTheSameConversationKeepSeparateNotifications() {
        val first = NotificationTarget("bot-a", "stored-a", "41")
        val second = NotificationTarget("bot-a", "stored-a", "42")

        assertFalse(notificationIdFor(first) == notificationIdFor(second))
    }

    @Test
    fun notificationTargetTrimsPayloadAndUsesAStablePerConversationNotificationId() {
        val target = notificationTargetFromPayload(
            mapOf("profile" to " bot-a ", "stored_session_id" to " stored-a ")
        )

        assertEquals(NotificationTarget("bot-a", "stored-a"), target)
        assertEquals(notificationIdFor(target!!), notificationIdFor(target))
        assertFalse(notificationIdFor(target) == notificationIdFor(NotificationTarget("bot-b", "stored-a")))
    }
}