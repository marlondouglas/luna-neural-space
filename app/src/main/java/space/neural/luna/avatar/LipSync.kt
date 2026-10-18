package space.neural.luna.avatar

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Lip sync da Luna em tempo de execução (sem modelo neural):
 *  1. texto -> fonemas/visemas pt-BR por regras (G2P);
 *  2. alinhamento leve com o áudio: pausas do áudio -> fronteiras de palavra (programação dinâmica)
 *     e, dentro de cada trecho de fala, Viterbi segmental com durações típicas + evidência acústica
 *     (energia, "mergulhos" de energia nas oclusivas/M-B-P, energia de alta frequência nas fricativas);
 *  3. coarticulação por dominância (Cohen-Massaro), antecipação de 60 ms e M/B/P sempre fechados.
 * É o mesmo método do protótipo em Python (luna-avatar/rt_align2.py), validado contra o alinhamento
 * forçado MMS da v6: erro mediano de 23 a 37 ms no início dos fonemas.
 */
object LipSync {
    /** Pesos das poses de boca: A, E/I, O/U, F/V, M/B/P. */
    const val AH = 0; const val EE = 1; const val OO = 2; const val FV = 3; const val MBP = 4

    data class Seg(val v: String, val t0: Double, val t1: Double, val stressed: Boolean)

    /** Trilha a 30 fps: pesos[frame][5], intensidade (0..1) e acentos de fala (para acenos/sobrancelhas). */
    class Track(val fps: Int, val w: Array<FloatArray>, val energy: FloatArray, val nod: FloatArray, val brow: FloatArray, val segs: List<Seg>) {
        val frames get() = w.size
        fun at(t: Double, out: FloatArray) {
            val f = t * fps
            val i = f.toInt()
            if (i < 0 || i >= w.size) { out.fill(0f); return }
            val j = min(i + 1, w.size - 1); val a = (f - i).toFloat()
            for (k in 0 until 5) out[k] = w[i][k] * (1 - a) + w[j][k] * a
        }
    }

    // ---------------- G2P ----------------
    private val CLS = mapOf("A" to 'V', "A~" to 'V', "E" to 'V', "e" to 'V', "i" to 'V', "O" to 'V', "o" to 'V', "u" to 'V',
        "MBP" to 'M', "FV" to 'F', "S" to 'F', "SH" to 'F', "T" to 'T', "K" to 'T', "L" to 'L', "R" to 'L')
    private val MEAN = mapOf('V' to 8.0, 'M' to 7.0, 'F' to 9.0, 'T' to 6.0, 'L' to 5.0)
    private const val STRESS = 1.3; private const val FINAL = 2.0; private const val SHIFT = 0.045
    private const val ALPHA = 0.3; private const val LAM = 2.0; private const val PAUSE_THR = 0.3; private const val PAUSE_MIN = 10
    private const val PUNCT_BONUS = 0.8

    private fun base(c: Char): Char = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0]
    private fun isVowel(c: Char) = base(c) in "aeiou"

    /** Uma palavra (minúscula) -> lista (visema, tônica). */
    fun g2pWord(w: String): List<Pair<String, Boolean>> {
        val n = w.length
        val vow = w.indices.filter { isVowel(w[it]) }
        val acc = w.indices.filter { w[it] in "áéíóúâêôãõ" }
        val st = when {
            acc.isNotEmpty() -> acc[0]
            vow.size >= 2 && !(w.endsWith("r") || w.endsWith("l") || w.endsWith("z") || w.endsWith("i") || w.endsWith("u") || w.endsWith("n")) -> vow[vow.size - 2]
            vow.isNotEmpty() -> vow.last()
            else -> -1
        }
        val out = ArrayList<Pair<String, Boolean>>()
        var i = 0
        while (i < n) {
            val c = w[i]; val b = base(c); val nx = if (i + 1 < n) w[i + 1] else ' '
            val nxV = nx != ' ' && isVowel(nx)
            val front = nx != ' ' && nx in "eiéê"
            val final = i == n - 1 || (i == n - 2 && w[n - 1] == 's')
            var v: String? = null
            when {
                b == 'h' -> v = null
                b == 'c' && nx == 'h' -> { v = "SH"; i++ }
                b == 'a' -> v = if (c in "ãâ" || (nx != ' ' && nx in "mn" && (i + 2 >= n || !isVowel(w[i + 2])))) "A~" else "A"
                b == 'e' -> v = if (c == 'é') "E" else if (final && i != st && n > 1) "i" else "e"
                b == 'i' -> v = "i"
                b == 'o' -> v = if (c == 'ó') "O" else if (final && i != st && n > 1) "u" else "o"
                b == 'u' -> v = if (i > 0 && w[i - 1] in "qg" && front) null else "u"
                b in "mbp" -> v = if (b == 'm' && (final || (nx != ' ' && !nxV))) null else "MBP"
                b in "fv" -> v = "FV"
                b in "szçx" || (b == 'c' && front) -> v = "S"
                b == 'j' || (b == 'g' && front) -> v = "SH"
                b in "tdn" -> v = if (b == 'n' && (final || (nx != ' ' && !nxV))) null else "T"
                b == 'l' -> v = "L"
                b == 'r' -> v = "R"
                b in "ckqg" -> v = "K"
            }
            if (v != null) out.add(v to (i == st))
            i++
        }
        return out
    }

    class Word(val text: String, var punct: Boolean, val ph: List<Pair<String, Boolean>>) { var lastV = -1 }

    private val TOKEN = Regex("[a-zà-úç]+|[,.!?;:…]")
    fun words(text: String): List<Word> {
        val out = ArrayList<Word>()
        for (m in TOKEN.findAll(Numeros.expandir(text).lowercase())) {
            val t = m.value
            if (t[0].isLetter()) out.add(Word(t, false, g2pWord(t))) else if (out.isNotEmpty()) out.last().punct = true
        }
        val ws = out.filter { it.ph.isNotEmpty() }
        for (w in ws) if (w.punct) w.lastV = w.ph.indices.lastOrNull { CLS[w.ph[it].first] == 'V' } ?: -1
        return ws
    }

    private fun pdur(v: String, st: Boolean, fin: Boolean): Double {
        val c = CLS.getValue(v)
        return MEAN.getValue(c) * (if (st && c == 'V') STRESS else 1.0) * (if (fin) FINAL else 1.0)
    }
    private fun wdur(w: Word) = w.ph.indices.sumOf { pdur(w.ph[it].first, w.ph[it].second, it == w.lastV) }

    // ---------------- features (10 ms) ----------------
    class Feat(val en: DoubleArray, val hf: DoubleArray, val lf: DoubleArray, val dp: DoubleArray)

    fun features(x: FloatArray, sr: Int): Feat {
        val hop = sr / 100; val win = 512
        val n = max(1, (x.size - win) / hop + 1)
        val e = DoubleArray(n); val hf = DoubleArray(n); val lf = DoubleArray(n)
        val re = DoubleArray(win); val im = DoubleArray(win)
        val hann = DoubleArray(win) { 0.5 - 0.5 * cos(2 * Math.PI * it / (win - 1)) }
        val df = sr.toDouble() / win
        for (i in 0 until n) {
            for (k in 0 until win) { val idx = i * hop + k; re[k] = (if (idx < x.size) x[idx].toDouble() else 0.0) * hann[k]; im[k] = 0.0 }
            fft(re, im)
            var a = 0.0; var b = 0.0; var c = 0.0
            for (k in 0..win / 2) {
                val f = k * df; val p = re[k] * re[k] + im[k] * im[k]
                if (f >= 80 && f < 400) a += p else if (f >= 400 && f < 3000) b += p else if (f >= 3500) c += p
            }
            val tot = a + b + c + 1e-12
            e[i] = 10 * log10(tot + 1e-12); hf[i] = c / tot; lf[i] = a / (a + b + 1e-12)
        }
        val pk = percentile(e, 95.0)
        val en = DoubleArray(n) { ((e[it] - (pk - 42)) / 42).coerceIn(0.0, 1.0) }
        for (i in 0 until n) if (en[i] <= 0.2) hf[i] = 0.0
        val dp = DoubleArray(n) { i ->
            var m = e[i]; for (k in max(0, i - 7)..min(n - 1, i + 7)) m = max(m, e[k])
            ((m - e[i]) / 18).coerceIn(0.0, 1.0)
        }
        return Feat(en, hf, lf, dp)
    }

    fun percentile(a: DoubleArray, q: Double): Double {
        if (a.isEmpty()) return 0.0
        val s = a.sortedArray(); val pos = (s.size - 1) * q / 100.0
        val lo = pos.toInt(); val hi = min(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (pos - lo)
    }

    /** FFT radix-2 in-place. */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * Math.PI / len; val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun cum(v: DoubleArray): DoubleArray { val c = DoubleArray(v.size + 1); for (i in v.indices) c[i + 1] = c[i] + v[i] * ALPHA; return c }

    private fun emissions(f: Feat): Map<Char, DoubleArray> {
        val n = f.en.size
        fun p(x: Double) = max(0.0, x)
        val v = DoubleArray(n) { -8 * p(0.7 - f.en[it]) - 6 * f.dp[it] - 4 * p(f.hf[it] - 0.25) }
        val m = DoubleArray(n) { 3 * f.dp[it] - 3 * p(f.en[it] - 0.8) + f.lf[it] - 2 * p(f.hf[it] - 0.3) }
        val fr = DoubleArray(n) { -7 * p(0.3 - f.hf[it]) + f.dp[it] }
        val t = DoubleArray(n) { 1.5 * f.dp[it] - 2 * p(f.en[it] - 0.85) }
        val l = DoubleArray(n) { -4 * p(0.55 - f.en[it]) - 3 * p(f.hf[it] - 0.35) }
        return mapOf('V' to cum(v), 'M' to cum(m), 'F' to cum(fr), 'T' to cum(t), 'L' to cum(l))
    }

    private data class Pz(val a: Int, val b: Int)

    private fun pauses(f: Feat): Triple<Int, Int, List<Pz>> {
        val n = f.en.size
        val sp = BooleanArray(n) { f.en[it] > PAUSE_THR || (f.en[it] > 0.12 && f.hf[it] > 0.3) }
        val raw = ArrayList<Pz>(); var i = 0
        while (i < n) {
            if (!sp[i]) { var j = i; while (j < n && !sp[j]) j++; raw.add(Pz(i, j)); i = j } else i++
        }
        val m = ArrayList<Pz>()
        for (p in raw) {
            val last = m.lastOrNull()
            if (last != null && p.a - last.b < 15 && p.b - p.a >= 6 && last.b - last.a >= 6) m[m.size - 1] = Pz(last.a, p.b) else m.add(p)
        }
        val lead = if (m.isNotEmpty() && m[0].a == 0) m[0].b else 0
        val trail = if (m.isNotEmpty() && m.last().b == n) m.last().a else n
        return Triple(lead, trail, m.filter { it.a > lead && it.b < trail && it.b - it.a >= PAUSE_MIN })
    }

    private fun assignPauses(words: List<Word>, lead: Int, trail: Int, inner: List<Pz>): Map<Int, Pz> {
        val nW = words.size
        val cumd = DoubleArray(nW + 1); for (i in 0 until nW) cumd[i + 1] = cumd[i] + wdur(words[i])
        val pz = listOf(Pz(lead, lead)) + inner + listOf(Pz(trail, trail)); val nP = pz.size
        val speech = (trail - lead) - inner.sumOf { it.b - it.a }
        val k = speech / max(cumd[nW], 1.0)
        val inf = 1e18
        val dp = Array(nP) { DoubleArray(nW + 1) { inf } }
        val bk = HashMap<Long, Long>()
        dp[0][0] = 0.0
        val pauseLenBefore = IntArray(nP + 1); for (j in 0 until nP) pauseLenBefore[j + 1] = pauseLenBefore[j] + (pz[j].b - pz[j].a)
        for (j in 1 until nP) for (i in 1..nW) {
            if ((j == nP - 1) != (i == nW)) continue
            val bc = if (j == nP - 1) 0.0 else if (words[i - 1].punct) -PUNCT_BONUS * 10 else 2.0
            for (jp in 0 until j) for (ip in 0 until i) {
                if (dp[jp][ip] >= inf) continue
                val skipped = pauseLenBefore[j] - pauseLenBefore[jp + 1]
                val act = pz[j].a - pz[jp].b - skipped
                val ex = (cumd[i] - cumd[ip]) * k
                val c = dp[jp][ip] + 8 * abs(ln(max(act.toDouble(), 1.0) / max(ex, 1.0))) + bc + 1.0 * (j - jp - 1)
                if (c < dp[j][i]) { dp[j][i] = c; bk[j.toLong() * 100000 + i] = jp.toLong() * 100000 + ip }
            }
        }
        val amap = HashMap<Int, Pz>()
        var j = nP - 1; var i = nW
        while (true) {
            val b = bk[j.toLong() * 100000 + i] ?: break
            val jp = (b / 100000).toInt(); val ip = (b % 100000).toInt()
            if (jp > 0) amap[ip] = pz[jp]
            j = jp; i = ip
        }
        return amap
    }

    private class Ph(val v: String, val st: Boolean, val fin: Boolean)

    private fun viterbi(ph: List<Ph>, c: Map<Char, DoubleArray>, t0: Int, t1: Int, out: MutableList<Seg>) {
        val n = ph.size; val T = t1 - t0
        val cls = ph.map { CLS.getValue(it.v) }
        val mu0 = DoubleArray(n) { pdur(ph[it].v, ph[it].st, ph[it].fin) }
        val s = mu0.sum(); val mu = DoubleArray(n) { mu0[it] * T / s }
        val neg = -1e18
        val best = Array(n + 1) { DoubleArray(T + 1) { neg } }
        val bk = Array(n + 1) { IntArray(T + 1) }
        best[0][0] = 0.0
        val last = c.getValue('V').size - 1
        fun em(cl: Char, t: Int) = c.getValue(cl)[min(t, last)]
        for (i in 1..n) {
            val cl = cls[i - 1]; val m = mu[i - 1]; val dmax = (m * 3).toInt() + 3
            for (t in 1..T) {
                val lo = max(0, t - dmax)
                var bv = neg; var ba = 0
                val et = em(cl, t0 + t)
                for (a in lo until t) {
                    val pb = best[i - 1][a]; if (pb <= neg / 2) continue
                    val d = (t - a).toDouble(); val lr = ln(d / m)
                    val sc = pb + (et - em(cl, t0 + a)) - LAM * lr * lr
                    if (sc > bv) { bv = sc; ba = a }
                }
                best[i][t] = bv; bk[i][t] = ba
            }
        }
        val tmp = ArrayList<Seg>(); var t = T
        for (i in n downTo 1) {
            val a = bk[i][t]
            tmp.add(Seg(ph[i - 1].v, (t0 + a) / 100.0, (t0 + t) / 100.0, ph[i - 1].st)); t = a
        }
        out.addAll(tmp.asReversed())
    }

    /** Alinha o texto ao áudio (mono, float -1..1). Retorna segmentos (inclui "sil" nas pausas). */
    fun align(text: String, x: FloatArray, sr: Int, f: Feat = features(x, sr)): List<Seg> {
        val ws = words(text)
        if (ws.isEmpty()) return emptyList()
        val c = emissions(f)
        val (lead, trail, inner) = pauses(f)
        val amap = assignPauses(ws, lead, trail, inner).toSortedMap()
        val segs = ArrayList<Seg>()
        var start = lead; var wi = 0
        val bounds = amap.entries.map { it.key to it.value } + listOf(ws.size to Pz(trail, trail))
        for ((i, p) in bounds) {
            val ph = ArrayList<Ph>()
            for (w in ws.subList(wi, i)) w.ph.forEachIndexed { k, (v, st) -> ph.add(Ph(v, st, k == w.lastV)) }
            if (ph.isNotEmpty()) viterbi(ph, c, start, max(p.a, start + ph.size * 2), segs)
            if (i < ws.size) segs.add(Seg("sil", p.a / 100.0, p.b / 100.0, false))
            start = p.b; wi = i
        }
        return segs.map { Seg(it.v, it.t0 + SHIFT, it.t1 + SHIFT, it.stressed) }
    }

    // ---------------- coarticulation (dominance) ----------------
    private class Tg(val w: FloatArray, val dom: Double)
    private fun tg(ah: Float = 0f, ee: Float = 0f, oo: Float = 0f, fv: Float = 0f, mbp: Float = 0f, dom: Double) = Tg(floatArrayOf(ah, ee, oo, fv, mbp), dom)
    private val TGT = mapOf(
        "A" to tg(ah = 1f, dom = 0.8), "A~" to tg(ah = .7f, ee = .15f, dom = 0.7),
        "E" to tg(ah = .45f, ee = .6f, dom = 0.7), "e" to tg(ee = .85f, dom = 0.7), "i" to tg(ee = .75f, dom = 0.6),
        "O" to tg(oo = .8f, ah = .3f, dom = 0.75), "o" to tg(oo = .95f, dom = 0.75), "u" to tg(oo = .75f, dom = 0.8),
        "MBP" to tg(mbp = 1f, dom = 3.0), "FV" to tg(fv = 1f, dom = 2.2),
        "S" to tg(ee = .3f, dom = 0.9), "SH" to tg(oo = .35f, ee = .2f, dom = 0.9),
        "T" to tg(ee = .2f, dom = 0.35), "L" to tg(ee = .25f, ah = .15f, dom = 0.3),
        "R" to tg(ah = .2f, dom = 0.25), "K" to tg(ah = .2f, dom = 0.3), "sil" to tg(dom = 0.6),
    )
    private val VOG = setOf("A", "A~", "E", "e", "i", "O", "o", "u")

    fun track(segs0: List<Seg>, dur: Double, f: Feat? = null, fps: Int = 30, antic: Double = 0.06): Track {
        val nF = max(1, (dur * fps).toInt())
        val sp = segs0.filter { it.v != "sil" }
        val full = ArrayList<Seg>()
        if (sp.isNotEmpty()) {
            full.add(Seg("sil", -1.0, sp[0].t0 - 0.06, false))
            sp.forEachIndexed { j, s ->
                full.add(s)
                if (j + 1 < sp.size && sp[j + 1].t0 - s.t1 > 0.12) full.add(Seg("sil", s.t1 + 0.04, sp[j + 1].t0 - 0.04, false))
            }
            full.add(Seg("sil", sp.last().t1 + 0.06, dur + 1, false))
        }
        val num = Array(nF) { FloatArray(5) }; val den = DoubleArray(nF) { 1e-6 }
        for (s in full) {
            val g = TGT.getValue(s.v)
            var cen = 0.5 * (s.t0 + s.t1) - (if (s.v != "sil") antic else 0.0)
            if (s.v == "MBP") cen = s.t0 + 0.02 - antic * 0.5
            var half = max(0.5 * (s.t1 - s.t0), 0.03); if (s.v == "sil") half = max(half, 0.06)
            val amp = if (s.stressed) 1f else if (s.v in VOG) 0.78f else 1f
            val flat = if (s.v != "sil") 0.6 else 0.9
            val f0 = max(0, ((cen - half * 4 - 0.3) * fps).toInt()); val f1 = min(nF - 1, ((cen + half * 4 + 0.3) * fps).toInt())
            for (i in f0..f1) {
                val t = i.toDouble() / fps
                val xx = abs(t - cen) / half
                val d = g.dom * exp(-0.9 * max(xx - flat, 0.0).pow(1.5) * 3)
                if (d < 1e-5) continue
                for (k in 0 until 5) num[i][k] += (d * g.w[k] * amp).toFloat()
                den[i] += d
            }
        }
        val w = Array(nF) { i -> FloatArray(5) { k -> (num[i][k] / den[i]).toFloat() } }
        // low-pass (gaussian sigma 1.1 frames)
        val ker = DoubleArray(9) { exp(-0.5 * ((it - 4) / 1.1).pow(2)) }.let { k -> val s = k.sum(); DoubleArray(9) { k[it] / s } }
        val sm = Array(nF) { FloatArray(5) }
        for (i in 0 until nF) for (k in 0 until 5) {
            var a = 0.0
            for (j in -4..4) { var q = i + j; if (q < 0) q = -q - 1; if (q >= nF) q = 2 * nF - q - 1; q = q.coerceIn(0, nF - 1); a += w[q][k] * ker[j + 4] }
            sm[i][k] = a.toFloat()
        }
        // loudness -> opening intensity (closures not affected)
        val energy = FloatArray(nF)
        if (f != null) {
            for (i in 0 until nF) {
                val c = (i.toDouble() / fps * 100).toInt()
                var a = 0.0; var nn = 0
                for (q in c - 6..c + 6) if (q >= 0 && q < f.en.size) { a += f.en[q]; nn++ }
                energy[i] = if (nn > 0) (a / nn).toFloat() else 0f
                val gk = (0.55f + 0.6f * energy[i]).coerceIn(0.45f, 1.1f)
                for (k in 0 until 3) sm[i][k] *= gk
            }
        }
        for (s in full) if (s.v == "MBP") {
            val a = ((s.t0 - antic * 0.5 - 0.035) * fps); val b = ((s.t0 + 0.035) * fps)
            for (i in max(0, kotlin.math.ceil(a).toInt())..min(nF - 1, b.toInt())) sm[i][MBP] = 1f
        }
        // speech accents: nod on every vowel (bigger on stressed), brow on every other stressed vowel
        val nod = FloatArray(nF); val brow = FloatArray(nF); var alt = 0
        for (s in sp) if (s.v in VOG) {
            val c = s.t0 * fps; val big = s.stressed
            for (i in max(0, (c - 12).toInt())..min(nF - 1, (c + 12).toInt())) {
                val x = i - c
                nod[i] += ((if (big) 2.4 else 0.8) * exp(-((x - 1) / 3.2).pow(2)) - (if (big) 0.8 * exp(-((x + 4) / 3.0).pow(2)) else 0.0)).toFloat()
                if (big && alt % 2 == 0) brow[i] = max(brow[i], exp(-((x + 1) / 5.0).pow(2)).toFloat())
            }
            if (big) alt++
        }
        return Track(fps, sm, energy, nod, brow, segs0)
    }

    /** Tudo de uma vez: texto + PCM -> trilha da boca. */
    fun analisar(text: String, x: FloatArray, sr: Int): Track {
        val f = features(x, sr)
        val segs = runCatching { align(text, x, sr, f) }.getOrElse { emptyList() }
        return track(segs, x.size.toDouble() / sr + 0.3, f)
    }
}

/** Números e valores por extenso (para o G2P acompanhar o que a voz fala). */
object Numeros {
    private val UN = listOf("zero", "um", "dois", "três", "quatro", "cinco", "seis", "sete", "oito", "nove", "dez", "onze", "doze", "treze",
        "quatorze", "quinze", "dezesseis", "dezessete", "dezoito", "dezenove")
    private val DEZ = listOf("", "", "vinte", "trinta", "quarenta", "cinquenta", "sessenta", "setenta", "oitenta", "noventa")
    private val CEN = listOf("", "cento", "duzentos", "trezentos", "quatrocentos", "quinhentos", "seiscentos", "setecentos", "oitocentos", "novecentos")

    fun extenso(n: Long): String {
        if (n < 0) return "menos " + extenso(-n)
        if (n < 20) return UN[n.toInt()]
        if (n < 100) return DEZ[(n / 10).toInt()] + if (n % 10 != 0L) " e " + UN[(n % 10).toInt()] else ""
        if (n == 100L) return "cem"
        if (n < 1000) return CEN[(n / 100).toInt()] + if (n % 100 != 0L) " e " + extenso(n % 100) else ""
        if (n < 1_000_000) {
            val m = n / 1000; val r = n % 1000
            val pre = if (m == 1L) "mil" else extenso(m) + " mil"
            return pre + if (r == 0L) "" else if (r < 100 || r % 100 == 0L) " e " + extenso(r) else " " + extenso(r)
        }
        val m = n / 1_000_000; val r = n % 1_000_000
        return (if (m == 1L) "um milhão" else extenso(m) + " milhões") + if (r == 0L) "" else " " + extenso(r)
    }

    private val REAIS = Regex("R\\$\\s*([0-9.]+)(?:,([0-9]{1,2}))?")
    private val NUM = Regex("(\\d+)(?:,(\\d+))?")
    fun expandir(t: String): String {
        var s = REAIS.replace(t) { m ->
            val r = m.groupValues[1].replace(".", "").toLongOrNull() ?: 0L
            val c = m.groupValues[2].takeIf { it.isNotEmpty() }?.padEnd(2, '0')?.toLongOrNull() ?: 0L
            buildString {
                append(extenso(r)).append(if (r == 1L) " real" else " reais")
                if (c > 0) append(" e ").append(extenso(c)).append(if (c == 1L) " centavo" else " centavos")
            }
        }
        s = s.replace("%", " por cento").replace("km/h", " quilômetros por hora").replace(Regex("(\\d)\\s*km\\b"), "$1 quilômetros")
        s = NUM.replace(s) { m ->
            val a = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            if (m.groupValues[2].isNotEmpty()) extenso(a) + " vírgula " + m.groupValues[2].map { extenso((it - '0').toLong()) }.joinToString(" ") else extenso(a)
        }
        return s
    }
}
