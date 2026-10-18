package space.neural.luna.licenca

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime

/** Limiares do aviso de fim do teste (2 dias, amanhã, hoje) no fuso de Governador Valadares. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AvisoTesteTest {
    private val z = ZoneId.of("America/Sao_Paulo")
    private fun t(d: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, d, h, m, 0, 0, z).toInstant().toEpochMilli()
    private val fim = t(25, 14, 30) // teste acaba dia 25 às 14:30

    @Test fun limiaresPorDiaDoCalendario() {
        assertNull(AvisoTeste.limiar(t(21, 10), fim, z)) // faltam 4 dias
        assertNull(AvisoTeste.limiar(t(22, 23, 59), fim, z)) // 3 dias
        assertEquals(2, AvisoTeste.limiar(t(23, 0, 1), fim, z))
        assertEquals(2, AvisoTeste.limiar(t(23, 23), fim, z))
        assertEquals(1, AvisoTeste.limiar(t(24, 8), fim, z))
        assertEquals(0, AvisoTeste.limiar(t(25, 0, 5), fim, z))
        assertEquals(0, AvisoTeste.limiar(t(25, 14, 29), fim, z))
        assertNull(AvisoTeste.limiar(t(25, 14, 30), fim, z)) // acabou: vira a tela de bloqueio
        assertNull(AvisoTeste.limiar(t(26, 9), fim, z))
    }

    @Test fun textos() {
        assertEquals("Seu teste grátis acaba em 2 dias", AvisoTeste.titulo(2))
        assertEquals("Seu teste grátis acaba amanhã", AvisoTeste.titulo(1))
        assertEquals("Seu teste grátis acaba hoje", AvisoTeste.titulo(0))
        assertTrue(AvisoTeste.texto(0, fim, z).contains("hoje às 14:30"))
        assertTrue(AvisoTeste.texto(1, fim, z).contains("amanhã às 14:30"))
        assertTrue(AvisoTeste.textoBanner(0, fim, z).startsWith("Teste grátis acaba hoje às 14:30"))
    }

    @Test fun momentosDasNotificacoes() {
        val m = AvisoTeste.momentos(t(18, 12), fim, z)
        assertEquals(t(23, 9), m[2]); assertEquals(t(24, 9), m[1]); assertEquals(t(25, 9), m[0])
        // fim cedo (07:00): a de "hoje" sai 1 h antes do fim, não depois
        val cedo = t(25, 7)
        assertEquals(t(25, 6), AvisoTeste.momentos(t(18, 12), cedo, z)[0])
        // só os que ainda vão acontecer (servidor diz que já é dia 24 às 10h)
        assertEquals(setOf(0), AvisoTeste.momentos(t(24, 10), fim, z).keys)
        assertTrue(AvisoTeste.momentos(t(25, 15), fim, z).isEmpty())
    }

    @Test fun janelaUmaVezPorLimiar() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        assertEquals(2, AvisoTeste.janelaPendente(ctx, t(23, 10), fim, z))
        assertNull(AvisoTeste.janelaPendente(ctx, t(23, 18), fim, z)) // mesmo limiar: não repete
        assertEquals(1, AvisoTeste.janelaPendente(ctx, t(24, 10), fim, z))
        assertEquals(0, AvisoTeste.janelaPendente(ctx, t(25, 8), fim, z))
        assertNull(AvisoTeste.janelaPendente(ctx, t(25, 12), fim, z))
        // outro teste (fim diferente) tem os próprios avisos
        assertEquals(0, AvisoTeste.janelaPendente(ctx, t(25, 8), fim + 60_000, z))
        assertNull(AvisoTeste.janelaPendente(ctx, t(21, 8), fim + 120_000, z)) // fora dos limiares
    }
}
