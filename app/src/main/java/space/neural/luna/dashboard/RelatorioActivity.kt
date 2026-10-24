package space.neural.luna.dashboard

import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import space.neural.luna.R
import space.neural.luna.data.repo.LunaRepositories
import java.time.LocalDate
import java.util.Locale

/** Relatórios com base, amostra e janelas explicitadas. */
class RelatorioActivity : AppCompatActivity() {
    private lateinit var ui: Ui
    private lateinit var content: LinearLayout
    private var panel: Painel? = null
    private var period = Periodo.HOJE
    private var selectedDay: LocalDate? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        period = runCatching { Periodo.valueOf(savedInstanceState?.getString("period") ?: intent.getStringExtra("period").orEmpty()) }.getOrDefault(Periodo.HOJE)
        selectedDay = savedInstanceState?.getString("day")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val (root, body) = telaSimples(this, ui, "Relatórios")
        content = body; setContentView(root)
        lifecycleScope.launch { Painel.observar(LunaRepositories(this@RelatorioActivity)).collect { panel = it; draw(it) } }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("period", period.name); selectedDay?.let { outState.putString("day", it.toString()) }
        super.onSaveInstanceState(outState)
    }
    private fun summary(p: Painel, window: ReportAnalytics.Window) = ReportAnalytics.summarize(
        window, p.ganhosTodos, p.gastosTodos, p.turnosTodos, space.neural.luna.sync.Relogio.agora())

    private fun draw(p: Painel) {
        content.removeAllViews()
        val selector = SeletorPeriodo(ui) { period = it; selectedDay = null; panel?.let(::draw) }
        selector.bind(period); content.addView(selector.view, ui.mp())
        val days = ui.hbox()
        days.addView(ui.botao("Dias recentes", Ui.Estilo.CONTORNO) {
            val dates = (0L..13L).map { p.hoje.minusDays(it) }
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Selecionar dia")
                .setItems(dates.map { Fmt.dataCurta(it) }.toTypedArray()) { _, which -> selectedDay = dates[which]; panel?.let(::draw) }.show()
        }, ui.peso(1f))
        days.addView(ui.botao("Outra data", Ui.Estilo.CONTORNO) {
            val d = selectedDay ?: p.hoje
            android.app.DatePickerDialog(this, { _, y, m, day -> selectedDay = LocalDate.of(y, m + 1, day); panel?.let(::draw) }, d.year, d.monthValue - 1, d.dayOfMonth).apply {
                datePicker.maxDate = System.currentTimeMillis(); show()
            }
        }, ui.peso(1f, ui.dp(8)))
        content.addView(days, ui.mp(ui.dp(8)))
        val window = selectedDay?.let { ReportAnalytics.Window(it, it) } ?: ReportAnalytics.window(period, p.hoje)
        val data = summary(p, window)
        content.addView(metrics(if (selectedDay == null) period.rotulo else "Dia selecionado", data), ui.mp(ui.dp(12)))
        val apps = ui.card(acento = Cores.LARANJA)
        apps.addView(ui.titulo("Apps no período · valores brutos"))
        val grouped = p.ganhosTodos.filter { !it.deleted && window.contains(it.data) }.distinctBy { it.id }.groupBy { it.app }
        grouped.entries.sortedByDescending { it.value.sumOf { gain -> gain.valorCentavos } }.forEach { (app, gains) ->
            val gross = gains.sumOf { it.valorCentavos }
            val rides = gains.count { it.descricao != Estatisticas.AJUSTE }
            apps.addView(ui.text(Fmt.nomeApp(app) + ": " + Fmt.reais(gross) + " · " + rides + " corridas registradas", 14f, Cores.BRANCO), ui.mp(ui.dp(8)))
        }
        apps.addView(ui.text(if (grouped.isEmpty()) "Sem ganhos registrados no período." else "Não há rateio confiável de despesas nem tempo por aplicativo; líquido/hora por app não é calculado.", 13f, Cores.BRANCO_70), ui.mp(ui.dp(8)))
        content.addView(apps, ui.mp(ui.dp(12)))

        val (currentWindow, previousWindow) = ReportAnalytics.weeklyWindows(p.hoje)
        val comparison = ReportAnalytics.Comparison(summary(p, currentWindow), summary(p, previousWindow))
        val weekly = ui.card(acento = Cores.CIANO)
        weekly.addView(ui.titulo("Comparação semanal equivalente"))
        weekly.addView(ui.text("Semana começa na segunda. Hoje e o dia correspondente da semana anterior são excluídos: não há horário confiável de cada corrida para comparação intradiária.", 13f, Cores.BRANCO_70), ui.mp(ui.dp(6)))
        if (currentWindow.empty) weekly.addView(ui.text("Ainda não há dias completos nesta semana para comparar.", 14f, Cores.BRANCO), ui.mp(ui.dp(8)))
        else {
            weekly.addView(ui.text("Atual: "+range(currentWindow)+" · líquido "+Fmt.reais(comparison.current.net), 14f, Cores.BRANCO), ui.mp(ui.dp(8)))
            weekly.addView(ui.text("Anterior equivalente: "+range(previousWindow)+" · líquido "+Fmt.reais(comparison.previous.net), 14f, Cores.BRANCO), ui.mp(ui.dp(6)))
            val change = comparison.netChangePercent
            weekly.addView(ui.text(if (change == null) "Variação indisponível: base anterior zero." else "Variação do líquido: "+String.format(Locale.forLanguageTag("pt-BR"), "%+.1f%%", change), 14f, Cores.CIANO), ui.mp(ui.dp(6)))
            val a = comparison.current
            val b = comparison.previous
            fun moneyDelta(value: Long) = (if (value > 0) "+" else "") + Fmt.reais(value)
            val timeDelta = a.workMs - b.workMs
            weekly.addView(ui.text("Deltas (atual − anterior): bruto " + moneyDelta(a.gross - b.gross) +
                " · gastos " + moneyDelta(a.expenses - b.expenses) + " · líquido " + moneyDelta(a.net - b.net), 14f, Cores.BRANCO), ui.mp(ui.dp(6)))
            weekly.addView(ui.text("Tempo: " + Fmt.hms(a.workMs) + " / " + Fmt.hms(b.workMs) +
                " · delta " + (if (timeDelta < 0) "−" else "+") + Fmt.hms(kotlin.math.abs(timeDelta)) +
                "; corridas: " + a.rides + " / " + b.rides + " · delta " + (a.rides - b.rides), 14f, Cores.BRANCO), ui.mp(ui.dp(6)))
            weekly.addView(ui.text("Líquido/hora atual: " + (a.netPerHour?.let { Fmt.reais(it) } ?: "dados insuficientes") +
                " · anterior: " + (b.netPerHour?.let { Fmt.reais(it) } ?: "dados insuficientes"), 14f, Cores.BRANCO), ui.mp(ui.dp(6)))
            weekly.addView(ui.text("Decomposição contábil: delta líquido = delta bruto − delta gastos. Esses registros descrevem o resultado; não provam sua causa. Horas de turnos abertos são limitadas ao fim de cada janela.", 12f, Cores.BRANCO_70), ui.mp(ui.dp(6)))
            weekly.addView(ui.text("Amostra: "+comparison.current.recordedDays+" dias registrados / "+comparison.previous.recordedDays+" anteriores. Ausência de lançamento não comprova dia sem trabalho.", 12f, Cores.BRANCO_70), ui.mp(ui.dp(6)))
        }
        content.addView(weekly, ui.mp(ui.dp(12)))
        content.addView(metrics("Semana passada completa · referência separada", summary(p, ReportAnalytics.previousFullWeek(p.hoje))), ui.mp(ui.dp(12)))

        val evidence = ui.card(acento = Cores.LARANJA)
        evidence.addView(ui.titulo("Evidências e hipóteses"))
        evidence.addView(ui.text("Evidência: os valores acima usam lançamentos e turnos registrados, excluindo itens apagados e gastos pessoais com limite. Melhor app usa BRUTO; custos não estão rateados entre apps.", 14f, Cores.BRANCO), ui.mp(ui.dp(6)))
        evidence.addView(ui.text(if (data.insufficientSample) "Amostra insuficiente para sugerir estratégia: mínimo exploratório de 3 dias registrados e 10 corridas. Esse mínimo não comprova padrão estatístico." else "Hipótese para investigar: repetir condições dos dias com melhor líquido pode ajudar. A amostra não demonstra causa nem garante o mesmo resultado.", 14f, Cores.BRANCO_70), ui.mp(ui.dp(8)))
        evidence.addView(ui.text("Quilometragem, R$/km, melhores horários e rentabilidade por app: dados insuficientes. updatedAt é edição/sincronização, não horário da corrida. Nenhuma recomendação de horário foi inferida desses campos.", 13f, Cores.BRANCO_70), ui.mp(ui.dp(8)))
        content.addView(evidence, ui.mp(ui.dp(12)))
    }
    private fun range(w: ReportAnalytics.Window) = Fmt.dataCurta(w.start)+" a "+Fmt.dataCurta(w.end)
    private fun metrics(title: String, s: ReportAnalytics.Summary): LinearLayout = ui.card(acento = Cores.MAGENTA).apply {
        addView(ui.titulo(title), ui.mp())
        addView(ui.text(range(s.window), 13f, Cores.BRANCO_70), ui.mp(ui.dp(4)))
        val lines = listOf(
            "Bruto: "+Fmt.reais(s.gross), "Gastos do trabalho: "+Fmt.reais(s.expenses), "Líquido: "+Fmt.reais(s.net),
            "Corridas registradas: "+s.rides+" · lançamentos de ganho: "+s.incomeRecords,
            "Tempo trabalhado: "+Fmt.hms(s.workMs),
            "Líquido/hora: "+(s.netPerHour?.let { Fmt.reais(it) } ?: "insuficiente (mínimo 15 min)"),
            "Corridas/hora: "+(s.ridesPerHour?.let { String.format(Locale.forLanguageTag("pt-BR"), "%.2f", it) } ?: "dados insuficientes"),
            "Ticket bruto por corrida: "+(s.averageTicket?.let { Fmt.reais(it) } ?: "sem corridas"),
            "Melhor app por BRUTO: "+(s.bestAppByGross?.let { Fmt.nomeApp(it.app)+" · "+Fmt.reais(it.cents) } ?: "sem dados"),
            "Melhor dia por LÍQUIDO: "+(s.bestDayByNet?.let { Fmt.dataCurta(it.date)+" · "+Fmt.reais(it.cents) } ?: "sem dados"),
            "Amostra: "+s.recordedDays+" dias com registros · "+if (s.insufficientSample) "insuficiente para tendências" else "exploratória"
        )
        lines.forEach { addView(ui.text(it, 14f, Cores.BRANCO), ui.mp(ui.dp(7))) }
    }
}
