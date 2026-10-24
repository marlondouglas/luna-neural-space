package space.neural.luna.collaboration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface PresenceGateway {
    suspend fun publish(room: String, update: PresenceSession.Update)
}

/** Lifecycle owner starts on consent/connect, stops on screen/session exit.
 * A process kill is handled by server timestamp expiry, never by stale ONLINE data.
 * Location must come from a fresh authorized GPS fix supplied by the caller.
 */
class PresenceSession(private val gateway: PresenceGateway, private val scope: CoroutineScope) {
    constructor(repository: CommunityRepository, scope: CoroutineScope) : this(PresenceGateway { room, update ->
        repository.publishPresence(room, update.status, update.share, update.latitude, update.longitude, update.profile, update.accuracyMeters, update.bearingDegrees, update.locationTimestamp)
    }, scope)
    data class Update(val status: DriverStatus, val share: Boolean, val latitude: Double? = null, val longitude: Double? = null, val profile: DriverProfile = DriverProfile(), val accuracyMeters: Double? = null, val bearingDegrees: Double? = null, val locationTimestamp: Long? = null)
    private var heartbeat: Job? = null
    private var currentRoom: String? = null
    private var generation = 0L
    private val stateLock = Any()
    private val writeLock = Mutex()
    private var latestUpdate: (() -> Update)? = null
    private var reportError: (Exception) -> Unit = {}
    fun start(room: String, latest: () -> Update, onError: (Exception) -> Unit = {}) {
        stop()
        synchronized(stateLock) {
          currentRoom = room
          latestUpdate = latest
          reportError = onError
          val sessionGeneration = ++generation
          heartbeat = scope.launch {
            while (isActive) {
                try {
                    publishCurrent(room, sessionGeneration)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { onError(e) }
                delay(30_000)
            }
          }
        }
    }
    /** Immediate status/consent/GPS update through the same queue as heartbeat and stop. */
    fun publishNow() {
        val target = synchronized(stateLock) { Triple(currentRoom, generation, reportError) }
        val room = target.first ?: return
        scope.launch {
            try { publishCurrent(room, target.second) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { target.third(e) }
        }
    }
    private suspend fun publishCurrent(room: String, sessionGeneration: Long) = writeLock.withLock {
        val latest = synchronized(stateLock) {
            if (generation == sessionGeneration && currentRoom == room) latestUpdate else null
        }
        if (latest != null) {
            // Snapshot only after taking the write lock; queued updates cannot restore an old consent.
            val update = latest()
            // Awaiting a Firebase Task is cancellable, but its network write is not.
            // Keep the lock until the actual write completes, so OFFLINE cannot overtake it.
            withContext(NonCancellable) { gateway.publish(room, update) }
        }
    }
    fun stop() {
        val stopped = synchronized(stateLock) {
            heartbeat?.cancel(); heartbeat = null
            val room = currentRoom; currentRoom = null
            latestUpdate = null; reportError = {}
            room to ++generation
        }
        val room = stopped.first ?: return
        scope.launch {
            writeLock.withLock {
                val superseded = synchronized(stateLock) {
                    currentRoom?.let { CommunityRooms.slug(it) == CommunityRooms.slug(room) } == true && generation != stopped.second
                }
                if (!superseded) runCatching {
                    withContext(NonCancellable) { gateway.publish(room, Update(DriverStatus.OFFLINE, false)) }
                }
            }
        }
    }
}
