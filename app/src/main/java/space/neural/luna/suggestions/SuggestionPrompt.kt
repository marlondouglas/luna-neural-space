package space.neural.luna.suggestions

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import space.neural.luna.dashboard.Cores
import space.neural.luna.dashboard.Ui

/** Host must supply a freshly evaluated safety gate. This class never schedules or pauses anything. */
object SuggestionPrompt {
    fun show(activity: AppCompatActivity, coordinator: SuggestionCoordinator, suggestion: Suggestion,
        gate: () -> SuggestionGate, requestPause: (() -> Unit)? = null, requestPlanning: (() -> Unit)? = null): AlertDialog? {
        if (activity.isFinishing || !gate().allows()) return null
        val ui = Ui(activity)
        val body = ui.vbox(ui.dp(16))
        body.addView(ui.text(suggestion.text, 17f, Cores.BRANCO), ui.mp())
        body.addView(ui.text("Você decide. Nenhuma pausa será iniciada automaticamente.", 14f, Cores.BRANCO_70), ui.mp(ui.dp(8)))
        val dialog = AlertDialog.Builder(activity).setTitle("Sugestão da Luna").setView(body)
            .setNegativeButton("Agora não") { _, _ -> coordinator.dismiss(suggestion) }.create()
        listOf(30, 60, 120).forEach { minutes ->
            body.addView(ui.botao("Adiar $minutes minutos", Ui.Estilo.CONTORNO) {
                if (!gate().allows()) { dialog.dismiss(); return@botao }
                runCatching { coordinator.proposeSnooze(suggestion, minutes) }.onSuccess { proposal ->
                    AlertDialog.Builder(activity).setTitle("Confirmar adiamento").setMessage(proposal.text)
                        .setPositiveButton("Confirmar") { _, _ ->
                            if (coordinator.confirm(proposal.token, gate())) dialog.dismiss()
                        }.setNegativeButton("Cancelar") { _, _ -> coordinator.cancel(proposal.token) }
                        .setOnCancelListener { coordinator.cancel(proposal.token) }.show()
                }
            }, ui.mp(ui.dp(6)))
        }
        val action = if (suggestion.rule == SuggestionRule.PERFORMANCE) requestPlanning else requestPause
        if (action != null) body.addView(ui.botao(if (suggestion.rule == SuggestionRule.PERFORMANCE) "Revisar planejamento" else "Pausar turno agora", Ui.Estilo.CONTORNO) {
            if (!gate().allows()) { dialog.dismiss(); return@botao }
            AlertDialog.Builder(activity).setTitle("Confirmar ação")
                .setMessage(if (suggestion.rule == SuggestionRule.PERFORMANCE) "Abrir seu planejamento?" else "Pausar o turno agora? Esta ação só acontece ao confirmar.")
                .setPositiveButton("Confirmar") { _, _ ->
                    if (coordinator.runConfirmedAction(suggestion, gate, action)) dialog.dismiss()
                }
                .setNegativeButton("Cancelar", null).show()
        }, ui.mp(ui.dp(8)))
        dialog.setOnShowListener { if (!coordinator.displayed(suggestion, gate())) dialog.dismiss() }
        dialog.show()
        return dialog
    }
}
