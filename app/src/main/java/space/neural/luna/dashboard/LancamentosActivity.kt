package space.neural.luna.dashboard

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import space.neural.luna.R
import space.neural.luna.data.local.Ganho
import space.neural.luna.data.local.Gasto
import space.neural.luna.data.local.Turno
import space.neural.luna.data.local.MaquinaTurno
import space.neural.luna.data.repo.LunaRepositories
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Lista de ganhos e gastos, com editar/excluir e "+ lançar".
 * - Detalhamento do dia (ou de um app no dia), também usado pelo calendário (dia esquecido).
 * - Tela de correção do turno (spec 2.1): ganhos, gastos, início e fim.
 */
class LancamentosActivity : AppCompatActivity() {
    private lateinit var ui: Ui
    private lateinit var repo: LunaRepositories
    private lateinit var dlg: Dialogos
    private lateinit var conteudo: LinearLayout
    private var turno: Turno? = null
    private var turnosDia: List<Turno> = emptyList()
    private var inicio = 0L
    private var fim = 0L
    private val hm = DateTimeFormatter.ofPattern("dd/MM HH:mm")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this); repo = LunaRepositories(this); dlg = Dialogos(this, repo, lifecycleScope)
        val data = intent.getStringExtra(EXTRA_DATA) ?: LocalDate.now().toString()
        val app = intent.getStringExtra(EXTRA_APP)
        val turnoId = intent.getStringExtra(EXTRA_TURNO)

        val raiz = ui.vbox(ui.dp(16)).apply { background = FundoNeon() }
        val cab = ui.hbox()
        cab.addView(ui.botaoIcone(R.drawable.ic_close, "Voltar") { finish() })
        val titulo = when {
            turnoId != null -> "Correção do turno"
            app != null -> "${Fmt.nomeApp(app)} — ${Fmt.dataCurta(LocalDate.parse(data))}"
            else -> "Lançamentos — ${Fmt.dataCurta(LocalDate.parse(data))}"
        }
        cab.addView(ui.text(titulo, 20f, Cores.BRANCO, bold = true), ui.peso(1f, ui.dp(8)))
        raiz.addView(cab, ui.mp())
        conteudo = ui.vbox()
        raiz.addView(ScrollView(this).apply { addView(conteudo) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(raiz)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(raiz) { v, ins ->
            val b = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left + ui.dp(16), b.top + ui.dp(16), b.right + ui.dp(16), b.bottom + ui.dp(16)); ins
        }

        lifecycleScope.launch {
            if (turnoId != null) {
                turno = repo.turnoPorId(turnoId)
                turno?.let { inicio = savedInstanceState?.getLong("ini") ?: it.inicio; fim = savedInstanceState?.getLong("fim") ?: (it.fim ?: System.currentTimeMillis()) }
            }
            kotlinx.coroutines.flow.combine(repo.observeGanhos(), repo.observeGastos(), repo.observeTurnos()) { g, d, t -> Triple(g, d, t) }.collect { (gs, ds, ts) ->
                val ganhos = gs.filter { if (turnoId != null) it.turnoId == turnoId else it.data == data && (app == null || it.app == app) }
                val gastos = if (app != null) emptyList() else ds.filter { if (turnoId != null) it.turnoId == turnoId else it.data == data }
                turnosDia = MaquinaTurno.doDia(ts, data)
                desenhar(ganhos, gastos, turno?.data ?: data, turnoId, app)
            }
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out); out.putLong("ini", inicio); out.putLong("fim", fim)
    }

    private fun desenhar(ganhos: List<Ganho>, gastos: List<Gasto>, data: String, turnoId: String?, app: String?) {
        conteudo.removeAllViews()
        val t = turno
        if (turnoId != null && t != null) {
            val c = ui.card(destaque = true)
            c.addView(ui.tituloIcone(R.drawable.ic_timer, "Início e fim"))
            c.addView(ui.text("Corrija se o turno começou ou terminou em outro horário.", 14f, Cores.BRANCO_70), ui.mp(ui.dp(6)))
            val linhaIni = ui.hbox()
            linhaIni.addView(ui.text("Início: ${fmtHora(inicio)}", 17f), ui.peso(1f))
            linhaIni.addView(ui.botao("Alterar") { escolherHora(inicio) { inicio = it; desenhar(ganhos, gastos, data, turnoId, app) } })
            c.addView(linhaIni, ui.mp(ui.dp(10)))
            val linhaFim = ui.hbox()
            linhaFim.addView(ui.text("Fim: ${fmtHora(fim)}", 17f), ui.peso(1f))
            linhaFim.addView(ui.botao("Alterar") { escolherHora(fim) { fim = if (it < inicio) it + 86_400_000L else it; desenhar(ganhos, gastos, data, turnoId, app) } })
            c.addView(linhaFim, ui.mp(ui.dp(8)))
            val trab = (fim - inicio - t.paradoMs).coerceAtLeast(0)
            c.addView(ui.text("Tempo de trabalho: ${Fmt.hms(if (inicio == t.inicio && fim == (t.fim ?: fim)) t.trabalhoMs else trab)} · Parado: ${Fmt.minutos(t.paradoMs)} min", 15f, Cores.CIANO), ui.mp(ui.dp(8)))
            c.addView(ui.comIcone(ui.botao("Salvar correção", Ui.Estilo.CHEIO_VIOLETA, alto = true) {
                lifecycleScope.launch {
                    val mudou = inicio != t.inicio || fim != t.fim
                    repo.salvarTurno(t.copy(inicio = inicio, fim = fim, trabalhoMs = if (mudou) trab else t.trabalhoMs))
                    finish()
                }
            }, R.drawable.ic_check), ui.mp(ui.dp(12)))
            conteudo.addView(c, ui.mp())
        }

        val futuro = LocalDate.parse(data).isAfter(LocalDate.now())
        if (turnoId == null && app == null) conteudo.addView(cardTurnos(data, futuro), ui.mp())

        val bruto = ganhos.sumOf { it.valorCentavos }; val gasto = gastos.filter { it.limiteId == null }.sumOf { it.valorCentavos }; val pessoal = gastos.filter { it.limiteId != null }.sumOf { it.valorCentavos }
        val tot = ui.card()
        tot.addView(ui.tituloIcone(R.drawable.ic_wallet, "Totais"))
        tot.addView(ui.text("Bruto ${Fmt.reais(bruto)}" + (if (app == null) "  ·  Gastos ${Fmt.reais(gasto)}  ·  Líquido ${Fmt.reais(bruto - gasto)}" + (if (pessoal > 0) "\nPessoais (limites): ${Fmt.reais(pessoal)} — não entram no líquido do trabalho" else "") else "  ·  ${ganhos.size} corridas"), 16f), ui.mp(ui.dp(8)))
        if (futuro) {
            tot.addView(ui.text("Dia futuro: só para ver. Ganhos e gastos podem ser lançados a partir do próprio dia.", 14f, Cores.BRANCO_70), ui.mp(ui.dp(8)))
        } else {
            val bts = ui.hbox()
            bts.addView(ui.comIcone(ui.botao("Ganho", Ui.Estilo.CHEIO_ESMERALDA) { dlg.ganho(data, turnoId, app) }, R.drawable.ic_add), ui.peso(1f))
            if (app == null) bts.addView(ui.comIcone(ui.botao("Gasto") { dlg.gasto(data, turnoId) }, R.drawable.ic_add), ui.peso(1f, ui.dp(8)))
            tot.addView(bts, ui.mp(ui.dp(12)))
        }
        conteudo.addView(tot, ui.mp(ui.dp(12)))

        val lg = ui.card()
        lg.addView(ui.tituloIcone(R.drawable.ic_trending, "Ganhos", Cores.ESMERALDA))
        if (ganhos.isEmpty()) lg.addView(ui.text("Nenhum ganho lançado.", 15f, Cores.BRANCO_70), ui.mp(ui.dp(8)))
        ganhos.forEach { g ->
            lg.addView(linha("${Fmt.nomeApp(g.app)} · ${Fmt.reais(g.valorCentavos)}" + (g.descricao?.let { " · $it" } ?: ""),
                { dlg.ganho(g.data, g.turnoId, existente = g) },
                { dlg.confirmar("Excluir ganho", "Excluir ${Fmt.reais(g.valorCentavos)} do ${Fmt.nomeApp(g.app)}?", "Excluir") { lifecycleScope.launch { repo.excluirGanho(g.id) } } }), ui.mp(ui.dp(6)))
        }
        conteudo.addView(lg, ui.mp(ui.dp(12)))

        if (app == null) {
            val ld = ui.card()
            ld.addView(ui.tituloIcone(R.drawable.ic_wrench, "Gastos", Cores.CIANO))
            if (gastos.isEmpty()) ld.addView(ui.text("Nenhum gasto lançado.", 15f, Cores.BRANCO_70), ui.mp(ui.dp(8)))
            gastos.forEach { g ->
                val extra = g.kwh?.let { " · calculado: ${Fmt.kwh(it)} kWh" } ?: if (g.categoria == space.neural.luna.data.local.CategoriaGasto.ENERGIA) " · informado" else ""
                ld.addView(linha("${Fmt.nomeCategoria(g.categoria)} · ${Fmt.reais(g.valorCentavos)}$extra" + (g.descricao?.let { " · $it" } ?: ""),
                    { dlg.gasto(g.data, g.turnoId, existente = g) },
                    { dlg.confirmar("Excluir gasto", "Excluir ${Fmt.reais(g.valorCentavos)}?", "Excluir") { lifecycleScope.launch { repo.excluirGasto(g.id) } } }), ui.mp(ui.dp(6)))
            }
            conteudo.addView(ld, ui.mp(ui.dp(12)))
        }
    }

    /** Os 4 turnos do dia: início, término, pausas e horas (editáveis; dá para lançar dias passados). */
    private fun cardTurnos(data: String, futuro: Boolean): LinearLayout {
        val c = ui.card(destaque = true, acento = Cores.CIANO)
        val agora = space.neural.luna.sync.Relogio.agora()
        val total = turnosDia.sumOf { MaquinaTurno.trabalhoMs(it, agora) }
        c.addView(ui.hbox().apply {
            addView(ui.tituloIcone(R.drawable.ic_timer, "Turnos do dia", Cores.CIANO), ui.peso(1f))
            addView(ui.text("Trabalho: ${Fmt.hms(total).substring(0, 5)} h", 15f, Cores.CIANO, bold = true))
        })
        for (n in 1..MaquinaTurno.TOTAL) {
            val t = turnosDia.firstOrNull { it.numero == n }
            val l = ui.hbox().apply { minimumHeight = ui.dp(52) }
            l.addView(ui.text("$n", 16f, Cores.OBSIDIAN, bold = true).apply {
                gravity = android.view.Gravity.CENTER
                background = ui.fundo(if (t != null) Cores.CIANO else Cores.alpha(Cores.BRANCO, 0.15f), raio = 16f)
            }, LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)))
            val txt = if (t == null) "Turno $n — não lançado" else {
                val fimTxt = when {
                    t.estado == space.neural.luna.data.local.EstadoTurno.PRONTO -> "pronto"
                    t.fim != null -> hhmm(t.fim)
                    else -> "agora"
                }
                "Turno $n · ${if (t.estado == space.neural.luna.data.local.EstadoTurno.PRONTO) "—" else hhmm(t.inicio)}–$fimTxt · pausas ${Fmt.minutos(Calculos.paradoMs(t, agora))} min · ${Fmt.hms(MaquinaTurno.trabalhoMs(t, agora)).substring(0, 5)} h"
            }
            l.addView(ui.ajustavel(txt, 15f, 10f, if (t != null) Cores.BRANCO else Cores.BRANCO_70, bold = false), ui.peso(1f, ui.dp(10)))
            if (t != null && t.estado != space.neural.luna.data.local.EstadoTurno.PRONTO) {
                l.addView(ui.botao("Editar", Ui.Estilo.TEXTO) { editarTurno(data, n, t) })
            } else if (t == null && !futuro) {
                l.addView(ui.botao("Lançar", Ui.Estilo.TEXTO) { editarTurno(data, n, null) })
            }
            c.addView(l, ui.mp(ui.dp(6)))
        }
        if (futuro) c.addView(ui.text("Dia futuro: só visualização.", 13f, Cores.BRANCO_70), ui.mp(ui.dp(4)))
        return c
    }

    private val so = DateTimeFormatter.ofPattern("HH:mm")
    private fun hhmm(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(so)

    /**
     * Editar/lançar turno: início, término e pausas. Horas = término − início − pausas.
     * Turno ainda aberto: mudar o início soma/subtrai a diferença no tempo de trabalho.
     */
    private fun editarTurno(data: String, n: Int, t: Turno?) {
        val dia = LocalDate.parse(data)
        val z = ZoneId.systemDefault()
        val aberto = t != null && t.fim == null
        var ini = t?.inicio ?: dia.atTime(6, 0).atZone(z).toInstant().toEpochMilli()
        var fimMs: Long? = t?.fim ?: if (t == null) dia.atTime(10, 0).atZone(z).toInstant().toEpochMilli() else null
        val c = dlg.corpo()
        val bIni = ui.botao("") {}; val bFim = ui.botao("") {}
        val pausas = dlg.campo("Pausas (minutos)", ((t?.paradoMs ?: 0) / 60_000).toString(), decimal = false).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            isEnabled = !aberto
        }
        val horas = ui.text("", 15f, Cores.CIANO, bold = true)
        fun calc(): Long? {
            val f = fimMs ?: return null
            val p = (pausas.text.toString().toLongOrNull() ?: 0) * 60_000
            return (f - ini - p).coerceAtLeast(0)
        }
        fun pintar() {
            bIni.text = "Início: ${hhmm(ini)}"
            bFim.text = if (fimMs == null) "Término: em andamento" else "Término: ${hhmm(fimMs!!)}"
            horas.text = calc()?.let { "Horas trabalhadas: ${Fmt.hms(it).substring(0, 5)}" }
                ?: "Horas trabalhadas: ${Fmt.hms(MaquinaTurno.trabalhoMs(t, space.neural.luna.sync.Relogio.agora()) + ((t?.inicio ?: ini) - ini)).substring(0, 5)} (em andamento)"
        }
        bIni.setOnClickListener { escolherHora(ini) { ini = it; pintar() } }
        bFim.setOnClickListener { if (fimMs != null) escolherHora(fimMs!!) { v -> fimMs = if (v <= ini) v + 86_400_000L else v; pintar() } }
        pausas.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = pintar()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        c.addView(bIni, ui.mp()); c.addView(bFim, ui.mp(ui.dp(8))); c.addView(pausas, ui.mp(ui.dp(8))); c.addView(horas, ui.mp(ui.dp(10)))
        pintar()
        val b = AlertDialog.Builder(this).setTitle(if (t == null) "Lançar turno $n" else "Turno $n — ${Fmt.dataCurta(dia)}").setView(c)
            .setPositiveButton("Salvar", null).setNegativeButton("Cancelar", null)
        if (t != null) b.setNeutralButton("Excluir") { _, _ ->
            dlg.confirmar("Excluir turno $n", "Os ganhos e gastos do dia continuam.", "Excluir") { lifecycleScope.launch { repo.excluirTurno(t.id) } }
        }
        val a = b.create()
        a.setOnShowListener {
            a.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val f = fimMs
                if (f != null && f <= ini) return@setOnClickListener dlg.erro("O término precisa ser depois do início.")
                if (ini > System.currentTimeMillis()) return@setOnClickListener dlg.erro("O início não pode ser no futuro.")
                val p = (pausas.text.toString().toLongOrNull() ?: 0) * 60_000
                lifecycleScope.launch {
                    if (t == null) {
                        val est = if (dia.isBefore(LocalDate.now())) space.neural.luna.data.local.EstadoTurno.CONCLUIDO else space.neural.luna.data.local.EstadoTurno.FECHADO
                        repo.salvarTurno(Turno(data = data, numero = n, inicio = ini, fim = f, ultimoEvento = f ?: ini, estado = est, paradoMs = p, trabalhoMs = calc() ?: 0))
                    } else if (aberto) {
                        // Em andamento: só o início muda; o contador soma a diferença.
                        repo.salvarTurno(t.copy(inicio = ini, trabalhoMs = (t.trabalhoMs + (t.inicio - ini)).coerceAtLeast(0)))
                    } else {
                        repo.salvarTurno(t.copy(inicio = ini, fim = f, paradoMs = p, trabalhoMs = calc() ?: t.trabalhoMs))
                    }
                }
                a.dismiss()
            }
        }
        a.show()
    }

    private fun linha(texto: String, editar: () -> Unit, excluir: () -> Unit) = ui.hbox().apply {
        addView(ui.text(texto, 15f), ui.peso(1f))
        addView(ui.botao("Editar", Ui.Estilo.TEXTO) { editar() })
        addView(ui.botaoIcone(R.drawable.ic_close, "Excluir: $texto", Cores.BRANCO_70) { excluir() })
    }

    private fun fmtHora(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(hm)

    private fun escolherHora(base: Long, ok: (Long) -> Unit) {
        val z = Instant.ofEpochMilli(base).atZone(ZoneId.systemDefault())
        TimePickerDialog(this, { _, h, m ->
            ok(z.withHour(h).withMinute(m).withSecond(0).toInstant().toEpochMilli())
        }, z.hour, z.minute, true).show()
    }

    companion object {
        const val EXTRA_DATA = "data"
        const val EXTRA_APP = "app"
        const val EXTRA_TURNO = "turno"

        fun dia(ctx: Context, data: String, app: String? = null) =
            Intent(ctx, LancamentosActivity::class.java).putExtra(EXTRA_DATA, data).putExtra(EXTRA_APP, app)

        fun correcao(ctx: Context, turnoId: String) =
            Intent(ctx, LancamentosActivity::class.java).putExtra(EXTRA_TURNO, turnoId)
    }
}
