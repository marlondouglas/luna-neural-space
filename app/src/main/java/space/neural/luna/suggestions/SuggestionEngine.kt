package space.neural.luna.suggestions

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

enum class SuggestionRule { REST, LUNCH, COFFEE, HYDRATION, PERFORMANCE }
data class SuggestionPreferences(
    val enabled: Boolean = false,
    val rest: Boolean = true, val lunch: Boolean = true, val coffee: Boolean = true,
    val hydration: Boolean = true, val performance: Boolean = false,
    val frequencyMinutes: Int = 30, val cooldownMinutes: Int = 60,
    val maxPerInterval: Int = 2, val intervalMinutes: Int = 180,
    val quietStartHour: Int = 22, val quietEndHour: Int = 7,
    val textEnabled: Boolean = true, val voiceEnabled: Boolean = false,
    val affectionate: Boolean = false,
) {
    fun validated() = also {
        require(frequencyMinutes in 15..240 && cooldownMinutes in 15..240)
        require(maxPerInterval in 1..5 && intervalMinutes in 30..1440)
        require(quietStartHour in 0..23 && quietEndHour in 0..23)
    }
    fun allows(rule: SuggestionRule) = when (rule) {
        SuggestionRule.REST -> rest; SuggestionRule.LUNCH -> lunch; SuggestionRule.COFFEE -> coffee
        SuggestionRule.HYDRATION -> hydration; SuggestionRule.PERFORMANCE -> performance
    }
}
data class SuggestionTime(val wallMillis: Long, val elapsedMillis: Long, val bootId: String, val zone: ZoneId) {
    fun stamp() = SuggestionStamp(wallMillis, elapsedMillis, bootId)
}
fun interface SuggestionClock { fun now(): SuggestionTime }
data class SuggestionStamp(val wallMillis: Long, val elapsedMillis: Long, val bootId: String) {
    fun age(now: SuggestionTime): Long = if (bootId == now.bootId) now.elapsedMillis - elapsedMillis else now.wallMillis - wallMillis
}
data class ShiftSuggestionFacts(
    val shiftId: String, val active: Boolean, val workedMillis: Long, val uninterruptedMillis: Long,
    val currentCentsPerHour: Long? = null, val targetCentsPerHour: Long? = null,
    val comparablePerformance: Boolean = false,
)
/** Unknown context is blocked. Main must explicitly provide safe foreground conditions. */
data class SuggestionGate(
    val safeToInterrupt: Boolean = false, val radioTransmitting: Boolean = false,
    val maneuvering: Boolean = false, val dialogOpen: Boolean = false, val onCall: Boolean = false,
    val radioReceiving: Boolean = false,
) { fun allows() = safeToInterrupt && !radioTransmitting && !radioReceiving && !maneuvering && !dialogOpen && !onCall }
data class Suggestion(val id: String, val rule: SuggestionRule, val shiftId: String, val createdAt: Long, val text: String)
enum class SuggestionDecision { DISPLAYED, SNOOZED, DISMISSED }
data class SuggestionRecord(val decision: SuggestionDecision, val stamp: SuggestionStamp, val delayMinutes: Int = 0)
data class SuggestionState(val records: Map<String, SuggestionRecord> = emptyMap(), val shown: List<SuggestionStamp> = emptyList())
data class SnoozeProposal(val minutes: Int, val confirmationText: String)

/** Pure, deterministic timing rules. These are opt-in convenience reminders, not medical assessments. */
class SuggestionEngine(private val clock: SuggestionClock) {
    fun next(facts: ShiftSuggestionFacts, prefs: SuggestionPreferences, state: SuggestionState, gate: SuggestionGate): Suggestion? =
        if (gate.allows()) candidate(facts, prefs, state) else null

    /** A candidate may be staged by a worker; staging is never delivery and does not consume limits. */
    fun candidate(facts: ShiftSuggestionFacts, prefs: SuggestionPreferences, state: SuggestionState): Suggestion? {
        prefs.validated()
        if (!prefs.enabled || (!prefs.textEnabled && !prefs.voiceEnabled) || !facts.active || facts.shiftId.isBlank()) return null
        if (facts.workedMillis < 0 || facts.uninterruptedMillis < 0 || facts.uninterruptedMillis > facts.workedMillis) return null
        val now = clock.now()
        val hour = Instant.ofEpochMilli(now.wallMillis).atZone(now.zone).hour
        if (quiet(hour, prefs.quietStartHour, prefs.quietEndHour)) return null
        val recent = state.shown.map { it.age(now) }
        if (recent.any { it < 0 || it < maxOf(prefs.cooldownMinutes, prefs.frequencyMinutes) * MINUTE }) return null
        if (recent.count { it in 0 until prefs.intervalMinutes * MINUTE } >= prefs.maxPerInterval) return null
        for (rule in SuggestionRule.entries) {
            if (!prefs.allows(rule) || !eligible(rule, facts, hour)) continue
            val id = "${facts.shiftId}:${rule.name}"
            val record = state.records[id]
            if (record != null) {
                if (record.decision != SuggestionDecision.SNOOZED) continue
                val age = record.stamp.age(now)
                if (age < 0 || age < record.delayMinutes * MINUTE) continue
            }
            return Suggestion(id, rule, facts.shiftId, now.wallMillis, text(rule, prefs.affectionate))
        }
        return null
    }
    fun markShown(suggestion: Suggestion, state: SuggestionState): SuggestionState {
        val stamp = clock.now().stamp()
        return state.copy(records = state.records + (suggestion.id to SuggestionRecord(SuggestionDecision.DISPLAYED, stamp)), shown = (state.shown + stamp).takeLast(32))
    }
    fun snooze(suggestion: Suggestion, minutes: Int, state: SuggestionState): SuggestionState {
        require(minutes in listOf(30, 60, 120))
        return state.copy(records = state.records + (suggestion.id to SuggestionRecord(SuggestionDecision.SNOOZED, clock.now().stamp(), minutes)))
    }
    fun dismiss(suggestion: Suggestion, state: SuggestionState): SuggestionState = state.copy(records = state.records +
        (suggestion.id to SuggestionRecord(SuggestionDecision.DISMISSED, clock.now().stamp())))
    private fun eligible(rule: SuggestionRule, f: ShiftSuggestionFacts, hour: Int): Boolean = when (rule) {
        SuggestionRule.REST -> f.uninterruptedMillis >= 6 * HOUR
        SuggestionRule.LUNCH -> f.uninterruptedMillis >= 3 * HOUR && hour in 11..14
        SuggestionRule.COFFEE -> f.uninterruptedMillis >= 3 * HOUR && hour !in 11..14
        SuggestionRule.HYDRATION -> f.uninterruptedMillis >= 2 * HOUR
        SuggestionRule.PERFORMANCE -> f.comparablePerformance && f.currentCentsPerHour != null && f.targetCentsPerHour != null
            && f.currentCentsPerHour >= 0 && f.targetCentsPerHour > 0 && f.currentCentsPerHour < f.targetCentsPerHour
    }
    private fun text(rule: SuggestionRule, affectionate: Boolean): String = (if (affectionate) "Com carinho, " else "") + when (rule) {
        SuggestionRule.REST -> "você está trabalhando há seis horas sem uma pausa registrada. Quer planejar um descanso em local seguro?"
        SuggestionRule.LUNCH -> "há três horas de trabalho sem pausa registrada. É perto do almoço; quer planejar uma pausa?"
        SuggestionRule.COFFEE -> "há três horas de trabalho sem pausa registrada. Quer fazer uma pausa para um café em local seguro?"
        SuggestionRule.HYDRATION -> "há duas horas de trabalho sem pausa registrada. Quer reservar um momento para beber água, estacionado em local seguro?"
        SuggestionRule.PERFORMANCE -> "o ganho por hora está abaixo da meta informada no período comparável. Quer revisar seu planejamento quando estiver estacionado?"
    }
    companion object {
        const val MINUTE = 60_000L; const val HOUR = 60 * MINUTE
        fun quiet(hour: Int, start: Int, end: Int): Boolean = when {
            start == end -> false
            start < end -> hour in start until end
            else -> hour >= start || hour < end
        }
        /** Parsing proposes only. The caller must obtain confirmation before calling snooze. */
        fun proposeSnooze(input: String): SnoozeProposal? {
            val text = java.text.Normalizer.normalize(input.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            val matches = listOf(
                120 to Regex("\\b(?:duas|2) horas?\\b"),
                60 to Regex("\\b(?:uma|1) hora\\b"),
                30 to Regex("\\b(?:trinta|30) minutos?\\b")
            ).filter { it.second.containsMatchIn(text) }.map { it.first }
            val minutes = matches.singleOrNull() ?: return null
            if (!text.contains("daqui") || !Regex("\\b(parar|pausar|pare|lembrar|lembre|lembra)\\b").containsMatchIn(text)) return null
            return SnoozeProposal(minutes, "Lembrar você de fazer uma pausa daqui a $minutes minutos?")
        }
    }
}
