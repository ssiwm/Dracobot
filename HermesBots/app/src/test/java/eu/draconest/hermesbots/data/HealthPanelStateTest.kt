package eu.draconest.hermesbots.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthPanelStateTest {
    @Test
    fun healthPanelCanOpenAndCloseWithoutChangingOutboxResolutionState() {
        val viewModel = AppViewModel()

        assertFalse(viewModel.viewHealth.value)
        viewModel.openHealth()
        assertTrue(viewModel.viewHealth.value)
        viewModel.closeHealth()
        assertFalse(viewModel.viewHealth.value)
    }
}
