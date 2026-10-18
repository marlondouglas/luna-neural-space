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

    /**
     * v0.1.7: id fixo por dia e número ("2026-10-18_1"). Dois aparelhos que iniciam o turno 1 do mesmo dia
     * (ex.: um deles sem internet) mexem no MESMO registro, que se resolve pelo "último a alterar vence",
     * em vez de criar dois turnos 1/4.
     */
    fun idDoTurno(dia: String, numero: Int): String = "${dia}_${numero.coerceIn(1, TOTAL)}"

    /** Iniciar: usa o turno pronto (zerado) ou cria o próximo número do dia. */
    fun iniciar(s: Situacao, hoje: String, agora: Long): Turno {
        val pronto = s.turno?.takeIf { it.estado == EstadoTurno.PRONTO }
        val n = s.numero.coerceIn(1, TOTAL)
        return pronto?.copy(estado = EstadoTurno.ATIVO, inicio = agora, ultimoEvento = agora, fim = null)
            ?: Turno(id = idDoTurno(hoje, n), data = hoje, inicio = agora, ultimoEvento = agora, numero = n)
    }

    /** Zerar tempo: contador volta a 00:00:00 e o turno fica "pronto para iniciar". */
    fun zerar(t: Turno, agora: Long): Turno =
        t.copy(estado = EstadoTurno.PRONTO, trabalhoMs = 0, paradoMs = 0, inicio = agora, ultimoEvento = agora, fim = null)

    // ---------------- Zerar / voltar (v0.1.5) ----------------

    /** Turno fechado (ou concluído e reaberto) imediatamente antes do turno [numero] de hoje. */
    fun anteriorFechado(dia: List<Turno>, numero: Int): Turno? =
        dia.filter { it.numero < numero && (it.estado == EstadoTurno.FECHADO || it.estado == EstadoTurno.CONCLUIDO) }
            .maxWithOrNull(compareBy({ it.numero }, { it.inicio }))

    /** Resultado de "Voltar para o turno anterior": [excluir] = turno N atual (pode não existir), [reaberto] = turno N−1. */
    data class Volta(val excluir: Turno?, val reaberto: Turno)

    /** Até quanto tempo depois de fechar ainda é "desfazer na hora" (o tempo conta como trabalho). */
    const val DESFAZER_NA_HORA_MS = 2 * 60_000L

    /**
     * Desfaz o último "Fechar turno": apaga o turno N atual e reabre o N−1 (sem horário de fim).
     * - N ainda não iniciado: se voltou logo (≤ 2 min), N−1 continua contando como se nunca tivesse
     *   fechado; senão o intervalo vira tempo parado e o turno N−1 volta PAUSADO (é só tocar em Retomar).
     * - N já iniciado: o tempo de N (trabalho e parado) passa para o N−1, que continua no mesmo estado
     *   de N (contando ou pausado); o intervalo entre fechar N−1 e iniciar N conta como parado.
     */
    fun voltar(s: Situacao, dia: List<Turno>, agora: Long): Volta? {
        if (s.numero <= 1) return null
        val ant = anteriorFechado(dia, s.numero) ?: return null
        val atual = s.turno?.takeIf { it.numero == s.numero && it.estado in abertos }
        val fechouEm = ant.fim ?: ant.ultimoEvento
        val reaberto = if (atual == null || atual.estado == EstadoTurno.PRONTO) {
            val gap = (agora - fechouEm).coerceAtLeast(0)
            if (gap <= DESFAZER_NA_HORA_MS) ant.copy(estado = EstadoTurno.ATIVO, fim = null, ultimoEvento = fechouEm)
            else ant.copy(estado = EstadoTurno.PAUSADO, fim = null, ultimoEvento = agora, paradoMs = ant.paradoMs + gap)
        } else {
            val n = mudar(atual, atual.estado, agora) // soma o tempo corrente de N
            val entre = (atual.inicio - fechouEm).coerceAtLeast(0)
            ant.copy(estado = n.estado, fim = null, ultimoEvento = agora,
                trabalhoMs = ant.trabalhoMs + n.trabalhoMs, paradoMs = ant.paradoMs + n.paradoMs + entre)
        }
        return Volta(atual, reaberto)
    }

    /** Opções do diálogo "Zerar tempo". */
    enum class OpcaoZerar { SO_ESTE, VOLTAR, DIA }

    /**
     * Quais opções mostrar: "só este turno" quando o turno atual já começou; "voltar" quando N > 1
     * e existe o turno N−1 fechado hoje; "zerar o dia" quando há qualquer turno hoje.
     */
    fun opcoesZerar(s: Situacao, dia: List<Turno>): List<OpcaoZerar> = buildList {
        if (s.emAndamento && s.turno != null) add(OpcaoZerar.SO_ESTE)
        if (s.emAndamento || s.fase == Fase.PRONTO) if (voltar(s, dia, s.turno?.ultimoEvento ?: 0L) != null) add(OpcaoZerar.VOLTAR)
        if (dia.isNotEmpty() && s.fase != Fase.CONCLUIDO) add(OpcaoZerar.DIA)
    }

    /** Tempo de trabalho até [agora], recalculado pelos horários salvos. */
    fun trabalhoMs(t: Turno?, agora: Long): Long =
        if (t == null) 0 else t.trabalhoMs + if (t.estado == EstadoTurno.ATIVO) (agora - t.ultimoEvento).coerceAtLeast(0) else 0
}
