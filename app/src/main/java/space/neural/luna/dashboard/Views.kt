package space.neural.luna.dashboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.TextView
import space.neural.luna.data.local.AppCorrida
import kotlin.math.min
import kotlin.math.sin

/** Animações respeitam "remover animações" do Android. */
object Anima {
    fun ligadas() = ValueAnimator.areAnimatorsEnabled()

    /** Números que contam até o valor novo (count-up). */
    fun contar(tv: TextView, de: Long, para: Long, fmt: (Long) -> String, duracao: Long = 700) {
        val anterior = tv.getTag(TAG) as? ValueAnimator
        anterior?.cancel()
        if (!ligadas() || de == para) { tv.text = fmt(para); return }
        val a = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = duracao; interpolator = DecelerateInterpolator()
            addUpdateListener { tv.text = fmt(de + ((para - de) * (it.animatedValue as Float)).toLong()) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { tv.text = fmt(para) }
            })
        }
        tv.setTag(TAG, a); a.start()
    }

    /** Entrada dos cards: sobe e aparece, em sequência. */
    fun entrada(v: View, ordem: Int) {
        if (!ligadas()) return
        v.alpha = 0f; v.translationY = 24 * v.resources.displayMetrics.density
        v.animate().alpha(1f).translationY(0f).setStartDelay(60L * ordem).setDuration(380)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private val TAG = "contador".hashCode()
}

/** Anel de progresso de meta: trilho branco gelo 15%, arco esmeralda animado, brilho violeta ≥ 80%. */
class MetaRing(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private var mostrado = 0f
    private var alvo = 0
    private var anim: ValueAnimator? = null
    private val trilho = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Cores.alpha(Cores.BRANCO, 0.15f); strokeWidth = 8 * d
    }
    private val arco = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Cores.ESMERALDA; strokeWidth = 8 * d; strokeCap = Paint.Cap.ROUND
    }
    private val brilho = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Cores.alpha(Cores.VIOLETA, 0.35f); strokeWidth = 14 * d
    }
    private val texto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Cores.BRANCO; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val r = RectF()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun definir(p: Int) {
        if (p == alvo && anim != null) return
        alvo = p
        anim?.cancel()
        if (!Anima.ligadas()) { mostrado = p.toFloat(); invalidate(); return }
        anim = ValueAnimator.ofFloat(mostrado, p.toFloat()).apply {
            duration = 900; interpolator = DecelerateInterpolator()
            addUpdateListener { mostrado = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = (84 * d).toInt(); setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val pad = 9 * d
        r.set(pad, pad, width - pad, height - pad)
        if (alvo >= 80) c.drawArc(r, 0f, 360f, false, brilho)
        c.drawArc(r, 0f, 360f, false, trilho)
        val sweep = 360f * (mostrado.coerceIn(0f, 100f) / 100f)
        if (sweep > 0) c.drawArc(r, -90f, sweep, false, arco)
        texto.textSize = 18 * d
        val t = if (alvo >= 100) "✓ ${mostrado.toInt()}%" else "${mostrado.toInt()}%"
        c.drawText(t, width / 2f, height / 2f + 6 * d, texto)
    }
}

/** Gráfico de barras empilhadas dos últimos 7 dias por app (99 violeta, Uber ciano, InDrive esmeralda). */
class GanhosChart(ctx: Context) : View(ctx) {
    data class Dia(val rotulo: String, val porApp: Map<String, Long>)

    private val d = ctx.resources.displayMetrics.density
    private var dias: List<Dia> = emptyList()
    private var progresso = 1f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Cores.BRANCO_70; textAlign = Paint.Align.CENTER }
    private val grade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Cores.alpha(Cores.BRANCO, 0.08f); strokeWidth = d }
    private val r = RectF()

    fun definir(novo: List<Dia>, descricao: String) {
        val mudou = novo != dias
        dias = novo; contentDescription = descricao
        if (mudou && Anima.ligadas()) {
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 900; interpolator = DecelerateInterpolator()
                addUpdateListener { progresso = it.animatedValue as Float; invalidate() }
                start()
            }
        } else { progresso = 1f; invalidate() }
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, w), (150 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        if (dias.isEmpty()) return
        val base = height - 22 * d
        val topo = 8 * d
        for (i in 0..3) { val y = topo + (base - topo) * i / 3f; c.drawLine(0f, y, width.toFloat(), y, grade) }
        val max = dias.maxOf { it.porApp.values.sum() }.coerceAtLeast(1)
        val slot = width / dias.size.toFloat()
        val bw = min(slot * 0.55f, 28 * d)
        txt.textSize = 11 * d
        dias.forEachIndexed { i, dia ->
            val cx = slot * i + slot / 2
            var y = base
            for (app in Calculos.APPS) {
                val v = dia.porApp[app] ?: 0
                if (v <= 0) continue
                val h = (base - topo) * (v.toFloat() / max) * progresso
                p.color = corApp(app)
                r.set(cx - bw / 2, y - h, cx + bw / 2, y)
                c.drawRoundRect(r, 4 * d, 4 * d, p)
                y -= h + 1.5f * d
            }
            c.drawText(dia.rotulo, cx, height - 6 * d, txt)
        }
    }

    companion object {
        fun corApp(app: String) = when (app) {
            AppCorrida.NOVENTA_E_NOVE -> Cores.VIOLETA
            AppCorrida.UBER -> Cores.CIANO
            else -> Cores.ESMERALDA
        }
    }
}

/**
 * Orb da Luna com o avatar dentro (spec 1.3): mulher estilizada e adulta com
 * boné LUNA, desenhada em vetor só com as cores da paleta (não é foto, robô nem
 * criança). Em volta, um orb luminoso estilo aurora/galáxia que respira e gira
 * devagar. Shaders criados uma vez por tamanho (leve para a central do carro).
 * Com "remover animações" ligado, fica parado.
 */
class LunaOrbView(ctx: Context, private val mostrarAvatar: Boolean = true) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val m = Matrix()
    private var fase = 0f
    private var anim: ValueAnimator? = null
    private var aurora: SweepGradient? = null
    private var aurora2: SweepGradient? = null
    private var halo: RadialGradient? = null
    private var nucleo: RadialGradient? = null
    /** Fica mais intenso quando a Luna está "pensando" ou falando. */
    var ativa: Boolean = false
        set(v) { field = v; invalidate() }

    init { contentDescription = "Luna, sua assistente" }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val cx = w / 2f; val cy = h / 2f; val r = min(w, h) / 2f
        aurora = SweepGradient(cx, cy, intArrayOf(
            Cores.VIOLETA, Cores.CIANO, Cores.ESMERALDA, Cores.alpha(Cores.VIOLETA, 0.1f), Cores.CIANO, Cores.VIOLETA,
        ), null)
        aurora2 = SweepGradient(cx, cy, intArrayOf(
            Cores.alpha(Cores.ESMERALDA, 0f), Cores.alpha(Cores.CIANO, 0.7f), Cores.alpha(Cores.VIOLETA, 0f),
            Cores.alpha(Cores.VIOLETA, 0.7f), Cores.alpha(Cores.ESMERALDA, 0f),
        ), null)
        halo = RadialGradient(cx, cy, r, intArrayOf(
            Cores.alpha(Cores.VIOLETA, 0.55f), Cores.alpha(Cores.CIANO, 0.18f), Cores.alpha(Cores.OBSIDIAN, 0f),
        ), floatArrayOf(0.55f, 0.8f, 1f), Shader.TileMode.CLAMP)
        nucleo = RadialGradient(cx, cy - r * 0.2f, r * 0.8f, intArrayOf(
            Cores.mix(Cores.OBSIDIAN, Cores.VIOLETA, 0.45f), Cores.mix(Cores.OBSIDIAN, Cores.VIOLETA, 0.15f), Cores.OBSIDIAN,
        ), null, Shader.TileMode.CLAMP)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Anima.ligadas()) {
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 12_000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                addUpdateListener { fase = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); anim = null; super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        val cx = width / 2f; val cy = height / 2f
        val u = s / 120f
        val respira = 1f + (if (ativa) 0.06f else 0.035f) * sin(fase * 2 * Math.PI * 6).toFloat()

        // Halo que respira
        p.reset(); p.isAntiAlias = true
        p.shader = halo
        c.save(); c.scale(respira, respira, cx, cy); c.drawCircle(cx, cy, 60 * u, p); c.restore()

        // Anéis de aurora girando em sentidos opostos
        p.style = Paint.Style.STROKE
        m.setRotate(fase * 360f, cx, cy); aurora?.setLocalMatrix(m)
        p.shader = aurora; p.strokeWidth = (if (ativa) 5f else 3.5f) * u
        c.drawCircle(cx, cy, 44 * u * respira, p)
        m.setRotate(-fase * 720f, cx, cy); aurora2?.setLocalMatrix(m)
        p.shader = aurora2; p.strokeWidth = 7 * u
        c.drawCircle(cx, cy, 48 * u * respira, p)

        // Núcleo (galáxia) com estrelas
        p.style = Paint.Style.FILL; p.shader = nucleo
        c.drawCircle(cx, cy, 41 * u, p)
        p.shader = null
        for (i in 0 until 9) {
            val ang = (i * 40 + fase * 360 * (if (i % 2 == 0) 1 else -1)) * Math.PI / 180
            val rr = (18 + (i * 7) % 20) * u
            p.color = Cores.alpha(if (i % 3 == 0) Cores.CIANO else Cores.BRANCO, 0.25f + 0.5f * ((sin(fase * 40 + i) + 1) / 2))
            c.drawCircle(cx + (rr * Math.cos(ang)).toFloat(), cy + (rr * Math.sin(ang)).toFloat(), 0.9f * u, p)
        }
        if (mostrarAvatar) desenharAvatar(c, cx, cy + 4 * u, u * 1.0f)
    }

    private fun desenharAvatar(c: Canvas, cx: Float, cy0: Float, u: Float) {
        val bob = sin(fase * 2 * Math.PI * 6).toFloat() * 0.8f * u
        val cy = cy0 + bob
        c.save()
        path.reset(); path.addCircle(cx, cy0 - 4 * u, 41 * u, Path.Direction.CW); c.clipPath(path)
        // Cabelo longo (violeta), atrás do rosto
        p.color = Cores.VIOLETA
        path.reset()
        path.moveTo(cx - 20 * u, cy - 8 * u)
        path.cubicTo(cx - 27 * u, cy + 14 * u, cx - 23 * u, cy + 28 * u, cx - 14 * u, cy + 32 * u)
        path.lineTo(cx + 14 * u, cy + 32 * u)
        path.cubicTo(cx + 23 * u, cy + 28 * u, cx + 27 * u, cy + 14 * u, cx + 20 * u, cy - 8 * u)
        path.close(); c.drawPath(path, p)
        // Ombros (jaqueta ciano)
        p.color = Cores.alpha(Cores.BRANCO, 0.85f)
        rect.set(cx - 5 * u, cy + 12 * u, cx + 5 * u, cy + 24 * u); c.drawRect(rect, p)
        p.color = Cores.CIANO
        rect.set(cx - 26 * u, cy + 22 * u, cx + 26 * u, cy + 52 * u); c.drawOval(rect, p)
        p.color = Cores.alpha(Cores.OBSIDIAN, 0.5f)
        path.reset(); path.moveTo(cx - 6 * u, cy + 22 * u); path.lineTo(cx, cy + 32 * u); path.lineTo(cx + 6 * u, cy + 22 * u); path.close()
        c.drawPath(path, p)
        // Rosto (oval adulto)
        p.color = Cores.BRANCO
        rect.set(cx - 15 * u, cy - 12 * u, cx + 15 * u, cy + 19 * u); c.drawOval(rect, p)
        // Franja lateral
        p.color = Cores.VIOLETA
        path.reset()
        path.moveTo(cx - 16 * u, cy - 4 * u)
        path.quadTo(cx - 5 * u, cy - 1 * u, cx + 4 * u, cy - 10 * u)
        path.lineTo(cx - 15 * u, cy - 12 * u); path.close(); c.drawPath(path, p)
        // Olhos com cílios (piscam a cada ~3 s)
        val piscando = ((fase * 12_000).toInt() % 3000) < 140
        p.color = Cores.OBSIDIAN
        val eh = if (piscando) 0.5f * u else 2.6f * u
        rect.set(cx - 8.5f * u, cy + 3 * u - eh, cx - 3.5f * u, cy + 3 * u + eh); c.drawOval(rect, p)
        rect.set(cx + 3.5f * u, cy + 3 * u - eh, cx + 8.5f * u, cy + 3 * u + eh); c.drawOval(rect, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.1f * u
        c.drawLine(cx - 9 * u, cy + 1.5f * u, cx - 11 * u, cy - 0.5f * u, p)
        c.drawLine(cx + 9 * u, cy + 1.5f * u, cx + 11 * u, cy - 0.5f * u, p)
        // Sorriso
        p.color = Cores.VIOLETA; p.strokeWidth = 1.6f * u; p.strokeCap = Paint.Cap.ROUND
        rect.set(cx - 5 * u, cy + 7 * u, cx + 5 * u, cy + 13 * u); c.drawArc(rect, 20f, 140f, false, p)
        p.style = Paint.Style.FILL
        // Brincos
        p.color = Cores.ESMERALDA
        c.drawCircle(cx - 15 * u, cy + 9 * u, 1.7f * u, p); c.drawCircle(cx + 15 * u, cy + 9 * u, 1.7f * u, p)
        // Boné LUNA (copa obsidian/violeta, aba violeta, texto ciano)
        p.color = Cores.mix(Cores.OBSIDIAN, Cores.VIOLETA, 0.35f)
        rect.set(cx - 18 * u, cy - 26 * u, cx + 18 * u, cy - 1 * u); c.drawArc(rect, 180f, 180f, true, p)
        p.color = Cores.VIOLETA
        rect.set(cx - 20 * u, cy - 16 * u, cx + 23 * u, cy - 11 * u); c.drawRoundRect(rect, 2.5f * u, 2.5f * u, p)
        p.color = Cores.CIANO; p.textAlign = Paint.Align.CENTER; p.textSize = 7 * u
        p.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        c.drawText("LUNA", cx, cy - 17.5f * u, p)
        c.restore()
    }
}
