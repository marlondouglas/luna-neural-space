package space.neural.luna.navigation

import android.content.Context
import space.neural.luna.avatar.LunaVoz
import space.neural.luna.voz.LocalVoiceRuntime
import space.neural.luna.voz.SpeechPriority

/** Same explicitly authorized local engine as Luna. Never creates Android default TTS. */
class NavigationSpeechController(context: Context, private val onState: (String) -> Unit = {}) {
    private val app = context.applicationContext
    private var last = ""
    private var closed = false
    init { onState(LocalVoiceRuntime.state) }
    fun instruction(text: String): Boolean {
        if (closed || text.isBlank() || last == text) return false
        if (!LocalVoiceRuntime.available) { onState(LocalVoiceRuntime.state); return false }
        val accepted = LunaVoz.falarComPrioridade(app, text, SpeechPriority.MANEUVER)
        if (accepted) { last = text; onState("Orientação enfileirada na voz local selecionada") }
        else onState(LocalVoiceRuntime.state)
        return accepted
    }
    fun stop() { LunaVoz.cancelarNavegacao(); last = "" }
    fun close() { closed = true; stop() }
}
