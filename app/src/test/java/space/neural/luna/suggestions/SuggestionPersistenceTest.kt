package space.neural.luna.suggestions

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SuggestionPersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private var owner: String? = "suggestion-test-a"
    private val zone = ZoneId.of("America/Sao_Paulo")
    private var wall = LocalDateTime.of(2026, 10, 23, 10, 0).atZone(zone).toInstant().toEpochMilli()
    private var elapsed = 100000L
    private val clock = SuggestionClock { SuggestionTime(wall, elapsed, "boot-test", zone) }
    private fun repo() = SuggestionRepository(context) { owner }
    private fun coordinator() = SuggestionCoordinator(repo(), clock)
    private val facts = ShiftSuggestionFacts("shift-test", true, 7 * SuggestionEngine.HOUR, 7 * SuggestionEngine.HOUR)
    private val safe = SuggestionGate(safeToInterrupt = true)
    @Before fun reset() {
        listOf("suggestion-test-a", "suggestion-test-b").forEach { id ->
            owner = id; repo().savePreferences(SuggestionPreferences()); repo().clearHistory()
        }
        owner = "suggestion-test-a"
    }
    private fun shown(c: SuggestionCoordinator): Suggestion {
        repo().savePreferences(SuggestionPreferences(enabled = true))
        val suggestion = c.next(facts, safe)!!
        assertTrue(c.displayed(suggestion, safe))
        return suggestion
    }
    @Test fun defaultsOffAndAccountPreferencesPersistWithoutCrossingAccounts() {
        assertFalse(repo().preferences().enabled)
        repo().savePreferences(SuggestionPreferences(enabled = true, frequencyMinutes = 120, voiceEnabled = true))
        assertEquals(120, repo().preferences().frequencyMinutes)
        owner = "suggestion-test-b"; assertFalse(repo().preferences().enabled); assertFalse(repo().preferences().voiceEnabled)
        owner = "suggestion-test-a"; assertTrue(repo().preferences().enabled)
        assertTrue(repo().preferences().voiceEnabled)
        owner = null; assertFalse(repo().preferences().enabled)
    }
    @Test fun proposalDoesNotSaveUntilConfirmedAndTokenIsSingleUse() {
        val c = coordinator(); val suggestion = shown(c)
        val before = repo().state()
        val proposal = c.proposeSnooze(suggestion, "vou parar daqui a trinta minutos")!!
        assertEquals(before, repo().state())
        assertFalse(c.confirm("wrong-token", safe)); assertEquals(before, repo().state())
        assertTrue(c.confirm(proposal.token, safe)); assertFalse(c.confirm(proposal.token, safe))
        assertEquals(30, repo().state().records[suggestion.id]!!.delayMinutes)
        assertEquals(SuggestionDecision.SNOOZED, repo().state().records[suggestion.id]!!.decision)
        assertEquals(repo().state(), SuggestionRepository(context) { owner }.state())
    }
    @Test fun cancelledOrExpiredProposalNeverChangesStoredDecision() {
        val c = coordinator(); val suggestion = shown(c)
        val before = repo().state()
        val cancel = c.proposeSnooze(suggestion, 60); c.cancel(cancel.token)
        assertFalse(c.confirm(cancel.token, safe)); assertEquals(before, repo().state())
        val expired = c.proposeSnooze(suggestion, 120); elapsed += 600001; wall += 600001
        assertFalse(c.confirm(expired.token, safe)); assertEquals(before, repo().state())
    }
    @Test fun ownerSwitchAndOptOutRejectOutstandingConfirmation() {
        val c = coordinator(); val suggestion = shown(c)
        val proposal = c.proposeSnooze(suggestion, 30)
        owner = "suggestion-test-b"; repo().savePreferences(SuggestionPreferences(enabled = true))
        assertFalse(c.confirm(proposal.token, safe)); assertTrue(repo().state().records.isEmpty())
        owner = "suggestion-test-a"
        val second = c.proposeSnooze(suggestion, 60)
        repo().savePreferences(repo().preferences().copy(enabled = false))
        assertFalse(c.confirm(second.token, safe))
    }
    @Test fun noDeliveryWithoutSafeGateAndNoDoubleDisplayedMark() {
        val c = coordinator(); repo().savePreferences(SuggestionPreferences(enabled = true))
        assertNull(c.next(facts, SuggestionGate()))
        val candidate = c.next(facts, safe)!!
        assertFalse(c.displayed(candidate, safe.copy(onCall = true)))
        assertTrue(c.displayed(candidate, safe))
        assertFalse(c.displayed(candidate, safe))
        assertEquals(1, repo().state().shown.size)
    }
    @Test fun rebootUsesWallTimeAndSnoozeDelaySurvivesControllerRecreation() {
        val c = coordinator(); val suggestion = shown(c)
        val proposal = c.proposeSnooze(suggestion, 120); assertTrue(c.confirm(proposal.token, safe))
        val prefs = repo().preferences().copy(rest = true, lunch = false, coffee = false, hydration = false, cooldownMinutes = 15, frequencyMinutes = 15)
        repo().savePreferences(prefs)
        val restartedClock = SuggestionClock { SuggestionTime(wall + 119 * SuggestionEngine.MINUTE, 100, "new-boot", zone) }
        assertNull(SuggestionCoordinator(repo(), restartedClock).next(facts, safe))
        val dueClock = SuggestionClock { SuggestionTime(wall + 120 * SuggestionEngine.MINUTE, 100, "new-boot", zone) }
        assertEquals(suggestion.id, SuggestionCoordinator(repo(), dueClock).next(facts, safe)!!.id)
    }
    @Test fun hostActionNeverRunsAfterAccountSwitchOptOutOrUnsafeGate() {
        val c = coordinator(); val suggestion = shown(c)
        var calls = 0
        owner = "suggestion-test-b"; repo().savePreferences(SuggestionPreferences(enabled = true))
        assertFalse(c.runConfirmedAction(suggestion, { safe }) { calls++ })
        owner = "suggestion-test-a"; repo().savePreferences(repo().preferences().copy(enabled = false))
        assertFalse(c.runConfirmedAction(suggestion, { safe }) { calls++ })
        repo().savePreferences(repo().preferences().copy(enabled = true))
        assertFalse(c.runConfirmedAction(suggestion, { safe.copy(onCall = true) }) { calls++ })
        assertEquals(0, calls)
        assertTrue(c.runConfirmedAction(suggestion, { safe }) { calls++ })
        elapsed += 1000; wall += 1000
        assertFalse(c.runConfirmedAction(suggestion, { safe }) { calls++ })
        assertEquals(1, calls)
    }
    @Test fun staleSnoozeCannotOverwriteDismissalAndUnsafeConfirmationDoesNotPersist() {
        val c = coordinator(); val suggestion = shown(c)
        val unsafe = c.proposeSnooze(suggestion, 30)
        assertFalse(c.confirm(unsafe.token, safe.copy(radioTransmitting = true)))
        assertEquals(SuggestionDecision.DISPLAYED, repo().state().records[suggestion.id]!!.decision)
        val stale = c.proposeSnooze(suggestion, 60)
        assertTrue(c.dismiss(suggestion))
        assertFalse(c.confirm(stale.token, safe))
        assertEquals(SuggestionDecision.DISMISSED, repo().state().records[suggestion.id]!!.decision)
    }
}
