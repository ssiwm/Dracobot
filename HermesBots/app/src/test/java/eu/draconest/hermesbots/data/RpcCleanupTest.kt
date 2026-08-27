package eu.draconest.hermesbots.data

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RpcCleanupTest {
    @Test
    fun failedSendRemovesPendingRpc() = runBlocking {
        val pending = ConcurrentHashMap<Int, CompletableDeferred<String>>()
        val failure = try {
            awaitRpcResponse(pending, 42, CompletableDeferred()) { false }
            error("expected failed send")
        } catch (e: IllegalStateException) {
            e
        }

        assertEquals("Nie udało się wysłać ramki", failure.message)
        assertTrue(pending.isEmpty())
    }

    @Test
    fun successfulResponseRemovesPendingRpc() = runBlocking {
        val pending = ConcurrentHashMap<Int, CompletableDeferred<String>>()
        val response = CompletableDeferred<String>().apply { complete("ok") }

        assertEquals("ok", awaitRpcResponse(pending, 7, response) { true })
        assertTrue(pending.isEmpty())
    }

    @Test
    fun cancelledAwaitRemovesPendingRpc() = runBlocking {
        val pending = ConcurrentHashMap<Int, CompletableDeferred<String>>()
        try {
            withTimeout(25) {
                awaitRpcResponse(pending, 8, CompletableDeferred()) { true }
            }
        } catch (_: TimeoutCancellationException) {
            // Expected: await is cancelled before a gateway response arrives.
        }

        assertTrue(pending.isEmpty())
    }

    @Test
    fun staleSocketCallbacksAreIgnored() {
        assertTrue(shouldHandleConnectionCallback(callbackEpoch = 12, currentEpoch = 12))
        assertTrue(!shouldHandleConnectionCallback(callbackEpoch = 11, currentEpoch = 12))
    }

    @Test
    fun staleConnectCannotReplaceANewerInstalledSocket() {
        val connections = ConnectionEpochState<Any>()
        val staleEpoch = connections.beginConnection().epoch
        val currentEpoch = connections.beginConnection().epoch
        val currentSocket = Any()

        assertTrue(connections.installIfCurrent(currentEpoch, currentSocket))
        assertTrue(!connections.installIfCurrent(staleEpoch, Any()))
        assertTrue(connections.currentSocket() === currentSocket)
    }

    @Test
    fun staleFailedSendCannotDisconnectNewerInstalledTransport() {
        val connections = ConnectionEpochState<Any>()
        val oldEpoch = connections.beginConnection().epoch
        val oldSocket = Any()
        assertTrue(connections.installIfCurrent(oldEpoch, oldSocket))
        val oldSnapshot = connections.snapshot()

        val newEpoch = connections.beginConnection().epoch
        val newSocket = Any()
        assertTrue(connections.installIfCurrent(newEpoch, newSocket))

        assertFalse(connections.markSendFailedIfCurrent(oldSnapshot))
        assertTrue(connections.snapshot().socket === newSocket)
        assertEquals(LinkState.UP, connections.currentLinkState())
    }

    @Test
    fun resetBeforeSnapshotSendPreventsOldSocketTransmission() {
        val connections = ConnectionEpochState<Any>()
        val oldEpoch = connections.beginConnection().epoch
        val oldSocket = Any()
        assertTrue(connections.installIfCurrent(oldEpoch, oldSocket))
        val oldSnapshot = connections.snapshot()
        connections.beginConnection()
        var oldSocketSent = false

        assertFalse(connections.sendIfCurrent(oldSnapshot) {
            oldSocketSent = true
            true
        })
        assertFalse(oldSocketSent)
    }

    @Test
    fun closeCancelsEveryPendingRpcBeforeClearingMap() {
        val pending = ConcurrentHashMap<Int, CompletableDeferred<String>>()
        val first = CompletableDeferred<String>()
        val second = CompletableDeferred<String>()
        pending[1] = first
        pending[2] = second

        cancelPendingRequests(pending)

        assertTrue(pending.isEmpty())
        assertTrue(first.isCancelled)
        assertTrue(second.isCancelled)
    }
}
