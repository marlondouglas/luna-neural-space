package space.neural.luna.memory

import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import space.neural.luna.dashboard.Cores
import space.neural.luna.dashboard.Ui

/** Explicit local memory management; never records conversations in the background. */
class MemorySettingsActivity : AppCompatActivity() {
    private lateinit var ui: Ui
    private lateinit var repository: LunaMemoryRepository
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this); repository = LunaMemoryRepository(this)
        render()
    }
    private fun render() {
        val body = ui.vbox(ui.dp(16)).apply { setBackgroundColor(Cores.OBSIDIAN) }
        body.addView(ui.botao("Voltar", Ui.Estilo.CONTORNO) { finish() }, ui.mp())
        body.addView(ui.text("Memória local da Luna", 23f, Cores.BRANCO, true), ui.mp(ui.dp(14)))
        body.addView(ui.text("Desligada por padrão. Você decide quais resumos manuais guardar nesta conta e neste aparelho. Não treinamos pesos, não copiamos conversas inteiras e não enviamos estes itens para a nuvem. Não guarde senhas, tokens ou localização precisa.", 15f, Cores.BRANCO_70), ui.mp(ui.dp(10)))
        body.addView(ui.text("Ao desligar, os resumos locais desta conta são apagados. Ao trocar de conta, os itens da conta anterior são apagados deste aparelho. Referências web são dados não confiáveis e nunca autorizam ações.", 14f, Cores.BRANCO_70), ui.mp(ui.dp(8)))
        val logged = repository.safeUid() != null
        body.addView(Switch(this).apply {
            text = "Permitir memória manual nesta conta"; setTextColor(Cores.BRANCO)
            minHeight = ui.dp(48); isChecked = repository.enabled(); isEnabled = logged
            setOnCheckedChangeListener { _, enabled -> attempt { repository.setEnabled(enabled); render() } }
        }, ui.mp(ui.dp(12)))
        if (!logged) body.addView(ui.text("Entre na sua conta para gerenciar a memória.", 15f, Cores.BRANCO_70), ui.mp(ui.dp(12)))
        if (repository.enabled()) {
            body.addView(ui.botao("Adicionar resumo manual", Ui.Estilo.CHEIO_VIOLETA) { edit(null) }, ui.mp(ui.dp(12)))
            body.addView(ui.botao("Apagar todos os itens", Ui.Estilo.CONTORNO) {
                AlertDialog.Builder(this).setTitle("Apagar memórias locais?").setMessage("Somente os resumos da Luna desta conta serão removidos. Seus registros financeiros permanecem.")
                    .setPositiveButton("Apagar") { _, _ -> attempt { repository.clear(); render() } }.setNegativeButton("Cancelar", null).show()
            }, ui.mp(ui.dp(8)))
            val items = repository.list()
            if (items.isEmpty()) body.addView(ui.text("Nenhum resumo guardado. A Luna não coleta itens automaticamente.", 15f, Cores.BRANCO_70), ui.mp(ui.dp(16)))
            items.forEach { item ->
                val box = ui.vbox(ui.dp(12)).apply { background = ui.fundo(Cores.SUPERFICIE, Cores.VIOLETA, 14f) }
                box.addView(ui.text(item.category.label, 16f, Cores.CIANO, true))
                box.addView(ui.text(item.text, 16f, Cores.BRANCO), ui.mp(ui.dp(6)))
                val date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(item.updatedAt))
                box.addView(ui.text("${if (item.origin == MemoryOrigin.MANUAL) "Origem: manual" else "Origem: web, somente dado"} · $date${item.source?.let { "\nFonte: $it" }.orEmpty()}", 12f, Cores.BRANCO_70), ui.mp(ui.dp(6)))
                box.addView(ui.botao("Corrigir", Ui.Estilo.CONTORNO) { edit(item) }, ui.mp(ui.dp(8)))
                box.addView(ui.botao("Apagar item", Ui.Estilo.CONTORNO) { attempt { repository.remove(item.id); render() } }, ui.mp(ui.dp(6)))
                body.addView(box, ui.mp(ui.dp(12)))
            }
        }
        val scroll = ScrollView(this).apply { addView(body); isFillViewport = true }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val edges = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(edges.left, edges.top, edges.right, edges.bottom); insets
        }
    }
    private fun edit(item: LunaMemoryItem?) {
        val form = ui.vbox(ui.dp(16))
        val category = Spinner(this).apply {
            adapter = ArrayAdapter(this@MemorySettingsActivity, android.R.layout.simple_spinner_dropdown_item, MemoryCategory.entries.map { it.label })
            minimumHeight = ui.dp(48); setSelection(item?.category?.ordinal ?: 0)
            isEnabled = item?.origin != MemoryOrigin.WEB_REFERENCE
        }
        val text = EditText(this).apply {
            hint = "Ex.: prefiro respostas curtas"; setText(item?.text.orEmpty()); setTextColor(Cores.BRANCO)
            setHintTextColor(Cores.BRANCO_70); filters = arrayOf(InputFilter.LengthFilter(280)); minLines = 3
        }
        val error = ui.text("", 14f, Cores.CORAL)
        form.addView(ui.text("Salve somente uma preferência ou resumo que você escolheu expressamente.", 14f, Cores.BRANCO_70))
        form.addView(category, ui.mp(ui.dp(8))); form.addView(text, ui.mp(ui.dp(8))); form.addView(error)
        val dialog = AlertDialog.Builder(this).setTitle(if (item == null) "Novo resumo manual" else "Corrigir resumo")
            .setView(form).setPositiveButton("Salvar este resumo", null).setNegativeButton("Cancelar", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching { repository.put(text.text.toString(), MemoryCategory.entries[category.selectedItemPosition], item?.id, item?.origin ?: MemoryOrigin.MANUAL, item?.source) }
                .onSuccess { dialog.dismiss(); render() }.onFailure { error.text = it.message ?: "Não foi possível salvar." }
        } }
        dialog.show()
    }
    private fun attempt(block: () -> Unit) { runCatching(block).onFailure { Toast.makeText(this, it.message ?: "Não foi possível alterar a memória.", Toast.LENGTH_LONG).show() } }
}
