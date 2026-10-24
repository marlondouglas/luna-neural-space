package space.neural.luna.suggestions

import android.os.Bundle
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import space.neural.luna.dashboard.Cores
import space.neural.luna.dashboard.Ui

/** Local opt-in only. This screen does not schedule notifications or open a microphone. */
class SuggestionSettingsActivity : AppCompatActivity() {
    private lateinit var repository: SuggestionRepository
    private lateinit var ui: Ui
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); repository = SuggestionRepository(this); ui = Ui(this); render()
    }
    private fun change(transform: (SuggestionPreferences) -> SuggestionPreferences) {
        runCatching { repository.savePreferences(transform(repository.preferences())) }.onFailure {
            Toast.makeText(this, it.message ?: "Não foi possível salvar.", Toast.LENGTH_LONG).show()
        }
        render()
    }
    private fun choose(title: String, values: List<Int>, unit: String, transform: (SuggestionPreferences, Int) -> SuggestionPreferences) {
        AlertDialog.Builder(this).setTitle(title).setItems(values.map { "$it $unit" }.toTypedArray()) { _, index -> change { transform(it, values[index]) } }
            .setNegativeButton("Cancelar", null).show()
    }
    private fun render() {
        val p = repository.preferences()
        val logged = repository.ownerId() != null
        val body = ui.vbox(ui.dp(16)).apply { setBackgroundColor(Cores.OBSIDIAN) }
        body.addView(ui.botao("Voltar", Ui.Estilo.CONTORNO) { finish() }, ui.mp())
        body.addView(ui.text("Sugestões da Luna", 24f, Cores.BRANCO, true), ui.mp(ui.dp(14)))
        body.addView(ui.text("Desligadas por padrão e separadas por conta. São lembretes opcionais: nunca pausam o turno, abrem o microfone ou alteram seus dados sozinhos. Adiar exige confirmação de 30, 60 ou 120 minutos.", 15f, Cores.BRANCO_70), ui.mp(ui.dp(10)))
        body.addView(ui.text("Esta etapa prepara preferências e decisões persistentes. A entrega automática no painel e notificações em segundo plano ainda não estão ativadas; dependem de contexto de segurança e validação próprios.", 14f, Cores.LARANJA), ui.mp(ui.dp(8)))
        fun toggle(label: String, checked: Boolean, enabled: Boolean = logged, transform: (SuggestionPreferences, Boolean) -> SuggestionPreferences) {
            body.addView(Switch(this).apply {
                text = label; isChecked = checked; isEnabled = enabled; minHeight = ui.dp(48); setTextColor(Cores.BRANCO)
                setOnCheckedChangeListener { _, value -> change { transform(it, value) } }
            }, ui.mp(ui.dp(4)))
        }
        toggle("Permitir sugestões nesta conta", p.enabled) { prefs, value -> prefs.copy(enabled = value) }
        if (!logged) body.addView(ui.text("Entre na conta para configurar as sugestões.", 15f, Cores.BRANCO_70), ui.mp(ui.dp(8)))
        if (logged && p.enabled) {
            toggle("Descanso após trabalho prolongado", p.rest) { prefs, value -> prefs.copy(rest = value) }
            toggle("Pausa para almoço", p.lunch) { prefs, value -> prefs.copy(lunch = value) }
            toggle("Pausa para café", p.coffee) { prefs, value -> prefs.copy(coffee = value) }
            toggle("Lembrete de água em local seguro", p.hydration) { prefs, value -> prefs.copy(hydration = value) }
            toggle("Revisão de desempenho com base comparável", p.performance) { prefs, value -> prefs.copy(performance = value) }
            toggle("Mostrar texto", p.textEnabled) { prefs, value -> prefs.copy(textEnabled = value) }
            toggle("Permitir fala na voz escolhida", p.voiceEnabled) { prefs, value -> prefs.copy(voiceEnabled = value) }
            if (p.voiceEnabled) body.addView(ui.text("Voz aprovada: Supertonic 3 Português, speaker 0. Não há troca automática de motor; sem modelo local disponível, a sugestão permanece em texto.", 13f, Cores.BRANCO_70), ui.mp(ui.dp(6)))
            toggle("Tom afetuoso", p.affectionate) { prefs, value -> prefs.copy(affectionate = value) }
            body.addView(ui.botao("Intervalo mínimo: ${p.frequencyMinutes} min", Ui.Estilo.CONTORNO) {
                choose("Intervalo mínimo entre sugestões", listOf(15, 30, 60, 120, 240), "min") { prefs, value -> prefs.copy(frequencyMinutes = value) }
            }, ui.mp(ui.dp(8)))
            body.addView(ui.botao("Espera após entrega: ${p.cooldownMinutes} min", Ui.Estilo.CONTORNO) {
                choose("Espera após uma sugestão", listOf(15, 30, 60, 120, 240), "min") { prefs, value -> prefs.copy(cooldownMinutes = value) }
            }, ui.mp(ui.dp(8)))
            body.addView(ui.botao("Máximo ${p.maxPerInterval} a cada ${p.intervalMinutes} min", Ui.Estilo.CONTORNO) {
                choose("Máximo por intervalo", (1..5).toList(), "sugestões") { prefs, value -> prefs.copy(maxPerInterval = value) }
            }, ui.mp(ui.dp(8)))
            body.addView(ui.botao("Duração do intervalo: ${p.intervalMinutes} min", Ui.Estilo.CONTORNO) {
                choose("Intervalo de contagem", listOf(30, 60, 120, 180, 360, 720, 1440), "min") { prefs, value -> prefs.copy(intervalMinutes = value) }
            }, ui.mp(ui.dp(8)))
            body.addView(ui.botao("Silêncio: ${p.quietStartHour}h às ${p.quietEndHour}h", Ui.Estilo.CONTORNO) {
                choose("Início do silêncio (mesmo início/fim desativa)", (0..23).toList(), "h") { prefs, value -> prefs.copy(quietStartHour = value) }
                // End is edited separately to keep every saved change explicit.
            }, ui.mp(ui.dp(8)))
            body.addView(ui.botao("Fim do silêncio: ${p.quietEndHour}h", Ui.Estilo.CONTORNO) {
                choose("Fim do silêncio", (0..23).toList(), "h") { prefs, value -> prefs.copy(quietEndHour = value) }
            }, ui.mp(ui.dp(8)))
            body.addView(ui.botao("Apagar histórico de sugestões", Ui.Estilo.CONTORNO) {
                AlertDialog.Builder(this).setTitle("Apagar decisões e adiamentos?")
                    .setMessage("As sugestões deste turno poderão aparecer novamente quando a entrega estiver disponível. Seus turnos e lançamentos não serão alterados.")
                    .setPositiveButton("Apagar") { _, _ -> runCatching { repository.clearHistory() }.onFailure { Toast.makeText(this, "Falha ao apagar histórico.", Toast.LENGTH_LONG).show() } }
                    .setNegativeButton("Cancelar", null).show()
            }, ui.mp(ui.dp(12)))
        }
        val scroll = ScrollView(this).apply { addView(body); isFillViewport = true }; setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
    }
}
