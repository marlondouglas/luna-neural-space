package space.neural.luna.dashboard

import org.junit.Assert.*
import org.junit.Test
import space.neural.luna.data.local.Ganho
import space.neural.luna.data.local.Gasto
import space.neural.luna.data.local.Turno
import space.neural.luna.data.local.EstadoTurno
import java.time.LocalDate
import java.time.ZoneId

class ReportAnalyticsTest {
    private val today = LocalDate.of(2026, 10, 23) // sexta-feira
    private val window = ReportAnalytics.Window(today.minusDays(3), today)
    @Test fun weeklyComparisonUsesMondayAndExcludesCurrentDayOnBothSides() {
        val (a, b) = ReportAnalytics.weeklyWindows(today)
        assertEquals(LocalDate.of(2026, 10, 19), a.start)
        assertEquals(LocalDate.of(2026, 10, 22), a.end)
        assertEquals(LocalDate.of(2026, 10, 12), b.start)
        assertEquals(LocalDate.of(2026, 10, 15), b.end)
        assertTrue(ReportAnalytics.weeklyWindows(a.start).first.empty)
        assertEquals(LocalDate.of(2026, 10, 18), ReportAnalytics.previousFullWeek(today).end)
    }
    @Test fun noPercentageWithZeroBaseEvenIfBothZero() {
        assertNull(ReportAnalytics.percent(0, 0))
        assertNull(ReportAnalytics.percent(100, 0))
        assertEquals(50.0, ReportAnalytics.percent(150, 100)!!, 0.001)
    }
    @Test fun bestAppUsesGrossAndBestDayUsesNetAndFiltersDeletedPersonalAndDuplicates() {
        val a = Ganho(id = "a", data = today.toString(), app = "UBER", valorCentavos = 10000)
        val b = Ganho(id = "b", data = today.minusDays(1).toString(), app = "99", valorCentavos = 8000)
        val costs = listOf(Gasto(data = today.toString(), categoria = "OUTRO", valorCentavos = 9000),
            Gasto(data = b.data, categoria = "OUTRO", valorCentavos = 50000, limiteId = "pessoal"),
            Gasto(data = b.data, categoria = "OUTRO", valorCentavos = 50000, deleted = true))
        val s = ReportAnalytics.summarize(window, listOf(a, a, b, a.copy(id = "deleted", valorCentavos = 999999, deleted = true)), costs, emptyList(), 0)
        assertEquals("UBER", s.bestAppByGross!!.app)
        assertEquals(today.minusDays(1), s.bestDayByNet!!.date)
        assertEquals(9000L, s.net)
        assertEquals(2, s.rides)
        assertTrue(s.insufficientSample)
    }
    @Test fun ratesUseWorkTimeAndTicketExcludesAdjustment() {
        val gain = Ganho(data = today.toString(), app = "UBER", valorCentavos = 10000)
        val adjustment = gain.copy(id = "adjustment", valorCentavos = 1000, descricao = Estatisticas.AJUSTE)
        val start = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val shift = Turno(data = today.toString(), inicio = start, fim = start + 3600000,
            estado = EstadoTurno.FECHADO, trabalhoMs = 3600000)
        val s = ReportAnalytics.summarize(window, listOf(gain, adjustment), emptyList(), listOf(shift), start + 3600000)
        assertEquals(11000L, s.netPerHour)
        assertEquals(1.0, s.ridesPerHour!!, 0.0001)
        assertEquals(10000L, s.averageTicket)
        assertNull(ReportAnalytics.summarize(window, emptyList(), emptyList(), emptyList(), 0).netPerHour)
    }
    @Test fun historicalOpenShiftCannotAccumulateIntoExcludedToday() {
        val zone = ZoneId.of("America/Sao_Paulo")
        val day = today.minusDays(1)
        val start = day.atTime(23, 0).atZone(zone).toInstant().toEpochMilli()
        val now = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val shift = Turno(data = day.toString(), inicio = start, ultimoEvento = start, estado = EstadoTurno.ATIVO)
        val s = ReportAnalytics.summarize(ReportAnalytics.Window(day, day), emptyList(), emptyList(), listOf(shift), now, zone)
        assertEquals(3600000L, s.workMs)
        val corruptedAccumulation = shift.copy(trabalhoMs = 999999999L)
        assertEquals(3600000L, ReportAnalytics.summarize(ReportAnalytics.Window(day, day), emptyList(), emptyList(), listOf(corruptedAccumulation), now, zone).workMs)
    }
    @Test fun dayBoundaryUsesLocalTimezoneAndDstLength() {
        val zone = ZoneId.of("America/New_York")
        val day = LocalDate.of(2026, 3, 8)
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val shift = Turno(data = day.toString(), inicio = start, ultimoEvento = start, estado = EstadoTurno.ATIVO)
        val now = day.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(23L * 3600000, ReportAnalytics.summarize(ReportAnalytics.Window(day, day), emptyList(), emptyList(), listOf(shift), now, zone).workMs)
    }
}
