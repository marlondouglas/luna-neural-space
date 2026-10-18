package space.neural.luna.dashboard

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import space.neural.luna.R
import space.neural.luna.data.local.CategoriaGasto
import space.neural.luna.data.local.EstadoTurno
import space.neural.luna.data.local.TipoMeta
import java.time.LocalDate
import java.time.LocalTime

/** O que os cards pedem para a tela fazer. */
interface AcoesPainel {
    fun iniciar(); fun pausar(); fun cafe(); fun retomar(); fun fecharTurno(); fun concluir(); fun corrigir(); fun zerar()
    fun diaEsquecido()
    fun detalheDia(); fun detalheApp(app: String)
    fun lancarGanho(app: String?); fun lancarGasto(categoria: String?)
    fun editarMeta(tipo: String, atual: Long?)
    fun dispensarAlerta(chave: String)
    fun pararAgoraNao()
    fun atualizarNoticias(); fun abrirNoticia(n: Externo.Noticia); fun previsaoHoras()
    fun ler(texto: String); fun compartilhar(texto: String)
    fun conversar(); fun menu(ancora: View); fun sincronizar()
}

// ---------------- Cabeçalho ----------------
class Cabecalho(private val ui: Ui, compacto: Boolean, a: AcoesPainel) {
    val view: LinearLayout = ui.hbox().apply { setPadding(0, 0, 0, ui.dp(4)) }
    private val saudacao = ui.text("", if (compacto) 18f else 22f, Cores.BRANCO, bold = true)
    private val data = ui.text("", 14f, Cores.BRANCO_70)
    private val syncIc = ui.icone(R.drawable.ic_cloud, Cores.ESMERALDA, 18)
    private val syncTx = ui.text("", 12f, Cores.BRANCO_70)
    private val sync = ui.hbox().apply {
        addView(syncIc); addView(syncTx, ui.wc(ui.dp(4)))
        minimumHeight = ui.dp(48); isClickable = true; isFocusable = true
        setPadding(ui.dp(8), 0, ui.dp(8), 0)
        setOnClickListener { a.sincronizar() }
    }

    init {
        val orb = LunaOrbView(ui.ctx).apply {
            setOnClickListener { a.conversar() }
            contentDescription = "Luna. Toque para conversar"
        }
        val tam = if (compacto) 52 else 76
        view.addView(orb, LinearLayout.LayoutParams(ui.dp(tam), ui.dp(tam)))
        val textos = if (compacto) ui.hbox() else ui.vbox()
        textos.addView(saudacao)
        textos.addView(data, if (compacto) ui.wc(ui.dp(10)) else ui.mp())
        view.addView(textos, ui.peso(1f, ui.dp(10)))
        view.addView(sync)
        view.addView(ui.botaoIcone(R.drawable.ic_chat, "Conversar com a Luna", Cores.VIOLETA) { a.conversar() })
        view.addView(ui.botaoIcone(R.drawable.ic_more, "Mais opções: histórico, anotações, Teste Firebase, Atualização", Cores.BRANCO) { a.menu(it) })
    }

    fun bind(p: Painel, online: Boolean, nuvem: Boolean) {
        val h = LocalTime.now().hour
        saudacao.text = (if (h < 12) "Bom dia" else if (h < 18) "Boa tarde" else "Boa noite") + ", Marlon"
        data.text = Fmt.dataCurta(p.hoje)
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

// ---------------- Turno (componente 4) ----------------
class TurnoCard(private val ui: Ui, private val largo: Boolean, private val a: AcoesPainel) {
    val view = ui.card(destaque = true)
    private val selo = ui.selo("", Cores.CIANO).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val contador = TextoAjustavel(ui.ctx, if (largo) 40f else 44f, 22f).apply {
        text = "00:00:00"; setTextColor(Cores.BRANCO_40)
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val parado = ui.text("", 14f, Cores.BRANCO_70)
    private val botoes = ui.hbox()
    private var estadoDesenhado: String? = "inicial"
    private var turno: space.neural.luna.data.local.Turno? = null

    init {
        val topo = ui.hbox()
        topo.addView(ui.tituloIcone(R.drawable.ic_timer, "Turno"), ui.peso(1f))
        topo.addView(selo)
        view.addView(topo, ui.mp())
        if (largo) {
            val linha = ui.hbox()
            linha.addView(contador)
            linha.addView(parado, ui.wc(ui.dp(16)))
            view.addView(linha, ui.mp(ui.dp(8)))
        } else {
            view.addView(contador, ui.mp(ui.dp(6)).apply { })
            contador.gravity = Gravity.CENTER
            parado.gravity = Gravity.CENTER
            view.addView(parado, ui.mp())
        }
        view.addView(botoes, ui.mp(ui.dp(12)))
    }

    fun bind(t: space.neural.luna.data.local.Turno?) {
        turno = t
        val est = t?.estado ?: "SEM"
        if (est != estadoDesenhado) {
            estadoDesenhado = est
            botoes.removeAllViews()
            fun princ(txt: String, ic: Int, f: () -> Unit) =
                botoes.addView(ui.comIcone(ui.botao(txt, Ui.Estilo.CHEIO_VIOLETA, alto = true) { f() }, ic), ui.peso(1.4f))
            fun sec(txt: String, ic: Int?, desc: String = txt, f: () -> Unit) {
                val b = ui.botao(txt) { f() }.apply { contentDescription = desc }
                if (ic != null) ui.comIcone(b, ic)
                botoes.addView(b, ui.peso(1f, ui.dp(8)))
            }
            when (est) {
                "SEM" -> { princ("Iniciar turno", R.drawable.ic_play) { a.iniciar() }; sec("Calendário", R.drawable.ic_calendar, "Calendário: lançar dia esquecido") { a.diaEsquecido() } }
                EstadoTurno.ATIVO -> { princ("Pausar", R.drawable.ic_pause) { a.pausar() }; sec("Café", R.drawable.ic_coffee) { a.cafe() }; sec("Fechar", R.drawable.ic_stop, "Fechar turno") { a.fecharTurno() } }
                EstadoTurno.PAUSADO -> { princ("Retomar", R.drawable.ic_play) { a.retomar() }; sec("Café", R.drawable.ic_coffee) { a.cafe() }; sec("Fechar", R.drawable.ic_stop, "Fechar turno") { a.fecharTurno() } }
                EstadoTurno.CAFE -> { princ("Retomar", R.drawable.ic_play) { a.retomar() }; sec("Fechar", R.drawable.ic_stop, "Fechar turno") { a.fecharTurno() } }
                EstadoTurno.FECHADO -> { princ("Concluir dia", R.drawable.ic_check) { a.concluir() }; sec("Corrigir", null) { a.corrigir() }; sec("Zerar", null) { a.zerar() } }
            }
            val (s, cor) = when (est) {
                EstadoTurno.ATIVO -> "● Trabalhando" to Cores.CIANO
                EstadoTurno.PAUSADO -> "⏸ Pausado" to Cores.CIANO
                EstadoTurno.CAFE -> "☕ Café" to Cores.CIANO
                EstadoTurno.FECHADO -> "■ Turno fechado" to Cores.VIOLETA
                else -> "Sem turno" to Cores.BRANCO_70
            }
            selo.text = s; selo.setTextColor(cor)
            selo.background = ui.fundo(Cores.alpha(Cores.VIOLETA, 0.12f), Cores.alpha(cor, 0.6f), 12f)
        }
        tick(System.currentTimeMillis())
    }

    /** Chamado a cada segundo: só atualiza textos. */
    fun tick(agora: Long) {
        val t = turno
        val trab = Calculos.trabalhoMs(t, agora)
        contador.text = Fmt.hms(trab)
        contador.setTextColor(when (t?.estado) {
            null -> Cores.BRANCO_40
            EstadoTurno.ATIVO -> Cores.CIANO
            EstadoTurno.PAUSADO, EstadoTurno.CAFE -> Cores.alpha(Cores.CIANO, 0.6f)
            else -> Cores.BRANCO
        })
        parado.text = if (t == null) "Pronto para começar?" else "Parado: ${Fmt.minutos(Calculos.paradoMs(t, agora))} min"
        view.contentDescription = null
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
            val ph = Calculos.porHoraCentavos(r.liquidoCentavos, Calculos.trabalhoMs(p.turno, agora)) ?: 0
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
        val ph = Calculos.porHoraCentavos(liqAtual, Calculos.trabalhoMs(p.turno, agora))
        porHora.text = if (ph != null) "Ganho por hora: ${Fmt.reais(ph)}/h" else "Ganho por hora aparece após 15 min de turno"
    }
}

// ---------------- Metas (componente 2) ----------------
class MetasCard(private val ui: Ui, private val a: AcoesPainel) {
    val view = ui.card()
    private val linha = ui.hbox().apply { gravity = Gravity.TOP }
    private val alerta = ui.vbox()
    private val aneis = mutableMapOf<String, MetaRing>()
    private val textos = mutableMapOf<String, TextView>()
    private val textos2 = mutableMapOf<String, TextView>()
    private val colunas = mutableMapOf<String, LinearLayout>()

    init {
        view.addView(ui.tituloIcone(R.drawable.ic_flag, "Metas", Cores.ESMERALDA))
        view.addView(linha, ui.mp(ui.dp(10)))
        view.addView(alerta, ui.mp())
        listOf(TipoMeta.DIA to "Dia", TipoMeta.SEMANA to "Semana", TipoMeta.MES to "Mês").forEachIndexed { i, (tipo, nome) ->
            val col = ui.vbox().apply {
                gravity = Gravity.CENTER_HORIZONTAL; isClickable = true; isFocusable = true
                minimumHeight = ui.dp(48)
            }
            col.addView(ui.text(nome, 14f, Cores.BRANCO, bold = true))
            val ring = MetaRing(ui.ctx); aneis[tipo] = ring
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
            col.setOnClickListener { a.editarMeta(m.tipo, m.metaCentavos) }
            if (m.metaCentavos == null) {
                ring.visibility = View.GONE
                t.text = "Defina sua meta"; t.setTextColor(Cores.VIOLETA)
                t2.text = "＋ Definir meta"; t2.setTextColor(Cores.VIOLETA)
                col.contentDescription = "Meta ${nome(m.tipo)} não definida. Toque para definir"
            } else {
                ring.visibility = View.VISIBLE; ring.definir(m.percentual)
                t.setTextColor(Cores.BRANCO_70); t2.setTextColor(if (m.percentual >= 100) Cores.ESMERALDA else Cores.BRANCO_70)
                t.text = "${Fmt.reaisCurto(m.liquidoCentavos.coerceAtLeast(0))} / ${Fmt.reaisCurto(m.metaCentavos)}"
                t2.text = if (m.percentual >= 100) "+${Fmt.reaisCurto(m.acimaCentavos)} acima" else "faltam ${Fmt.reaisCurto(m.faltaCentavos)}"
                col.contentDescription = "Meta ${nome(m.tipo)}: ${m.percentual} por cento. " +
                    (if (m.percentual >= 100) "Batida, ${Fmt.reais(m.acimaCentavos)} acima." else "Faltam ${Fmt.reais(m.faltaCentavos)}.") +
                    " Bruto ${Fmt.reais(m.brutoCentavos)}. Toque para editar"
            }
        }
        alerta.removeAllViews()
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

// ---------------- Ganhos por app (componente 3) + gráfico de 7 dias ----------------
class AppsCard(private val ui: Ui, private val horizontal: Boolean, private val a: AcoesPainel) {
    val view = ui.card()
    private val blocos = if (horizontal) ui.hbox() else ui.vbox()
    private val valores = mutableMapOf<String, TextView>()
    private val corridas = mutableMapOf<String, TextView>()
    private val barras = mutableMapOf<String, MetaBarFina>()
    private val caixas = mutableMapOf<String, LinearLayout>()
    private val ult = mutableMapOf<String, Long>()
    private val grafico = GanhosChart(ui.ctx)

    init {
        view.addView(ui.tituloIcone(R.drawable.ic_car, "Ganhos por app"))
        view.addView(blocos, ui.mp(ui.dp(10)))
        Calculos.APPS.forEachIndexed { i, app ->
            val caixa = ui.vbox(ui.dp(12)).apply { isClickable = true; isFocusable = true; setOnClickListener { a.detalheApp(app) } }
            val nomeV = ui.ajustavel(Fmt.nomeApp(app), 15f, 11f, GanhosChart.corApp(app))
            val mais = ui.botaoIcone(R.drawable.ic_add, "Lançar ganho no ${Fmt.nomeApp(app)}", Cores.BRANCO) { a.lancarGanho(app) }
            val v = ui.ajustavel("R$ 0,00", if (horizontal) 20f else 18f, 12f); valores[app] = v
            val c = ui.ajustavel("0 corridas", 13f, 10f, Cores.BRANCO_70, bold = false); corridas[app] = c
            val b = MetaBarFina(ui.ctx); barras[app] = b
            if (horizontal) {
                caixa.addView(ui.hbox().apply { addView(nomeV, ui.peso(1f)); addView(mais) })
                caixa.addView(v); caixa.addView(c)
            } else {
                caixa.setPadding(ui.dp(12), ui.dp(2), ui.dp(4), ui.dp(8))
                caixa.addView(ui.hbox().apply {
                    addView(nomeV, LinearLayout.LayoutParams(ui.dp(64), -2))
                    addView(v, ui.peso(1f)); addView(c, ui.wc(ui.dp(6))); addView(mais)
                })
            }
            caixa.addView(b, ui.mp(ui.dp(6)))
            caixas[app] = caixa
            blocos.addView(caixa, if (horizontal) ui.peso(1f, if (i == 0) 0 else ui.dp(8)) else ui.mp(if (i == 0) 0 else ui.dp(8)))
        }
        view.addView(ui.text("Últimos 7 dias", 13f, Cores.BRANCO_70), ui.mp(ui.dp(14)))
        view.addView(grafico, ui.mp(ui.dp(6)))
        val leg = ui.hbox()
        Calculos.APPS.forEach { app ->
            leg.addView(View(ui.ctx).apply { background = ui.fundo(GanhosChart.corApp(app), raio = 3f) }, LinearLayout.LayoutParams(ui.dp(10), ui.dp(10)).apply { leftMargin = ui.dp(10) })
            leg.addView(ui.text(Fmt.nomeApp(app), 12f, Cores.BRANCO_70), ui.wc(ui.dp(4)))
        }
        view.addView(leg, ui.mp(ui.dp(4)))
    }

    fun bind(p: Painel) {
        val melhor = p.porApp.filter { it.centavos > 0 }.maxByOrNull { it.centavos }?.app
        p.porApp.forEach { x ->
            val v = valores[x.app]!!
            Anima.contar(v, ult[x.app] ?: 0, x.centavos, Fmt::reais); ult[x.app] = x.centavos
            v.setTextColor(if (x.centavos > 0) Cores.BRANCO else Cores.BRANCO_70)
            corridas[x.app]!!.text = "${x.corridas} ${if (x.corridas == 1) "corrida" else "corridas"} · ${x.participacao}%"
            barras[x.app]!!.definir(x.participacao)
            caixas[x.app]!!.background = ui.fundo(Cores.alpha(Cores.VIOLETA, 0.06f),
                if (x.app == melhor) Cores.VIOLETA else Cores.alpha(Cores.BRANCO, 0.10f), 14f, if (x.app == melhor) 2 else 1)
            caixas[x.app]!!.contentDescription = "${Fmt.nomeApp(x.app)}: ${Fmt.reais(x.centavos)}, ${x.corridas} corridas, ${x.participacao} por cento do bruto" +
                (if (x.app == melhor) ". O que mais rendeu" else "") + ". Toque para ver as corridas"
        }
        grafico.definir(p.semana, "Gráfico dos últimos 7 dias: " + p.semana.joinToString("; ") { "${it.rotulo} ${Fmt.reais(it.porApp.values.sum())}" })
    }
}

/** Barra fina de participação (violeta), animada. */
class MetaBarFina(ctx: android.content.Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private var mostrado = 0f
    private val pT = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Cores.alpha(Cores.BRANCO, 0.15f) }
    private val pF = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Cores.VIOLETA }
    private val r = android.graphics.RectF()
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun definir(p: Int) {
        if (!Anima.ligadas()) { mostrado = p.toFloat(); invalidate(); return }
        android.animation.ValueAnimator.ofFloat(mostrado, p.toFloat()).apply {
            duration = 700; addUpdateListener { mostrado = it.animatedValue as Float; invalidate() }; start()
        }
    }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, w), (8 * d).toInt())
    override fun onDraw(c: android.graphics.Canvas) {
        val rad = height / 2f
        r.set(0f, 0f, width.toFloat(), height.toFloat()); c.drawRoundRect(r, rad, rad, pT)
        if (mostrado > 0) { r.set(0f, 0f, width * mostrado.coerceIn(0f, 100f) / 100f, height.toFloat()); c.drawRoundRect(r, rad, rad, pF) }
    }
}

// ---------------- Notícias e clima (componente 5) ----------------
class NoticiasCard(private val ui: Ui, private val lado: Boolean, private val a: AcoesPainel) {
    val view = ui.card()
    private val corpo = if (lado) ui.hbox().apply { gravity = Gravity.TOP } else ui.vbox()
    private val clima = ui.vbox().apply { isClickable = true; isFocusable = true; setOnClickListener { a.previsaoHoras() } }
    private val lista = ui.vbox()
    private val aviso = ui.vbox()

    init {
        val topo = ui.hbox()
        topo.addView(ui.tituloIcone(R.drawable.ic_news, "Governador Valadares", Cores.CIANO), ui.peso(1f))
        topo.addView(ui.botaoIcone(R.drawable.ic_refresh, "Atualizar notícias e clima", Cores.CIANO) { a.atualizarNoticias() })
        view.addView(topo, ui.mp())
        view.addView(aviso, ui.mp())
        corpo.addView(clima, if (lado) LinearLayout.LayoutParams(ui.dp(130), -2) else ui.mp(ui.dp(4)))
        corpo.addView(lista, if (lado) ui.peso(1f, ui.dp(12)) else ui.mp(ui.dp(8)))
        view.addView(corpo, ui.mp(ui.dp(6)))
    }

    fun bind(d: Externo.Dados?, online: Boolean, carregando: Boolean) {
        aviso.removeAllViews(); clima.removeAllViews(); lista.removeAllViews()
        if (d == null) {
            lista.addView(ui.text(if (carregando) "Carregando notícias e clima…" else "Notícias e clima aparecem quando houver internet.", 14f, Cores.BRANCO_70))
            return
        }
        if (!online) {
            val hora = java.time.Instant.ofEpochMilli(d.atualizadoEm).atZone(java.time.ZoneId.systemDefault())
            val quando = if (hora.toLocalDate() == LocalDate.now()) "às %02d:%02d".format(hora.hour, hora.minute) else "em ${Fmt.dataCurta(hora.toLocalDate())}"
            aviso.addView(ui.text("ⓘ Sem internet — última atualização $quando", 13f, Cores.CIANO), ui.mp(ui.dp(4)))
        }
        d.clima?.let { c ->
            val (ic, desc) = Externo.descricaoTempo(c.codigo)
            clima.addView(ui.hbox().apply {
                addView(ui.text("${Math.round(c.tempC)}°", 34f, Cores.BRANCO, bold = true))
                addView(ui.text(ic, 26f, Cores.CIANO), ui.wc(ui.dp(6)))
            })
            clima.addView(ui.text(desc, 13f, Cores.BRANCO_70))
            clima.addView(ui.text("Mín ${Math.round(c.minC)}° · Máx ${Math.round(c.maxC)}°", 13f, Cores.BRANCO_70))
            clima.addView(ui.text("Chuva: ${c.chuvaProx}% nas próx. horas", 13f, Cores.CIANO))
            clima.contentDescription = "Clima: ${Math.round(c.tempC)} graus, $desc. Mínima ${Math.round(c.minC)}, máxima ${Math.round(c.maxC)}. Chance de chuva ${c.chuvaProx} por cento. Toque para a previsão por hora"
        }
        if (d.noticias.isEmpty()) lista.addView(ui.text("Sem manchetes no momento.", 14f, Cores.BRANCO_70))
        d.noticias.forEach { n ->
            val item = ui.vbox().apply {
                isClickable = true; isFocusable = true; minimumHeight = ui.dp(48)
                setPadding(0, ui.dp(4), 0, ui.dp(4))
                setOnClickListener { a.abrirNoticia(n) }
            }
            val rot = ui.hbox()
            if (n.br381) rot.addView(ui.selo("BR-381", Cores.CIANO).apply { textSize = 11f })
            val hora = if (n.quando > 0) java.time.Instant.ofEpochMilli(n.quando).atZone(java.time.ZoneId.systemDefault()).let { "%02d/%02d %02d:%02d".format(it.dayOfMonth, it.monthValue, it.hour, it.minute) } else ""
            rot.addView(ui.text("${n.fonte} · $hora", 12f, Cores.BRANCO_70), ui.wc(if (n.br381) ui.dp(6) else 0))
            item.addView(rot)
            item.addView(ui.truncar(ui.text(n.titulo, 15f, Cores.BRANCO), 2))
            lista.addView(item, ui.mp(ui.dp(4)))
        }
    }
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
