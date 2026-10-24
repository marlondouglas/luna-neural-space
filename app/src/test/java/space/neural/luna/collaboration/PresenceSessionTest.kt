package space.neural.luna.collaboration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class PresenceSessionTest {
    @Test fun delayedOfflineCompletesBeforeNewOnlineInSameRoom() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val newestOnline = CompletableDeferred<Unit>()
        val completed = mutableListOf<DriverStatus>()
        var delayOffline = true
        val session = PresenceSession(PresenceGateway { _, update ->
            if (update.status == DriverStatus.OFFLINE && delayOffline) {
                delayOffline = false; entered.complete(Unit); release.await()
            }
            completed += update.status
            if (completed.size >= 3 && update.status == DriverStatus.ONLINE) newestOnline.complete(Unit)
        }, scope)
        try {
            session.start("Geely", { PresenceSession.Update(DriverStatus.ONLINE, false) })
            session.stop()
            withTimeout(1_000) { entered.await() }
            session.start("Geely", { PresenceSession.Update(DriverStatus.ONLINE, false) })
            assertEquals(listOf(DriverStatus.ONLINE), completed)
            release.complete(Unit)
            withTimeout(1_000) { newestOnline.await() }
            assertEquals(listOf(DriverStatus.ONLINE, DriverStatus.OFFLINE, DriverStatus.ONLINE), completed)
        } finally { release.complete(Unit); session.stop(); scope.cancel() }
    }
    @Test fun queuedStaleOfflineDoesNotOverrideRestartedSameRoom() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val releaseInitial = CompletableDeferred<Unit>(); val newestOnline = CompletableDeferred<Unit>()
        val completed = mutableListOf<DriverStatus>()
        var initial = true
        val session = PresenceSession(PresenceGateway { _, update ->
            if (initial) { initial = false; releaseInitial.await() }
            completed += update.status
            if (completed.size == 2) newestOnline.complete(Unit)
        }, scope)
        try {
            session.start("Geely", { PresenceSession.Update(DriverStatus.ONLINE, false) })
            session.stop()
            session.start("Elétricos GV", { PresenceSession.Update(DriverStatus.ONLINE, false) })
            releaseInitial.complete(Unit)
            withTimeout(1_000) { newestOnline.await() }
            assertEquals(listOf(DriverStatus.ONLINE, DriverStatus.ONLINE), completed)
        } finally { releaseInitial.complete(Unit); session.stop(); scope.cancel() }
    }
    @Test fun changingRoomClosesPreviousRoomBeforePublishingNewPresence() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val writes = mutableListOf<Pair<String, DriverStatus>>()
        val session = PresenceSession(PresenceGateway { room, update -> writes += room to update.status }, scope)
        try {
            session.start("Geely", { PresenceSession.Update(DriverStatus.ONLINE, false) })
            session.start("BYD", { PresenceSession.Update(DriverStatus.ONLINE, false) })
            assertEquals(listOf("Geely" to DriverStatus.ONLINE, "Geely" to DriverStatus.OFFLINE, "BYD" to DriverStatus.ONLINE), writes)
        } finally { session.stop(); scope.cancel() }
    }
    @Test fun immediateConsentUpdateCannotBeOvertakenByInFlightHeartbeat() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val releaseInitial = CompletableDeferred<Unit>(); val changed = CompletableDeferred<Unit>()
        val completed = mutableListOf<Boolean>()
        var first = true
        var latest = PresenceSession.Update(DriverStatus.ONLINE, true, -18.85, -41.94)
        val session = PresenceSession(PresenceGateway { _, update ->
            if (first) { first = false; releaseInitial.await() }
            completed += update.share
            if (completed.size == 2) changed.complete(Unit)
        }, scope)
        try {
            session.start("Geely", { latest })
            latest = latest.copy(share = false, latitude = null, longitude = null)
            session.publishNow()
            assertTrue(completed.isEmpty())
            releaseInitial.complete(Unit)
            withTimeout(1_000) { changed.await() }
            assertEquals(listOf(true, false), completed)
        } finally { releaseInitial.complete(Unit); session.stop(); scope.cancel() }
    }
}
