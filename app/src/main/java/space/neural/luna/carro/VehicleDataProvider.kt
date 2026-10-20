package space.neural.luna.carro

/**
 * Dados do veículo (EX Sapo / Geely).
 * Só retorna valores reais quando houver conexão verificada com a central.
 * Sem telemetria válida, indica indisponível — nunca inventa bateria, autonomia ou consumo.
 */
object VehicleDataProvider {
    data class Estado(
        val disponivel: Boolean = false,
        val bateriaPercent: Int? = null,
        val autonomiaKm: Int? = null,
        val consumoKwh100km: Double? = null,
        val mensagem: String = "Dados do veículo indisponíveis. Conecte a central do carro."
    )

    fun atual(): Estado = Estado() // Sem protocolo confirmado no momento

    fun estaEmModoCarro(prefs: android.content.SharedPreferences): Boolean =
        prefs.getString("dispositivo", "PHONE") == "CARRO"
}
