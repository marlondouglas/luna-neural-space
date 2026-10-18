package space.neural.luna.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import space.neural.luna.BuildConfig
import space.neural.luna.dashboard.Calculos
import space.neural.luna.dashboard.Conteudo
import space.neural.luna.dashboard.Fmt
import space.neural.luna.dashboard.Painel
import space.neural.luna.data.local.EstadoTurno
import space.neural.luna.data.local.TipoMeta
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer

/** Uma mensagem da conversa. role = "user" ou "assistant". */
data class Msg(val role: String, val content: String)

/**
 * Cérebro da Luna. Qualquer implementação recebe o histórico e o painel do dia.
 * Hoje há duas: [RemotoCerebro] (servidor /chat, com IA) e [LocalCerebro]
 * (respostas prontas a partir dos dados, funciona offline). Uma IA de voz
 * (LiveKit) pode entrar aqui no futuro implementando esta interface.
 */
interface LunaCerebro {
    suspend fun responder(historico: List<Msg>, painel: Painel): String
}

/** Chama POST {LUNA_CHAT_ENDPOINT}: {messages, context} -> {reply}. */
class RemotoCerebro(private val endpoint: String = BuildConfig.LUNA_CHAT_ENDPOINT) : LunaCerebro {
    val disponivel: Boolean get() = endpoint.startsWith("https://")

    override suspend fun responder(historico: List<Msg>, painel: Painel): String = withContext(Dispatchers.IO) {
        check(disponivel) { "Servidor de chat não configurado" }
        val body = JSONObject().apply {
            put("messages", JSONArray().apply {
                historico.takeLast(12).forEach { put(JSONObject().put("role", it.role).put("content", it.content.take(1000))) }
            })
            put("context", painel.contexto().apply { space.neural.luna.conta.Conta.primeiroNome().takeIf { it.isNotBlank() }?.let { put("motorista", it) } })
        }.toString()
        val c = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 10_000; readTimeout = 50_000
            setRequestProperty("content-type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", "LunaNeuralSpace-Android")
            space.neural.luna.licenca.Licenca.cabecalhos(this)
        }
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            space.neural.luna.licenca.Licenca.respostaServidor(c.responseCode)
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            val r = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optString("reply").trim()
            check(r.isNotEmpty()) { "Resposta vazia" }
            r
        } finally { c.disconnect() }
    }
}

/** Respostas locais a partir dos dados do dia (sem internet). */
class LocalCerebro : LunaCerebro {
    override suspend fun responder(historico: List<Msg>, painel: Painel): String {
        val q = normalizar(historico.lastOrNull { it.role == "user" }?.content ?: "")
        val r = painel.resumo
        val dia = painel.metas.first { it.tipo == TipoMeta.DIA }
        fun meta(p: Calculos.ProgressoMeta, nome: String) = when {
            p.metaCentavos == null -> "Você ainda não definiu a meta $nome. Toque em \"Definir meta\" no painel."
            p.percentual >= 100 -> "Meta $nome batida! Você está ${Fmt.reais(p.acimaCentavos)} acima. 🎉"
            else -> "Meta $nome: ${p.percentual}% (${Fmt.reais(p.liquidoCentavos)} de ${Fmt.reais(p.metaCentavos)}). Faltam ${Fmt.reais(p.faltaCentavos)}."
        }
        return when {
            q.isBlank() -> "Estou aqui! Pergunte sobre seus ganhos, metas, turno ou peça uma dica."
            tem(q, "semana") && tem(q, "meta") -> meta(painel.metas.first { it.tipo == TipoMeta.SEMANA }, "da semana")
            tem(q, "mes") && tem(q, "meta") -> meta(painel.metas.first { it.tipo == TipoMeta.MES }, "do mês")
            tem(q, "meta", "falta") -> meta(dia, "do dia")
            tem(q, "uber", "99", "indrive", "gogeta", "particular", "app") -> {
                val melhor = painel.porApp.maxByOrNull { it.centavos }
                painel.porApp.joinToString(" · ") { "${Fmt.nomeApp(it.app)}: ${Fmt.reais(it.centavos)} (${it.corridas})" } +
                    if (melhor != null && melhor.centavos > 0) ". O que mais rendeu hoje foi o ${Fmt.nomeApp(melhor.app)}." else "."
            }
            tem(q, "energia", "recarga", "kwh", "bateria") ->
                if (r.temRecarga) "Energia hoje: ${Fmt.reais(r.energiaCentavos)}" + (if (r.energiaKwh > 0) " (${Fmt.kwh(r.energiaKwh)} kWh)." else ".")
                else "Nenhuma recarga lançada hoje. Toque em Energia no resumo para lançar."
            tem(q, "gasto", "gastei") -> "Gastos de hoje: ${Fmt.reais(r.gastosCentavos)} (energia ${Fmt.reais(r.energiaCentavos)}, manutenção ${Fmt.reais(r.manutencaoCentavos)}, outros ${Fmt.reais(r.outrosCentavos)})."
            tem(q, "hora", "turno", "tempo") -> {
                val agora = space.neural.luna.sync.Relogio.agora()
                val t = painel.turno
                if (t == null) "Você está sem turno. Diga \"iniciar turno\" ou toque em Iniciar no painel."
                else {
                    val ph = Calculos.porHoraCentavos(r.liquidoCentavos, painel.trabalhoDia(agora))
                    "Turno ${painel.situacao.numero}/4 ${estado(t.estado)}: ${Fmt.hms(Calculos.trabalhoMs(t, agora))} de trabalho e ${Fmt.minutos(Calculos.paradoMs(t, agora))} min parado." +
                        (ph?.let { " Ganho por hora: ${Fmt.reais(it)}/h." } ?: "")
                }
            }
            tem(q, "dica") -> "💡 " + Conteudo.dica(painel.hoje)
            tem(q, "versiculo", "biblia", "palavra", "oracao") -> Conteudo.versiculo(painel.hoje).let { "\"${it.texto}\" — ${it.referencia}" }
            tem(q, "ganh", "quanto", "fiz", "liquido", "bruto", "resumo") ->
                if (!r.temGanho) "Ainda não tem corrida lançada hoje. Quando lançar, eu te conto tudo."
                else "Hoje: bruto ${Fmt.reais(r.brutoCentavos)}, gastos ${Fmt.reais(r.gastosCentavos)}, líquido ${Fmt.reais(r.liquidoCentavos)}. " + meta(dia, "do dia")
            tem(q, "oi", "ola", "bom dia", "boa tarde", "boa noite") -> "Oi${space.neural.luna.conta.Conta.vocativo()}! " + if (r.temGanho) "Hoje você já fez ${Fmt.reais(r.liquidoCentavos)} de líquido." else "Pronto para começar?"
            tem(q, "obrigad", "valeu") -> "Por nada! Boa rota e bom trabalho. 💜"
            else -> "Estou sem internet agora, então respondo o básico: ganhos, metas, turno, energia, dicas e versículo. " +
                "Também lanço corridas: diga, por exemplo, \"30 na Uber\"."
        }
    }

    private fun estado(e: String) = when (e) {
        EstadoTurno.ATIVO -> "trabalhando"; EstadoTurno.PAUSADO -> "pausado"; EstadoTurno.CAFE -> "no café"
        EstadoTurno.FECHADO -> "fechado"; else -> e.lowercase()
    }

    companion object {
        fun normalizar(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        fun tem(q: String, vararg termos: String) = termos.any { q.contains(it) }
    }
}

/**
 * Comandos que a Luna executa no aparelho (sem IA), antes de conversar:
 * - lançar ganho: "30 na Uber", "lança 45,50 no 99", "30 na Gogeta", "50 particular", "corrida particular de 40" — pergunta "é o total ou a mais?" (spec 2.2);
 * - turno: "iniciar turno", "pausar", "café", "retomar".
 */
object Comandos {
    sealed interface Comando {
        data class Ganho(val app: String, val centavos: Long) : Comando
        data class Turno(val acao: String) : Comando
    }

    private val valorRe = Regex("(\\d{1,5}(?:[.,]\\d{1,2})?)")

    fun interpretar(texto: String): Comando? {
        val q = LocalCerebro.normalizar(texto).trim()
        val app = when {
            Regex("\\b(indrive|in drive)\\b").containsMatchIn(q) -> space.neural.luna.data.local.AppCorrida.INDRIVE
            Regex("\\b(gogeta|go geta|gogueta)\\b").containsMatchIn(q) -> space.neural.luna.data.local.AppCorrida.GOGETA
            Regex("\\bparticular(es)?\\b").containsMatchIn(q) -> space.neural.luna.data.local.AppCorrida.PARTICULAR
            Regex("\\buber\\b").containsMatchIn(q) -> space.neural.luna.data.local.AppCorrida.UBER
            Regex("\\b(99|noventa e nove)\\b").containsMatchIn(q) && valorRe.findAll(q).count() >= 2 -> space.neural.luna.data.local.AppCorrida.NOVENTA_E_NOVE
            Regex("\\bno 99\\b|\\bna 99\\b|\\bdo 99\\b|\\bda 99\\b").containsMatchIn(q) -> space.neural.luna.data.local.AppCorrida.NOVENTA_E_NOVE
            else -> null
        }
        if (app != null && !q.contains("?") && !LocalCerebro.tem(q, "quanto", "qual", "como")) {
            val semApp = if (app == space.neural.luna.data.local.AppCorrida.NOVENTA_E_NOVE) {
                val pre = Regex("\\b(no|na|do|da) 99\\b")
                if (pre.containsMatchIn(q)) q.replaceFirst(pre, "") else q.replaceFirst(Regex("\\b99\\b"), "")
            } else q
            val nums = valorRe.findAll(semApp).map { it.value }.toList()
            val c = nums.firstNotNullOfOrNull { Calculos.parseCentavos(it) }
            if (c != null) return Comando.Ganho(app, c)
        }
        return when {
            Regex("^(iniciar|inicia|comecar|começar)( o)? turno").containsMatchIn(q) -> Comando.Turno("INICIAR")
            Regex("^(pausar|pausa)\\b").containsMatchIn(q) -> Comando.Turno("PAUSAR")
            Regex("^(cafe|hora do cafe)\\b").containsMatchIn(q) -> Comando.Turno("CAFE")
            Regex("^(retomar|voltar|continuar)\\b").containsMatchIn(q) -> Comando.Turno("RETOMAR")
            else -> null
        }
    }

    /** Resposta a "total ou a mais?": "TOTAL", "MAIS" ou null. */
    fun totalOuMais(texto: String): String? {
        val q = LocalCerebro.normalizar(texto)
        return when {
            LocalCerebro.tem(q, "a mais", "mais", "somar", "soma", "adicion") -> "MAIS"
            LocalCerebro.tem(q, "total") -> "TOTAL"
            else -> null
        }
    }
}
