package space.neural.luna.dashboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import android.view.animation.DecelerateInterpolator
import space.neural.luna.data.local.AppCorrida
import space.neural.luna.data.local.CategoriaGasto
import kotlin.math.max
import kotlin.math.min

/** Cores neon por app e por categoria de gasto. */
object CoresSerie {
    fun app(a: String) = when (a) {
        AppCorrida.NOVENTA_E_NOVE -> Cores.LARANJA
        AppCorrida.UBER -> Cores.CIANO
        AppCorrida.INDRIVE -> Cores.ESMERALDA
        AppCorrida.GOGETA -> Cores.MAGENTA
        AppCorrida.PARTICULAR -> Cores.AMARELO
        else -> Cores.BRANCO_70
    }
    fun categoria(c: String) = when (c) {
        CategoriaGasto.ENERGIA -> Cores.CIANO
        CategoriaGasto.MANUTENCAO -> Cores.LARANJA
        else -> Cores.MAGENTA
    }
    fun escurecer(c: Int, f: Float) = Cores.mix(c, Cores.OBSIDIAN, f)
    fun clarear(c: Int, f: Float) = Cores.mix(c, Cores.BRANCO, f)
}

/** Base: anima de 0 a 1 quando os dados mudam (desenho "entrando"). */
abstract class GraficoAnimado(ctx: Context) : View(ctx) {
    protected val d = ctx.resources.displayMetrics.density
    protected var prog = 1f
    private var anim: ValueAnimator? = null
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
    protected fun animar(dur: Long = 900) {
        anim?.cancel()
        if (!Anima.ligadas()) { prog = 1f; invalidate(); return }
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = dur; interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { prog = it.animatedValue as Float; invalidate() }
            start()
        }
    }
    protected val texto = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Cores.BRANCO_70; textSize = 11 * d }
}

/** Gráfico de área com linha neon brilhante, preenchimento em degradê e marcador do pico. */
class AreaGlow(ctx: Context) : GraficoAnimado(ctx) {
    private var pontos: List<Estatisticas.Ponto> = emptyList()
    private val linha = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 10 * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val area = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Cores.alpha(Cores.BRANCO, 0.07f); strokeWidth = d }
    private val marc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Cores.OBSIDIAN; textSize = 11 * d; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val p = Path(); private val pa = Path(); private val r = RectF()

    fun definir(novo: List<Estatisticas.Ponto>, descricao: String) {
        val mudou = novo.map { it.liquidoCentavos } != pontos.map { it.liquidoCentavos } || novo.size != pontos.size
        pontos = novo; contentDescription = descricao
        if (mudou) animar(1100) else invalidate()
    }

    /** Altura do gráfico (v0.1.5: maior no tablet, ocupando o espaço liberado). */
    var alturaDp = 190
        set(v) { field = v; requestLayout() }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, w), (alturaDp * d).toInt())

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        linha.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, intArrayOf(Cores.VIOLETA, Cores.CIANO, Cores.MAGENTA), null, Shader.TileMode.CLAMP)
        halo.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, intArrayOf(Cores.alpha(Cores.VIOLETA, 0.25f), Cores.alpha(Cores.CIANO, 0.25f), Cores.alpha(Cores.MAGENTA, 0.25f)), null, Shader.TileMode.CLAMP)
        area.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(Cores.alpha(Cores.CIANO, 0.45f), Cores.alpha(Cores.VIOLETA, 0.12f), Cores.alpha(Cores.VIOLETA, 0f)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        if (pontos.isEmpty()) return
        val esq = 44 * d; val dir = width - 10 * d; val topo = 26 * d; val base = height - 22 * d
        val vals = pontos.map { it.liquidoCentavos }
        val maxV = max(vals.max(), 1L); val minV = min(vals.min(), 0L)
        fun y(v: Long) = base - (base - topo) * ((v - minV).toFloat() / (maxV - minV).coerceAtLeast(1))
        val passo = if (pontos.size > 1) (dir - esq) / (pontos.size - 1) else 0f
        fun x(i: Int) = if (pontos.size > 1) esq + passo * i else (esq + dir) / 2
        // grade + rótulos do eixo
        texto.textAlign = Paint.Align.RIGHT
        for (i in 0..3) {
            val v = minV + (maxV - minV) * i / 3
            val yy = y(v); c.drawLine(esq, yy, dir, yy, grade)
            c.drawText(Fmt.reaisCurto(v).replace("R$ ", ""), esq - 6 * d, yy + 4 * d, texto)
        }
        texto.textAlign = Paint.Align.CENTER
        val cada = max(1, pontos.size / 8)
        pontos.forEachIndexed { i, pt -> if (i % cada == 0 || i == pontos.lastIndex) { texto.color = if (pt.atual) Cores.CIANO else Cores.BRANCO_70; c.drawText(pt.rotulo, x(i), height - 6 * d, texto) } }
        texto.color = Cores.BRANCO_70
        // curva suave
        p.reset()
        pontos.indices.forEach { i ->
            val xi = x(i); val yi = y(vals[i])
            if (i == 0) p.moveTo(xi, yi) else {
                val xp = x(i - 1); val yp = y(vals[i - 1]); val cx = (xp + xi) / 2
                p.cubicTo(cx, yp, cx, yi, xi, yi)
            }
        }
        pa.set(p); pa.lineTo(x(pontos.lastIndex), base); pa.lineTo(x(0), base); pa.close()
        c.save(); c.clipRect(0f, 0f, esq + (dir - esq) * prog + 2 * d, height.toFloat())
        c.drawPath(pa, area); c.drawPath(p, halo); c.drawPath(p, linha)
        c.restore()
        // pico
        val iPico = vals.indices.maxByOrNull { vals[it] } ?: return
        if (vals[iPico] > 0 && prog > 0.95f) {
            val px = x(iPico); val py = y(vals[iPico])
            marc.color = Cores.alpha(Cores.CIANO, 0.25f); c.drawCircle(px, py, 10 * d, marc)
            marc.color = Cores.BRANCO; c.drawCircle(px, py, 4.5f * d, marc)
            val t = "PICO " + Fmt.reaisCurto(vals[iPico])
            val tw = pillTxt.measureText(t) + 14 * d
            val cx = px.coerceIn(esq + tw / 2, dir - tw / 2)
            r.set(cx - tw / 2, py - 30 * d, cx + tw / 2, py - 12 * d)
            if (r.top < 0) r.offset(0f, -r.top)
            pill.color = Cores.CIANO; c.drawRoundRect(r, 9 * d, 9 * d, pill)
            c.drawText(t, cx, r.bottom - 5 * d, pillTxt)
        }
    }
}

/** Rosca 3D: anel grosso com face inferior escura (espessura), brilho na face de cima e total no centro. */
class Donut3D(ctx: Context) : GraficoAnimado(ctx) {
    data class Fatia(val rotulo: String, val valor: Long, val cor: Int)
    private var fatias: List<Fatia> = emptyList()
    private var titulo = ""; private var valorCentro = ""
    private val arco = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val brilho = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val cTit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Cores.BRANCO_70; textAlign = Paint.Align.CENTER }
    private val cVal = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Cores.BRANCO; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val r = RectF()

    fun definir(f: List<Fatia>, tituloCentro: String, valor: String, descricao: String) {
        val mudou = f != fatias
        fatias = f; titulo = tituloCentro; valorCentro = valor; contentDescription = descricao
        if (mudou) animar(1000) else invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = min(MeasureSpec.getSize(w).takeIf { it > 0 } ?: (150 * d).toInt(), (150 * d).toInt())
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        val esp = s * 0.15f; val prof = s * 0.05f
        val cx = width / 2f; val cy = height / 2f - prof / 2
        val rad = s / 2 - esp / 2 - prof / 2 - 3 * d
        arco.strokeWidth = esp; brilho.strokeWidth = esp * 0.28f
        val total = fatias.sumOf { it.valor }
        // trilho
        r.set(cx - rad, cy - rad + prof, cx + rad, cy + rad + prof)
        arco.color = Cores.alpha(Cores.OBSIDIAN, 0.9f); c.drawArc(r, 0f, 360f, false, arco)
        r.set(cx - rad, cy - rad, cx + rad, cy + rad)
        arco.color = Cores.alpha(Cores.BRANCO, 0.08f); c.drawArc(r, 0f, 360f, false, arco)
        if (total > 0) {
            val gap = if (fatias.count { it.valor > 0 } > 1) 2.5f else 0f
            // face de baixo (espessura)
            var ang = -90f
            fatias.filter { it.valor > 0 }.forEach { f ->
                val sw = 360f * f.valor / total * prog
                r.set(cx - rad, cy - rad + prof, cx + rad, cy + rad + prof)
                arco.color = CoresSerie.escurecer(f.cor, 0.55f); c.drawArc(r, ang, (sw - gap).coerceAtLeast(0.5f), false, arco)
                ang += sw
            }
            ang = -90f
            fatias.filter { it.valor > 0 }.forEach { f ->
                val sw = 360f * f.valor / total * prog
                r.set(cx - rad, cy - rad, cx + rad, cy + rad)
                arco.shader = LinearGradient(cx, cy - rad, cx, cy + rad, CoresSerie.clarear(f.cor, 0.15f), CoresSerie.escurecer(f.cor, 0.2f), Shader.TileMode.CLAMP)
                c.drawArc(r, ang, (sw - gap).coerceAtLeast(0.5f), false, arco)
                arco.shader = null
                // brilho interno (borda de cima do anel)
                val rb = rad + esp * 0.3f
                r.set(cx - rb, cy - rb, cx + rb, cy + rb)
                brilho.color = Cores.alpha(Cores.BRANCO, 0.22f)
                c.drawArc(r, ang, (sw - gap).coerceAtLeast(0.5f), false, brilho)
                ang += sw
            }
        }
        cTit.textSize = s * 0.075f; cVal.textSize = s * 0.12f
        var vs = cVal.textSize
        while (cVal.measureText(valorCentro) > rad * 1.5f && vs > 8 * d) { vs -= d; cVal.textSize = vs }
        c.drawText(titulo, cx, cy - s * 0.02f, cTit)
        c.drawText(valorCentro, cx, cy + s * 0.11f, cVal)
    }
}

/** Barras 3D empilhadas por app: face frontal em degradê, lateral e topo sombreados, e reflexo no chão. */
class Barras3D(ctx: Context) : GraficoAnimado(ctx) {
    private var pontos: List<Estatisticas.Ponto> = emptyList()
    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lado = Paint(Paint.ANTI_ALIAS_FLAG)
    private val topoP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val refl = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chao = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Cores.alpha(Cores.BRANCO, 0.10f); strokeWidth = d }
    private val path = Path()

    fun definir(novo: List<Estatisticas.Ponto>, descricao: String) {
        val mudou = novo.map { it.porApp } != pontos.map { it.porApp }
        pontos = novo; contentDescription = descricao
        if (mudou) animar(1000) else invalidate()
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, w), (170 * d).toInt())

    override fun onDraw(c: Canvas) {
        if (pontos.isEmpty()) return
        val base = height - 40 * d; val topo = 10 * d
        val max = pontos.maxOf { it.porApp.values.sum() }.coerceAtLeast(1)
        val slot = width / pontos.size.toFloat()
        val bw = min(slot * 0.5f, 26 * d); val dx = bw * 0.28f; val dy = bw * 0.2f
        c.drawLine(0f, base, width.toFloat(), base, chao)
        texto.textAlign = Paint.Align.CENTER
        val cada = max(1, pontos.size / 10)
        pontos.forEachIndexed { i, pt ->
            val x0 = slot * i + (slot - bw - dx) / 2
            var y = base
            for (app in Calculos.APPS) {
                val v = pt.porApp[app] ?: 0
                if (v <= 0) continue
                val h = (base - topo - dy) * (v.toFloat() / max) * prog
                val cor = CoresSerie.app(app)
                face.shader = LinearGradient(x0, 0f, x0 + bw, 0f, CoresSerie.clarear(cor, 0.2f), CoresSerie.escurecer(cor, 0.15f), Shader.TileMode.CLAMP)
                c.drawRect(x0, y - h, x0 + bw, y, face)
                // lateral
                lado.color = CoresSerie.escurecer(cor, 0.5f)
                path.reset(); path.moveTo(x0 + bw, y); path.lineTo(x0 + bw + dx, y - dy); path.lineTo(x0 + bw + dx, y - h - dy); path.lineTo(x0 + bw, y - h); path.close()
                c.drawPath(path, lado)
                // topo
                topoP.color = CoresSerie.clarear(cor, 0.35f)
                path.reset(); path.moveTo(x0, y - h); path.lineTo(x0 + dx, y - h - dy); path.lineTo(x0 + bw + dx, y - h - dy); path.lineTo(x0 + bw, y - h); path.close()
                c.drawPath(path, topoP)
                // reflexo (só do primeiro bloco, apoiado no chão)
                if (y == base) {
                    val rh = min(h, 18 * d)
                    refl.shader = LinearGradient(0f, base, 0f, base + rh, Cores.alpha(cor, 0.35f), Cores.alpha(cor, 0f), Shader.TileMode.CLAMP)
                    c.drawRect(x0, base + 1 * d, x0 + bw, base + rh, refl)
                }
                y -= h
            }
            if (i % cada == 0 || i == pontos.lastIndex) {
                texto.color = if (pt.atual) Cores.CIANO else Cores.BRANCO_70
                c.drawText(pt.rotulo, slot * i + slot / 2, height - 6 * d, texto)
            }
        }
        texto.color = Cores.BRANCO_70
    }
}

/** Barra horizontal 3D (ranking): degradê, brilho no topo e ponta luminosa. */
class BarraH3D(ctx: Context) : GraficoAnimado(ctx) {
    private var frac = 0f; private var cor = Cores.VIOLETA
    private val trilho = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Cores.alpha(Cores.BRANCO, 0.08f) }
    private val barra = Paint(Paint.ANTI_ALIAS_FLAG)
    private val luz = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun definir(f: Float, c: Int) { val m = f != frac || c != cor; frac = f; cor = c; if (m) animar(900) else invalidate() }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, w), (16 * d).toInt())
    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val rad = h / 2
        r.set(0f, 0f, width.toFloat(), h); c.drawRoundRect(r, rad, rad, trilho)
        val w = width * frac.coerceIn(0f, 1f) * prog
        if (w <= 0) return
        r.set(0f, 0f, max(w, h), h)
        barra.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(CoresSerie.clarear(cor, 0.35f), cor, CoresSerie.escurecer(cor, 0.35f)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, rad, rad, barra)
        luz.color = Cores.alpha(Cores.BRANCO, 0.35f)
        r.set(rad / 2, h * 0.15f, max(w, h) - rad / 2, h * 0.38f); c.drawRoundRect(r, h * 0.12f, h * 0.12f, luz)
        luz.color = Cores.alpha(cor, 0.5f); c.drawCircle(max(w, h) - rad, rad, rad * 1.1f, luz)
    }
}
