package space.neural.luna.licenca

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import space.neural.luna.data.local.AppCorrida
import space.neural.luna.data.local.Ganho
import space.neural.luna.data.local.LunaDatabase
import java.io.File
import java.time.LocalDate

/** v0.1.6 apaga os dados das versões de teste UMA vez (e nunca mais). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class Limpeza016Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    private fun ganhos(): Int = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        LunaDatabase.get(ctx).query("SELECT COUNT(*) FROM ganhos", null).use { it.moveToFirst(); it.getInt(0) }
    }

    @org.junit.Before fun wm() { androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(ctx) }

    private fun semear() {
        runBlocking { space.neural.luna.data.repo.LunaRepositories(ctx).salvarGanho(Ganho(data = LocalDate.now().toString(), app = AppCorrida.UBER, valorCentavos = 5000)) }
        ctx.getSharedPreferences("dashboard", 0).edit().putString("periodo", "MES").commit()
        ctx.getSharedPreferences("luna_sync_uidVelho", 0).edit().putLong("ultimo", 1).commit()
        File(ctx.cacheDir, "luna_voz").apply { mkdirs() }.resolve("x.audio").writeText("abc")
    }

    @Test fun apagaUmaVezSo() {
        semear()
        assertEquals(1, ganhos())
        fecharBanco()
        var saiu = 0
        assertTrue(Limpeza016.executarSeNecessario(ctx) { saiu++ })
        assertEquals(1, saiu)
        assertEquals(0, ganhos())
        assertFalse(ctx.getSharedPreferences("dashboard", 0).contains("periodo"))
        assertFalse(ctx.getSharedPreferences("luna_sync_uidVelho", 0).contains("ultimo"))
        assertFalse(File(ctx.cacheDir, "luna_voz/x.audio").exists())
        assertTrue(ctx.getSharedPreferences(Limpeza016.PREFS, 0).getBoolean(Limpeza016.CHAVE, false))

        // Dados novos (contas novas) nunca mais são apagados.
        semear()
        fecharBanco()
        assertFalse(Limpeza016.executarSeNecessario(ctx) { saiu++ })
        assertEquals(1, saiu)
        assertEquals(1, ganhos())
        assertTrue(ctx.getSharedPreferences("dashboard", 0).contains("periodo"))
    }

    /** No app a limpeza roda antes de abrir o banco; no teste o banco já foi aberto para semear. */
    private fun fecharBanco() {
        val f = LunaDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }
        (f.get(null) as LunaDatabase?)?.close()
        f.set(null, null)
    }
}
