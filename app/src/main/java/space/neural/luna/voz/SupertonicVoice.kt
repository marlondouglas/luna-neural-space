package space.neural.luna.voz

import android.content.Context
import android.os.Build
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.coroutineContext

/** Real native Sherpa 1.13.8 adapter, fixed to the user's chosen Supertonic 3 pt speaker 0. */
class SupertonicVoice private constructor(private val context: Context) : LocalVoiceRuntime.Engine {
    override val descriptor = LocalVoiceRuntime.Descriptor(LocalVoiceRuntime.APPROVED_ENGINE_ID,
        "Supertonic 3 Português · speaker 0", "pt-BR", "Apache-2.0 (Sherpa-ONNX)",
        "OpenRAIL-M (Supertone supertonic-3)", "OpenRAIL-M (preset speaker 0)")
    private val lock = Mutex()
    private var native: OfflineTts? = null
    @Volatile private var released = false
    override fun release() {
        released = true
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO).launch {
            lock.withLock { native?.release(); native = null }
        }
    }
    override suspend fun synthesize(text: String): LocalVoiceRuntime.Audio = withContext(Dispatchers.IO) {
        lock.withLock {
            coroutineContext.ensureActive()
            check(!released) { "A voz foi desativada." }
            val dir = modelDirectory(context)
            val tts = native ?: run {
                check(SupertonicModelFiles.required.all { (name, entry) -> SupertonicModelFiles.verified(File(dir, name), entry) }) {
                    "Arquivos da voz ausentes ou alterados. Instale/verifique a voz nas configurações."
                }
                OfflineTts(config = OfflineTtsConfig(model = OfflineTtsModelConfig(
                    supertonic = OfflineTtsSupertonicModelConfig(
                        durationPredictor = File(dir, "duration_predictor.int8.onnx").path,
                        textEncoder = File(dir, "text_encoder.int8.onnx").path,
                        vectorEstimator = File(dir, "vector_estimator.int8.onnx").path,
                        vocoder = File(dir, "vocoder.int8.onnx").path,
                        ttsJson = File(dir, "tts.json").path,
                        unicodeIndexer = File(dir, "unicode_indexer.bin").path,
                        voiceStyle = File(dir, "voice.bin").path),
                    numThreads = 2, debug = false, provider = "cpu"))).also { native = it }
            }
            val jobContext = coroutineContext
            val audio = tts.generateWithConfigAndCallback(text.take(1200), GenerationConfig(
                sid = LocalVoiceRuntime.APPROVED_SPEAKER, numSteps = 8, speed = 1f,
                extra = mapOf("lang" to LocalVoiceRuntime.SYNTHESIS_LANGUAGE))) {
                if (!released && jobContext[kotlinx.coroutines.Job]?.isActive != false) 1 else 0
            }
            jobContext.ensureActive()
            LocalVoiceRuntime.Audio(audio.samples, audio.sampleRate)
        }
    }
    companion object {
        const val ASSET_DIRECTORY = "supertonic3"
        private const val PREFS = "luna_local_voice"
        private val installLock = Mutex()
        fun modelDirectory(context: Context) = File(context.filesDir, "luna_voice/${LocalVoiceRuntime.APPROVED_MODEL}")
        fun selected(context: Context): Boolean = context.getSharedPreferences(PREFS, 0)
            .getString("engine", null) == LocalVoiceRuntime.APPROVED_ENGINE_ID
        fun select(context: Context, enabled: Boolean) {
            check(context.getSharedPreferences(PREFS, 0).edit().apply {
                if (enabled) putString("engine", LocalVoiceRuntime.APPROVED_ENGINE_ID) else remove("engine")
            }.commit()) { "Não foi possível salvar a seleção de voz." }
            if (enabled) restore(context) else LocalVoiceRuntime.clearAuthorization()
        }
        fun restore(context: Context): Boolean {
            if (!selected(context)) return false
            if (Build.SUPPORTED_ABIS.none { it in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }) {
                LocalVoiceRuntime.reportUnavailable("Arquitetura sem runtime Sherpa compatível."); return false
            }
            if (!LocalVoiceRuntime.available) LocalVoiceRuntime.register(SupertonicVoice(context.applicationContext))
            return LocalVoiceRuntime.authorizeEngine(LocalVoiceRuntime.APPROVED_ENGINE_ID)
        }
        fun bundled(context: Context): Boolean = runCatching {
            context.assets.list(ASSET_DIRECTORY).orEmpty().toSet().containsAll(SupertonicModelFiles.required.keys)
        }.getOrDefault(false)
        /** Explicit settings action only: no network and no extraction of untrusted archive paths. */
        suspend fun installBundled(context: Context, progress: (Int) -> Unit = {}) = withContext(Dispatchers.IO) {
            installLock.withLock {
                check(bundled(context)) { "O pacote desta versão não inclui os modelos da voz." }
                val target = modelDirectory(context)
                val parent = target.parentFile!!.apply { mkdirs() }
                check(parent.usableSpace >= SupertonicModelFiles.totalBytes + 16L * 1024 * 1024) { "Espaço insuficiente para instalar a voz local." }
                VerifiedVoiceInstaller.install(parent, LocalVoiceRuntime.APPROVED_MODEL, SupertonicModelFiles.required,
                    { name -> context.assets.open("$ASSET_DIRECTORY/$name") }, progress)

            }
        }
    }
}
