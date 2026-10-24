package space.neural.luna.partners

import android.content.Context
import space.neural.luna.charging.ChargingCatalog

object PartnerCatalog {
    private val maintainedAt = java.time.Instant.parse("2026-10-23T00:00:00Z").toEpochMilli()
    val partners: List<Partner> = ChargingCatalog.stations.map {
        Partner(it.id, it.name, it.address, PartnerCategory.CHARGING, it.source,
            it.latitude, it.longitude, updatedAt = maintainedAt)
    } + listOf(
        Partner("gpcar-insulfilm", "GPcar Insulfilm", null, PartnerCategory.SHOP,
            "Solicitação do usuário — comando mestre ROTA GV 0.1.15, 23/10/2026",
            offer = PartnerOffer("Oferta solicitada; cupom e condições não recuperados. Aguardando confirmação comercial."),
            updatedAt = maintainedAt, evidence = PartnerEvidence.USER_REQUESTED),
        Partner("studio-tiffany-carla", "Studio Tiffany Carla", null, PartnerCategory.SERVICES,
            "Solicitação do usuário — comando mestre ROTA GV 0.1.15, 23/10/2026",
            offer = PartnerOffer("Oferta solicitada; cupom e condições não recuperados. Aguardando confirmação comercial."),
            updatedAt = maintainedAt, evidence = PartnerEvidence.USER_REQUESTED),
    )
    fun find(id: String): Partner? = partners.firstOrNull { it.id == id }
    fun search(query: String = "", category: PartnerCategory? = null): List<Partner> = partners.filter {
        (category == null || it.category == category) && (query.isBlank() ||
            it.name.contains(query.trim(), true) || it.address.orEmpty().contains(query.trim(), true) || it.category.label.contains(query.trim(), true))
    }
}

class PartnerFavorites(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("partner_favorites_v1", Context.MODE_PRIVATE)
    fun ids(): Set<String> = prefs.getStringSet("ids", emptySet()).orEmpty().toSet()
    fun contains(id: String): Boolean = id in ids()
    @Synchronized fun toggle(id: String): Boolean {
        require(PartnerCatalog.find(id) != null) { "Parceiro não encontrado" }
        val selected = ids().toMutableSet()
        val enabled = if (id in selected) { selected.remove(id); false } else { selected.add(id); true }
        prefs.edit().putStringSet("ids", selected).apply()
        return enabled
    }
}
