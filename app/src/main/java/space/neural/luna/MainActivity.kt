package space.neural.luna

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import space.neural.luna.chat.ChatActivity
import space.neural.luna.dashboard.AcoesPainel
import space.neural.luna.dashboard.Anima
import space.neural.luna.dashboard.AnotacoesActivity
import space.neural.luna.dashboard.FundoNeon
import space.neural.luna.dashboard.GraficoCard
import space.neural.luna.dashboard.KpiStrip
import space.neural.luna.dashboard.CalendarioCard
import space.neural.luna.dashboard.GalaxiaView
import space.neural.luna.dashboard.LunaCard
import space.neural.luna.dashboard.RelatoriosCard
import space.neural.luna.chat.LunaConversa
import space.neural.luna.dashboard.LimitesCard
import space.neural.luna.dashboard.LancamentosCard
import space.neural.luna.dashboard.Periodo
import space.neural.luna.dashboard.RankingCard
import space.neural.luna.dashboard.RoscasCard
import space.neural.luna.dashboard.SeletorPeriodo
import space.neural.luna.dashboard.Cabecalho
import space.neural.luna.dashboard.Calculos
import space.neural.luna.dashboard.Cores
import space.neural.luna.dashboard.Dialogos
import space.neural.luna.dashboard.DicaCard
import space.neural.luna.dashboard.Externo
import space.neural.luna.dashboard.Fmt
import space.neural.luna.dashboard.HistoricoActivity
import space.neural.luna.dashboard.LancamentosActivity
import space.neural.luna.dashboard.MetasCard
import space.neural.luna.dashboard.NoticiasCard
import space.neural.luna.dashboard.Painel
import space.neural.luna.dashboard.ResumoCard
import space.neural.luna.dashboard.TurnoAcoes
import space.neural.luna.data.local.MaquinaTurno
import space.neural.luna.dashboard.TurnoCard
import space.neural.luna.dashboard.Ui
import space.neural.luna.data.local.TipoMeta
import space.neural.luna.data.repo.LunaRepositories
import space.neural.luna.sync.SyncManager
import space.neural.luna.update.AtualizacaoActivity
import java.util.Locale

/**
 * Tela inicial: dashboard (TELAS_DASHBOARD.md). Lê só os dados locais (Room);
 * a sincronização com o Firebase continua em segundo plano (SyncWorker).
 * Layout: 1 coluna (< 600dp), telefone deitado em 2 colunas, tablet (600–839dp) em linhas
 * e 3 colunas com faixa de KPIs (≥ 840dp, ex.: Geely X2).
 */
class MainActivity : AppCompatActivity(), AcoesPainel {
    private lateinit var ui: Ui
    private lateinit var repo: LunaRepositories
    private lateinit var acoes: TurnoAcoes
    private lateinit var dlg: Dialogos
    private val prefs by lazy { getSharedPreferences("dashboard", 0) }

    private lateinit var cab: Cabecalho
    private lateinit var turnoCard: TurnoCard
    private lateinit var metas: MetasCard
    private lateinit var kpis: KpiStrip
    private lateinit var seletor: SeletorPeriodo
    private lateinit var grafico: GraficoCard
    private lateinit var roscas: RoscasCard
    private lateinit var lancamentos: LancamentosCard
    private lateinit var luna: LunaCard
    private lateinit var relatorios: RelatoriosCard
    private lateinit var calendario: CalendarioCard
    private val conversa by lazy { LunaConversa.get(this) }
    /** v0.1.5: conversa por voz contínua (com barge-in) no card da Luna. */
    private val voz by lazy {
        space.neural.luna.voz.ConversaVoz(this, object : space.neural.luna.voz.ConversaVoz.Ouvinte {
            override fun ouvindo(parcial: String?) = luna.ouvindo(parcial)
            override fun ouviu(texto: String) = perguntarLuna(texto)
            override fun encerrou(motivo: String?) { if (motivo != null) luna.ocioso(motivo) else luna.ocioso() }
        })
    }
    private val permMic = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) ouvir() else luna.ocioso("Sem permissão do microfone. Toque duas vezes para escrever na conversa.")
    }
    private val receiptCapture = space.neural.luna.media.ReceiptCapture(this, ::lerNota,
        { message -> Toast.makeText(this, message, Toast.LENGTH_LONG).show() }, { voz.parar(); space.neural.luna.avatar.LunaVoz.parar() })
    private val ditadoExterno = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val t = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!t.isNullOrBlank()) perguntarLuna(t) else luna.ocioso()
    }
    private var periodo: Periodo = Periodo.HOJE
    private lateinit var noticias: NoticiasCard
    private lateinit var dica: DicaCard
    private var painel: Painel? = null
    private var externo: Externo.Dados? = null
    private var carregandoExterno = false
    @Volatile private var online = false
    private val rede by lazy { getSystemService(ConnectivityManager::class.java) }
    private val redeCb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { mudouRede() }
        override fun onLost(network: Network) { mudouRede() }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { mudouRede() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!portaoLicenca()) { bloqueada = true; return }
        ui = Ui(this); repo = LunaRepositories(this); acoes = TurnoAcoes(repo) { space.neural.luna.sync.RealtimeSync.guarda(this) }; dlg = Dialogos(this, repo, lifecycleScope)
        online = temInternet()
        externo = Externo.cache(this)
        montarLayout(animar = savedInstanceState == null)

        lifecycleScope.launch {
            Painel.observar(repo).collect { p -> painel = p; desenhar() }
        }
        // v0.1.7: "Baixando seus dados…" até a primeira descida da nuvem desta conta neste aparelho.
        lifecycleScope.launch { space.neural.luna.sync.PrimeiraCarga.mudou.collect { atualizarBaixando() } }
        // Contador do turno: só o texto muda a cada segundo.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val agora = space.neural.luna.sync.Relogio.agora()
                    turnoCard.tick(agora)
                    painel?.let { turnoCard.tickResumo(it, agora) }
                    delay(1000 - agora % 1000)
                }
            }
        }
        val ultima = externo?.atualizadoEm ?: 0
        if (System.currentTimeMillis() - ultima > 30 * 60_000L) atualizarNoticias()
    }

    // ---------------- Conta e licença (v0.1.6) ----------------
    private var bloqueada = false
    private lateinit var bannerTeste: android.widget.TextView

    /**
     * Sem conta -> login; teste vencido / versão antiga / sem confirmação -> tela de licença.
     * SOS é exceção obrigatória: o botão/atalho de SOS (Rádio) e o serviço que o envia não podem
     * passar por este portão nem pelo LicencaActivity — devem funcionar com teste vencido ou sem pagamento.
     */
    private fun portaoLicenca(): Boolean {
        val l = space.neural.luna.licenca.Licenca
        if (!l.ativa && l.forcar == null) return true
        if (l.ativa && !space.neural.luna.conta.Conta.logado()) { space.neural.luna.conta.LoginActivity.abrir(this); finish(); return false }
        if (!l.decidir(this).liberado) { space.neural.luna.licenca.LicencaActivity.bloquear(this); finish(); return false }
        return true
    }

    private fun montarBanner() = ui.text("", 14f, Cores.BRANCO).apply {
        gravity = android.view.Gravity.CENTER_VERTICAL
        minHeight = ui.dp(40)
        setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
        background = ui.fundo(Cores.alpha(Cores.ESMERALDA, 0.12f), Cores.alpha(Cores.ESMERALDA, 0.55f), 14f)
        isClickable = true; isFocusable = true
        visibility = View.GONE
        setOnClickListener { space.neural.luna.licenca.LicencaActivity.assinar(this@MainActivity) }
    }

    private var janelaAviso: android.app.Dialog? = null
    private val permNotif = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }

    /** Últimos dias do teste: janela (uma vez por limiar) e, no Android 13+, pedido amigável de notificação. */
    private fun avisosTeste(d: space.neural.luna.licenca.LicencaLogica.Decisao.Teste) {
        val aviso = space.neural.luna.licenca.AvisoTeste
        if (janelaAviso?.isShowing == true || isFinishing) return
        val l = aviso.janelaPendente(this, space.neural.luna.licenca.Licenca.agora(this), d.terminaEm)
        if (l != null) { janelaAviso = aviso.mostrarJanela(this, l, d.terminaEm); return }
        if (aviso.deveExplicarPermissao(this)) {
            AlertDialog.Builder(this).setTitle("Avisos do teste grátis")
                .setMessage("Quer que o Rota GV te avise por notificação quando o teste grátis estiver acabando? Só nos últimos dias, sem propaganda.")
                .setPositiveButton("Permitir") { _, _ -> runCatching { permNotif.launch(android.Manifest.permission.POST_NOTIFICATIONS) } }
                .setNegativeButton("Agora não", null).show()
        }
    }

    private fun atualizarLicenca(d: space.neural.luna.licenca.LicencaLogica.Decisao) {
        if (!d.liberado) { space.neural.luna.licenca.LicencaActivity.bloquear(this); finish(); return }
        val zona = java.time.ZoneId.systemDefault()
        val (txt, cor) = when (d) {
            is space.neural.luna.licenca.LicencaLogica.Decisao.Teste -> {
                avisosTeste(d)
                val l = space.neural.luna.licenca.AvisoTeste.limiar(space.neural.luna.licenca.Licenca.agora(this), d.terminaEm, zona)
                (if (l != null) space.neural.luna.licenca.AvisoTeste.textoBanner(l, d.terminaEm, zona)
                else "Teste grátis: ${if (d.diasRestantes == 1) "falta 1 dia" else "faltam ${d.diasRestantes} dias"}  ·  Assinar R$ 7/mês ›") to
                    (if (l == 0) Cores.LARANJA else Cores.ESMERALDA)
            }
            is space.neural.luna.licenca.LicencaLogica.Decisao.Pago ->
                if (d.diasRestantes <= 3) "Sua assinatura vence em ${d.diasRestantes} ${if (d.diasRestantes == 1) "dia" else "dias"}  ·  Renovar ›" to Cores.AMARELO else null to 0
            else -> null to 0
        }
        if (txt == null) { bannerTeste.visibility = View.GONE; return }
        bannerTeste.text = txt
        bannerTeste.contentDescription = txt.replace("›", "").replace("·", ".")
        bannerTeste.background = ui.fundo(Cores.alpha(cor, 0.12f), Cores.alpha(cor, 0.55f), 14f)
        bannerTeste.visibility = View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        if (bloqueada) return
        val l = space.neural.luna.licenca.Licenca
        if (!l.ativa && l.forcar == null) return
        atualizarLicenca(l.decidir(this))
        if (l.ativa && l.precisaRevalidar(this)) lifecycleScope.launch {
            when (val r = l.verificar(this@MainActivity)) {
                is space.neural.luna.licenca.Licenca.Resultado.Ok -> if (!isFinishing) atualizarLicenca(r.decisao)
                is space.neural.luna.licenca.Licenca.Resultado.Erro -> if (r.semConta) {
                    space.neural.luna.conta.Conta.sair(); space.neural.luna.conta.LoginActivity.abrir(this@MainActivity, entrar = true); finish()
                } else if (!isFinishing) atualizarLicenca(l.decidir(this@MainActivity))
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (bloqueada) return
        runCatching { rede.registerDefaultNetworkCallback(redeCb) }
        mudouRede()
        // v0.1.7: toda vez que o painel aparece, desce o que o outro aparelho lançou (e liga o tempo real).
        SyncManager.requestSync(this, agora = true)
        space.neural.luna.sync.RealtimeSync.ligar(this)
        atualizarBaixando()
    }

    // ---------------- v0.1.7: primeira descida e turno no outro aparelho ----------------

    private var baixando: space.neural.luna.dashboard.BaixandoDados? = null
    /** "Continuar sem esperar" (sem internet): some nesta abertura; o Planejamento continua travado. */
    private var pularBaixando = false

    private fun atualizarBaixando() {
        val b = baixando ?: return
        val uid = space.neural.luna.sync.Nuvem.uid(this)
        val precisa = !pularBaixando && !space.neural.luna.sync.PrimeiraCarga.pronto(this, uid)
        b.bind(precisa, online)
    }

    /** Roda uma ação de turno; se o turno mudou no outro aparelho, avisa (o estado de lá já veio para cá). */
    private fun turno(bloco: suspend () -> Unit) {
        lifecycleScope.launch {
            try { bloco() } catch (e: space.neural.luna.sync.TurnoNoOutroAparelho) { avisarOutroAparelho(e.message.orEmpty()) }
        }
    }

    fun avisarOutroAparelho(msg: String) {
        space.neural.luna.sync.RealtimeSync.sincronizarAgora()
        AlertDialog.Builder(this).setTitle("Mudou no outro aparelho").setMessage(msg).setPositiveButton("Entendi", null).show()
    }

    override fun onStop() {
        if (bloqueada) { super.onStop(); return }
        if (voz.ativa || voz.escutando) voz.parar(null) // saiu da tela: o microfone não fica aberto
        runCatching { rede.unregisterNetworkCallback(redeCb) }
        super.onStop()
    }

    override fun onDestroy() { if (bloqueada) { super.onDestroy(); return }; voz.destruir(); if (isFinishing) space.neural.luna.avatar.LunaVoz.parar(); super.onDestroy() }

    // ---------------- Luna por voz na tela inicial ----------------

    /** Um toque no card da Luna: começa a conversa por voz (ou para, se já estiver ouvindo/conversando). */
    private fun falarComLuna() {
        if (voz.ativa || voz.escutando) { voz.parar("Conversa encerrada. Toque para falar de novo."); space.neural.luna.avatar.LunaVoz.parar(); return }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permMic.launch(android.Manifest.permission.RECORD_AUDIO); return
        }
        ouvir()
    }

    private fun ouvir() {
        voz.motivoBloqueio()?.let { luna.ocioso(it); return }
        space.neural.luna.avatar.LunaVoz.parar()
        if (!voz.disponivel()) {
            val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Fale com a Luna")
            runCatching { ditadoExterno.launch(intent) }.onFailure { luna.ocioso("Este aparelho não tem reconhecimento de voz. Toque duas vezes para escrever.") }
            return
        }
        voz.iniciar()
    }

    /** Manda para a Luna (voz transcrita ou botão) e mostra/fala a resposta no próprio card. */
    private fun barraInferiorParceiros(root: LinearLayout) {
        root.addView(ui.botao("Parceiros e Ofertas", Ui.Estilo.CONTORNO) {
            startActivity(Intent(this, space.neural.luna.pages.ParceirosActivity::class.java))
        }, ui.mp())
    }

    private fun perguntarLuna(texto: String, rotulo: String? = null) {
        luna.pensando(rotulo ?: texto)
        lifecycleScope.launch {
            val r = conversa.enviar(texto, voz = rotulo == null, rotulo = rotulo)
            space.neural.luna.chat.LunaNavigationController.open(this@MainActivity, r.destino)
            mostrarRespostaComVoz(r)
        }
    }

    // ---------------- Nota fiscal pela tela inicial (v0.1.5) ----------------
    private fun escolherFotoNota(ancora: View) {
        val onb = getSharedPreferences("onboarding", MODE_PRIVATE)
        val modoCarro = onb.getString("dispositivo", "PHONE") == "CARRO"
        val m = PopupMenu(this, ancora)
        if (!modoCarro && receiptCapture.canTakePhoto()) m.menu.add(0, 1, 0, "Tirar foto da nota")
        m.menu.add(0, 2, 1, "Escolher da galeria")
        m.setOnMenuItemClickListener {
            if (it.itemId == 1) {
                receiptCapture.capture()
            } else receiptCapture.selectImage()
            true
        }
        m.show()
    }

    /** Mesmo fluxo da conversa: /receipt lê loja e total, a Luna pergunta o limite com botões, "Sim" confirma e ela fala quanto sobra. */
    private fun lerNota(uri: android.net.Uri) {
        if (voz.ativa) voz.parar(null)
        luna.pensando("📷 Lendo a nota fiscal…")
        lifecycleScope.launch {
            val jpeg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { space.neural.luna.chat.Foto.reduzir(this@MainActivity, uri) }.getOrNull() }
            val r = if (jpeg == null) space.neural.luna.chat.LunaConversa.Resposta("Não consegui abrir essa foto.") else conversa.lerNota(jpeg)
            mostrarRespostaComVoz(r)
        }
    }

    private fun temInternet(): Boolean {
        val caps = rede.getNetworkCapabilities(rede.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun mostrarRespostaComVoz(r: space.neural.luna.chat.LunaConversa.Resposta) {
        val owner = space.neural.luna.conta.Conta.uid()
        var shown = false
        val reveal = {
            if (!shown && !isDestroyed && space.neural.luna.conta.Conta.uid() == owner) {
                shown = true
                luna.resposta(r)
            }
        }
        luna.pensando("Preparando voz…")
        if (voz.ativa) voz.falar(r.texto, aoIniciar = reveal, aoTerminar = reveal)
        else space.neural.luna.avatar.LunaVoz.falarSincronizado(this, r.texto, reveal, reveal)
    }

    private fun mudouRede() {
        val o = temInternet()
        runOnUiThread {
            val mudou = o != online
            online = o
            desenhar()
            atualizarBaixando()
            if (mudou && o) SyncManager.requestSync(this, agora = true)
        }
    }

    // ---------------- Layout responsivo ----------------

    private fun montarLayout(animar: Boolean) {
        val cfg = resources.configuration
        val w = cfg.screenWidthDp; val h = cfg.screenHeightDp
        var cls = when { w < 600 -> 1; w < 840 -> 2; else -> 3 }
        if (cfg.fontScale >= 1.5f) cls = (cls - 1).coerceAtLeast(1) // fonte grande: menos colunas
        // Telefone deitado (altura baixa): 2 colunas, turno + resumo fixos à esquerda.
        val telefonePaisagem = cls >= 2 && w > h && h < 480
        if (telefonePaisagem) cls = 2
        val tablet = cls >= 2 && !telefonePaisagem
        val margem = ui.dp(if (tablet) 12 else 10)
        val gap = ui.dp(if (tablet) 10 else 8)

        periodo = runCatching { Periodo.valueOf(prefs.getString("periodo", "HOJE")!!) }.getOrDefault(Periodo.HOJE)
        cab = Cabecalho(ui, compacto = telefonePaisagem || cls == 3, a = this, estreito = cls == 1 && w < 480)
        val compacto = w > h && cfg.fontScale < 1.5f
        turnoCard = TurnoCard(ui, largo = false, a = this, orbDp = when { telefonePaisagem -> 112; cls == 3 -> 150; else -> 160 }, mostrarRegistroRapido = false, compacto = compacto)
        metas = MetasCard(ui, this, compacto = compacto)
        kpis = KpiStrip(ui, colunas = if (cls == 1 && w < 600) 2 else 4).apply { aoTocarLimites = { abrirLimites() } }
        seletor = SeletorPeriodo(ui) { escolherPeriodo(it) }
        grafico = GraficoCard(ui, alturaDp = when { compacto -> 100; cls == 3 -> 140; cls == 2 -> 160; else -> 180 }, compacto = compacto)
        roscas = RoscasCard(ui)
        lancamentos = LancamentosCard(ui, this, compacto = compacto)
        luna = LunaCard(ui, { falarComLuna() }, { conversar() }, { b -> perguntarLuna(b.comando, b.rotulo) }, { escolherFotoNota(it) })
        relatorios = RelatoriosCard(ui) { startActivity(Intent(this, space.neural.luna.dashboard.RelatorioActivity::class.java).putExtra("period", periodo.name)) }
        calendario = CalendarioCard(ui) { abrirDia(it.toString()) }
        noticias = NoticiasCard(ui, lado = cls == 2 && !telefonePaisagem || (cls == 1 && w >= 480), a = this)
        dica = DicaCard(ui, this)
        val raiz = ui.vbox()
        raiz.addView(cab.view, ui.mp())
        bannerTeste = montarBanner()
        raiz.addView(bannerTeste, ui.mp(ui.dp(8)))
        raiz.addView(luna.view, ui.mp(ui.dp(8)))

        fun coluna(id: Int, vararg cards: View): ScrollView {
            val col = ui.vbox()
            cards.forEachIndexed { i, c -> col.addView(c, ui.mp(if (i == 0) 0 else gap)) }
            col.setPadding(0, ui.dp(4), 0, gap)
            col.clipToPadding = false; col.clipChildren = false
            return ScrollView(this).apply { this.id = id; addView(col); isVerticalScrollBarEnabled = true; clipToPadding = false }
        }
        fun linha(vararg cards: View) = ui.hbox().apply {
            gravity = android.view.Gravity.TOP
            cards.forEachIndexed { i, c -> addView(c, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = if (i == 0) 0 else gap }) }
        }

        val registro = ui.card(acento = Cores.CIANO).apply {
            addView(ui.tituloIcone(R.drawable.ic_wallet, "Registro financeiro", Cores.CIANO), ui.mp())
            addView(ui.comIcone(ui.botao("Ganho", Ui.Estilo.CHEIO_ESMERALDA) { lancarGanho(null) }, R.drawable.ic_add), ui.mp(ui.dp(8)))
            addView(ui.comIcone(ui.botao("Gasto", Ui.Estilo.CONTORNO) { lancarGasto(null) }.apply { background = space.neural.luna.dashboard.RotaDesign.gradient(ui, Cores.CORAL, Cores.MAGENTA) }, R.drawable.ic_wallet), ui.mp(ui.dp(6)))
            addView(ui.comIcone(ui.botao("Recarga", Ui.Estilo.CONTORNO) { lancarGasto(space.neural.luna.data.local.CategoriaGasto.ENERGIA) }.apply { background = space.neural.luna.dashboard.RotaDesign.gradient(ui, Cores.VIOLETA, Cores.CIANO) }, R.drawable.ic_bolt), ui.mp(ui.dp(6)))
        }
        val ordem: List<View>
        if (cls == 1) {
            ordem = listOf(kpis.view, turnoCard.view, registro, calendario.view, relatorios.view, seletor.view, grafico.view, roscas.view, noticias.view, metas.view, lancamentos.view, dica.view)
            raiz.addView(coluna(R.id.rolagem_principal, *ordem.toTypedArray()), LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = gap })
        } else if (w >= 1000 && cfg.fontScale < 1.5f) {
            val conteudo = ui.vbox()
            conteudo.addView(kpis.view, ui.mp())
            val relatorioBox = ui.vbox().apply { addView(relatorios.view, ui.mp()); addView(seletor.view, ui.mp(gap)) }
            conteudo.addView(linha(turnoCard.view, metas.view, registro), ui.mp(gap))
            conteudo.addView(linha(grafico.view, relatorioBox, lancamentos.view), ui.mp(gap))
            conteudo.addView(linha(roscas.view, calendario.view, noticias.view), ui.mp(gap))
            conteudo.addView(dica.view, ui.mp(gap))
            conteudo.setPadding(0, 0, 0, gap)
            ordem = listOf(kpis.view, turnoCard.view, relatorios.view, registro, grafico.view, metas.view, lancamentos.view, roscas.view, calendario.view, noticias.view, dica.view)
            raiz.addView(ScrollView(this).apply { id = R.id.rolagem_principal; addView(conteudo); isVerticalScrollBarEnabled = true }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = gap })
        } else {
            // Uma superfície de rolagem: painéis alinhados como a referência, sem colunas presas com vazios.
            val conteudo = ui.vbox()
            conteudo.addView(kpis.view, ui.mp())
            val ladoDireito = ui.vbox()
            if (w >= 1000 && cfg.fontScale < 1.5f) {
                ladoDireito.addView(linha(calendario.view, relatorios.view, noticias.view), ui.mp())
                ladoDireito.addView(seletor.view, ui.mp(gap))
                val graficos = ui.hbox().apply {
                    gravity = android.view.Gravity.TOP
                    addView(grafico.view, LinearLayout.LayoutParams(0, -2, 1.3f))
                    addView(roscas.view, LinearLayout.LayoutParams(0, -2, 1.1f).apply { leftMargin = gap })
                    addView(registro, LinearLayout.LayoutParams(0, -2, 0.85f).apply { leftMargin = gap })
                }
                ladoDireito.addView(graficos, ui.mp(gap))
            } else {
                ladoDireito.addView(linha(calendario.view, relatorios.view), ui.mp())
                ladoDireito.addView(seletor.view, ui.mp(gap))
                ladoDireito.addView(grafico.view, ui.mp(gap))
                ladoDireito.addView(linha(roscas.view, registro), ui.mp(gap))
                ladoDireito.addView(noticias.view, ui.mp(gap))
            }
            val corpo = ui.hbox().apply {
                gravity = android.view.Gravity.TOP
                addView(turnoCard.view, LinearLayout.LayoutParams(0, -2, if (w >= 1000) 0.23f else 0.30f))
                addView(ladoDireito, LinearLayout.LayoutParams(0, -2, if (w >= 1000) 0.77f else 0.70f).apply { leftMargin = gap })
            }
            conteudo.addView(corpo, ui.mp(gap))
            if (w >= 1000) conteudo.addView(linha(metas.view, lancamentos.view, dica.view), ui.mp(gap))
            else {
                conteudo.addView(linha(metas.view, lancamentos.view), ui.mp(gap))
                conteudo.addView(dica.view, ui.mp(gap))
            }
            conteudo.setPadding(0, 0, 0, gap)
            ordem = listOf(kpis.view, turnoCard.view, calendario.view, relatorios.view, noticias.view, seletor.view, grafico.view, roscas.view, registro, metas.view, lancamentos.view, dica.view)
            raiz.addView(ScrollView(this).apply { id = R.id.rolagem_principal; addView(conteudo); isVerticalScrollBarEnabled = true }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = gap })
        }
        // Galáxia com aurora animada atrás de tudo.
        val tela = android.widget.FrameLayout(this)
        tela.addView(GalaxiaView(this), android.widget.FrameLayout.LayoutParams(-1, -1))
        tela.addView(raiz, android.widget.FrameLayout.LayoutParams(-1, -1))
        baixando = space.neural.luna.dashboard.BaixandoDados(ui, "Continuar sem esperar") { pularBaixando = true; atualizarBaixando() }
            .also { tela.addView(it.view, android.widget.FrameLayout.LayoutParams(-1, -1)) }
        val dock = ui.hbox().apply {
            tag = "primary_navigation"
            gravity = android.view.Gravity.CENTER
            setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6))
            background = ui.fundo(Cores.alpha(Cores.OBSIDIAN, 0.96f), Cores.alpha(Cores.VIOLETA, 0.65f), 22f)
        }
        fun destino(rotulo: String, icone: Int, selecionado: Boolean = false, acao: () -> Unit) {
            val item = ui.vbox().apply {
                gravity = android.view.Gravity.CENTER
                minimumHeight = ui.dp(52)
                isClickable = true; isFocusable = true; contentDescription = rotulo
                if (selecionado) background = ui.fundo(Cores.alpha(Cores.VIOLETA, 0.25f), Cores.VIOLETA, 16f)
                setOnClickListener { acao() }
                addView(ui.icone(icone, if (selecionado) Cores.CIANO else Cores.BRANCO_70, 22))
                addView(ui.ajustavel(rotulo, 12f, 9f, if (selecionado) Cores.CIANO else Cores.BRANCO_70).apply { gravity = android.view.Gravity.CENTER }, ui.mp(ui.dp(3)))
            }
            dock.addView(item, ui.peso(1f, ui.dp(2)))
        }
        destino("Início", R.drawable.ic_bolt, true) { findViewById<ScrollView>(R.id.rolagem_principal)?.smoothScrollTo(0, 0) }
        destino("Mapa e Rádio", R.drawable.ic_map) { startActivity(Intent(this, space.neural.luna.pages.MapRadioActivity::class.java)) }
        destino("Ofertas", R.drawable.ic_partners) { startActivity(Intent(this, space.neural.luna.pages.ParceirosActivity::class.java)) }
        raiz.addView(dock, ui.mp(ui.dp(6)))

        setContentView(tela)
        atualizarBaixando()
        ViewCompat.setOnApplyWindowInsetsListener(raiz) { v, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left + margem, b.top + margem / 2, b.right + margem, b.bottom); ins
        }
        if (animar) { Anima.entrada(cab.view, 0); if (!telefonePaisagem) Anima.entrada(luna.view, 1); ordem.forEachIndexed { i, v -> if (v !== luna.view || telefonePaisagem) Anima.entrada(v, i + 2) } }
    }

    private fun desenhar() {
        val p = painel ?: run {
            noticias.bind(externo, online, carregandoExterno); dica.bind(java.time.LocalDate.now()); return
        }
        val agora = space.neural.luna.sync.Relogio.agora()
        cab.bind(p, online, BuildConfig.FIREBASE_CONFIGURED)
        turnoCard.bind(p.situacao)
        turnoCard.bindResumo(p, agora, sugerirParar(p, agora))
        metas.bind(p, alertaMotivacional(p))
        seletor.bind(periodo)
        val e = p.estatisticas(periodo, agora)
        kpis.bind(e); kpis.bindLimites(p.envelopes()); grafico.bind(e); roscas.bind(e); lancamentos.bind(e)
        relatorios.bind(p, periodo); calendario.bind(p)
        noticias.bind(externo, online, carregandoExterno)
        dica.bind(p.hoje)
    }

    private fun sugerirParar(p: Painel, agora: Long): Boolean {
        if (agora < prefs.getLong("parar_agora_nao_ate", 0)) return false
        val trab = p.trabalhoDia(agora)
        val ph = Calculos.porHoraCentavos(p.resumo.liquidoCentavos, trab)
        return Calculos.sugerirParar(p.turno, trab, ph, prefs.getLong("limite_por_hora_centavos", LIMITE_POR_HORA_PADRAO))
    }

    /** Alerta motivacional: aparece uma vez por meta por limiar (80% e 100%) e fica até ser dispensado. */
    private fun alertaMotivacional(p: Painel): Triple<String, String, Int>? {
        var mostrar: Triple<String, String, Int>? = null
        for (m in p.metas) {
            val lim = Calculos.limiar(m) ?: continue
            val chave = "alerta_${m.tipo}_${m.inicio}_$lim"
            val nome = when (m.tipo) { TipoMeta.SEMANA -> "da semana"; TipoMeta.MES -> "do mês"; else -> "do dia" }
            val texto = if (lim >= 100) "✓ Meta $nome batida! 🎉 +${Fmt.reaisCurto(m.acimaCentavos)} acima."
            else "🔥 Falta pouco! Mais ${Fmt.reaisCurto(m.faltaCentavos)} e você bate a meta $nome."
            when (prefs.getInt(chave, 0)) {
                0 -> {
                    prefs.edit().putInt(chave, 1).apply {
                        if (lim >= 100) putInt("alerta_${m.tipo}_${m.inicio}_80", 2)
                    }.apply()
                    if (prefs.getBoolean("voz_alertas", true)) ler(texto)
                    if (mostrar == null) mostrar = Triple(chave, texto, lim)
                }
                1 -> if (mostrar == null) mostrar = Triple(chave, texto, lim)
            }
        }
        return mostrar
    }

    // ---------------- Ações ----------------

    private fun comTurno(f: suspend (space.neural.luna.data.local.Turno) -> Unit) {
        val t = painel?.turno ?: return
        turno { f(t) }
    }

    override fun iniciar() { painel?.let { p -> turno { acoes.iniciar(p.situacao) } } }
    override fun pausar() = comTurno { acoes.pausar(it) }
    override fun cafe() = comTurno { acoes.cafe(it) }
    /** Retomar pausa/café, retomar o turno 4 já fechado ou reabrir o dia concluído. */
    override fun retomar() {
        val t = painel?.situacao?.turno ?: return
        turno { acoes.retomar(t) }
    }
    /** Fecha o turno sem bloquear: o próximo já fica pronto; a correção fica como opção no card. */
    override fun fecharTurno() = comTurno {
        acoes.fechar(it)
        val msg = if (it.numero < MaquinaTurno.TOTAL) "Turno ${it.numero} fechado. Turno ${it.numero + 1} pronto para iniciar."
        else "Turno 4 fechado. Você pode retomar ou concluir o dia."
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
    override fun concluir() {
        val p = painel ?: return
        dlg.confirmar("Concluir dia", "Encerra o dia de trabalho e guarda os turnos no histórico.", "Concluir dia") {
            turno {
                acoes.concluirDia(p.turnosTodos, p.situacao)
                Toast.makeText(this@MainActivity, "Dia concluído e salvo no histórico.", Toast.LENGTH_SHORT).show()
            }
        }
    }
    override fun corrigir() {
        val s = painel?.situacao ?: return
        (s.anterior ?: s.turno)?.let { startActivity(LancamentosActivity.correcao(this, it.id)) }
    }
    override fun zerar() {
        val p = painel ?: return
        val s = p.situacao
        val hoje = java.time.LocalDate.now().toString()
        val dia = MaquinaTurno.doDia(p.turnosTodos, hoje)
        val n = s.numero
        val itens = MaquinaTurno.opcoesZerar(s, dia).map { op ->
            when (op) {
                MaquinaTurno.OpcaoZerar.SO_ESTE -> Triple("Zerar só este turno",
                    "O turno $n continua, com o contador em 00:00:00. Os ganhos lançados continuam.") {
                    s.turno?.let { t -> turno { acoes.zerar(t) } }; Unit
                }
                MaquinaTurno.OpcaoZerar.VOLTAR -> Triple("Voltar para o turno anterior",
                    "Desfaz o “Fechar turno”: apaga o turno $n e reabre o turno ${n - 1} com o tempo que ele já tinha.") {
                    turno {
                        val r = acoes.voltar(s, p.turnosTodos)
                        val msg = when {
                            r == null -> "Não deu para voltar: não achei o turno anterior."
                            r.estado == space.neural.luna.data.local.EstadoTurno.PAUSADO -> "Turno ${r.numero} reaberto (pausado). Toque em Retomar para continuar."
                            else -> "Turno ${r.numero} reaberto e contando."
                        }
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    }; Unit
                }
                MaquinaTurno.OpcaoZerar.DIA -> Triple("Zerar o dia",
                    "Apaga todos os turnos de hoje e volta para o Turno 1/${MaquinaTurno.TOTAL}. Ganhos e gastos ficam.") {
                    val qtd = dia.size
                    dlg.confirmar("Zerar o dia?",
                        "Vou apagar ${if (qtd == 1) "o turno de hoje" else "os $qtd turnos de hoje"} e o dia volta para o Turno 1/${MaquinaTurno.TOTAL}, sem iniciar.\n\n" +
                            "Os ganhos e gastos de hoje NÃO são apagados: continuam no resumo do dia.", "Sim, zerar o dia") {
                        turno {
                            acoes.zerarDia(p.turnosTodos, s)
                            Toast.makeText(this@MainActivity, "Dia zerado. Turno 1 pronto para iniciar.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        if (itens.isEmpty()) { Toast.makeText(this, "Nada para zerar: o turno ainda não começou.", Toast.LENGTH_SHORT).show(); return }
        dlg.opcoes("Zerar tempo — turno $n", itens)
    }
    /** Toque no KPI "Limites": todos os limites do mês (barras, lançar gasto em cada um, Planejamento). */
    private fun abrirLimites() {
        val p = painel ?: return
        val card = LimitesCard(ui, this).apply { bind(p) }
        val d = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(card.view); setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)) })
            .setNegativeButton("Fechar", null).create()
        limitesDialogo = d
        d.setOnDismissListener { if (limitesDialogo === d) limitesDialogo = null }
        d.show()
    }
    private var limitesDialogo: androidx.appcompat.app.AlertDialog? = null

    override fun diaEsquecido() = dlg.escolherDia { startActivity(LancamentosActivity.dia(this, it.toString())) }
    override fun detalheDia() = startActivity(LancamentosActivity.dia(this, java.time.LocalDate.now().toString()))
    override fun abrirDia(data: String) = startActivity(LancamentosActivity.dia(this, data))
    private fun escolherPeriodo(p: Periodo) {
        if (p == periodo) return
        periodo = p; prefs.edit().putString("periodo", p.name).apply(); desenhar()
    }
    override fun detalheApp(app: String) = startActivity(LancamentosActivity.dia(this, java.time.LocalDate.now().toString(), app))
    override fun lancarGanho(app: String?) = dlg.ganho(java.time.LocalDate.now().toString(), painel?.turno?.id, app)
    override fun lancarGasto(categoria: String?) = dlg.gasto(java.time.LocalDate.now().toString(), painel?.turno?.id, categoria)
    override fun gastoEnvelope(e: space.neural.luna.dashboard.Plano.Envelope) { limitesDialogo?.dismiss(); dlg.gastoEnvelope(e.limite, e.restanteCentavos) }
    override fun planejamento() { limitesDialogo?.dismiss(); startActivity(Intent(this, space.neural.luna.config.PlanejamentoActivity::class.java)) }
    override fun dispensarAlerta(chave: String) { prefs.edit().putInt(chave, 2).apply(); desenhar() }
    override fun pararAgoraNao() { prefs.edit().putLong("parar_agora_nao_ate", System.currentTimeMillis() + 60 * 60_000L).apply(); desenhar() }

    override fun atualizarNoticias() {
        if (carregandoExterno) return
        carregandoExterno = true; desenhar()
        lifecycleScope.launch {
            val d = Externo.atualizar(this@MainActivity)
            carregandoExterno = false
            if (d != null) externo = d
            else if (online) Toast.makeText(this@MainActivity, "Não foi possível atualizar agora.", Toast.LENGTH_SHORT).show()
            desenhar()
        }
    }

    override fun abrirNoticia(n: Externo.Noticia) {
        if (!online) { Toast.makeText(this, "Disponível quando houver internet.", Toast.LENGTH_SHORT).show(); return }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(n.link))) }
            .onFailure { Toast.makeText(this, "Nenhum navegador encontrado.", Toast.LENGTH_SHORT).show() }
    }

    override fun previsaoHoras() {
        val c = externo?.clima ?: return
        val txt = c.horas.joinToString("\n") { "${it.hora}   ${Math.round(it.tempC)}°   chuva ${it.chuva}%" }
        AlertDialog.Builder(this).setTitle("Previsão por hora — Valadares").setMessage(txt).setPositiveButton("Fechar", null).show()
    }

    /** Fala da Luna (respostas e alertas): voz Kore do servidor com a boca sincronizada; reserva: voz do Android. */
    override fun ler(texto: String) = space.neural.luna.avatar.LunaVoz.falar(this, texto)

    override fun compartilhar(texto: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, texto), "Compartilhar"))
    }

    override fun conversar() = startActivity(Intent(this, ChatActivity::class.java))

    override fun sincronizar() {
        SyncManager.requestSync(this, agora = true)
        space.neural.luna.sync.RealtimeSync.sincronizarAgora()
        Toast.makeText(this, if (online) "Sincronizando…" else "Sem internet: sincroniza quando a conexão voltar.", Toast.LENGTH_SHORT).show()
    }

    /** v0.1.5: a engrenagem abre a tela de Configurações (substitui o menu de três pontinhos). */
    override fun menu(ancora: View) = startActivity(Intent(this, space.neural.luna.config.ConfiguracoesActivity::class.java))

    companion object {
        /** Sugestão de parar abaixo de R$ 20/h de líquido (depois de 2 h de trabalho). */
        const val LIMITE_POR_HORA_PADRAO = 2000L
    }
}
