package space.neural.luna.charging

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** No polling: cache an already authorized adapter only when requested by its caller. */
class CachedChargingProvider(
    private val delegate: ChargingProvider,
    private val intervalMillis: Long = 10 * 60 * 1000L,
    private val now: () -> Long = System::currentTimeMillis,
) : ChargingProvider {
    init { require(intervalMillis >= 1_000L) }
    private data class Entry<T>(val value: T, val expires: Long)
    private val lock = Mutex()
    private val stations = mutableMapOf<GeoBounds, Entry<List<ChargingStation>>>()
    private val live = mutableMapOf<String, Entry<ChargingLiveStatus?>>()
    private val tariffs = mutableMapOf<String, Entry<ChargingTariff?>>()
    override suspend fun getStations(bounds: GeoBounds): List<ChargingStation> = lock.withLock {
        stations[bounds]?.takeIf { it.expires > now() }?.value ?: delegate.getStations(bounds).also {
            if (stations.size >= 32) stations.clear()
            stations[bounds] = Entry(it, now() + intervalMillis)
        }
    }
    override suspend fun getLiveStatus(stationId: String): ChargingLiveStatus? = lock.withLock {
        val cached = live[stationId]
        if (cached != null && cached.expires > now()) return@withLock cached.value
        delegate.getLiveStatus(stationId).also {
            if (live.size >= 100) live.clear()
            live[stationId] = Entry(it, minOf(now() + intervalMillis, it?.expiresAt ?: Long.MAX_VALUE))
        }
    }
    override suspend fun getTariff(stationId: String): ChargingTariff? = lock.withLock {
        val cached = tariffs[stationId]
        if (cached != null && cached.expires > now()) return@withLock cached.value
        delegate.getTariff(stationId).also {
            if (tariffs.size >= 100) tariffs.clear()
            tariffs[stationId] = Entry(it, now() + intervalMillis)
        }
    }
}
