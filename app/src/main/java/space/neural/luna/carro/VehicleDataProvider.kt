package space.neural.luna.carro

/**
 * Adaptador isolado para dados do veículo via EX Sapo ou API autorizada.
 * PDF: somente ler o que for comprovadamente exposto. Nunca inventar leituras.
 * Estados: disponível / indisponível / desatualizado.
 */
object VehicleDataProvider {
    enum class Estado { DISPONIVEL, INDISPONIVEL, DESATUALIZADO }

    data class Leitura(
        val bateriaPercent: Int? = null,
        val autonomiaKm: Int? = null,
        val velocidadeKmh: Int? = null,
        val estado: Estado = Estado.INDISPONIVEL,
        val atualizadoEm: Long = 0L
    )

    /**
     * Tenta ler dados do EX Sapo. Se a interface não estiver disponível ou
     * não retornar valores válidos, devolve INDISPONIVEL.
     * Implementação real depende de testes no aparelho Geely.
     */
    fun ler(): Leitura {
        // Stub: no aparelho real, conectar via ContentProvider ou Intent do Sapo.
        // Por enquanto retorna indisponível de forma explícita.
        return Leitura(estado = Estado.INDISPONIVEL)
    }

    fun disponivel(): Boolean = ler().estado == Estado.DISPONIVEL
}
