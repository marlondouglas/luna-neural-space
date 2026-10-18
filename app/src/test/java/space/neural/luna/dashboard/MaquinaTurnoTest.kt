package space.neural.luna.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import space.neural.luna.data.local.EstadoTurno
import space.neural.luna.data.local.MaquinaTurno
import space.neural.luna.data.local.MaquinaTurno.Fase
import space.neural.luna.data.local.Turno

class MaquinaTurnoTest {
    private val hoje = "2026-10-18"
    private val h = 3_600_000L

    /** Aplica uma sequência simulando o banco (lista de turnos). */
    private fun salvar(lista: MutableList<Turno>, t: Turno) { lista.removeAll { it.id == t.id }; lista += t }

    @Test fun quatroTurnosEDepoisSoRetomarOuConcluir() {
        val db = mutableListOf<Turno>()
        var agora = 1_000_000L
        for (n in 1..4) {
            val s = MaquinaTurno.situacao(db, hoje)
            assertEquals(n, s.numero); assertEquals(Fase.PRONTO, s.fase)
            val t = MaquinaTurno.iniciar(s, hoje, agora); salvar(db, t)
            assertEquals(Fase.ATIVO, MaquinaTurno.situacao(db, hoje).fase)
            agora += h
            salvar(db, MaquinaTurno.mudar(t, EstadoTurno.FECHADO, agora))
        }
        val s = MaquinaTurno.situacao(db, hoje)
        assertEquals(Fase.ULTIMO_FECHADO, s.fase); assertEquals(4, s.numero) // não existe 5º
        assertEquals(4, db.size)
        // Retomar estende o turno 4
        val t4 = s.turno!!
        val ret = MaquinaTurno.mudar(t4, EstadoTurno.ATIVO, agora + h)
        assertNull(ret.fim)
        salvar(db, ret)
        assertEquals(Fase.ATIVO, MaquinaTurno.situacao(db, hoje).fase)
        assertEquals(4, MaquinaTurno.situacao(db, hoje).numero)
        assertEquals(h + 30 * 60_000L, MaquinaTurno.trabalhoMs(ret, agora + h + 30 * 60_000L))
    }

    @Test fun fecharAvancaParaOProximoPronto() {
        val db = mutableListOf<Turno>()
        val t = MaquinaTurno.iniciar(MaquinaTurno.situacao(db, hoje), hoje, 0); salvar(db, t)
        salvar(db, MaquinaTurno.mudar(t, EstadoTurno.FECHADO, h))
        val s = MaquinaTurno.situacao(db, hoje)
        assertEquals(2, s.numero); assertEquals(Fase.PRONTO, s.fase); assertNull(s.turno)
        assertEquals(t.id, s.anterior?.id)
    }

    @Test fun zerarTempoVoltaAZeroEFicaPronto() {
        val db = mutableListOf<Turno>()
        val t = MaquinaTurno.iniciar(MaquinaTurno.situacao(db, hoje), hoje, 0); salvar(db, t)
        val z = MaquinaTurno.zerar(t, 2 * h); salvar(db, z)
        assertEquals(0L, MaquinaTurno.trabalhoMs(z, 5 * h)) // parado
        val s = MaquinaTurno.situacao(db, hoje)
        assertEquals(Fase.PRONTO, s.fase); assertEquals(1, s.numero)
        val de = MaquinaTurno.iniciar(s, hoje, 3 * h)
        assertEquals(t.id, de.id) // reaproveita o mesmo turno 1
        assertEquals(h, MaquinaTurno.trabalhoMs(de, 4 * h))
    }

    @Test fun tempoRecalculadoPeloRelogioDepoisDeFecharOApp() {
        // Começou 06:00, pausou 15 min, retomou; o app foi morto e reaberto 3 h depois.
        var t = Turno(data = hoje, inicio = 0, ultimoEvento = 0, estado = EstadoTurno.ATIVO)
        t = MaquinaTurno.mudar(t, EstadoTurno.PAUSADO, h)
        t = MaquinaTurno.mudar(t, EstadoTurno.ATIVO, h + 15 * 60_000)
        val reaberto = t.copy() // só o que está salvo no Room
        assertEquals(h + 3 * h, MaquinaTurno.trabalhoMs(reaberto, h + 15 * 60_000 + 3 * h))
        assertEquals(15 * 60_000L, reaberto.paradoMs)
    }

    @Test fun concluidoMostraDiaConcluido() {
        val t = Turno(data = hoje, inicio = 0, estado = EstadoTurno.CONCLUIDO, numero = 2, fim = h)
        assertEquals(Fase.CONCLUIDO, MaquinaTurno.situacao(listOf(t), hoje).fase)
        // outro dia: começa no turno 1
        assertEquals(1, MaquinaTurno.situacao(listOf(t), "2026-10-19").numero)
    }
}
