package space.neural.luna.partners

import kotlin.math.*

data class PartnerPosition(val latitude: Double, val longitude: Double, val timestamp: Long, val accuracyMeters: Float)

object PartnerDistance {
    fun meters(latitude: Double?, longitude: Double?, position: PartnerPosition?, now: Long): Double? {
        position ?: return null
        fun valid(lat: Double, lon: Double) = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0
        if (latitude == null || longitude == null || !valid(latitude, longitude) || !valid(position.latitude, position.longitude) ||
            now - position.timestamp !in 0..60_000 || !position.accuracyMeters.isFinite() || position.accuracyMeters !in 0f..100f) return null
        val lat = Math.toRadians(latitude - position.latitude)
        val lon = Math.toRadians(longitude - position.longitude)
        val a = sin(lat / 2).pow(2) + cos(Math.toRadians(latitude)) * cos(Math.toRadians(position.latitude)) * sin(lon / 2).pow(2)
        return 6_371_000 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
