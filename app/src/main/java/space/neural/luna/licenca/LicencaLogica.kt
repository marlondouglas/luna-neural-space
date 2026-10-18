package space.neural.luna.licenca

import org.json.JSONObject
import kotlin.math.ceil

/**
 * Regras locais da licença (sem Android, testáveis). O servidor (Worker /license/check) decide e assina;
 * aqui só se conta o tempo desde a última resposta assinada, sem confiar no relógio do aparelho:
 *  - "agora confiável" = horário do servidor + tempo decorrido desde a resposta (elapsedRealtime, que não muda
 *    quando o usuário mexe na data); depois de reiniciar o celular, usa o relógio mas nunca volta para trás
 *    da última marca conhecida;
 *  - relógio do aparelho atrasado em relação à marca = mexeram na data -> exige internet;
 *  - sem internet, funciona por no máximo graceMs (48 h) desde a última confirmação do servidor.
 */
object LicencaLogica {
    const val DIA_MS = 86_400_000L
    /** Tolerância para o relógio do aparelho estar atrás da marca (ajuste automático de rede). */
    const val TOLERANCIA_RELOGIO_MS = 10 * 60_000L

    data class Payload(
        val status: String,
        val uid: String,
        val device: String,
        val code: String,
        val trialStart: Long,
        val trialEndsAt: Long,
        val paidUntil: Long,
        val serverTime: Long,
        val graceMs: Long,
        val minVersionCode: Int,
        val downloadUrl: String,
        val nonce: String,
        /** Link de pagamento definido no servidor ("" = só Pix). */
        val checkoutUrl: String = "",
    ) {
        companion object {
            fun ler(json: String): Payload? = runCatching {
                val o = JSONObject(json)
                require(o.getInt("v") == 1)
                Payload(
                    status = o.getString("status"), uid = o.getString("uid"), device = o.getString("device"),
                    code = o.optString("code"), trialStart = o.getLong("trialStart"), trialEndsAt = o.getLong("trialEndsAt"),
                    paidUntil = o.getLong("paidUntil"), serverTime = o.getLong("serverTime"), graceMs = o.getLong("graceMs"),
                    minVersionCode = o.optInt("minVersionCode", 0), downloadUrl = o.optString("downloadUrl"), nonce = o.optString("nonce"),
                    checkoutUrl = o.optString("checkoutUrl"),
                )
            }.getOrNull()
        }
    }

    /** Relógios do aparelho: parede (pode ser alterado), desde o boot (não pode) e qual boot é. */
    data class Relogio(val parede: Long, val desdeBoot: Long, val boot: Int)

    /** Âncora de tempo confiável: um instante já confirmado e onde ele estava no relógio de boot. */
    data class Ancora(val tempo: Long, val desdeBoot: Long, val boot: Int)

    data class Cache(val payload: Payload, val recebido: Ancora)

    sealed class Decisao {
        data class Teste(val diasRestantes: Int, val terminaEm: Long, val codigo: String) : Decisao()
        data class Pago(val ate: Long, val diasRestantes: Int, val codigo: String) : Decisao()
        data class Expirado(val codigo: String) : Decisao()
        data class Atualizar(val url: String) : Decisao()
        object LimiteAparelhos : Decisao()
        /** Sem resposta válida do servidor, offline demais ou data mexida: precisa conectar. */
        data class SemConexao(val relogioAlterado: Boolean) : Decisao()

        val liberado: Boolean get() = this is Teste || this is Pago
    }

    /**
     * Instante confiável agora. [marca] = maior instante confiável já visto (persistido a cada uso).
     * Retorna o instante e se o relógio de parede parece ter sido atrasado.
     */
    fun agoraConfiavel(cache: Cache, marca: Ancora?, r: Relogio): Pair<Long, Boolean> {
        val candidatos = mutableListOf(cache.payload.serverTime)
        fun avancar(a: Ancora) {
            if (a.boot == r.boot && r.desdeBoot >= a.desdeBoot) candidatos += a.tempo + (r.desdeBoot - a.desdeBoot)
            else candidatos += a.tempo
        }
        avancar(cache.recebido)
        marca?.let { avancar(it) }
        val mesmoBoot = cache.recebido.boot == r.boot && r.desdeBoot >= cache.recebido.desdeBoot ||
            (marca != null && marca.boot == r.boot && r.desdeBoot >= marca.desdeBoot)
        // Reiniciou e não há âncora deste boot: o relógio de parede é o único que avança (nunca para trás).
        if (!mesmoBoot) candidatos += r.parede
        val agora = candidatos.max()
        val alterado = r.parede < agora - TOLERANCIA_RELOGIO_MS
        return agora to alterado
    }

    /** Só aceita https (o payload é assinado, mas o padrão embutido também passa por aqui). */
    fun linkPagamento(servidor: String?, padrao: String): String {
        fun ok(u: String?) = u != null && Regex("^https://[^\\s\"<>]{4,300}$").matches(u.trim())
        return when {
            servidor != null && ok(servidor) -> servidor.trim()
            ok(padrao) -> padrao.trim()
            else -> ""
        }
    }

    fun dias(ms: Long): Int = ceil(ms.coerceAtLeast(0) / DIA_MS.toDouble()).toInt()

    /**
     * Decide o que o app mostra. [cache] = última resposta ASSINADA e conferida (null = nunca confirmou).
     * [uid]/[device] = conta e aparelho atuais (a licença de outra conta/aparelho não vale).
     */
    fun decidir(cache: Cache?, marca: Ancora?, r: Relogio, uid: String, device: String, versionCode: Int): Decisao {
        if (cache == null) return Decisao.SemConexao(false)
        val p = cache.payload
        if (p.uid != uid || p.device != device) return Decisao.SemConexao(false)
        if (p.minVersionCode > versionCode) return Decisao.Atualizar(p.downloadUrl)
        if (p.status == "device_limit") return Decisao.LimiteAparelhos
        val (agora, alterado) = agoraConfiavel(cache, marca, r)
        val pago = p.paidUntil > agora
        val emTeste = agora < p.trialEndsAt
        if (!pago && !emTeste) return Decisao.Expirado(p.code) // vencido não precisa de internet para bloquear
        if (alterado) return Decisao.SemConexao(true)
        if (agora - p.serverTime > p.graceMs) return Decisao.SemConexao(false)
        return if (pago) Decisao.Pago(p.paidUntil, dias(p.paidUntil - agora), p.code)
        else Decisao.Teste(dias(p.trialEndsAt - agora), p.trialEndsAt, p.code)
    }

    /** Nova marca a persistir depois de decidir (o maior instante confiável visto, ancorado neste boot). */
    fun novaMarca(cache: Cache, marca: Ancora?, r: Relogio): Ancora = Ancora(agoraConfiavel(cache, marca, r).first, r.desdeBoot, r.boot)

    /** Confere uma resposta nova do servidor contra o pedido (nonce) e a conta/aparelho. */
    fun respostaValida(p: Payload, nonce: String, uid: String, device: String): Boolean =
        p.nonce == nonce && p.uid == uid && p.device == device
}
