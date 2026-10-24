package space.neural.luna.dashboard

import space.neural.luna.data.local.CategoriaGasto
import space.neural.luna.data.local.Ganho
import space.neural.luna.data.local.Gasto
import space.neural.luna.data.local.Meta
import space.neural.luna.data.local.TipoMeta
import space.neural.luna.data.local.Turno
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Período do seletor Hoje / Semana / Mês / Ano. */
enum class Periodo(val rotulo: String, val comparacao: String) {
    HOJE("Hoje", "vs ontem"),
    SEMANA("Semana", "vs semana passada"),
    MES("Mês", "vs mês passado"),
    ANO("Ano", "vs ano passado"),
}

/**
 * Números do período escolhido, comparados com o mesmo trecho do período anterior
 * (ex.: semana até hoje × semana passada até o mesmo dia). Sem Android, testável.
 */
data class Estatisticas(
    val periodo: Periodo,
    val inicio: LocalDate,
    val fim: LocalDate,
    val brutoCentavos: Long,
    val gastosCentavos: Long,
    val corridas: Int,
    val trabalhoMs: Long,
    val brutoAnterior: Long,
    val liquidoAnterior: Long,
    val corridasAnterior: Int,
    val trabalhoAnteriorMs: Long,
    val meta: Calculos.ProgressoMeta,
    val serie: List<Ponto>,
    val porApp: List<Calculos.PorApp>,
    val gastosPorCategoria: List<Pair<String, Long>>,
    val ultimos: List<Lancamento>,
) {
    data class Ponto(val rotulo: String, val liquidoCentavos: Long, val porApp: Map<String, Long>, val atual: Boolean)
    data class Lancamento(val id: String, val data: String, val ganho: Boolean, val nome: String, val centavos: Long, val quando: Long, val descricao: String?)

    val liquidoCentavos get() = brutoCentavos - gastosCentavos
    val porHora: Long? get() = Calculos.porHoraCentavos(liquidoCentavos, trabalhoMs)
    val porHoraAnterior: Long? get() = Calculos.porHoraCentavos(liquidoAnterior, trabalhoAnteriorMs)

    /** Variação em % (null se não há base para comparar). */
    fun variacao(atual: Long, anterior: Long): Double? =
        if (anterior == 0L) null else (atual.toDouble() - anterior.toDouble()) * 100.0 / kotlin.math.abs(anterior.toDouble())

    companion object {
        private val pt = Locale.forLanguageTag("pt-BR")
        const val AJUSTE = "Ajuste para o total do dia"

        /** [inicio, fim] do período até hoje e o mesmo trecho do período anterior. */
        fun intervalos(p: Periodo, hoje: LocalDate): Pair<Pair<LocalDate, LocalDate>, Pair<LocalDate, LocalDate>> = when (p) {
            Periodo.HOJE -> (hoje to hoje) to (hoje.minusDays(1) to hoje.minusDays(1))
            Periodo.SEMANA -> {
                val ini = hoje.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                (ini to hoje) to (ini.minusWeeks(1) to hoje.minusWeeks(1))
            }
            Periodo.MES -> {
                val ini = hoje.withDayOfMonth(1)
                val antIni = ini.minusMonths(1)
                (ini to hoje) to (antIni to antIni.withDayOfMonth(minOf(hoje.dayOfMonth, antIni.lengthOfMonth())))
            }
            Periodo.ANO -> {
                val ini = hoje.withDayOfYear(1)
                (ini to hoje) to (ini.minusYears(1) to hoje.minusYears(1))
            }
        }

        fun calcular(
            p: Periodo, hoje: LocalDate, agora: Long, ganhos: List<Ganho>, gastos: List<Gasto>,
            turnos: List<Turno>, metas: List<Meta>,
        ): Estatisticas {
            val (atual, ant) = intervalos(p, hoje)
            fun ent(d: String, i: Pair<LocalDate, LocalDate>) = d >= i.first.toString() && d <= i.second.toString()
            val g = ganhos.filter { ent(it.data, atual) }; val d = gastos.filter { ent(it.data, atual) }
            val ga = ganhos.filter { ent(it.data, ant) }; val da = gastos.filter { ent(it.data, ant) }
            fun corr(l: List<Ganho>) = l.count { it.descricao != AJUSTE }
            fun trab(i: Pair<LocalDate, LocalDate>) = turnos.filter { !it.deleted && ent(it.data, i) }.sumOf { Calculos.trabalhoMs(it, agora) }

            // Série do gráfico
            val dias: List<LocalDate> = when (p) {
                Periodo.HOJE -> (6 downTo 0).map { hoje.minusDays(it.toLong()) }
                Periodo.SEMANA -> (0..6).map { atual.first.plusDays(it.toLong()) }
                Periodo.MES -> (1..hoje.lengthOfMonth()).map { hoje.withDayOfMonth(it) }
                Periodo.ANO -> emptyList()
            }
            val serie = if (p == Periodo.ANO) {
                (1..12).map { m ->
                    val pre = String.format(Locale.ROOT, "%04d-%02d", hoje.year, m)
                    val gm = ganhos.filter { it.data.startsWith(pre) }
                    val dm = gastos.filter { it.data.startsWith(pre) }
                    Ponto(java.time.Month.of(m).getDisplayName(TextStyle.SHORT, pt).replace(".", "").take(3),
                        gm.sumOf { it.valorCentavos } - dm.sumOf { it.valorCentavos },
                        Calculos.APPS.associateWith { a -> gm.filter { it.app == a }.sumOf { it.valorCentavos } },
                        m == hoje.monthValue)
                }
            } else dias.map { dia ->
                val s = dia.toString()
                val gd = ganhos.filter { it.data == s }; val dd = gastos.filter { it.data == s }
                Ponto(
                    if (p == Periodo.MES) dia.dayOfMonth.toString() else dia.dayOfWeek.getDisplayName(TextStyle.SHORT, pt).replace(".", "").take(3),
                    gd.sumOf { it.valorCentavos } - dd.sumOf { it.valorCentavos },
                    Calculos.APPS.associateWith { a -> gd.filter { it.app == a }.sumOf { it.valorCentavos } },
                    dia == hoje,
                )
            }

            val cats = listOf(CategoriaGasto.ENERGIA, CategoriaGasto.MANUTENCAO, CategoriaGasto.OUTRO).map { c ->
                c to d.filter { if (c == CategoriaGasto.OUTRO) it.categoria != CategoriaGasto.ENERGIA && it.categoria != CategoriaGasto.MANUTENCAO else it.categoria == c }.sumOf { it.valorCentavos }
            }
            val ultimos = (g.map { Lancamento(it.id, it.data, true, Fmt.nomeApp(it.app), it.valorCentavos, it.updatedAt, it.descricao) } +
                d.map { Lancamento(it.id, it.data, false, when (it.categoria) { CategoriaGasto.ENERGIA -> "Energia"; CategoriaGasto.MANUTENCAO -> "Manutenção"; else -> "Outro gasto" }, -it.valorCentavos, it.updatedAt, it.descricao) })
                .sortedWith(compareByDescending<Lancamento> { it.data }.thenByDescending { it.quando }).take(8)
            val tipoMeta = when (p) { Periodo.HOJE -> TipoMeta.DIA; Periodo.SEMANA -> TipoMeta.SEMANA; else -> TipoMeta.MES }

            return Estatisticas(
                p, atual.first, atual.second,
                g.sumOf { it.valorCentavos }, d.sumOf { it.valorCentavos }, corr(g), trab(atual),
                ga.sumOf { it.valorCentavos }, ga.sumOf { it.valorCentavos } - da.sumOf { it.valorCentavos }, corr(ga), trab(ant),
                Calculos.progresso(tipoMeta, hoje, metas, ganhos, gastos),
                serie, Calculos.porApp(g), cats, ultimos,
            )
        }
    }
}
