package space.neural.luna.chat

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import space.neural.luna.BuildConfig
import space.neural.luna.dashboard.Fmt
import space.neural.luna.dashboard.Painel
import space.neural.luna.dashboard.TurnoAcoes
import space.neural.luna.data.local.CategoriaGasto
import space.neural.luna.data.local.EstadoTurno
import space.neural.luna.data.local.Ganho
import space.neural.luna.data.local.Gasto
import space.neural.luna.data.local.MaquinaTurno
import space.neural.luna.data.local.MensagemChat
import space.neural.luna.data.local.Meta
import space.neural.luna.data.local.TipoMeta
import space.neural.luna.data.repo.LunaRepositories
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Base64

/**
 * A conversa com a Luna, usada pelo chat e pelo card da Luna na tela inicial.
 * Guarda o histórico no Room, roda os comandos no aparelho (lançar ganho, turno,
 * nota fiscal) e usa a IA do servidor (/chat) com queda para respostas locais.
 * Respostas podem trazer botões; o botão manda [Botao.comando] de volta.
 * Nada muda nos dados sem ele confirmar.
 */
class LunaConversa private constructor(private val appCtx: Context) {
    data class Botao(val rotulo: String, val comando: String, val principal: Boolean = false)
    data class Resposta(val texto: String, val botoes: List<Botao> = emptyList(), val usouIa: Boolean? = null)

    /** Nota fiscal lida pela IA e ainda não salva. */
    data class Nota(val loja: String, val totalCentavos: Long, val data: String, val itens: String)

    private val repo = LunaRepositories(appCtx)
    private val remoto = RemotoCerebro()
    private val local = LocalCerebro()
    private val trava = Mutex()
    val remotoDisponivel get() = remoto.disponivel

    private var ganhoPendente: Comandos.Comando.Ganho? = null
    private var nota: Nota? = null
    private var envelopeDaNota: String? = null // id do limite, "comum" ou null

    private suspend fun painel(): Painel = Painel.observar(repo).first()

    private suspend fun salvar(role: String, texto: String, voz: Boolean = false) =
        repo.salvarMensagem(MensagemChat(role = role, texto = texto, voz = voz))

    suspend fun historico(limite: Int = 200): List<Msg> = repo.ultimasMensagens(limite).map { Msg(it.role, it.texto) }

    /** Mensagem dele (digitada, falada ou botão). Salva as duas mensagens no histórico. */
    suspend fun enviar(texto: String, voz: Boolean = false, rotulo: String? = null): Resposta = trava.withLock {
        salvar("user", rotulo ?: texto, voz)
        val p = painel()
        val r = tratarLocal(texto, p) ?: run {
            val hist = historico(12)
            try {
                if (!remoto.disponivel) error("sem servidor")
                Resposta(remoto.responder(hist, p), usouIa = true)
            } catch (e: Exception) {
                Resposta(local.responder(hist, p), usouIa = false)
            }
        }
        salvar("assistant", r.texto)
        r
    }

    // ---------------- Comandos no aparelho ----------------

    private suspend fun tratarLocal(texto: String, p: Painel): Resposta? {
        if (texto.startsWith(CMD)) return botao(texto.removePrefix(CMD), p)
        ganhoPendente?.let { pend ->
            when (Comandos.totalOuMais(texto)) {
                "TOTAL" -> {
                    ganhoPendente = null
                    val atual = p.porApp.firstOrNull { it.app == pend.app }?.centavos ?: 0
                    val dif = pend.centavos - atual
                    if (dif <= 0) return Resposta("O ${Fmt.nomeApp(pend.app)} já está com ${Fmt.reais(atual)} hoje, então não lancei nada.")
                    repo.salvarGanho(Ganho(turnoId = p.turno?.id, data = p.hoje.toString(), app = pend.app, valorCentavos = dif, descricao = "Ajuste para o total do dia"))
                    return Resposta("Pronto! Lancei ${Fmt.reais(dif)} no ${Fmt.nomeApp(pend.app)}, e o total do dia ficou ${Fmt.reais(pend.centavos)}.")
                }
                "MAIS" -> {
                    ganhoPendente = null
                    repo.salvarGanho(Ganho(turnoId = p.turno?.id, data = p.hoje.toString(), app = pend.app, valorCentavos = pend.centavos))
                    return Resposta("Lancei mais ${Fmt.reais(pend.centavos)} no ${Fmt.nomeApp(pend.app)}. 💜")
                }
                else -> ganhoPendente = null
            }
        }
        val acoes = TurnoAcoes(repo) { space.neural.luna.sync.RealtimeSync.guarda(appCtx) }
        return when (val c = Comandos.interpretar(texto)) {
            is Comandos.Comando.Ganho -> {
                ganhoPendente = c
                Resposta("${Fmt.reais(c.centavos)} no ${Fmt.nomeApp(c.app)}: é o total do dia ou um valor a mais?",
                    listOf(Botao("É o total", "total"), Botao("É a mais", "a mais", principal = true)))
            }
            is Comandos.Comando.Turno -> {
                val t = p.turno
                val s = p.situacao
                try { Resposta(when (c.acao) {
                    "INICIAR" -> when (s.fase) {
                        MaquinaTurno.Fase.PRONTO -> { acoes.iniciar(s); "Turno ${s.numero}/4 iniciado! Boa rota. ▶" }
                        MaquinaTurno.Fase.ULTIMO_FECHADO -> "Os 4 turnos de hoje já foram. Diga \"retomar\" para continuar no turno 4 ou conclua o dia no painel."
                        MaquinaTurno.Fase.CONCLUIDO -> "O dia já foi concluído. Você pode reabrir pelo painel."
                        else -> "Já tem um turno aberto (turno ${s.numero}/4)."
                    }
                    "PAUSAR" -> if (t?.estado == EstadoTurno.ATIVO) { acoes.pausar(t); "Turno pausado. ⏸" } else "Não há turno trabalhando para pausar."
                    "CAFE" -> if (t?.estado == EstadoTurno.ATIVO || t?.estado == EstadoTurno.PAUSADO) { acoes.cafe(t); "Bom café! ☕ O tempo conta como parado." } else "Não há turno aberto."
                    "RETOMAR" -> if (t != null && (t.estado == EstadoTurno.PAUSADO || t.estado == EstadoTurno.CAFE || t.estado == EstadoTurno.FECHADO)) { acoes.retomar(t); "De volta ao trabalho! ▶" } else "O turno não está pausado."
                    else -> return null
                }) } catch (e: space.neural.luna.sync.TurnoNoOutroAparelho) { Resposta(e.message.orEmpty()) }
            }
            null -> null
        }
    }

    // ---------------- Nota fiscal (foto) ----------------

    /** Foto já reduzida (JPEG ≤1600 px). Lê no servidor e pergunta em qual envelope entra. */
    suspend fun lerNota(jpeg: ByteArray): Resposta = trava.withLock {
        salvar("user", "📷 Foto de nota fiscal")
        val r = try {
            val j = enviarNota(jpeg)
            if (!j.optBoolean("eh_compra")) Resposta("Não consegui achar uma compra nessa foto. Tente uma foto mais de perto, com o total aparecendo.")
            else {
                val n = Nota(j.optString("loja").ifBlank { "loja" }, j.optLong("total_centavos"), j.optString("data"), j.optString("itens"))
                nota = n; envelopeDaNota = null
                val dia = runCatching { LocalDate.parse(n.data) }.getOrNull()?.let { " em ${"%02d/%02d".format(it.dayOfMonth, it.monthValue)}" } ?: ""
                val p = painel()
                val bts = p.limites.map { Botao(it.nome, "${CMD}env:${it.id}") } +
                    listOf(Botao("Gasto comum", "${CMD}env:comum"), Botao("Não é compra", "${CMD}env:nao"))
                Resposta("Compra no ${n.loja}$dia, total ${Fmt.reais(n.totalCentavos)}." +
                    (if (n.itens.isNotBlank()) " Itens: ${n.itens}." else "") +
                    if (p.limites.isEmpty()) " Você ainda não tem limites no Planejamento. Lanço como gasto comum?" else " Em qual limite entra?", bts)
            }
        } catch (e: Exception) {
            Resposta("Não consegui ler a nota agora (sem internet ou servidor ocupado). Você pode lançar o gasto tocando no limite, no painel.")
        }
        salvar("assistant", r.texto)
        r
    }

    private suspend fun enviarNota(jpeg: ByteArray): JSONObject = withContext(Dispatchers.IO) {
        val ep = BuildConfig.LUNA_CHAT_ENDPOINT.removeSuffix("/chat") + "/receipt"
        check(ep.startsWith("https://")) { "Servidor não configurado" }
        val body = JSONObject().put("mime", "image/jpeg").put("image", Base64.getEncoder().encodeToString(jpeg)).toString()
        val c = (URL(ep).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 10_000; readTimeout = 60_000
            setRequestProperty("content-type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", "LunaNeuralSpace-Android")
            space.neural.luna.licenca.Licenca.cabecalhos(this)
        }
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            space.neural.luna.licenca.Licenca.respostaServidor(c.responseCode)
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }

    private suspend fun botao(cmd: String, p: Painel): Resposta {
        val n = nota
        when {
            cmd.startsWith("env:") -> {
                if (n == null) return Resposta("Essa nota já foi resolvida. Mande outra foto se quiser.")
                val alvo = cmd.removePrefix("env:")
                if (alvo == "nao") { nota = null; return Resposta("Tudo bem, não lancei nada.") }
                envelopeDaNota = alvo
                val nome = if (alvo == "comum") "gasto comum" else p.limites.firstOrNull { it.id == alvo }?.nome ?: "limite"
                return Resposta("Salvar ${Fmt.reais(n.totalCentavos)} do ${n.loja} em $nome?",
                    listOf(Botao("Sim", "${CMD}nota:sim", principal = true), Botao("Cancelar", "${CMD}nota:nao")))
            }
            cmd == "nota:nao" -> { nota = null; envelopeDaNota = null; return Resposta("Cancelado, não salvei nada.") }
            cmd == "nota:sim" -> {
                val alvo = envelopeDaNota
                if (n == null || alvo == null) return Resposta("Essa nota já foi resolvida.")
                nota = null; envelopeDaNota = null
                val hoje = p.hoje
                val data = runCatching { LocalDate.parse(n.data) }.getOrNull()?.takeIf { !it.isAfter(hoje) } ?: hoje
                val limite = p.limites.firstOrNull { it.id == alvo }
                repo.salvarGasto(Gasto(data = data.toString(), categoria = CategoriaGasto.OUTRO, valorCentavos = n.totalCentavos,
                    descricao = (n.loja + if (n.itens.isNotBlank()) " — ${n.itens}" else "").take(200), limiteId = limite?.id))
                if (limite == null) return Resposta("Lancei ${Fmt.reais(n.totalCentavos)} como gasto comum. 💜")
                val env = painel().envelopes().firstOrNull { it.limite.id == limite.id }
                    ?: return Resposta("Lancei ${Fmt.reais(n.totalCentavos)} em ${limite.nome}.")
                if (!env.excedido) return Resposta("Pronto! Agora você só pode gastar ${Fmt.reais(env.restanteCentavos)} em ${limite.nome.lowercase()} este mês.")
                return Resposta("⚠ Você gastou muito: passou ${Fmt.reais(env.excessoCentavos)} do limite de ${limite.nome} (${Fmt.reais(env.gastoCentavos)} de ${Fmt.reais(limite.limiteCentavos)}). Isso coloca o plano do mês em risco. O que prefere?",
                    listOf(Botao("Recalcular meu plano", "${CMD}plano:recalc", principal = true), Botao("Deixar para o mês que vem", "${CMD}plano:manter")))
            }
            cmd == "plano:recalc" -> {
                val r = painel().recalculo()
                val horas = r.horasDia?.let { " (cerca de ${"%.1f".format(it).replace('.', ',')} h por dia)" } ?: ""
                return Resposta("Novo plano para sair do vermelho: precisa de ${Fmt.reais(r.necessarioMesCentavos)} no mês; já tem ${Fmt.reais(r.liquidoMesCentavos)} " +
                    "e faltam ${Fmt.reais(r.faltaCentavos)} em ${r.diasRestantes} dias de trabalho. Meta do dia: ${Fmt.reais(r.metaDiaCentavos)}$horas; " +
                    "meta da semana: ${Fmt.reais(r.metaSemanaCentavos)}. Aplico?",
                    listOf(Botao("Aplicar novo plano", "${CMD}plano:aplicar", principal = true), Botao("Manter como está", "${CMD}plano:manter")))
            }
            cmd == "plano:aplicar" -> {
                val r = painel().recalculo()
                val h = p.hoje.toString()
                repo.salvarMeta(Meta(data = h, tipo = TipoMeta.DIA, valorCentavos = r.metaDiaCentavos))
                repo.salvarMeta(Meta(data = h, tipo = TipoMeta.SEMANA, valorCentavos = r.metaSemanaCentavos))
                repo.salvarMeta(Meta(data = h, tipo = TipoMeta.MES, valorCentavos = r.necessarioMesCentavos))
                return Resposta("Plano atualizado! Meta do dia ${Fmt.reais(r.metaDiaCentavos)}, semana ${Fmt.reais(r.metaSemanaCentavos)}, mês ${Fmt.reais(r.necessarioMesCentavos)}. Bora! 💪")
            }
            cmd == "plano:manter" -> return Resposta("Ok, mantive seu plano como está. No mês que vem a gente ajusta. 💜")
        }
        return Resposta("Não entendi esse botão.")
    }

    companion object {
        /** Prefixo dos comandos de botão (não são texto dele). */
        const val CMD = "§"
        @Volatile private var inst: LunaConversa? = null
        fun get(ctx: Context): LunaConversa = inst ?: synchronized(this) { inst ?: LunaConversa(ctx.applicationContext).also { inst = it } }
    }
}
