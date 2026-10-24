package space.neural.luna.suggestions

/** Proposals never change the shift. This controller has no financial/shift mutation dependency. */
class SuggestionCoordinator(private val repository: SuggestionRepository, private val clock: SuggestionClock) {
    data class Confirmation internal constructor(val token: String, val text: String, val minutes: Int)
    private data class Pending(val confirmation: Confirmation, val owner: String, val suggestion: Suggestion,
        val stamp: SuggestionStamp, val before: SuggestionRecord)
    private val engine = SuggestionEngine(clock)
    private var pending: Pending? = null
    private var offered: Pair<String, Suggestion>? = null
    private val decisionOwners = mutableMapOf<String, String>()
    fun next(facts: ShiftSuggestionFacts, gate: SuggestionGate): Suggestion? {
        val owner = repository.ownerId() ?: return null
        val candidate = engine.next(facts, repository.preferences(), repository.state(), gate)
        offered = candidate?.let { owner to it }
        return candidate
    }
    /** Call only once the host actually displays the suggestion in a known safe foreground state. */
    fun displayed(suggestion: Suggestion, gate: SuggestionGate): Boolean {
        if (!gate.allows()) return false
        val owner = repository.ownerId() ?: return false
        if (offered != (owner to suggestion)) return false
        offered = null
        val saved = repository.update(owner) { engine.markShown(suggestion, it) }
        if (saved) decisionOwners[suggestion.id] = owner
        return saved
    }
    fun proposeSnooze(suggestion: Suggestion, minutes: Int): Confirmation {
        require(minutes in listOf(30, 60, 120))
        val owner = checkNotNull(repository.ownerId())
        check(decisionOwners[suggestion.id] == owner) { "Esta sugestão pertence a outra sessão ou conta." }
        check(repository.preferences().enabled) { "Ative as sugestões antes de adiar." }
        val before = checkNotNull(repository.state().records[suggestion.id]) { "Essa sugestão não está disponível nesta conta." }
        check(before.decision != SuggestionDecision.DISMISSED) { "Essa sugestão já foi encerrada." }
        val confirmation = Confirmation(java.util.UUID.randomUUID().toString(), "Lembrar desta sugestão daqui a $minutes minutos? Nenhum turno será pausado.", minutes)
        pending = Pending(confirmation, owner, suggestion, clock.now().stamp(), before)
        return confirmation
    }
    fun proposeSnooze(suggestion: Suggestion, text: String): Confirmation? =
        SuggestionEngine.proposeSnooze(text)?.let { proposeSnooze(suggestion, it.minutes) }
    fun confirm(token: String, gate: SuggestionGate): Boolean {
        val proposal = pending ?: return false
        if (proposal.confirmation.token != token) return false
        pending = null // one-use token, including a rejected expired/account-changed confirmation
        if (!gate.allows()) return false
        if (proposal.stamp.age(clock.now()) !in 0..600_000L) return false
        return repository.update(proposal.owner) {
            if (it.records[proposal.suggestion.id] != proposal.before) it
            else engine.snooze(proposal.suggestion, proposal.confirmation.minutes, it)
        }
    }
    fun cancel(token: String) { if (pending?.confirmation?.token == token) pending = null }
    fun dismiss(suggestion: Suggestion): Boolean {
        val owner = repository.ownerId() ?: return false
        if (decisionOwners[suggestion.id] != owner) return false
        if (!repository.state().records.containsKey(suggestion.id)) return false
        return repository.update(owner) {
            if (it.records[suggestion.id]?.decision == SuggestionDecision.DISMISSED) it else engine.dismiss(suggestion, it)
        }
    }
    /** Safe host callback boundary: no action after opt-out, account switch or safety change. */
    fun runConfirmedAction(suggestion: Suggestion, gate: () -> SuggestionGate, action: () -> Unit): Boolean {
        val owner = decisionOwners[suggestion.id] ?: return false
        if (repository.ownerId() != owner || !gate().allows() || !dismiss(suggestion)) return false
        if (!gate().allows() || repository.ownerId() != owner || !repository.preferences().enabled) return false
        action()
        return true
    }
}
