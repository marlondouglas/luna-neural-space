package space.neural.luna.sync

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import space.neural.luna.MainActivity
import space.neural.luna.config.PlanejamentoActivity
import space.neural.luna.dashboard.FundoNeon
import space.neural.luna.dashboard.Painel
import space.neural.luna.dashboard.TurnoAcoes
import space.neural.luna.data.local.EstadoTurno
import space.neural.luna.data.local.LunaDatabase
import space.neural.luna.data.repo.LunaRepositories
import java.io.File

/**
 * Telas da v0.1.7: "Baixando seus dados…" (primeira descida) e "O turno já foi pausado no outro aparelho".
 * Com -Dluna.shots=<pasta>, salva os prints (telefone em pé e central do carro deitada).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class SyncTelasTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun antes() { WorkManagerTestInitHelper.initializeTestWorkManager(ctx) }
    @After fun depois() { PrimeiraCarga.forcar = null; RealtimeSync.guardaTeste = null }

    private fun idle(n: Int = 10) = repeat(n) { shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200)) }

    private fun visiveis(v: View): List<String> = if (v.visibility != View.VISIBLE) emptyList() else when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { visiveis(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun achar(v: View, f: (View) -> Boolean): View? = if (f(v)) v else (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { achar(g.getChildAt(it), f) } }

    private fun desenhar(v: View, sobre: View? = null): Bitmap {
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        FundoNeon().apply { setBounds(0, 0, v.width, v.height) }.draw(cv)
        v.draw(cv)
        if (sobre != null) {
            cv.drawColor(Color.argb(150, 0, 0, 0))
            cv.save(); cv.translate((v.width - sobre.width) / 2f, (v.height - sobre.height) / 2f); sobre.draw(cv); cv.restore()
        }
        return b
    }

    private fun salvar(b: Bitmap, nome: String) {
        val dir = System.getProperty("luna.shots") ?: return
        File(dir).mkdirs()
        File(dir, "$nome.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // ---------------- Baixando seus dados… ----------------

    private fun comInternet() {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
    }

    private fun baixando(nome: String) {
        PrimeiraCarga.forcar = false
        comInternet()
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle()
        val raiz = act.window.decorView
        val ov = raiz.findViewById<View>(space.neural.luna.R.id.baixando_dados)
        assertEquals(View.VISIBLE, ov.visibility)
        val v = visiveis(ov)
        assertTrue(v.toString(), v.contains("Baixando seus dados…"))
        assertTrue(v.any { it.startsWith("Seus ganhos, turnos, metas e planejamento estão vindo da nuvem") })
        salvar(desenhar(raiz), nome)
        // baixou: some sozinho
        PrimeiraCarga.forcar = true; PrimeiraCarga.marcar(ctx, "qualquer"); idle()
        assertEquals(View.GONE, ov.visibility)
        act.finish()
    }

    private fun baixandoSemInternet(nome: String) {
        PrimeiraCarga.forcar = false
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, null)
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle()
        val raiz = act.window.decorView
        val ov = raiz.findViewById<View>(space.neural.luna.R.id.baixando_dados)
        val v = visiveis(ov)
        assertTrue(v.toString(), v.contains("Continuar sem esperar"))
        salvar(desenhar(raiz), nome)
        (achar(ov) { it is Button && it.text == "Continuar sem esperar" } as Button).performClick(); idle(2)
        assertEquals(View.GONE, ov.visibility)
        act.finish()
    }

    // ---------------- Turno pausado no outro aparelho ----------------

    private fun pausadoNoOutro(nome: String) = runBlocking {
        val repo = LunaRepositories(ctx)
        val p0 = Painel.observar(repo).first()
        TurnoAcoes(repo).zerarDia(p0.turnosTodos, p0.situacao)
        val t = TurnoAcoes(repo).iniciar(Painel.observar(repo).first().situacao)
        // o "outro aparelho" (central) já pausou este turno
        val remoto = TurnoCompartilhado.Estado(t.copy(estado = EstadoTurno.PAUSADO, ultimoEvento = t.inicio + 25 * 60_000, trabalhoMs = 25 * 60_000, updatedAt = t.updatedAt + 1), 4, "central")
        RealtimeSync.guardaTeste = object : TurnoGuard {
            override suspend fun confirmar(alterados: List<space.neural.luna.data.local.Turno>): TurnoGuard.Resultado {
                LunaDatabase.get(ctx).turnoDao().upsert(remoto.turno) // o estado de lá vem para cá
                return TurnoGuard.Resultado.Conflito(remoto, TurnoCompartilhado.mensagem(remoto))
            }
        }
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle()
        // espera o painel carregar o turno do Room e toca em "Café"
        for (i in 0 until 30) { act.cafe(); idle(3); if (ShadowDialog.getLatestDialog()?.isShowing == true) break; Thread.sleep(50) }
        val d = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val textos = visiveis(d.window!!.decorView)
        assertTrue(textos.toString(), textos.contains("Mudou no outro aparelho"))
        assertTrue(textos.toString(), textos.any { it.startsWith("O turno já foi pausado no outro aparelho.") })
        // nada do café foi salvo: o turno está como o outro aparelho deixou
        assertEquals(EstadoTurno.PAUSADO, repo.turnoPorId(t.id)!!.estado)
        salvar(desenhar(act.window.decorView, d.window!!.decorView), nome)
        d.dismiss(); act.finish()
    }

    // ---------------- Planejamento travado ----------------

    @Test fun planejamentoNaoSalvaAntesDeBaixar() {
        kotlin.concurrent.thread { LunaDatabase.get(ctx).clearAllTables() }.join() // o banco é único no processo de teste
        PrimeiraCarga.forcar = false
        val ctl = Robolectric.buildActivity(PlanejamentoActivity::class.java).setup(); idle()
        val act = ctl.get()
        val ov = act.window.decorView.findViewById<View>(space.neural.luna.R.id.baixando_dados)
        assertEquals(View.VISIBLE, ov.visibility)
        // toque num dia da semana (por baixo do bloqueio) e sair da tela: nada é gravado
        (achar(act.window.decorView) { it is TextView && it.contentDescription?.startsWith("Semana 1, segunda") == true })?.performClick(); idle()
        ctl.pause().stop(); idle()
        assertNull(runBlocking { LunaDatabase.get(ctx).planejamentoDao().byId(space.neural.luna.data.local.Planejamento.ID) })
        ctl.destroy()
    }

    @Test fun planejamentoLiberaDepoisDeBaixar() {
        kotlin.concurrent.thread { LunaDatabase.get(ctx).clearAllTables() }.join()
        PrimeiraCarga.forcar = false
        val ctl = Robolectric.buildActivity(PlanejamentoActivity::class.java).setup(); idle()
        val ov = ctl.get().window.decorView.findViewById<View>(space.neural.luna.R.id.baixando_dados)
        PrimeiraCarga.forcar = true; PrimeiraCarga.marcar(ctx, "x"); idle()
        assertEquals(View.GONE, ov.visibility)
        (achar(ctl.get().window.decorView) { it is TextView && it.contentDescription?.startsWith("Semana 1, segunda") == true })!!.performClick(); idle()
        runBlocking { kotlinx.coroutines.delay(300) }; idle()
        assertTrue(runBlocking { LunaDatabase.get(ctx).planejamentoDao().byId(space.neural.luna.data.local.Planejamento.ID) } != null)
        ctl.pause().stop().destroy()
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port-xxhdpi") fun telefone() {
        baixando("baixando-dados-telefone")
        pausadoNoOutro("turno-pausado-outro-aparelho-telefone")
        baixandoSemInternet("baixando-dados-sem-internet-telefone")
    }

    @Test @Config(qualifiers = "w1280dp-h720dp-land-mdpi") fun central() {
        baixando("baixando-dados-central")
        pausadoNoOutro("turno-pausado-outro-aparelho-central")
        baixandoSemInternet("baixando-dados-sem-internet-central")
    }
}
