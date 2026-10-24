package space.neural.luna.voz

/** An installed model is not a selected/authorized voice. No Android/Google fallback exists here. */
object LocalVoiceRuntime {
    /** Exact preset explicitly selected by the user; RHVoice proof is comparison only. */
    const val APPROVED_ENGINE_ID = "supertonic-3-pt-sid-0"
    const val APPROVED_MODEL = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
    const val APPROVED_SPEAKER = 0
    const val SYNTHESIS_LANGUAGE = "pt"
    data class Audio(val samples: FloatArray, val sampleRate: Int)
    data class Descriptor(val id: String, val voiceName: String, val language: String,
        val engineLicense: String, val modelLicense: String, val voiceLicense: String)
    interface Engine {
        val descriptor: Descriptor
        /** Offline synthesis only. No network, Android TextToSpeech or voice cloning. */
        suspend fun synthesize(text: String): Audio
        fun release() {}
    }
    private val engines = mutableMapOf<String, Engine>()
    @Volatile private var selected: String? = null
    @Volatile var state: String = "Supertonic 3 Português, speaker 0 selecionado; motor/modelo local ainda indisponíveis neste aparelho."
        private set
    @Synchronized fun register(engine: Engine) {
        val d = engine.descriptor
        require(d.id.isNotBlank() && d.language == "pt-BR")
        require(listOf(d.engineLicense, d.modelLicense, d.voiceLicense).all { it.isNotBlank() })
        engines[d.id] = engine
    }
    /** Invoke only after the user explicitly selects the identified engine, model and voice. */
    @Synchronized fun authorizeEngine(id: String): Boolean {
        if (id != APPROVED_ENGINE_ID) return false
        val engine = engines[id] ?: return false
        selected = id
        state = "Voz local selecionada: ${engine.descriptor.voiceName}. Qualidade no aparelho ainda precisa de validação."
        return true
    }
    @Synchronized fun clearAuthorization() {
        engines.values.forEach { it.release() }; engines.clear(); selected = null
        state = "Nenhuma voz local selecionada."
    }
    @Synchronized fun selectedEngine(): Engine? = engines[selected]
    val available get() = selectedEngine() != null
    fun reportUnavailable(message: String) { state = message }
    suspend fun synthesize(text: String): Audio? {
        val engine = selectedEngine() ?: return null
        return try {
            engine.synthesize(text).also {
                require(it.sampleRate in 8000..96000 && it.samples.isNotEmpty() && it.samples.all(Float::isFinite))
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { state = "A voz local falhou. O texto continua disponível na tela."; null }
        catch (_: LinkageError) { state = "Runtime nativo da voz incompatível neste aparelho. O texto continua disponível."; null }
        catch (_: OutOfMemoryError) { state = "Memória insuficiente para gerar a voz local. O texto continua disponível."; null }
    }
}

/** Shared priority contract: SOS > maneuver > assistant. Radio transmission/reception is exclusive. */
enum class SpeechPriority(val rank: Int) { ASSISTANT(0), MANEUVER(10), SOS(20) }

class SpeechQueuePolicy {
    data class Item(val id: Long, val priority: SpeechPriority, val text: String)
    private val pending = mutableListOf<Item>()
    val retainedIds get() = pending.map { it.id }.toSet() + listOfNotNull(active?.id)
    var radioBusy: Boolean = false
    var active: Item? = null; private set
    fun offer(item: Item): Boolean {
        if (pending.any { it.text == item.text && it.priority == item.priority } || active?.let { it.text == item.text && it.priority == item.priority } == true) return false
        // A newer maneuver supersedes stale queued directions; SOS and assistant retain FIFO order.
        if (item.priority == SpeechPriority.MANEUVER) pending.removeAll { it.priority == SpeechPriority.MANEUVER }
        pending.add(item)
        if (pending.size > 12) pending.minWithOrNull(compareBy<Item> { it.priority.rank }.thenBy { it.id })?.let { pending.remove(it) }
        return true
    }
    fun next(): Item? {
        if (radioBusy || active != null) return null
        val item = pending.maxWithOrNull(compareBy<Item> { it.priority.rank }.thenBy { -it.id }) ?: return null
        pending.remove(item); active = item; return item
    }
    fun shouldPreempt(priority: SpeechPriority) = !radioBusy && active?.let { priority.rank > it.priority.rank } == true
    fun finish() { active = null }
    fun cancel(priority: SpeechPriority): Boolean {
        pending.removeAll { it.priority == priority }
        val interrupted = active?.priority == priority
        if (interrupted) active = null
        return interrupted
    }
    fun interruptForRadio() { active = null; pending.removeAll { it.priority == SpeechPriority.ASSISTANT } }
    fun clear() { active = null; pending.clear() }
}
