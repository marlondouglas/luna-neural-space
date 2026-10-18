package space.neural.luna.dashboard

import space.neural.luna.data.local.ContaFixa
import space.neural.luna.data.local.Gasto
import space.neural.luna.data.local.Limite
import space.neural.luna.data.local.Planejamento
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Contas do planejamento (Configurações > Planejamento):
 * total do mês = contas fixas + limites (envelopes) + renda desejada;
 * meta do dia = total ÷ dias de trabalho do mês; meta da semana = meta do dia × dias
 * de trabalho da semana atual; horas/dia = meta do dia ÷ ganho por hora.
 * Semana do mês: dias 1–7 = semana 1, 8–14 = 2, 15–21 = 3, 22 em diante = 4.
 */
object Plano {
    data class Envelope(val limite: Limite, val gastoCentavos: Long) {
        val restanteCentavos: Long get() = (limite.limiteCentavos - gastoCentavos).coerceAtLeast(0)
        val excessoCentavos: Long get() = (gastoCentavos - limite.limiteCentavos).coerceAtLeast(0)
        val excedido: Boolean get() = gastoCentavos > limite.limiteCentavos
        val fracao: Float get() = if (limite.limiteCentavos <= 0) 1f else gastoCentavos.toFloat() / limite.limiteCentavos
    }

    data class Sugestao(
        val fixasCentavos: Long, val limitesCentavos: Long, val rendaCentavos: Long,
        val totalMesCentavos: Long, val diasTrabalhoMes: Int, val diasTrabalhoSemana: Int,
        val metaDiaCentavos: Long, val metaSemanaCentavos: Long, val horasDia: Double?,
    )

    data class Recalculo(
        val excessoCentavos: Long, val necessarioMesCentavos: Long, val liquidoMesCentavos: Long,
        val faltaCentavos: Long, val diasRestantes: Int,
        val metaDiaCentavos: Long, val metaSemanaCentavos: Long, val horasDia: Double?,
    )

    private fun mes(d: LocalDate) = d.toString().substring(0, 7)
    private fun ceilDiv(a: Long, b: Int): Long = if (b <= 0) a else (a + b - 1) / b

    fun envelopes(limites: List<Limite>, gastosLimites: List<Gasto>, hoje: LocalDate): List<Envelope> {
        val m = mes(hoje)
        return limites.filter { !it.deleted }.map { l ->
            Envelope(l, gastosLimites.filter { !it.deleted && it.limiteId == l.id && it.data.startsWith(m) }.sumOf { it.valorCentavos })
        }
    }

    /** Semana do mês (0–3). */
    fun semanaDoMes(d: LocalDate): Int = ((d.dayOfMonth - 1) / 7).coerceAtMost(3)

    /** Dias marcados: 4 listas de 7 booleanos (seg..dom). */
    fun dias(p: Planejamento): List<List<Boolean>> {
        val partes = p.diasTrabalho.split(",")
        return (0 until 4).map { s ->
            val t = partes.getOrNull(s)?.trim().orEmpty()
            (0 until 7).map { i -> t.getOrNull(i) == '1' }
        }
    }

    fun codificar(dias: List<List<Boolean>>): String =
        dias.joinToString(",") { sem -> sem.joinToString("") { if (it) "1" else "0" } }

    fun trabalha(p: Planejamento, d: LocalDate): Boolean = dias(p)[semanaDoMes(d)][d.dayOfWeek.value - 1]

    fun diasTrabalho(p: Planejamento, de: LocalDate, ate: LocalDate): Int {
        var n = 0; var d = de
        while (!d.isAfter(ate)) { if (trabalha(p, d)) n++; d = d.plusDays(1) }
        return n
    }

    private fun fimSemana(hoje: LocalDate, fimMes: LocalDate): LocalDate =
        minOf(hoje.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)), fimMes)

    private fun horas(metaDia: Long, ganhoHora: Long): Double? = if (ganhoHora > 0) metaDia.toDouble() / ganhoHora else null

    fun sugerir(p: Planejamento, contas: List<ContaFixa>, limites: List<Limite>, hoje: LocalDate): Sugestao {
        val fixas = contas.filter { !it.deleted }.sumOf { it.valorCentavos }
        val lim = limites.filter { !it.deleted }.sumOf { it.limiteCentavos }
        val renda = p.rendaDesejadaCentavos ?: 0
        val total = fixas + lim + renda
        val ini = hoje.withDayOfMonth(1); val fim = hoje.with(TemporalAdjusters.lastDayOfMonth())
        val diasMes = diasTrabalho(p, ini, fim)
        val iniSem = maxOf(hoje.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), ini)
        val diasSem = diasTrabalho(p, iniSem, fimSemana(hoje, fim))
        val metaDia = ceilDiv(total, diasMes)
        return Sugestao(fixas, lim, renda, total, diasMes, diasSem, metaDia, metaDia * diasSem, horas(metaDia, p.ganhoHoraCentavos))
    }

    /**
     * Recalcular o plano para sair do vermelho: o que passou dos limites entra no
     * necessário do mês; o que falta é dividido pelos dias de trabalho que restam (hoje incluso).
     */
    fun recalcular(
        p: Planejamento, contas: List<ContaFixa>, limites: List<Limite>, envelopes: List<Envelope>,
        liquidoMesCentavos: Long, hoje: LocalDate, liquidoSemanaAntesDeHojeCentavos: Long = 0,
    ): Recalculo {
        val s = sugerir(p, contas, limites, hoje)
        val excesso = envelopes.sumOf { it.excessoCentavos }
        val necessario = s.totalMesCentavos + excesso
        val falta = (necessario - liquidoMesCentavos).coerceAtLeast(0)
        val fim = hoje.with(TemporalAdjusters.lastDayOfMonth())
        val rest = diasTrabalho(p, hoje, fim).coerceAtLeast(1)
        val metaDia = ceilDiv(falta, rest)
        val diasSem = diasTrabalho(p, hoje, fimSemana(hoje, fim)).coerceAtLeast(1)
        return Recalculo(excesso, necessario, liquidoMesCentavos, falta, rest, metaDia, liquidoSemanaAntesDeHojeCentavos.coerceAtLeast(0) + metaDia * diasSem, horas(metaDia, p.ganhoHoraCentavos))
    }
}
