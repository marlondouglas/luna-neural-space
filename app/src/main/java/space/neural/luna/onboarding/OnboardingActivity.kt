package space.neural.luna.onboarding

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.LinearLayout
import android.widget.ScrollView
import space.neural.luna.dashboard.Cores
import space.neural.luna.dashboard.Ui

/**
 * Onboarding guiado (PDF partes 011-020).
 * Pergunta modalidade de uso e tipo de aparelho. Não apaga dados existentes.
 * Pode ser pulado. Preferências salvas em SharedPreferences.
 */
class OnboardingActivity : AppCompatActivity() {
    private lateinit var ui: Ui
    private val prefs by lazy { getSharedPreferences("onboarding", 0) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Cores.OBSIDIAN)
            setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(24))
        }

        root.addView(ui.text("Bem-vindo ao Rota GV", 22f, Cores.BRANCO))
        root.addView(ui.text("Escolha como você vai usar. Pode mudar depois nas Configurações.", 14f, Cores.BRANCO_70), ui.mp(ui.dp(8)))

        // Modalidade
        root.addView(ui.text("Tipo de uso", 16f, Cores.CIANO), ui.mp(ui.dp(16)))
        val mods = listOf(
            "MOTORISTA" to "Gestão completa de motorista",
            "FINANCEIRO" to "Somente controle financeiro",
            "OUTRO" to "Outro uso ou negócio"
        )
        mods.forEach { (id, nome) ->
            root.addView(ui.botao(nome, Ui.Estilo.CONTORNO, alto = true) {
                prefs.edit().putString("modalidade", id).apply()
            }, ui.mp(ui.dp(6)))
        }

        // Tipo de aparelho
        root.addView(ui.text("Onde está instalando?", 16f, Cores.CIANO), ui.mp(ui.dp(16)))
        val devices = listOf(
            "CELULAR" to "Celular",
            "TABLET" to "Tablet",
            "CARRO" to "Multimídia do carro"
        )
        devices.forEach { (id, nome) ->
            root.addView(ui.botao(nome, Ui.Estilo.CONTORNO, alto = true) {
                prefs.edit().putString("aparelho", id).apply()
            }, ui.mp(ui.dp(6)))
        }

        root.addView(ui.botao("Continuar", Ui.Estilo.CHEIO_VIOLETA, alto = true) {
            prefs.edit().putBoolean("onboarding_completo", true).apply()
            finish()
        }, ui.mp(ui.dp(20)))

        root.addView(ui.botao("Pular", Ui.Estilo.CONTORNO) {
            prefs.edit().putBoolean("onboarding_completo", true).apply()
            finish()
        }, ui.mp(ui.dp(8)))

        setContentView(ScrollView(this).apply { addView(root) })
    }

    companion object {
        fun precisa(context: Context): Boolean =
            !context.getSharedPreferences("onboarding", 0).getBoolean("onboarding_completo", false)

        fun abrir(context: Context) {
            context.startActivity(Intent(context, OnboardingActivity::class.java))
        }
    }
}
