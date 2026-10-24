package space.neural.luna.dashboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import space.neural.luna.R
import space.neural.luna.chat.LunaConversa
import space.neural.luna.data.local.EstadoTurno
import space.neural.luna.data.local.MaquinaTurno
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.sin

/**
 * Fundo animado: galáxia (estrelas que piscam) com aurora boreal que ondula devagar.
 * ~24 quadros/s, só shaders e caminhos pré-calculados; para quando a tela some.
 * Com "remover animações" ligado, fica parada.
 */
class GalaxiaView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var fase = 0f
    private var anim: ValueAnimator? = null
    private var nebulosas: List<RadialGradient> = emptyList()
    private var auroras: List<LinearGradient> = emptyList()
    private val estrelas = FloatArray(90 * 3)
    private var ultimo = 0L

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val t = Shader.TileMode.CLAMP
        val m = maxOf(w, h).toFloat()
        nebulosas = listOf(
            RadialGradient(w * 0.15f, h * 0.05f, m * 0.6f, intArrayOf(Cores.alpha(Cores.VIOLETA, 0.28f), 0), null, t),
            RadialGradient(w * 0.95f, h * 0.5f, m * 0.5f, intArrayOf(Cores.alpha(Cores.CIANO, 0.12f), 0), null, t),
            RadialGradient(w * 0.35f, h * 1.0f, m * 0.55f, intArrayOf(Cores.alpha(Cores.MAGENTA, 0.12f), 0), null, t),
        )
        val cs = listOf(Cores.ESMERALDA, Cores.CIANO, Cores.MAGENTA)
        auroras = cs.mapIndexed { i, c ->
            val c2 = cs[(i + 1) % cs.size]
            LinearGradient(0f, 0f, w.toFloat(), 0f, intArrayOf(Cores.alpha(c, 0.15f), c, Cores.alpha(c2, 0.6f), Cores.alpha(c, 0.1f)), floatArrayOf(0f, 0.35f, 0.7f, 1f), t)
        }
        val rnd = java.util.Random(7)
        for (i in 0 until 90) { estrelas[i * 3] = rnd.nextFloat() * w; estrelas[i * 3 + 1] = rnd.nextFloat() * h; estrelas[i * 3 + 2] = rnd.nextFloat() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!Anima.ligadas()) return
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 60_000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener {
                fase = it.animatedValue as Float
                val agora = System.nanoTime()
                if (agora - ultimo >= 41_000_000) { ultimo = agora; invalidate() } // ~24 fps
            }
            start()
        }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); anim = null; super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) anim?.resume() else anim?.pause()
    }

    private companion object { val AURORA = listOf(0.16f to 0.05f, 0.09f to 0.06f, 0.035f to 0.09f) }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(Cores.OBSIDIAN)
        p.style = Paint.Style.FILL
        nebulosas.forEach { p.shader = it; c.drawRect(0f, 0f, w, h, p) }
        // Aurora: faixas onduladas que correm devagar
        val tw = (fase * 2 * Math.PI).toFloat()
        // Cada faixa é uma curva suave desenhada em 3 passadas (larga e fraca → fina e forte) = brilho difuso sem blur.
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        auroras.forEachIndexed { i, g ->
            path.reset()
            val base = h * (0.10f + i * 0.09f)
            val amp = h * 0.045f
            val passo = w / 6
            fun y(x: Float) = base + amp * sin(x / w * 6.28f * (0.8f + i * 0.25f) + tw * (i + 1) + i * 1.7f)
            var x0 = -passo; var y0 = y(x0)
            path.moveTo(x0, y0)
            while (x0 <= w + passo) {
                val x1 = x0 + passo; val y1 = y(x1)
                path.quadTo(x0, y0, (x0 + x1) / 2, (y0 + y1) / 2)
                x0 = x1; y0 = y1
            }
            p.shader = g
            for ((larg, a) in AURORA) { p.strokeWidth = h * larg; p.alpha = (a * 255).toInt(); c.drawPath(path, p) }
        }
        p.alpha = 255; p.style = Paint.Style.FILL
        p.shader = null
        for (i in 0 until 90) {
            val b = estrelas[i * 3 + 2]
            val a = 0.25f + 0.6f * ((sin(tw * 40 * (0.3f + b) + i) + 1) / 2) * b
            p.color = Cores.alpha(if (i % 7 == 0) Cores.CIANO else Cores.BRANCO, a)
            c.drawCircle(estrelas[i * 3], estrelas[i * 3 + 1], 0.6f + b * 1.4f, p)
        }
    }
}

/**
 * Card de entrada da Luna (abaixo de "Bom dia"): degradê colorido animado, orb, "Fale com a Luna…" e microfone.
 * Um toque = falar por voz aqui mesmo; dois toques (ou "Abrir conversa") = conversa completa.
 */
class LunaCard(private val ui: Ui, private val aoFalar: () -> Unit, private val aoAbrir: () -> Unit, private val aoBotao: (LunaConversa.Botao) -> Unit, private val aoCamera: (View) -> Unit = {}) {
    private val fundo = FundoLuna(ui)
    val view: LinearLayout = ui.vbox(ui.dp(8)).apply {
        background = fundo
        elevation = ui.dpf(14f)
        if (android.os.Build.VERSION.SDK_INT >= 28) { outlineSpotShadowColor = Cores.MAGENTA; outlineAmbientShadowColor = Cores.VIOLETA }
        contentDescription = "Luna. Toque para falar por voz. Toque duas vezes para abrir a conversa"
        isClickable = true; isFocusable = true
    }
    /** v0.1.5: a Luna de verdade (foto animada: respira, pisca e fala com a boca sincronizada). */
    private val orb = space.neural.luna.avatar.LunaAvatarView(ui.ctx)
    /** Uma linha que encolhe a fonte (18 → 13 sp) para caber inteira, em vez de cortar em "Fale com a L…". */
    private val titulo = ui.ajustavel("Fale com a Luna…", 18f, if (ui.ctx.resources.configuration.screenWidthDp < 400) 11f else 13f).apply { ellipsize = android.text.TextUtils.TruncateAt.END; setShadowLayer(10f, 0f, 0f, Cores.alpha(Cores.OBSIDIAN, 0.6f)) }
    /** Tela estreita (telefone em pé): legenda curta e botões menores, para "Fale com a Luna…" caber inteiro. */
    private val estreito = ui.ctx.resources.configuration.screenWidthDp < 480
    private val subPadrao = if (estreito) "Toque: falar · 2 toques: conversa" else "Toque para falar · toque duas vezes para abrir a conversa"
    private val subDeNovo = if (estreito) "Toque: falar de novo · 2 toques: conversa" else "Toque para falar de novo · duas vezes para a conversa"
    private val sub = ui.text(subPadrao, 13f, Cores.alpha(Cores.BRANCO, 0.85f))
    private val resposta = ui.text("", 15f, Cores.BRANCO).apply { visibility = View.GONE; maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val botoes = ui.hbox().apply { visibility = View.GONE }
    private val mic = ui.botaoIcone(R.drawable.ic_mic, "Falar com a Luna", Cores.BRANCO) { aoFalar() }.apply {
        background = ui.fundo(Cores.alpha(Cores.BRANCO, 0.18f), Cores.alpha(Cores.BRANCO, 0.5f), 28f)
    }

    /** v0.1.5: foto de nota fiscal direto da tela inicial (câmera ou galeria). */
    private val camera = ui.botaoIcone(R.drawable.ic_camera, "Foto de nota fiscal: câmera ou galeria", Cores.BRANCO) { aoCamera(it) }.apply {
        background = ui.fundo(Cores.alpha(Cores.BRANCO, 0.12f), Cores.alpha(Cores.BRANCO, 0.4f), 26f)
    }

    init {
        val l = ui.hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        orb.setOnClickListener { aoFalar() }
        val orbDp = if (ui.ctx.resources.configuration.screenWidthDp < 400) 52 else 56
        l.addView(orb, LinearLayout.LayoutParams(ui.dp(orbDp), ui.dp(orbDp)))
        val t = ui.vbox(); t.addView(titulo); t.addView(sub, ui.mp(ui.dp(2)))
        ui.truncar(sub, 2)
        l.addView(t, ui.peso(1f, ui.dp(if (estreito) 8 else 10)))
        // botões sempre dentro do card (peso 0, tamanho fixo): o texto encolhe, nada passa da borda
        val b = if (estreito) 46 else 48; val gap = if (estreito) 4 else 6
        val cameraDisponivel = ui.ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)
        val modoCarro = ui.ctx.getSharedPreferences("onboarding", Context.MODE_PRIVATE).getString("dispositivo", "PHONE") == "CARRO"
        if (cameraDisponivel && !modoCarro) l.addView(camera, LinearLayout.LayoutParams(ui.dp(b), ui.dp(b)).apply { leftMargin = ui.dp(gap) })
        l.addView(mic, LinearLayout.LayoutParams(ui.dp(b + 4), ui.dp(b + 4)).apply { leftMargin = ui.dp(gap) })
        l.addView(ui.botaoIcone(R.drawable.ic_chat, "Abrir conversa com a Luna", Cores.BRANCO) { aoAbrir() }, LinearLayout.LayoutParams(ui.dp(b), ui.dp(b)).apply { leftMargin = ui.dp(2) })
        view.addView(l, ui.mp())
        view.addView(resposta, ui.mp(ui.dp(8)))
        view.addView(botoes, ui.mp(ui.dp(8)))
        val gd = GestureDetector(ui.ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { aoFalar(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { aoAbrir(); return true }
        })
        view.setOnTouchListener { v, e -> val r = gd.onTouchEvent(e); if (e.action == MotionEvent.ACTION_UP) v.performClick(); r }
        view.setOnClickListener { } // acessibilidade: o TalkBack usa o clique → falar
        androidx.core.view.ViewCompat.replaceAccessibilityAction(view, androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK, "falar com a Luna") { _, _ -> aoFalar(); true }
    }

    fun ouvindo(parcial: String?) {
        orb.ativa = true; orb.ouvindo = true; fundo.intenso = true
        mic.imageTintList = android.content.res.ColorStateList.valueOf(Cores.CIANO)
        mic.contentDescription = "Ouvindo. Toque para parar"
        titulo.text = "Ouvindo…"; sub.text = parcial?.takeIf { it.isNotBlank() } ?: "Pode falar${space.neural.luna.conta.Conta.vocativo()}"
        botoes.visibility = View.GONE
    }

    fun pensando(texto: String) {
        pararOuvir()
        orb.ativa = true; fundo.intenso = true
        titulo.text = "Luna está pensando…"; sub.text = "“$texto”"
    }

    private fun pararOuvir() {
        orb.ouvindo = false
        mic.imageTintList = android.content.res.ColorStateList.valueOf(Cores.BRANCO)
        mic.contentDescription = "Falar com a Luna"
    }

    fun resposta(r: LunaConversa.Resposta) {
        pararOuvir()
        orb.ativa = false; fundo.intenso = false
        titulo.text = "Luna"; sub.text = subDeNovo
        resposta.text = r.texto; resposta.visibility = View.VISIBLE
        resposta.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        botoes.removeAllViews()
        r.botoes.take(3).forEachIndexed { i, b ->
            botoes.addView(ui.botao(b.rotulo, if (b.principal) Ui.Estilo.CHEIO_VIOLETA else Ui.Estilo.CONTORNO) { aoBotao(b) }.apply { textSize = 13f }, ui.peso(1f, if (i == 0) 0 else ui.dp(6)))
        }
        botoes.visibility = if (r.botoes.isEmpty()) View.GONE else View.VISIBLE
        if (Anima.ligadas()) { resposta.alpha = 0f; resposta.animate().alpha(1f).setDuration(350).start() }
    }

    fun ocioso(msg: String? = null) {
        pararOuvir()
        orb.ativa = false; fundo.intenso = false
        titulo.text = "Fale com a Luna…"
        sub.text = msg ?: subPadrao
    }
}

/** Degradê rosa → violeta → ciano que corre devagar (como a barra colorida da referência). */
class FundoLuna(private val ui: Ui) : android.graphics.drawable.Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val brilho = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borda = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ui.dpf(1.5f); color = Cores.alpha(Cores.BRANCO, 0.45f) }
    private val sombra = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val m = Matrix()
    private var fase = 0f
    private var anim: ValueAnimator? = null
    var intenso = false
    private val raio = ui.dpf(22f)

    override fun onBoundsChange(b: android.graphics.Rect) {
        val w = b.width().toFloat()
        p.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(Cores.OBSIDIAN, Cores.VIOLETA, Cores.CIANO, Cores.VIOLETA, Cores.MAGENTA), null, Shader.TileMode.MIRROR)
        brilho.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.top + b.height() * 0.5f, Cores.alpha(Cores.BRANCO, 0.28f), 0, Shader.TileMode.CLAMP)
        sombra.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(), 0, Cores.alpha(Cores.OBSIDIAN, 0.45f), Shader.TileMode.CLAMP)
        if (anim == null && Anima.ligadas()) {
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 9000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                addUpdateListener { fase = it.animatedValue as Float; invalidateSelf() }
                start()
            }
        }
    }

    override fun draw(c: Canvas) {
        val b = bounds
        r.set(b)
        m.setTranslate(fase * b.width() * 2 * (if (intenso) 1.6f else 1f), 0f)
        p.shader?.setLocalMatrix(m)
        p.alpha = 235
        c.drawRoundRect(r, raio, raio, p)
        // escurece um pouco embaixo para o texto ler bem
        c.drawRoundRect(r, raio, raio, sombra)
        c.drawRoundRect(r, raio, raio, brilho)
        c.drawRoundRect(r, raio, raio, borda)
    }

    override fun getOutline(outline: android.graphics.Outline) { outline.setRoundRect(bounds, raio); outline.alpha = 0.7f }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** Números dos relatórios (mês atual), calculados do banco local. */
object Relatorio {
    data class Item(val titulo: String, val valor: String, val sub: String, val cor: Int, val icone: Int)

    fun calcular(p: Painel, agora: Long = space.neural.luna.sync.Relogio.agora()): List<Item> {
        val hoje = p.hoje
        val mesPre = hoje.toString().substring(0, 7)
        val iniSem = hoje.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
        val turnos = p.turnosTodos.filter { !it.deleted }
        fun horas(f: (String) -> Boolean) = turnos.filter { f(it.data) }.sumOf { MaquinaTurno.trabalhoMs(it, agora) }
        val hDia = horas { it == hoje.toString() }
        val hSem = horas { it >= iniSem && it <= hoje.toString() }
        val hMes = horas { it.startsWith(mesPre) }
        val gm = p.ganhosTodos.filter { it.data.startsWith(mesPre) }
        val dm = p.gastosTodos.filter { it.data.startsWith(mesPre) }
        val pessoais = p.gastosLimites.filter { it.data.startsWith(mesPre) }.sumOf { it.valorCentavos }
        val bruto = gm.sumOf { it.valorCentavos }; val gastos = dm.sumOf { it.valorCentavos }
        val liq = bruto - gastos
        val dias = (gm.map { it.data } + turnos.filter { it.data.startsWith(mesPre) && MaquinaTurno.trabalhoMs(it, agora) > 0 }.map { it.data }).toSet()
        val porDia = dias.associateWith { d -> gm.filter { it.data == d }.sumOf { it.valorCentavos } - dm.filter { it.data == d }.sumOf { it.valorCentavos } }
        val melhorDia = porDia.maxByOrNull { it.value }
        val porApp = Calculos.APPS.associateWith { a -> gm.filter { it.app == a }.sumOf { it.valorCentavos } }
        val melhorApp = porApp.maxByOrNull { it.value }?.takeIf { it.value > 0 }
        val corridas = gm.count { it.descricao != Estatisticas.AJUSTE }
        fun h(ms: Long) = Fmt.hms(ms).substring(0, 5) + " h"
        val nomeMes = hoje.month.getDisplayName(TextStyle.FULL, Locale.forLanguageTag("pt-BR"))
        return listOf(
            Item("Dias trabalhados", "${dias.size}", "em $nomeMes", Cores.ESMERALDA, R.drawable.ic_calendar),
            Item("Horas hoje", h(hDia), "semana ${h(hSem)} · mês ${h(hMes)}", Cores.CIANO, R.drawable.ic_timer),
            Item("Média por hora", Calculos.porHoraCentavos(liq, hMes)?.let { Fmt.reais(it) + "/h" } ?: "—", "líquido do mês", Cores.MAGENTA, R.drawable.ic_trending),
            Item("Média por dia", if (dias.isNotEmpty()) Fmt.reais(liq / dias.size) else "—", "líquido por dia trabalhado", Cores.VIOLETA, R.drawable.ic_wallet),
            Item("Melhor dia", melhorDia?.let { Fmt.reais(it.value) } ?: "—", melhorDia?.let { LocalDate.parse(it.key).let { d -> Fmt.dataCurta(d) } } ?: "sem dados", Cores.LARANJA, R.drawable.ic_flag),
            Item("Melhor app", melhorApp?.let { Fmt.nomeApp(it.key) } ?: "—", melhorApp?.let { "${Fmt.reais(it.value)} no mês" } ?: "sem corridas", melhorApp?.let { CoresSerie.app(it.key) } ?: Cores.CIANO, R.drawable.ic_car),
            Item("Gastos do mês", Fmt.reais(gastos), "trabalho · pessoais ${Fmt.reais(pessoais)}", Cores.CORAL, R.drawable.ic_wrench),
            Item("Corridas no mês", "$corridas", "bruto ${Fmt.reais(bruto)}", Cores.AMARELO, R.drawable.ic_car),
            Item("Líquido do mês", Fmt.reais(liq), "bruto − gastos do trabalho", Cores.ESMERALDA, R.drawable.ic_wallet),
        )
    }
}

/** Carrossel de relatórios: rola sozinho (pausa quando ele mexe) e abre o relatório completo ao tocar. */
class RelatoriosCard(private val ui: Ui, private val aoAbrir: () -> Unit) {
    val view = ui.card(acento = Cores.MAGENTA)
    private val faixa = ui.hbox()
    private val rolagem = object : HorizontalScrollView(ui.ctx) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean { pausaAte = System.currentTimeMillis() + 6000; return super.dispatchTouchEvent(ev) }
    }.apply { isHorizontalScrollBarEnabled = true; isScrollbarFadingEnabled = false; addView(faixa); clipToPadding = false }
    private var pausaAte = 0L
    private var automatico = true
    private var assinatura = ""
    private val passo = Runnable { avancar() }

    init {
        val topo = ui.hbox()
        topo.addView(ui.icone(R.drawable.ic_trending, Cores.MAGENTA, 18))
        topo.addView(ui.titulo("Relatórios"), ui.peso(1f, ui.dp(8)))
        topo.addView(ui.botao("Parar", Ui.Estilo.CONTORNO) { button ->
            automatico = !automatico
            (button as android.widget.Button).text = if (automatico) "Parar" else "Auto"
            rolagem.removeCallbacks(passo)
            if (automatico && rolagem.isAttachedToWindow) rolagem.postDelayed(passo, 5000)
        }.apply { textSize = 12f; contentDescription = "Pausar ou retomar a rolagem automática dos relatórios" })
        topo.addView(ui.botao("Ver tudo", Ui.Estilo.TEXTO) { aoAbrir() })
        view.addView(topo, ui.mp())
        view.addView(rolagem, ui.mp(ui.dp(8)))
        rolagem.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { if (automatico) v.removeCallbacks(passo).also { v.postDelayed(passo, 5000) } }
            override fun onViewDetachedFromWindow(v: View) { v.removeCallbacks(passo) }
        })
    }

    private fun avancar() {
        if (!automatico) return
        if (System.currentTimeMillis() >= pausaAte && faixa.childCount > 0) {
            val w = faixa.getChildAt(0).width + ui.dp(10)
            val max = faixa.width - rolagem.width
            if (max > 0) { if (rolagem.scrollX + 4 >= max) rolagem.smoothScrollTo(0, 0) else rolagem.smoothScrollBy(w, 0) }
        }
        rolagem.postDelayed(passo, 5000)
    }

    fun bind(p: Painel, periodo: Periodo = Periodo.MES) {
        val summary = ReportAnalytics.summarize(ReportAnalytics.window(periodo, p.hoje), p.ganhosTodos, p.gastosTodos, p.turnosTodos, space.neural.luna.sync.Relogio.agora())
        val itens = listOf(
            Relatorio.Item("Melhor app · bruto", summary.bestAppByGross?.let { Fmt.nomeApp(it.app) } ?: "—", summary.bestAppByGross?.let { Fmt.reais(it.cents) } ?: "sem dados", Cores.CIANO, R.drawable.ic_car),
            Relatorio.Item("Melhor dia · líquido", summary.bestDayByNet?.let { Fmt.reais(it.cents) } ?: "—", summary.bestDayByNet?.let { Fmt.dataCurta(it.date) } ?: "sem dados", Cores.LARANJA, R.drawable.ic_calendar),
            Relatorio.Item("Líquido por hora", summary.netPerHour?.let { Fmt.reais(it) } ?: "—", if (summary.insufficientSample) "amostra insuficiente" else "${summary.rides} corridas · ${summary.recordedDays} dias", Cores.MAGENTA, R.drawable.ic_timer),
        )
        val ass = periodo.name + itens.joinToString("|") { it.valor + it.sub }
        if (ass == assinatura) return
        val primeira = assinatura.isEmpty()
        assinatura = ass
        faixa.removeAllViews()
        itens.forEachIndexed { i, it ->
            val tile = ui.card(acento = it.cor, colorido = true).apply {
                setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
                isClickable = true; isFocusable = true; setOnClickListener { aoAbrir() }
                contentDescription = "${it.titulo}: ${it.valor}. ${it.sub}. Toque para ver o relatório"
            }
            tile.addView(ui.hbox().apply {
                addView(ui.icone(it.icone, Cores.BRANCO, 16))
                addView(ui.ajustavel(it.titulo.uppercase(), 11f, 9f, Cores.alpha(Cores.BRANCO, 0.85f), bold = true), ui.peso(1f, ui.dp(6)))
            })
            tile.addView(ui.ajustavel(it.valor, 22f, 12f, Cores.BRANCO), ui.mp(ui.dp(6)))
            tile.addView(ui.ajustavel(it.sub, 12f, 9f, Cores.alpha(Cores.BRANCO, 0.8f), bold = false), ui.mp(ui.dp(2)))
            faixa.addView(tile, LinearLayout.LayoutParams(ui.dp(170), -2).apply { if (i > 0) leftMargin = ui.dp(10); bottomMargin = ui.dp(6) })
            if (primeira) Anima.entrada(tile, i)
        }
    }
}

/** Calendário do mês: ponto verde nos dias com lançamento; toque abre o dia (ver/editar/lançar). */
class CalendarioCard(private val ui: Ui, private val aoAbrirDia: (LocalDate) -> Unit) {
    val view = ui.card(acento = Cores.ESMERALDA)
    // Em telefones estreitos (< 400 dp) o mês ocupa menos e encolhe a fonte, para "›" não passar da borda.
    private val estreito = ui.ctx.resources.configuration.screenWidthDp < 400
    private val titulo = ui.ajustavel("", 16f, 11f, Cores.BRANCO).apply { gravity = Gravity.CENTER; maxLines = 1 }
    private val grade = ui.vbox()
    private var mes = YearMonth.now()
    private var painel: Painel? = null
    private val pt = Locale.forLanguageTag("pt-BR")

    init {
        val topo = ui.hbox()
        topo.addView(ui.icone(R.drawable.ic_calendar, Cores.ESMERALDA, 18))
        topo.addView(ui.titulo("Calendário"), ui.peso(1f, ui.dp(8)))
        view.addView(topo, ui.mp())
        val navegacao = ui.hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        navegacao.addView(ui.botao("‹", Ui.Estilo.TEXTO) { mes = mes.minusMonths(1); desenhar() }.apply { contentDescription = "Mês anterior"; textSize = 22f })
        navegacao.addView(titulo, ui.peso(1f))
        navegacao.addView(ui.botao("›", Ui.Estilo.TEXTO) { mes = mes.plusMonths(1); desenhar() }.apply { contentDescription = "Próximo mês"; textSize = 22f })
        view.addView(navegacao, ui.mp())
        view.addView(grade, ui.mp(ui.dp(6)))
    }

    fun bind(p: Painel) { painel = p; desenhar() }

    private fun desenhar() {
        val p = painel ?: return
        val hoje = LocalDate.now()
        titulo.text = mes.month.getDisplayName(TextStyle.FULL, pt).replaceFirstChar { it.uppercase() } + " " + mes.year
        val pre = mes.toString()
        val comDados = (p.ganhosTodos.map { it.data } + p.gastosTodos.map { it.data } + p.gastosLimites.map { it.data } +
            p.turnosTodos.filter { !it.deleted && it.estado != EstadoTurno.PRONTO }.map { it.data }).filter { it.startsWith(pre) }.toSet()
        grade.removeAllViews()
        val cab = ui.hbox()
        listOf("S", "T", "Q", "Q", "S", "S", "D").forEach { cab.addView(ui.text(it, 12f, Cores.BRANCO_70, bold = true).apply { gravity = Gravity.CENTER }, ui.peso(1f)) }
        grade.addView(cab, ui.mp())
        val primeiro = mes.atDay(1)
        var d = primeiro.minusDays((primeiro.dayOfWeek.value - 1).toLong())
        repeat(6) { semana ->
            if (semana > 0 && d.monthValue != mes.monthValue && d.isAfter(primeiro)) return@repeat
            val linha = ui.hbox()
            repeat(7) {
                val dia = d
                val doMes = dia.monthValue == mes.monthValue
                val cel = ui.vbox().apply {
                    gravity = Gravity.CENTER; minimumHeight = ui.dp(44)
                    if (doMes) {
                        isClickable = true; isFocusable = true
                        setOnClickListener { aoAbrirDia(dia) }
                        val tem = dia.toString() in comDados
                        contentDescription = "${dia.dayOfMonth} de ${dia.month.getDisplayName(TextStyle.FULL, pt)}" + (if (tem) ", com lançamentos" else "") +
                            (if (dia == hoje) ", hoje" else "") + (if (dia.isAfter(hoje)) ", dia futuro" else "")
                        if (dia == hoje) background = ui.fundo(Cores.alpha(Cores.ESMERALDA, 0.15f), Cores.ESMERALDA, 12f)
                    }
                }
                cel.addView(ui.text(if (doMes) "${dia.dayOfMonth}" else "", 14f,
                    when { !doMes -> 0; dia.isAfter(hoje) -> Cores.BRANCO_40; else -> Cores.BRANCO }, bold = dia == hoje).apply { gravity = Gravity.CENTER })
                cel.addView(View(ui.ctx).apply {
                    background = ui.fundo(Cores.ESMERALDA, raio = 3f)
                    visibility = if (doMes && dia.toString() in comDados) View.VISIBLE else View.INVISIBLE
                    if (android.os.Build.VERSION.SDK_INT >= 28) { elevation = ui.dpf(3f); outlineSpotShadowColor = Cores.ESMERALDA }
                }, LinearLayout.LayoutParams(ui.dp(6), ui.dp(6)).apply { topMargin = ui.dp(3) })
                linha.addView(cel, ui.peso(1f))
                d = d.plusDays(1)
            }
            grade.addView(linha, ui.mp(ui.dp(2)))
        }
    }
}
