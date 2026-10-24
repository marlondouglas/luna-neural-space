package space.neural.luna.dashboard

import space.neural.luna.data.local.Ganho
import space.neural.luna.data.local.Gasto
import space.neural.luna.data.local.Turno
import space.neural.luna.data.local.MaquinaTurno
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Análise de registros contábeis; updatedAt é sincronização, nunca horário da corrida. */
object ReportAnalytics {
    data class Window(val start: LocalDate, val end: LocalDate) {
        val empty get() = end < start
        fun contains(date: String) = !empty && date >= start.toString() && date <= end.toString()
    }
    data class AppGross(val app: String, val cents: Long, val rides: Int)
    data class DayNet(val date: LocalDate, val cents: Long)
    data class Summary(val window: Window, val gross: Long, val expenses: Long, val rides: Int,
        val workMs: Long, val recordedDays: Int, val incomeRecords: Int,
        val bestAppByGross: AppGross?, val bestDayByNet: DayNet?, val rideGross: Long) {
        val net get() = gross - expenses
        val netPerHour: Long? get() = if (workMs >= 900_000L) BigDecimal.valueOf(net)
            .multiply(BigDecimal.valueOf(3_600_000)).divide(BigDecimal.valueOf(workMs), 0, RoundingMode.HALF_UP).longValueExact() else null
        val ridesPerHour: Double? get() = if (workMs >= 900_000L && rides > 0) rides * 3_600_000.0 / workMs else null
        // Ajustes ao total do dia não são corridas nem participam do ticket.
        val averageTicket: Long? get() = if (rides > 0) BigDecimal.valueOf(rideGross)
            .divide(BigDecimal.valueOf(rides.toLong()), 0, RoundingMode.HALF_UP).longValueExact() else null
        val insufficientSample get() = recordedDays < 3 || rides < 10
    }
    data class Comparison(val current: Summary, val previous: Summary) {
        val netChangePercent: Double? get() = percent(current.net, previous.net)
    }

    fun percent(current: Long, previous: Long): Double? = if (previous == 0L) null
        else (current.toDouble() - previous.toDouble()) * 100.0 / kotlin.math.abs(previous.toDouble())

    fun window(period: Periodo, today: LocalDate) = Window(when (period) {
        Periodo.HOJE -> today
        Periodo.SEMANA -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        Periodo.MES -> today.withDayOfMonth(1)
        Periodo.ANO -> today.withDayOfYear(1)
    }, today)

    /** Segunda até ontem versus os mesmos dias anteriores; segunda-feira tem janela vazia. */
    fun weeklyWindows(today: LocalDate): Pair<Window, Window> {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return Window(monday, today.minusDays(1)) to Window(monday.minusWeeks(1), today.minusWeeks(1).minusDays(1))
    }
    fun previousFullWeek(today: LocalDate): Window {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return Window(monday.minusWeeks(1), monday.minusDays(1))
    }
    fun summarize(window: Window, gains: List<Ganho>, costs: List<Gasto>, shifts: List<Turno>, now: Long,
        zone: ZoneId = ZoneId.systemDefault()): Summary {
        val g = gains.filter { !it.deleted && window.contains(it.data) }.distinctBy { it.id }
        val d = costs.filter { !it.deleted && it.limiteId == null && window.contains(it.data) }.distinctBy { it.id }
        val t = shifts.filter { !it.deleted && window.contains(it.data) }.distinctBy { it.id }
        val startMs = window.start.atStartOfDay(zone).toInstant().toEpochMilli()
        val endExclusiveMs = window.end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val cappedNow = minOf(now, endExclusiveMs)
        // Janela histórica termina à meia-noite local, inclusive em transições de fuso/DST.
        // O teto adicional evita acumulados inconsistentes maiores que o intervalo do turno.
        fun work(t: Turno): Long = if (window.empty) 0 else MaquinaTurno.trabalhoMs(t, cappedNow)
            .coerceIn(0L, (minOf(cappedNow, t.fim ?: cappedNow) - maxOf(t.inicio, startMs)).coerceAtLeast(0L))
        val rides = g.filter { it.descricao != Estatisticas.AJUSTE }
        val days = (g.map { it.data } + d.map { it.data } + t.filter { work(it) > 0 }.map { it.data }).toSet()
        val bestApp = g.groupBy { it.app }.map { (app, values) ->
            AppGross(app, values.sumOf { it.valorCentavos }, values.count { it.descricao != Estatisticas.AJUSTE })
        }.sortedWith(compareByDescending<AppGross> { it.cents }.thenBy { it.app }).firstOrNull()
        val bestDay = days.map { day -> DayNet(LocalDate.parse(day),
            g.filter { it.data == day }.sumOf { it.valorCentavos } - d.filter { it.data == day }.sumOf { it.valorCentavos })
        }.sortedWith(compareByDescending<DayNet> { it.cents }.thenBy { it.date }).firstOrNull()
        return Summary(window, g.sumOf { it.valorCentavos }, d.sumOf { it.valorCentavos }, rides.size,
            t.sumOf { work(it) }, days.size, g.size, bestApp, bestDay, rides.sumOf { it.valorCentavos })
    }
}
