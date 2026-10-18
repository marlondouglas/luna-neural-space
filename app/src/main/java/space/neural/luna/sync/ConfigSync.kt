package space.neural.luna.sync

import android.content.Context
import android.content.SharedPreferences

/**
 * Configurações compartilhadas entre os aparelhos da conta (v0.1.7): users/{uid}/config/dashboard,
 * um registro só com "último a alterar vence".
 *
 * Só vão as preferências que fazem sentido iguais no celular e na central: preço do kWh, limite de R$/hora
 * e "Luna fala os alertas". Voz/microfone (barge-in, conversa contínua, falar no chat), período do painel e
 * adiamentos ficam em cada aparelho.
 */
object ConfigSync {
    const val PATH = "config/dashboard"
    const val PREFS_DASH = "dashboard"
    private const val PREFS = "config_sync"

    /** chave → tipo (Long ou Boolean). */
    val COMPARTILHADAS: Map<String, Class<*>> = mapOf(
        "preco_kwh_centavos" to Long::class.javaObjectType,
        "limite_por_hora_centavos" to Long::class.javaObjectType,
        "voz_alertas" to Boolean::class.javaObjectType,
    )

    fun valores(dash: SharedPreferences): Map<String, Any?> =
        COMPARTILHADAS.keys.filter { dash.contains(it) }.associateWith { dash.all[it] }

    private fun assinatura(v: Map<String, Any?>) = v.toSortedMap().toString()

    data class Local(val updatedAt: Long, val dirty: Boolean)

    fun local(ctx: Context): Local = ctx.getSharedPreferences(PREFS, 0).let { Local(it.getLong("updatedAt", 0), it.getBoolean("dirty", false)) }

    @Volatile private var ouvinte: SharedPreferences.OnSharedPreferenceChangeListener? = null
    /** Chamado quando uma configuração compartilhada mudou neste aparelho (o app pede o envio). */
    @Volatile var aoMudar: ((Context) -> Unit)? = null

    fun iniciar(ctx: Context) {
        if (ouvinte != null) return
        val app = ctx.applicationContext
        val dash = app.getSharedPreferences(PREFS_DASH, 0)
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, k -> if (k in COMPARTILHADAS) mudouAqui(app) }
        ouvinte = l
        dash.registerOnSharedPreferenceChangeListener(l)
    }

    /** Registra a mudança local (se for de fato diferente do último estado sincronizado). */
    fun mudouAqui(ctx: Context) {
        val v = valores(ctx.getSharedPreferences(PREFS_DASH, 0))
        val p = ctx.getSharedPreferences(PREFS, 0)
        val a = assinatura(v)
        if (a == p.getString("assinatura", null)) return
        p.edit().putString("assinatura", a).putLong("updatedAt", Relogio.agora()).putBoolean("dirty", true).commit()
        aoMudar?.invoke(ctx)
    }

    /** Aplica a versão remota se ela vencer. */
    fun aplicarRemoto(ctx: Context, d: RemoteStore.Doc, eu: String): Boolean {
        val rv = d.versao ?: return false
        val l = local(ctx)
        if (!Conflito.remotoVence(Conflito.Versao(l.updatedAt, if (l.dirty) eu else null).takeIf { l.updatedAt > 0 }, rv)) return false
        val novos = COMPARTILHADAS.mapNotNull { (k, tipo) ->
            val x = d.data[k] ?: return@mapNotNull null
            k to when (tipo) { Boolean::class.javaObjectType -> x as? Boolean; else -> (x as? Number)?.toLong() }
        }.toMap().filterValues { it != null }
        val dash = ctx.getSharedPreferences(PREFS_DASH, 0)
        val final = valores(dash) + novos
        // Assinatura antes de escrever: o ouvinte vê "nada mudou" e não devolve a mudança para a nuvem.
        ctx.getSharedPreferences(PREFS, 0).edit().putString("assinatura", assinatura(final))
            .putLong("updatedAt", rv.updatedAt).putBoolean("dirty", false).commit()
        val e = dash.edit()
        for ((k, x) in novos) when (x) { is Boolean -> e.putBoolean(k, x); is Long -> e.putLong(k, x) }
        e.commit()
        return true
    }

    /**
     * 0.1.6 → 0.1.7: as configurações que já existem viram uma versão "muito antiga" (updatedAt = 1):
     * sobem se a conta ainda não tiver nenhuma, mas perdem para qualquer versão que já esteja na nuvem.
     */
    fun migrar(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, 0)
        if (p.contains("updatedAt")) return
        val v = valores(ctx.getSharedPreferences(PREFS_DASH, 0))
        p.edit().putString("assinatura", assinatura(v)).putLong("updatedAt", if (v.isEmpty()) 0 else 1).putBoolean("dirty", v.isNotEmpty()).commit()
    }

    /** Troca de conta: a versão desta conta vem da nuvem. */
    fun redefinir(ctx: Context) { ctx.getSharedPreferences(PREFS, 0).edit().clear().commit() }

    class Remoto(private val ctx: Context, private val remote: RemoteStore, uid: String, private val deviceId: String) {
        suspend fun baixar() { remote.ler(PATH)?.let { aplicarRemoto(ctx, it, deviceId) } }
        fun aoReceber(d: RemoteStore.Doc?) { d?.let { aplicarRemoto(ctx, it, deviceId) } }

        suspend fun enviar() {
            val l = local(ctx)
            if (!l.dirty) return
            val v = valores(ctx.getSharedPreferences(PREFS_DASH, 0))
            var perdeu: RemoteStore.Doc? = null
            remote.transacao(listOf(PATH)) { lidos ->
                val r = lidos[PATH]
                if (Conflito.envioVence(Conflito.Versao(l.updatedAt, deviceId), r?.versao)) {
                    perdeu = null
                    mapOf(PATH to v + mapOf("updatedAt" to l.updatedAt, "deviceId" to deviceId))
                } else { perdeu = r; emptyMap() }
            }
            val r = perdeu
            if (r != null) aplicarRemoto(ctx, r, deviceId)
            else {
                val p = ctx.getSharedPreferences(PREFS, 0)
                if (p.getLong("updatedAt", 0) == l.updatedAt) p.edit().putBoolean("dirty", false).commit()
            }
        }
    }
}
