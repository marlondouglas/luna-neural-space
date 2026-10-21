package space.neural.luna.pages

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import space.neural.luna.dashboard.Cores
import space.neural.luna.dashboard.Ui
import space.neural.luna.dashboard.telaSimples

class RadioActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = Ui(this)
        val (raiz, c) = telaSimples(this, ui, "Rádio dos Motoristas")
        c.addView(ui.text("Sala de voz (LiveKit).\nToken via endpoint seguro.\nIntegração completa pendente de configuração do servidor.", 15f, Cores.BRANCO), ui.mp(ui.dp(8)))
        setContentView(raiz)
    }
}
