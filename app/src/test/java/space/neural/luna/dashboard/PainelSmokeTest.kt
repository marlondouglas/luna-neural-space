package space.neural.luna.dashboard

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.flow.first
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
        // clima/notícias de exemplo (sem rede no teste): mostra o card de clima de GV
        val agoraMs = System.currentTimeMillis()
        val ex = org.json.JSONObject().put("em", agoraMs)
            .put("clima", org.json.JSONObject().put("t", 29.4).put("c", 1).put("min", 19.0).put("max", 33.0).put("ch", 20).put("h", org.json.JSONArray()))
            .put("n", org.json.JSONArray()
                .put(org.json.JSONObject().put("t", "Exemplo: obras na Av. Minas Gerais mudam o trânsito no Centro nesta semana").put("f", "DRD").put("l", "https://example.com/1").put("q", agoraMs - 3_600_000).put("b", false))
                .put(org.json.JSONObject().put("t", "Exemplo: BR-381 tem pista liberada após manutenção perto de Valadares").put("f", "G1").put("l", "https://example.com/2").put("q", agoraMs - 7_200_000).put("b", true)))
        ctx.getSharedPreferences("externo", 0).edit().putString("dados", ex.toString()).commit()
        if (semeado) return
        semeado = true
        runBlocking {
            listOf(AppCorrida.NOVENTA_E_NOVE to 9000L, AppCorrida.UBER to 8000L, AppCorrida.INDRIVE to 4000L).forEach { (a, v) ->
                repo.salvarGanho(Ganho(data = hoje, app = a, valorCentavos = v))
            }
            // Semana anterior variada, para o gráfico e as comparações
            val dias = listOf(15000L, 11000L, 17500L, 9000L, 20500L, 13000L, 16000L, 12000L)
            dias.forEachIndexed { i, v ->
                val d = LocalDate.now().minusDays(i + 1L).toString()
                repo.salvarGanho(Ganho(data = d, app = AppCorrida.UBER, valorCentavos = v / 2))
                repo.salvarGanho(Ganho(data = d, app = AppCorrida.NOVENTA_E_NOVE, valorCentavos = v / 3))
                repo.salvarGanho(Ganho(data = d, app = AppCorrida.INDRIVE, valorCentavos = v / 6))
                if (i % 2 == 0) repo.salvarGasto(Gasto(data = d, categoria = CategoriaGasto.ENERGIA, valorCentavos = 2800L + i * 100, kwh = 16.0))
            }
            repo.salvarGasto(Gasto(data = LocalDate.now().minusDays(3).toString(), categoria = CategoriaGasto.MANUTENCAO, valorCentavos = 4500))
            repo.salvarGasto(Gasto(data = LocalDate.now().minusDays(2).toString(), categoria = CategoriaGasto.OUTRO, valorCentavos = 1800, descricao = "Lavagem"))
            repo.salvarGasto(Gasto(data = hoje, categoria = CategoriaGasto.ENERGIA, valorCentavos = 3240, kwh = 18.0))
            val p0 = Painel.observar(repo).first()
            if (p0.situacao.fase == space.neural.luna.data.local.MaquinaTurno.Fase.PRONTO) TurnoAcoes(repo).iniciar(p0.situacao)
            val sup = space.neural.luna.data.local.Limite(nome = "Supermercado", limiteCentavos = 100000)
            repo.salvarLimite(sup); repo.salvarLimite(space.neural.luna.data.local.Limite(nome = "Lanche", limiteCentavos = 20000))
            repo.salvarGasto(Gasto(data = hoje, categoria = CategoriaGasto.OUTRO, valorCentavos = 72000, limiteId = sup.id, descricao = "Supermercado"))
            repo.salvarContaFixa(space.neural.luna.data.local.ContaFixa(nome = "Aluguel", valorCentavos = 120000, diaVencimento = 10))
            repo.salvarPlanejamento(space.neural.luna.data.local.Planejamento(rendaDesejadaCentavos = 100000))
            repo.salvarGanho(Ganho(data = hoje, app = AppCorrida.GOGETA, valorCentavos = 3500))
            repo.salvarGanho(Ganho(data = hoje, app = AppCorrida.PARTICULAR, valorCentavos = 6000))
            repo.salvarMeta(Meta(data = hoje, tipo = TipoMeta.DIA, valorCentavos = 22000))
            repo.salvarMeta(Meta(data = hoje, tipo = TipoMeta.SEMANA, valorCentavos = 120000))
        }
    }

    private fun abrir(nome: String) {
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(20) { shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200)) }
        val raiz = act.window.decorView
        assertTrue(raiz.width > 0)
        semEstouro(raiz)
        print(raiz, nome)
        listOf(space.neural.luna.R.id.rolagem_principal, space.neural.luna.R.id.rolagem_esquerda, space.neural.luna.R.id.rolagem_direita, space.neural.luna.R.id.rolagem_col1, space.neural.luna.R.id.rolagem_col2, space.neural.luna.R.id.rolagem_col3)
            .mapNotNull { act.findViewById<android.widget.ScrollView>(it) }
            .forEachIndexed { i, sv -> sv.getChildAt(0)?.let { if (it.height > 0) print(it, "$nome-rolagem$i") } }
        val chat = Robolectric.buildActivity(ChatActivity::class.java).setup().get()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        print(chat.window.decorView, "$nome-chat")
        if (nome == "telefone-retrato" || nome == "central-paisagem") {
            listOf(space.neural.luna.config.PlanejamentoActivity::class.java to "planejamento", RelatorioActivity::class.java to "relatorio").forEach { (c, n) ->
                val a = Robolectric.buildActivity(c).setup().get()
                repeat(10) { shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200)) }
                print(a.window.decorView, "$nome-$n")
                (a.window.decorView.findViewById<android.view.ViewGroup>(android.R.id.content)).let { content ->
                    fun achar(v: android.view.View): android.widget.ScrollView? = if (v is android.widget.ScrollView) v else (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { achar(g.getChildAt(it)) } }
                    achar(content)?.getChildAt(0)?.let { if (it.height > 0) print(it, "$nome-$n-rolagem") }
                }
            }
            // v0.1.5: Configurações (engrenagem), Luna ouvindo (conversa contínua), limites (toque no KPI)
            val cfg = Robolectric.buildActivity(space.neural.luna.config.ConfiguracoesActivity::class.java).setup().get()
            repeat(5) { shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200)) }
            print(cfg.window.decorView, "$nome-configuracoes")
            val lunaCard = MainActivity::class.java.getDeclaredField("luna").apply { isAccessible = true }.get(act) as LunaCard
            lunaCard.ouvindo("quanto falta pra meta")
            repeat(3) { shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }
            print(lunaCard.view, "$nome-luna-ouvindo")
            print(raiz, "$nome-home-ouvindo")
            MainActivity::class.java.getDeclaredMethod("abrirLimites").apply { isAccessible = true }.invoke(act)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            (org.robolectric.shadows.ShadowDialog.getLatestDialog())?.let { d -> print(d.window!!.decorView, "$nome-limites"); d.dismiss() }
            space.neural.luna.chat.ChatActivity::class.java.getDeclaredMethod("mostrarOuvindo", Boolean::class.java, String::class.java)
                .apply { isAccessible = true }.invoke(chat, true, "lança 30 na Uber")
            repeat(3) { shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }
            print(chat.window.decorView, "$nome-chat-ouvindo")
            val dia = Robolectric.buildActivity(LancamentosActivity::class.java, LancamentosActivity.dia(ctx, LocalDate.now().toString())).setup().get()
            repeat(10) { shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200)) }
            print(dia.window.decorView, "$nome-dia")
        }
    }

    private fun print(v: View, nome: String) {
        val dir = System.getProperty("luna.shots") ?: return
        if (v.width <= 0 || v.height <= 0) return
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        if (v.background == null) FundoNeon().apply { setBounds(0, 0, v.width, v.height) }.draw(cv)
        v.draw(cv)
        File(dir).mkdirs()
        File(dir, "$nome.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    companion object { private var semeado = false }

    @Test @Config(qualifiers = "w411dp-h891dp-port-xxhdpi") fun telefoneRetrato() = abrir("telefone-retrato")
    @Test @Config(qualifiers = "w891dp-h411dp-land-xxhdpi") fun telefonePaisagem() = abrir("telefone-paisagem")
    @Test @Config(qualifiers = "w800dp-h1280dp-port-mdpi") fun tabletRetrato() = abrir("tablet-retrato")
    @Test @Config(qualifiers = "w1280dp-h720dp-land-mdpi") fun centralPaisagem() = abrir("central-paisagem")
    /** Tablet do Marlon em paisagem (2000x1200 px, ~1333x800 dp) e 1280x800 dp. */
    @Test @Config(qualifiers = "w1333dp-h800dp-land-hdpi") fun tablet2000x1200() = abrir("tablet-2000x1200")
    @Test @Config(qualifiers = "w1280dp-h800dp-land-mdpi") fun tablet1280x800() = abrir("tablet-1280x800")

    /** Nada pode passar da borda direita: todo filho visível cabe na largura da janela. */
    private fun semEstouro(raiz: View) {
        val w = raiz.width
        fun ver(v: View) {
            if (v.visibility != View.VISIBLE) return
            val loc = IntArray(2); v.getLocationInWindow(loc)
            val dentroDeRolagemH = generateSequence(v.parent) { it.parent }.any { it is android.widget.HorizontalScrollView }
            if (!dentroDeRolagemH && v.width > 0) assertTrue("${v.javaClass.simpleName} ${v.contentDescription ?: ""} passa da borda: ${loc[0] + v.width} > $w", loc[0] + v.width <= w + 1)
            if (v is android.view.ViewGroup && v !is android.widget.HorizontalScrollView) for (i in 0 until v.childCount) ver(v.getChildAt(i))
        }
        ver(raiz)
    }
}
