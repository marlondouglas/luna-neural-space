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

    // ---------------- v0.1.5: Zerar tempo (só este turno / voltar / zerar o dia) ----------------

    /** Dois turnos fechados: o card mostra o turno 3 pronto (sem registro). */
    private fun doisFechados(): Pair<MutableList<Turno>, Long> {
        val db = mutableListOf<Turno>()
        var agora = 1_000_000L
        for (n in 1..2) {
            val t = MaquinaTurno.iniciar(MaquinaTurno.situacao(db, hoje), hoje, agora); salvar(db, t)
            agora += h
            salvar(db, MaquinaTurno.mudar(t, EstadoTurno.FECHADO, agora))
        }
        return db to agora
    }

    @Test fun voltarLogoDepoisDeFecharContinuaContando() {
        val (db, fechou) = doisFechados()
        val s = MaquinaTurno.situacao(db, hoje)
        assertEquals(3, s.numero); assertEquals(Fase.PRONTO, s.fase); assertNull(s.turno)
        assertEquals(listOf(MaquinaTurno.OpcaoZerar.VOLTAR, MaquinaTurno.OpcaoZerar.DIA), MaquinaTurno.opcoesZerar(s, MaquinaTurno.doDia(db, hoje)))
        val v = MaquinaTurno.voltar(s, MaquinaTurno.doDia(db, hoje), fechou + 30_000)!!
        assertNull(v.excluir)
        assertEquals(2, v.reaberto.numero)
        assertEquals(EstadoTurno.ATIVO, v.reaberto.estado); assertNull(v.reaberto.fim)
        salvar(db, v.reaberto)
        val s2 = MaquinaTurno.situacao(db, hoje)
        assertEquals(2, s2.numero); assertEquals(Fase.ATIVO, s2.fase)
        // início e tempo salvos do turno 2 continuam; os 30 s desde o fechamento contam como trabalho
        assertEquals(1_000_000L + h, s2.turno!!.inicio)
        assertEquals(h + 30_000, MaquinaTurno.trabalhoMs(s2.turno, fechou + 30_000))
    }

    @Test fun voltarDepoisDeUmTempoReabrePausado() {
        val (db, fechou) = doisFechados()
        val s = MaquinaTurno.situacao(db, hoje)
        val v = MaquinaTurno.voltar(s, MaquinaTurno.doDia(db, hoje), fechou + h)!!
        assertEquals(EstadoTurno.PAUSADO, v.reaberto.estado)
        assertEquals(h, v.reaberto.trabalhoMs) // o intervalo não vira trabalho
        assertEquals(h, v.reaberto.paradoMs)
        assertEquals(h, MaquinaTurno.trabalhoMs(v.reaberto, fechou + 2 * h))
    }

    @Test fun voltarComTurnoAtualJaIniciadoJuntaOTempo() {
        val (db, fechou) = doisFechados()
        val t3 = MaquinaTurno.iniciar(MaquinaTurno.situacao(db, hoje), hoje, fechou + 10 * 60_000); salvar(db, t3)
        val agora = fechou + 40 * 60_000
        val s = MaquinaTurno.situacao(db, hoje)
        assertEquals(3, s.numero); assertEquals(Fase.ATIVO, s.fase)
        assertEquals(MaquinaTurno.OpcaoZerar.values().toList(), MaquinaTurno.opcoesZerar(s, MaquinaTurno.doDia(db, hoje)))
        val v = MaquinaTurno.voltar(s, MaquinaTurno.doDia(db, hoje), agora)!!
        assertEquals(t3.id, v.excluir!!.id)
        db.removeAll { it.id == t3.id }; salvar(db, v.reaberto)
        val s2 = MaquinaTurno.situacao(db, hoje)
        assertEquals(2, s2.numero); assertEquals(Fase.ATIVO, s2.fase)
        assertEquals(h + 30 * 60_000, MaquinaTurno.trabalhoMs(s2.turno, agora))
        assertEquals(10 * 60_000L, s2.turno!!.paradoMs) // intervalo entre fechar 2 e iniciar 3
    }

    @Test fun voltarComTurnoAtualPausadoContinuaPausado() {
        val (db, fechou) = doisFechados()
        var t3 = MaquinaTurno.iniciar(MaquinaTurno.situacao(db, hoje), hoje, fechou); salvar(db, t3)
        t3 = MaquinaTurno.mudar(t3, EstadoTurno.PAUSADO, fechou + 20 * 60_000); salvar(db, t3)
        val v = MaquinaTurno.voltar(MaquinaTurno.situacao(db, hoje), MaquinaTurno.doDia(db, hoje), fechou + 25 * 60_000)!!
        assertEquals(EstadoTurno.PAUSADO, v.reaberto.estado)
        assertEquals(h + 20 * 60_000, v.reaberto.trabalhoMs)
        assertEquals(5 * 60_000L, v.reaberto.paradoMs)
    }

    @Test fun voltarDepoisDeReabrirODia() {
        val (db, fechou) = doisFechados()
        // concluir o dia (tudo CONCLUIDO) e depois "Reabrir dia" (último volta a ATIVO)
        db.toList().forEach { salvar(db, MaquinaTurno.mudar(it, EstadoTurno.CONCLUIDO, fechou)) }
        assertEquals(Fase.CONCLUIDO, MaquinaTurno.situacao(db, hoje).fase)
        assertEquals(emptyList<MaquinaTurno.OpcaoZerar>(), MaquinaTurno.opcoesZerar(MaquinaTurno.situacao(db, hoje), MaquinaTurno.doDia(db, hoje)))
        val t2 = db.first { it.numero == 2 }
        salvar(db, MaquinaTurno.mudar(t2, EstadoTurno.ATIVO, fechou + 60_000))
        val s = MaquinaTurno.situacao(db, hoje)
        assertEquals(2, s.numero); assertEquals(Fase.ATIVO, s.fase)
        assertEquals(MaquinaTurno.OpcaoZerar.values().toList(), MaquinaTurno.opcoesZerar(s, MaquinaTurno.doDia(db, hoje)))
        val v = MaquinaTurno.voltar(s, MaquinaTurno.doDia(db, hoje), fechou + 2 * 60_000)!!
        assertEquals(1, v.reaberto.numero); assertEquals(t2.id, v.excluir!!.id)
        assertEquals(EstadoTurno.ATIVO, v.reaberto.estado)
        assertEquals(2 * h + 60_000, v.reaberto.trabalhoMs) // turno 1 (1 h) + turno 2 apagado (1 h + 1 min depois de reabrir)
    }

    @Test fun semVoltarNoTurno1() {
        val db = mutableListOf<Turno>()
        val t = MaquinaTurno.iniciar(MaquinaTurno.situacao(db, hoje), hoje, 1_000L); salvar(db, t)
        val s = MaquinaTurno.situacao(db, hoje)
        assertNull(MaquinaTurno.voltar(s, MaquinaTurno.doDia(db, hoje), 2_000L))
        assertEquals(listOf(MaquinaTurno.OpcaoZerar.SO_ESTE, MaquinaTurno.OpcaoZerar.DIA), MaquinaTurno.opcoesZerar(s, MaquinaTurno.doDia(db, hoje)))
        // sem nenhum turno hoje: nada a zerar
        assertEquals(emptyList<MaquinaTurno.OpcaoZerar>(), MaquinaTurno.opcoesZerar(MaquinaTurno.situacao(emptyList(), hoje), emptyList()))
    }

    @Test fun voltarApagaTurnoZeradoPronto() {
        val (db, fechou) = doisFechados()
        var t3 = MaquinaTurno.iniciar(MaquinaTurno.situacao(db, hoje), hoje, fechou); salvar(db, t3)
        t3 = MaquinaTurno.zerar(t3, fechou + 60_000); salvar(db, t3)
        val s = MaquinaTurno.situacao(db, hoje)
        assertEquals(3, s.numero); assertEquals(Fase.PRONTO, s.fase)
        val v = MaquinaTurno.voltar(s, MaquinaTurno.doDia(db, hoje), fechou + 90_000)!!
        assertEquals(t3.id, v.excluir!!.id); assertEquals(2, v.reaberto.numero)
    }
}
