package space.neural.luna.avatar

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LipSyncTest {
    private fun recurso(n: String) = javaClass.getResourceAsStream("/luna/$n")!!.use { it.readBytes() }

    @Test fun g2pPortugues() {
        assertEquals(listOf("MBP", "A", "T", "e", "R"), LipSync.g2pWord("bater").map { it.first })
        assertEquals(listOf("MBP", "e", "T", "A"), LipSync.g2pWord("meta").map { it.first })
        assertEquals(listOf("K", "A", "FV", "E"), LipSync.g2pWord("café").map { it.first })
        // m/n no fim da sílaba é nasal (não fecha a boca); "u" de "que" é mudo; -o átono final vira "u"
        assertEquals(listOf("MBP", "e"), LipSync.g2pWord("bem").map { it.first })
        assertEquals(listOf("K", "i"), LipSync.g2pWord("que").map { it.first })
        assertEquals("u", LipSync.g2pWord("turno").last().first)
        assertTrue(LipSync.g2pWord("você").last().second) // tônica no acento
    }

    @Test fun numerosPorExtenso() {
        assertEquals("cento e vinte reais e cinquenta centavos", Numeros.expandir("R$ 120,50"))
        assertEquals("mil e duzentos reais", Numeros.expandir("R$ 1.200"))
        assertEquals("faltam trinta e cinco por cento", Numeros.expandir("faltam 35%"))
        assertEquals("cem", Numeros.extenso(100)); assertEquals("cento e um", Numeros.extenso(101))
    }

    @Test fun alinhamentoIgualAoPrototipoPython() {
        val pcm = LunaVoz.lerWav(recurso("fala_teste.wav"))!!
        val ref = JSONObject(String(recurso("fala_teste_python.json")))
        val esperado = ref.getJSONArray("segs")
        val segs = LipSync.align(ref.getString("text"), pcm.amostras, pcm.taxa)
        assertEquals(esperado.length(), segs.size)
        var soma = 0.0
        for (i in segs.indices) {
            val e = esperado.getJSONArray(i)
            assertEquals(e.getString(0), segs[i].v)
            soma += abs(segs[i].t0 - e.getDouble(1))
        }
        assertTrue("diferença média ${soma / segs.size}", soma / segs.size < 0.03)
    }

    @Test fun mbpFechaABocaPorCompleto() {
        val pcm = LunaVoz.lerWav(recurso("fala_teste.wav"))!!
        val tr = LipSync.analisar("Oi, Marlon! Bora pra mais um turno? O café já tá pronto, hein!", pcm.amostras, pcm.taxa)
        val mbps = tr.segs.filter { it.v == "MBP" }
        assertEquals(5, mbps.size) // Marlon, Bora, pra, mais, pronto ("um" é nasal: não fecha)
        for (s in mbps) {
            val f = (s.t0 * tr.fps).toInt()
            val maxMbp = (f - 2..f + 1).filter { it in 0 until tr.frames }.maxOf { tr.w[it][LipSync.MBP] }
            assertEquals("fechamento em ${s.t0}", 1f, maxMbp, 1e-4f)
        }
        // nas vogais abertas a boca abre
        assertTrue(tr.w.maxOf { it[LipSync.AH] } > 0.6f)
    }
}
