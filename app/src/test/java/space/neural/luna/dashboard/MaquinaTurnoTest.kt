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

    @Test fun ontemTurno1FechadoTurno2NaoIniciadoHojeMostraTurno1() {
        val db = mutableListOf<Turno>()
        val ontem = "2026-10-17"
        val t1 = Turno(data = ontem, inicio = 0, ultimoEvento = 0, numero = 1, estado = EstadoTurno.FECHADO, fim = 60_000L)
        val t2 = Turno(data = ontem, inicio = 0, ultimoEvento = 0, numero = 2, estado = EstadoTurno.PRONTO)
        db += t1; db += t2
        val s = MaquinaTurno.situacao(db, hoje)
        assertEquals(1, s.numero)
        assertEquals(Fase.PRONTO, s.fase)
        assertNull(s.turno)
    }

    @Test fun ontemTurno2AtivoHojePreservaTurnoAtivo() {
        val db = mutableListOf<Turno>()
        val ontem = "2026-10-17"
        val t2 = Turno(data = ontem, inicio = 0, ultimoEvento = 0, numero = 2, estado = EstadoTurno.ATIVO)
        db += t2
        val s = MaquinaTurno.situacao(db, hoje)
        assertEquals(2, s.numero)
        assertEquals(Fase.ATIVO, s.fase)
        assertEquals(t2.id, s.turno?.id)
    }

    @Test fun hojeIniciaTurno1SemAlterarHistoricoDeOntem() {
        val db = mutableListOf<Turno>()
        val ontem = "2026-10-17"
        val fechado = Turno(data = ontem, inicio = 0, ultimoEvento = 0, numero = 1, estado = EstadoTurno.FECHADO, fim = 60_000L)
        val ativo = Turno(data = ontem, inicio = 0, ultimoEvento = 0, numero = 2, estado = EstadoTurno.ATIVO)
        db += fechado; db += ativo
        val hojeS = MaquinaTurno.situacao(db, hoje)
        assertEquals(2, hojeS.numero)
        assertEquals(Fase.ATIVO, hojeS.fase)
        val t1Hoje = MaquinaTurno.iniciar(MaquinaTurno.situacao(db, hoje), hoje, 10_000L)
        assertEquals(1, t1Hoje.numero)
        assertEquals(hoje, t1Hoje.data)
        assertEquals(2, MaquinaTurno.situacao(db, hoje).numero)
    }

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
        assertEquals(Fase.ULTIMO_FECHADO, s.fase); assertEquals(4, s.numero)
        assertEquals(4, db.size)
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
        assertEquals(0L, MaquinaTurno.trabalhoMs(z, 5 * h))
        val s = MaquinaTurno.situacao(db, hoje)
        assertEquals(Fase.PRONTO, s.fase); assertEquals(1, s.numero)
        val de = MaquinaTurno.iniciar(s, hoje, 3 * h)
        assertEquals(t.id, de.id)
        assertEquals(h, MaquinaTurno.trabalhoMs(de, 4 * h))
    }

    @Test fun tempoRecalculadoPeloRelogioDepoisDeFecharOApp() {
        var t = Turno(data = hoje, inicio = 0, ultimoEvento = 0, estado = EstadoTurno.ATIVO)
        t = MaquinaTurno.mudar(t, EstadoTurno.PAUSADO, h)
        t = MaquinaTurno.mudar(t, EstadoTurno.ATIVO, h + 15 * 60_000)
        val reaberto = t.copy()
        assertEquals(h + 3 * h, MaquinaTurno.trabalhoMs(reaberto, h + 15 * 60_000 + 3 * h))
        assertEquals(15 * 60_000L, reaberto.paradoMs)
    }

    @Test fun concluidoMostraDiaConcluido() {
        val t = Turno(data = hoje, inicio = 0, estado = EstadoTurno.CONCLUIDO, numero = 2, fim = h)
        assertEquals(Fase.CONCLUIDO, MaquinaTurno.situacao(listOf(t), hoje).fase)
        assertEquals(1, MaquinaTurno.situacao(listOf(t), "2026-10-19").numero)
    }
}
