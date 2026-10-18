package space.neural.luna.avatar

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import space.neural.luna.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.resume

/**
 * A voz da Luna em todo o app: pede o áudio ao Worker (POST /tts, Gemini TTS voz Kore; o Worker cai no
 * Workers AI se o Gemini falhar), guarda em cache no aparelho, calcula o lip sync (texto + áudio) e toca
 * com AudioTrack, avisando o [LunaMotor] da posição para a boca acompanhar. Sem internet: a voz do
 * Android é gravada em arquivo (synthesizeToFile) e passa pelo mesmo caminho, então a boca mexe igual.
 */
object LunaVoz {
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var track: AudioTrack? = null
    private var tts: TextToSpeech? = null
    private var ttsPronto = false
    private var foco: AudioFocusRequest? = null

    /**
     * v0.1.5 conversa por voz: toca como chamada (USAGE_VOICE_COMMUNICATION) para o cancelador de eco
     * do aparelho ter a referência do que a Luna fala e o microfone poder ficar aberto (barge-in).
     */
    @Volatile var comunicacao = false
    /** true do pedido do áudio até o fim da reprodução. */
    val ocupada get() = job?.isActive == true || track != null

    /** Para testes: substitui a busca do áudio. */
    var fonteTeste: (suspend (String) -> ByteArray?)? = null

    fun endpoint(): String {
        val chat = BuildConfig.LUNA_CHAT_ENDPOINT
        return if (chat.startsWith("https://")) chat.trimEnd('/').removeSuffix("/chat") + "/tts" else ""
    }

    /** Fala o [texto] (para qualquer fala anterior). [fixa] = frase fixa (cache longo no servidor). */
    fun falar(ctx: Context, texto: String, fixa: Boolean = false, aoTerminar: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        val limpo = texto.replace(Regex("[\\p{So}\\p{Cn}]"), "").replace(Regex("[*_#`]"), "").trim()
        if (limpo.isEmpty()) return
        parar()
        job = escopo.launch {
            val pcm = withContext(Dispatchers.IO) { obterAudio(app, limpo, fixa) }
            if (pcm == null) { falarSistema(app, limpo, aoTerminar); return@launch }
            val tr = withContext(Dispatchers.Default) { LipSync.analisar(limpo, pcm.amostras, pcm.taxa) }
            tocar(app, pcm, tr, aoTerminar)
        }
    }

    fun parar() {
        job?.cancel(); job = null
        track?.let { runCatching { it.stop() }; it.release() }; track = null
        tts?.stop()
        LunaMotor.calar()
    }

    class Pcm(val amostras: FloatArray, val taxa: Int)

    // ---------------- áudio ----------------
    private suspend fun obterAudio(ctx: Context, texto: String, fixa: Boolean): Pcm? {
        val dir = File(ctx.cacheDir, "luna_voz").apply { mkdirs() }
        val hash = MessageDigest.getInstance("SHA-1").digest("kore|$texto".toByteArray()).joinToString("") { "%02x".format(it) }
        val f = File(dir, "$hash.audio")
        if (f.exists() && f.length() > 100) { f.setLastModified(System.currentTimeMillis()); decodificar(f)?.let { return it } }
        val bytes = fonteTeste?.invoke(texto) ?: baixar(texto, fixa) ?: return null
        f.writeBytes(bytes)
        limparCache(dir)
        return decodificar(f)
    }

    /** Servidor sem voz (limite gratuito do Gemini TTS por minuto): até quando usar direto a voz do Android. */
    @Volatile private var semServidorAte = 0L

    private fun baixar(texto: String, fixa: Boolean): ByteArray? {
        val ep = endpoint(); if (ep.isEmpty()) return null
        if (System.currentTimeMillis() < semServidorAte) return null
        return runCatching {
            val c = (URL(ep).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 8_000; readTimeout = 30_000
                setRequestProperty("content-type", "application/json; charset=utf-8")
                setRequestProperty("User-Agent", "LunaNeuralSpace-Android")
            }
            try {
                c.outputStream.use { it.write(JSONObject().put("text", texto.take(600)).put("cache", fixa).toString().toByteArray()) }
                val code = c.responseCode
                if (code == 429 || code == 503) {
                    val seg = c.getHeaderField("retry-after")?.toLongOrNull()?.coerceIn(5, 120) ?: 20
                    semServidorAte = System.currentTimeMillis() + seg * 1000
                }
                if (code !in 200..299) null else c.inputStream.use { it.readBytes() }.takeIf { it.size > 100 }
            } finally { c.disconnect() }
        }.getOrNull()
    }

    private fun limparCache(dir: File) {
        val fs = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var tot = 0L
        fs.forEachIndexed { i, f -> tot += f.length(); if (i >= 60 || tot > 12L * 1024 * 1024) f.delete() }
    }

    /** WAV PCM 16 bits direto; outros formatos (MP3 do Workers AI) via MediaCodec. Mistura para mono. */
    fun decodificar(f: File): Pcm? = runCatching {
        val b = f.readBytes()
        if (b.size > 44 && String(b, 0, 4) == "RIFF" && String(b, 8, 4) == "WAVE") lerWav(b) else decodificarCodec(f)
    }.getOrNull()

    fun lerWav(b: ByteArray): Pcm? {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var p = 12; var canais = 1; var taxa = 24000; var bits = 16
        while (p + 8 <= b.size) {
            val id = String(b, p, 4); val len = bb.getInt(p + 4)
            if (id == "fmt ") { canais = bb.getShort(p + 10).toInt(); taxa = bb.getInt(p + 12); bits = bb.getShort(p + 22).toInt() }
            if (id == "data") {
                if (bits != 16) return null
                val n = (minOf(len, b.size - p - 8)) / 2 / canais
                val out = FloatArray(n)
                for (i in 0 until n) { var s = 0f; for (ch in 0 until canais) s += bb.getShort(p + 8 + (i * canais + ch) * 2) / 32768f; out[i] = s / canais }
                return Pcm(out, taxa)
            }
            p += 8 + len + (len and 1)
        }
        return null
    }

    private fun decodificarCodec(f: File): Pcm? {
        val ex = MediaExtractor(); ex.setDataSource(f.absolutePath)
        val tr = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
        ex.selectTrack(tr)
        val fmt = ex.getTrackFormat(tr)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        var taxa = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var canais = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val out = java.io.ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo(); var fimEntrada = false
        while (true) {
            if (!fimEntrada) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); fimEntrada = true }
                    else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { codec.outputFormat.let { taxa = it.getInteger(MediaFormat.KEY_SAMPLE_RATE); canais = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } }
            if (o >= 0) {
                val buf = codec.getOutputBuffer(o)!!; val chunk = ByteArray(info.size); buf.get(chunk); out.write(chunk)
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        codec.stop(); codec.release(); ex.release()
        val bb = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        val n = bb.remaining() / 2 / canais
        return Pcm(FloatArray(n) { i -> var s = 0f; for (c in 0 until canais) s += bb.getShort((i * canais + c) * 2) / 32768f; s / canais }, taxa)
    }

    // ---------------- reprodução ----------------
    private fun tocar(ctx: Context, pcm: Pcm, tr: LipSync.Track, aoTerminar: (() -> Unit)?) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attrs = AudioAttributes.Builder().setUsage(if (comunicacao) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        if (Build.VERSION.SDK_INT >= 26) {
            foco = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build().also { am.requestAudioFocus(it) }
        }
        val s16 = ShortArray(pcm.amostras.size) { (pcm.amostras[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
        val t = AudioTrack.Builder().setAudioAttributes(attrs)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(pcm.taxa).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(s16.size * 2, 4096)).setTransferMode(AudioTrack.MODE_STATIC).build()
        t.write(s16, 0, s16.size)
        val total = s16.size
        t.notificationMarkerPosition = maxOf(1, total - 1)
        t.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(a: AudioTrack) { fim(ctx, a, aoTerminar) }
            override fun onPeriodicNotification(a: AudioTrack) {}
        })
        track = t
        LunaMotor.falar(tr) { runCatching { t.playbackHeadPosition.toDouble() / pcm.taxa }.getOrDefault(-1.0) }
        t.play()
        // segurança: se o marcador não chegar, encerra pelo tempo
        Handler(Looper.getMainLooper()).postDelayed({ if (track === t) fim(ctx, t, aoTerminar) }, (total * 1000L / pcm.taxa) + 800)
    }

    private fun fim(ctx: Context, a: AudioTrack, aoTerminar: (() -> Unit)?) {
        if (track !== a) return
        track = null
        runCatching { a.stop() }; a.release()
        LunaMotor.calar()
        if (Build.VERSION.SDK_INT >= 26) foco?.let { (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it) }
        aoTerminar?.invoke()
    }

    // ---------------- reserva: voz do Android ----------------
    private suspend fun prepararSistema(ctx: Context): TextToSpeech? {
        tts?.let { if (ttsPronto) return it }
        return withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { cont ->
                var ref: TextToSpeech? = null
                ref = TextToSpeech(ctx) { st ->
                    ttsPronto = st == TextToSpeech.SUCCESS
                    if (ttsPronto) ref?.language = Locale.forLanguageTag("pt-BR")
                    if (cont.isActive) cont.resume(if (ttsPronto) ref else null)
                }
                tts = ref
            }
        }
    }

    private suspend fun falarSistema(ctx: Context, texto: String, aoTerminar: (() -> Unit)?) {
        val t = prepararSistema(ctx) ?: return
        val f = File(ctx.cacheDir, "luna_voz_sistema.wav")
        val ok = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<Boolean> { cont ->
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { if (cont.isActive) cont.resume(true) }
                    @Deprecated("") override fun onError(id: String?) { if (cont.isActive) cont.resume(false) }
                })
                if (t.synthesizeToFile(texto, null, f, "luna-arq") != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
            }
        } ?: false
        val pcm = if (ok) withContext(Dispatchers.IO) { decodificar(f) } else null
        if (pcm != null && pcm.amostras.size > 1000) {
            val tr = withContext(Dispatchers.Default) { LipSync.analisar(texto, pcm.amostras, pcm.taxa) }
            tocar(ctx, pcm, tr, aoTerminar)
        } else {
            // último recurso (sem boca sincronizada); o fim só é avisado quando a fala acaba de verdade,
            // para a conversa contínua não abrir o microfone com ela ainda falando
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { Handler(Looper.getMainLooper()).post { aoTerminar?.invoke() } }
                @Deprecated("") override fun onError(id: String?) { Handler(Looper.getMainLooper()).post { aoTerminar?.invoke() } }
            })
            t.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "luna")
        }
    }
}
