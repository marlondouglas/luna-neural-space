package space.neural.luna.voz

/** Output-engine selection is independent from the explicitly invoked Android recognizer. */
object VoiceCapturePolicy {
    const val WINDOW_MS = 8_000L
    fun blockReason(permission: Boolean, radioTx: Boolean, radioRx: Boolean, call: Boolean): String? = when {
        !permission -> "Sem permissão do microfone."
        call -> "Microfone da Luna suspenso durante chamada ou comunicação de áudio."
        radioTx || radioRx -> "Microfone da Luna suspenso enquanto o rádio transmite ou recebe."
        else -> null
    }
}
