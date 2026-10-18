package space.neural.luna.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import space.neural.luna.chat.Comandos
import space.neural.luna.data.local.AppCorrida
import space.neural.luna.data.local.CategoriaGasto
import space.neural.luna.data.local.EstadoTurno
import space.neural.luna.data.local.Ganho
import space.neural.luna.data.local.Gasto
import space.neural.luna.data.local.Meta
import space.neural.luna.data.local.TipoMeta
import space.neural.luna.data.local.Turno
import java.time.LocalDate

class CalculosTest {
    private val hoje = LocalDate.of(2026, 10, 18) // domingo

    @Test fun parseValores() {
        assertEquals(1250L, Calculos.parseCentavos("12,50"))
        assertEquals(1250L, Calculos.parseCentavos("12.50"))
        assertEquals(123456L, Calculos.parseCentavos("1.234,56"))
        assertEquals(3000L, Calculos.parseCentavos("R$ 30"))
        assertNull(Calculos.parseCentavos("0"))
        assertNull(Calculos.parseCentavos("abc"))
    }

    @Test fun tempoDeTrabalhoNaoContaPausa() {
        val t = Turno(data = "2026-10-18", inicio = 0, estado = EstadoTurno.PAUSADO, trabalhoMs = 3_600_000, paradoMs = 0, ultimoEvento = 3_600_000)
        assertEquals(3_600_000L, Calculos.trabalhoMs(t, 5_400_000))
        assertEquals(1_800_000L, Calculos.paradoMs(t, 5_400_000))
        val ativo = t.copy(estado = EstadoTurno.ATIVO)
        assertEquals(5_400_000L, Calculos.trabalhoMs(ativo, 5_400_000))
    }

    @Test fun resumoBrutoLiquidoEnergia() {
        val g = listOf(Ganho(data = "2026-10-18", app = AppCorrida.UBER, valorCentavos = 8000), Ganho(data = "2026-10-18", app = AppCorrida.NOVENTA_E_NOVE, valorCentavos = 9000))
        val d = listOf(
            Gasto(data = "2026-10-18", categoria = CategoriaGasto.ENERGIA, valorCentavos = 3240, kwh = 18.0),
            Gasto(data = "2026-10-18", categoria = CategoriaGasto.MANUTENCAO, valorCentavos = 1000),
        )
        val r = Calculos.resumo(g, d)
        assertEquals(17000L, r.brutoCentavos)
        assertEquals(12760L, r.liquidoCentavos)
        assertEquals(3240L, r.energiaCalculadaCentavos)
        assertEquals(18.0, r.energiaKwh, 0.0)
        val apps = Calculos.porApp(g)
        assertEquals(listOf(9000L, 8000L, 0L, 0L, 0L), apps.map { it.centavos })
        assertEquals(52, apps[0].participacao)
    }

    @Test fun metasSemanaComecaNaSegundaEUsaLiquido() {
        val (ini, fim) = Calculos.periodo(TipoMeta.SEMANA, hoje)
        assertEquals(LocalDate.of(2026, 10, 12), ini)
        assertEquals(LocalDate.of(2026, 10, 18), fim)
        val metas = listOf(Meta(data = "2026-10-01", tipo = TipoMeta.DIA, valorCentavos = 10000), Meta(data = "2026-10-18", tipo = TipoMeta.DIA, valorCentavos = 20000))
        val g = listOf(Ganho(data = "2026-10-18", app = AppCorrida.UBER, valorCentavos = 18000))
        val d = listOf(Gasto(data = "2026-10-18", categoria = CategoriaGasto.OUTRO, valorCentavos = 2000))
        val p = Calculos.progresso(TipoMeta.DIA, hoje, metas, g, d)
        assertEquals(20000L, p.metaCentavos) // a meta mais recente vale
        assertEquals(80, p.percentual)
        assertEquals(4000L, p.faltaCentavos)
        assertEquals(80, Calculos.limiar(p))
    }

    @Test fun sessaoZeraDepoisDeConcluir() {
        val g = listOf(
            Ganho(data = "2026-10-18", turnoId = "t1", app = AppCorrida.UBER, valorCentavos = 100),
            Ganho(data = "2026-10-18", turnoId = null, app = AppCorrida.UBER, valorCentavos = 200),
        )
        val s = Calculos.sessao(g, "2026-10-18", null, setOf("t1"), { it.data }, { it.turnoId })
        assertEquals(listOf(200L), s.map { it.valorCentavos })
    }

    @Test fun sugestaoDeParar() {
        val t = Turno(data = "2026-10-18", inicio = 0, estado = EstadoTurno.ATIVO)
        assertTrue(Calculos.sugerirParar(t, 3 * 3_600_000L, 1500, 2000))
        assertFalse(Calculos.sugerirParar(t, 3_600_000L, 1500, 2000))
        assertFalse(Calculos.sugerirParar(t, 3 * 3_600_000L, 2500, 2000))
    }

    @Test fun comandosDeVoz() {
        assertEquals(Comandos.Comando.Ganho(AppCorrida.UBER, 3000), Comandos.interpretar("30 na Uber"))
        assertEquals(Comandos.Comando.Ganho(AppCorrida.NOVENTA_E_NOVE, 4550), Comandos.interpretar("lança 45,50 no 99"))
        assertEquals(Comandos.Comando.Ganho(AppCorrida.INDRIVE, 1200), Comandos.interpretar("InDrive 12"))
        assertNull(Comandos.interpretar("quanto fiz na uber hoje?"))
        assertEquals(Comandos.Comando.Turno("PAUSAR"), Comandos.interpretar("pausar"))
        assertEquals("TOTAL", Comandos.totalOuMais("é o total"))
        assertEquals("MAIS", Comandos.totalOuMais("a mais"))
    }
}
