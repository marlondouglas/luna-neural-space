package space.neural.luna.pages

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import space.neural.luna.chat.ChatActivity
import space.neural.luna.dashboard.Cores
import space.neural.luna.dashboard.Ui
import space.neural.luna.dashboard.telaSimples
import space.neural.luna.voz.ConversaVoz

/**
 * Página LUNA (assistente de voz).
 * Separada do dashboard. Reutiliza ConversaVoz e ChatActivity existentes.
 */
class LunaPageActivity : AppCompatActivity() {
    private lateinit var ui: Ui
    private lateinit var status: TextView
    private val voz by lazy {
        ConversaVoz(this, object : ConversaVoz.Ouvinte {
            override fun ouvindo(parcial: String?) { status.text = parcial ?: "Ouvindo…" }
            override fun ouviu(texto: String) { status.text = "Você: $texto" }
            override fun encerrou(motivo: String?) { status.text = motivo ?: "Pronto" }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val (raiz, c) = telaSimples(this, ui, "Luna")

        status = ui.text("Toque para falar com a Luna", 16f, Cores.BRANCO)
        c.addView(status, ui.mp(ui.dp(8)))

        val btnFalar = Button(this).apply {
            text = "Falar com a Luna"
            setOnClickListener { falar() }
        }
        c.addView(btnFalar, ui.mp(ui.dp(8)))

        val btnChat = Button(this).apply {
            text = "Abrir conversa completa"
            setOnClickListener { startActivity(Intent(this@LunaPageActivity, ChatActivity::class.java)) }
        }
        c.addView(btnChat, ui.mp(ui.dp(8)))

        setContentView(raiz)
    }

    private fun falar() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1003)
            return
        }
        if (voz.ativa || voz.escutando) voz.parar("Parado") else voz.iniciar()
    }

    override fun onDestroy() {
        voz.destruir()
        super.onDestroy()
    }
}
