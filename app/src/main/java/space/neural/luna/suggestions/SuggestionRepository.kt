package space.neural.luna.suggestions

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import space.neural.luna.conta.TrocaConta
import java.security.MessageDigest
import java.time.ZoneId

/** Local account-scoped preferences and delivery decisions. No finance or microphone access. */
class SuggestionRepository(context: Context, private val owner: () -> String? = { TrocaConta.dono(context) }) {
    private val app = context.applicationContext
    fun ownerId(): String? = owner()?.takeIf { it.isNotBlank() }
    private fun storage(uid: String = checkNotNull(ownerId()) { "Entre na conta para configurar sugestões." }): android.content.SharedPreferences {
        val hash = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray()).joinToString("") { "%02x".format(it) }
        return app.getSharedPreferences("luna_suggestions_$hash", Context.MODE_PRIVATE)
    }
    fun preferences(): SuggestionPreferences = synchronized(LOCK) {
        if (ownerId() == null) return@synchronized SuggestionPreferences()
        runCatching { decodePreferences(JSONObject(storage().getString("preferences", "{}").orEmpty())) }
            .getOrDefault(SuggestionPreferences())
    }
    fun savePreferences(value: SuggestionPreferences) = synchronized(LOCK) {
        value.validated()
        check(storage().edit().putString("preferences", encodePreferences(value).toString()).commit()) { "Não foi possível salvar as preferências." }
    }
    fun state(): SuggestionState = synchronized(LOCK) {
        if (ownerId() == null) return@synchronized SuggestionState()
        runCatching { decodeState(JSONObject(storage().getString("state", "{}").orEmpty())) }.getOrDefault(SuggestionState())
    }
    /** Rechecks opt-in and owner inside the mutation; stale dialogs cannot affect a different account. */
    fun update(expectedOwner: String, transform: (SuggestionState) -> SuggestionState): Boolean = synchronized(LOCK) {
        if (ownerId() != expectedOwner || !preferences().enabled) return@synchronized false
        val target = storage(expectedOwner)
        val current = runCatching { decodeState(JSONObject(target.getString("state", "{}").orEmpty())) }.getOrDefault(SuggestionState())
        val next = transform(current)
        if (next == current) return@synchronized false
        if (ownerId() != expectedOwner || !preferences().enabled) return@synchronized false
        check(target.edit().putString("state", encodeState(next).toString()).commit()) { "Não foi possível salvar a decisão." }
        true
    }
    fun clearHistory() = synchronized(LOCK) {
        check(storage().edit().remove("state").commit()) { "Não foi possível limpar o histórico." }
    }
    companion object {
        private val LOCK = Any()
        private fun encodePreferences(p: SuggestionPreferences) = JSONObject().apply {
            put("enabled", p.enabled); put("rest", p.rest); put("lunch", p.lunch); put("coffee", p.coffee)
            put("hydration", p.hydration); put("performance", p.performance); put("frequencyMinutes", p.frequencyMinutes)
            put("cooldownMinutes", p.cooldownMinutes); put("maxPerInterval", p.maxPerInterval); put("intervalMinutes", p.intervalMinutes)
            put("quietStartHour", p.quietStartHour); put("quietEndHour", p.quietEndHour)
            put("textEnabled", p.textEnabled); put("voiceEnabled", p.voiceEnabled); put("affectionate", p.affectionate)
        }
        private fun decodePreferences(j: JSONObject) = SuggestionPreferences(
            j.optBoolean("enabled", false), j.optBoolean("rest", true), j.optBoolean("lunch", true),
            j.optBoolean("coffee", true), j.optBoolean("hydration", true), j.optBoolean("performance", false),
            j.optInt("frequencyMinutes", 30), j.optInt("cooldownMinutes", 60), j.optInt("maxPerInterval", 2),
            j.optInt("intervalMinutes", 180), j.optInt("quietStartHour", 22), j.optInt("quietEndHour", 7),
            j.optBoolean("textEnabled", true), j.optBoolean("voiceEnabled", false), j.optBoolean("affectionate", false)).validated()
        private fun stamp(s: SuggestionStamp) = JSONObject().put("wall", s.wallMillis).put("elapsed", s.elapsedMillis).put("boot", s.bootId)
        private fun stamp(j: JSONObject) = SuggestionStamp(j.getLong("wall"), j.getLong("elapsed"), j.getString("boot"))
        private fun encodeState(s: SuggestionState) = JSONObject().apply {
            put("records", JSONArray().apply {
                s.records.entries.sortedByDescending { it.value.stamp.wallMillis }.take(256).forEach { (id, r) ->
                    put(JSONObject().put("id", id).put("decision", r.decision.name).put("stamp", stamp(r.stamp)).put("minutes", r.delayMinutes))
                }
            })
            put("shown", JSONArray().apply { s.shown.takeLast(32).forEach { put(stamp(it)) } })
        }
        private fun decodeState(j: JSONObject): SuggestionState {
            val records = j.optJSONArray("records") ?: JSONArray()
            val shown = j.optJSONArray("shown") ?: JSONArray()
            return SuggestionState((0 until minOf(records.length(), 256)).associate { index ->
                val r = records.getJSONObject(index)
                val decision = SuggestionDecision.valueOf(r.getString("decision"))
                val delay = r.optInt("minutes")
                require(decision != SuggestionDecision.SNOOZED || delay in listOf(30, 60, 120))
                r.getString("id") to SuggestionRecord(decision, stamp(r.getJSONObject("stamp")), delay)
            }, (0 until minOf(shown.length(), 32)).map { stamp(shown.getJSONObject(it)) })
        }
        fun clock(context: Context): SuggestionClock {
            val app = context.applicationContext
            val fallbackBoot = java.util.UUID.randomUUID().toString()
            return SuggestionClock {
                val boot = runCatching { Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT).toString() }.getOrDefault(fallbackBoot)
                SuggestionTime(System.currentTimeMillis(), SystemClock.elapsedRealtime(), boot, ZoneId.systemDefault())
            }
        }
    }
}
