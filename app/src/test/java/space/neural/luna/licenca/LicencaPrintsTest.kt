package space.neural.luna.licenca

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import space.neural.luna.MainActivity
import space.neural.luna.conta.LoginActivity
import space.neural.luna.dashboard.FundoNeon
import java.io.File

/**
 * Telas da v0.1.6: login/cadastro, aviso do teste na tela inicial e "Seu teste grátis acabou".
 * Com -Dluna.shots=<pasta>, salva os prints (telefone em pé e central do carro deitada).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class LicencaPrintsTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun antes() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        LicencaActivity.semRede = true
    }

    @After fun depois() { Licenca.forcar = null; Licenca.checkoutForcado = null; LicencaActivity.semRede = false }

    private fun idle(n: Int = 10) = repeat(n) { shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200)) }

    private fun textos(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString()) + ((v as? EditText)?.hint?.toString()?.let { listOf(it) } ?: emptyList())
        is ViewGroup -> (0 until v.childCount).flatMap { textos(v.getChildAt(it)) }
        else -> emptyList()
    }.let { l -> if (v is ViewGroup || v is TextView) l else l }

    private fun visiveis(v: View): List<String> = if (v.visibility != View.VISIBLE) emptyList() else when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { visiveis(v.getChildAt(it)) }
        else -> emptyList()
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

    /** Conteúdo inteiro da rolagem (para a tela longa do telefone). */
    private fun printRolagem(raiz: View, nome: String) {
        fun achar(v: View): android.widget.ScrollView? = if (v is android.widget.ScrollView) v else (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { achar(g.getChildAt(it)) } }
        achar(raiz)?.getChildAt(0)?.let { print(it, nome) }
    }

    private fun login(nome: String, entrar: Boolean) {
        val act = Robolectric.buildActivity(LoginActivity::class.java, Intent(ctx, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_ENTRAR, entrar)).setup().get()
        idle()
        val raiz = act.window.decorView
        val v = visiveis(raiz)
        if (entrar) {
            assertTrue(v.contains("Esqueci a senha")); assertTrue(!v.contains("Confirmar senha"))
        } else {
            assertTrue(v.contains("Nome")); assertTrue(v.contains("Confirmar senha")); assertTrue(v.contains("Criar conta e começar 7 dias grátis"))
        }
        assertTrue(v.contains("E-mail ou celular"))
        // validação no próprio formulário (sem rede)
        if (!entrar) {
            act.findViewById<View>(android.R.id.content)
            val botao = achar(raiz) { it is android.widget.Button && it.text.toString().startsWith("Criar conta e") }!!
            botao.performClick(); idle(2)
            assertTrue(visiveis(raiz).contains("Digite seu nome."))
            // limpa o erro para o print
            val erroTv = achar(raiz) { it is TextView && it.text.toString() == "Digite seu nome." } as TextView
            erroTv.visibility = View.GONE; idle(1)
        }
        print(raiz, nome)
    }

    private fun achar(v: View, f: (View) -> Boolean): View? = if (f(v)) v else (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { achar(g.getChildAt(it), f) } }

    private fun expirado(nome: String) {
        Licenca.forcar = LicencaLogica.Decisao.Expirado("K7M2QX")
        val act = Robolectric.buildActivity(LicencaActivity::class.java).setup().get()
        idle()
        val raiz = act.window.decorView
        val v = visiveis(raiz)
        assertTrue(v.contains("Seu teste grátis acabou"))
        assertTrue(v.contains("R$ 7"))
        assertTrue(v.contains(PixBrCode.CHAVE_EXIBICAO))
        assertTrue(v.contains("Já paguei"))
        assertTrue(v.contains("K7M2QX"))
        assertNotNull(achar(raiz) { it is android.widget.ImageView && it.contentDescription == "QR Code Pix de R$ 7,00" })
        // link de pagamento (padrão embutido = checkout da InfinitePay) abre no navegador
        if (Licenca.checkoutForcado != "") {
            assertTrue(v.contains("Assinar R$ 7/mês")); assertTrue(v.contains("OU PAGUE DIRETO NO PIX"))
            (achar(raiz) { it is android.widget.Button && it.text == "Assinar R$ 7/mês" } as android.widget.Button).performClick()
            val i = shadowOf(act).nextStartedActivity
            assertEquals(space.neural.luna.BuildConfig.CHECKOUT_URL, i.dataString)
        } else {
            assertTrue(!v.contains("Assinar R$ 7/mês")); assertTrue(v.contains("PAGUE COM PIX"))
        }
        // copiar chave
        (achar(raiz) { it is android.widget.Button && it.text == "Pix copia e cola" } as android.widget.Button).performClick()
        val clip = ctx.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
        assertEquals(PixBrCode.payload(txid = PixBrCode.txid("K7M2QX")), clip)
        // voltar não sai do bloqueio
        act.onBackPressedDispatcher.onBackPressed(); idle(1)
        assertTrue(!act.isFinishing)
        print(raiz, nome)
        printRolagem(raiz, "$nome-inteira")
    }

    private fun banner(nome: String) {
        Licenca.forcar = LicencaLogica.Decisao.Teste(5, System.currentTimeMillis() + 5 * 86_400_000L, "K7M2QX")
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle(15)
        val raiz = act.window.decorView
        val b = achar(raiz) { it is TextView && it.text.toString().startsWith("Teste grátis: faltam 5 dias") }
        assertNotNull("aviso do teste", b); assertEquals(View.VISIBLE, b!!.visibility)
        print(raiz, nome)
    }

    /** Janela "Seu teste grátis acaba amanhã" na tela inicial (uma vez por limiar). */
    private fun janela(nome: String) {
        val z = java.time.ZoneId.systemDefault()
        val fim = java.time.ZonedDateTime.now(z).plusDays(1).withHour(14).withMinute(30).toInstant().toEpochMilli()
        ctx.getSharedPreferences("avisos_teste", 0).edit().clear().putBoolean("perm_perguntada", true).commit()
        Licenca.forcar = LicencaLogica.Decisao.Teste(2, fim, "K7M2QX")
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle(10)
        val d = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertNotNull("janela do aviso", d); assertTrue(d.isShowing)
        val v = visiveis(d.window!!.decorView)
        assertTrue(v.contains("Seu teste grátis acaba amanhã")); assertTrue(v.contains("Assinar R$ 7/mês")); assertTrue(v.contains("Depois"))
        val b = achar(act.window.decorView) { it is TextView && it.text.toString().startsWith("Teste grátis acaba amanhã") }
        assertNotNull("aviso verde com o texto do limiar", b)
        // print: tela inicial escurecida + janela por cima
        val dir = System.getProperty("luna.shots")
        if (dir != null) {
            val raiz = act.window.decorView
            val bmp = Bitmap.createBitmap(raiz.width, raiz.height, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            FundoNeon().apply { setBounds(0, 0, raiz.width, raiz.height) }.draw(cv); raiz.draw(cv)
            cv.drawColor(android.graphics.Color.argb(150, 0, 0, 0))
            val dv = d.window!!.decorView
            cv.save(); cv.translate((raiz.width - dv.width) / 2f, (raiz.height - dv.height) / 2f); dv.draw(cv); cv.restore()
            File(dir).mkdirs(); File(dir, "$nome.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // "Assinar" abre o link de pagamento
        (achar(d.window!!.decorView) { it is android.widget.Button && it.text == "Assinar R$ 7/mês" } as android.widget.Button).performClick()
        assertEquals(space.neural.luna.BuildConfig.CHECKOUT_URL, shadowOf(act).nextStartedActivity.dataString)
        // não aparece de novo no mesmo limiar
        d.dismiss()
        val antes = org.robolectric.shadows.ShadowDialog.getShownDialogs().size
        Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle(5)
        assertEquals(antes, org.robolectric.shadows.ShadowDialog.getShownDialogs().size)
    }

    private fun outras(nome: String) {
        Licenca.forcar = LicencaLogica.Decisao.Atualizar("https://marlondouglas.github.io/luna-neural-space/download.html")
        var act = Robolectric.buildActivity(LicencaActivity::class.java).setup().get(); idle()
        assertTrue(visiveis(act.window.decorView).contains("Atualize o app"))
        print(act.window.decorView, "$nome-atualize")
        Licenca.forcar = LicencaLogica.Decisao.SemConexao(true)
        act = Robolectric.buildActivity(LicencaActivity::class.java).setup().get(); idle()
        assertTrue(visiveis(act.window.decorView).contains("Confira a data e a hora"))
        print(act.window.decorView, "$nome-data-alterada")
        Licenca.forcar = LicencaLogica.Decisao.Teste(5, System.currentTimeMillis() + 5 * 86_400_000L, "K7M2QX")
        act = Robolectric.buildActivity(LicencaActivity::class.java, Intent(ctx, LicencaActivity::class.java).putExtra(LicencaActivity.EXTRA_ASSINAR, true)).setup().get(); idle()
        assertTrue(visiveis(act.window.decorView).contains("Assine o Rota GV"))
        print(act.window.decorView, "$nome-assinar")
    }

    @Test fun mainSemLicencaForcadaAbreNormal() {
        // nos testes (Application simples) a trava fica desligada: o app abre direto
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle(3)
        assertTrue(!act.isFinishing)
    }

    @Test fun mainBloqueiaQuandoVencido() {
        Licenca.forcar = LicencaLogica.Decisao.Expirado("K7M2QX")
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle(3)
        assertTrue(act.isFinishing)
        val prox = shadowOf(act).nextStartedActivity
        assertEquals(LicencaActivity::class.java.name, prox.component!!.className)
    }

    @Test @Config(qualifiers = "w411dp-h891dp-port-xxhdpi") fun telefoneRetrato() {
        login("login-criar-telefone", entrar = false)
        login("login-entrar-telefone", entrar = true)
        banner("teste-aviso-telefone")
        janela("teste-janela-amanha-telefone")
        expirado("teste-acabou-telefone")
        Licenca.checkoutForcado = ""
        expirado("teste-acabou-so-pix-telefone")
        Licenca.checkoutForcado = null
        outras("telefone")
    }

    @Test @Config(qualifiers = "w1280dp-h720dp-land-mdpi") fun centralPaisagem() {
        login("login-criar-central", entrar = false)
        login("login-entrar-central", entrar = true)
        banner("teste-aviso-central")
        janela("teste-janela-amanha-central")
        expirado("teste-acabou-central")
    }
}
