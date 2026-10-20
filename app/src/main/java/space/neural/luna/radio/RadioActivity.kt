package space.neural.luna.radio

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.LinearLayout
import android.widget.ScrollView
import space.neural.luna.dashboard.Cores
import space.neural.luna.dashboard.Ui

/**
 * Tela do Rádio (PDF Ref. Visual 45-51, 55-57).
 * Mapa de motoristas, chat da sala, status Online/Ocupado/Offline.
 * Integração real com Firebase/LiveKit entra depois.
 */
class RadioActivity : AppCompatActivity() {
    private lateinit var ui: Ui

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Cores.OBSIDIAN)
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
        }

        // Header
        val header = ui.hbox()
        header.addView(ui.text("Rádio", 20f, Cores.BRANCO), ui.peso(1f))
        header.addView(ui.botao("AO VIVO", Ui.Estilo.CHEIO_VIOLETA) {})
        root.addView(header, ui.mp(ui.dp(4)))

        // Salas
        val salas = ui.hbox()
        listOf("Elétricos GV · 14", "Geral GV · 38", "Aeroporto GV · 6").forEach { s ->
            salas.addView(ui.botao(s, Ui.Estilo.CONTORNO) {}, ui.peso(1f, ui.dp(4)))
        }
        root.addView(salas, ui.mp(ui.dp(10)))

        // Mapa placeholder
        val mapa = ui.vbox(ui.dp(8)).apply {
            background = ui.fundo(Cores.alpha(Cores.CIANO, 0.1f), Cores.CIANO, 12f)
            setPadding(ui.dp(16), ui.dp(40), ui.dp(16), ui.dp(40))
            minimumHeight = ui.dp(180)
        }
        mapa.addView(ui.text("Mapa de Governador Valadares", 16f, Cores.BRANCO))
        mapa.addView(ui.text("14 motoristas online  •  3 eletropostos  •  2 alertas", 13f, Cores.BRANCO_70))
        root.addView(mapa, ui.mp(ui.dp(12)))

        // Status
        val status = ui.hbox()
        listOf("Online", "Ocupado", "Offline").forEachIndexed { i, s ->
            status.addView(ui.botao(s, if (i == 0) Ui.Estilo.CHEIO_VIOLETA else Ui.Estilo.CONTORNO) {}, ui.peso(1f, ui.dp(6)))
        }
        root.addView(status, ui.mp(ui.dp(8)))

        // Botão falar
        root.addView(ui.botao("Segure para falar", Ui.Estilo.CHEIO_VIOLETA, alto = true) {}, ui.mp(ui.dp(12)))

        // Chat da sala
        root.addView(ui.text("CHAT DA SALA", 13f, Cores.CIANO), ui.mp(ui.dp(8)))
        listOf(
            "Dona Cida" to "Alguém indo pro aeroporto? Tenho passageiro de volta",
            "Carla M." to "Blitz na Av. Minas Gerais, sentido Centro. Documento em dia, gente!",
            "Luna" to "Alagamento reportado perto da Ilha dos Araújos. Evitem a ponte.",
        ).forEach { (nome, msg) ->
            val linha = ui.vbox(ui.dp(2)).apply {
                setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
                background = ui.fundo(Cores.alpha(Cores.VIOLETA, 0.08f), Cores.VIOLETA, 8f)
            }
            linha.addView(ui.text(nome, 13f, Cores.CIANO))
            linha.addView(ui.text(msg, 14f, Cores.BRANCO))
            root.addView(linha, ui.mp(ui.dp(4)))
        }

        setContentView(ScrollView(this).apply { addView(root) })
    }
}
