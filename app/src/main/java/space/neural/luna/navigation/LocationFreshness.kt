package space.neural.luna.navigation

/** A heartbeat must never turn an old GPS fix into a current position. */
object LocationFreshness {
    fun isCurrent(timestamp: Long, now: Long = System.currentTimeMillis()): Boolean =
        timestamp > 0 && timestamp <= now && now - timestamp <= 90_000L

    fun usable(permissionGranted: Boolean, timestamp: Long, latitude: Double, longitude: Double,
        accuracyMeters: Float? = null, now: Long = System.currentTimeMillis()): Boolean =
        permissionGranted && isCurrent(timestamp, now) && latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
            (accuracyMeters == null || accuracyMeters.isFinite() && accuracyMeters >= 0f)

    /** Preserve the latest measured fix; cached samples from another provider must not move time backwards. */
    fun notOlderThan(timestamp: Long, previousTimestamp: Long?): Boolean = previousTimestamp == null || timestamp >= previousTimestamp
}
