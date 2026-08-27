package eu.draconest.hermesbots.data

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GatewayClientTlsPolicyTest {
    @Test
    fun everyBuildVariantUsesPlatformTlsWithoutCleartextFallback() {
        val appFlags = RuntimeEnvironment.getApplication().applicationInfo.flags

        assertEquals(0, appFlags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC)
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("eu.draconest.hermesbots.data.DebugGatewayTls")
        }
    }
}
