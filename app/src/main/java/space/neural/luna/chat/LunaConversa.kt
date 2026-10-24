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
class LunaConversa internal constructor(private val appCtx: Context, private val repo: LunaRepositories = LunaRepositories(appCtx)) {
    data class Botao(val rotulo: String, val comando: String, val principal: Boolean = false)
    data class Resposta(val texto: String, val botoes: List<Botao> = emptyList(), val usouIa: Boolean? = null, val destino: String? = null)

    /** Nota fiscal lida pela IA e ainda não salva. */
    data class Nota(val loja: String, val totalCentavos: Long, val data: String, val itens: String)

    private val remoto = RemotoCerebro()
    private val local = LocalCerebro()
    private val trava = Mutex()
    val remotoDisponivel get() = remoto.disponivel

    private var ganhoPendente: Comandos.Comando.Ganho? = null
    private data class FinancePending(val token: String, val expense: Gasto? = null, val income: Ganho? = null,
        val beforeExpense: Gasto? = null, val beforeIncome: Ganho? = null)
    private var financePending: FinancePending? = null
    private var correctingPending = false
    private var lastFinance: FinancePending? = null
    private var missingCategory: LunaIntentParser.Intent.Expense? = null
    private val correctionChoices = mutableMapOf<String, FinancePending>()
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
        tratarFinanceiro(texto, p)?.let { return it }
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

    private fun propor(expense: Gasto? = null, income: Ganho? = null, beforeExpense: Gasto? = null, beforeIncome: Ganho? = null): Resposta {
        val pending = FinancePending(java.util.UUID.randomUUID().toString(), expense, income, beforeExpense, beforeIncome)
        financePending = pending
        correctingPending = false
        val amount = expense?.valorCentavos ?: income!!.valorCentavos
        val date = expense?.data ?: income!!.data
        val action = if (expense?.deleted == true || income?.deleted == true) "Excluir" else if (beforeExpense != null || beforeIncome != null) "Alterar" else "Registrar"
        return Resposta("$action ${Fmt.reais(amount)}, $date. ${expense?.descricao ?: "Recebimento"}. Confirmar?",
            listOf(Botao("Confirmar", "${CMD}finance:confirm:${pending.token}", true), Botao("Corrigir", "${CMD}finance:correct:${pending.token}"), Botao("Cancelar", "${CMD}finance:cancel")))
    }

    private suspend fun tratarFinanceiro(texto: String, p: Painel): Resposta? {
        val q = LunaIntentParser.normalize(texto)
        if (!texto.startsWith(CMD) && !Regex("\\b(gastei|gasto|paguei|comprei|recebi|ganhei)\\b").containsMatchIn(q)
            && Comandos.interpretar(texto) != null) return null
        if (Regex("\\b(quanto|qual)\\b").containsMatchIn(q) && Regex("gastei|gasto").containsMatchIn(q)) {
            val month = "mes" in q
            val expenses = (p.gastosTodos + p.gastosLimites).distinctBy { it.id }.filter {
                !it.deleted && if (month) it.data.startsWith(p.hoje.toString().take(7)) else it.data == p.hoje.toString()
            }
            return Resposta("Gastos ${if (month) "neste mês" else "de hoje"}: ${Fmt.reais(expenses.sumOf { it.valorCentavos })}.")
        }
        if (texto.startsWith("${CMD}finance:choose:")) {
            val chosen = correctionChoices.remove(texto.substringAfterLast(':')) ?: return Resposta("Essa seleção expirou.")
            correctionChoices.clear()
            return propor(chosen.expense, chosen.income, chosen.beforeExpense, chosen.beforeIncome)
        }
        if (texto == "${CMD}finance:cancel" || q in setOf("cancelar", "cancela", "nao")) {
            financePending = null; missingCategory = null; correctingPending = false
            return Resposta("Cancelado.")
        }
        val correction = financePending
        if (correction != null && (texto == "${CMD}finance:correct:${correction.token}" || q in setOf("corrigir", "corrige"))) {
            correctingPending = true
            return Resposta("Qual é o valor correto? Depois eu peço sua confirmação.", listOf(Botao("Cancelar", "${CMD}finance:cancel")))
        }
        if (correctingPending && correction != null && !texto.startsWith(CMD)) {
            val cents = LunaIntentParser.amount(texto) ?: return Resposta("Informe um valor exato, como 20,50, ou cancele.")
            financePending = correction.copy(expense = correction.expense?.copy(valorCentavos = cents), income = correction.income?.copy(valorCentavos = cents))
            correctingPending = false
            return Resposta("Confirmar o valor corrigido de ${Fmt.reais(cents)}?", listOf(
                Botao("Confirmar", "${CMD}finance:confirm:${correction.token}", true),
                Botao("Corrigir", "${CMD}finance:correct:${correction.token}"), Botao("Cancelar", "${CMD}finance:cancel")))
        }
        if (texto.startsWith("${CMD}finance:confirm:") || (q in setOf("sim", "confirmar", "confirmo") && financePending != null)) {
            if (correctingPending) return Resposta("Informe o valor correto antes de confirmar.")
            val pending = financePending ?: return Resposta("Esse lançamento já foi resolvido.")
            if (texto.startsWith(CMD) && !texto.endsWith(pending.token)) return Resposta("Essa confirmação expirou.")
            // Mesmo UUID em eventual repetição: upsert não cria um segundo registro.
            pending.expense?.let { repo.salvarGasto(it) }
            pending.income?.let { repo.salvarGanho(it) }
            financePending = null; lastFinance = pending
            val envelope = pending.expense?.limiteId?.let { id -> painel().envelopes().firstOrNull { it.limite.id == id } }
            return Resposta("Registrado!" + (envelope?.let { " ${it.limite.nome}: disponível ${Fmt.reais(it.restanteCentavos)} de ${Fmt.reais(it.limite.limiteCentavos)}." } ?: ""),
                listOf(Botao("Desfazer", "desfazer")))
        }
        missingCategory?.let { missing ->
            val category = p.limites.singleOrNull { LunaIntentParser.normalize(it.nome) == q || texto == "${CMD}finance:category:${it.id}" }
            if (category != null && missing.cents != null) {
                missingCategory = null
                return propor(expense = Gasto(data = missing.date.toString(), categoria = CategoriaGasto.OUTRO,
                    valorCentavos = missing.cents, limiteId = category.id, descricao = category.nome))
            }
        }
        if (texto.startsWith("${CMD}finance:")) return Resposta("Essa ação expirou. Envie o lançamento novamente.")
        val command = LunaIntentParser.parse(texto, p.limites.map { it.nome }, p.hoje) ?: return null
        return when (command) {
            is LunaIntentParser.Intent.Navigate -> Resposta("Abrindo ${command.destination}.", destino = command.destination)
            LunaIntentParser.Intent.Limits -> Resposta(p.envelopes().joinToString("\n") {
                "${it.limite.nome}: gasto ${Fmt.reais(it.gastoCentavos)}, disponível ${Fmt.reais(it.restanteCentavos)} de ${Fmt.reais(it.limite.limiteCentavos)}."
            }.ifBlank { "Você ainda não cadastrou limites no Planejamento." })
            LunaIntentParser.Intent.Undo -> {
                val last = lastFinance ?: return Resposta("Não há lançamento desta conversa para desfazer.")
                last.expense?.let { if (last.beforeExpense != null) repo.salvarGasto(last.beforeExpense) else repo.excluirGasto(it.id) }
                last.income?.let { if (last.beforeIncome != null) repo.salvarGanho(last.beforeIncome) else repo.excluirGanho(it.id) }
                lastFinance = null
                Resposta("Lançamento desfeito. Os saldos foram recalculados.")
            }
            is LunaIntentParser.Intent.Clarify -> Resposta(command.question)
            is LunaIntentParser.Intent.Correction -> {
                if (!command.delete && command.newCents == null && command.newCategory == null)
                    return Resposta("Informe o novo valor ou uma categoria cadastrada.")
                val targetCategory = p.limites.singleOrNull { it.nome == command.newCategory }
                val originalCategory = p.limites.singleOrNull { it.nome == command.category }
                correctionChoices.clear()
                if (command.income) p.ganhosTodos.filter {
                    !it.deleted && (command.date == null || it.data == command.date.toString()) && (command.oldCents == null || it.valorCentavos == command.oldCents)
                }.take(20).forEach { before ->
                    val token = java.util.UUID.randomUUID().toString()
                    correctionChoices[token] = FinancePending(token, income = before.copy(valorCentavos = command.newCents ?: before.valorCentavos, deleted = command.delete), beforeIncome = before)
                } else (p.gastosTodos + p.gastosLimites).distinctBy { it.id }.filter {
                    !it.deleted && (command.date == null || it.data == command.date.toString()) &&
                        (command.oldCents == null || it.valorCentavos == command.oldCents) &&
                        (originalCategory == null || it.limiteId == originalCategory.id)
                }.take(20).forEach { before ->
                    val token = java.util.UUID.randomUUID().toString()
                    correctionChoices[token] = FinancePending(token, expense = before.copy(valorCentavos = command.newCents ?: before.valorCentavos,
                        limiteId = targetCategory?.id ?: before.limiteId, descricao = targetCategory?.nome ?: before.descricao, deleted = command.delete), beforeExpense = before)
                }
                if (correctionChoices.isEmpty()) Resposta("Não encontrei lançamento correspondente. Confira a data e a categoria.")
                else Resposta("Selecione o lançamento real que deseja ${if (command.delete) "excluir" else "alterar"}:", correctionChoices.map { (id, choice) ->
                    val amount = choice.beforeExpense?.valorCentavos ?: choice.beforeIncome!!.valorCentavos
                    val date = choice.beforeExpense?.data ?: choice.beforeIncome!!.data
                    Botao("$date · ${Fmt.reais(amount)} · ${choice.beforeExpense?.descricao ?: choice.beforeIncome?.app}", "${CMD}finance:choose:$id")
                })
            }
            is LunaIntentParser.Intent.Income -> if (command.cents == null) Resposta("Qual é o valor exato do recebimento?")
                else propor(income = Ganho(data = command.date.toString(), app = space.neural.luna.data.local.AppCorrida.OUTRO,
                    valorCentavos = command.cents, descricao = texto.take(200)))
            is LunaIntentParser.Intent.Expense -> {
                if (command.cents == null) return Resposta("Qual é o valor exato do gasto? Envie valor e categoria juntos.")
                val category = p.limites.singleOrNull { it.nome == command.category }
                val native = LunaIntentParser.nativeCategory(command.description)
                if (category == null && native != null) return propor(expense = Gasto(
                    data = command.date.toString(), categoria = native,
                    valorCentavos = command.cents, descricao = command.description))
                if (category == null) {
                    missingCategory = command
                    Resposta(if (p.limites.isEmpty()) "Cadastre uma categoria com limite no Planejamento para associar esse gasto."
                        else "Em qual categoria entra ${Fmt.reais(command.cents)}?", p.limites.map {
                        Botao(it.nome, "${CMD}finance:category:${it.id}")
                    })
                } else propor(expense = Gasto(data = command.date.toString(), categoria = CategoriaGasto.OUTRO,
                    valorCentavos = command.cents, limiteId = category.id, descricao = category.nome))
            }
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
        private var owner: String? = null
        fun get(ctx: Context): LunaConversa = synchronized(this) {
            val currentOwner = space.neural.luna.conta.TrocaConta.dono(ctx)
            if (inst == null || owner != currentOwner) {
                inst = LunaConversa(ctx.applicationContext); owner = currentOwner
            }
            inst!!
        }
    }
}
