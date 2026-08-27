package eu.draconest.hermesbots

import android.content.Intent
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import eu.draconest.hermesbots.data.AppStore
import eu.draconest.hermesbots.data.AppViewModel
import eu.draconest.hermesbots.data.BotInfo
import eu.draconest.hermesbots.data.NOTIFICATION_PROFILE_EXTRA
import eu.draconest.hermesbots.data.NOTIFICATION_STORED_SESSION_EXTRA
import eu.draconest.hermesbots.data.NotificationTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityNotificationLifecycleTest {
    @Test
    fun coldAndWarmNotificationIntentsReachTheSamePendingTargetRoute() {
        val coldTarget = NotificationTarget("architect", "stored-cold")
        val warmTarget = NotificationTarget("architect", "stored-warm")
        val controller = Robolectric.buildActivity(
            NotificationLifecycleTestActivity::class.java,
            notificationIntent(coldTarget)
        ).create()
        val activity = controller.get()

        assertEquals(coldTarget, activity.pendingNotificationTargetForTest)

        controller.newIntent(notificationIntent(warmTarget))

        assertEquals(warmTarget, activity.pendingNotificationTargetForTest)
    }

    @Test
    fun malformedWarmNotificationIntentClearsThePendingRouteInsteadOfOpeningAnotherChat() {
        val controller = Robolectric.buildActivity(
            NotificationLifecycleTestActivity::class.java,
            notificationIntent(NotificationTarget("architect", "stored-valid"))
        ).create()
        val activity = controller.get()

        controller.newIntent(Intent().putExtra(NOTIFICATION_PROFILE_EXTRA, "architect"))

        assertNull(activity.pendingNotificationTargetForTest)
    }

    @Test
    fun warmNotificationIntentFlowsFromActivityThroughAppRootToTheExactProfile() {
        val controller = Robolectric.buildActivity(
            NotificationLifecycleTestActivity::class.java,
            Intent()
        ).create().start().postCreate(null).resume().visible()
        val activity = controller.get()
        val viewModel = ViewModelProvider(activity)[AppViewModel::class.java]
        viewModel.connected.value = true
        viewModel.bots.value = listOf(
            BotInfo(name = "architect", model = null, skillCount = 0, gatewayRunning = true)
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        controller.newIntent(notificationIntent(NotificationTarget("architect", "stored-warm")))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertNull(activity.pendingNotificationTargetForTest)
        assertEquals("architect", viewModel.activeBot.value?.name)
    }

    private fun notificationIntent(target: NotificationTarget): Intent =
        Intent().putExtra(NOTIFICATION_PROFILE_EXTRA, target.profileName)
            .putExtra(NOTIFICATION_STORED_SESSION_EXTRA, target.storedSessionId)
}

class NotificationLifecycleTestActivity : MainActivity() {
    override fun createAppStore(): AppStore =
        AppStore(this, getSharedPreferences("notification-lifecycle-test", MODE_PRIVATE))
}
