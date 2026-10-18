package space.neural.luna.dashboard

import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import space.neural.luna.R
import space.neural.luna.data.local.MaquinaTurno
import space.neural.luna.data.repo.LunaRepositories

/** Relatório completo: números do mês e comparação Hoje / Semana / Mês / Ano, com divisão por app. */
class RelatorioActivity : AppCompatActivity() {
    private lateinit var ui: Ui

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val repo = LunaRepositories(this)
        val (raiz, conteudo) = telaSimples(this, ui, "Relatórios")
        setContentView(raiz)
        lifecycleScope.launch {
            Painel.observar(repo).collect { p -> desenhar(conteudo, p) }
        }
    }

    private fun desenhar(c: LinearLayout, p: Painel) {
        c.removeAllViews()
        val agora = System.currentTimeMillis()
        // Grade de destaques do mês (2 por linha)
        Relatorio.calcular(p, agora).chunked(2).forEachIndexed { i, par ->
            val l = ui.hbox().apply { gravity = android.view.Gravity.TOP }
            par.forEachIndexed { j, it ->
                val t = ui.card(acento = it.cor, colorido = true)
                t.addView(ui.hbox().apply {
                    addView(ui.icone(it.icone, Cores.BRANCO, 16))
                    addView(ui.text(it.titulo.uppercase(), 11f, Cores.alpha(Cores.BRANCO, 0.85f), bold = true), ui.wc(ui.dp(6)))
                })
                t.addView(ui.ajustavel(it.valor, 24f, 12f, Cores.BRANCO), ui.mp(ui.dp(6)))
                t.addView(ui.ajustavel(it.sub, 13f, 9f, Cores.alpha(Cores.BRANCO, 0.8f), bold = false), ui.mp(ui.dp(2)))
                t.contentDescription = "${it.titulo}: ${it.valor}. ${it.sub}"
                l.addView(t, LinearLayout.LayoutParams(0, -1, 1f).apply { if (j > 0) leftMargin = ui.dp(12) })
            }
            if (par.size == 1) l.addView(android.view.View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = ui.dp(12) })
            c.addView(l, ui.mp(if (i == 0) 0 else ui.dp(12)))
            Anima.entrada(l, i)
        }
        // Tabela por período
        val tab = ui.card(acento = Cores.CIANO)
        tab.addView(ui.tituloIcone(R.drawable.ic_trending, "Por período", Cores.CIANO))
        val cab = ui.hbox()
        listOf("R$", "Bruto", "Gastos", "Líquido", "Corr.", "Horas", "R$/h").forEachIndexed { i, s ->
            cab.addView(ui.text(s, 11f, Cores.BRANCO_70, bold = true).apply { if (i > 0) gravity = android.view.Gravity.END }, ui.peso(if (i == 0) 1.1f else 1f))
        }
        tab.addView(cab, ui.mp(ui.dp(8)))
        Periodo.entries.forEach { per ->
            val e = p.estatisticas(per, agora)
            tab.addView(ui.divisor())
            val l = ui.hbox().apply { minimumHeight = ui.dp(40) }
            val vals = listOf(per.rotulo, Fmt.reaisCurto(e.brutoCentavos), Fmt.reaisCurto(e.gastosCentavos), Fmt.reaisCurto(e.liquidoCentavos), "${e.corridas}",
                Fmt.hms(e.trabalhoMs).substring(0, 5), e.porHora?.let { Fmt.reaisCurto(it) } ?: "—")
            vals.forEachIndexed { i, s0 ->
                val s = if (i in 1..3 || i == 6) s0.replace(Regex("R\\$\\s?"), "") else s0
                l.addView(ui.ajustavel(s, 13f, 9f, if (i == 3) Cores.ESMERALDA else Cores.BRANCO, bold = i == 0 || i == 3).apply { if (i > 0) gravity = android.view.Gravity.END }, ui.peso(if (i == 0) 1.1f else 1f))
            }
            l.contentDescription = "${per.rotulo}: bruto ${Fmt.reais(e.brutoCentavos)}, gastos ${Fmt.reais(e.gastosCentavos)}, líquido ${Fmt.reais(e.liquidoCentavos)}, ${e.corridas} corridas"
            tab.addView(l, ui.mp())
        }
        c.addView(tab, ui.mp(ui.dp(14)))
        // Por app no mês
        val mes = p.estatisticas(Periodo.MES, agora)
        val apps = ui.card(acento = Cores.LARANJA)
        apps.addView(ui.tituloIcone(R.drawable.ic_car, "Apps no mês", Cores.LARANJA))
        val max = mes.porApp.maxOfOrNull { it.centavos }?.coerceAtLeast(1) ?: 1
        mes.porApp.sortedByDescending { it.centavos }.forEach { a ->
            apps.addView(ui.hbox().apply {
                addView(ui.seloApp(a.app, 24))
                addView(ui.text(Fmt.nomeApp(a.app), 15f, CoresSerie.app(a.app), bold = true), ui.peso(1f, ui.dp(8)))
                addView(ui.text("${Fmt.reais(a.centavos)} · ${a.corridas} corr. · ${a.participacao}%", 14f, Cores.BRANCO))
            }, ui.mp(ui.dp(10)))
            apps.addView(BarraH3D(this).apply { definir(a.centavos.toFloat() / max, CoresSerie.app(a.app)) }, ui.mp(ui.dp(4)))
        }
        c.addView(apps, ui.mp(ui.dp(14)))
        val turnosMes = p.turnosTodos.count { !it.deleted && it.data.startsWith(p.hoje.toString().substring(0, 7)) && MaquinaTurno.trabalhoMs(it, agora) > 0 }
        c.addView(ui.text("Turnos trabalhados no mês: $turnosMes", 14f, Cores.BRANCO_70), ui.mp(ui.dp(12)))
    }
}
