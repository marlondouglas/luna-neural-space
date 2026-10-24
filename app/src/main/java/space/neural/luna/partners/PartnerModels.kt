package space.neural.luna.partners

enum class PartnerCategory(val label: String) {
    CHARGING("Eletropostos"), FUEL("Postos de combustível"), WORKSHOP("Oficinas"),
    CAR_WASH("Lava-jatos"), FOOD("Alimentação"), SHOP("Lojas e acessórios"), SERVICES("Serviços parceiros"),
    SUPERMARKET("Supermercados"), TIRES("Pneus"), OIL_CHANGE("Troca de óleo")
}
enum class OfferState { AWAITING_COMMERCIAL_CONFIRMATION, CONFIRMED, EXPIRED }
enum class PartnerEvidence { PUBLIC_DIRECTORY, USER_REQUESTED, BUSINESS_CONFIRMED }
data class PartnerOffer(val description: String, val code: String? = null, val state: OfferState = OfferState.AWAITING_COMMERCIAL_CONFIRMATION,
    val priceCents: Long? = null, val unit: String? = null, val validFrom: Long? = null,
    val validUntil: Long? = null, val source: String? = null, val updatedAt: Long? = null) {
    val usable get() = usableAt(System.currentTimeMillis())
    fun usableAt(now: Long): Boolean = state == OfferState.CONFIRMED && !source.isNullOrBlank() &&
        (validFrom == null || now >= validFrom) && (validUntil == null || now < validUntil) &&
        (validFrom == null || validUntil == null || validUntil > validFrom) &&
        (priceCents == null || priceCents >= 0 && !unit.isNullOrBlank())
}
data class Partner(
    val id: String, val name: String, val address: String?, val category: PartnerCategory,
    val source: String, val latitude: Double? = null, val longitude: Double? = null,
    val imageAsset: String? = null, val offer: PartnerOffer? = null,
    /** Date of catalog entry maintenance, NOT live status or establishment confirmation. */
    val updatedAt: Long? = null, val evidence: PartnerEvidence = PartnerEvidence.PUBLIC_DIRECTORY,
    val whatsapp: String? = null, val phone: String? = null, val instagram: String? = null,
    val tariff: PartnerTariff? = null, val openingHours: PartnerOpeningHours? = null,
    val connectorReadings: List<PartnerConnectorReading> = emptyList(),
) {
    val hasCoordinates get() = latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
    val statusLabel get() = when (evidence) {
        PartnerEvidence.PUBLIC_DIRECTORY -> "Cadastro público · confirme antes de ir"
        PartnerEvidence.USER_REQUESTED -> "Aguardando confirmação comercial"
        PartnerEvidence.BUSINESS_CONFIRMED -> "Cadastro confirmado pelo estabelecimento"
    }
}

enum class PartnerDataState { LIVE, RECENT, BUSINESS_REPORTED, UNCONFIRMED, UNAVAILABLE }
/** Commercial tariff and operating hours have independent sources and validity from each connector. */
data class PartnerTariff(val cents: Long, val unit: String, val source: String, val updatedAt: Long,
    val validUntil: Long, val state: PartnerDataState = PartnerDataState.UNCONFIRMED) {
    fun usableAt(now: Long) = cents >= 0 && unit.isNotBlank() && source.isNotBlank() && updatedAt > 0 && now >= updatedAt &&
        now < validUntil && state in setOf(PartnerDataState.LIVE, PartnerDataState.RECENT, PartnerDataState.BUSINESS_REPORTED)
}
data class PartnerOpeningHours(val description: String, val source: String, val updatedAt: Long)
data class PartnerConnectorReading(val id: String, val type: String?, val maxPowerKw: Double?,
    val status: space.neural.luna.charging.ConnectorState, val source: String, val updatedAt: Long,
    val validUntil: Long) {
    fun statusAt(now: Long): space.neural.luna.charging.ConnectorState =
        if (id.isNotBlank() && source.isNotBlank() && updatedAt > 0 && now >= updatedAt && now < validUntil) status
        else space.neural.luna.charging.ConnectorState.UNKNOWN
}
