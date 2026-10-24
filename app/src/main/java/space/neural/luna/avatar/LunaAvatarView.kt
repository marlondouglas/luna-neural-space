package space.neural.luna.avatar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.View
import space.neural.luna.dashboard.Anima
import space.neural.luna.dashboard.Cores
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Desenha o quadro atual da Luna (do [LunaMotor]) dentro de um círculo, com o movimento global da cabeça
 * (rotação/deslocamento/respiração) aplicado por drawBitmapMesh numa grade de 24x24.
 * [foco] e [zoom]: qual parte do retrato aparece (0..1 no recorte 448x448; zoom 1 = recorte inteiro).
 */
class LunaDesenho(private val ctx: Context, var focoX: Float = 0.5f, var focoY: Float = 0.5f, var zoom: Float = 0.9f) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private var verts = FloatArray(0)
    private val clip = Path()

    fun desenhar(c: Canvas, cx: Float, cy: Float, raio: Float, retrato: Boolean = false): Boolean {
        val r = LunaMotor.rigDe(ctx) ?: return false
        val bmp = LunaMotor.quadro ?: return false
        val g = r.grid; val n = r.size.toFloat(); val s = r.s
        if (verts.size != (g + 1) * (g + 1) * 2) verts = FloatArray((g + 1) * (g + 1) * 2)
        val h = LunaMotor.cabeca
        val a = Math.toRadians(h.rot.toDouble()); val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
        val tX = h.tx * s; val tY = h.ty * s - h.breath * 2.5f * s
        val esc = 2 * raio / (n * zoom)
        val fx = focoX * n; val fy = focoY * n
        var k = 0
        for (j in 0..g) for (i in 0..g) {
            val px = i * n / g; val py = j * n / g
            val hw = r.hw[j * (g + 1) + i]; val bw = r.bw[j * (g + 1) + i]
            val dx = px - r.pivX; val dy = py - r.pivY
            val hx = r.pivX + tX + ca * dx - sa * dy; val hy = r.pivY + tY + sa * dx + ca * dy
            val x = px * (1 - hw) + hx * hw
            val y = py * (1 - hw) + hy * hw - h.breath * 4f * s * bw * (1 - hw)
            verts[k++] = cx + (x - fx) * esc; verts[k++] = cy + (y - fy) * esc
        }
        c.save()
        clip.reset()
        if (retrato) clip.addRoundRect(cx - raio, cy - raio, cx + raio, cy + raio, raio * .12f, raio * .12f, Path.Direction.CW)
        else clip.addCircle(cx, cy, raio, Path.Direction.CW)
        c.clipPath(clip)
        c.drawBitmapMesh(bmp, g, g, verts, 0, null, 0, paint)
        c.restore()
        return true
    }
}

/** Animated Luna, circular in compact cards or framed as a portrait in the conversation. */
class LunaAvatarView(ctx: Context, focoY: Float = 0.47f, zoom: Float = 0.78f, private val retrato: Boolean = false) : View(ctx), LunaMotor.Alvo {
    private val desenho = LunaDesenho(ctx, 0.5f, focoY, zoom)
    private val aro = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()
    private var sweep: SweepGradient? = null
    private var visivel = false
    /** Aro mais forte quando a Luna está ouvindo/pensando/falando. */
    var ativa = false
        set(v) { field = v; invalidate() }

    /** v0.1.5: microfone aberto (conversa contínua): brilho ciano pulsando em volta. */
    var ouvindo = false
        set(v) { field = v; contentDescription = if (v) "Luna está ouvindo. Toque para parar" else "Luna, sua assistente"; invalidate() }
    private val brilho = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    init { contentDescription = "Luna, sua assistente" }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        sweep = SweepGradient(w / 2f, h / 2f, intArrayOf(Cores.CIANO, Cores.VIOLETA, Cores.MAGENTA, Cores.CIANO), null)
        val r = min(w, h) / 2f
        halo.shader = RadialGradient(w / 2f, h / 2f, r, intArrayOf(Cores.alpha(Cores.VIOLETA, 0.55f), Cores.alpha(Cores.MAGENTA, 0.15f), 0), floatArrayOf(0.7f, 0.88f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        visivel = isVisible
        if (isVisible) { LunaMotor.animar = Anima.ligadas(); LunaMotor.registrar(context, this) } else LunaMotor.remover(this)
    }

    override fun onDetachedFromWindow() { LunaMotor.remover(this); super.onDetachedFromWindow() }

    override fun quadroNovo() { if (visivel) invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = min(w, h) / 2f
        val cx = w / 2; val cy = h / 2
        if (retrato) {
            val side = r * .94f
            desenho.desenhar(c, cx, cy, side, retrato = true)
            aro.shader = null
            aro.color = if (ouvindo) Cores.CIANO else if (ativa || LunaMotor.falando) Cores.MAGENTA else Cores.VIOLETA
            aro.strokeWidth = maxOf(2f, resources.displayMetrics.density * 1.5f)
            c.drawRoundRect(cx - side, cy - side, cx + side, cy + side, side * .12f, side * .12f, aro)
            return
        }
        c.drawCircle(cx, cy, r, halo)
        val larg = maxOf(2f, r * (if (ativa || LunaMotor.falando) 0.075f else 0.05f))
        val ri = r * 0.86f
        if (!desenho.desenhar(c, cx, cy, ri)) { aro.shader = null; aro.color = Cores.VIOLETA }
        m.setRotate((System.nanoTime() / 1e7 % 360).toFloat(), cx, cy); sweep?.setLocalMatrix(m)
        aro.shader = sweep; aro.strokeWidth = larg
        c.drawCircle(cx, cy, ri + larg / 2, aro)
        if (ouvindo) {
            val f = (0.5 + 0.5 * kotlin.math.sin(System.nanoTime() / 1e9 * 2 * Math.PI / 1.4)).toFloat()
            brilho.color = Cores.alpha(Cores.CIANO, 0.35f + 0.5f * f); brilho.strokeWidth = larg * (1.2f + f)
            brilho.setShadowLayer(r * 0.12f, 0f, 0f, Cores.CIANO)
            c.drawCircle(cx, cy, ri + larg * 1.6f, brilho)
            if (!LunaMotor.animar || !visivel) postInvalidateDelayed(50)
        }
    }
}
