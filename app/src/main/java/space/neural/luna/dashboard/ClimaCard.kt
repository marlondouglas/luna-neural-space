package space.neural.luna.dashboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import space.neural.luna.R
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.cos
import kotlin.math.sin

/** Tema visual do card de clima a partir do código WMO (Open-Meteo) e da hora. */
object ClimaTema {
    enum class Tipo { SOL, NUBLADO, CHUVA, NOITE }

    fun noite(hora: Int) = hora < 6 || hora >= 18

    fun tipo(codigo: Int, hora: Int): Tipo = when {
        codigo in 51..67 || codigo in 80..82 || codigo in 95..99 -> Tipo.CHUVA
        noite(hora) -> Tipo.NOITE
        codigo == 0 || codigo == 1 -> Tipo.SOL
        else -> Tipo.NUBLADO
    }

    /** Cores do degradê (topo → base). */
    fun cores(t: Tipo): IntArray = when (t) {
        Tipo.SOL -> intArrayOf(0xFF1E6FD9.toInt(), 0xFF3A9BEF.toInt(), 0xFFF2B33D.toInt())
        Tipo.NUBLADO -> intArrayOf(0xFF3E5A78.toInt(), 0xFF56708C.toInt(), 0xFF7F93A8.toInt())
        Tipo.CHUVA -> intArrayOf(0xFF26384D.toInt(), 0xFF3A4F66.toInt(), 0xFF51647A.toInt())
        Tipo.NOITE -> intArrayOf(0xFF120A33.toInt(), 0xFF2A1660.toInt(), 0xFF4A2A8C.toInt())
    }
}

/**
 * Fundo animado do card: degradê do tempo, partículas (chuva / estrelas) e o ícone animado
 * (sol com raios girando, nuvens passando, gotas caindo). ~30 fps só quando visível; para sozinho
 * fora da tela ou com animações desligadas no sistema.
 */
class ClimaFundoView(ctx: Context) : View(ctx) {
    var tipo = ClimaTema.Tipo.NUBLADO
        set(v) { if (field != v) { field = v; gradiente = null; invalidate() } }
    /** Nuvem junto do sol (parcialmente nublado). */
    var comNuvem = true
    private val d = resources.displayMetrics.density
    private val fundo = Paint()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val raio = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private var gradiente: LinearGradient? = null
    private val rnd = java.util.Random(7)
    private val gotas = FloatArray(60 * 3).also { for (i in it.indices) it[i] = rnd.nextFloat() }
    private val estrelas = FloatArray(40 * 3).also { for (i in it.indices) it[i] = rnd.nextFloat() }
    private val nuvem = Path()
    private val r = RectF()
    private var visivel = false
    private val raioCanto = 22 * d
    private val clip = Path()
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { if (visivel && Anima.ligadas()) { invalidate(); h.postDelayed(this, 33) } } }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        visivel = isVisible
        h.removeCallbacks(tick)
        if (isVisible) h.post(tick)
    }

    override fun onDetachedFromWindow() { visivel = false; h.removeCallbacks(tick); super.onDetachedFromWindow() }

    /** Só ocupa o que o card (FrameLayout) der: não empurra a altura do card. */
    override fun onMeasure(w: Int, h: Int) {
        fun tam(spec: Int) = if (MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(spec) else 0
        setMeasuredDimension(tam(w), tam(h))
    }

    override fun onSizeChanged(w: Int, hh: Int, ow: Int, oh: Int) {
        gradiente = null
        clip.reset(); clip.addRoundRect(RectF(0f, 0f, w.toFloat(), hh.toFloat()), raioCanto, raioCanto, Path.Direction.CW)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val hh = height.toFloat()
        if (w <= 0f) return
        val t = (System.nanoTime() / 1e9).let { if (Anima.ligadas()) it else 0.0 }
        c.save(); c.clipPath(clip)
        val cs = ClimaTema.cores(tipo)
        if (gradiente == null) gradiente = LinearGradient(0f, 0f, w * 0.35f, hh, cs, null, Shader.TileMode.CLAMP)
        // o degradê "respira" devagar
        val m = android.graphics.Matrix(); m.setTranslate((sin(t * 0.25) * w * 0.08).toFloat(), 0f)
        gradiente!!.setLocalMatrix(m)
        fundo.shader = gradiente
        c.drawRect(0f, 0f, w, hh, fundo)
        // ícone no canto superior direito
        val cx = w - 70 * d; val cy = 112 * d; val s = 30 * d // abaixo do botão atualizar, ao lado da temperatura
        when (tipo) {
            ClimaTema.Tipo.SOL -> { sol(c, cx, cy, s, t); if (comNuvem) nuvem(c, cx + 14 * d + (sin(t * 0.5) * 10 * d).toFloat(), cy + 18 * d, s * 0.8f, 0.92f) }
            ClimaTema.Tipo.NUBLADO -> {
                nuvem(c, cx - 18 * d + (sin(t * 0.35) * 14 * d).toFloat(), cy - 6 * d, s * 0.75f, 0.55f)
                nuvem(c, cx + (sin(t * 0.5 + 1) * 10 * d).toFloat(), cy + 10 * d, s, 0.95f)
            }
            ClimaTema.Tipo.CHUVA -> { chuva(c, w, hh, t); nuvem(c, cx + (sin(t * 0.4) * 8 * d).toFloat(), cy, s, 0.9f); gotasIcone(c, cx, cy + s * 0.8f, s, t) }
            ClimaTema.Tipo.NOITE -> { estrelas(c, w, hh, t); lua(c, cx, cy, s) }
        }
        c.restore()
    }

    private fun sol(c: Canvas, cx: Float, cy: Float, s: Float, t: Double) {
        p.shader = RadialGradient(cx, cy, s * 2.2f, intArrayOf(Cores.alpha(0xFFFFE07A.toInt(), 0.55f), 0), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, s * 2.2f, p); p.shader = null
        raio.color = 0xFFFFE07A.toInt(); raio.strokeWidth = 3.2f * d
        val a0 = t * 0.6
        for (i in 0 until 10) {
            val a = a0 + i * Math.PI / 5
            val r1 = s * 0.78f; val r2 = s * (1.08f + 0.08f * sin(t * 3 + i).toFloat())
            c.drawLine(cx + (cos(a) * r1).toFloat(), cy + (sin(a) * r1).toFloat(), cx + (cos(a) * r2).toFloat(), cy + (sin(a) * r2).toFloat(), raio)
        }
        p.color = 0xFFFFD447.toInt(); c.drawCircle(cx, cy, s * 0.6f, p)
    }

    private fun nuvem(c: Canvas, cx: Float, cy: Float, s: Float, a: Float) {
        p.color = Cores.alpha(Cores.BRANCO, a)
        nuvem.reset()
        nuvem.addCircle(cx - s * 0.45f, cy, s * 0.38f, Path.Direction.CW)
        nuvem.addCircle(cx, cy - s * 0.18f, s * 0.5f, Path.Direction.CW)
        nuvem.addCircle(cx + s * 0.48f, cy, s * 0.36f, Path.Direction.CW)
        r.set(cx - s * 0.83f, cy, cx + s * 0.84f, cy + s * 0.36f)
        nuvem.addRoundRect(r, s * 0.18f, s * 0.18f, Path.Direction.CW)
        c.drawPath(nuvem, p)
    }

    private fun gotasIcone(c: Canvas, cx: Float, cy: Float, s: Float, t: Double) {
        raio.color = 0xFF8FD3FF.toInt(); raio.strokeWidth = 2.6f * d
        for (i in 0 until 3) {
            val f = ((t * 1.4 + i * 0.33) % 1.0).toFloat()
            val x = cx - s * 0.4f + i * s * 0.4f; val y = cy + f * s * 0.9f
            raio.alpha = ((1 - f) * 255).toInt()
            c.drawLine(x, y, x - 2 * d, y + 7 * d, raio)
        }
        raio.alpha = 255
    }

    private fun chuva(c: Canvas, w: Float, hh: Float, t: Double) {
        raio.color = Cores.alpha(0xFFBFE6FF.toInt(), 0.35f); raio.strokeWidth = 1.4f * d
        for (i in 0 until gotas.size / 3) {
            val x0 = gotas[i * 3] * w; val vel = 0.6f + gotas[i * 3 + 2] * 0.8f
            val y = (((gotas[i * 3 + 1] + t * vel) % 1.0) * (hh + 40 * d) - 20 * d).toFloat()
            val x = x0 - y * 0.15f
            c.drawLine(x, y, x - 3 * d, y + 14 * d, raio)
        }
    }

    private fun estrelas(c: Canvas, w: Float, hh: Float, t: Double) {
        for (i in 0 until estrelas.size / 3) {
            val b = (0.5 + 0.5 * sin(t * (0.8 + estrelas[i * 3 + 2]) + i)).toFloat()
            p.color = Cores.alpha(Cores.BRANCO, 0.25f + 0.6f * b)
            c.drawCircle(estrelas[i * 3] * w, estrelas[i * 3 + 1] * hh, (0.7f + estrelas[i * 3 + 2] * 1.3f) * d, p)
        }
    }

    private fun lua(c: Canvas, cx: Float, cy: Float, s: Float) {
        p.shader = RadialGradient(cx, cy, s * 1.8f, intArrayOf(Cores.alpha(0xFFE8E0FF.toInt(), 0.35f), 0), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, s * 1.8f, p); p.shader = null
        p.color = 0xFFF1ECFF.toInt(); c.drawCircle(cx, cy, s * 0.62f, p)
        p.color = ClimaTema.cores(ClimaTema.Tipo.NOITE)[1]; c.drawCircle(cx + s * 0.28f, cy - s * 0.18f, s * 0.52f, p)
    }
}

// ---------------- Clima + notícias de Governador Valadares (componente 5, v0.1.5) ----------------
class NoticiasCard(private val ui: Ui, @Suppress("unused") private val lado: Boolean, private val a: AcoesPainel) {
    private val fundo = ClimaFundoView(ui.ctx)
    private val conteudo = ui.vbox(ui.dp(16))
    val view: FrameLayout = FrameLayout(ui.ctx).apply {
        addView(fundo, FrameLayout.LayoutParams(-1, -1))
        addView(conteudo, FrameLayout.LayoutParams(-1, -2))
        elevation = ui.dpf(14f)
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, ui.dpf(22f)) }
        }
        clipToOutline = true
        if (android.os.Build.VERSION.SDK_INT >= 28) { outlineSpotShadowColor = Cores.CIANO; outlineAmbientShadowColor = Cores.CIANO }
    }
    private val aviso = ui.text("", 12f, Cores.BRANCO).apply { visibility = View.GONE }
    private val temp = ui.text("—", 54f, Cores.BRANCO, bold = true).apply { setShadowLayer(18f, 0f, 2f, Cores.alpha(Cores.OBSIDIAN, 0.5f)) }
    private val desc = ui.text("", 16f, Cores.BRANCO, bold = true)
    private val minMax = ui.text("", 14f, Cores.alpha(Cores.BRANCO, 0.9f))
    private val chuva = ui.text("", 14f, Cores.BRANCO)
    private val clima = ui.vbox().apply { isClickable = true; isFocusable = true; setOnClickListener { a.previsaoHoras() } }
    private val ticker = FrameLayout(ui.ctx).apply { minimumHeight = ui.dp(64); isClickable = true; isFocusable = true }
    private val manchete = ui.truncar(ui.text("", 15f, Cores.BRANCO, bold = true), 2)
    private val fonte = ui.text("", 12f, Cores.alpha(Cores.BRANCO, 0.85f))
    private val contador = ui.text("", 12f, Cores.alpha(Cores.BRANCO, 0.85f))
    private var noticias: List<space.neural.luna.dashboard.Externo.Noticia> = emptyList()
    private var atual = 0
    private val h = Handler(Looper.getMainLooper())
    private val girar = object : Runnable {
        override fun run() { if (noticias.size > 1 && view.isShown) { mostrar((atual + 1) % noticias.size, true) }; h.postDelayed(this, 6000) }
    }

    init {
        val topo = ui.hbox()
        topo.addView(ui.icone(R.drawable.ic_news, Cores.BRANCO, 18))
        topo.addView(ui.text("GOVERNADOR VALADARES", 12f, Cores.BRANCO, bold = true).apply { letterSpacing = 0.12f }, ui.peso(1f, ui.dp(8)))
        topo.addView(ui.botaoIcone(R.drawable.ic_refresh, "Atualizar notícias e clima", Cores.BRANCO) { a.atualizarNoticias() })
        conteudo.addView(topo, ui.mp())
        conteudo.addView(aviso, ui.mp(ui.dp(2)))
        clima.addView(temp)
        clima.addView(desc)
        clima.addView(ui.hbox().apply { addView(minMax); addView(chuva, ui.wc(ui.dp(12))) }, ui.mp(ui.dp(4)))
        conteudo.addView(clima, ui.mp(ui.dp(2)))
        // ticker: uma manchete por vez, troca a cada 6 s (toque abre)
        val caixa = ui.vbox(ui.dp(12)).apply { background = ui.fundo(Cores.alpha(Cores.OBSIDIAN, 0.35f), Cores.alpha(Cores.BRANCO, 0.35f), 16f) }
        caixa.addView(ui.hbox().apply { addView(fonte, ui.peso(1f)); addView(contador) })
        caixa.addView(manchete, ui.mp(ui.dp(4)))
        ticker.addView(caixa, FrameLayout.LayoutParams(-1, -2))
        ticker.setOnClickListener { noticias.getOrNull(atual)?.let { a.abrirNoticia(it) } }
        conteudo.addView(ticker, ui.mp(ui.dp(12)))
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { h.removeCallbacks(girar); h.postDelayed(girar, 6000) }
            override fun onViewDetachedFromWindow(v: View) { h.removeCallbacks(girar) }
        })
    }

    private fun mostrar(i: Int, animar: Boolean) {
        val n = noticias.getOrNull(i) ?: return
        atual = i
        val aplicar = {
            val hora = if (n.quando > 0) java.time.Instant.ofEpochMilli(n.quando).atZone(java.time.ZoneId.systemDefault()).let { "%02d/%02d %02d:%02d".format(it.dayOfMonth, it.monthValue, it.hour, it.minute) } else ""
            fonte.text = (if (n.br381) "BR-381 · " else "") + "${n.fonte} · $hora"
            manchete.text = n.titulo
            contador.text = "${i + 1}/${noticias.size}"
            ticker.contentDescription = "Notícia ${i + 1} de ${noticias.size}: ${n.titulo}. ${n.fonte}. Toque para abrir"
        }
        if (animar && Anima.ligadas()) {
            ticker.animate().alpha(0f).translationY(-ui.dpf(10f)).setDuration(220).withEndAction {
                aplicar(); ticker.translationY = ui.dpf(10f)
                ticker.animate().alpha(1f).translationY(0f).setDuration(260).start()
            }.start()
        } else aplicar()
    }

    fun bind(d: Externo.Dados?, online: Boolean, carregando: Boolean, hora: Int = LocalTime.now().hour) {
        aviso.visibility = View.GONE
        if (d == null) {
            temp.text = "—"; desc.text = if (carregando) "Carregando clima…" else "Clima aparece com internet"; minMax.text = ""; chuva.text = ""
            noticias = emptyList(); fonte.text = ""; contador.text = ""
            manchete.text = if (carregando) "Carregando notícias…" else "Notícias aparecem quando houver internet."
            fundo.tipo = ClimaTema.tipo(3, hora)
            return
        }
        if (!online) {
            val hora = java.time.Instant.ofEpochMilli(d.atualizadoEm).atZone(java.time.ZoneId.systemDefault())
            val quando = if (hora.toLocalDate() == LocalDate.now()) "às %02d:%02d".format(hora.hour, hora.minute) else "em ${Fmt.dataCurta(hora.toLocalDate())}"
            aviso.text = "ⓘ Sem internet — última atualização $quando"; aviso.visibility = View.VISIBLE
        }
        val c = d.clima
        if (c != null) {
            val (_, ds) = Externo.descricaoTempo(c.codigo)
            fundo.tipo = ClimaTema.tipo(c.codigo, hora)
            fundo.comNuvem = c.codigo != 0
            temp.text = "${Math.round(c.tempC)}°"
            desc.text = ds
            minMax.text = "↓ ${Math.round(c.minC)}°  ↑ ${Math.round(c.maxC)}°"
            chuva.text = "☂ ${c.chuvaProx}% de chuva"
            clima.contentDescription = "Clima: ${Math.round(c.tempC)} graus, $ds. Mínima ${Math.round(c.minC)}, máxima ${Math.round(c.maxC)}. Chance de chuva ${c.chuvaProx} por cento. Toque para a previsão por hora"
        } else { temp.text = "—"; desc.text = "Sem dados de clima"; minMax.text = ""; chuva.text = "" }
        val mudou = d.noticias != noticias
        noticias = d.noticias
        if (noticias.isEmpty()) { fonte.text = ""; contador.text = ""; manchete.text = "Sem manchetes no momento." }
        else if (mudou) mostrar(0, false)
    }
}
