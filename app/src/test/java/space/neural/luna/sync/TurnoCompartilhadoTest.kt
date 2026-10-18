package space.neural.luna.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.neural.luna.data.local.EstadoTurno
import space.neural.luna.data.local.MaquinaTurno
import space.neural.luna.data.local.Turno

class TurnoCompartilhadoTest {
    private fun t(estado: String, ult: Long = 10, deleted: Boolean = false, n: Int = 1) =
        Turno(id = "2026-10-18_$n", data = "2026-10-18", inicio = 1, estado = estado, ultimoEvento = ult, numero = n, updatedAt = ult, deleted = deleted)

    @Test fun decide() {
        val r = TurnoCompartilhado.Estado(t(EstadoTurno.PAUSADO), 5, "carro")
        assertEquals(TurnoCompartilhado.Decisao.Gravar(1), TurnoCompartilhado.decidir(null, 0, "cel"))
        assertEquals(TurnoCompartilhado.Decisao.Gravar(6), TurnoCompartilhado.decidir(r, 5, "cel"))
        assertEquals(TurnoCompartilhado.Decisao.Gravar(6), TurnoCompartilhado.decidir(r, 9, "cel"))
        assertEquals(TurnoCompartilhado.Decisao.Gravar(6), TurnoCompartilhado.decidir(r, 2, "carro")) // o próprio aparelho
        assertEquals(TurnoCompartilhado.Decisao.Conflito(r), TurnoCompartilhado.decidir(r, 4, "cel"))
    }

    @Test fun mensagens() {
        fun m(e: String, del: Boolean = false) = TurnoCompartilhado.mensagem(TurnoCompartilhado.Estado(t(e, deleted = del), 2, "x"))
        assertTrue(m(EstadoTurno.PAUSADO).startsWith("O turno já foi pausado no outro aparelho."))
        assertTrue(m(EstadoTurno.CAFE).contains("café"))
        assertTrue(m(EstadoTurno.ATIVO).startsWith("O turno 1 já está contando"))
        assertTrue(m(EstadoTurno.FECHADO).startsWith("O turno 1 já foi fechado"))
        assertTrue(m(EstadoTurno.CONCLUIDO).startsWith("O dia já foi concluído"))
        assertTrue(m(EstadoTurno.ATIVO, del = true).startsWith("O dia foi zerado"))
    }

    @Test fun representanteEMapa() {
        val a = t(EstadoTurno.FECHADO, 10, n = 1); val b = t(EstadoTurno.ATIVO, 20, n = 2)
        assertEquals(b, TurnoCompartilhado.representante(listOf(a, b)))
        assertEquals(a.id, TurnoCompartilhado.representante(listOf(a.copy(deleted = true)))!!.id)
        val e = TurnoCompartilhado.deMapa(TurnoCompartilhado.paraMapa(b, 3, "cel"))!!
        assertEquals(b.copy(dirty = false), e.turno); assertEquals(3L, e.versao); assertEquals("cel", e.deviceId)
    }

    @Test fun idDoTurnoFixo() {
        val s = MaquinaTurno.situacao(emptyList(), "2026-10-18")
        assertEquals("2026-10-18_1", MaquinaTurno.iniciar(s, "2026-10-18", 100).id)
        val fechado = t(EstadoTurno.FECHADO, 50)
        val s2 = MaquinaTurno.situacao(listOf(fechado), "2026-10-18")
        assertEquals("2026-10-18_2", MaquinaTurno.iniciar(s2, "2026-10-18", 100).id)
    }
}
