package space.neural.luna.voz

/** Per-request exactly-once callbacks. Start requires real frames and the original owner. */
class SpeechRequestCallbacks(private val ownerValid: () -> Boolean,
    private val onStart: (() -> Unit)?, private val onDone: (() -> Unit)?) {
    private var started = false
    private var finished = false
    fun ownerStillValid() = ownerValid()
    fun firstFrame() {
        if (finished || started || !ownerValid()) return
        started = true
        runCatching { onStart?.invoke() }
    }
    fun complete() {
        if (finished) return
        finished = true
        runCatching { onDone?.invoke() }
    }
}
