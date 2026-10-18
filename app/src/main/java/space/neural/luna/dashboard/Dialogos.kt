package space.neural.luna.dashboard

import android.app.Activity
import android.app.DatePickerDialog
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.launch
import space.neural.luna.data.local.AppCorrida
import space.neural.luna.data.local.CategoriaGasto
import space.neural.luna.data.local.Ganho
import space.neural.luna.data.local.Gasto
import space.neural.luna.data.local.Meta
import space.neural.luna.data.local.TipoMeta
import space.neural.luna.data.repo.LunaRepositories
import java.time.LocalDate
import java.util.Locale

/** Lançamentos rápidos (ganho, gasto, recarga, meta) em diálogos. Tudo salva no Room na hora. */
class Dialogos(private val act: Activity, private val repo: LunaRepositories, private val scope: LifecycleCoroutineScope) {
    private val ui = Ui(act)
    private val prefs = act.getSharedPreferences("dashboard", 0)

    fun campo(dica: String, valor: String = "", decimal: Boolean = true) = EditText(act).apply {
        hint = dica; setText(valor); setSingleLine()
        setTextColor(Cores.BRANCO); setHintTextColor(Cores.BRANCO_70); textSize = 18f
        inputType = if (decimal) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        if (decimal) keyListener = android.text.method.DigitsKeyListener.getInstance("0123456789.,")
        minHeight = ui.dp(48)
    }

    fun corpo(): LinearLayout = ui.vbox(ui.dp(20))

    /** Grupo de escolha única com botões (≥48dp). */
    private fun escolha(opcoes: List<Pair<String, String>>, inicial: String, aoMudar: (String) -> Unit = {}): Pair<LinearLayout, () -> String> {
        var atual = inicial
        // Até 3 por linha (5 fontes de ganho cabem em 2 linhas no telefone).
        val grade = ui.vbox()
        var linha = ui.hbox()
        val botoes = mutableListOf<android.widget.Button>()
        fun pintar() {
            botoes.forEachIndexed { i, b ->
                val sel = opcoes[i].first == atual
                b.background = ui.fundo(if (sel) Cores.VIOLETA else Cores.alpha(Cores.VIOLETA, 0.0f), Cores.VIOLETA, 14f)
                b.setTextColor(if (sel) Cores.OBSIDIAN else Cores.BRANCO)
                b.contentDescription = opcoes[i].second + if (sel) ", selecionado" else ""
            }
        }
        opcoes.forEachIndexed { i, (id, nome) ->
            val b = ui.botao(nome) { atual = id; pintar(); aoMudar(id) }
            botoes += b
            if (i % 3 == 0) { linha = ui.hbox(); grade.addView(linha, ui.mp(if (i == 0) 0 else ui.dp(8))) }
            linha.addView(b, ui.peso(1f, if (i % 3 == 0) 0 else ui.dp(8)))
        }
        if (opcoes.size > 3) repeat((3 - opcoes.size % 3) % 3) { linha.addView(android.view.View(act), ui.peso(1f, ui.dp(8))) }
        pintar()
        return grade to { atual }
    }

    fun erro(msg: String) = Toast.makeText(act, msg, Toast.LENGTH_SHORT).show()

    /** Lançar (ou editar) um ganho. [app] pré-seleciona o aplicativo. */
    fun ganho(data: String, turnoId: String?, app: String? = null, existente: Ganho? = null, depois: () -> Unit = {}) {
        val c = corpo()
        val (apps, appSel) = escolha(
            Calculos.APPS.map { it to Fmt.nomeApp(it) },
            existente?.app ?: app ?: AppCorrida.UBER,
        )
        c.addView(ui.text("Aplicativo", 14f, Cores.BRANCO_70))
        c.addView(apps, ui.mp(ui.dp(6)))
        val valor = campo("Valor da corrida (R$)", existente?.let { valorTexto(it.valorCentavos) } ?: "")
        c.addView(valor, ui.mp(ui.dp(16)))
        val desc = campo("Observação (opcional)", existente?.descricao ?: "", decimal = false)
        c.addView(desc, ui.mp(ui.dp(8)))
        if (data != LocalDate.now().toString()) c.addView(ui.text("Dia: ${Fmt.dataLonga(LocalDate.parse(data))}", 14f, Cores.CIANO), ui.mp(ui.dp(8)))
        val dlg = AlertDialog.Builder(act)
            .setTitle(if (existente == null) "Lançar ganho" else "Editar ganho")
            .setView(c)
            .setPositiveButton("Salvar", null)
            .setNegativeButton("Cancelar", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val v = Calculos.parseCentavos(valor.text.toString()) ?: return@setOnClickListener erro("Digite um valor maior que zero.")
                val d = desc.text.toString().trim().ifBlank { null }
                scope.launch {
                    repo.salvarGanho(
                        existente?.copy(app = appSel(), valorCentavos = v, descricao = d)
                            ?: Ganho(turnoId = turnoId, data = data, app = appSel(), valorCentavos = v, descricao = d)
                    )
                    depois()
                }
                dlg.dismiss()
            }
        }
        dlg.show(); valor.requestFocus()
    }

    /**
     * Lançar (ou editar) um gasto. Energia = recarga elétrica do Geely: valor
     * informado em R$ ou calculado pelos kWh × preço do kWh (o preço fica salvo).
     */
    fun gasto(data: String, turnoId: String?, categoria: String? = null, existente: Gasto? = null, depois: () -> Unit = {}) {
        val c = corpo()
        val inicialCat = existente?.categoria ?: categoria ?: CategoriaGasto.ENERGIA
        val porKwhInicial = existente?.kwh != null
        val blocoEnergia = ui.vbox()
        val blocoKwh = ui.vbox()
        val valor = campo("Valor (R$)", existente?.takeIf { it.kwh == null }?.let { valorTexto(it.valorCentavos) } ?: "")
        val kwh = campo("kWh recarregados", existente?.kwh?.let { Fmt.kwh(it) } ?: "")
        val precoSalvo = prefs.getLong("preco_kwh_centavos", 0L)
        val preco = campo("Preço do kWh (R$)", if (precoSalvo > 0) valorTexto(precoSalvo) else "")
        val previa = ui.text("", 15f, Cores.CIANO)
        fun calc() {
            val k = Calculos.parseDecimal(kwh.text.toString()); val p = Calculos.parseCentavos(preco.text.toString())
            previa.text = if (k != null && p != null) "⚡ Total calculado: ${Fmt.reais(Math.round(k * p))}" else ""
        }
        val w = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = calc()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        kwh.addTextChangedListener(w); preco.addTextChangedListener(w); calc()
        blocoKwh.addView(kwh, ui.mp()); blocoKwh.addView(preco, ui.mp(ui.dp(8))); blocoKwh.addView(previa, ui.mp(ui.dp(6)))
        var porKwh = porKwhInicial
        fun modo() {
            blocoKwh.visibility = if (porKwh) View.VISIBLE else View.GONE
            valor.visibility = if (porKwh) View.GONE else View.VISIBLE
        }
        val (modos, _) = escolha(listOf("RS" to "Valor em R$", "KWH" to "Por kWh"), if (porKwh) "KWH" else "RS") {
            porKwh = it == "KWH"; modo()
        }
        blocoEnergia.addView(ui.text("Recarga elétrica do Geely (não é combustível)", 14f, Cores.BRANCO_70))
        blocoEnergia.addView(modos, ui.mp(ui.dp(6)))
        val (cats, catSel) = escolha(
            listOf(CategoriaGasto.ENERGIA to "Energia", CategoriaGasto.MANUTENCAO to "Manutenção", CategoriaGasto.OUTRO to "Outro"),
            inicialCat,
        ) { cat ->
            blocoEnergia.visibility = if (cat == CategoriaGasto.ENERGIA) View.VISIBLE else View.GONE
            if (cat != CategoriaGasto.ENERGIA) { porKwh = false }
            modo()
        }
        blocoEnergia.visibility = if (inicialCat == CategoriaGasto.ENERGIA) View.VISIBLE else View.GONE
        modo()
        val desc = campo("Observação (opcional)", existente?.descricao ?: "", decimal = false)
        c.addView(ui.text("Tipo de gasto", 14f, Cores.BRANCO_70))
        c.addView(cats, ui.mp(ui.dp(6)))
        c.addView(blocoEnergia, ui.mp(ui.dp(14)))
        c.addView(valor, ui.mp(ui.dp(12)))
        c.addView(blocoKwh, ui.mp(ui.dp(12)))
        c.addView(desc, ui.mp(ui.dp(8)))
        if (data != LocalDate.now().toString()) c.addView(ui.text("Dia: ${Fmt.dataLonga(LocalDate.parse(data))}", 14f, Cores.CIANO), ui.mp(ui.dp(8)))
        val dlg = AlertDialog.Builder(act)
            .setTitle(if (existente == null) "Lançar gasto" else "Editar gasto")
            .setView(android.widget.ScrollView(act).apply { addView(c) })
            .setPositiveButton("Salvar", null)
            .setNegativeButton("Cancelar", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val cat = catSel()
                val d = desc.text.toString().trim().ifBlank { null }
                val item: Gasto
                if (cat == CategoriaGasto.ENERGIA && porKwh) {
                    val k = Calculos.parseDecimal(kwh.text.toString()) ?: return@setOnClickListener erro("Digite os kWh.")
                    val p = Calculos.parseCentavos(preco.text.toString()) ?: return@setOnClickListener erro("Digite o preço do kWh.")
                    prefs.edit().putLong("preco_kwh_centavos", p).apply()
                    val v = Math.round(k * p)
                    item = existente?.copy(categoria = cat, valorCentavos = v, kwh = k, descricao = d)
                        ?: Gasto(turnoId = turnoId, data = data, categoria = cat, valorCentavos = v, kwh = k, descricao = d)
                } else {
                    val v = Calculos.parseCentavos(valor.text.toString()) ?: return@setOnClickListener erro("Digite um valor maior que zero.")
                    item = existente?.copy(categoria = cat, valorCentavos = v, kwh = null, descricao = d)
                        ?: Gasto(turnoId = turnoId, data = data, categoria = cat, valorCentavos = v, descricao = d)
                }
                scope.launch { repo.salvarGasto(item); depois() }
                dlg.dismiss()
            }
        }
        dlg.show()
    }

    fun meta(tipo: String, atualCentavos: Long?) {
        val nome = when (tipo) { TipoMeta.SEMANA -> "da semana"; TipoMeta.MES -> "do mês"; else -> "do dia" }
        val c = corpo()
        c.addView(ui.text("Quanto você quer que sobre no bolso (líquido) $nome?", 15f, Cores.BRANCO_70))
        val v = campo("Meta (R$)", atualCentavos?.let { valorTexto(it) } ?: "")
        c.addView(v, ui.mp(ui.dp(12)))
        val dlg = AlertDialog.Builder(act).setTitle("Meta $nome").setView(c)
            .setPositiveButton("Salvar", null).setNegativeButton("Cancelar", null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val cent = Calculos.parseCentavos(v.text.toString()) ?: return@setOnClickListener erro("Digite um valor maior que zero.")
                scope.launch { repo.salvarMeta(Meta(data = LocalDate.now().toString(), tipo = tipo, valorCentavos = cent)) }
                dlg.dismiss()
            }
        }
        dlg.show(); v.requestFocus()
    }

    /** Gasto pessoal num envelope (ex.: Supermercado): não entra no líquido do trabalho. */
    fun gastoEnvelope(l: space.neural.luna.data.local.Limite, restanteCentavos: Long) {
        val c = corpo()
        c.addView(ui.text("Ainda pode gastar ${Fmt.reais(restanteCentavos)} em ${l.nome} este mês.", 15f, Cores.BRANCO_70))
        val v = campo("Valor (R$)")
        val d = campo("Descrição (opcional)", decimal = false)
        c.addView(v, ui.mp(ui.dp(12))); c.addView(d, ui.mp(ui.dp(8)))
        val dlg = AlertDialog.Builder(act).setTitle("Gasto em ${l.nome}").setView(c)
            .setPositiveButton("Salvar", null).setNegativeButton("Cancelar", null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val cent = Calculos.parseCentavos(v.text.toString()) ?: return@setOnClickListener erro("Digite um valor maior que zero.")
                scope.launch {
                    repo.salvarGasto(Gasto(data = LocalDate.now().toString(), categoria = CategoriaGasto.OUTRO, valorCentavos = cent,
                        descricao = d.text.toString().trim().ifEmpty { l.nome }, limiteId = l.id))
                }
                val sobra = restanteCentavos - cent
                Toast.makeText(act, if (sobra >= 0) "Agora você pode gastar ${Fmt.reais(sobra)} em ${l.nome}." else "Passou ${Fmt.reais(-sobra)} do limite de ${l.nome}.", Toast.LENGTH_LONG).show()
                dlg.dismiss()
            }
        }
        dlg.show(); v.requestFocus()
    }

    fun confirmar(titulo: String, msg: String, ok: String, acao: () -> Unit) {
        AlertDialog.Builder(act).setTitle(titulo).setMessage(msg)
            .setPositiveButton(ok) { _, _ -> acao() }.setNegativeButton("Cancelar", null).show()
    }

    fun escolherDia(aoEscolher: (LocalDate) -> Unit) {
        val h = LocalDate.now()
        DatePickerDialog(act, { _, y, m, d -> aoEscolher(LocalDate.of(y, m + 1, d)) }, h.year, h.monthValue - 1, h.dayOfMonth)
            .apply { datePicker.maxDate = System.currentTimeMillis(); setTitle("Lançar dia esquecido") }.show()
    }

    companion object {
        fun valorTexto(c: Long): String = String.format(Locale.forLanguageTag("pt-BR"), "%.2f", c / 100.0)
    }
}
