package space.neural.luna.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.neural.luna.chat.Comandos
import space.neural.luna.data.local.AppCorrida
import space.neural.luna.data.local.CategoriaGasto
import space.neural.luna.data.local.ContaFixa
import space.neural.luna.data.local.Gasto
import space.neural.luna.data.local.Limite
import space.neural.luna.data.local.Planejamento
import java.time.LocalDate

class PlanoTest {
    private val hoje = LocalDate.of(2026, 10, 18) // domingo, semana 3 do mês
    // seg–sáb nas 4 semanas
    private val plano = Planejamento(diasTrabalho = "1111110,1111110,1111110,1111110", ganhoHoraCentavos = 3000, rendaDesejadaCentavos = 100000)
    private val contas = listOf(ContaFixa(nome = "Aluguel", valorCentavos = 120000, diaVencimento = 10), ContaFixa(nome = "Internet", valorCentavos = 10000, diaVencimento = 5))
    private val supermercado = Limite(nome = "Supermercado", limiteCentavos = 100000)
    private val limites = listOf(supermercado, Limite(nome = "Lanche", limiteCentavos = 20000))

    @Test fun diasDeTrabalhoDoMes() {
        // Outubro/2026: 31 dias, 4 domingos (4, 11, 18, 25) -> 27 dias de seg–sáb
        assertEquals(27, Plano.diasTrabalho(plano, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)))
        assertEquals(2, Plano.semanaDoMes(hoje))
        assertEquals(3, Plano.semanaDoMes(LocalDate.of(2026, 10, 31)))
    }

    @Test fun sugestao() {
        val s = Plano.sugerir(plano, contas, limites, hoje)
        assertEquals(130000L, s.fixasCentavos); assertEquals(120000L, s.limitesCentavos)
        assertEquals(350000L, s.totalMesCentavos)
        assertEquals(27, s.diasTrabalhoMes)
        assertEquals(12963L, s.metaDiaCentavos) // 3500/27 = 129,63 (arredonda para cima)
        assertEquals(6, s.diasTrabalhoSemana) // seg 12 a sáb 17 (domingo 18 é folga)
        assertEquals(12963L * 6, s.metaSemanaCentavos)
        assertEquals(12963.0 / 3000, s.horasDia!!, 0.001)
    }

    @Test fun envelopeERecalculo() {
        val gastos = listOf(
            Gasto(data = "2026-10-05", categoria = CategoriaGasto.OUTRO, valorCentavos = 80000, limiteId = supermercado.id),
            Gasto(data = "2026-10-17", categoria = CategoriaGasto.OUTRO, valorCentavos = 30000, limiteId = supermercado.id),
            Gasto(data = "2026-09-30", categoria = CategoriaGasto.OUTRO, valorCentavos = 99999, limiteId = supermercado.id), // outro mês
        )
        val env = Plano.envelopes(limites, gastos, hoje)
        val sup = env.first { it.limite.id == supermercado.id }
        assertEquals(110000L, sup.gastoCentavos); assertTrue(sup.excedido)
        assertEquals(10000L, sup.excessoCentavos); assertEquals(0L, sup.restanteCentavos)
        assertEquals(20000L, env.first { it.limite.nome == "Lanche" }.restanteCentavos)

        val r = Plano.recalcular(plano, contas, limites, env, liquidoMesCentavos = 200000, hoje = LocalDate.of(2026, 10, 19))
        assertEquals(360000L, r.necessarioMesCentavos) // 3.500 + 100 que passou
        assertEquals(160000L, r.faltaCentavos)
        assertEquals(12, r.diasRestantes) // 19..31 sem o domingo 25
        assertEquals(13334L, r.metaDiaCentavos) // 1600/12 = 133,34
    }

    @Test fun cincoFontesNoChat() {
        assertEquals(Comandos.Comando.Ganho(AppCorrida.GOGETA, 3000), Comandos.interpretar("30 na Gogeta"))
        assertEquals(Comandos.Comando.Ganho(AppCorrida.PARTICULAR, 5000), Comandos.interpretar("50 particular"))
        assertEquals(Comandos.Comando.Ganho(AppCorrida.PARTICULAR, 4000), Comandos.interpretar("corrida particular de 40"))
        assertEquals(Comandos.Comando.Ganho(AppCorrida.INDRIVE, 2550), Comandos.interpretar("25,50 no indrive"))
        assertEquals(5, Calculos.APPS.size)
    }
}
