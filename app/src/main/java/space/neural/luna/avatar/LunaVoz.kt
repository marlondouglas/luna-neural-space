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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Saída única da Luna e da navegação. Só usa motor local explicitamente autorizado.
 * O caminho remoto legado foi desativado após auditoria por depender de Google/fallback.
 * Ausência de motor não aciona Android TTS/Google nem reutiliza cache Kore antigo.
 */
object LunaVoz {
    enum class PlaybackState { IDLE, PREPARING, PLAYING, UNAVAILABLE }
    private val playbackState = kotlinx.coroutines.flow.MutableStateFlow(PlaybackState.IDLE)
    val playback: kotlinx.coroutines.flow.StateFlow<PlaybackState> = playbackState
    @Volatile var firstAudioDelayMs: Long? = null; private set
    private var synthesisStartedAt = 0L
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var track: AudioTrack? = null
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

    private val queue = space.neural.luna.voz.SpeechQueuePolicy()
    private data class Request(val context: Context, val callbacks: space.neural.luna.voz.SpeechRequestCallbacks)
    private val requests = mutableMapOf<Long, Request>()
    private val afterInterruption = mutableListOf<() -> Unit>()
    private var sequence = 0L
    @Volatile private var cancellationGeneration = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var audioManager: AudioManager? = null
    @Volatile private var playbackVolume = 1f
    val estado get() = space.neural.luna.voz.LocalVoiceRuntime.state

    /** Compatibility API for existing assistant callers; no silent provider fallback. */
    fun falar(ctx: Context, texto: String, fixa: Boolean = false, aoTerminar: (() -> Unit)? = null) {
        falarComPrioridade(ctx, texto, space.neural.luna.voz.SpeechPriority.ASSISTANT, aoTerminar)
    }

    fun falarComPrioridade(ctx: Context, texto: String, priority: space.neural.luna.voz.SpeechPriority,
        aoTerminar: (() -> Unit)? = null): Boolean {
        return enqueue(ctx, texto, priority, null, aoTerminar)
    }

    /** Callbacks belong only to this request; start is emitted after its first rendered frame. */
    fun falarSincronizado(ctx: Context, texto: String, aoIniciar: (() -> Unit)?, aoTerminar: (() -> Unit)?): Boolean =
        enqueue(ctx, texto, space.neural.luna.voz.SpeechPriority.ASSISTANT, aoIniciar, aoTerminar)

    private fun enqueue(ctx: Context, texto: String, priority: space.neural.luna.voz.SpeechPriority,
        aoIniciar: (() -> Unit)?, aoTerminar: (() -> Unit)?): Boolean {
        val app = ctx.applicationContext
        val owner = space.neural.luna.conta.TrocaConta.dono(app)
        val callbacks = space.neural.luna.voz.SpeechRequestCallbacks(
            { space.neural.luna.conta.TrocaConta.dono(app) == owner }, aoIniciar, aoTerminar)
        val clean = texto.replace(Regex("[\\p{So}\\p{Cn}]"), "").replace(Regex("[*_#\u0060]"), "").trim()
        if (clean.isBlank()) { handler.post { callbacks.complete() }; return false }
        space.neural.luna.voz.SupertonicVoice.restore(ctx.applicationContext)
        val generation = cancellationGeneration
        handler.post {
            if (generation != cancellationGeneration || space.neural.luna.conta.TrocaConta.dono(app) != owner) { callbacks.complete(); return@post }
            if (queue.radioBusy && priority == space.neural.luna.voz.SpeechPriority.ASSISTANT) { callbacks.complete(); return@post }
            if (queue.shouldPreempt(priority)) {
                val previous = queue.active?.id
                stopPlayback(); queue.finish(); previous?.let { requests.remove(it)?.callbacks?.complete() }
            }
            val item = space.neural.luna.voz.SpeechQueuePolicy.Item(++sequence, priority, clean)
            if (queue.offer(item)) requests[item.id] = Request(app, callbacks) else callbacks.complete()
            completeRemovedRequests()
            drain()
        }
        return true
    }

    private fun drain() {
        val item = queue.next() ?: run {
            if (!queue.radioBusy && queue.active == null) {
                val callbacks = afterInterruption.toList(); afterInterruption.clear()
                callbacks.forEach { it() }
            }
            return
        }
        val request = requests[item.id] ?: run { queue.finish(); drain(); return }
        if (!request.callbacks.ownerStillValid()) { completed(item.id); return }
        synthesisStartedAt = android.os.SystemClock.elapsedRealtime()
        firstAudioDelayMs = null
        playbackState.value = PlaybackState.PREPARING
        job = escopo.launch {
            try {
                if (!space.neural.luna.voz.LocalVoiceRuntime.available && fonteTeste == null) {
                    val context = request.context.applicationContext
                    check(space.neural.luna.voz.SupertonicVoice.bundled(context)) { "O pacote de voz não está incluído nesta versão." }
                    val target = space.neural.luna.voz.SupertonicVoice.modelDirectory(context)
                    val ready = space.neural.luna.voz.SupertonicModelFiles.required.all { (name, entry) ->
                        space.neural.luna.voz.SupertonicModelFiles.verified(File(target, name), entry)
                    }
                    if (!ready) space.neural.luna.voz.SupertonicVoice.installBundled(context)
                    space.neural.luna.voz.SupertonicVoice.select(context, true)
                    check(space.neural.luna.voz.LocalVoiceRuntime.available) { "Não foi possível ativar a voz local neste aparelho." }
                }
                val audio = withContext(Dispatchers.Default) { space.neural.luna.voz.LocalVoiceRuntime.synthesize(item.text) }
                val pcm = audio?.let { Pcm(it.samples, it.sampleRate) } ?: fonteTeste?.invoke(item.text)?.let { bytes ->
                    withContext(Dispatchers.IO) {
                        val f = File.createTempFile("luna-test-", ".audio", request.context.cacheDir)
                        try { f.writeBytes(bytes); decodificar(f) } finally { f.delete() }
                    }
                }
                if (pcm == null) {
                    playbackState.value = PlaybackState.UNAVAILABLE
                    android.widget.Toast.makeText(request.context, estado, android.widget.Toast.LENGTH_LONG).show()
                    completed(item.id); return@launch
                }
                val lip = withContext(Dispatchers.Default) { LipSync.analisar(item.text, pcm.amostras, pcm.taxa) }
                if (!request.callbacks.ownerStillValid()) { completed(item.id); return@launch }
                tocar(request.context, pcm, lip, { if (queue.active?.id == item.id) request.callbacks.firstFrame() }) { completed(item.id) }
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) {
                playbackState.value = PlaybackState.UNAVAILABLE
                space.neural.luna.voz.LocalVoiceRuntime.reportUnavailable("Não foi possível reproduzir a voz local. Consulte o texto na tela.")
                completed(item.id)
            }
        }
    }
    private fun completed(id: Long) {
        if (queue.active?.id != id) return
        queue.finish(); requests.remove(id)?.callbacks?.complete(); drain()
        if (queue.active == null && playbackState.value != PlaybackState.UNAVAILABLE) playbackState.value = PlaybackState.IDLE
    }
    /** Called by radio TX and RX. Speech is deferred until both release the audio path. */
    fun radioOcupado(busy: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { radioOcupado(busy) }; return }
        queue.radioBusy = busy
        if (busy) {
            val active = queue.active
            stopPlayback(); queue.interruptForRadio()
            if (active != null) {
                if (active.priority != space.neural.luna.voz.SpeechPriority.ASSISTANT) queue.offer(active)
                else requests.remove(active.id)?.callbacks?.complete()
            }
            completeRemovedRequests()
        } else drain()
    }
    fun volume(value: Float) { playbackVolume = value.coerceIn(0f, 1f); handler.post { track?.setVolume(playbackVolume) } }
    private fun completeRemovedRequests() {
        val removed = requests.keys.filter { it !in queue.retainedIds }
        removed.forEach { requests.remove(it)?.callbacks?.complete() }
    }
    private fun stopPlayback() {
        job?.cancel(); job = null
        track?.let { runCatching { it.stop() }; it.release() }; track = null
        if (Build.VERSION.SDK_INT >= 26) foco?.let { audioManager?.abandonAudioFocusRequest(it) }
        foco = null; LunaMotor.calar()
        playbackState.value = PlaybackState.IDLE
    }
    fun cancelarNavegacao() { handler.post {
        if (queue.cancel(space.neural.luna.voz.SpeechPriority.MANEUVER)) stopPlayback()
        requests.keys.retainAll(queue.retainedIds); drain()
    } }
    fun parar() {
        cancellationGeneration++
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { parar() }; return }
        stopPlayback(); queue.clear(); val cancelled = requests.values.toList(); requests.clear(); afterInterruption.clear(); cancelled.forEach { it.callbacks.complete() }
    }

    class Pcm(val amostras: FloatArray, val taxa: Int)

    // PCM decoding is shared by the authorized local engine and isolated tests.
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
    private fun tocar(ctx: Context, pcm: Pcm, tr: LipSync.Track, aoIniciar: () -> Unit, aoTerminar: (() -> Unit)?) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = am
        val attrs = AudioAttributes.Builder().setUsage(if (comunicacao) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        if (Build.VERSION.SDK_INT >= 26) {
            foco = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) parar()
            }.build().also { if (am.requestAudioFocus(it) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) throw IllegalStateException("Áudio ocupado") }
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
        t.setVolume(playbackVolume)
        track = t
        t.play()
        // PLAYING and mouth movement begin only after Android reports rendered audio frames.
        val firstFrame = object : Runnable {
            override fun run() {
                if (track !== t) return
                val frames = runCatching { t.playbackHeadPosition }.getOrDefault(0)
                if (frames > 0) {
                    firstAudioDelayMs = android.os.SystemClock.elapsedRealtime() - synthesisStartedAt
                    playbackState.value = PlaybackState.PLAYING
                    aoIniciar()
                    if (track !== t) return
                    LunaMotor.falar(tr) { runCatching { t.playbackHeadPosition.toDouble() / pcm.taxa }.getOrDefault(-1.0) }
                } else handler.postDelayed(this, 16)
            }
        }
        handler.post(firstFrame)
        // segurança: se o marcador não chegar, encerra pelo tempo
        Handler(Looper.getMainLooper()).postDelayed({ if (track === t) fim(ctx, t, aoTerminar) }, (total * 1000L / pcm.taxa) + 800)
    }

    private fun fim(ctx: Context, a: AudioTrack, aoTerminar: (() -> Unit)?) {
        if (track !== a) return
        track = null
        runCatching { a.stop() }; a.release()
        LunaMotor.calar()
        playbackState.value = PlaybackState.IDLE
        if (Build.VERSION.SDK_INT >= 26) foco?.let { (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it) }
        aoTerminar?.invoke()
    }

}
