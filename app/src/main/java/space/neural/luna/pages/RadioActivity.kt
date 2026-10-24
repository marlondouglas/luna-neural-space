package space.neural.luna.pages

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import space.neural.luna.dashboard.Cores
import space.neural.luna.dashboard.Ui
import space.neural.luna.dashboard.telaSimples
import space.neural.luna.voice.HttpTokenProvider
import space.neural.luna.voice.VoiceRoomManager

/**
 * Página RÁDIO DOS MOTORISTAS (LiveKit).
 * Separada do dashboard. Tokens via endpoint seguro (HttpTokenProvider).
 * PTT serializado para que stopTalking nunca fique atrás de um startTalking atrasado.
 */
class RadioActivity : AppCompatActivity() {
    private lateinit var ui: Ui
    private lateinit var status: TextView
    private lateinit var btnJoin: Button
    private lateinit var btnPtt: Button
    private lateinit var btnLeave: Button
    private val manager by lazy { VoiceRoomManager.get(this) }
    private val talkMutex = Mutex()
    private var talking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val (raiz, c) = telaSimples(this, ui, "Rádio dos Motoristas")

        status = ui.text("Desconectado", 16f, Cores.BRANCO)
        c.addView(status, ui.mp(ui.dp(8)))

        btnJoin = Button(this).apply { text = "Entrar na sala"; setOnClickListener { entrar() } }
        btnPtt = Button(this).apply {
            text = "Apertar para falar"
            isEnabled = false
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lifecycleScope.launch {
                            talkMutex.withLock {
                                runCatching { manager.startTalking() }
                                    .onSuccess { talking = true }
                                    .onFailure { status.text = "Erro ao ativar microfone: ${it.message}" }
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        manager.cancelTransmission()
                        lifecycleScope.launch {
                            talkMutex.withLock {
                                runCatching { manager.stopTalking() }
                                    .onSuccess { talking = false }
                                    .onFailure { status.text = "Erro ao desligar microfone: ${it.message}" }
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
        }
        btnLeave = Button(this).apply {
            text = "Sair da sala"
            isEnabled = false
            setOnClickListener { sair() }
        }
        c.addView(btnJoin, ui.mp(ui.dp(6)))
        c.addView(btnPtt, ui.mp(ui.dp(6)))
        c.addView(btnLeave, ui.mp(ui.dp(6)))

        setContentView(raiz)
        observar()
    }

    private fun entrar() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1002)
            status.text = "Permissão de microfone necessária."
            return
        }
        lifecycleScope.launch {
            runCatching {
                manager.join("rota-gv", "motorista-${System.currentTimeMillis() % 10000}", HttpTokenProvider())
            }.onFailure { status.text = "Erro ao entrar: ${it.message}" }
        }
    }

    private fun sair() {
        lifecycleScope.launch {
            pararMic()
            runCatching { manager.leave() }
        }
    }

    private suspend fun pararMic() {
        talkMutex.withLock {
            if (talking) {
                runCatching { manager.stopTalking() }
                talking = false
            }
        }
    }

    private fun observar() {
        lifecycleScope.launch {
            manager.state.collect { s ->
                when (s) {
                    is VoiceRoomManager.State.Disconnected -> {
                        status.text = "Desconectado"
                        btnJoin.isEnabled = true
                        btnPtt.isEnabled = false
                        btnLeave.isEnabled = false
                    }
                    is VoiceRoomManager.State.Connecting -> status.text = "Conectando…"
                    is VoiceRoomManager.State.Connected -> {
                        status.text = "Conectado em ${s.roomName}"
                        btnJoin.isEnabled = false
                        btnPtt.isEnabled = true
                        btnLeave.isEnabled = true
                    }
                    is VoiceRoomManager.State.Error -> status.text = "Erro: ${s.message}"
                }
            }
        }
    }

    override fun onPause() {
        manager.cancelTransmission()
        lifecycleScope.launch { pararMic() }
        super.onPause()
    }

    override fun onDestroy() {
        lifecycleScope.launch { pararMic() }
        super.onDestroy()
    }
}
