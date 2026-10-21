package space.neural.luna.pages

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import space.neural.luna.avatar.LunaVoz
import space.neural.luna.chat.ChatActivity
import space.neural.luna.chat.LunaConversa
import space.neural.luna.dashboard.Cores
import space.neural.luna.dashboard.Ui
import space.neural.luna.dashboard.telaSimples
import space.neural.luna.voz.ConversaVoz

/**
 * Página LUNA (separada do dashboard).
 * Reutiliza LunaConversa + ConversaVoz + LunaVoz já existentes.
 */
class LunaPageActivity : AppCompatActivity() {
    private lateinit var ui: Ui
    private lateinit var status: TextView
    private lateinit var resposta: TextView
    private val conversa by lazy { LunaConversa.get(this) }
    private val voz by lazy {
        ConversaVoz(this, object : ConversaVoz.Ouvinte {
            override fun ouvindo(parcial: String?) { status.text = parcial ?: "Ouvindo…" }
            override fun ouviu(texto: String) { enviar(texto) }
            override fun encerrou(motivo: String?) { status.text = motivo ?: "Pronto" }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val (raiz, c) = telaSimples(this, ui, "Luna")

        status = ui.text("Toque para falar com a Luna", 15f, Cores.BRANCO_70)
        c.addView(status, ui.mp(ui.dp(6)))

        resposta = ui.text("—", 16f, Cores.BRANCO)
        c.addView(resposta, ui.mp(ui.dp(8)))

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
            status.text = "Permissão de microfone necessária."
            return
        }
        if (voz.ativa || voz.escutando) {
            voz.parar("Parado")
            LunaVoz.parar()
        } else {
            LunaVoz.parar()
            voz.iniciar()
        }
    }

    private fun enviar(texto: String) {
        status.text = "Você: $texto"
        resposta.text = "Luna pensando…"
        lifecycleScope.launch {
            runCatching { conversa.enviar(texto, voz = true) }
                .onSuccess { r ->
                    resposta.text = r.texto
                    runCatching { LunaVoz.falar(this@LunaPageActivity, r.texto) }
                        .onFailure { status.text = "Resposta exibida (áudio indisponível)." }
                }
                .onFailure { resposta.text = "Erro: ${it.message ?: "não foi possível falar com a Luna"}" }
        }
    }

    override fun onDestroy() {
        voz.destruir()
        LunaVoz.parar()
        super.onDestroy()
    }
}
