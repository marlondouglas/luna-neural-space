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

/** Anel 3D de meta: espessura sombreada, arco em degradê esmeralda→ciano animado, brilho neon ≥ 80%. */
class MetaRing(ctx: Context, private val sizeDp: Int = 88) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private var mostrado = 0f
    private var alvo = -1
    private var anim: ValueAnimator? = null
    private val trilho = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 10 * d }
    private val sombra = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 10 * d; strokeCap = Paint.Cap.ROUND; color = Cores.mix(Cores.ESMERALDA, Cores.OBSIDIAN, 0.6f) }
    private val arco = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 10 * d; strokeCap = Paint.Cap.ROUND }
    private val luz = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; strokeCap = Paint.Cap.ROUND; color = Cores.alpha(Cores.BRANCO, 0.45f) }
    private val brilho = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 18 * d }
    private val texto = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Cores.BRANCO; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val r = RectF()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun definir(p: Int) {
        if (p == alvo) return
        alvo = p
        anim?.cancel()
        if (!Anima.ligadas()) { mostrado = p.toFloat(); invalidate(); return }
        anim = ValueAnimator.ofFloat(mostrado, p.toFloat()).apply {
            duration = 1100; interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { mostrado = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(w: Int, h: Int) { val s = (sizeDp * d).toInt(); setMeasuredDimension(s, s) }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val cx = w / 2f; val cy = h / 2f
        arco.shader = SweepGradient(cx, cy, intArrayOf(Cores.ESMERALDA, Cores.CIANO, Cores.ESMERALDA), null).apply {
            setLocalMatrix(Matrix().apply { setRotate(-90f, cx, cy) })
        }
        trilho.shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), Cores.alpha(Cores.BRANCO, 0.06f), Cores.alpha(Cores.OBSIDIAN, 0.9f), Shader.TileMode.CLAMP)
        brilho.shader = RadialGradient(cx, cy, w / 2f, intArrayOf(0, Cores.alpha(Cores.VIOLETA, 0.45f), 0), floatArrayOf(0.55f, 0.78f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val pad = 11 * d
        r.set(pad, pad, width - pad, height - pad)
        if (alvo >= 80) c.drawArc(r, 0f, 360f, false, brilho)
        c.drawArc(r, 0f, 360f, false, trilho)
        val sweep = 360f * (mostrado.coerceIn(0f, 100f) / 100f)
        if (sweep > 0) {
            c.save(); c.translate(0f, 2.5f * d); c.drawArc(r, -90f, sweep, false, sombra); c.restore()
            c.drawArc(r, -90f, sweep, false, arco)
            val ri = RectF(r.left - 2.5f * d, r.top - 2.5f * d, r.right + 2.5f * d, r.bottom + 2.5f * d)
            c.drawArc(ri, -90f, sweep, false, luz)
        }
        texto.textSize = 17 * d
        c.drawText(if (alvo >= 100) "✓${mostrado.toInt()}%" else "${mostrado.toInt()}%", width / 2f, height / 2f + 6 * d, texto)
    }
}

/** Tipos usados pelo painel (dados de 7 dias por app) e cor de cada app. */
object GanhosChart {
    data class Dia(val rotulo: String, val porApp: Map<String, Long>)
    fun corApp(app: String) = CoresSerie.app(app)
}

/**
 * Orb da Luna: esfera 3D luminosa (sombreamento esférico, brilho especular, aurora e
 * galáxia por dentro, anéis girando em volta e partículas), com o avatar da Luna
 * (spec 1.3: mulher estilizada e adulta, boné LUNA; não é foto, robô nem criança).
 * Shaders criados só quando o tamanho muda; a cada quadro só giram matrizes.
 * Com "remover animações" ligado, fica parada.
 */
class LunaOrbView(ctx: Context, private val mostrarAvatar: Boolean = true) : View(ctx), space.neural.luna.avatar.LunaMotor.Alvo {
    /** v0.1.5: a Luna de verdade (foto animada, respira, pisca e fala) dentro da esfera; o desenho antigo é só reserva. */
    private val foto = space.neural.luna.avatar.LunaDesenho(ctx, 0.5f, 0.43f, 0.86f)
    private var comFoto = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val m = Matrix()
    private var fase = 0f
    private var anim: ValueAnimator? = null
    private var R = 0f; private var cx = 0f; private var cy = 0f
    private var halo: RadialGradient? = null
    private var esfera: RadialGradient? = null
    private var aurora: SweepGradient? = null
    private var sombraEsf: RadialGradient? = null
    private var especular: RadialGradient? = null
    private var aro: SweepGradient? = null
    private var piso: RadialGradient? = null
    private val clipEsf = Path()
    private val anel = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    /** Mais intensa quando a Luna está "pensando" ou o turno está correndo. */
    var ativa: Boolean = false
        set(v) { field = v; invalidate() }

    init { contentDescription = "Luna, sua assistente" }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        cx = w / 2f; cy = h / 2f * 0.94f; R = min(w, h) * 0.34f
        val t = Shader.TileMode.CLAMP
        halo = RadialGradient(cx, cy, R * 1.55f, intArrayOf(Cores.alpha(Cores.VIOLETA, 0.6f), Cores.alpha(Cores.MAGENTA, 0.18f), 0), floatArrayOf(0.6f, 0.82f, 1f), t)
        esfera = RadialGradient(cx - R * 0.35f, cy - R * 0.4f, R * 1.7f,
            intArrayOf(Cores.mix(Cores.VIOLETA, Cores.BRANCO, 0.25f), Cores.mix(Cores.VIOLETA, Cores.OBSIDIAN, 0.35f), Cores.mix(Cores.OBSIDIAN, Cores.VIOLETA, 0.1f)),
            floatArrayOf(0f, 0.45f, 1f), t)
        aurora = SweepGradient(cx, cy, intArrayOf(Cores.alpha(Cores.CIANO, 0.55f), 0, Cores.alpha(Cores.MAGENTA, 0.5f), 0, Cores.alpha(Cores.ESMERALDA, 0.45f), 0, Cores.alpha(Cores.CIANO, 0.55f)), null)
        sombraEsf = RadialGradient(cx - R * 0.3f, cy - R * 0.35f, R * 1.5f, intArrayOf(0, Cores.alpha(Cores.OBSIDIAN, 0.15f), Cores.alpha(Cores.OBSIDIAN, 0.92f)), floatArrayOf(0f, 0.5f, 1f), t)
        piso = RadialGradient(cx, cy + R * 1.32f, R * 1.1f, intArrayOf(Cores.alpha(Cores.VIOLETA, 0.55f), Cores.alpha(Cores.CIANO, 0.12f), 0), floatArrayOf(0f, 0.5f, 1f), t)
        especular = RadialGradient(cx - R * 0.42f, cy - R * 0.5f, R * 0.42f, intArrayOf(Cores.alpha(Cores.BRANCO, 0.75f), Cores.alpha(Cores.BRANCO, 0f)), null, t)
        aro = SweepGradient(cx, cy, intArrayOf(Cores.CIANO, Cores.VIOLETA, Cores.MAGENTA, Cores.CIANO), null)
        clipEsf.reset(); clipEsf.addCircle(cx, cy, R, Path.Direction.CW)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Anima.ligadas()) {
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 16_000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                addUpdateListener { fase = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); anim = null; space.neural.luna.avatar.LunaMotor.remover(this); super.onDetachedFromWindow() }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) anim?.resume() else anim?.pause()
        if (mostrarAvatar) {
            if (isVisible) { space.neural.luna.avatar.LunaMotor.animar = Anima.ligadas(); space.neural.luna.avatar.LunaMotor.registrar(context, this) }
            else space.neural.luna.avatar.LunaMotor.remover(this)
        }
    }

    override fun quadroNovo() { if (anim == null) invalidate() } // com o animador ligado, ele já redesenha a cada quadro

    /** Anel elíptico inclinado: [frente] desenha só a metade da frente da esfera. */
    private fun anelOrbital(c: Canvas, inclinacao: Float, escala: Float, cor: Int, frente: Boolean, giro: Float, larg: Float) {
        c.save(); c.rotate(inclinacao, cx, cy)
        rect.set(cx - R * escala, cy - R * escala * 0.3f, cx + R * escala, cy + R * escala * 0.3f)
        anel.strokeWidth = larg; anel.shader = null
        anel.color = Cores.alpha(cor, if (frente) 0.9f else 0.35f)
        anel.pathEffect = android.graphics.DashPathEffect(floatArrayOf(R * 0.5f, R * 0.12f, R * 0.08f, R * 0.12f), -giro * R * 6)
        c.drawArc(rect, if (frente) 0f else 180f, 180f, false, anel)
        anel.pathEffect = null
        // partícula correndo no anel
        val ang = (giro * 360f + if (frente) 0f else 180f) % 360f
        if ((ang in 0f..180f) == frente) {
            val rad = Math.toRadians(ang.toDouble())
            p.shader = null; p.color = Cores.BRANCO
            c.drawCircle(cx + (R * escala * Math.cos(rad)).toFloat(), cy + (R * escala * 0.3f * Math.sin(rad)).toFloat(), larg * 1.2f, p)
            p.color = Cores.alpha(cor, 0.4f)
            c.drawCircle(cx + (R * escala * Math.cos(rad)).toFloat(), cy + (R * escala * 0.3f * Math.sin(rad)).toFloat(), larg * 3f, p)
        }
        c.restore()
    }

    override fun onDraw(c: Canvas) {
        if (R <= 0f) return
        val u = R / 41f
        val respira = 1f + (if (ativa) 0.07f else 0.04f) * sin(fase * 2 * Math.PI * 8).toFloat()
        val larg = maxOf(1.2f * u, R * 0.03f)
        p.reset(); p.isAntiAlias = true

        // Halo que respira
        p.shader = halo
        c.save(); c.scale(respira, respira, cx, cy); c.drawCircle(cx, cy, R * 1.55f, p); c.restore()

        // Reflexo luminoso no "piso" (a esfera flutua sobre uma base de luz)
        p.shader = piso
        c.save(); c.scale(1f, 0.16f, cx, cy + R * 1.32f); c.drawCircle(cx, cy + R * 1.32f, R * 1.1f * (2f - respira), p); c.restore()

        // Anéis (metade de trás)
        anelOrbital(c, -18f, 1.42f, Cores.CIANO, false, fase * 2, larg)
        anelOrbital(c, 24f, 1.28f, Cores.MAGENTA, false, -fase * 3, larg * 0.8f)

        // Esfera
        p.shader = esfera; c.drawCircle(cx, cy, R, p)
        c.save(); c.clipPath(clipEsf)
        // Aurora girando dentro da esfera
        m.setRotate(fase * 360f, cx, cy); aurora?.setLocalMatrix(m)
        p.shader = aurora; c.drawCircle(cx, cy, R, p)
        p.shader = null
        // Faixas de aurora (ondas que correm, dão sensação de rotação)
        for (k in 0 until 3) {
            path.reset()
            val yb = cy - R * 0.5f + k * R * 0.42f
            val desl = (fase * 4 + k * 0.33f) % 1f * R * 2
            path.moveTo(cx - R * 2 + desl, yb)
            var x = cx - R * 2 + desl
            while (x < cx + R * 1.2f) { path.rQuadTo(R * 0.25f, -R * 0.18f, R * 0.5f, 0f); path.rQuadTo(R * 0.25f, R * 0.18f, R * 0.5f, 0f); x += R }
            anel.style = Paint.Style.STROKE; anel.strokeWidth = R * 0.12f; anel.pathEffect = null
            anel.color = Cores.alpha(listOf(Cores.CIANO, Cores.ESMERALDA, Cores.MAGENTA)[k], 0.22f)
            c.drawPath(path, anel)
        }
        // Estrelas
        for (i in 0 until 14) {
            val ang = (i * 47 + fase * 360 * (if (i % 2 == 0) 1 else -1)) * Math.PI / 180
            val rr = ((i * 13) % 37 + 3) / 40f * R
            p.color = Cores.alpha(if (i % 3 == 0) Cores.CIANO else Cores.BRANCO, 0.3f + 0.5f * ((sin(fase * 60 + i) + 1) / 2))
            c.drawCircle(cx + (rr * Math.cos(ang)).toFloat(), cy + (rr * Math.sin(ang) * 0.8).toFloat(), maxOf(0.8f, 0.9f * u), p)
        }
        comFoto = mostrarAvatar && foto.desenhar(c, cx, cy, R)
        if (mostrarAvatar && !comFoto) desenharAvatar(c, cx, cy + 4 * u, u)
        // Sombreamento esférico + brilho especular (mais suaves sobre a foto, para não escurecer o rosto)
        p.shader = sombraEsf; p.alpha = if (comFoto) 140 else 255; c.drawCircle(cx, cy, R, p)
        p.shader = especular; p.alpha = if (comFoto) 110 else 255; c.drawCircle(cx - R * 0.42f, cy - R * 0.5f, R * 0.42f, p)
        p.alpha = 255
        c.restore()
        // Aro luminoso
        m.setRotate(-fase * 720f, cx, cy); aro?.setLocalMatrix(m)
        anel.shader = aro; anel.pathEffect = null; anel.strokeWidth = larg * (if (ativa) 1.6f else 1.1f); anel.color = Cores.BRANCO
        c.drawCircle(cx, cy, R, anel); anel.shader = null

        // Anéis (metade da frente): só FORA do disco da Luna (aro + margem), para nunca cruzar o rosto
        disco.reset(); disco.addCircle(cx, cy, R + larg * 2.2f, Path.Direction.CW)
        c.save()
        if (android.os.Build.VERSION.SDK_INT >= 26) c.clipOutPath(disco)
        else @Suppress("DEPRECATION") c.clipPath(disco, android.graphics.Region.Op.DIFFERENCE)
        anelOrbital(c, -18f, 1.42f, Cores.CIANO, true, fase * 2, larg)
        anelOrbital(c, 24f, 1.28f, Cores.MAGENTA, true, -fase * 3, larg * 0.8f)
        c.restore()
    }
    private val disco = Path()

    private fun desenharAvatar(c: Canvas, cx: Float, cy0: Float, u: Float) {
        val bob = sin(fase * 2 * Math.PI * 6).toFloat() * 0.8f * u
        val cy = cy0 + bob
        c.save()
        
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
