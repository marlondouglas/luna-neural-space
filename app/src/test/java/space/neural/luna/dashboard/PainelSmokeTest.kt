package space.neural.luna.dashboard

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import space.neural.luna.MainActivity
import space.neural.luna.chat.ChatActivity
import space.neural.luna.data.local.AppCorrida
import space.neural.luna.data.local.CategoriaGasto
import space.neural.luna.data.local.Ganho
import space.neural.luna.data.local.Gasto
import space.neural.luna.data.local.Meta
import space.neural.luna.data.local.TipoMeta
import space.neural.luna.data.repo.LunaRepositories
import java.io.File
import java.time.LocalDate

/**
 * Abre o dashboard e a conversa nos 4 tamanhos de tela (telefone retrato/paisagem,
 * tablet retrato, central do carro/tablet paisagem) e confere que nada quebra.
 * Com -Dluna.shots=<pasta>, salva prints de cada layout.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class PainelSmokeTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun dados() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        val repo = LunaRepositories(ctx)
        val hoje = LocalDate.now().toString()
        runBlocking {
            listOf(AppCorrida.NOVENTA_E_NOVE to 9000L, AppCorrida.UBER to 8000L, AppCorrida.INDRIVE to 4000L).forEach { (a, v) ->
                repo.salvarGanho(Ganho(data = hoje, app = a, valorCentavos = v))
            }
            repo.salvarGanho(Ganho(data = LocalDate.now().minusDays(1).toString(), app = AppCorrida.UBER, valorCentavos = 15000))
            repo.salvarGasto(Gasto(data = hoje, categoria = CategoriaGasto.ENERGIA, valorCentavos = 3240, kwh = 18.0))
            repo.salvarMeta(Meta(data = hoje, tipo = TipoMeta.DIA, valorCentavos = 22000))
            repo.salvarMeta(Meta(data = hoje, tipo = TipoMeta.SEMANA, valorCentavos = 120000))
        }
    }

    private fun abrir(nome: String) {
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(20) { shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200)) }
        val raiz = act.window.decorView
        assertTrue(raiz.width > 0)
        print(raiz, nome)
        listOf(space.neural.luna.R.id.rolagem_principal, space.neural.luna.R.id.rolagem_direita, space.neural.luna.R.id.rolagem_col2, space.neural.luna.R.id.rolagem_col3)
            .mapNotNull { act.findViewById<android.widget.ScrollView>(it) }
            .forEachIndexed { i, sv -> sv.getChildAt(0)?.let { if (it.height > 0) print(it, "$nome-rolagem$i") } }
        val chat = Robolectric.buildActivity(ChatActivity::class.java).setup().get()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        print(chat.window.decorView, "$nome-chat")
    }

    private fun print(v: View, nome: String) {
        val dir = System.getProperty("luna.shots") ?: return
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        File(dir).mkdirs()
        File(dir, "$nome.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port-xxhdpi") fun telefoneRetrato() = abrir("telefone-retrato")
    @Test @Config(qualifiers = "w891dp-h411dp-land-xxhdpi") fun telefonePaisagem() = abrir("telefone-paisagem")
    @Test @Config(qualifiers = "w800dp-h1280dp-port-mdpi") fun tabletRetrato() = abrir("tablet-retrato")
    @Test @Config(qualifiers = "w1280dp-h720dp-land-mdpi") fun centralPaisagem() = abrir("central-paisagem")
}
