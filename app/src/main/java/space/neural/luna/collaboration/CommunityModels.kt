package space.neural.luna.collaboration

/** Stable wire IDs: changing a label must never split an existing radio room. */
object CommunityRooms {
    val labels = linkedMapOf("luna-eletricos-gv" to "Geely", "luna-byd" to "BYD", "luna-geral-gv" to "ROTA GV", "luna-combustao" to "Combustão")
    fun slug(value: String): String = when (value.lowercase()) {
        "geely", "elétricos gv", "eletricos gv", "luna-geely" -> "luna-eletricos-gv"
        "byd" -> "luna-byd"
        "rota gv", "geral gv", "luna-rota-gv" -> "luna-geral-gv"
        "combustão", "combustao" -> "luna-combustao"
        "aeroporto gv" -> "luna-aeroporto-gv"
        else -> value.also { require(it.matches(Regex("luna-[a-z0-9_-]{1,59}"))) }
    }
}

enum class DriverStatus { ONLINE, OCUPADO, OFFLINE }
enum class AlertCategory { TRANSITO_LENTO, TRANSITO_PARADO, ACIDENTE, OBRAS, BURACO, ALAGAMENTO, VIA_BLOQUEADA, OBJETO, VEICULO_PARADO, OUTRO }
data class DriverPresence(val uid: String, val name: String, val status: DriverStatus, val shareLocation: Boolean, val latitude: Double?, val longitude: Double?, val updatedAt: Long,
    val vehicleModel: String? = null, val vehicleColor: String? = null, val licensePlate: String? = null,
    val accuracyMeters: Double? = null, val bearingDegrees: Double? = null, val locationTimestamp: Long? = null) {
    fun recent(now: Long) = updatedAt > 0 && now - updatedAt in 0..90_000
    fun visibleOnMap(now: Long) = status == DriverStatus.ONLINE && shareLocation && latitude != null && longitude != null && recent(now)
        && accuracyMeters != null && accuracyMeters.isFinite() && accuracyMeters in 0.0..100.0
        && locationTimestamp != null && now - locationTimestamp in 0..90_000
}
data class RoomMessage(val id: String, val authorUid: String, val authorName: String, val text: String, val createdAt: Long, val pending: Boolean)
data class CommunityEvent(val id: String, val authorUid: String, val category: String, val latitude: Double, val longitude: Double, val description: String, val createdAt: Long, val expiresAt: Long, val active: Boolean,
    val sourceRoom: String? = null, val authorName: String? = null, val vehicleModel: String? = null, val vehicleColor: String? = null, val licensePlate: String? = null, val global: Boolean = false) {
    fun valid(now: Long) = active && expiresAt > now
}
data class GlobalSosEligibility(val enabled: Boolean, val rooms: Set<String> = emptySet()) {
    val reason: String? get() = if (enabled) null else "Esta conta ainda não está habilitada para compartilhar SOS entre as quatro salas."
}
