package space.neural.luna.config

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import space.neural.luna.R
import space.neural.luna.dashboard.Calculos
import space.neural.luna.dashboard.Cores
import space.neural.luna.dashboard.Dialogos
import space.neural.luna.dashboard.Fmt
import space.neural.luna.dashboard.Plano
import space.neural.luna.dashboard.Ui
import space.neural.luna.dashboard.telaSimples
import space.neural.luna.data.local.ContaFixa
import space.neural.luna.data.local.Limite
import space.neural.luna.data.local.Meta
import space.neural.luna.data.local.Planejamento
import space.neural.luna.data.local.TipoMeta
import space.neural.luna.data.repo.LunaRepositories
import java.time.LocalDate
import java.util.Locale

/**
 * Configurações > Planejamento: dias de trabalho (4 semanas), ganho por hora típico,
 * contas fixas, contas com limite (envelopes) e renda desejada. O app calcula a
 * sugestão de metas (dia/semana/mês e horas por dia); ele aceita ou edita.
 * As metas só mudam aqui (o dashboard só mostra o progresso).
 */
class PlanejamentoActivity : AppCompatActivity() {
    private lateinit var ui: Ui
    private lateinit var repo: LunaRepositories
    private lateinit var dlg: Dialogos
    private lateinit var conteudo: LinearLayout
    private var plano = Planejamento()
    private var contas: List<ContaFixa> = emptyList()
    private var limites: List<Limite> = emptyList()
    private var metas: List<Meta> = emptyList()
    private var primeira = true

    private val gradeDias by lazy { ui.vbox() }
    private val listaContas by lazy { ui.vbox() }
    private val listaLimites by lazy { ui.vbox() }
    private val sugestaoBox by lazy { ui.vbox() }
    private val ganhoHora by lazy { dlg.campo("R$ por hora (ex.: 30)") }
    private val renda by lazy { dlg.campo("R$ no mês (opcional)") }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this); repo = LunaRepositories(this); dlg = Dialogos(this, repo, lifecycleScope)
        val (raiz, c) = telaSimples(this, ui, "Planejamento")
        conteudo = c
        montar()
        // v0.1.7: até a primeira descida da nuvem, o Planejamento fica travado (na 0.1.6, mexer aqui num aparelho
        // novo sobrescrevia o planejamento do outro aparelho com o padrão).
        val tela = android.widget.FrameLayout(this)
        tela.addView(raiz, android.widget.FrameLayout.LayoutParams(-1, -1))
        baixando = space.neural.luna.dashboard.BaixandoDados(ui, "Voltar") { finish() }.also { tela.addView(it.view, android.widget.FrameLayout.LayoutParams(-1, -1)) }
        setContentView(tela)
        lifecycleScope.launch { space.neural.luna.sync.PrimeiraCarga.mudou.collect { atualizarBaixando(); preencherCampos() } }
        lifecycleScope.launch {
            combine(repo.observePlanejamento(), repo.observeContasFixas(), repo.observeLimites(), repo.observeMetas()) { p, cf, l, m ->
                arrayOf(p, cf, l, m)
            }.collect { a ->
                @Suppress("UNCHECKED_CAST")
                run {
                    plano = (a[0] as Planejamento?) ?: Planejamento()
                    contas = a[1] as List<ContaFixa>; limites = a[2] as List<Limite>; metas = a[3] as List<Meta>
                }
                carregou = true
                preencherCampos()
                desenhar()
            }
        }
    }

    override fun onPause() { salvarCampos(); super.onPause() }

    private var baixando: space.neural.luna.dashboard.BaixandoDados? = null
    private var carregou = false

    private fun pronto() = space.neural.luna.sync.PrimeiraCarga.pronto(this, space.neural.luna.sync.Nuvem.uid(this))

    private fun atualizarBaixando() { baixando?.bind(!pronto(), space.neural.luna.sync.Nuvem.temInternet(this)) }

    /** Os campos só recebem o plano depois que ele existe de verdade (Room carregado e nuvem já baixada). */
    private fun preencherCampos() {
        if (!primeira || !carregou || !pronto()) return
        primeira = false
        ganhoHora.setText(texto(plano.ganhoHoraCentavos))
        renda.setText(plano.rendaDesejadaCentavos?.let { texto(it) } ?: "")
    }

    private fun texto(c: Long) = String.format(Locale.forLanguageTag("pt-BR"), "%.2f", c / 100.0)

    private fun salvarCampos() {
        if (primeira || !pronto()) return
        val gh = Calculos.parseCentavos(ganhoHora.text.toString()) ?: plano.ganhoHoraCentavos
        val r = renda.text.toString().trim().let { if (it.isEmpty()) null else Calculos.parseCentavos(it) }
        if (gh != plano.ganhoHoraCentavos || r != plano.rendaDesejadaCentavos) {
            plano = plano.copy(ganhoHoraCentavos = gh, rendaDesejadaCentavos = r)
            val p = plano
            lifecycleScope.launch { repo.salvarPlanejamento(p) }
        }
    }

    private fun secao(icone: Int, titulo: String, cor: Int, explica: String?): LinearLayout {
        val card = ui.card(acento = cor)
        card.addView(ui.tituloIcone(icone, titulo, cor))
        if (explica != null) card.addView(ui.text(explica, 14f, Cores.BRANCO_70), ui.mp(ui.dp(6)))
        conteudo.addView(card, ui.mp(if (conteudo.childCount == 0) 0 else ui.dp(14)))
        return card
    }

    private fun montar() {
        secao(R.drawable.ic_calendar, "Dias de trabalho", Cores.CIANO, "Toque nos dias que você trabalha em cada semana do mês (semana 1 = dias 1–7, … semana 4 = dia 22 em diante).")
            .addView(gradeDias, ui.mp(ui.dp(10)))
        val g = secao(R.drawable.ic_timer, "Ganho por hora típico", Cores.MAGENTA, "Quanto sobra por hora num dia normal (líquido). Usado para dizer quantas horas trabalhar.")
        g.addView(ganhoHora, ui.mp(ui.dp(8)))
        val cf = secao(R.drawable.ic_flag, "Contas fixas", Cores.LARANJA, "Aluguel, internet, parcela… nome, valor e dia do vencimento.")
        cf.addView(listaContas, ui.mp(ui.dp(8)))
        cf.addView(ui.comIcone(ui.botao("Conta fixa") { editarConta(null) }, R.drawable.ic_add), ui.mp(ui.dp(8)))
        val li = secao(R.drawable.ic_wallet, "Contas com limite (envelopes)", Cores.ESMERALDA, "Quanto pode gastar no mês em cada coisa, ex.: Supermercado R$ 1.000, Lanche R$ 200.")
        li.addView(listaLimites, ui.mp(ui.dp(8)))
        li.addView(ui.comIcone(ui.botao("Limite") { editarLimite(null) }, R.drawable.ic_add), ui.mp(ui.dp(8)))
        val rd = secao(R.drawable.ic_trending, "Renda desejada", Cores.VIOLETA, "Opcional: quanto você quer que sobre no mês além das contas.")
        rd.addView(renda, ui.mp(ui.dp(8)))
        val sg = secao(R.drawable.ic_bulb, "Sugestão de metas", Cores.CIANO, null)
        sg.addView(sugestaoBox, ui.mp(ui.dp(8)))
    }

    private fun desenhar() {
        // Dias
        gradeDias.removeAllViews()
        val dias = Plano.dias(plano).map { it.toMutableList() }
        val nomes = listOf("S", "T", "Q", "Q", "S", "S", "D")
        val nomesLongos = listOf("segunda", "terça", "quarta", "quinta", "sexta", "sábado", "domingo")
        dias.forEachIndexed { s, sem ->
            val l = ui.hbox()
            l.addView(ui.text("Sem ${s + 1}", 13f, Cores.BRANCO_70, bold = true), LinearLayout.LayoutParams(ui.dp(52), -2))
            sem.forEachIndexed { i, on ->
                val b = ui.text(nomes[i], 14f, if (on) Cores.OBSIDIAN else Cores.BRANCO, bold = true).apply {
                    gravity = Gravity.CENTER; minHeight = ui.dp(44); isClickable = true; isFocusable = true
                    background = ui.fundo(if (on) Cores.CIANO else Cores.alpha(Cores.CIANO, 0.06f), Cores.alpha(Cores.CIANO, 0.6f), 12f)
                    contentDescription = "Semana ${s + 1}, ${nomesLongos[i]}: " + if (on) "trabalha" else "folga"
                    setOnClickListener {
                        if (!pronto()) return@setOnClickListener
                        dias[s][i] = !dias[s][i]
                        plano = plano.copy(diasTrabalho = Plano.codificar(dias))
                        val p = plano
                        lifecycleScope.launch { repo.salvarPlanejamento(p) }
                        desenhar()
                    }
                }
                l.addView(b, ui.peso(1f, ui.dp(4)))
            }
            gradeDias.addView(l, ui.mp(if (s == 0) 0 else ui.dp(6)))
        }
        // Contas fixas
        listaContas.removeAllViews()
        if (contas.isEmpty()) listaContas.addView(ui.text("Nenhuma conta fixa.", 14f, Cores.BRANCO_40))
        contas.forEach { c -> listaContas.addView(linhaItem("${c.nome} · vence dia ${c.diaVencimento}", Fmt.reais(c.valorCentavos), Cores.LARANJA,
            { editarConta(c) }, { apagar(c.nome) { lifecycleScope.launch { repo.excluirContaFixa(c.id) } } }), ui.mp(ui.dp(4))) }
        // Limites
        listaLimites.removeAllViews()
        if (limites.isEmpty()) listaLimites.addView(ui.text("Nenhum limite.", 14f, Cores.BRANCO_40))
        limites.forEach { l -> listaLimites.addView(linhaItem(l.nome, Fmt.reais(l.limiteCentavos) + "/mês", Cores.ESMERALDA,
            { editarLimite(l) }, { apagar(l.nome) { lifecycleScope.launch { repo.excluirLimite(l.id) } } }), ui.mp(ui.dp(4))) }
        desenharSugestao()
    }

    private fun linhaItem(nome: String, valor: String, cor: Int, editar: () -> Unit, apagar: () -> Unit) = ui.hbox().apply {
        minimumHeight = ui.dp(48); isClickable = true; isFocusable = true
        setOnClickListener { editar() }
        contentDescription = "$nome, $valor. Toque para editar"
        addView(ui.ajustavel(nome, 15f, 11f, Cores.BRANCO, bold = false), ui.peso(1f))
        addView(ui.text(valor, 15f, cor, bold = true), ui.wc(ui.dp(8)))
        addView(ui.botaoIcone(R.drawable.ic_close, "Apagar $nome", Cores.BRANCO_70) { apagar() })
    }

    private fun apagar(nome: String, f: () -> Unit) = dlg.confirmar("Apagar", "Apagar \"$nome\" do planejamento?", "Apagar", f)

    private fun desenharSugestao() {
        sugestaoBox.removeAllViews()
        val gh = Calculos.parseCentavos(ganhoHora.text.toString()) ?: plano.ganhoHoraCentavos
        val r = renda.text.toString().trim().let { if (it.isEmpty()) null else Calculos.parseCentavos(it) }
        val s = Plano.sugerir(plano.copy(ganhoHoraCentavos = gh, rendaDesejadaCentavos = r), contas, limites, LocalDate.now())
        fun linha(a: String, b: String, cor: Int = Cores.BRANCO, grande: Boolean = false) {
            sugestaoBox.addView(ui.hbox().apply {
                addView(ui.text(a, if (grande) 16f else 14f, Cores.BRANCO_70), ui.peso(1f))
                addView(ui.text(b, if (grande) 20f else 15f, cor, bold = true))
            }, ui.mp(ui.dp(4)))
        }
        linha("Contas fixas", Fmt.reais(s.fixasCentavos))
        linha("Limites (envelopes)", Fmt.reais(s.limitesCentavos))
        linha("Renda desejada", Fmt.reais(s.rendaCentavos))
        sugestaoBox.addView(ui.divisor())
        linha("Precisa no mês", Fmt.reais(s.totalMesCentavos), Cores.CIANO, true)
        linha("Dias de trabalho no mês", "${s.diasTrabalhoMes}")
        linha("Por semana (esta: ${s.diasTrabalhoSemana} dias)", Fmt.reais(s.metaSemanaCentavos), Cores.MAGENTA)
        linha("Por dia de trabalho", Fmt.reais(s.metaDiaCentavos), Cores.ESMERALDA, true)
        s.horasDia?.let { linha("Horas por dia a ${Fmt.reaisCurto(gh)}/h", String.format(Locale.forLanguageTag("pt-BR"), "%.1f h", it), Cores.LARANJA) }
        val atual = listOf(TipoMeta.DIA, TipoMeta.SEMANA, TipoMeta.MES).map { Calculos.metaVigente(metas, it)?.valorCentavos }
        sugestaoBox.addView(ui.text("Metas atuais: dia ${atual[0]?.let { Fmt.reaisCurto(it) } ?: "—"} · semana ${atual[1]?.let { Fmt.reaisCurto(it) } ?: "—"} · mês ${atual[2]?.let { Fmt.reaisCurto(it) } ?: "—"}", 13f, Cores.BRANCO_70), ui.mp(ui.dp(10)))
        val bts = ui.hbox()
        bts.addView(ui.botao("Recalcular", Ui.Estilo.CONTORNO) { salvarCampos(); desenharSugestao() }, ui.peso(1f))
        bts.addView(ui.botao("Editar", Ui.Estilo.CONTORNO) { editarMetas(s) }, ui.peso(1f, ui.dp(8)))
        sugestaoBox.addView(bts, ui.mp(ui.dp(10)))
        val ok = ui.botao("Aceitar sugestão", Ui.Estilo.CHEIO_ESMERALDA, alto = true) {
            if (s.totalMesCentavos <= 0) { dlg.erro("Cadastre contas, limites ou a renda desejada primeiro."); return@botao }
            salvarMetas(s.metaDiaCentavos, s.metaSemanaCentavos, s.totalMesCentavos)
        }
        sugestaoBox.addView(ok, ui.mp(ui.dp(8)))
    }

    private fun salvarMetas(dia: Long, semana: Long, mes: Long) {
        salvarCampos()
        val h = LocalDate.now().toString()
        lifecycleScope.launch {
            repo.salvarMeta(Meta(data = h, tipo = TipoMeta.DIA, valorCentavos = dia))
            repo.salvarMeta(Meta(data = h, tipo = TipoMeta.SEMANA, valorCentavos = semana))
            repo.salvarMeta(Meta(data = h, tipo = TipoMeta.MES, valorCentavos = mes))
        }
        android.widget.Toast.makeText(this, "Metas salvas: dia ${Fmt.reaisCurto(dia)}, semana ${Fmt.reaisCurto(semana)}, mês ${Fmt.reaisCurto(mes)}.", android.widget.Toast.LENGTH_LONG).show()
    }

    private fun editarMetas(s: Plano.Sugestao) {
        val c = dlg.corpo()
        c.addView(ui.text("Ajuste a sugestão antes de salvar.", 15f, Cores.BRANCO_70))
        val d = dlg.campo("Meta do dia (R$)", texto(s.metaDiaCentavos))
        val w = dlg.campo("Meta da semana (R$)", texto(s.metaSemanaCentavos))
        val m = dlg.campo("Meta do mês (R$)", texto(s.totalMesCentavos))
        listOf(d, w, m).forEach { c.addView(it, ui.mp(ui.dp(8))) }
        val a = AlertDialog.Builder(this).setTitle("Editar metas").setView(c).setPositiveButton("Salvar", null).setNegativeButton("Cancelar", null).create()
        a.setOnShowListener {
            a.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val vd = Calculos.parseCentavos(d.text.toString()); val vw = Calculos.parseCentavos(w.text.toString()); val vm = Calculos.parseCentavos(m.text.toString())
                if (vd == null || vw == null || vm == null) return@setOnClickListener dlg.erro("Preencha as três metas com valores maiores que zero.")
                salvarMetas(vd, vw, vm); a.dismiss()
            }
        }
        a.show()
    }

    private fun editarConta(x: ContaFixa?) {
        val c = dlg.corpo()
        val n = dlg.campo("Nome (ex.: Aluguel)", x?.nome ?: "", decimal = false)
        val v = dlg.campo("Valor (R$)", x?.let { texto(it.valorCentavos) } ?: "")
        val d = dlg.campo("Dia do vencimento (1–31)", x?.diaVencimento?.toString() ?: "")
        listOf(n, v, d).forEach { c.addView(it, ui.mp(ui.dp(8))) }
        val a = AlertDialog.Builder(this).setTitle(if (x == null) "Nova conta fixa" else "Editar conta").setView(c).setPositiveButton("Salvar", null).setNegativeButton("Cancelar", null).create()
        a.setOnShowListener {
            a.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val nome = n.text.toString().trim()
                val cent = Calculos.parseCentavos(v.text.toString())
                val dia = d.text.toString().trim().toIntOrNull()?.takeIf { it in 1..31 }
                if (nome.isEmpty() || cent == null || dia == null) return@setOnClickListener dlg.erro("Preencha nome, valor e dia (1 a 31).")
                lifecycleScope.launch { repo.salvarContaFixa((x ?: ContaFixa(nome = nome, valorCentavos = cent, diaVencimento = dia)).copy(nome = nome, valorCentavos = cent, diaVencimento = dia)) }
                a.dismiss()
            }
        }
        a.show()
    }

    private fun editarLimite(x: Limite?) {
        val c = dlg.corpo()
        val n = dlg.campo("Nome (ex.: Supermercado)", x?.nome ?: "", decimal = false)
        val v = dlg.campo("Limite no mês (R$)", x?.let { texto(it.limiteCentavos) } ?: "")
        listOf(n, v).forEach { c.addView(it, ui.mp(ui.dp(8))) }
        val a = AlertDialog.Builder(this).setTitle(if (x == null) "Novo limite" else "Editar limite").setView(c).setPositiveButton("Salvar", null).setNegativeButton("Cancelar", null).create()
        a.setOnShowListener {
            a.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val nome = n.text.toString().trim()
                val cent = Calculos.parseCentavos(v.text.toString())
                if (nome.isEmpty() || cent == null) return@setOnClickListener dlg.erro("Preencha nome e limite.")
                lifecycleScope.launch { repo.salvarLimite((x ?: Limite(nome = nome, limiteCentavos = cent)).copy(nome = nome, limiteCentavos = cent)) }
                a.dismiss()
            }
        }
        a.show()
    }
}
