package space.neural.luna.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.neural.luna.data.local.AppCorrida
import space.neural.luna.data.local.CategoriaGasto
import space.neural.luna.data.local.Ganho
import space.neural.luna.data.local.Gasto
import java.time.LocalDate

class EstatisticasTest {
    private val hoje = LocalDate.of(2026, 10, 18) // domingo

    private val ganhos = listOf(
        Ganho(data = "2026-10-18", app = AppCorrida.UBER, valorCentavos = 10000),
        Ganho(data = "2026-10-18", app = AppCorrida.NOVENTA_E_NOVE, valorCentavos = 5000),
        Ganho(data = "2026-10-17", app = AppCorrida.UBER, valorCentavos = 8000),
        Ganho(data = "2026-10-11", app = AppCorrida.UBER, valorCentavos = 4000), // semana passada
        Ganho(data = "2026-10-18", app = AppCorrida.UBER, valorCentavos = 100, descricao = Estatisticas.AJUSTE),
    )
    private val gastos = listOf(
        Gasto(data = "2026-10-18", categoria = CategoriaGasto.ENERGIA, valorCentavos = 2000),
        Gasto(data = "2026-10-12", categoria = CategoriaGasto.OUTRO, valorCentavos = 1000),
    )

    @Test fun hojeComparaComOntem() {
        val e = Estatisticas.calcular(Periodo.HOJE, hoje, 0, ganhos, gastos, emptyList(), emptyList())
        assertEquals(15100L, e.brutoCentavos)
        assertEquals(13100L, e.liquidoCentavos)
        assertEquals(2, e.corridas) // ajuste não conta como corrida
        assertEquals(8000L, e.liquidoAnterior)
        assertEquals(63.75, e.variacao(e.liquidoCentavos, e.liquidoAnterior)!!, 0.01)
        assertEquals(7, e.serie.size)
        assertTrue(e.serie.last().atual)
        assertEquals("Energia", e.ultimos.first { !it.ganho }.nome)
    }

    @Test fun semanaDeSegundaADomingo() {
        val e = Estatisticas.calcular(Periodo.SEMANA, hoje, 0, ganhos, gastos, emptyList(), emptyList())
        assertEquals(LocalDate.of(2026, 10, 12), e.inicio)
        assertEquals(23100L - 2000L - 1000L, e.liquidoCentavos)
        assertEquals(7, e.serie.size)
        assertEquals(4000L, e.liquidoAnterior)
        assertEquals(listOf(2000L, 0L, 1000L), e.gastosPorCategoria.map { it.second })
    }

    @Test fun anoTemDozeMesesESemAnteriorNaoHaVariacao() {
        val e = Estatisticas.calcular(Periodo.ANO, hoje, 0, ganhos, gastos, emptyList(), emptyList())
        assertEquals(12, e.serie.size)
        assertNull(e.variacao(e.liquidoCentavos, 0))
    }
}
