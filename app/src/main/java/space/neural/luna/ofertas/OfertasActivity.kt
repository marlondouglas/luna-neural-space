package space.neural.luna.ofertas

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import space.neural.luna.dashboard.Cores
import space.neural.luna.dashboard.Ui

/**
 * Tela de Ofertas dos parceiros (PDF Ref. Visual 52-63).
 * Dados de exemplo por enquanto; catálogo real entra via Firestore depois.
 */
class OfertasActivity : AppCompatActivity() {
    private lateinit var ui: Ui

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Cores.OBSIDIAN)
            setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
        }

        root.addView(ui.text("Ofertas dos parceiros", 22f, Cores.BRANCO))
        root.addView(ui.text("Mockup — parceiros e preços fictícios", 13f, Cores.BRANCO_70), ui.mp(ui.dp(4)))

        val chips = ui.hbox()
        listOf("Todas", "Eletropostos", "Oficinas", "Lava-jato", "Alimentação").forEach { nome ->
            chips.addView(ui.botao(nome, Ui.Estilo.CONTORNO) {}, ui.peso(1f, ui.dp(6)))
        }
        root.addView(chips, ui.mp(ui.dp(12)))

        // Oferta destaque
        val destaque = ui.vbox(ui.dp(12)).apply {
            background = ui.fundo(Cores.alpha(Cores.VIOLETA, 0.15f), Cores.VIOLETA, 16f)
            setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
        }
        destaque.addView(ui.text("20% OFF", 28f, Cores.CIANO))
        destaque.addView(ui.text("na recarga rápida", 16f, Cores.BRANCO))
        destaque.addView(ui.text("EletroPonto Vale — EXEMPLO", 14f, Cores.BRANCO_70), ui.mp(ui.dp(6)))
        destaque.addView(ui.text("Para motoristas do app, seg a sex das 10h às 16h. Mostre esta tela no caixa.", 14f, Cores.BRANCO))
        destaque.addView(ui.text("Válido até 30/10  •  1,2 km", 13f, Cores.BRANCO_70), ui.mp(ui.dp(8)))
        destaque.addView(ui.botao("Chamar no WhatsApp", Ui.Estilo.CHEIO_VIOLETA) {})
        root.addView(destaque, ui.mp(ui.dp(16)))

        // Outras ofertas
        listOf(
            Triple("Oficina", "Auto Center Rota Certa", "Revisão + alinhamento por R$ 89"),
            Triple("Lava-jato", "Lava-Jato Brilho GV", "Lavagem completa R$ 35"),
            Triple("Alimentação", "Marmitaria do Motorista", "PF + suco por R$ 18"),
        ).forEach { (tipo, nome, desc) ->
            val card = ui.vbox(ui.dp(8)).apply {
                background = ui.fundo(Cores.alpha(Cores.VIOLETA, 0.08f), Cores.VIOLETA, 12f)
                setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
            }
            card.addView(ui.text("$tipo  •  $nome", 14f, Cores.CIANO))
            card.addView(ui.text(desc, 15f, Cores.BRANCO))
            card.addView(ui.botao("Ver no mapa", Ui.Estilo.CONTORNO) {}, ui.mp(ui.dp(6)))
            root.addView(card, ui.mp(ui.dp(10)))
        }

        setContentView(ScrollView(this).apply { addView(root) })
    }
}
