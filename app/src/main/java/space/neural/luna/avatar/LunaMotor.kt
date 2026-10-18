package space.neural.luna.avatar

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * Motor único da Luna animada: um relógio (~30 fps, Handler da thread principal) que só roda enquanto alguma
 * [LunaAvatarView]/orb estiver visível. Calcula o estado (respiração, piscadas, sobrancelhas,
 * olhar e cabeça vindos de captura de movimento real + boca vinda da fala) e desenha o rosto UMA vez
 * por quadro num Bitmap compartilhado por todas as vistas.
 */
object LunaMotor : Runnable {
    interface Alvo { fun quadroNovo() }

    class Cabeca { var rot = 0f; var tx = 0f; var ty = 0f; var breath = 0f }

    private var rig: LunaRig? = null
    private var mocap: Mocap? = null
    var quadro: Bitmap? = null; private set
    val cabeca = Cabeca()
    private val estado = LunaRig.Estado()
    private val alvos = LinkedHashSet<Alvo>()
    private var rodando = false
    private var t0 = 0L
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var fala: LipSync.Track? = null
    private var posicao: (() -> Double)? = null
    private var falaIntensa = 0f // 0 = ocioso, 1 = falando (suavizado)
    private val pesos = FloatArray(5)
    /** Se false (testes / "remover animações"), mostra a Luna parada. */
    var animar = true

    fun rigDe(ctx: Context): LunaRig? {
        if (rig == null) rig = LunaRig.get(ctx)
        if (mocap == null) mocap = runCatching { Mocap.load(ctx) }.getOrNull()
        if (quadro == null) rig?.let { r ->
            quadro = Bitmap.createBitmap(r.size, r.size, Bitmap.Config.ARGB_8888)
            r.render(quadro!!, estado)
        }
        return rig
    }

    fun registrar(ctx: Context, a: Alvo) {
        rigDe(ctx) ?: return
        alvos.add(a)
        if (!rodando && animar) { rodando = true; handler.post(this) }
    }

    fun remover(a: Alvo) { alvos.remove(a) }

    /** A Luna começa a falar: [pos] devolve os segundos já tocados do áudio. */
    fun falar(track: LipSync.Track, pos: () -> Double) { fala = track; posicao = pos }
    fun calar() { fala = null; posicao = null }
    val falando get() = fala != null

    override fun run() {
        if (alvos.isEmpty() || !animar) { rodando = false; return }
        val agora = SystemClock.uptimeMillis()
        handler.postAtTime(this, agora + 33) // ~30 fps (economia de bateria); para sozinho quando nada está visível
        if (t0 == 0L) t0 = agora
        val t = (agora - t0) / 1e3
        passo(t)
        alvos.toList().forEach { it.quadroNovo() }
    }

    /** Calcula e desenha o quadro do instante [t] (s). Público para testes e prints. */
    fun passo(t: Double) {
        val r = rig ?: return; val q = quadro ?: return
        val f = fala; val p = posicao?.invoke() ?: -1.0
        val falandoAgora = f != null && p >= 0 && p < f.frames.toDouble() / f.fps
        falaIntensa += ((if (falandoAgora) 1f else 0f) - falaIntensa) * 0.08f
        val m = mocap
        val k = 0.55f + 0.45f * falaIntensa // ocioso: movimento mais calmo
        var rot = 0f; var tx = 0f; var ty = 0f; var brow = 0f; var gx = 0f; var gy = 0f; var smile = 0f
        if (m != null) {
            val i = m.indice(t)
            rot = m.rot[i] * k; tx = m.tx[i] * k; ty = m.ty[i] * k; brow = m.brow[i] * 0.75f * k
            gx = m.gx[i]; gy = m.gy[i]; smile = m.smile[i]
        }
        // movimento de base (lento) + respiração a cada ~4 s
        rot += 0.25f * (1.05f * sin(2 * PI * t / 8).toFloat() + 0.45f * sin(4 * PI * t / 8 + 1).toFloat())
        tx += 0.3f * 3f * sin(2 * PI * t / 8 + 0.5).toFloat()
        ty += 0.3f * 1.6f * sin(2 * PI * t / 8 * 1.3 + 2).toFloat()
        val breath = (0.5 + 0.5 * sin(2 * PI * t / 4.0)).toFloat()
        estado.w.fill(0f)
        if (falandoAgora && f != null) {
            f.at(p, pesos); pesos.copyInto(estado.w)
            val fi = (p * f.fps).toInt().coerceIn(0, f.frames - 1)
            ty += f.nod[fi]; brow = max(brow, 0.8f * f.brow[fi])
        }
        estado.smile = ((if (falandoAgora) 0.18f else 0.3f) + smile).coerceIn(0.05f, 0.45f)
        estado.brow = brow.coerceIn(0f, 1f)
        estado.blink = m?.piscada(t) ?: 0f
        estado.gx = gx; estado.gy = gy
        cabeca.rot = rot.coerceIn(-2.2f, 2.2f); cabeca.tx = tx.coerceIn(-4.5f, 4.5f); cabeca.ty = ty.coerceIn(-4.5f, 5f); cabeca.breath = breath
        r.render(q, estado)
    }

    /** Curvas de cabeça/olhos/sobrancelhas de uma pessoa real (NIOSH, domínio público), em pingue-pongue. */
    class Mocap(val rot: FloatArray, val tx: FloatArray, val ty: FloatArray, val brow: FloatArray, val gx: FloatArray, val gy: FloatArray,
                val smile: FloatArray, val blinks: DoubleArray, val fps: Int) {
        private val dur = rot.size.toDouble() / fps
        private fun local(t: Double): Double { val c = t % (2 * dur); return if (c < dur) c else 2 * dur - c }
        fun indice(t: Double) = (local(t) * fps).toInt().coerceIn(0, rot.size - 1)
        /** Piscada: fecha em 75 ms, segura até 110 ms, abre em 150 ms. */
        fun piscada(t: Double): Float {
            val c = t % (2 * dur)
            var b = 0.0
            for (bt in blinks) for (tt in doubleArrayOf(bt, 2 * dur - bt - 0.26)) {
                val d = c - tt
                if (d < 0 || d > 0.26) continue
                b = max(b, if (d < 0.075) Math.pow(d / 0.075, 1.5) else if (d < 0.11) 1.0 else Math.pow(max(0.0, 1 - (d - 0.11) / 0.15), 1.3))
            }
            return b.toFloat()
        }
        companion object {
            fun load(ctx: Context): Mocap {
                val j = JSONObject(ctx.assets.open("luna/mocap.json").bufferedReader().use { it.readText() })
                fun a(k: String): FloatArray { val x = j.getJSONArray(k); return FloatArray(x.length()) { x.getDouble(it).toFloat() } }
                val bl = j.getJSONArray("blinks")
                return Mocap(a("rot"), a("tx"), a("ty"), a("brow"), a("gx"), a("gy"), a("smile"), DoubleArray(bl.length()) { bl.getDouble(it) }, j.getInt("fps"))
            }
        }
    }

    @Suppress("unused") private fun bump(x: Double, w: Double) = exp(-(x / w) * (x / w))
}
