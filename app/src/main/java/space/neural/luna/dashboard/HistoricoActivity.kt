package space.neural.luna.dashboard

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import space.neural.luna.R
import space.neural.luna.data.local.Anotacao
import space.neural.luna.data.repo.LunaRepositories
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Histórico: dias com lançamento (bruto, gastos, líquido). Tocar abre os lançamentos do dia. */
class HistoricoActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = Ui(this); val repo = LunaRepositories(this)
        val (raiz, conteudo) = telaSimples(this, ui, "Histórico")
        val dlg = Dialogos(this, repo, lifecycleScope)
        conteudo.addView(ui.comIcone(ui.botao("Lançar dia esquecido") {
            dlg.escolherDia { d -> startActivity(LancamentosActivity.dia(this, d.toString())) }
        }, R.drawable.ic_calendar), ui.mp())
        val lista = ui.vbox(); conteudo.addView(lista, ui.mp(ui.dp(12)))
        setContentView(raiz)
        lifecycleScope.launch {
            repo.observeGanhos().combine(repo.observeGastos()) { g, d -> g to d }.collect { (gs, ds) ->
                lista.removeAllViews()
                val dias = (gs.map { it.data } + ds.map { it.data }).toSortedSet().reversed()
                if (dias.isEmpty()) lista.addView(ui.text("Nenhum dia com lançamentos ainda.", 15f, Cores.BRANCO_70))
                dias.take(90).forEach { d ->
                    val b = gs.filter { it.data == d }.sumOf { it.valorCentavos }
                    val g = ds.filter { it.data == d }.sumOf { it.valorCentavos }
                    val c = ui.card().apply {
                        isClickable = true; isFocusable = true
                        setOnClickListener { startActivity(LancamentosActivity.dia(this@HistoricoActivity, d)) }
                    }
                    c.addView(ui.text(Fmt.dataLonga(LocalDate.parse(d)), 15f, Cores.VIOLETA, bold = true))
                    c.addView(ui.text("Bruto ${Fmt.reais(b)} · Gastos ${Fmt.reais(g)} · Líquido ${Fmt.reais(b - g)}", 15f), ui.mp(ui.dp(4)))
                    lista.addView(c, ui.mp(ui.dp(8)))
                }
            }
        }
    }
}

/** Anotações livres (ficam no aparelho e sincronizam com o Firebase). */
class AnotacoesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = Ui(this); val repo = LunaRepositories(this)
        val (raiz, conteudo) = telaSimples(this, ui, "Anotações")
        val campo = EditText(this).apply {
            hint = "Escreva uma anotação…"; setTextColor(Cores.BRANCO); setHintTextColor(Cores.BRANCO_70)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minHeight = ui.dp(48)
        }
        conteudo.addView(campo, ui.mp())
        conteudo.addView(ui.comIcone(ui.botao("Salvar anotação", Ui.Estilo.CHEIO_VIOLETA) {
            val t = campo.text.toString().trim()
            if (t.isNotEmpty()) { lifecycleScope.launch { repo.salvarAnotacao(Anotacao(texto = t)) }; campo.setText("") }
        }, R.drawable.ic_check), ui.mp(ui.dp(8)))
        val lista = ui.vbox(); conteudo.addView(lista, ui.mp(ui.dp(12)))
        setContentView(raiz)
        val fmt = DateTimeFormatter.ofPattern("dd/MM HH:mm")
        lifecycleScope.launch {
            repo.observeAnotacoes().collect { itens ->
                lista.removeAllViews()
                if (itens.isEmpty()) lista.addView(ui.text("Nenhuma anotação.", 15f, Cores.BRANCO_70))
                itens.forEach { a ->
                    val c = ui.card()
                    val l = ui.hbox()
                    l.addView(ui.vbox().apply {
                        addView(ui.text(Instant.ofEpochMilli(a.criadaEm).atZone(ZoneId.systemDefault()).format(fmt), 13f, Cores.BRANCO_70))
                        addView(ui.text(a.texto, 16f))
                    }, ui.peso(1f))
                    l.addView(ui.botaoIcone(R.drawable.ic_close, "Excluir anotação", Cores.BRANCO_70) {
                        Dialogos(this@AnotacoesActivity, repo, lifecycleScope).confirmar("Excluir", "Excluir esta anotação?", "Excluir") {
                            lifecycleScope.launch { repo.excluirAnotacao(a.id) }
                        }
                    })
                    c.addView(l)
                    lista.addView(c, ui.mp(ui.dp(8)))
                }
            }
        }
    }
}

/** Tela com cabeçalho (voltar + título) e conteúdo rolável. */
internal fun telaSimples(act: AppCompatActivity, ui: Ui, titulo: String): Pair<LinearLayout, LinearLayout> {
    val raiz = ui.vbox(ui.dp(16)).apply { setBackgroundColor(Cores.OBSIDIAN) }
    val cab = ui.hbox()
    cab.addView(ui.botaoIcone(R.drawable.ic_close, "Voltar") { act.finish() })
    cab.addView(ui.text(titulo, 20f, Cores.BRANCO, bold = true), ui.peso(1f, ui.dp(8)))
    raiz.addView(cab, ui.mp())
    val conteudo = ui.vbox()
    raiz.addView(ScrollView(act).apply { addView(conteudo) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(12) })
    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(raiz) { v, ins ->
        val b = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.ime())
        v.setPadding(b.left + ui.dp(16), b.top + ui.dp(16), b.right + ui.dp(16), b.bottom + ui.dp(16)); ins
    }
    return raiz to conteudo
}
