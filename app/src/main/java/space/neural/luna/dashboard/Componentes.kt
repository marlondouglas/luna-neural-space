package space.neural.luna.dashboard

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import space.neural.luna.R
import space.neural.luna.data.local.CategoriaGasto
import space.neural.luna.data.local.EstadoTurno
import space.neural.luna.data.local.MaquinaTurno
import space.neural.luna.data.local.TipoMeta
import java.time.LocalDate
import java.time.LocalTime

/** O que os cards pedem para a tela fazer. */
interface AcoesPainel {
    fun iniciar(); fun pausar(); fun cafe(); fun retomar(); fun fecharTurno(); fun concluir(); fun corrigir(); fun zerar()
    fun diaEsquecido()
    fun detalheDia(); fun detalheApp(app: String); fun abrirDia(data: String)
    fun lancarGanho(app: String?); fun lancarGasto(categoria: String?)
    fun planejamento(); fun gastoEnvelope(e: Plano.Envelope)
    fun dispensarAlerta(chave: String)
    fun pararAgoraNao()
    fun atualizarNoticias(); fun abrirNoticia(n: Externo.Noticia); fun previsaoHoras()
    fun ler(texto: String); fun compartilhar(texto: String)
    fun conversar(); fun menu(ancora: View); fun sincronizar()
}

// ---------------- Cabeçalho ----------------
class Cabecalho(private val ui: Ui, compacto: Boolean, a: AcoesPainel, estreito: Boolean = false) {
    val view: LinearLayout = ui.hbox().apply { setPadding(0, 0, 0, ui.dp(6)); gravity = Gravity.CENTER_VERTICAL; minimumHeight = ui.dp(52) }
    private val saudacao = ui.ajustavel("", if (compacto) 18f else 22f, 15f, Cores.BRANCO)  // encolhe antes de cortar o nome
    private val data = ui.text("", 13f, Cores.BRANCO_70)
    private val aoVivo = ui.text("● AO VIVO", 11f, Cores.MAGENTA, bold = true).apply {
        setPadding(ui.dp(8), ui.dp(3), ui.dp(8), ui.dp(3))
        background = ui.fundo(Cores.alpha(Cores.MAGENTA, 0.15f), Cores.MAGENTA, 10f)
        visibility = View.GONE
        contentDescription = "Turno ao vivo"
    }
    private var pulso: android.animation.ValueAnimator? = null
    private val syncIc = ui.icone(R.drawable.ic_cloud, Cores.ESMERALDA, 18)
    private val syncTx = ui.text("", 12f, Cores.BRANCO_70)
    private val sync = ui.hbox().apply {
        addView(syncIc); addView(syncTx, ui.wc(ui.dp(4)))
        minimumHeight = ui.dp(48); isClickable = true; isFocusable = true
        setPadding(ui.dp(8), 0, ui.dp(8), 0)
        setOnClickListener { a.sincronizar() }
    }

    init {
        view.addView(ui.botaoIcone(R.drawable.ic_more, "Menu do ROTA GV", Cores.BRANCO) { a.menu(it) })
        val marca = ui.vbox().apply {
            addView(ui.ajustavel("ROTA GV", if (estreito) 22f else 30f, 16f, Cores.CIANO).apply { letterSpacing = 0.06f })
            addView(ui.ajustavel("SEU CAMINHO MAIS INTELIGENTE", 9f, 7f, Cores.BRANCO, bold = false))
        }
        view.addView(marca, ui.peso(if (estreito) 1.4f else 1f, ui.dp(6)))
        ui.truncar(data, 1)
        saudacao.visibility = View.GONE
        if (!estreito) view.addView(data, ui.peso(1f, ui.dp(12)))
        if (estreito) syncTx.visibility = View.GONE
        sync.background = ui.fundo(Cores.alpha(Cores.VIOLETA, 0.1f), Cores.alpha(Cores.VIOLETA, 0.6f), 24f)
        view.addView(aoVivo, ui.wc(ui.dp(4)))
        view.addView(sync, ui.wc(ui.dp(4)))
        view.addView(ui.botaoIcone(R.drawable.ic_settings, "Configurações e perfil", Cores.BRANCO) { a.menu(it) })
    }

    fun bind(p: Painel, online: Boolean, nuvem: Boolean) {
        val h = LocalTime.now().hour
        saudacao.text = (if (h < 12) "Bom dia" else if (h < 18) "Boa tarde" else "Boa noite") + space.neural.luna.conta.Conta.vocativo()
        data.text = Fmt.dataCurta(p.hoje) + "   " + LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        val vivo = p.turno?.estado == EstadoTurno.ATIVO
        aoVivo.visibility = if (vivo) View.VISIBLE else View.GONE
        if (vivo && pulso == null && Anima.ligadas()) {
            pulso = android.animation.ValueAnimator.ofFloat(1f, 0.45f).apply {
                duration = 900; repeatMode = android.animation.ValueAnimator.REVERSE; repeatCount = android.animation.ValueAnimator.INFINITE
                addUpdateListener { aoVivo.alpha = it.animatedValue as Float }; start()
            }
        } else if (!vivo) { pulso?.cancel(); pulso = null; aoVivo.alpha = 1f }
        val (ic, cor, tx) = when {
            !nuvem -> Triple(R.drawable.ic_cloud_off, Cores.BRANCO_70, "Só no aparelho")
            !online -> Triple(R.drawable.ic_cloud_off, Cores.CIANO, "ⓘ Offline")
            p.pendentes > 0 -> Triple(R.drawable.ic_refresh, Cores.CIANO, "Pendente")
            else -> Triple(R.drawable.ic_cloud, Cores.ESMERALDA, "Sincronizado")
        }
        syncIc.setImageResource(ic); syncIc.imageTintList = android.content.res.ColorStateList.valueOf(cor)
        syncTx.text = tx
        sync.contentDescription = "Sincronização: $tx. Toque para sincronizar agora"
    }
}

// ---------------- Turno (componente 4) com a orb 3D da Luna ----------------
class TurnoCard(private val ui: Ui, private val largo: Boolean, private val a: AcoesPainel, orbDp: Int = 200, mostrarRegistroRapido: Boolean = true, private val compacto: Boolean = false) {
    val view = ui.card(destaque = true)
    private val selo = ui.selo("", Cores.CIANO).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val contador = TextoAjustavel(ui.ctx, if (compacto) 36f else 44f, 22f).apply {
        text = "00:00:00"; setTextColor(Cores.BRANCO_40); gravity = Gravity.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        setShadowLayer(18f, 0f, 0f, Cores.alpha(Cores.CIANO, 0.7f))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val parado = ui.text("", 14f, Cores.BRANCO_70).apply { gravity = Gravity.CENTER }
    private val botoes = ui.hbox()
    private var estadoDesenhado: String? = "inicial"
    private var turno: space.neural.luna.data.local.Turno? = null
    private var fase = MaquinaTurno.Fase.PRONTO
    private val titulo = ui.titulo("Turno 1/4")
    private val info = ui.text("", 13f, Cores.BRANCO_70).apply { isClickable = true; isFocusable = true; minHeight = ui.dp(32) }
    private val porHora = ui.text("", 13f, Cores.BRANCO_70)
    private val faixa = ui.vbox()
    private var liqDia = 0L

    init {
        if (compacto) view.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
        val topo = ui.hbox()
        topo.addView(ui.icone(R.drawable.ic_timer, Cores.CIANO, 18))
        topo.addView(titulo, ui.peso(1f, ui.dp(8)))
        topo.addView(selo)
        view.addView(topo, ui.mp())
        val legenda = ui.text("TEMPO DE TRABALHO", 11f, Cores.BRANCO_70).apply { gravity = Gravity.CENTER; letterSpacing = 0.15f }
        if (compacto) {
            val linha = ui.hbox()
            linha.addView(ui.vbox().apply { addView(legenda); addView(contador); addView(parado) }, ui.peso(1f, ui.dp(8)))
            view.addView(linha, ui.mp(ui.dp(4)))
            view.addView(botoes, ui.mp(ui.dp(6)))
        } else if (largo) {
            val linha = ui.hbox()
            val dir = ui.vbox().apply { gravity = Gravity.CENTER }
            dir.addView(legenda, ui.mp()); dir.addView(contador, ui.mp()); dir.addView(parado, ui.mp())
            dir.addView(botoes, ui.mp(ui.dp(12)))
            linha.addView(dir, ui.peso(1f, ui.dp(12)))
            view.addView(linha, ui.mp(ui.dp(4)))
        } else {
            view.addView(legenda, ui.mp()); view.addView(contador, ui.mp()); view.addView(parado, ui.mp())
            view.addView(botoes, ui.mp(ui.dp(12)))
        }
        // v0.1.5: ações que eram do card "Resumo do dia" (removido) ficam aqui no turno
        if (!compacto) view.addView(ui.divisor())
        val acoes = ui.hbox()
        acoes.addView(ui.comIcone(ui.botao("Ganho", Ui.Estilo.CHEIO_ESMERALDA) { a.lancarGanho(null) }.apply { contentDescription = "Lançar ganho" }, R.drawable.ic_add), ui.peso(1f))
        acoes.addView(ui.comIcone(ui.botao("Gasto") { a.lancarGasto(null) }.apply { contentDescription = "Lançar gasto" }, R.drawable.ic_add), ui.peso(1f, ui.dp(8)))
        acoes.addView(ui.comIcone(ui.botao("Recarga") { a.lancarGasto(space.neural.luna.data.local.CategoriaGasto.ENERGIA) }.apply { contentDescription = "Lançar recarga" }, R.drawable.ic_bolt), ui.peso(1f, ui.dp(8)))
        if (mostrarRegistroRapido) view.addView(acoes, ui.mp())
        info.setOnClickListener { a.detalheDia() }
        if (!compacto) view.addView(info, ui.mp(ui.dp(8)))
        view.addView(porHora, ui.mp(ui.dp(2)))
        view.addView(faixa, ui.mp())
    }


    /** Recarga/manutenção do dia, ganho por hora e a sugestão de encerrar (antes no "Resumo do dia"). */
    fun bindResumo(p: Painel, agora: Long, sugerirParar: Boolean) {
        val r = p.resumo
        liqDia = r.liquidoCentavos
        val en = if (r.temRecarga) "⚡ Energia ${Fmt.reais(r.energiaCentavos)}" + (if (r.energiaKwh > 0) " (${Fmt.kwh(r.energiaKwh)} kWh)" else "") else "⚡ Nenhuma recarga hoje"
        info.text = "$en  ·  🔧 Manutenção ${Fmt.reais(r.manutencaoCentavos)}" + if (r.outrosCentavos > 0) "  ·  Outros ${Fmt.reais(r.outrosCentavos)}" else ""
        info.contentDescription = info.text.toString() + ". Toque para ver os lançamentos do dia"
        tickResumo(p, agora)
        faixa.removeAllViews()
        if (sugerirParar) {
            val ph = Calculos.porHoraCentavos(r.liquidoCentavos, p.trabalhoDia(agora)) ?: 0
            val f = ui.vbox(ui.dp(12)).apply { background = ui.fundo(Cores.alpha(Cores.CIANO, 0.10f), Cores.alpha(Cores.CIANO, 0.6f), 14f) }
            f.addView(ui.hbox().apply {
                addView(ui.icone(R.drawable.ic_info, Cores.CIANO, 18))
                addView(ui.text("Seu ganho por hora caiu para ${Fmt.reaisCurto(ph)}/h. Que tal encerrar?", 15f, Cores.CIANO), ui.peso(1f, ui.dp(8)))
            })
            f.addView(ui.hbox().apply {
                addView(ui.botao("Fechar turno", Ui.Estilo.CHEIO_VIOLETA) { a.fecharTurno() }, ui.peso(1f))
                addView(ui.botao("Agora não") { a.pararAgoraNao() }, ui.peso(1f, ui.dp(8)))
            }, ui.mp(ui.dp(8)))
            faixa.addView(f, ui.mp(ui.dp(10)))
        }
    }

    fun tickResumo(p: Painel, agora: Long) {
        val ph = Calculos.porHoraCentavos(liqDia, p.trabalhoDia(agora))
        porHora.text = if (ph != null) "Ganho por hora hoje: ${Fmt.reais(ph)}/h" else "Ganho por hora aparece após 15 min de turno"
    }

    fun bind(sit: MaquinaTurno.Situacao) {
        val t = sit.turno
        turno = t?.takeIf { sit.fase != MaquinaTurno.Fase.CONCLUIDO && sit.fase != MaquinaTurno.Fase.PRONTO || it.estado == EstadoTurno.PRONTO }
        fase = sit.fase
        val chave = "${sit.fase}-${sit.numero}-${sit.anterior?.id}"
        if (chave != estadoDesenhado) {
            estadoDesenhado = chave
            titulo.text = if (sit.fase == MaquinaTurno.Fase.CONCLUIDO) "DIA CONCLUÍDO" else "TURNO ${sit.numero}/${MaquinaTurno.TOTAL}"
            botoes.removeAllViews()
            botoes.orientation = LinearLayout.VERTICAL
            val linhas = List(3) { ui.hbox() }
            linhas.forEachIndexed { i, l -> botoes.addView(l, ui.mp(if (i == 0) 0 else ui.dp(8))) }
            fun princ(txt: String, ic: Int, desc: String = txt, f: () -> Unit) =
                linhas[0].addView(ui.comIcone(ui.botao(txt, Ui.Estilo.CHEIO_VIOLETA, alto = true) { f() }.apply { contentDescription = desc }, ic), ui.peso(1f))
            fun sec(l: Int, txt: String, ic: Int?, desc: String = txt, estilo: Ui.Estilo = Ui.Estilo.CONTORNO, f: () -> Unit) {
                val b = ui.botao(txt, estilo) { f() }.apply { contentDescription = desc }
                if (ic != null) ui.comIcone(b, ic)
                linhas[l].addView(b, ui.peso(1f, if (linhas[l].childCount == 0) 0 else ui.dp(8)))
            }
            val n = sit.numero
            val zerarTxt = "Zerar tempo"
            when (sit.fase) {
                MaquinaTurno.Fase.PRONTO -> {
                    princ("Iniciar turno $n", R.drawable.ic_play) { a.iniciar() }
                    if (sit.anterior != null) sec(1, "Corrigir turno ${sit.anterior.numero}", R.drawable.ic_wrench) { a.corrigir() }
                    else sec(1, "Calendário", R.drawable.ic_calendar, "Calendário: lançar dia esquecido") { a.diaEsquecido() }
                    if (n > 1) {
                        // v0.1.5: logo depois de fechar um turno também dá para voltar/zerar
                        sec(2, zerarTxt, R.drawable.ic_refresh, "Zerar tempo: voltar ao turno ${n - 1} ou zerar o dia") { a.zerar() }
                        sec(2, "Concluir dia", R.drawable.ic_check, estilo = Ui.Estilo.CHEIO_ESMERALDA) { a.concluir() }
                    }
                }
                MaquinaTurno.Fase.ATIVO -> {
                    princ("Pausar", R.drawable.ic_pause) { a.pausar() }
                    sec(1, "Café", R.drawable.ic_coffee) { a.cafe() }
                    sec(1, "Fechar turno", R.drawable.ic_stop, "Fechar turno $n") { a.fecharTurno() }
                    sec(2, zerarTxt, R.drawable.ic_refresh, "Zerar tempo do turno $n") { a.zerar() }
                    sec(2, "Concluir dia", R.drawable.ic_check, estilo = Ui.Estilo.CHEIO_ESMERALDA) { a.concluir() }
                }
                MaquinaTurno.Fase.PAUSADO, MaquinaTurno.Fase.CAFE -> {
                    princ("Retomar", R.drawable.ic_play) { a.retomar() }
                    if (sit.fase == MaquinaTurno.Fase.PAUSADO) sec(1, "Café", R.drawable.ic_coffee) { a.cafe() }
                    sec(1, "Fechar turno", R.drawable.ic_stop, "Fechar turno $n") { a.fecharTurno() }
                    sec(2, zerarTxt, R.drawable.ic_refresh, "Zerar tempo do turno $n") { a.zerar() }
                    sec(2, "Concluir dia", R.drawable.ic_check, estilo = Ui.Estilo.CHEIO_ESMERALDA) { a.concluir() }
                }
                MaquinaTurno.Fase.ULTIMO_FECHADO -> {
                    princ("Retomar turno 4", R.drawable.ic_play, "Retomar: continua no turno 4") { a.retomar() }
                    sec(1, "Concluir dia", R.drawable.ic_check, estilo = Ui.Estilo.CHEIO_ESMERALDA) { a.concluir() }
                    sec(1, "Corrigir", R.drawable.ic_wrench, "Corrigir turno 4") { a.corrigir() }
                }
                MaquinaTurno.Fase.CONCLUIDO -> {
                    sec(1, "Reabrir dia", R.drawable.ic_play, "Reabrir dia: continua no turno ${sit.numero}") { a.retomar() }
                    sec(1, "Calendário", R.drawable.ic_calendar, "Calendário: lançar dia esquecido") { a.diaEsquecido() }
                }
            }
            linhas.filter { it.childCount == 0 }.forEach { botoes.removeView(it) }
            if (botoes.childCount > 0) (botoes.getChildAt(0).layoutParams as LinearLayout.LayoutParams).topMargin = 0
            if (compacto) {
                val actions = linhas.flatMap { line -> (0 until line.childCount).map { line.getChildAt(it) as android.widget.Button } }
                botoes.removeAllViews(); botoes.orientation = LinearLayout.HORIZONTAL
                actions.firstOrNull()?.let { first ->
                    (first.parent as? android.view.ViewGroup)?.removeView(first)
                    first.textSize = 14f; first.minHeight = ui.dp(48); first.minimumHeight = ui.dp(48)
                    botoes.addView(first, ui.peso(1f))
                }
                botoes.addView(ui.botao("Mais ações", Ui.Estilo.CONTORNO) {
                    val others = actions.drop(1)
                    androidx.appcompat.app.AlertDialog.Builder(ui.ctx).setTitle("Turno e ferramentas")
                        .setItems((others.map { it.text.toString() } + "Lançamentos do dia").toTypedArray()) { _, index ->
                            if (index < others.size) others[index].performClick() else a.detalheDia()
                        }.setNegativeButton("Voltar", null).show()
                }.apply { textSize = 13f }, ui.peso(1f, ui.dp(6)))
            }
            val (s, cor) = when (sit.fase) {
                MaquinaTurno.Fase.ATIVO -> "● Trabalhando" to Cores.CIANO
                MaquinaTurno.Fase.PAUSADO -> "⏸ Pausado" to Cores.LARANJA
                MaquinaTurno.Fase.CAFE -> "☕ Café" to Cores.LARANJA
                MaquinaTurno.Fase.ULTIMO_FECHADO -> "■ Turno 4 fechado" to Cores.VIOLETA
                MaquinaTurno.Fase.CONCLUIDO -> "✓ Concluído" to Cores.ESMERALDA
                MaquinaTurno.Fase.PRONTO -> "Pronto para iniciar" to Cores.BRANCO_70
            }
            selo.text = s; selo.setTextColor(cor)
            selo.background = ui.fundo(Cores.alpha(cor, 0.12f), Cores.alpha(cor, 0.6f), 12f)
            view.contentDescription = null
        }
        tick(space.neural.luna.sync.Relogio.agora())
    }

    /** Chamado a cada segundo: só atualiza textos. */
    fun tick(agora: Long) {
        val t = turno
        contador.text = Fmt.hms(Calculos.trabalhoMs(t, agora))
        contador.setTextColor(when (t?.estado) {
            null, EstadoTurno.PRONTO -> Cores.BRANCO_40
            EstadoTurno.ATIVO -> Cores.CIANO
            EstadoTurno.PAUSADO, EstadoTurno.CAFE -> Cores.alpha(Cores.CIANO, 0.6f)
            else -> Cores.BRANCO
        })
        parado.text = when {
            fase == MaquinaTurno.Fase.CONCLUIDO -> "Bom descanso${space.neural.luna.conta.Conta.vocativo()}. 💜"
            t == null || t.estado == EstadoTurno.PRONTO -> "Pronto para iniciar"
            else -> "Parado: ${Fmt.minutos(Calculos.paradoMs(t, agora))} min"
        }
    }
}

// ---------------- Resumo do dia (componente 1) ----------------
class ResumoCard(private val ui: Ui, private val a: AcoesPainel) {
    val view = ui.card()
    private val bruto = ui.ajustavel("R$ 0,00", 26f, 14f)
    private val liquido = ui.ajustavel("R$ 0,00", 26f, 14f)
    private val energia = ui.text("", 15f, Cores.CIANO)
    private val energiaOrigem = ui.text("", 12f, Cores.BRANCO_70)
    private val manut = ui.text("", 15f, Cores.BRANCO_70)
    private val porHora = ui.text("", 14f, Cores.BRANCO_70)
    private val vazio = ui.vbox()
    private val faixa = ui.vbox()
    private var ultBruto = 0L; private var ultLiq = 0L
    private var temGanho = false; private var liqAtual = 0L

    init {
        view.isClickable = true; view.isFocusable = true
        view.setOnClickListener { a.detalheDia() }
        view.addView(ui.tituloIcone(R.drawable.ic_wallet, "Resumo do dia"))
        val cols = ui.hbox().apply { gravity = Gravity.TOP }
        cols.addView(ui.vbox().apply { addView(ui.text("Bruto", 14f, Cores.BRANCO_70)); addView(bruto) }, ui.peso(1f))
        cols.addView(View(ui.ctx).apply { setBackgroundColor(Cores.alpha(Cores.BRANCO, 0.12f)) }, LinearLayout.LayoutParams(ui.dp(1), ui.dp(48)).apply { leftMargin = ui.dp(8); rightMargin = ui.dp(12) })
        cols.addView(ui.vbox().apply { addView(ui.text("Líquido", 14f, Cores.BRANCO_70)); addView(liquido) }, ui.peso(1f))
        view.addView(cols, ui.mp(ui.dp(10)))
        val linhaEn = ui.hbox().apply {
            minimumHeight = ui.dp(48); isClickable = true; isFocusable = true
            setOnClickListener { a.lancarGasto(CategoriaGasto.ENERGIA) }
            addView(ui.icone(R.drawable.ic_bolt, Cores.CIANO, 18))
            addView(ui.vbox().apply { addView(energia); addView(energiaOrigem) }, ui.peso(1f, ui.dp(6)))
        }
        view.addView(linhaEn, ui.mp(ui.dp(6)))
        val linhaMan = ui.hbox()
        linhaMan.addView(ui.icone(R.drawable.ic_wrench, Cores.BRANCO_70, 18))
        linhaMan.addView(manut, ui.peso(1f, ui.dp(6)))
        linhaMan.addView(ui.comIcone(ui.botao("Gasto") { a.lancarGasto(null) }.apply { contentDescription = "Lançar gasto" }, R.drawable.ic_add))
        view.addView(linhaMan, ui.mp())
        view.addView(porHora, ui.mp(ui.dp(4)))
        view.addView(vazio, ui.mp())
        view.addView(faixa, ui.mp())
    }

    fun bind(p: Painel, agora: Long, sugerirParar: Boolean) {
        val r = p.resumo
        Anima.contar(bruto, ultBruto, r.brutoCentavos, Fmt::reais); ultBruto = r.brutoCentavos
        Anima.contar(liquido, ultLiq, r.liquidoCentavos, Fmt::reais); ultLiq = r.liquidoCentavos
        liquido.setTextColor(if (r.liquidoCentavos > 0) Cores.ESMERALDA else Cores.BRANCO)
        bruto.contentDescription = "Bruto: ${Fmt.reais(r.brutoCentavos)}"
        liquido.contentDescription = "Líquido: ${Fmt.reais(r.liquidoCentavos)}"
        if (r.temRecarga) {
            energia.text = "Energia ${Fmt.reais(r.energiaCentavos)}"
            energiaOrigem.text = buildList {
                if (r.energiaInformadaCentavos > 0) add("informado")
                if (r.energiaKwh > 0) add("calculado: ${Fmt.kwh(r.energiaKwh)} kWh")
            }.joinToString(" · ")
        } else {
            energia.text = "Nenhuma recarga lançada"; energiaOrigem.text = "Lançar recarga"
        }
        manut.text = "Manutenção ${Fmt.reais(r.manutencaoCentavos)}" + if (r.outrosCentavos > 0) " · Outros ${Fmt.reais(r.outrosCentavos)}" else ""
        temGanho = r.temGanho; liqAtual = r.liquidoCentavos
        tick(p, agora)
        vazio.removeAllViews()
        if (!r.temGanho) {
            vazio.addView(ui.text("Nenhuma corrida hoje ainda. Inicie o turno ou lance um ganho.", 14f, Cores.BRANCO_70), ui.mp(ui.dp(8)))
            vazio.addView(ui.comIcone(ui.botao("Lançar ganho", Ui.Estilo.CHEIO_ESMERALDA) { a.lancarGanho(null) }, R.drawable.ic_add), ui.wc())
        }
        faixa.removeAllViews()
        if (sugerirParar) {
            val ph = Calculos.porHoraCentavos(r.liquidoCentavos, p.trabalhoDia(agora)) ?: 0
            val f = ui.vbox(ui.dp(12)).apply { background = ui.fundo(Cores.alpha(Cores.CIANO, 0.10f), Cores.alpha(Cores.CIANO, 0.6f), 14f) }
            f.addView(ui.hbox().apply {
                addView(ui.icone(R.drawable.ic_info, Cores.CIANO, 18))
                addView(ui.text("Seu ganho por hora caiu para ${Fmt.reaisCurto(ph)}/h. Que tal encerrar?", 15f, Cores.CIANO), ui.peso(1f, ui.dp(8)))
            })
            f.addView(ui.hbox().apply {
                addView(ui.botao("Fechar turno", Ui.Estilo.CHEIO_VIOLETA) { a.fecharTurno() }, ui.peso(1f))
                addView(ui.botao("Agora não") { a.pararAgoraNao() }, ui.peso(1f, ui.dp(8)))
            }, ui.mp(ui.dp(8)))
            faixa.addView(f, ui.mp(ui.dp(10)))
        }
    }

    private var painelAtual: Painel? = null
    fun tick(p: Painel, agora: Long) {
        painelAtual = p
        val ph = Calculos.porHoraCentavos(liqAtual, p.trabalhoDia(agora))
        porHora.text = if (ph != null) "Ganho por hora: ${Fmt.reais(ph)}/h" else "Ganho por hora aparece após 15 min de turno"
    }
}

// ---------------- Metas (componente 2) ----------------
class MetasCard(private val ui: Ui, private val a: AcoesPainel, private val compacto: Boolean = false) {
    val view = ui.card(acento = Cores.ESMERALDA)
    private val linha = ui.hbox().apply { gravity = Gravity.TOP }
    private val alerta = ui.vbox()
    private val aneis = mutableMapOf<String, MetaRing>()
    private val textos = mutableMapOf<String, TextView>()
    private val textos2 = mutableMapOf<String, TextView>()
    private val colunas = mutableMapOf<String, LinearLayout>()
    private val aviso = ui.botaoIcone(R.drawable.ic_info, "Aviso da meta", Cores.ESMERALDA) {}.apply { visibility = View.GONE }

    init {
        val topo = ui.hbox()
        topo.addView(ui.tituloIcone(R.drawable.ic_flag, "Metas", Cores.ESMERALDA), ui.peso(1f))
        if (compacto) { view.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12)); topo.addView(aviso) }
        topo.addView(ui.botao("Planejamento", Ui.Estilo.TEXTO) { a.planejamento() }.apply {
            contentDescription = "Abrir o planejamento, onde as metas são calculadas"
        })
        view.addView(topo, ui.mp())
        view.addView(linha, ui.mp(ui.dp(6)))
        view.addView(alerta, ui.mp())
        listOf(TipoMeta.DIA to "Dia", TipoMeta.SEMANA to "Semana", TipoMeta.MES to "Mês").forEachIndexed { i, (tipo, nome) ->
            val col = ui.vbox(ui.dp(8)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; isFocusable = true
                minimumHeight = ui.dp(48)
                background = RotaDesign.panel(ui, if (i == 0) Cores.CIANO else if (i == 1) Cores.ESMERALDA else Cores.MAGENTA)
                setOnClickListener { a.planejamento() }
            }
            col.addView(ui.text(nome, 14f, Cores.BRANCO, bold = true))
            val ring = MetaRing(ui.ctx, if (compacto) 56 else 88); aneis[tipo] = ring
            col.addView(ring, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(4) })
            val t = ui.ajustavel("", 13f, 9f, Cores.BRANCO_70, bold = false).apply { gravity = Gravity.CENTER }; textos[tipo] = t
            col.addView(t, ui.mp(ui.dp(4)))
            val t2 = ui.ajustavel("", 13f, 9f, Cores.BRANCO_70, bold = false).apply { gravity = Gravity.CENTER }; textos2[tipo] = t2
            col.addView(t2, ui.mp())
            colunas[tipo] = col
            linha.addView(col, ui.peso(1f, if (i == 0) 0 else ui.dp(6)))
        }
    }

    /** [alertaAtual]: (chave, texto, limiar) do alerta motivacional a mostrar, ou null. */
    fun bind(p: Painel, alertaAtual: Triple<String, String, Int>?) {
        p.metas.forEach { m ->
            val ring = aneis[m.tipo]!!; val t = textos[m.tipo]!!; val t2 = textos2[m.tipo]!!; val col = colunas[m.tipo]!!
            if (m.metaCentavos == null) {
                ring.visibility = View.GONE
                t.text = "Sem meta"; t.setTextColor(Cores.BRANCO_70)
                t2.text = "vem do Planejamento"; t2.setTextColor(Cores.BRANCO_40)
                col.contentDescription = "Meta ${nome(m.tipo)} não definida. Ela é calculada no Planejamento"
            } else {
                ring.visibility = View.VISIBLE; ring.definir(m.percentual)
                t.setTextColor(Cores.BRANCO_70); t2.setTextColor(if (m.percentual >= 100) Cores.ESMERALDA else Cores.BRANCO_70)
                t.text = "${Fmt.reaisCurto(m.liquidoCentavos.coerceAtLeast(0))} / ${Fmt.reaisCurto(m.metaCentavos)}"
                t2.text = if (m.percentual >= 100) "+${Fmt.reaisCurto(m.acimaCentavos)} acima" else "faltam ${Fmt.reaisCurto(m.faltaCentavos)}"
                col.contentDescription = "Meta ${nome(m.tipo)}: ${m.percentual} por cento. " +
                    (if (m.percentual >= 100) "Batida, ${Fmt.reais(m.acimaCentavos)} acima." else "Faltam ${Fmt.reais(m.faltaCentavos)}.") +
                    " Bruto ${Fmt.reais(m.brutoCentavos)}."
            }
        }
        alerta.removeAllViews()
        aviso.visibility = if (compacto && alertaAtual != null) View.VISIBLE else View.GONE
        if (compacto && alertaAtual != null) {
            val (chave, texto, _) = alertaAtual
            aviso.contentDescription = "$texto. Abrir aviso da meta"
            aviso.setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(ui.ctx).setTitle("Progresso da meta").setMessage(texto)
                    .setPositiveButton("Dispensar") { _, _ -> a.dispensarAlerta(chave) }.setNegativeButton("Voltar", null).show()
            }
            return
        }
        if (alertaAtual != null) {
            val (chave, texto, lim) = alertaAtual
            val c = ui.vbox(ui.dp(12)).apply {
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Cores.alpha(Cores.VIOLETA, 0.28f), Cores.alpha(Cores.ESMERALDA, 0.18f)),
                ).apply { cornerRadius = ui.dpf(14f); setStroke(ui.dp(1), Cores.VIOLETA) }
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            c.addView(ui.text(texto, 16f, if (lim >= 100) Cores.ESMERALDA else Cores.BRANCO, bold = true))
            c.addView(ui.botao("Dispensar", Ui.Estilo.TEXTO) { a.dispensarAlerta(chave) }, ui.wc())
            alerta.addView(c, ui.mp(ui.dp(10)))
            if (Anima.ligadas()) c.animate().scaleX(1.02f).scaleY(1.02f).setDuration(260).withEndAction { c.animate().scaleX(1f).scaleY(1f).setDuration(260).start() }.start()
        }
    }

    private fun nome(t: String) = when (t) { TipoMeta.SEMANA -> "da semana"; TipoMeta.MES -> "do mês"; else -> "do dia" }
}

// ---------------- Dica do dia + versículo (componente 6) ----------------
class DicaCard(private val ui: Ui, private val a: AcoesPainel) {
    val view = ui.card()
    private val dica = ui.text("", 15f, Cores.BRANCO)
    private val vers = ui.text("", 16f, Cores.BRANCO).apply { setTypeface(typeface, android.graphics.Typeface.ITALIC) }
    private val ref = ui.text("", 13f, Cores.BRANCO_70)
    private var dia: LocalDate? = null

    init {
        val l1 = ui.hbox()
        l1.addView(ui.tituloIcone(R.drawable.ic_bulb, "Dica do dia"), ui.peso(1f))
        l1.addView(ui.botaoIcone(R.drawable.ic_volume, "Ouvir a dica", Cores.VIOLETA) { a.ler(dica.text.toString()) })
        view.addView(l1, ui.mp()); view.addView(dica, ui.mp(ui.dp(2)))
        view.addView(ui.divisor())
        view.addView(vers, ui.mp())
        val l2 = ui.hbox()
        l2.addView(ref, ui.peso(1f))
        l2.addView(ui.botaoIcone(R.drawable.ic_volume, "Ouvir o versículo", Cores.VIOLETA) { a.ler("${vers.text}. ${ref.text}") })
        l2.addView(ui.botaoIcone(R.drawable.ic_share, "Compartilhar o versículo", Cores.VIOLETA) { a.compartilhar("${vers.text} — ${ref.text}") })
        view.addView(l2, ui.mp(ui.dp(4)))
    }

    fun bind(hoje: LocalDate) {
        if (hoje == dia) return
        dia = hoje
        dica.text = Conteudo.dica(hoje)
        val v = Conteudo.versiculo(hoje)
        vers.text = "“${v.texto}”"; ref.text = v.referencia
    }
}

// ---------------- KPIs do período ----------------
class KpiStrip(private val ui: Ui, colunas: Int) {
    private var compararSemana = false
    val view: LinearLayout = ui.vbox()
    private inner class Kpi(val titulo: String, icone: Int, val cor: Int) {
        val card = ui.card(acento = cor, colorido = true).apply { setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8)) }
        val valor = ui.ajustavel("—", 24f, 13f)
        val sub = ui.ajustavel("", 12f, 9f, Cores.BRANCO_70, bold = false)
        val pil = android.widget.FrameLayout(ui.ctx).apply { minimumHeight = ui.dp(24) }
        var ult = 0L
        init {
            card.addView(ui.hbox().apply {
                addView(ui.icone(icone, cor, 16))
                addView(ui.ajustavel(titulo.uppercase(), 11f, 8f, Cores.BRANCO_70).apply { letterSpacing = 0.08f }, ui.peso(1f, ui.dp(6)))
            })
            card.addView(valor, ui.mp(ui.dp(6)))
            card.addView(pil, ui.mp(ui.dp(4)))
            card.addView(sub, ui.mp(ui.dp(4)))
        }
    }
    private val liq = Kpi("Total líquido", R.drawable.ic_wallet, Cores.ESMERALDA)
    private val corr = Kpi("Corridas", R.drawable.ic_car, Cores.CIANO)
    private val hora = Kpi("Ganho/hora", R.drawable.ic_timer, Cores.MAGENTA)
    /** v0.1.5: no lugar do KPI "Meta", o resumo dos limites de gasto (toque abre os limites). */
    private val lim = Kpi("Limites", R.drawable.ic_wallet, Cores.LARANJA)
    var aoTocarLimites: () -> Unit = {}

    init {
        lim.card.isClickable = true; lim.card.isFocusable = true
        lim.card.setOnClickListener { aoTocarLimites() }
        val todos = listOf(liq, corr, hora, lim)
        if (colunas >= 4) {
            view.addView(ui.hbox().apply { gravity = Gravity.TOP; todos.forEachIndexed { i, k -> addView(k.card, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = if (i == 0) 0 else ui.dp(12) }) } }, ui.mp())
        } else {
            todos.chunked(2).forEachIndexed { r, par ->
                view.addView(ui.hbox().apply { gravity = Gravity.TOP; par.forEachIndexed { i, k -> addView(k.card, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = if (i == 0) 0 else ui.dp(12) }) } }, ui.mp(if (r == 0) 0 else ui.dp(12)))
            }
        }
    }

    private fun pilula(k: Kpi, v: Double?, suf: String) {
        k.pil.removeAllViews()
        k.pil.addView(if (compararSemana) ui.text("Dias fechados: ver relatório", 11f, Cores.BRANCO_70) else ui.pilula(v, suf), android.widget.FrameLayout.LayoutParams(-2, -2))
    }

    fun bind(e: Estatisticas) {
        compararSemana = e.periodo == Periodo.SEMANA
        val suf = e.periodo.comparacao
        Anima.contar(liq.valor, liq.ult, e.liquidoCentavos, Fmt::reais); liq.ult = e.liquidoCentavos
        liq.valor.setTextColor(if (e.liquidoCentavos >= 0) Cores.ESMERALDA else Cores.CORAL)
        pilula(liq, e.variacao(e.liquidoCentavos, e.liquidoAnterior), suf)
        liq.sub.text = "Bruto ${Fmt.reais(e.brutoCentavos)}"
        liq.card.contentDescription = "Total líquido ${e.periodo.rotulo}: ${Fmt.reais(e.liquidoCentavos)}"

        Anima.contar(corr.valor, corr.ult, e.corridas.toLong(), { it.toString() }); corr.ult = e.corridas.toLong()
        pilula(corr, e.variacao(e.corridas.toLong(), e.corridasAnterior.toLong()), suf)
        corr.sub.text = "Média ${if (e.corridas > 0) Fmt.reais(e.brutoCentavos / e.corridas) else "R$ 0,00"} por corrida"
        corr.card.contentDescription = "Corridas: ${e.corridas}"

        val ph = e.porHora
        if (ph != null) { Anima.contar(hora.valor, hora.ult, ph, { Fmt.reais(it) + "/h" }); hora.ult = ph } else hora.valor.text = "—"
        if (ph != null) pilula(hora, e.porHoraAnterior?.let { e.variacao(ph, it) }, suf) else hora.pil.removeAllViews()
        hora.sub.text = "Trabalho ${Fmt.hms(e.trabalhoMs).substring(0, 5)} h"
        hora.card.contentDescription = "Ganho por hora: " + (ph?.let { Fmt.reais(it) } ?: "sem dados")

    }

    /** Total dos limites do mês: gasto / restante; vermelho se algum passou. */
    fun bindLimites(envs: List<Plano.Envelope>) {
        lim.pil.removeAllViews()
        if (envs.isEmpty()) {
            lim.valor.text = "—"; lim.valor.setTextColor(Cores.BRANCO); lim.sub.text = "Crie limites no Planejamento"
            lim.card.contentDescription = "Limites de gasto: nenhum. Toque para criar"
            return
        }
        val r = ResumoLimites.de(envs)
        val cor = when { r.excedidos > 0 -> Cores.CORAL; r.fracao >= 0.8f -> Cores.LARANJA; else -> Cores.ESMERALDA }
        lim.valor.text = Fmt.reaisCurto(r.restanteCentavos); lim.valor.setTextColor(cor)
        lim.sub.text = "Gasto ${Fmt.reaisCurto(r.gastoCentavos)} de ${Fmt.reaisCurto(r.limiteCentavos)}"
        lim.pil.addView(ui.text(if (r.excedidos > 0) "⚠ ${r.excedidos} PASSOU" + (if (r.excedidos > 1) "RAM" else "") else "restante no mês", 11f, cor, bold = true).apply {
            setPadding(ui.dp(8), ui.dp(3), ui.dp(8), ui.dp(3))
            background = ui.fundo(Cores.alpha(cor, 0.12f), Cores.alpha(cor, 0.5f), 10f)
        }, android.widget.FrameLayout.LayoutParams(-2, -2))
        lim.card.contentDescription = "Limites de gasto: restam ${Fmt.reais(r.restanteCentavos)} de ${Fmt.reais(r.limiteCentavos)}." +
            (if (r.excedidos > 0) " ${r.excedidos} limite passou do valor." else "") + " Toque para ver os limites"
    }
}

/** Soma dos envelopes (limites) do mês para o KPI. */
data class ResumoLimites(val limiteCentavos: Long, val gastoCentavos: Long, val restanteCentavos: Long, val excedidos: Int) {
    val fracao get() = if (limiteCentavos <= 0) 0f else gastoCentavos.toFloat() / limiteCentavos
    companion object {
        fun de(envs: List<Plano.Envelope>) = ResumoLimites(
            envs.sumOf { it.limite.limiteCentavos }, envs.sumOf { it.gastoCentavos },
            envs.sumOf { it.restanteCentavos }, envs.count { it.excedido },
        )
    }

}

/** Seletor Hoje / Semana / Mês / Ano. */
class SeletorPeriodo(private val ui: Ui, private val aoMudar: (Periodo) -> Unit) {
    val view: LinearLayout = ui.hbox().apply {
        setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
        background = ui.fundo(Cores.alpha(Cores.VIOLETA, 0.10f), Cores.alpha(Cores.VIOLETA, 0.4f), 26f)
    }
    private val bts = Periodo.entries.associateWith { p ->
        ui.botao(p.rotulo, Ui.Estilo.TEXTO) { aoMudar(p) }.also { view.addView(it, ui.peso(1f)) }
    }
    fun bind(sel: Periodo) {
        bts.forEach { (p, b) ->
            val on = p == sel
            b.background = if (on) android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Cores.VIOLETA, Cores.mix(Cores.VIOLETA, Cores.MAGENTA, 0.5f)),
            ).apply { cornerRadius = ui.dpf(22f) } else null
            b.setTextColor(if (on) Cores.BRANCO else Cores.BRANCO_70)
            b.contentDescription = "Período: ${p.rotulo}" + if (on) ", selecionado" else ""
        }
    }
}

/** Líquido do período (área) + bruto por app (barras 3D). */
class GraficoCard(private val ui: Ui, alturaDp: Int = 190, compacto: Boolean = false) {
    val view = ui.card(acento = Cores.CIANO)
    private val titulo = ui.titulo("Líquido")
    private val total = ui.ajustavel("R$ 0,00", 26f, 16f)
    private val pil = android.widget.FrameLayout(ui.ctx)
    private val area = AreaGlow(ui.ctx)
    private val barras = Barras3D(ui.ctx)
    private var ult = 0L

    init {
        view.addView(ui.hbox().apply {
            addView(ui.icone(R.drawable.ic_trending, Cores.CIANO, 18)); addView(titulo, ui.peso(1f, ui.dp(8)))
            addView(pil)
        })
        view.addView(total, ui.mp(ui.dp(4)))
        area.alturaDp = alturaDp
        view.addView(area, ui.mp(ui.dp(8)))
        if (!compacto) {
        view.addView(ui.divisor())
        view.addView(ui.text("BRUTO POR APP", 11f, Cores.BRANCO_70, bold = true).apply { letterSpacing = 0.1f })
        view.addView(barras, ui.mp(ui.dp(6)))
        Calculos.APPS.chunked(3).forEach { grupo ->
            val leg = ui.hbox()
            grupo.forEach { app ->
                leg.addView(View(ui.ctx).apply { background = ui.fundo(CoresSerie.app(app), raio = 3f) }, LinearLayout.LayoutParams(ui.dp(10), ui.dp(10)).apply { leftMargin = ui.dp(10) })
                leg.addView(ui.text(Fmt.nomeApp(app), 12f, Cores.BRANCO_70), ui.wc(ui.dp(4)))
            }
            view.addView(leg, ui.mp(ui.dp(2)))
        }
        }
    }

    fun bind(e: Estatisticas) {
        titulo.text = "LÍQUIDO — ${when (e.periodo) { Periodo.HOJE -> "ÚLTIMOS 7 DIAS"; Periodo.SEMANA -> "ESTA SEMANA"; Periodo.MES -> "ESTE MÊS"; Periodo.ANO -> "ESTE ANO" }}"
        Anima.contar(total, ult, e.liquidoCentavos, Fmt::reais); ult = e.liquidoCentavos
        total.setTextColor(if (e.liquidoCentavos >= 0) Cores.BRANCO else Cores.CORAL)
        pil.removeAllViews(); pil.addView(if (e.periodo == Periodo.SEMANA) ui.text("Comparação no relatório", 11f, Cores.BRANCO_70) else ui.pilula(e.variacao(e.liquidoCentavos, e.liquidoAnterior), e.periodo.comparacao))
        area.definir(e.serie, "Gráfico de líquido: " + e.serie.joinToString("; ") { "${it.rotulo} ${Fmt.reais(it.liquidoCentavos)}" })
        barras.definir(e.serie, "Bruto por app: " + e.serie.joinToString("; ") { "${it.rotulo} ${Fmt.reais(it.porApp.values.sum())}" })
    }
}

/** Roscas 3D: ganhos por app e gastos por categoria, com legenda em %. */
class RoscasCard(private val ui: Ui) {
    val view = ui.card(acento = Cores.MAGENTA)
    private val dGanhos = Donut3D(ui.ctx)
    private val dGastos = Donut3D(ui.ctx)
    private val legG = ui.vbox(); private val legD = ui.vbox()

    init {
        view.addView(ui.hbox().apply {
            addView(ui.icone(R.drawable.ic_wallet, Cores.MAGENTA, 18)); addView(ui.titulo("Divisão do período"), ui.wc(ui.dp(8)))
        })
        val l = ui.hbox().apply { gravity = Gravity.TOP }
        l.addView(ui.vbox().apply { gravity = Gravity.CENTER_HORIZONTAL; addView(ui.text("Ganhos por app", 12f, Cores.BRANCO_70, bold = true)); addView(dGanhos, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) }); addView(legG, ui.mp(ui.dp(4))) }, ui.peso(1f))
        l.addView(ui.vbox().apply { gravity = Gravity.CENTER_HORIZONTAL; addView(ui.text("Gastos", 12f, Cores.BRANCO_70, bold = true)); addView(dGastos, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) }); addView(legD, ui.mp(ui.dp(4))) }, ui.peso(1f, ui.dp(12)))
        view.addView(l, ui.mp(ui.dp(10)))
    }

    private fun legenda(box: LinearLayout, itens: List<Triple<String, Long, Int>>) {
        box.removeAllViews()
        val tot = itens.sumOf { it.second }
        itens.forEach { (n, v, cor) ->
            box.addView(ui.hbox().apply {
                addView(View(ui.ctx).apply { background = ui.fundo(cor, raio = 3f) }, LinearLayout.LayoutParams(ui.dp(10), ui.dp(10)))
                addView(ui.ajustavel(n, 12f, 9f, Cores.BRANCO, bold = false), ui.peso(1f, ui.dp(6)))
                addView(ui.text(if (tot > 0) "${v * 100 / tot}%" else "0%", 12f, cor, bold = true))
            }, ui.mp(ui.dp(4)))
        }
    }

    fun bind(e: Estatisticas) {
        val g = e.porApp.map { Triple(Fmt.nomeApp(it.app), it.centavos, CoresSerie.app(it.app)) }
        dGanhos.definir(g.map { Donut3D.Fatia(it.first, it.second, it.third) }, "BRUTO", Fmt.reaisCurto(e.brutoCentavos),
            "Ganhos por app: " + g.joinToString("; ") { "${it.first} ${Fmt.reais(it.second)}" })
        legenda(legG, g)
        val d = e.gastosPorCategoria.filter { it.second > 0 }.map { Triple(when (it.first) { CategoriaGasto.ENERGIA -> "Energia"; CategoriaGasto.MANUTENCAO -> "Manutenção"; else -> "Outro" }, it.second, CoresSerie.categoria(it.first)) }
        dGastos.definir(d.map { Donut3D.Fatia(it.first, it.second, it.third) }, "GASTOS", Fmt.reaisCurto(e.gastosCentavos),
            "Gastos: " + d.joinToString("; ") { "${it.first} ${Fmt.reais(it.second)}" })
        legenda(legD, d)
    }
}

/** Ranking dos apps (componente 3): barras horizontais 3D, valor, corridas e "+" para lançar. */
class RankingCard(private val ui: Ui, private val a: AcoesPainel) {
    val view = ui.card(acento = Cores.LARANJA)
    private val titulo = ui.titulo("Ranking dos apps")
    private val linhas = ui.vbox()
    private inner class Linha(val app: String) {
        val box = ui.vbox().apply { isClickable = true; isFocusable = true; minimumHeight = ui.dp(48); setOnClickListener { a.detalheApp(app) } }
        val pos = ui.text("", 13f, Cores.BRANCO_70, bold = true)
        val valor = ui.ajustavel("R$ 0,00", 16f, 11f)
        val info = ui.ajustavel("", 12f, 9f, Cores.BRANCO_70, bold = false)
        val barra = BarraH3D(ui.ctx)
        var ult = 0L
        init {
            box.addView(ui.hbox().apply {
                addView(pos, LinearLayout.LayoutParams(ui.dp(24), -2))
                addView(ui.seloApp(app, 24))
                addView(ui.ajustavel(Fmt.nomeApp(app), 15f, 10f, CoresSerie.app(app)), LinearLayout.LayoutParams(ui.dp(72), -2).apply { leftMargin = ui.dp(6) })
                addView(valor, ui.peso(1f, ui.dp(4)))
                addView(info, ui.wc(ui.dp(6)))
                addView(ui.botaoIcone(R.drawable.ic_add, "Lançar ganho no ${Fmt.nomeApp(app)}", Cores.BRANCO) { a.lancarGanho(app) })
            })
            box.addView(barra, ui.mp(ui.dp(2)))
        }
    }
    private val mapa = Calculos.APPS.associateWith { Linha(it) }

    init {
        view.addView(ui.hbox().apply { addView(ui.icone(R.drawable.ic_car, Cores.LARANJA, 18)); addView(titulo, ui.peso(1f, ui.dp(8))) })
        view.addView(linhas, ui.mp(ui.dp(8)))
    }

    fun bind(e: Estatisticas) {
        titulo.text = "RANKING DOS APPS — ${e.periodo.rotulo.uppercase()}"
        val ordem = e.porApp.sortedByDescending { it.centavos }
        val max = ordem.firstOrNull()?.centavos?.coerceAtLeast(1) ?: 1
        linhas.removeAllViews()
        ordem.forEachIndexed { i, x ->
            val l = mapa.getValue(x.app)
            l.pos.text = "#${i + 1}"; l.pos.setTextColor(if (i == 0 && x.centavos > 0) Cores.LARANJA else Cores.BRANCO_70)
            Anima.contar(l.valor, l.ult, x.centavos, Fmt::reais); l.ult = x.centavos
            l.valor.setTextColor(if (x.centavos > 0) Cores.BRANCO else Cores.BRANCO_70)
            l.info.text = "${x.corridas} corr. · ${x.participacao}%"
            l.barra.definir(x.centavos.toFloat() / max, CoresSerie.app(x.app))
            l.box.contentDescription = "${i + 1}º ${Fmt.nomeApp(x.app)}: ${Fmt.reais(x.centavos)}, ${x.corridas} corridas, ${x.participacao} por cento. Toque para ver as corridas"
            linhas.addView(l.box, ui.mp(if (i == 0) 0 else ui.dp(8)))
        }
    }
}

/** Tabela "Últimos lançamentos" do período. */
class LancamentosCard(private val ui: Ui, private val a: AcoesPainel, private val compacto: Boolean = false) {
    val view = ui.card()
    private val tabela = ui.vbox()

    init {
        val topo = ui.hbox()
        topo.addView(ui.icone(R.drawable.ic_news, Cores.VIOLETA, 18))
        topo.addView(ui.titulo("Últimos lançamentos"), ui.peso(1f, ui.dp(8)))
        topo.addView(ui.botao("Ver todos", Ui.Estilo.TEXTO) { a.detalheDia() })
        view.addView(topo, ui.mp())
        view.addView(tabela, ui.mp(ui.dp(4)))
    }

    fun bind(e: Estatisticas) {
        tabela.removeAllViews()
        val cab = ui.hbox()
        listOf("DATA" to 0.8f, "TIPO" to 0.9f, "ORIGEM" to 1.3f, "VALOR" to 1.2f).forEach { (t, w) ->
            cab.addView(ui.text(t, 10f, Cores.BRANCO_70, bold = true).apply { letterSpacing = 0.1f; if (t == "VALOR") gravity = Gravity.END }, ui.peso(w))
        }
        tabela.addView(cab, ui.mp(ui.dp(4)))
        if (e.ultimos.isEmpty()) {
            tabela.addView(ui.text("Nenhum lançamento neste período. Use o + no ranking ou o botão Gasto.", 14f, Cores.BRANCO_70), ui.mp(ui.dp(10)))
            return
        }
        e.ultimos.take(if (compacto) 2 else 8).forEach { l ->
            tabela.addView(ui.divisor().apply { (layoutParams as LinearLayout.LayoutParams).setMargins(0, ui.dp(6), 0, ui.dp(6)) })
            val linha = ui.hbox().apply {
                isClickable = true; isFocusable = true; minimumHeight = ui.dp(48)
                background = ui.fundo(Cores.ELEVADA, raio = 10f)
                setOnClickListener { a.abrirDia(l.data) }
                contentDescription = "${if (l.ganho) "Ganho" else "Gasto"} ${l.nome}, ${Fmt.reais(Math.abs(l.centavos))}, dia ${l.data}. Toque para editar"
            }
            val dt = LocalDate.parse(l.data)
            linha.addView(ui.text("%02d/%02d".format(dt.dayOfMonth, dt.monthValue), 13f, Cores.BRANCO_70), ui.peso(0.8f))
            linha.addView(ui.text(if (l.ganho) "▲ Ganho" else "▼ Gasto", 12f, if (l.ganho) Cores.ESMERALDA else Cores.CORAL, bold = true), ui.peso(0.9f))
            linha.addView(ui.ajustavel(l.nome, 13f, 9f, Cores.BRANCO, bold = false), ui.peso(1.3f))
            linha.addView(ui.ajustavel((if (l.ganho) "+" else "−") + Fmt.reais(Math.abs(l.centavos)), 13f, 9f, if (l.ganho) Cores.ESMERALDA else Cores.CORAL).apply { gravity = Gravity.END }, ui.peso(1.2f))
            tabela.addView(linha, ui.mp())
        }
    }
}

/** Envelopes do mês (contas com limite): gasto / limite, quanto ainda pode gastar, vermelho quando estoura. */
class LimitesCard(private val ui: Ui, private val a: AcoesPainel) {
    val view = ui.card(acento = Cores.LARANJA)
    private val lista = ui.vbox()

    init {
        val topo = ui.hbox()
        topo.addView(ui.icone(R.drawable.ic_wallet, Cores.LARANJA, 18))
        topo.addView(ui.titulo("Limites do mês"), ui.peso(1f, ui.dp(8)))
        topo.addView(ui.botao("Planejamento", Ui.Estilo.TEXTO) { a.planejamento() })
        view.addView(topo, ui.mp())
        view.addView(lista, ui.mp(ui.dp(4)))
    }

    private var idx = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var envsCache: List<space.neural.luna.dashboard.Plano.Envelope> = emptyList()
    private val rotacionar = object : Runnable {
        override fun run() {
            if (envsCache.size > 1 && view.isAttachedToWindow) {
                idx = (idx + 1) % envsCache.size
                desenharUm(envsCache[idx])
                handler.postDelayed(this, 5000)
            }
        }
    }

    init {
        view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) { agendarRotacao() }
            override fun onViewDetachedFromWindow(v: android.view.View) { handler.removeCallbacks(rotacionar) }
        })
    }

    private fun agendarRotacao() {
        handler.removeCallbacks(rotacionar)
        if (view.isAttachedToWindow && envsCache.size > 1) handler.postDelayed(rotacionar, 5000)
    }

    fun bind(p: Painel) {
        val envs = p.envelopes()
        val mesmaLista = envs.map { it.limite.id } == envsCache.map { it.limite.id }
        envsCache = envs
        if (envs.isEmpty()) {
            handler.removeCallbacks(rotacionar)
            lista.removeAllViews()
            lista.addView(ui.text("Nenhum limite ainda. Crie envelopes (ex.: Supermercado R$ 800) no Planejamento.", 14f, Cores.BRANCO_70), ui.mp(ui.dp(6)))
            return
        }
        if (!mesmaLista) idx = 0 else idx = idx.coerceIn(0, envs.size - 1)
        desenharUm(envs[idx])
        if (!mesmaLista) agendarRotacao()
    }

    private fun desenharUm(e: space.neural.luna.dashboard.Plano.Envelope) {
        lista.removeAllViews()
        val cor = when { e.excedido -> Cores.CORAL; e.fracao >= 0.8f -> Cores.LARANJA; else -> Cores.ESMERALDA }
        val box = ui.vbox().apply {
            contentDescription = "${e.limite.nome}: gastou ${Fmt.reais(e.gastoCentavos)} de ${Fmt.reais(e.limite.limiteCentavos)}. " +
                (if (e.excedido) "Passou ${Fmt.reais(e.excessoCentavos)} do limite." else "Ainda pode gastar ${Fmt.reais(e.restanteCentavos)}.")
            isFocusable = true; isClickable = true; minimumHeight = ui.dp(64)
            setOnClickListener { a.gastoEnvelope(e) }
        }
        box.addView(ui.hbox().apply {
            addView(ui.ajustavel(e.limite.nome, 16f, 12f, Cores.BRANCO), ui.peso(1f))
            addView(ui.ajustavel("${Fmt.reaisCurto(e.gastoCentavos)} / ${Fmt.reaisCurto(e.limite.limiteCentavos)}", 15f, 11f, cor), ui.wc(ui.dp(8)))
        })
        box.addView(BarraH3D(ui.ctx).apply { definir(e.fracao.coerceIn(0f, 1f), cor) }, ui.mp(ui.dp(4)))
        box.addView(ui.text(
            if (e.excedido) "⚠ Passou ${Fmt.reais(e.excessoCentavos)} do limite" else "Ainda pode gastar ${Fmt.reais(e.restanteCentavos)}",
            13f, if (e.excedido) Cores.CORAL else Cores.BRANCO_70, bold = e.excedido,
        ), ui.mp(ui.dp(2)))
        lista.addView(box, ui.mp(ui.dp(6)))
    }

    // legado: lista completa (não usada no modo carrossel)
    private fun bindLista(p: Painel) {
        val envs = p.envelopes()
        envs.forEachIndexed { i, e ->
            val cor = when { e.excedido -> Cores.CORAL; e.fracao >= 0.8f -> Cores.LARANJA; else -> Cores.ESMERALDA }
            val box = ui.vbox().apply {
                contentDescription = "${e.limite.nome}: gastou ${Fmt.reais(e.gastoCentavos)} de ${Fmt.reais(e.limite.limiteCentavos)}. " +
                    (if (e.excedido) "Passou ${Fmt.reais(e.excessoCentavos)} do limite." else "Ainda pode gastar ${Fmt.reais(e.restanteCentavos)}.") + " Toque para lançar um gasto"
                isFocusable = true; isClickable = true; minimumHeight = ui.dp(48)
                setOnClickListener { a.gastoEnvelope(e) }
            }
            box.addView(ui.hbox().apply {
                addView(ui.ajustavel(e.limite.nome, 15f, 11f, Cores.BRANCO), ui.peso(1f))
                addView(ui.ajustavel("${Fmt.reaisCurto(e.gastoCentavos)} / ${Fmt.reaisCurto(e.limite.limiteCentavos)}", 14f, 10f, cor), ui.wc(ui.dp(8)))
            })
            box.addView(BarraH3D(ui.ctx).apply { definir(e.fracao.coerceIn(0f, 1f), cor) }, ui.mp(ui.dp(4)))
            box.addView(ui.text(
                if (e.excedido) "⚠ Passou ${Fmt.reais(e.excessoCentavos)} do limite" else "Ainda pode gastar ${Fmt.reais(e.restanteCentavos)}",
                13f, if (e.excedido) Cores.CORAL else Cores.BRANCO_70, bold = e.excedido,
            ), ui.mp(ui.dp(2)))
            lista.addView(box, ui.mp(if (i == 0) ui.dp(4) else ui.dp(12)))
        }
    }
}
