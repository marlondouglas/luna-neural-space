package space.neural.luna.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflitoTest {
    private fun v(t: Long, d: String? = null) = Conflito.Versao(t, d)

    @Test fun remotoMaisNovoVence() {
        assertTrue(Conflito.remotoVence(v(100, "a"), v(200, "b")))
        assertFalse(Conflito.remotoVence(v(300, "a"), v(200, "b")))
        assertTrue(Conflito.remotoVence(null, v(1, "b")))
    }

    @Test fun empateDecididoPeloAparelho() {
        assertTrue(Conflito.remotoVence(v(100, "a"), v(100, "b")))
        assertFalse(Conflito.remotoVence(v(100, "b"), v(100, "a")))
        // mesma versão (eco do próprio envio): não reaplica
        assertFalse(Conflito.remotoVence(v(100, "a"), v(100, "a")))
    }

    @Test fun envioNaoSobrescreveVersaoMaisNova() {
        assertTrue(Conflito.envioVence(v(200, "a"), null))
        assertTrue(Conflito.envioVence(v(200, "a"), v(100, "b")))
        assertTrue("reenvio da mesma alteração", Conflito.envioVence(v(200, "a"), v(200, "a")))
        assertFalse("0.1.6 sobrescrevia aqui", Conflito.envioVence(v(100, "a"), v(200, "b")))
    }

    @Test fun documentoAntigoSemDeviceId() {
        assertTrue(Conflito.remotoVence(v(100, null), v(100, "b")))
        assertEquals(0, Conflito.compara(v(5, null), v(5, "")))
    }

    @Test fun relogioCorrigido() {
        // servidor 10 s à frente; ida e volta de 200 ms
        assertEquals(10_000L, Relogio.calcularOffset(1_010_100, 1_000_000, 1_000_200))
        // central do carro 3 dias atrasada
        assertEquals(3 * 86_400_000L, Relogio.calcularOffset(1_000_000 + 3 * 86_400_000L, 1_000_000, 1_000_000))
    }
}
