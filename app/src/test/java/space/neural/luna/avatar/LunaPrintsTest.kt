package space.neural.luna.avatar

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renderiza a Luna com o MESMO código do app (rig + motor + lip sync em tempo de execução):
 * folha com as poses e uma sequência de quadros falando a frase de teste. Com -Dluna.shots=<pasta>
 * grava os PNGs (o vídeo de prévia é montado com ffmpeg a partir deles).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class LunaPrintsTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun posesEFala() {
        val rig = LunaRig.get(ctx)!!
        val dir = System.getProperty("luna.shots")?.let { File(it) }
        // folha de poses
        val poses = listOf("repouso" to floatArrayOf(0f, 0f, 0f, 0f, 0f), "A" to floatArrayOf(1f, 0f, 0f, 0f, 0f), "E/I" to floatArrayOf(0f, 1f, 0f, 0f, 0f),
            "O" to floatArrayOf(0f, 0f, 1f, 0f, 0f), "F/V" to floatArrayOf(0f, 0f, 0f, 1f, 0f), "M/B/P" to floatArrayOf(.5f, 0f, 0f, 0f, 1f))
        val cel = 224
        val folha = Bitmap.createBitmap(cel * 3, cel * 2, Bitmap.Config.ARGB_8888)
        val cf = Canvas(folha); val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW; textSize = 16f }
        val tmp = Bitmap.createBitmap(rig.size, rig.size, Bitmap.Config.ARGB_8888)
        poses.forEachIndexed { i, (n, w) ->
            rig.render(tmp, LunaRig.Estado().apply { w.copyInto(this.w) })
            val crop = Bitmap.createBitmap(tmp, 120, 250, 210, 140)
            val x = (i % 3) * cel; val y = (i / 3) * cel
            cf.drawBitmap(Bitmap.createScaledBitmap(crop, cel, (cel * 140 / 210f).toInt(), true), x.toFloat(), y.toFloat(), null)
            cf.drawText(n, x + 6f, y + 18f, txt)
        }
        dir?.let { it.mkdirs(); File(it, "luna-poses-app.png").outputStream().use { o -> folha.compress(Bitmap.CompressFormat.PNG, 100, o) } }

        // fala: áudio de teste do Worker (/tts) + texto -> trilha -> quadros
        val pcm = LunaVoz.lerWav(javaClass.getResourceAsStream("/luna/fala_teste.wav")!!.use { it.readBytes() })!!
        val tr = LipSync.analisar("Oi, Marlon! Bora pra mais um turno? O café já tá pronto, hein!", pcm.amostras, pcm.taxa)
        LunaMotor.rigDe(ctx)
        var pos = 0.0
        LunaMotor.falar(tr) { pos }
        val des = LunaDesenho(ctx, 0.5f, 0.45f, 0.84f)
        val out = Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888); val c = Canvas(out)
        val fd = dir?.let { File(it, "luna_frames").apply { deleteRecursively(); mkdirs() } }
        val n = ((pcm.amostras.size.toDouble() / pcm.taxa + 1.0) * 30).toInt()
        for (i in 0 until n) {
            val t = i / 30.0
            pos = t - 0.5 // começa a falar em 0,5 s
            LunaMotor.passo(t + 3.0)
            c.drawColor(Color.rgb(12, 10, 28))
            assertTrue(des.desenhar(c, 240f, 240f, 232f))
            fd?.let { File(it, "%04d.png".format(i)).outputStream().use { o -> out.compress(Bitmap.CompressFormat.PNG, 100, o) } }
        }
        LunaMotor.calar()
    }
}
