package space.neural.luna.voz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VadTest {
    /** Alimenta quadros de 20 ms com o nível (dBFS) dado; retorna o índice do quadro que disparou, ou -1. */
    private fun rodar(niveis: List<Float>): Int {
        val v = Vad()
        niveis.forEachIndexed { i, db -> if (v.quadroDb(db)) return i }
        return -1
    }

    private fun rep(db: Float, ms: Int) = List(ms / 20) { db }

    @Test fun ecoResidualContinuoNaoDispara() {
        // eco da Luna depois do AEC: -45 dBFS oscilando, por 5 s
        val eco = List(250) { -45f + ((it * 7) % 5) - 2f }
        assertEquals(-1, rodar(eco))
    }

    @Test fun falaSustentadaDisparaEm300ms() {
        val n = rep(-55f, 1000) + rep(-30f, 600)
        val i = rodar(n)
        assertTrue("disparou em $i", i in 50 + 14..50 + 16) // ~300 ms depois do início da fala
    }

    @Test fun estaloCurtoNaoDispara() {
        // batida/buzina curta de 120 ms não é fala
        assertEquals(-1, rodar(rep(-55f, 1000) + rep(-25f, 120) + rep(-55f, 1000)))
    }

    @Test fun naoDisparaNoAquecimento() {
        // fala logo nos primeiros 400 ms (início da fala dela) só conta depois do aquecimento
        val i = rodar(rep(-30f, 1000))
        assertTrue(i == -1 || i > 20)
    }

    @Test fun pequenasFalhasNaFalaContinuam() {
        val fala = (0 until 20).flatMap { if (it % 6 == 5) listOf(-60f) else listOf(-30f) }
        assertTrue(rodar(rep(-55f, 1000) + fala) > 0)
    }

    @Test fun silencioAbaixoDoPisoNaoDispara() {
        // ruído muito baixo que "sobe" 15 dB mas continua abaixo de -48 dBFS
        assertEquals(-1, rodar(rep(-75f, 1000) + rep(-58f, 1000)))
    }

    @Test fun dbfs() {
        assertEquals(0f, Vad.dbfs(FloatArray(100) { if (it % 2 == 0) 1f else -1f }), 0.01f)
        assertEquals(-120f, Vad.dbfs(FloatArray(100)), 0.5f)
    }

    @Test fun despedidas() {
        listOf("tchau", "Obrigado, Luna!", "valeu", "pode parar", "Tchau Luna", "obrigada", "até mais", "é só isso").forEach {
            assertTrue(it, Encerrar.deveParar(it))
        }
        listOf("quanto fiz hoje", "obrigado por me lembrar de lançar a corrida de 30 na uber", "para quanto está a meta", "", "lança 20 na 99").forEach {
            assertFalse(it, Encerrar.deveParar(it))
        }
    }
}
