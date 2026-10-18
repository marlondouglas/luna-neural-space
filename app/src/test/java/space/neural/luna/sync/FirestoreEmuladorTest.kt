package space.neural.luna.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import space.neural.luna.dashboard.TurnoAcoes
import space.neural.luna.data.local.AppCorrida
import space.neural.luna.data.local.EstadoTurno
import space.neural.luna.data.local.Ganho
import space.neural.luna.data.local.LunaDatabase
import space.neural.luna.data.local.MaquinaTurno
import space.neural.luna.data.repo.LunaRepositories
import java.util.UUID

/**
 * Dois aparelhos (duas instâncias do Firebase) contra o EMULADOR do Firestore, com o [FirestoreRemote] de verdade
 * (transações, consultas por serverUpdatedAt e ouvintes em tempo real).
 * Só roda com -Dluna.emulador=host:porta (ex.: 127.0.0.1:8090, projeto demo-rotagv-kotlin com regras abertas;
 * as regras do app são testadas à parte, em tools/firestore-rules-test).
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [35], application = Application::class)
class FirestoreEmuladorTest {
    private val base get() = ApplicationProvider.getApplicationContext<Application>()
    private val emu = System.getProperty("luna.emulador")
    private val uid = "emu-" + UUID.randomUUID().toString().take(8)
    private val hoje = java.time.LocalDate.now().toString()

    inner class Dev(val nome: String) {
        val fs: FirebaseFirestore
        val ctx = ContextoAparelho(base, nome)
        val db: LunaDatabase = Room.inMemoryDatabaseBuilder(base, LunaDatabase::class.java).allowMainThreadQueries().build()
        val engine: SyncEngine
        init {
            val app = FirebaseApp.getApps(base).firstOrNull { it.name == nome } ?: FirebaseApp.initializeApp(base,
                FirebaseOptions.Builder().setProjectId("demo-rotagv-kotlin").setApplicationId("1:1:android:$nome").setApiKey("fake-$nome").build(), nome)
            fs = FirebaseFirestore.getInstance(app)
            runCatching {
                val (h, p) = emu!!.split(":"); fs.useEmulator(h, p.toInt())
                fs.firestoreSettings = FirebaseFirestoreSettings.Builder().setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build()
            }
            engine = SyncEngine(ctx, db, FirestoreRemote(fs, uid), uid, "dev-$nome")
        }
        fun acoes() = TurnoAcoes(LunaRepositories(ctx, db)) { engine.turno }
    }

    private lateinit var a: Dev
    private lateinit var b: Dev

    @Before fun antes() {
        assumeTrue("emulador não configurado", !emu.isNullOrBlank())
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(base)
        a = Dev("celular"); b = Dev("central")
    }

    @After fun depois() { if (::a.isInitialized) { a.db.close(); b.db.close() } }

    /** Espera algo acontecer rodando a fila principal (os ouvintes do Firestore respondem nela). */
    private fun esperar(ms: Long = 8000, cond: () -> Boolean): Long {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < ms) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (cond()) return System.currentTimeMillis() - t0
            Thread.sleep(20)
        }
        throw AssertionError("tempo esgotado")
    }

    private fun ganho(id: String, v: Long, t: Long) = Ganho(id = id, data = hoje, app = AppCorrida.UBER, valorCentavos = v, updatedAt = t)

    @Test fun doisAparelhosNoEmulador() = runBlocking {
        // 1) A lança, B (aparelho novo) baixa tudo
        a.db.ganhoDao().upsert(ganho("g1", 5000, 1000)); a.engine.sincronizar()
        b.engine.sincronizar()
        assertEquals(5000L, b.db.ganhoDao().byId("g1")!!.valorCentavos)
        assertTrue(PrimeiraCarga.pronto(b.ctx, uid))

        // 2) envio atrasado (B offline) não sobrescreve o mais novo de A
        b.fs.disableNetwork().await()
        b.db.ganhoDao().upsert(ganho("g1", 6000, 2000))
        a.db.ganhoDao().upsert(ganho("g1", 7000, 3000)); a.engine.sincronizar()
        // (transações não passam pelo disableNetwork: o envio acontece e a transação vê a versão mais nova de A)
        runCatching { b.engine.subir() }
        b.fs.enableNetwork().await()
        b.engine.sincronizar()
        assertEquals(7000L, b.db.ganhoDao().byId("g1")!!.valorCentavos)
        val nuvem = a.fs.collection("users").document(uid).collection("ganhos").document("g1").get().await()
        assertEquals(7000L, nuvem.getLong("valorCentavos"))

        // 3) tempo real: B ouve, A lança, chega sem B puxar
        val c = b.engine.colecao("ganhos")
        val desligar = b.engine.remote.ouvir("ganhos", b.engine.cursor("ganhos")) { docs -> runBlocking { b.engine.aplicar(c, docs) } }
        val t0 = System.currentTimeMillis()
        a.db.ganhoDao().upsert(ganho("g2", 1234, 4000)); a.engine.subir()
        val ms = esperar { runBlocking { b.db.ganhoDao().byId("g2") } != null } + (System.currentTimeMillis() - t0 - 0)
        println("Tempo real no emulador: ganho de A apareceu em B em ~${ms} ms (incluindo o envio)")
        desligar()

        // 4) turno compartilhado: A pausa, B (sem saber) tenta o café → conflito
        val acA = a.acoes(); val acB = b.acoes()
        val t = acA.iniciar(MaquinaTurno.situacao(emptyList(), hoje))
        b.engine.sincronizar()
        acA.pausar(a.db.turnoDao().byId(t.id)!!)
        val e = runCatching { acB.cafe(b.db.turnoDao().byId(t.id)!!) }.exceptionOrNull()
        assertTrue("$e", e is TurnoNoOutroAparelho)
        assertTrue(e!!.message!!.startsWith("O turno já foi pausado no outro aparelho."))
        assertEquals(EstadoTurno.PAUSADO, b.db.turnoDao().byId(t.id)!!.estado)
        acB.retomar(b.db.turnoDao().byId(t.id)!!)
        assertFalse(b.db.turnoDao().byId(t.id)!!.dirty)
        a.engine.sincronizar()
        assertEquals(EstadoTurno.ATIVO, a.db.turnoDao().byId(t.id)!!.estado)

        // 5) relógio: hora do servidor do emulador
        a.engine.acertarRelogio(forcar = true)
    }
}
