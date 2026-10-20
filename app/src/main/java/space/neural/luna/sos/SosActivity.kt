package space.neural.luna.sos

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.LinearLayout
import android.widget.ScrollView
import space.neural.luna.dashboard.Cores
import space.neural.luna.dashboard.Ui

/**
 * Tela de SOS silencioso (PDF Ref. Visual 03, 42-44, 64-65).
 * Envia localização ao vivo para motoristas da sala sem tocar no celular.
 * Integração real com Firebase entra depois.
 */
class SosActivity : AppCompatActivity() {
    private lateinit var ui: Ui

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1A0A0A.toInt())
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
        }

        // Banner SOS
        val banner = ui.hbox().apply {
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            background = ui.fundo(0xFF8B1A1A.toInt(), 0xFFFF4444.toInt(), 8f)
        }
        banner.addView(ui.text("SOS — Marlon precisa de ajuda", 16f, Cores.BRANCO), ui.peso(1f))
        root.addView(banner, ui.mp(ui.dp(4)))

        // Mapa placeholder
        val mapa = ui.vbox(ui.dp(8)).apply {
            background = ui.fundo(Cores.alpha(Cores.CIANO, 0.08f), Cores.CIANO, 12f)
            setPadding(ui.dp(16), ui.dp(60), ui.dp(16), ui.dp(60))
            minimumHeight = ui.dp(200)
        }
        mapa.addView(ui.text("Rota ao vivo • 2,1 km • 4 min", 16f, Cores.BRANCO))
        mapa.addView(ui.text("3 motoristas a caminho", 13f, Cores.CIANO))
        root.addView(mapa, ui.mp(ui.dp(12)))

        // Card do motorista
        val card = ui.vbox(ui.dp(6)).apply {
            background = ui.fundo(Cores.alpha(Cores.VIOLETA, 0.12f), Cores.VIOLETA, 12f)
            setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
        }
        card.addView(ui.text("Marlon • Geely EX2 • Verde pistache", 15f, Cores.BRANCO))
        card.addView(ui.text("Em movimento • 34 km/h", 13f, Cores.BRANCO_70))
        card.addView(ui.text("Contato de emergência avisado", 13f, Cores.CIANO), ui.mp(ui.dp(4)))
        root.addView(card, ui.mp(ui.dp(12)))

        // Ações
        root.addView(ui.botao("Ir até ele", Ui.Estilo.CHEIO_VIOLETA, alto = true) {}, ui.mp(ui.dp(6)))
        root.addView(ui.botao("Ligar 190", Ui.Estilo.CONTORNO, alto = true) {}, ui.mp(ui.dp(6)))
        root.addView(ui.botao("Falar com o grupo", Ui.Estilo.CONTORNO, alto = true) {}, ui.mp(ui.dp(6)))

        root.addView(ui.text("Não ligue para Marlon: o celular dele não pode tocar.", 12f, Cores.BRANCO_70), ui.mp(ui.dp(12)))

        setContentView(ScrollView(this).apply { addView(root) })
    }
}
