package space.neural.luna.sync

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import space.neural.luna.dashboard.TurnoAcoes
import space.neural.luna.data.local.AppCorrida
import space.neural.luna.data.local.EstadoTurno
import space.neural.luna.data.local.Ganho
import space.neural.luna.data.local.MaquinaTurno
import space.neural.luna.data.local.Planejamento
import space.neural.luna.data.local.Turno
import space.neural.luna.licenca.Limpeza016

/**
 * Celular (A) e central do carro (B) na mesma conta, falando com um servidor em memória.
 * Cobre os achados da investigação da 0.1.6 e o comportamento da 0.1.7.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DoisAparelhosTest {
    private val base get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var nuvem: FakeNuvem
    private lateinit var a: AparelhoTeste
    private lateinit var b: AparelhoTeste
    private val hoje = "2026-10-18"

    @Before fun antes() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(base)
        nuvem = FakeNuvem()
        a = AparelhoTeste(base, "celular", nuvem)
        b = AparelhoTeste(base, "central", nuvem)
    }

    @After fun depois() { a.db.close(); b.db.close(); Relogio.redefinirParaTestes() }

    private fun ganho(id: String, v: Long, t: Long, dirty: Boolean = true, deleted: Boolean = false) =
        Ganho(id = id, data = hoje, app = AppCorrida.UBER, valorCentavos = v, updatedAt = t, dirty = dirty, deleted = deleted)

    @Test fun aparelhoNovoBaixaTudoEMarcaPrimeiraCarga() = runBlocking {
        a.db.ganhoDao().upsert(ganho("g1", 5000, 1000))
        a.db.planejamentoDao().upsert(Planejamento(ganhoHoraCentavos = 4200, diasTrabalho = "1111100,1111100,1111100,1111100", updatedAt = 1000))
        a.engine.sincronizar()
        assertFalse(PrimeiraCarga.pronto(b.ctx, b.uid))
        b.engine.sincronizar()
        assertTrue(PrimeiraCarga.pronto(b.ctx, b.uid))
        assertEquals(5000L, b.db.ganhoDao().byId("g1")!!.valorCentavos)
        assertEquals(4200L, b.db.planejamentoDao().byId(Planejamento.ID)!!.ganhoHoraCentavos)
        assertFalse("o que veio da nuvem não sobe de novo", b.db.ganhoDao().byId("g1")!!.dirty)
    }

    @Test fun limpezaDa016NoSegundoAparelhoNaoApagaANuvem() = runBlocking {
        a.db.ganhoDao().upsert(ganho("g1", 5000, 1000))
        a.engine.sincronizar()
        val antes = nuvem.docs.keys.toSet()
        // primeira abertura da 0.1.6/0.1.7 no aparelho B
        assertTrue(Limpeza016.executarSeNecessario(b.ctx) {})
        b.engine.sincronizar()
        assertTrue(nuvem.docs.keys.containsAll(antes))
        assertFalse((nuvem.docs["ganhos/g1"]!!.data["deleted"] as Boolean))
        assertEquals(5000L, b.db.ganhoDao().byId("g1")!!.valorCentavos)
    }

    @Test fun envioAtrasadoNaoSobrescreveAlteracaoMaisNova() = runBlocking {
        a.db.ganhoDao().upsert(ganho("g1", 5000, 1000)); a.engine.sincronizar(); b.engine.sincronizar()
        // B edita sem internet às 2000; A edita online às 3000
        b.remote.online = false
        b.db.ganhoDao().upsert(ganho("g1", 6000, 2000))
        a.db.ganhoDao().upsert(ganho("g1", 7000, 3000)); a.engine.sincronizar()
        // B volta: na 0.1.6 o envio sobrescrevia os R$ 70 com R$ 60
        b.remote.online = true; b.engine.sincronizar()
        assertEquals(7000L, (nuvem.docs["ganhos/g1"]!!.data["valorCentavos"] as Number).toLong())
        assertEquals(7000L, b.db.ganhoDao().byId("g1")!!.valorCentavos)
        assertFalse(b.db.ganhoDao().byId("g1")!!.dirty)
        a.engine.sincronizar()
        assertEquals(7000L, a.db.ganhoDao().byId("g1")!!.valorCentavos)
    }

    @Test fun exclusaoNaoRessuscita() = runBlocking {
        a.db.ganhoDao().upsert(ganho("g1", 5000, 1000)); a.engine.sincronizar(); b.engine.sincronizar()
        b.remote.online = false
        b.db.ganhoDao().upsert(ganho("g1", 5500, 2000)) // edição antiga, offline
        a.db.ganhoDao().upsert(ganho("g1", 5000, 3000, deleted = true)); a.engine.sincronizar()
        b.remote.online = true; b.engine.sincronizar()
        assertTrue(b.db.ganhoDao().byId("g1")!!.deleted)
        assertEquals(true, nuvem.docs["ganhos/g1"]!!.data["deleted"])
    }

    @Test fun alteracaoOfflineMaisNovaSobe() = runBlocking {
        a.db.ganhoDao().upsert(ganho("g1", 5000, 1000)); a.engine.sincronizar(); b.engine.sincronizar()
        b.remote.online = false
        b.db.ganhoDao().upsert(ganho("g1", 8000, 5000))
        b.remote.online = true; b.engine.sincronizar(); a.engine.sincronizar()
        assertEquals(8000L, a.db.ganhoDao().byId("g1")!!.valorCentavos)
    }

    @Test fun planejamentoPadraoDoAparelhoNovoNaoApagaOdoOutro() = runBlocking {
        // A tem o planejamento de verdade, gravado às 1000
        a.db.planejamentoDao().upsert(Planejamento(ganhoHoraCentavos = 4200, rendaDesejadaCentavos = 300000, updatedAt = 1000))
        a.engine.sincronizar()
        // B baixa antes de qualquer edição (o painel e o Planejamento esperam a PrimeiraCarga)
        b.engine.sincronizar()
        assertTrue(PrimeiraCarga.pronto(b.ctx, b.uid))
        val p = b.db.planejamentoDao().byId(Planejamento.ID)!!
        assertEquals(4200L, p.ganhoHoraCentavos); assertEquals(300000L, p.rendaDesejadaCentavos)
    }

    @Test fun tempoRealChegaSemPuxar() = runBlocking {
        b.engine.sincronizar()
        val c = b.engine.colecao("ganhos")
        val desligar = b.remote.ouvir("ganhos", b.engine.cursor("ganhos")) { docs -> runBlocking { b.engine.aplicar(c, docs) } }
        a.db.ganhoDao().upsert(ganho("g9", 1234, 1000)); a.engine.subir()
        assertEquals(1234L, b.db.ganhoDao().byId("g9")!!.valorCentavos)
        desligar()
    }

    @Test fun configuracoesCompartilhadas() = runBlocking {
        a.ctx.getSharedPreferences(ConfigSync.PREFS_DASH, 0).edit().putLong("preco_kwh_centavos", 189).putBoolean("barge_in", false).commit()
        ConfigSync.mudouAqui(a.ctx); a.engine.sincronizar()
        b.engine.sincronizar()
        val bd = b.ctx.getSharedPreferences(ConfigSync.PREFS_DASH, 0)
        assertEquals(189L, bd.getLong("preco_kwh_centavos", 0))
        assertFalse("barge_in é do aparelho", bd.contains("barge_in"))
        // B muda depois: vence
        bd.edit().putLong("preco_kwh_centavos", 205).commit(); ConfigSync.mudouAqui(b.ctx); b.engine.sincronizar()
        a.engine.sincronizar()
        assertEquals(205L, a.ctx.getSharedPreferences(ConfigSync.PREFS_DASH, 0).getLong("preco_kwh_centavos", 0))
    }

    // ---------------- turno compartilhado ----------------

    private fun repo(x: AparelhoTeste) = RepoTeste(x)

    @Test fun pausadoNoOutroAparelho() = runBlocking {
        val ra = repo(a); val rb = repo(b)
        val acA = TurnoAcoes(ra.repo) { a.engine.turno }; val acB = TurnoAcoes(rb.repo) { b.engine.turno }
        b.engine.sincronizar()
        val t = acA.iniciar(MaquinaTurno.situacao(emptyList(), java.time.LocalDate.now().toString()))
        assertEquals("${java.time.LocalDate.now()}_1", t.id)
        b.engine.sincronizar() // B vê o turno contando
        assertEquals(EstadoTurno.ATIVO, b.db.turnoDao().byId(t.id)!!.estado)
        // A pausa; B ainda não recebeu e toca em "Café"
        acA.pausar(a.db.turnoDao().byId(t.id)!!)
        val e = runCatching { acB.cafe(b.db.turnoDao().byId(t.id)!!) }.exceptionOrNull()
        assertTrue(e is TurnoNoOutroAparelho)
        assertTrue(e!!.message!!.startsWith("O turno já foi pausado no outro aparelho."))
        // o estado de A veio para B, e o café não foi aplicado
        assertEquals(EstadoTurno.PAUSADO, b.db.turnoDao().byId(t.id)!!.estado)
        assertEquals(EstadoTurno.PAUSADO, TurnoCompartilhado.deMapa(nuvem.docs[TurnoCompartilhado.PATH]!!.data)!!.turno.estado)
        // tocando de novo, agora vale
        acB.retomar(b.db.turnoDao().byId(t.id)!!)
        a.engine.sincronizar()
        assertEquals(EstadoTurno.ATIVO, a.db.turnoDao().byId(t.id)!!.estado)
        assertFalse("confirmado na transação: não sobe de novo", b.db.turnoDao().byId(t.id)!!.dirty)
    }

    @Test fun mesmoAparelhoNaoConflitaConsigo() = runBlocking {
        val ac = TurnoAcoes(repo(a).repo) { a.engine.turno }
        val t = ac.iniciar(MaquinaTurno.situacao(emptyList(), java.time.LocalDate.now().toString()))
        ac.pausar(a.db.turnoDao().byId(t.id)!!); ac.retomar(a.db.turnoDao().byId(t.id)!!)
        assertEquals(3L, TurnoCompartilhado.deMapa(nuvem.docs[TurnoCompartilhado.PATH]!!.data)!!.versao)
    }

    @Test fun turnoOfflineReconcilia() = runBlocking {
        val acA = TurnoAcoes(repo(a).repo) { a.engine.turno }
        val t = acA.iniciar(MaquinaTurno.situacao(emptyList(), java.time.LocalDate.now().toString()))
        b.engine.sincronizar()
        // B sem internet: a guarda é local, a ação vale no aparelho
        val acB = TurnoAcoes(repo(b).repo) { TurnoGuard.Local }
        acB.pausar(b.db.turnoDao().byId(t.id)!!)
        assertTrue(b.db.turnoDao().byId(t.id)!!.dirty)
        b.engine.sincronizar()
        val e = TurnoCompartilhado.deMapa(nuvem.docs[TurnoCompartilhado.PATH]!!.data)!!
        assertEquals(EstadoTurno.PAUSADO, e.turno.estado); assertEquals(2L, e.versao)
        // A, que ainda não sabia, recebe conflito na próxima ação e fica com o estado de B
        val r = runCatching { acA.fechar(a.db.turnoDao().byId(t.id)!!) }.exceptionOrNull()
        assertTrue(r is TurnoNoOutroAparelho)
        assertEquals(EstadoTurno.PAUSADO, a.db.turnoDao().byId(t.id)!!.estado)
    }

    @Test fun doisAparelhosIniciamOMesmoTurnoSemDuplicar() = runBlocking {
        val dia = java.time.LocalDate.now().toString()
        val acA = TurnoAcoes(repo(a).repo) { TurnoGuard.Local }
        val acB = TurnoAcoes(repo(b).repo) { TurnoGuard.Local }
        acA.iniciar(MaquinaTurno.situacao(emptyList(), dia))
        Thread.sleep(5)
        acB.iniciar(MaquinaTurno.situacao(emptyList(), dia))
        a.engine.sincronizar(); b.engine.sincronizar(); a.engine.sincronizar()
        val ta = a.db.turnoDao().observeAllOnce(); val tb = b.db.turnoDao().observeAllOnce()
        assertEquals(1, ta.size); assertEquals(1, tb.size); assertEquals(ta[0], tb[0])
    }

    @Test fun estadoRemotoSoEntraSeMaisNovo() = runBlocking {
        val t = Turno(id = "x_1", data = hoje, inicio = 1, estado = EstadoTurno.ATIVO, updatedAt = 5000, dirty = true)
        a.db.turnoDao().upsert(t)
        a.engine.turno.aplicar(TurnoCompartilhado.Estado(t.copy(estado = EstadoTurno.PAUSADO, updatedAt = 4000), 7, "outro"))
        assertEquals(EstadoTurno.ATIVO, a.db.turnoDao().byId("x_1")!!.estado)
        assertEquals(7L, a.engine.turno.versaoVista())
        assertNull(nuvem.docs[TurnoCompartilhado.PATH])
        assertNotNull(a.db.turnoDao().byId("x_1"))
    }
}

/** Repositório real apontando para o Room em memória do aparelho de teste. */
class RepoTeste(x: AparelhoTeste) {
    val repo = space.neural.luna.data.repo.LunaRepositories(x.ctx, x.db)
}

private suspend fun space.neural.luna.data.local.TurnoDao.observeAllOnce(): List<Turno> = observeAll().first()
