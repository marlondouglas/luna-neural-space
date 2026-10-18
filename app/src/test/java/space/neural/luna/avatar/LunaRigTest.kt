package space.neural.luna.avatar

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class LunaRigTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    private fun diff(a: Bitmap, b: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        var s = 0.0
        for (y in y0 until y1) for (x in x0 until x1) {
            val p = a.getPixel(x, y); val q = b.getPixel(x, y)
            s += abs((p shr 16 and 255) - (q shr 16 and 255)) + abs((p shr 8 and 255) - (q shr 8 and 255)) + abs((p and 255) - (q and 255))
        }
        return s / ((x1 - x0) * (y1 - y0) * 3)
    }

    @Test fun bocaAbreMasOculosFicamRigidos() {
        val rig = LunaRig.get(ctx)
        assertNotNull(rig); rig!!
        val repouso = Bitmap.createBitmap(rig.size, rig.size, Bitmap.Config.ARGB_8888)
        val aberta = Bitmap.createBitmap(rig.size, rig.size, Bitmap.Config.ARGB_8888)
        rig.render(repouso, LunaRig.Estado())
        rig.render(aberta, LunaRig.Estado().apply { w[LipSync.AH] = 1f })
        // boca (centro inferior do rosto) muda; óculos (faixa dos olhos) e boné não mudam
        val boca = diff(repouso, aberta, 180, 310, 265, 345)
        val oculos = diff(repouso, aberta, 120, 180, 340, 232)
        val bone = diff(repouso, aberta, 100, 20, 350, 100)
        assertTrue("boca $boca", boca > 3.0)
        assertTrue("óculos $oculos", oculos < 0.5)
        assertTrue("boné $bone", bone < 0.5)
        // M/B/P fecha: quase igual ao repouso na abertura
        val fechada = Bitmap.createBitmap(rig.size, rig.size, Bitmap.Config.ARGB_8888)
        rig.render(fechada, LunaRig.Estado().apply { w[LipSync.AH] = 0.8f; w[LipSync.MBP] = 1f })
        assertTrue(diff(repouso, fechada, 180, 310, 265, 345) < boca / 2)
    }

    @Test fun piscadaFechaOsOlhos() {
        val rig = LunaRig.get(ctx)!!
        val a = Bitmap.createBitmap(rig.size, rig.size, Bitmap.Config.ARGB_8888)
        val b = Bitmap.createBitmap(rig.size, rig.size, Bitmap.Config.ARGB_8888)
        rig.render(a, LunaRig.Estado()); rig.render(b, LunaRig.Estado().apply { blink = 1f })
        assertTrue(diff(a, b, 145, 192, 312, 222) > 2.0)
    }

    @Test fun mocapCarregaEPisca() {
        val m = LunaMotor.Mocap.load(ctx)
        assertTrue(m.rot.size > 700)
        assertTrue((0 until 3000).any { m.piscada(it / 100.0) > 0.9f })
        assertTrue(m.rot.all { abs(it) <= 2.01f })
    }
}
