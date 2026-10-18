package space.neural.luna.data.local

/**
 * Máquina de estados dos turnos (ROTA_DOS_PARCEIROS_ESPECIFICACAO.md, v0.1.4).
 *
 * - O dia tem exatamente [TOTAL] turnos (1/4 … 4/4).
 * - Fechar o turno N (1–3) fecha e o painel já mostra o turno N+1 "pronto para iniciar".
 * - Depois de fechar o 4º não existe 5º: só "Retomar" (reabre o 4º e continua contando)
 *   ou "Concluir dia".
 * - "Zerar tempo" para o contador do turno atual e volta a 00:00:00 (turno fica pronto).
 * - O tempo vem só de horários de relógio salvos (ultimoEvento + acumulados), então
 *   é recalculado certo depois de fechar o app ou reiniciar o aparelho.
 * Funções puras: o repositório só salva o resultado.
 */
object MaquinaTurno {
    const val TOTAL = 4

    enum class Fase { PRONTO, ATIVO, PAUSADO, CAFE, ULTIMO_FECHADO, CONCLUIDO }

    /**
     * O que o card de turno mostra. [turno] é o registro atual (null quando o próximo
     * turno ainda não foi criado); [anterior] é o último turno fechado (para "Corrigir").
     */
    data class Situacao(val numero: Int, val fase: Fase, val turno: Turno?, val anterior: Turno?) {
        val emAndamento get() = fase == Fase.ATIVO || fase == Fase.PAUSADO || fase == Fase.CAFE
    }

    private val abertos = setOf(EstadoTurno.PRONTO, EstadoTurno.ATIVO, EstadoTurno.PAUSADO, EstadoTurno.CAFE)

    fun doDia(turnos: List<Turno>, dia: String): List<Turno> =
        turnos.filter { !it.deleted && it.data == dia }.sortedWith(compareBy({ it.numero }, { it.inicio }))

    fun situacao(turnos: List<Turno>, hoje: String): Situacao {
        val dia = doDia(turnos, hoje)
        val aberto = turnos.filter { !it.deleted && it.estado in abertos }.maxByOrNull { it.ultimoEvento }
        if (aberto != null) {
            val fase = when (aberto.estado) {
                EstadoTurno.ATIVO -> Fase.ATIVO; EstadoTurno.PAUSADO -> Fase.PAUSADO
                EstadoTurno.CAFE -> Fase.CAFE; else -> Fase.PRONTO
            }
            val ant = dia.lastOrNull { it.numero < aberto.numero && it.estado == EstadoTurno.FECHADO }
            return Situacao(aberto.numero, fase, aberto, ant)
        }
        val ultimo = dia.lastOrNull() ?: return Situacao(1, Fase.PRONTO, null, null)
        if (ultimo.estado == EstadoTurno.CONCLUIDO) return Situacao(ultimo.numero, Fase.CONCLUIDO, ultimo, null)
        return if (ultimo.numero < TOTAL) Situacao(ultimo.numero + 1, Fase.PRONTO, null, ultimo)
        else Situacao(TOTAL, Fase.ULTIMO_FECHADO, ultimo, ultimo)
    }

    /** Muda o estado somando o tempo desde o último evento (ATIVO → trabalho; pausa/café → parado). */
    fun mudar(t: Turno, novo: String, agora: Long): Turno {
        val corrido = (agora - t.ultimoEvento).coerceAtLeast(0)
        val acum = when (t.estado) {
            EstadoTurno.ATIVO -> t.copy(trabalhoMs = t.trabalhoMs + corrido)
            EstadoTurno.PAUSADO, EstadoTurno.CAFE -> t.copy(paradoMs = t.paradoMs + corrido)
            else -> t
        }
        val fim = when (novo) {
            EstadoTurno.FECHADO, EstadoTurno.CONCLUIDO -> acum.fim ?: agora
            else -> null
        }
        return acum.copy(estado = novo, ultimoEvento = agora, fim = fim)
    }

    /** Iniciar: usa o turno pronto (zerado) ou cria o próximo número do dia. */
    fun iniciar(s: Situacao, hoje: String, agora: Long): Turno {
        val pronto = s.turno?.takeIf { it.estado == EstadoTurno.PRONTO }
        return pronto?.copy(estado = EstadoTurno.ATIVO, inicio = agora, ultimoEvento = agora, fim = null)
            ?: Turno(data = hoje, inicio = agora, ultimoEvento = agora, numero = s.numero.coerceIn(1, TOTAL))
    }

    /** Zerar tempo: contador volta a 00:00:00 e o turno fica "pronto para iniciar". */
    fun zerar(t: Turno, agora: Long): Turno =
        t.copy(estado = EstadoTurno.PRONTO, trabalhoMs = 0, paradoMs = 0, inicio = agora, ultimoEvento = agora, fim = null)

    /** Tempo de trabalho até [agora], recalculado pelos horários salvos. */
    fun trabalhoMs(t: Turno?, agora: Long): Long =
        if (t == null) 0 else t.trabalhoMs + if (t.estado == EstadoTurno.ATIVO) (agora - t.ultimoEvento).coerceAtLeast(0) else 0
}
