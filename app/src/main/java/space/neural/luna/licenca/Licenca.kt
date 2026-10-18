package space.neural.luna.licenca

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import space.neural.luna.BuildConfig
import space.neural.luna.conta.Conta
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Licença no aparelho: pede ao Worker (/license/check), confere a assinatura, guarda a última resposta e
 * decide (LicencaLogica) se o app abre, mostra o aviso do teste ou bloqueia.
 */
object Licenca {
    /** Ligada pelo LunaApp. Nos testes (Application simples) fica desligada e o app abre direto. */
    @Volatile var ativa = false
    /** Só para testes/prints: força uma decisão. */
    @Volatile var forcar: LicencaLogica.Decisao? = null
    @Volatile private var app: Context? = null

    private const val PREFS = "licenca"
    /** Reconsulta o servidor ao abrir o app se a última resposta tiver mais que isso. */
    private const val REVALIDAR_MS = 15 * 60_000L

    fun iniciar(ctx: Context) {
        app = ctx.applicationContext
        ativa = BuildConfig.FIREBASE_CONFIGURED && base().startsWith("https://")
    }

    fun base(): String = BuildConfig.LUNA_CHAT_ENDPOINT.trimEnd('/').removeSuffix("/chat")

    // ---------------- aparelho ----------------
    @SuppressLint("HardwareIds")
    fun androidId(ctx: Context): String {
        val id = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
        if (!id.isNullOrBlank() && id.matches(Regex("^[0-9a-fA-F]{8,32}$"))) return id.lowercase()
        // Aparelho sem ANDROID_ID (raro): id aleatório guardado (fraco: some ao limpar dados).
        val p = ctx.getSharedPreferences(PREFS, 0)
        return p.getString("id_reserva", null) ?: ByteArray(8).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }.also { p.edit().putString("id_reserva", it).apply() }
    }

    /** Igual ao deviceHash() do Worker. */
    fun deviceHash(androidId: String): String =
        MessageDigest.getInstance("SHA-256").digest("rotagv-device|${androidId.trim().lowercase()}".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)

    /** Código curto para o "Já paguei" quando ainda não há licença: os 6 primeiros do hash do aparelho. */
    fun codigoAparelho(ctx: Context): String = deviceHash(androidId(ctx)).take(6).uppercase()

    fun relogio(ctx: Context): LicencaLogica.Relogio = LicencaLogica.Relogio(
        parede = System.currentTimeMillis(),
        desdeBoot = SystemClock.elapsedRealtime(),
        boot = runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1),
    )

    // ---------------- cache assinado ----------------
    private fun cache(ctx: Context): LicencaLogica.Cache? {
        val p = ctx.getSharedPreferences(PREFS, 0)
        val payload = p.getString("payload", null) ?: return null
        val sig = p.getString("sig", null) ?: return null
        if (!AssinaturaLicenca.confere(payload, sig)) return null // editaram o arquivo
        val pl = LicencaLogica.Payload.ler(payload) ?: return null
        return LicencaLogica.Cache(pl, LicencaLogica.Ancora(p.getLong("rec_t", 0), p.getLong("rec_e", 0), p.getInt("rec_b", -1)))
    }

    private fun marca(ctx: Context): LicencaLogica.Ancora? {
        val p = ctx.getSharedPreferences(PREFS, 0)
        if (!p.contains("marca_t")) return null
        return LicencaLogica.Ancora(p.getLong("marca_t", 0), p.getLong("marca_e", 0), p.getInt("marca_b", -1))
    }

    private fun salvarMarca(ctx: Context, a: LicencaLogica.Ancora) {
        ctx.getSharedPreferences(PREFS, 0).edit().putLong("marca_t", a.tempo).putLong("marca_e", a.desdeBoot).putInt("marca_b", a.boot).apply()
    }

    /** Decisão com o que está guardado (rápida, sem rede). */
    fun decidir(ctx: Context): LicencaLogica.Decisao {
        forcar?.let { return it }
        val uid = Conta.usuario()?.uid ?: return LicencaLogica.Decisao.SemConexao(false)
        val c = cache(ctx)
        val m = marca(ctx)
        val r = relogio(ctx)
        val d = LicencaLogica.decidir(c, m, r, uid, deviceHash(androidId(ctx)), BuildConfig.VERSION_CODE)
        if (c != null && !(d is LicencaLogica.Decisao.SemConexao && d.relogioAlterado)) salvarMarca(ctx, LicencaLogica.novaMarca(c, m, r))
        return d
    }

    /** Código da conta (6 letras/números) para o Pix e o "Já paguei". */
    fun codigo(ctx: Context): String = cache(ctx)?.payload?.code?.takeIf { it.isNotBlank() } ?: codigoAparelho(ctx)

    fun precisaRevalidar(ctx: Context): Boolean {
        val c = cache(ctx) ?: return true
        if (ctx.getSharedPreferences(PREFS, 0).getBoolean("revalidar", false)) return true
        val r = relogio(ctx)
        val rec = c.recebido
        return rec.boot != r.boot || r.desdeBoot < rec.desdeBoot || r.desdeBoot - rec.desdeBoot > REVALIDAR_MS
    }

    fun limpar(ctx: Context) {
        ctx.getSharedPreferences(PREFS, 0).edit().remove("payload").remove("sig").remove("marca_t").remove("marca_e").remove("marca_b").apply()
    }

    // ---------------- servidor ----------------
    sealed class Resultado {
        data class Ok(val decisao: LicencaLogica.Decisao) : Resultado()
        /** [semConta] = token inválido/anônimo: voltar ao login. */
        data class Erro(val mensagem: String, val semConta: Boolean = false) : Resultado()
    }

    suspend fun verificar(ctx: Context): Resultado = withContext(Dispatchers.IO) {
        val user = Conta.usuario() ?: return@withContext Resultado.Erro("Entre na sua conta.", semConta = true)
        val token = Conta.idToken() ?: return@withContext Resultado.Erro("Sem internet para confirmar sua conta.")
        val aid = androidId(ctx)
        val nonce = ByteArray(18).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        try {
            val c = (URL(base() + "/license/check").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 10_000; readTimeout = 15_000
                setRequestProperty("content-type", "application/json; charset=utf-8")
                setRequestProperty("User-Agent", "RotaGV-Android/${BuildConfig.VERSION_CODE}")
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("X-Device-Id", aid)
                setRequestProperty("X-App-Version", BuildConfig.VERSION_CODE.toString())
            }
            val r0 = SystemClock.elapsedRealtime()
            try {
                c.outputStream.use { it.write(JSONObject().put("nonce", nonce).put("versionCode", BuildConfig.VERSION_CODE).toString().toByteArray()) }
                val code = c.responseCode
                val corpo = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code == 401) return@withContext Resultado.Erro("Sua sessão expirou. Entre de novo.", semConta = true)
                if (code !in 200..299) return@withContext Resultado.Erro(runCatching { JSONObject(corpo).optString("error") }.getOrNull()?.ifBlank { null } ?: "Servidor indisponível ($code).")
                val o = JSONObject(corpo)
                val payload = o.getString("payload"); val sig = o.getString("sig")
                val pl = LicencaLogica.Payload.ler(payload)
                if (pl == null || !AssinaturaLicenca.confere(payload, sig) || !LicencaLogica.respostaValida(pl, nonce, user.uid, deviceHash(aid))) {
                    return@withContext Resultado.Erro("Resposta do servidor inválida.")
                }
                // Âncora: o servidor respondeu entre r0 e agora; usa o ponto médio no relógio de boot.
                val r = relogio(ctx)
                val meio = (r0 + r.desdeBoot) / 2
                ctx.getSharedPreferences(PREFS, 0).edit()
                    .putString("payload", payload).putString("sig", sig)
                    .putLong("rec_t", pl.serverTime).putLong("rec_e", meio).putInt("rec_b", r.boot)
                    // resposta nova zera a marca (corrige uma data adiantada antes)
                    .putLong("marca_t", pl.serverTime).putLong("marca_e", meio).putInt("marca_b", r.boot)
                    .putBoolean("revalidar", false)
                    .apply()
                Resultado.Ok(decidir(ctx))
            } finally { c.disconnect() }
        } catch (e: Exception) {
            Resultado.Erro("Sem internet para confirmar sua assinatura.")
        }
    }

    /** "Já paguei": avisa o servidor (o admin confere o Pix e libera). Retorna o código ou null. */
    suspend fun avisarPagamento(ctx: Context): String? = withContext(Dispatchers.IO) {
        val token = Conta.idToken() ?: return@withContext null
        runCatching {
            val c = (URL(base() + "/license/claim").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 10_000; readTimeout = 15_000
                setRequestProperty("content-type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("X-Device-Id", androidId(ctx))
            }
            try {
                c.outputStream.use { it.write(JSONObject().put("note", "app ${BuildConfig.VERSION_NAME}").toString().toByteArray()) }
                if (c.responseCode !in 200..299) null else JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optString("code").ifBlank { null }
            } finally { c.disconnect() }
        }.getOrNull()
    }

    /**
     * Cabeçalhos de licença para /chat, /tts e /receipt (o Worker recusa sem conta ou com teste vencido).
     * Chamar fora da thread principal.
     */
    fun cabecalhos(c: HttpURLConnection) {
        val ctx = app ?: return
        c.setRequestProperty("X-App-Version", BuildConfig.VERSION_CODE.toString())
        c.setRequestProperty("X-Device-Id", androidId(ctx))
        val token = if (!BuildConfig.FIREBASE_CONFIGURED) null else runCatching { runBlocking { withTimeoutOrNull(8_000) { Conta.idToken() } } }.getOrNull()
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
    }

    /** Respostas 401/402/426 do servidor: a licença mudou; força nova consulta na próxima abertura. */
    fun respostaServidor(code: Int) {
        if (code == 401 || code == 402 || code == 426) app?.getSharedPreferences(PREFS, 0)?.edit()?.putBoolean("revalidar", true)?.apply()
    }
}
