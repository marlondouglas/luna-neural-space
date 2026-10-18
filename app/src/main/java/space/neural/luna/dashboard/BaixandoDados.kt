package space.neural.luna.dashboard

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Tela "Baixando seus dados…" (v0.1.7): cobre o painel (ou o Planejamento) até a primeira descida da nuvem
 * desta conta neste aparelho. Bloqueia toques para ninguém editar em cima de uma tela ainda vazia.
 * [rotuloAcao]/[acao]: botão que aparece sem internet ("Continuar sem esperar" no painel, "Voltar" no Planejamento).
 */
class BaixandoDados(private val ui: Ui, rotuloAcao: String, acao: () -> Unit) {
    private val titulo: TextView = ui.text("Baixando seus dados…", 22f, Cores.BRANCO, bold = true).apply { gravity = Gravity.CENTER }
    private val texto: TextView = ui.text("", 15f, Cores.BRANCO_70).apply { gravity = Gravity.CENTER }
    private val roda = ProgressBar(ui.ctx).apply { isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(Cores.CIANO) }
    private val botao = ui.botao(rotuloAcao, Ui.Estilo.CONTORNO) { acao() }

    val view: FrameLayout = FrameLayout(ui.ctx).apply {
        id = space.neural.luna.R.id.baixando_dados
        setBackgroundColor(Cores.alpha(Cores.OBSIDIAN, 0.94f))
        isClickable = true; isFocusable = true // segura os toques
        val card = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ui.dp(24), ui.dp(28), ui.dp(24), ui.dp(24))
            background = ui.fundo(Cores.alpha(Cores.CIANO, 0.08f), Cores.alpha(Cores.CIANO, 0.5f), 22f)
            addView(roda, LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)))
            addView(titulo, ui.mp(ui.dp(18)))
            addView(texto, ui.mp(ui.dp(10)))
            addView(botao, ui.mp(ui.dp(18)))
        }
        addView(card, FrameLayout.LayoutParams(ui.dp(440).coerceAtMost(resources.displayMetrics.widthPixels - ui.dp(40)), -2, Gravity.CENTER))
        visibility = View.GONE
    }

    fun bind(visivel: Boolean, online: Boolean) {
        view.visibility = if (visivel) View.VISIBLE else View.GONE
        if (!visivel) return
        texto.text = if (online) "Seus ganhos, turnos, metas e planejamento estão vindo da nuvem. Leva só alguns segundos."
        else "Sem internet. Conecte o aparelho para trazer da nuvem os dados que você já lançou no outro aparelho."
        roda.visibility = if (online) View.VISIBLE else View.INVISIBLE
        botao.visibility = if (online) View.GONE else View.VISIBLE
    }
}
