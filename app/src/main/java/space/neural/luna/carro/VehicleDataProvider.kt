package space.neural.luna.carro

/**
 * Stub para dados do veículo (EX Sapo / Geely). 
 * Quando as APIs estiverem disponíveis, implementar estados reais (bateria, autonomia, consumo).
 * Por enquanto retorna valores estáticos / placeholder.
 */
object VehicleDataProvider {
    data class Estado(
        val bateriaPercent: Int = 75,
        val autonomiaKm: Int = 260,
        val consumoKwh100km: Double = 12.0,
        val online: Boolean = true
    )

    fun atual(): Estado = Estado()

    fun estaEmModoCarro(prefs: android.content.SharedPreferences): Boolean =
        prefs.getString("dispositivo", "PHONE") == "CARRO"
}
