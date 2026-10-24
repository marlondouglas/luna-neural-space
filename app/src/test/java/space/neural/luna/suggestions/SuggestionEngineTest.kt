package space.neural.luna.suggestions

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class SuggestionEngineTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val wall = LocalDateTime.of(2026, 10, 23, 10, 0).atZone(zone).toInstant().toEpochMilli()
    private val facts = ShiftSuggestionFacts("turno", true, 7 * SuggestionEngine.HOUR, 7 * SuggestionEngine.HOUR)
    private fun engine(minutes: Long = 0) = SuggestionEngine { SuggestionTime(wall + minutes * 60000, minutes * 60000, "boot", zone) }
    @Test fun disabledUnknownAndUnsafeContextsNeverProduceSuggestion() {
        val e = engine(); val prefs = SuggestionPreferences(enabled = true)
        assertNull(e.next(facts, SuggestionPreferences(), SuggestionState(), SuggestionGate(safeToInterrupt = true)))
        assertNull(e.next(facts, prefs, SuggestionState(), SuggestionGate()))
        assertNull(e.next(facts, prefs, SuggestionState(), SuggestionGate(safeToInterrupt = true, maneuvering = true)))
        assertNull(e.next(facts, prefs, SuggestionState(), SuggestionGate(safeToInterrupt = true, radioReceiving = true)))
        assertNull(e.candidate(facts.copy(active = false), prefs, SuggestionState()))
        assertNull(e.candidate(facts.copy(uninterruptedMillis = facts.workedMillis + 1), prefs, SuggestionState()))
    }
    @Test fun frequencyAndCooldownBothLimitDelivery() {
        val prefs = SuggestionPreferences(enabled = true, frequencyMinutes = 120, cooldownMinutes = 15)
        val e = engine(); val first = e.candidate(facts, prefs, SuggestionState())!!
        val state = e.markShown(first, SuggestionState())
        assertNull(engine(60).candidate(facts, prefs, state))
        assertNotNull(engine(120).candidate(facts, prefs, state))
    }
    @Test fun quietTimeAndMaximumCountAreEnforced() {
        val prefs = SuggestionPreferences(enabled = true, frequencyMinutes = 15, cooldownMinutes = 15, maxPerInterval = 1)
        val e = engine(); val first = e.candidate(facts, prefs, SuggestionState())!!
        val state = e.markShown(first, SuggestionState())
        assertNull(engine(60).candidate(facts, prefs, state))
        assertTrue(SuggestionEngine.quiet(23, 22, 7)); assertTrue(SuggestionEngine.quiet(6, 22, 7))
        assertFalse(SuggestionEngine.quiet(10, 22, 7))
    }
    @Test fun parserOnlyProposesSupportedUnambiguousSnoozeRequests() {
        assertEquals(30, SuggestionEngine.proposeSnooze("vou parar daqui a trinta minutos")!!.minutes)
        assertEquals(60, SuggestionEngine.proposeSnooze("me lembre daqui a uma hora")!!.minutes)
        assertEquals(120, SuggestionEngine.proposeSnooze("vou pausar daqui a duas horas")!!.minutes)
        assertNull(SuggestionEngine.proposeSnooze("vou parar daqui a uma hora e trinta minutos"))
        assertNull(SuggestionEngine.proposeSnooze("daqui a trinta minutos compartilhar arquivo"))
        assertNull(SuggestionEngine.proposeSnooze("lembrar em cinco minutos"))
    }
}
