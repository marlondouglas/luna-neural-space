package space.neural.luna.avatar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * O "rig" da Luna (foto real + malha), exportado de luna-avatar/export_android.py para assets/luna/:
 * textura sem o microfone (clean), camada rígida (óculos + headset), malha de 125 pontos / 217 triângulos,
 * deltas das poses de boca reais (A, E/I, O/U), interiores reais da boca e sprites de piscada/sobrancelha.
 * Desenha um quadro do rosto num Bitmap (Canvas por software, que aceita drawVertices em qualquer API).
 */
class LunaRig private constructor(
    val size: Int, val s: Float,
    private val base: Bitmap, clean: Bitmap, private val overlay: Bitmap,
    private val src: FloatArray, private val tris: ShortArray,
    private val upIdx: IntArray, private val loIdx: IntArray, private val loInner: IntArray, private val fixed: IntArray,
    private val mouthPts: IntArray, private val cheek0: Int, private val ring0: Int, private val mcY: Float,
    private val jaw: Map<Int, Float>, private val refs: List<Ref?>, private val cav: Int,
    private val upRect: IntArray, private val upSprites: Array<Array<Bitmap>>, private val blinkLv: FloatArray, private val browLv: FloatArray,
    private val eyes: List<FloatArray>,
    val grid: Int, val hw: FloatArray, val bw: FloatArray, val pivX: Float, val pivY: Float,
) {
    class Ref(val img: Bitmap, val ou: FloatArray, val ol: FloatArray, val dlt: FloatArray, val ox: Float, val oy: Float) {
        val shader = BitmapShader(img, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }

    private val n = src.size / 2
    private val dst = FloatArray(src.size)
    private val cleanShader = BitmapShader(clean, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val meshPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { shader = cleanShader }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val refPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val colsN = 14
    private val strip = FloatArray((colsN + 1) * 3 * 2)
    private val stripTex = FloatArray((colsN + 1) * 3 * 2)
    private val stripIdx: ShortArray = ShortArray(colsN * 2 * 2 * 3).also { ix ->
        var k = 0
        for (c in 0 until colsN) for (r in 0 until 2) {
            val a = (c * 3 + r).toShort(); val b = (c * 3 + r + 1).toShort(); val cc = ((c + 1) * 3 + r).toShort(); val d = ((c + 1) * 3 + r + 1).toShort()
            ix[k++] = a; ix[k++] = cc; ix[k++] = b; ix[k++] = b; ix[k++] = cc; ix[k++] = d
        }
    }

    private fun sx(i: Int) = src[2 * i]; private fun sy(i: Int) = src[2 * i + 1]

    /** Interpolação linear em uma polilinha (x crescente). */
    private fun interp(x: Float, xs: FloatArray, ys: FloatArray): Float {
        if (x <= xs[0]) return ys[0]
        for (k in 1 until xs.size) if (x <= xs[k]) { val a = (x - xs[k - 1]) / max(1e-3f, xs[k] - xs[k - 1]); return ys[k - 1] + (ys[k] - ys[k - 1]) * a }
        return ys[ys.size - 1]
    }
    private val tx = FloatArray(7); private val ty = FloatArray(7)
    private fun curveY(arr: FloatArray, idx: IntArray, x: Float): Float {
        for (k in idx.indices) { tx[k] = arr[2 * idx[k]]; ty[k] = arr[2 * idx[k] + 1] }
        return interp(x, tx, ty)
    }

    /** Pontos de destino da malha para os pesos [A, E, O, FV, MBP] (porta direta de rig.mouth_dst_v). */
    fun mouthDst(w: FloatArray, smile: Float): FloatArray {
        val d = dst; src.copyInto(d)
        for (i in loInner) d[2 * i + 1] = curveY(src, upIdx, sx(i)) + 0.4f * s
        val mbp = w[LipSync.MBP].coerceIn(0f, 1f); val fv = w[LipSync.FV].coerceIn(0f, 1f)
        val openk = (1 - mbp) * (1 - 0.7f * fv)
        for (k in 0 until 3) {
            val r = refs[k] ?: continue
            val ww = w[k] * openk
            if (ww < 1e-3f) continue
            for (j in mouthPts.indices) { val p = mouthPts[j]; d[2 * p] += ww * r.dlt[2 * j]; d[2 * p + 1] += ww * r.dlt[2 * j + 1] }
        }
        if (fv > 1e-3f) {
            val f = floatArrayOf(.6f, .9f, 1f, .9f, .6f); val ii = intArrayOf(upIdx[1], 61, 62, 63, upIdx[5])
            for (k in 0 until 5) d[2 * ii[k] + 1] -= 3.5f * fv * f[k] * s
            for (i in 55..59) d[2 * i + 1] -= 2.0f * fv * s
            for (i in loInner) d[2 * i + 1] -= 0.8f * fv * s
        }
        if (mbp > 1e-3f) {
            for (i in 49..53) d[2 * i + 1] += 1.8f * mbp * s
            for (i in 55..59) d[2 * i + 1] -= 1.8f * mbp * s
            for (i in loInner) d[2 * i + 1] = d[2 * i + 1] * (1 - mbp) + (curveY(d, upIdx, d[2 * i]) + 0.3f * s) * mbp
        }
        // mandíbula: segue o lábio inferior, no máximo ~9 px
        val drop = d[2 * 57 + 1] - sy(57)
        val jv = (drop * 0.55f).coerceIn(-2f * s, 9f * s)
        for ((i, f) in jaw) d[2 * i + 1] = sy(i) + jv * f
        for (i in fixed) { d[2 * i] = sx(i); d[2 * i + 1] = sy(i) }
        for (i in intArrayOf(48, 54, 60, 64)) d[2 * i + 1] -= 2.5f * smile * s
        for (j in 0 until 12) {
            val i = 48 + j
            val k = if (i in 55..59) 0.66f else if (i == 48 || i == 54) 0.5f else 0.38f
            d[2 * (cheek0 + j)] = sx(cheek0 + j) + (d[2 * i] - sx(i)) * k
            d[2 * (cheek0 + j) + 1] = sy(cheek0 + j) + (d[2 * i + 1] - sy(i)) * k
        }
        for (r in ring0 until ring0 + 24) if (sy(r) > mcY + 60 * s) d[2 * r + 1] = sy(r) + (d[2 * 8 + 1] - sy(8)) * 0.7f
        return d
    }

    class Estado {
        val w = FloatArray(5)
        var smile = 0.25f; var blink = 0f; var brow = 0f; var gx = 0f; var gy = 0f
    }

    /** Desenha o rosto (sem o movimento global da cabeça) em [out] (size x size). */
    fun render(out: Bitmap, e: Estado) {
        val c = Canvas(out)
        c.drawBitmap(base, 0f, 0f, null)
        val d = mouthDst(e.w, e.smile)
        c.drawVertices(Canvas.VertexMode.TRIANGLES, d.size, d, 0, src, 0, null, 0, tris, 0, tris.size, meshPaint)
        interior(c, d, e.w)
        sprites(c, e)
        c.drawBitmap(overlay, 0f, 0f, bmpPaint)
    }

    private fun interior(c: Canvas, d: FloatArray, w: FloatArray) {
        val x60 = d[2 * 60]; val x64 = d[2 * 64]
        var maxSpan = 0f
        for (k in 1..5) maxSpan = max(maxSpan, d[2 * loIdx[k] + 1] - d[2 * upIdx[k] + 1])
        if (maxSpan < 0.6f) return
        val ws = FloatArray(3) { w[it] }; ws[0] += w[LipSync.FV]
        var tot = ws.sum(); if (tot < 1e-3f) { ws[0] = 1f; tot = 1f }
        var acc = 0f
        for (k in 0 until 3) {
            val r = refs[k] ?: continue
            val wk = ws[k] / tot; if (wk < 0.04f) continue
            acc += wk
            for (col in 0..colsN) {
                val x = x60 + (x64 - x60) * col / colsN
                val up = curveY(d, upIdx, x); val lo = max(up, curveY(d, loIdx, x)); val span = lo - up
                val oxs = FloatArray(7) { r.ou[2 * it] }; val ous = FloatArray(7) { r.ou[2 * it + 1] }
                val ols = FloatArray(7) { r.ol[2 * it + 1] }; val olx = FloatArray(7) { r.ol[2 * it] }
                val srcx = oxs[0] + (x - x60) / max(1f, x64 - x60) * (oxs[6] - oxs[0])
                val ou = interp(srcx, oxs, ous); val ol = interp(srcx, olx, ols)
                val b = col * 3 * 2
                strip[b] = x; strip[b + 1] = up; stripTex[b] = srcx; stripTex[b + 1] = ou
                strip[b + 2] = x; strip[b + 3] = up + 0.62f * span; stripTex[b + 2] = srcx; stripTex[b + 3] = min(ol - 1f, ou + 0.62f * span)
                strip[b + 4] = x; strip[b + 5] = lo; stripTex[b + 4] = srcx; stripTex[b + 5] = ol
            }
            refPaint.shader = r.shader; refPaint.alpha = (255 * (wk / acc)).toInt().coerceIn(0, 255)
            c.drawVertices(Canvas.VertexMode.TRIANGLES, strip.size, strip, 0, stripTex, 0, null, 0, stripIdx, 0, stripIdx.size, refPaint)
        }
        // cantos da boca mais escuros (profundidade)
        path.reset()
        for (k in upIdx.indices) { val i = upIdx[k]; if (k == 0) path.moveTo(d[2 * i], d[2 * i + 1]) else path.lineTo(d[2 * i], d[2 * i + 1]) }
        for (k in loIdx.indices.reversed()) { val i = loIdx[k]; path.lineTo(d[2 * i], d[2 * i + 1]) }
        path.close()
        val cA = Color.argb(150, Color.red(cav), Color.green(cav), Color.blue(cav)); val cT = Color.argb(0, Color.red(cav), Color.green(cav), Color.blue(cav))
        shade.shader = LinearGradient(x60, 0f, x64, 0f, intArrayOf(cA, cT, cT, cA), floatArrayOf(0f, 0.25f, 0.75f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(path, shade)
    }

    private fun sprites(c: Canvas, e: Estado) {
        val bl = e.blink.coerceIn(0f, 1f); val br = e.brow.coerceIn(0f, 1f)
        if (bl < 0.02f && br < 0.02f) { gaze(c, e, base, 0f, 0f); return }
        fun pos(v: Float, lv: FloatArray): Pair<Int, Float> {
            var i = 0; while (i < lv.size - 2 && v > lv[i + 1]) i++
            return i to ((v - lv[i]) / (lv[i + 1] - lv[i])).coerceIn(0f, 1f)
        }
        val (i, a) = pos(bl, blinkLv); val (j, b) = pos(br, browLv)
        val x = upRect[0].toFloat(); val y = upRect[1].toFloat()
        bmpPaint.alpha = 255; c.drawBitmap(upSprites[i][j], x, y, bmpPaint)
        if (a > 0.01f) { bmpPaint.alpha = (255 * a).toInt(); c.drawBitmap(upSprites[i + 1][j], x, y, bmpPaint) }
        if (b > 0.01f) {
            bmpPaint.alpha = (255 * b * (1 - a)).toInt(); c.drawBitmap(upSprites[i][j + 1], x, y, bmpPaint)
            if (a > 0.01f) { bmpPaint.alpha = (255 * a * b).toInt(); c.drawBitmap(upSprites[i + 1][j + 1], x, y, bmpPaint) }
        }
        bmpPaint.alpha = 255
        if (bl < 0.3f) gaze(c, e, upSprites[i][j], x, y)
    }

    /** Olhar: desloca íris/esclera (até ~1,5 px) dentro do contorno da pálpebra. */
    private fun gaze(c: Canvas, e: Estado, bmp: Bitmap, ox: Float, oy: Float) {
        if (kotlin.math.abs(e.gx) < 0.05f && kotlin.math.abs(e.gy) < 0.05f) return
        for (p in eyes) {
            path.reset(); path.moveTo(p[0], p[1]); for (k in 1 until p.size / 2) path.lineTo(p[2 * k], p[2 * k + 1]); path.close()
            c.save(); c.clipPath(path)
            c.drawBitmap(bmp, ox + e.gx * s, oy + e.gy * s, bmpPaint)
            c.restore()
        }
    }

    companion object {
        @Volatile private var inst: LunaRig? = null
        fun get(ctx: Context): LunaRig? = inst ?: synchronized(this) { inst ?: runCatching { load(ctx.applicationContext) }.getOrNull().also { inst = it } }

        private fun bmp(ctx: Context, name: String): Bitmap =
            ctx.assets.open("luna/$name").use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }) }
                ?: error("asset $name")
        private fun fa(a: JSONArray): FloatArray {
            val out = ArrayList<Float>()
            fun walk(x: Any?) { if (x is JSONArray) for (i in 0 until x.length()) walk(x.get(i)) else out.add((x as Number).toFloat()) }
            walk(a); return out.toFloatArray()
        }
        private fun ia(a: JSONArray) = IntArray(a.length()) { a.getInt(it) }

        private fun load(ctx: Context): LunaRig {
            val j = JSONObject(ctx.assets.open("luna/rig.json").bufferedReader().use { it.readText() })
            val size = j.getInt("size"); val s = j.getDouble("scale").toFloat()
            val trisA = j.getJSONArray("tris"); val tris = ShortArray(trisA.length() * 3)
            for (i in 0 until trisA.length()) { val t = trisA.getJSONArray(i); for (k in 0..2) tris[3 * i + k] = t.getInt(k).toShort() }
            val jawO = j.getJSONObject("jaw"); val jaw = HashMap<Int, Float>()
            for (k in jawO.keys()) jaw[k.toInt()] = jawO.getDouble(k).toFloat()
            val rr = j.getJSONArray("refrect"); val refsO = j.getJSONObject("refs")
            val refs = listOf("ah", "ee", "oo").map { k ->
                if (!refsO.has(k)) null else {
                    val r = refsO.getJSONObject(k)
                    Ref(bmp(ctx, "ref_$k.webp"), fa(r.getJSONArray("ou")), fa(r.getJSONArray("ol")), fa(r.getJSONArray("dlt")), rr.getDouble(0).toFloat(), rr.getDouble(1).toFloat())
                }
            }
            // As coordenadas dos interiores estão no recorte de cada ref; a malha usa coordenadas do recorte (0..size).
            val cavA = j.getJSONArray("cav"); val cav = Color.rgb(cavA.getDouble(0).toInt(), cavA.getDouble(1).toInt(), cavA.getDouble(2).toInt())
            val up = j.getJSONObject("up"); val bl = fa(up.getJSONArray("blink")); val brl = fa(up.getJSONArray("brow"))
            val sprites = Array(bl.size) { i -> Array(brl.size) { k -> bmp(ctx, "up_${i}_$k.webp") } }
            val eyesA = j.getJSONArray("eyes"); val eyes = (0 until eyesA.length()).map { fa(eyesA.getJSONArray(it)) }
            val piv = j.getJSONArray("pivot")
            return LunaRig(size, s, bmp(ctx, "base.webp"), bmp(ctx, "clean.webp"), bmp(ctx, "overlay.webp"),
                fa(j.getJSONArray("src")), tris, ia(j.getJSONArray("up_idx")), ia(j.getJSONArray("lo_idx")), ia(j.getJSONArray("lo_inner")),
                ia(j.getJSONArray("fixed")), ia(j.getJSONArray("mouth_pts")), j.getInt("cheek0"), j.getInt("ring0"),
                j.getJSONArray("mc").getDouble(1).toFloat(), jaw, refs, cav, ia(up.getJSONArray("rect")), sprites, bl, brl, eyes,
                j.getInt("grid"), fa(j.getJSONArray("hw")), fa(j.getJSONArray("bw")), piv.getDouble(0).toFloat(), piv.getDouble(1).toFloat())
        }
    }
}
