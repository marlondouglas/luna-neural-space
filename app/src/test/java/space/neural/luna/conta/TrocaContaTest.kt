package space.neural.luna.conta

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import space.neural.luna.conta.TrocaConta.Acao
import space.neural.luna.data.local.AppCorrida
import space.neural.luna.data.local.Ganho
import space.neural.luna.data.local.LunaDatabase
import space.neural.luna.sync.PrimeiraCarga

/** v0.1.7: outra conta no mesmo aparelho não vê (nem sobe) os dados da anterior. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TrocaContaTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()
    private val db get() = LunaDatabase.get(ctx)

    @Before fun wm() { androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(ctx) }

    @Test fun decisao() {
        assertEquals(Acao.MANTER, TrocaConta.decidir("ana", "ana", temDados = true, pendentes = 3))
        assertEquals(Acao.ADOTAR, TrocaConta.decidir(null, "ana", temDados = false, pendentes = 0))
        assertEquals(Acao.ADOTAR, TrocaConta.decidir(null, "ana", temDados = true, pendentes = 2))
        assertEquals(Acao.APAGAR, TrocaConta.decidir(null, "ana", temDados = true, pendentes = 0))
        assertEquals(Acao.APAGAR, TrocaConta.decidir("ana", "bia", temDados = true, pendentes = 5))
        assertEquals(Acao.APAGAR, TrocaConta.decidir("ana", "bia", temDados = false, pendentes = 0))
    }

    private fun ganho(id: String) = runBlocking { db.ganhoDao().upsert(Ganho(id = id, data = "2026-10-18", app = AppCorrida.UBER, valorCentavos = 100, updatedAt = 1)) }

    @Test fun outraContaApagaLocalMesmaContaMantem() = runBlocking {
        assertEquals(Acao.ADOTAR, TrocaConta.aoEntrar(ctx, "ana"))
        ganho("g-ana")
        // Ana sai e entra de novo: nada muda
        assertEquals(Acao.MANTER, TrocaConta.aoEntrar(ctx, "ana"))
        assertNotNull(db.ganhoDao().byId("g-ana"))
        // Bia entra no mesmo aparelho: os lançamentos da Ana saem daqui e a Bia baixa os dela
        PrimeiraCarga.marcar(ctx, "bia")
        ctx.getSharedPreferences("luna_sync_bia", 0).edit().putLong("lastPull_ganhos_s", 99).commit()
        assertEquals(Acao.APAGAR, TrocaConta.aoEntrar(ctx, "bia"))
        assertNull(db.ganhoDao().byId("g-ana"))
        assertFalse("Bia precisa baixar de novo", PrimeiraCarga.pronto(ctx, "bia"))
        assertFalse(ctx.getSharedPreferences("luna_sync_bia", 0).contains("lastPull_ganhos_s"))
        assertEquals("bia", TrocaConta.dono(ctx))
    }

    @Test fun migracaoDa016AdotaQuemEstavaLogado() = runBlocking {
        ganho("g1")
        TrocaConta.migrar(ctx, "ana")
        assertEquals(Acao.MANTER, TrocaConta.aoEntrar(ctx, "ana"))
        assertNotNull(db.ganhoDao().byId("g1"))
    }
}
