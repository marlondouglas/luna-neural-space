package space.neural.luna.voz

import java.text.Normalizer
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Detector de voz leve para o barge-in (interromper a Luna falando). Recebe quadros de ~20 ms do
 * microfone (já com cancelador de eco/supressor de ruído do aparelho) e dispara quando há fala
 * sustentada (~300 ms) bem acima do "chão" que sobra do eco da própria Luna e do ruído do carro.
 * O chão desce rápido e sobe devagar, então fala que começa de repente se destaca, mas o eco
 * residual contínuo é absorvido.
 */
class Vad(
    private val quadroMs: Int = 20,
    private val sustentarMs: Int = 300,
    private val acimaDb: Float = 12f,
    private val pisoDbfs: Float = -48f,
    private val aquecerMs: Int = 400,
) {
    private var base = Float.NaN
    private var tempo = 0
    private var voz = 0
    private var falhas = 0
    var disparou = false; private set

    fun reiniciar() { base = Float.NaN; tempo = 0; voz = 0; falhas = 0; disparou = false }

    /** Um quadro (amostras de -1..1 ou PCM16 já normalizado). Retorna true no quadro em que dispara. */
    fun quadro(amostras: FloatArray, n: Int = amostras.size): Boolean = quadroDb(dbfs(amostras, n))

    fun quadroDb(db: Float): Boolean {
        tempo += quadroMs
        if (base.isNaN()) base = db
        // chão: desce rápido (0,3), sobe devagar (0,02); não sobe durante candidato a fala
        base = if (db < base) base + 0.3f * (db - base) else if (voz == 0) base + 0.02f * (db - base) else base
        if (tempo <= aquecerMs || disparou) return false
        val ehVoz = db > pisoDbfs && db > base + acimaDb
        if (ehVoz) { voz++; falhas = 0 } else if (voz > 0 && ++falhas > 2) { voz = 0; falhas = 0 }
        if (voz * quadroMs >= sustentarMs) { disparou = true; return true }
        return false
    }

    companion object {
        fun dbfs(a: FloatArray, n: Int = a.size): Float {
            var s = 0.0
            for (i in 0 until n) s += a[i] * a[i]
            val rms = sqrt(s / max(1, n))
            return (20 * log10(max(rms, 1e-6))).toFloat()
        }
    }
}

/** Frases que encerram a conversa contínua. */
object Encerrar {
    private val fim = listOf(
        "tchau", "xau", "obrigado", "obrigada", "brigado", "brigada", "valeu", "pode parar", "para de ouvir",
        "chega", "ate mais", "ate logo", "ate ja", "falou", "e so isso", "so isso", "encerrar", "pode desligar", "nada nao", "deixa pra la",
    )
    fun normalizar(t: String) = Normalizer.normalize(t.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** true quando a fala é (basicamente) uma despedida: "obrigado, Luna", "tchau", "pode parar". */
    fun deveParar(texto: String): Boolean {
        val t = normalizar(texto)
        if (t.isEmpty()) return false
        val palavras = t.split(' ')
        if (palavras.size > 6) return false // frase longa com "obrigado" no meio é pergunta, não despedida
        return fim.any { f -> t == f || t.startsWith("$f ") || t.endsWith(" $f") || t.contains(" $f ") }
    }

    /** Silêncio total (sem ouvir nada) que encerra a escuta contínua. */
    const val SILENCIO_MS = 7_000L
}
