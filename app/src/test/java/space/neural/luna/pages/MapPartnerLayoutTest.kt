package space.neural.luna.pages

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class MapPartnerLayoutTest {
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun render(width: Int, height: Int, partners: Boolean, fontScale: Float = 1f) {
        RuntimeEnvironment.setQualifiers("w${width}dp-h${height}dp-${if (width > height) "land" else "port"}-mdpi")
        RuntimeEnvironment.setFontScale(fontScale)
        val controller = if (partners) Robolectric.buildActivity(ParceirosActivity::class.java).setup()
            else Robolectric.buildActivity(MapRadioActivity::class.java).setup()
        val activity = controller.get()
        val root = activity.findViewById<View>(android.R.id.content)
        descendants(root).filterIsInstance<org.osmdroid.views.MapView>().forEach { it.setUseDataConnection(false) }
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        val dock = descendants(root).filterIsInstance<ViewGroup>().single { it.tag == "primary_navigation" }
        assertEquals(listOf("Início", "Mapa e Rádio", "Ofertas"), (0 until dock.childCount).map { dock.getChildAt(it).contentDescription.toString() })
        assertTrue((0 until dock.childCount).all { dock.getChildAt(it).height >= 48 })
        if (!partners) {
            val map = descendants(root).filterIsInstance<org.osmdroid.views.MapView>().single()
            assertTrue("Map must remain usable: ${map.height}", map.height >= height / 3)
        }
        val text = descendants(root).filterIsInstance<TextView>().joinToString("\n") { it.text }
        assertFalse(text.contains("Carla M.")); assertFalse(text.contains("14 motoristas"))
        assertFalse(text.contains("R$ 2,39")); assertFalse(text.contains("2/4 livres"))
        val folder = System.getProperty("luna.shots")?.let(::File)
        if (folder != null) {
            folder.mkdirs()
            val bitmap = Bitmap.createBitmap(width, height + 24, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); root.draw(canvas)
            val label = android.graphics.Paint().apply { color = space.neural.luna.dashboard.Cores.OBSIDIAN }
            canvas.drawRect(0f, height.toFloat(), width.toFloat(), height + 24f, label)
            label.color = space.neural.luna.dashboard.Cores.CIANO; label.textSize = 12f
            canvas.drawText(if (partners) "TESTE · catálogo sem ofertas confirmadas" else "TESTE de layout · mapa sem rede", 6f, height + 17f, label)
            File(folder, "${if (partners) "parceiros" else "mapa"}-${width}x${height}${if (fontScale > 1f) "-fonte-ampliada" else ""}-robolectric.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        controller.pause().stop().destroy()
    }
    @Test fun mapPortrait() = render(412, 915, false)
    @Test fun mapLandscape() = render(915, 412, false)
    @Test fun mapCarDisplay() = render(1280, 720, false)
    @Test fun partnersPortrait() = render(412, 915, true)
    @Test fun partnersLandscape() = render(915, 412, true)
    @Test fun partnersCarDisplay() = render(1280, 720, true)
    @Test fun mapLargeText() = render(412, 915, false, 1.6f)
    @Test fun partnersLargeText() = render(412, 915, true, 1.6f)
}
