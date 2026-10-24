package space.neural.luna.charging

enum class ConnectorState { AVAILABLE, CHARGING, OCCUPIED, RESERVED, OUT_OF_ORDER, INOPERATIVE, UNKNOWN }
enum class ChargingEvidence { CONFIRMED_LIVE, CONFIRMED_RECENTLY, REPORTED_BY_BUSINESS, UNCONFIRMED, UNAVAILABLE }
data class GeoBounds(val south: Double, val west: Double, val north: Double, val east: Double)
data class ChargingConnector(val id: String, val type: String?, val maxPowerKw: Double?, val state: ConnectorState, val updatedAt: Long?)
data class ChargingTariff(val cents: Long, val unit: String, val source: String, val updatedAt: Long)
data class ChargingStation(
    val id: String, val providerId: String?, val name: String, val address: String,
    val latitude: Double?, val longitude: Double?, val connectors: List<ChargingConnector>,
    val source: String, val verifiedAt: Long?,
    val evidence: ChargingEvidence = ChargingEvidence.UNCONFIRMED,
)
data class ChargingLiveStatus(val connectors: List<ChargingConnector>, val expiresAt: Long) {
    fun effectiveConnectors(now: Long): List<ChargingConnector> =
        if (now >= expiresAt) connectors.map { it.copy(state = ConnectorState.UNKNOWN) } else connectors
}
interface ChargingProvider {
    suspend fun getStations(bounds: GeoBounds): List<ChargingStation>
    suspend fun getLiveStatus(stationId: String): ChargingLiveStatus?
    suspend fun getTariff(stationId: String): ChargingTariff?
}

/** Cadastro público com atribuição. Não representa telemetria nem parceria comercial. */
object ChargingCatalog {
    val stations = listOf(ChargingStation(
        id = "energiza-gv-listagem-publica", providerId = null, name = "Energiza RECHARGE",
        address = "Rua Marechal Deodoro, 130, Centro, Governador Valadares, MG",
        latitude = null, longitude = null, connectors = emptyList(),
        source = "https://carregados.com.br/estacoes/estacao-energiza-recharge-2076768", verifiedAt = null,
    ), ChargingStation(
        id = "posto-rede-hg-gv", providerId = null, name = "Posto Rede HG - NeoCharge",
        address = "Rio-Bahia, 6295, Planalto, Governador Valadares, MG",
        latitude = -18.83168935554188, longitude = -41.988378406359, connectors = emptyList(),
        source = "https://carregados.com.br/estacoes/estacao-posto-rede-hg-502", verifiedAt = null,
    ), ChargingStation(
        id = "plug31-gv", providerId = null, name = "Plug31 Recharge",
        address = "Av. Brasil, 4191, Centro, Governador Valadares, MG",
        latitude = -18.865098190242424, longitude = -41.95125244245794, connectors = emptyList(),
        source = "https://carregados.com.br/estacoes/estacao-lopes-veiculos-2075989", verifiedAt = null,
    ), ChargingStation(
        id = "vae-san-diego-gv", providerId = null, name = "VA-e MOB / San Diego GV",
        address = "R. Ten-Cel. Francisco Rodrigues, 51, Morada do Acampamento, Governador Valadares, MG",
        latitude = null, longitude = null, connectors = emptyList(),
        source = "https://ev.fullcarro.com/plug/brazil/mg/gov-valadares/va-e-mob-san-diego-gv/", verifiedAt = null,
    ), ChargingStation(
        id = "bigmais-lagoa-santa-gv", providerId = null, name = "Bigmais Lagoa Santa",
        address = "R. das Gaivotas, 665, Lagoa Santa, Governador Valadares, MG",
        latitude = -18.846846975734376, longitude = -41.96311945715813, connectors = emptyList(),
        source = "https://carregados.com.br/estacoes/estacao-bigmais-lagoa-santa-2071930", verifiedAt = null,
    ))
}
