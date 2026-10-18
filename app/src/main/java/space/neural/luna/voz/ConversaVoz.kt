package space.neural.luna.voz

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import space.neural.luna.avatar.LunaVoz

/**
 * Conversa por voz contínua (v0.1.5), usada na tela inicial e na conversa:
 * ouvir → [Ouvinte.ouviu] → a tela pergunta à Luna → [falar] → quando a fala termina, ouve de novo.
 * Para quando: ~7 s sem ninguém falar, uma despedida ("tchau", "obrigado", "pode parar"),
 * ou toque na Luna / no botão (chamar [parar]).
 * Barge-in: enquanto ela fala, um [Vad] escuta o microfone com cancelador de eco; se você começar a
 * falar, a fala dela para na hora (boca fecha) e o reconhecedor abre para ouvir você.
 */
class ConversaVoz(private val act: Activity, private val ouvinte: Ouvinte) {
    interface Ouvinte {
        fun ouvindo(parcial: String?)
        fun ouviu(texto: String)
        fun encerrou(motivo: String?)
    }

    private val prefs = act.getSharedPreferences("dashboard", 0)
    private val main = Handler(Looper.getMainLooper())
    private var rec: SpeechRecognizer? = null
    private var barge: BargeIn? = null
    private var escutaDesde = 0L
    /** Conversa contínua ligada (entre o primeiro toque e o fim). */
    var ativa = false; private set
    var escutando = false; private set
    private val continua get() = prefs.getBoolean("conversa_continua", true)
    private val bargeInLigado get() = prefs.getBoolean("barge_in", true)

    fun disponivel() = SpeechRecognizer.isRecognitionAvailable(act)
    fun temPermissao() = act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Primeiro toque: para a Luna (se estiver falando) e começa a ouvir. */
    fun iniciar() {
        ativa = true
        LunaVoz.parar(); pararBarge()
        escutaDesde = SystemClock.elapsedRealtime()
        ouvir()
    }

    /** Toque na Luna / botão parar. */
    fun parar(motivo: String? = null) {
        val estava = ativa || escutando
        ativa = false; escutando = false
        pararBarge()
        runCatching { rec?.cancel() }
        modoComunicacao(false)
        if (estava) ouvinte.encerrou(motivo)
    }

    fun destruir() { parar(null); rec?.destroy(); rec = null }

    /** Fala a resposta; na conversa contínua, volta a ouvir só depois que a fala acabar. */
    fun falar(texto: String) {
        if (!ativa || !continua) { ativa = false; modoComunicacao(false); LunaVoz.comunicacao = false; LunaVoz.falar(act, texto); return }
        val comBarge = bargeInLigado && temPermissao()
        modoComunicacao(comBarge)
        LunaVoz.comunicacao = comBarge
        LunaVoz.falar(act, texto) {
            pararBarge()
            if (ativa) { escutaDesde = SystemClock.elapsedRealtime(); main.postDelayed({ if (ativa) ouvir() }, 250) } // 250 ms: o alto-falante esvazia antes do mic abrir
            else modoComunicacao(false)
        }
        if (comBarge) {
            barge = BargeIn(act) {
                // você começou a falar: ela cala (a boca fecha no LunaMotor.calar) e o reconhecedor abre
                LunaVoz.parar(); pararBarge()
                if (ativa) { escutaDesde = SystemClock.elapsedRealtime(); main.postDelayed({ if (ativa) ouvir() }, 120) } // 120 ms: libera o mic
            }.also { if (!it.iniciar()) barge = null }
        }
    }

    private fun pararBarge() { barge?.parar(); barge = null }

    private fun ouvir() {
        if (!ativa) return
        modoComunicacao(false)
        LunaVoz.comunicacao = false
        val r = rec ?: SpeechRecognizer.createSpeechRecognizer(act).also { rec = it }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { escutando = true; ouvinte.ouvindo(null) }
            override fun onBeginningOfSpeech() { escutaDesde = SystemClock.elapsedRealtime() }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { ouvinte.ouvindo(it) }
            }
            override fun onError(error: Int) {
                escutando = false
                if (!ativa) return
                val semFala = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                val calado = SystemClock.elapsedRealtime() - escutaDesde
                when {
                    semFala && calado < Encerrar.SILENCIO_MS -> main.postDelayed({ ouvir() }, 150) // ainda dentro dos ~7 s: continua ouvindo
                    semFala -> parar("Fiquei sem ouvir nada, então parei. Toque para falar de novo.")
                    error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> parar("Sem permissão do microfone.")
                    error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT -> main.postDelayed({ ouvir() }, 400)
                    else -> parar("Não ouvi direito. Toque para tentar de novo.")
                }
            }
            override fun onResults(b: Bundle?) {
                escutando = false
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                if (!ativa) return
                if (t.isNullOrBlank()) { main.postDelayed({ ouvir() }, 150); return }
                if (Encerrar.deveParar(t)) {
                    ativa = false
                    LunaVoz.comunicacao = false
                    ouvinte.encerrou("Até já, Marlon! 💜")
                    LunaVoz.falar(act, "Tá bom. Até já, Marlon!", fixa = true)
                    return
                }
                ouvinte.ouviu(t)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        escutando = true
        ouvinte.ouvindo(null)
        runCatching { r.startListening(intent) }.onFailure { parar("Reconhecimento de voz indisponível.") }
    }

    // ---------------- roteamento de áudio para o cancelador de eco ----------------
    private var modoAnterior: Int? = null

    @Suppress("DEPRECATION")
    private fun modoComunicacao(ligar: Boolean) {
        val am = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (ligar) {
            if (modoAnterior == null) modoAnterior = am.mode
            runCatching {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                // em modo chamada o Android manda para o fone de ouvido do telefone: escolhe Bluetooth/fone
                // se houver (carro), senão o alto-falante
                if (Build.VERSION.SDK_INT >= 31) {
                    val ds = am.availableCommunicationDevices
                    val pref = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                    pref.firstNotNullOfOrNull { t -> ds.firstOrNull { it.type == t } }?.let { am.setCommunicationDevice(it) }
                } else if (!am.isWiredHeadsetOn && !am.isBluetoothScoOn) am.isSpeakerphoneOn = true
            }
        } else modoAnterior?.let { m ->
            modoAnterior = null
            runCatching {
                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else am.isSpeakerphoneOn = false
                am.mode = m
            }
        }
    }
}

/** Microfone aberto durante a fala da Luna, com cancelador de eco e supressor de ruído, alimentando o [Vad]. */
class BargeIn(private val ctx: Context, private val aoFalar: () -> Unit) {
    @Volatile private var rodando = false
    private var th: Thread? = null

    @SuppressLint("MissingPermission")
    fun iniciar(): Boolean {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val taxa = 16_000
        val min = AudioRecord.getMinBufferSize(taxa, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val ar = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, taxa, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, taxa)) }.getOrNull() ?: return false
        if (ar.state != AudioRecord.STATE_INITIALIZED) { ar.release(); return false }
        val aec = if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(ar.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        val ns = if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(ar.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        rodando = true
        th = Thread({
            val vad = Vad()
            val q = ShortArray(taxa / 50) // 20 ms
            val f = FloatArray(q.size)
            runCatching {
                ar.startRecording()
                while (rodando) {
                    val n = ar.read(q, 0, q.size)
                    if (n <= 0) continue
                    for (i in 0 until n) f[i] = q[i] / 32768f
                    if (vad.quadro(f, n)) { Handler(Looper.getMainLooper()).post { if (rodando) { rodando = false; aoFalar() } }; break }
                }
            }
            runCatching { ar.stop() }; ar.release(); aec?.release(); ns?.release()
        }, "luna-barge-in").apply { priority = Thread.MAX_PRIORITY; start() }
        return true
    }

    fun parar() { rodando = false; th = null }
}
