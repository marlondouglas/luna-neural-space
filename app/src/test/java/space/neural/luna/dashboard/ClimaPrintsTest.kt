package space.neural.luna.dashboard

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.LinearLayout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Card de clima de GV nos 4 temas (sol, nublado, chuva, noite); com -Dluna.shots salva os prints. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-port-xxhdpi")
class ClimaPrintsTest {
    @Test fun quatroTemas() {
        val act = Robolectric.buildActivity(androidx.appcompat.app.AppCompatActivity::class.java).setup().get()
        act.setTheme(space.neural.luna.R.style.Theme_Luna)
        val ui = Ui(act)
        val acoes = java.lang.reflect.Proxy.newProxyInstance(AcoesPainel::class.java.classLoader, arrayOf(AcoesPainel::class.java)) { _, _, _ -> null } as AcoesPainel
        val agora = System.currentTimeMillis()
        val noticias = listOf(
            Externo.Noticia("Exemplo: feira de produtores volta à Ilha dos Araújos neste fim de semana", "DRD", "https://example.com/1", agora - 3_600_000, false),
            Externo.Noticia("Exemplo: BR-381 tem pista liberada após manutenção", "G1", "https://example.com/2", agora - 7_200_000, true),
        )
        val casos = listOf(Triple("sol", 0, 13), Triple("nublado", 3, 15), Triple("chuva", 63, 16), Triple("noite", 1, 21))
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40) }
        casos.forEach { (nome, cod, hora) ->
            val card = NoticiasCard(ui, false, acoes)
            card.bind(Externo.Dados(Externo.Clima(if (hora > 18) 22.6 else 30.2, cod, 19.0, 33.0, if (cod > 50) 85 else 10, emptyList()), noticias, agora), true, false, hora)
            col.addView(card.view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (col.childCount == 0) 0 else 40 })
            card.view.contentDescription = nome
        }
        act.setContentView(android.widget.ScrollView(act).apply { addView(col) })
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
        assertTrue(col.height > 0)
        val dir = System.getProperty("luna.shots") ?: return
        val b = Bitmap.createBitmap(col.width, col.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); FundoNeon().apply { setBounds(0, 0, col.width, col.height) }.draw(c); col.draw(c)
        File(dir).mkdirs(); File(dir, "clima-4-temas.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
