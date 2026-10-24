package space.neural.luna.memory

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

enum class MemoryCategory(val label: String) {
    PREFERENCE("Preferência"), RESPONSE_STYLE("Estilo de resposta"), WORK_ROUTINE("Rotina de trabalho"), REFERENCE("Referência, somente dado")
}
enum class MemoryOrigin { MANUAL, WEB_REFERENCE }
data class LunaMemoryItem(val id: String, val text: String, val category: MemoryCategory, val origin: MemoryOrigin,
    val createdAt: Long, val updatedAt: Long, val source: String? = null)

object MemoryContentPolicy {
    fun validate(text: String): String {
        val clean = text.trim()
        require(clean.length in 3..280 && clean.none { it == '\u0000' }) { "Use um resumo manual de 3 a 280 caracteres." }
        require(!Regex("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|\\bBearer\\s+[A-Za-z0-9._~-]{12,}|\\bsk-[A-Za-z0-9_-]{16,}|\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+", RegexOption.IGNORE_CASE).containsMatchIn(clean)) { "Não guarde chaves, tokens ou credenciais na memória." }
        require(!Regex("(?:senha|password|api[_ -]?key|segredo|token)\\s*[:=]\\s*\\S+", RegexOption.IGNORE_CASE).containsMatchIn(clean)) { "Não guarde credenciais na memória." }
        require(!Regex("(?:latitude|longitude|lat|lon)\\s*[:=]\\s*-?\\d|[-+]\\d{1,2}\\.\\d{3,}\\s*[,;]\\s*[-+]\\d{1,3}\\.\\d{3,}|minha localiza[çc][ãa]o|endere[çc]o residencial|minha casa fica|moro na rua", RegexOption.IGNORE_CASE).containsMatchIn(clean)) { "Não guarde localização precisa ou endereço residencial na memória." }
        return clean
    }
}

/** Opt-in local summaries only. No conversation capture, cloud, training, network, or command execution. */
class LunaMemoryRepository(context: Context,
    private val uidProvider: () -> String? = { space.neural.luna.conta.Conta.uid() },
    private val now: () -> Long = System::currentTimeMillis) {
    private val app = context.applicationContext
    fun safeUid(): String? = validUid(uidProvider())
    private fun store(uid: String) = app.getSharedPreferences(storeName(uid), Context.MODE_PRIVATE)
    fun enabled(): Boolean = synchronized(lock) { safeUid()?.let { store(it).getBoolean("enabled", false) } == true }
    fun setEnabled(value: Boolean) = synchronized(lock) {
        val uid = checkNotNull(safeUid()) { "Entre na sua conta para alterar a memória." }
        val editor = store(uid).edit()
        if (!value) editor.clear() // Opt-out removes all retained summaries on this device.
        check(editor.putBoolean("enabled", value).commit()) { "Não foi possível atualizar a memória local." }
    }
    fun list(): List<LunaMemoryItem> = synchronized(lock) {
        val uid = safeUid() ?: return@synchronized emptyList()
        if (!store(uid).getBoolean("enabled", false)) return@synchronized emptyList()
        val result = read(uid)
        if (safeUid() == uid) result else emptyList()
    }
    /** Only manually entered preferences may be offered as contextual DATA to Luna. Never execute item text. */
    fun relevantPreferences(limit: Int = 8): List<LunaMemoryItem> = list().filter {
        it.origin == MemoryOrigin.MANUAL && it.category != MemoryCategory.REFERENCE
    }.take(limit.coerceIn(0, 8))
    fun put(text: String, category: MemoryCategory, id: String? = null,
        origin: MemoryOrigin = MemoryOrigin.MANUAL, source: String? = null): LunaMemoryItem = synchronized(lock) {
        val uid = checkNotNull(safeUid()) { "Entre na sua conta para salvar a memória." }
        check(store(uid).getBoolean("enabled", false)) { "Ative a memória antes de salvar um resumo manual." }
        val clean = MemoryContentPolicy.validate(text)
        require(id == null || id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Identificador de memória inválido." }
        val safeSource = source?.trim()?.takeIf { it.isNotEmpty() }?.also {
            val uri = runCatching { java.net.URI(it) }.getOrNull()
            require(uri?.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) { "Use uma fonte HTTPS pública sem credenciais ou parâmetros." }
        }
        require(origin != MemoryOrigin.WEB_REFERENCE || category == MemoryCategory.REFERENCE && safeSource != null) { "Conteúdo web deve ser referência com fonte, nunca instrução ou preferência automática." }
        val values = read(uid)
        val previous = id?.let { key -> values.firstOrNull { it.id == key } }
        require(id == null || previous != null) { "A memória não pertence à conta atual." }
        require(previous?.origin != MemoryOrigin.WEB_REFERENCE || origin == MemoryOrigin.WEB_REFERENCE) {
            "Uma referência web deve permanecer identificada como fonte externa."
        }
        check(previous != null || values.size < 50) { "Limite de 50 resumos locais atingido." }
        val time = now()
        val item = LunaMemoryItem(previous?.id ?: UUID.randomUUID().toString(), clean, category, origin, previous?.createdAt ?: time, time, safeSource)
        check(safeUid() == uid) { "A conta mudou. Tente novamente." }
        write(uid, listOf(item) + values.filterNot { it.id == item.id })
        item
    }
    fun remove(id: String) = synchronized(lock) {
        val uid = checkNotNull(safeUid()) { "Entre na sua conta." }
        write(uid, read(uid).filterNot { it.id == id })
    }
    fun clear() = synchronized(lock) {
        val uid = checkNotNull(safeUid()) { "Entre na sua conta." }
        check(store(uid).edit().remove("items").commit()) { "Não foi possível apagar as memórias." }
    }
    private fun read(uid: String): List<LunaMemoryItem> = runCatching {
        val rows = JSONArray(store(uid).getString("items", "[]"))
        (0 until rows.length()).map { rows.getJSONObject(it) }.map { row ->
            LunaMemoryItem(row.getString("id"), row.getString("text"), MemoryCategory.valueOf(row.getString("category")), MemoryOrigin.valueOf(row.getString("origin")), row.getLong("createdAt"), row.getLong("updatedAt"), row.optString("source").takeIf { it.isNotBlank() })
        }
    }.getOrDefault(emptyList())
    private fun write(uid: String, values: List<LunaMemoryItem>) {
        val rows = JSONArray()
        values.forEach { rows.put(JSONObject().put("id", it.id).put("text", it.text).put("category", it.category.name).put("origin", it.origin.name).put("createdAt", it.createdAt).put("updatedAt", it.updatedAt).apply { if (it.source != null) put("source", it.source) }) }
        check(store(uid).edit().putString("items", rows.toString()).commit()) { "Não foi possível salvar a memória local." }
    }
    companion object {
        private val lock = Any()
        private fun validUid(uid: String?) = uid?.takeIf { it.isNotBlank() && it == it.trim() && it.length <= 128 && it.none(Char::isISOControl) }
        private fun storeName(uid: String): String = "luna_private_memory_v1_" + MessageDigest.getInstance("SHA-256").digest(uid.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        /** Privacy hook only: never touches Room, finance, chat history, files, or another current account. */
        fun onAccountSwitch(context: Context, previousUid: String?, newUid: String?) = synchronized(lock) {
            val previous = validUid(previousUid) ?: return@synchronized
            if (previous != validUid(newUid)) check(context.applicationContext.getSharedPreferences(storeName(previous), Context.MODE_PRIVATE).edit().clear().commit()) { "Não foi possível apagar a memória da conta anterior." }
        }
    }
}
