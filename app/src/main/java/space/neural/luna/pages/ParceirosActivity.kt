package space.neural.luna.pages

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import space.neural.luna.dashboard.Cores
import space.neural.luna.dashboard.Ui
import space.neural.luna.dashboard.telaSimples

/**
 * Página PARCEIROS — eletropostos reais de Governador Valadares.
 * Preços só quando confirmados publicamente. Sem preço = "Preço não confirmado".
 */
class ParceirosActivity : AppCompatActivity() {
    data class Ponto(
        val nome: String,
        val endereco: String,
        val preco: String?, // null = não confirmado
        val potencia: String,
        val rede: String,
        val status: String
    )

    // Dados verificados em carregados.com.br e fontes públicas (out/2026)
    private val pontos = listOf(
        Ponto("Energiza RECHARGE", "Rua Marechal Deodoro, 130 - Centro", "R$ 2,39/kWh", "até 120 kW", "Rede Clube Charger", "Funcionando"),
        Ponto("Posto Rede HG - NeoCharge", "Rio-Bahia, 6295 - Planalto", "R$ 3,70/kWh", "28 kW", "Spott / NeoCharge", "Funcionando"),
        Ponto("Plug31 Recharge", "Av. Brasil, 4191 - Centro", null, "71 kW", "Plug31", "Aberto 24h"),
        Ponto("VA-e MOB / San Diego GV", "R. Ten.-Cel. Francisco Rodrigues, 51", "R$ 1,99/kWh", "7 kW", "Tupinambá", "Funcionando"),
        Ponto("Bigmais Lagoa Santa", "R. das Gaivotas, 665 - Lagoa Santa", "R$ 2,50/kWh", "40 kW", "PV MOB", "Funcionando"),
        Ponto("DF Charge São Pedro", "Rua Israel Pinheiro, 634 - São Pedro", "R$ 1,59/kWh", "80 kW", "DF Charge", "Funcionando"),
        Ponto("Posto Vila Isa - Uai Charge", "R. Nízio Peçanha Barcelos, 340", null, "79 kW", "Uai Charge", "Funcionando"),
        Ponto("Brandon Borborema", "Rua Prof. Antonio Albim, 13", "R$ 2,00/kWh", "7 kW", "On-Charge", "Funcionando"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = Ui(this)
        val (raiz, c) = telaSimples(this, ui, "Parceiros")

        c.addView(ui.text("Eletropostos em Governador Valadares", 16f, Cores.BRANCO, bold = true), ui.mp(ui.dp(4)))
        c.addView(ui.text("Preços só quando confirmados publicamente.", 12f, Cores.BRANCO_70), ui.mp(ui.dp(6)))

        pontos.forEach { p ->
            val card = ui.card(acento = Cores.ESMERALDA)
            card.addView(ui.text(p.nome, 15f, Cores.BRANCO, bold = true))
            card.addView(ui.text(p.endereco, 13f, Cores.BRANCO_70), ui.mp(ui.dp(2)))
            val precoTxt = p.preco ?: "Preço não confirmado"
            val corPreco = if (p.preco != null) Cores.ESMERALDA else Cores.LARANJA
            card.addView(ui.text(precoTxt, 14f, corPreco, bold = true), ui.mp(ui.dp(4)))
            card.addView(ui.text("${p.potencia} · ${p.rede}", 12f, Cores.BRANCO_70))
            card.addView(ui.text("● ${p.status}", 12f, Cores.ESMERALDA), ui.mp(ui.dp(2)))
            c.addView(card, ui.mp(ui.dp(8)))
        }

        val btnMapa = ui.botao("Ver parceiros no mapa", Ui.Estilo.CHEIO_VIOLETA) {
            startActivity(android.content.Intent(this, MapRadioActivity::class.java))
        }
        c.addView(btnMapa, ui.mp(ui.dp(8)))

        setContentView(raiz)
    }
}
