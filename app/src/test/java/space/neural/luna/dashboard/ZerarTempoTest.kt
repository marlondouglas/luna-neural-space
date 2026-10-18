package space.neural.luna.dashboard

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
import space.neural.luna.data.local.AppCorrida
import space.neural.luna.data.local.EstadoTurno
import space.neural.luna.data.local.Ganho
import space.neural.luna.data.local.MaquinaTurno
import space.neural.luna.data.local.MaquinaTurno.Fase
import space.neural.luna.data.repo.LunaRepositories
import java.io.File
import java.time.LocalDate

/** v0.1.5: "Zerar tempo" com as opções só este turno / voltar ao anterior / zerar o dia (com o banco Room de verdade). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-port-xxhdpi")
class ZerarTempoTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var repo: LunaRepositories
    private lateinit var acoes: TurnoAcoes
    private val hoje = LocalDate.now().toString()

    @Before fun limpar() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        repo = LunaRepositories(ctx); acoes = TurnoAcoes(repo)
        runBlocking { val p = Painel.observar(repo).first(); acoes.zerarDia(p.turnosTodos, p.situacao) }
    }

    private fun painel() = runBlocking { Painel.observar(repo).first() }
    private fun ganhosHoje() = runBlocking { repo.observeGanhos().first().filter { it.data == hoje } }

    /** Fecha os turnos 1 e 2 (o card fica no turno 3, pronto), com um ganho lançado no turno 2. */
    private fun doisFechados() = runBlocking {
        val t1 = acoes.iniciar(painel().situacao); acoes.fechar(repo.turnoPorId(t1.id)!!)
        val t2 = acoes.iniciar(painel().situacao)
        repo.salvarGanho(Ganho(data = hoje, app = AppCorrida.UBER, valorCentavos = 2500, turnoId = t2.id))
        acoes.fechar(repo.turnoPorId(t2.id)!!)
        t2
    }

    @Test fun voltarReabreOAnteriorEMantemGanhos() {
        val t2 = doisFechados()
        val s = painel().situacao
        assertEquals(3, s.numero); assertEquals(Fase.PRONTO, s.fase)
        val r = runBlocking { acoes.voltar(s, painel().turnosTodos) }!!
        assertEquals(t2.id, r.id)
        val s2 = painel().situacao
        assertEquals(2, s2.numero); assertEquals(Fase.ATIVO, s2.fase); assertNull(s2.turno!!.fim)
        assertEquals(t2.inicio, s2.turno!!.inicio)
        assertEquals(1, ganhosHoje().count { it.turnoId == t2.id })
    }

    @Test fun voltarComTurno3IniciadoApagaOTurno3EPassaOsGanhos() {
        val t2 = doisFechados()
        val t3 = runBlocking { acoes.iniciar(painel().situacao) }
        runBlocking { repo.salvarGanho(Ganho(data = hoje, app = AppCorrida.INDRIVE, valorCentavos = 1000, turnoId = t3.id)) }
        runBlocking { acoes.voltar(painel().situacao, painel().turnosTodos) }
        val p = painel()
        assertEquals(2, p.situacao.numero)
        assertTrue(p.turnosTodos.none { it.id == t3.id }) // apagado
        assertEquals(2, ganhosHoje().count { it.turnoId == t2.id })
    }

    @Test fun zerarDiaApagaTurnosEMantemGanhosEGastos() {
        doisFechados()
        val antes = ganhosHoje().sumOf { it.valorCentavos }
        val n = runBlocking { acoes.zerarDia(painel().turnosTodos, painel().situacao) }
        assertEquals(2, n)
        val p = painel()
        assertEquals(1, p.situacao.numero); assertEquals(Fase.PRONTO, p.situacao.fase)
        assertTrue(MaquinaTurno.doDia(p.turnosTodos, hoje).isEmpty())
        assertEquals(antes, ganhosHoje().sumOf { it.valorCentavos }) // nada de ganho apagado
        assertTrue(ganhosHoje().all { it.turnoId == null })
    }

    @Test fun zerarSoEsteTurnoContinuaNoMesmoNumero() {
        doisFechados()
        val t3 = runBlocking { acoes.iniciar(painel().situacao) }
        runBlocking { acoes.zerar(repo.turnoPorId(t3.id)!!) }
        val s = painel().situacao
        assertEquals(3, s.numero); assertEquals(EstadoTurno.PRONTO, s.turno!!.estado)
        assertEquals(0L, MaquinaTurno.trabalhoMs(s.turno, System.currentTimeMillis()))
    }

    // ---------- Tela: botão no card pronto + diálogo (e prints com -Dluna.shots) ----------

    private fun idle() = repeat(15) { shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200)) }

    private fun achar(v: View, f: (View) -> Boolean): View? = if (f(v)) v else
        (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { achar(g.getChildAt(it), f) } }

    @Test fun dialogoNoCardProntoENoTurnoAtivo() {
        doisFechados()
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle()
        // turno 3 pronto: agora tem "Zerar tempo" também
        val botao = achar(act.window.decorView) { it is Button && it.text == "Zerar tempo" }
        assertNotNull("botão Zerar tempo no turno pronto", botao)
        achar(act.window.decorView) { it.id == space.neural.luna.R.id.rolagem_principal }?.let { sv ->
            (sv as android.widget.ScrollView).getChildAt(0)?.let { print(it, "zerar-card-turno3-pronto") }
        }
        botao!!.performClick(); idle()
        val d = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val textos = mutableListOf<String>()
        achar(d.window!!.decorView) { if (it is Button) textos += it.text.toString(); false }
        assertTrue(textos.toString(), textos.containsAll(listOf("Voltar para o turno anterior", "Zerar o dia")))
        assertTrue(textos.none { it == "Zerar só este turno" }) // turno 3 nem começou
        print(d.window!!.decorView, "zerar-dialogo-turno3-pronto")
        d.dismiss()
        // turno 3 em andamento: as três opções
        runBlocking { acoes.iniciar(painel().situacao) }; idle()
        act.zerar(); idle()
        val d2 = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val t2 = mutableListOf<String>()
        achar(d2.window!!.decorView) { if (it is Button) t2 += it.text.toString(); false }
        assertTrue(t2.toString(), t2.containsAll(listOf("Zerar só este turno", "Voltar para o turno anterior", "Zerar o dia")))
        print(d2.window!!.decorView, "zerar-dialogo-turno3-ativo")
        // "Zerar o dia" pede segunda confirmação
        (achar(d2.window!!.decorView) { it is Button && it.text == "Zerar o dia" } as Button).performClick(); idle()
        val conf = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertTrue(conf !== d2)
        print(conf.window!!.decorView, "zerar-dialogo-confirmar-dia")
        conf.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); idle()
        runBlocking { kotlinx.coroutines.delay(300) }; idle()
        assertEquals(1, painel().situacao.numero)
        assertTrue(MaquinaTurno.doDia(painel().turnosTodos, hoje).isEmpty())
    }

    private fun print(v: View, nome: String) {
        val dir = System.getProperty("luna.shots") ?: return
        if (v.width <= 0 || v.height <= 0) return
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        FundoNeon().apply { setBounds(0, 0, v.width, v.height) }.draw(cv)
        v.draw(cv)
        File(dir).mkdirs()
        File(dir, "$nome.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
