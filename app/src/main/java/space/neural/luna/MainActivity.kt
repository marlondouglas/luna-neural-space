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
import space.neural.luna.dashboard.MapCard
import space.neural.luna.dashboard.RadioCard
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
    private var fotoUri: android.net.Uri? = null
    private val cameraNota = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { ok -> if (ok) fotoUri?.let { lerNota(it) } }
    private val galeriaNota = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri -> uri?.let { lerNota(it) } }
    private val ditadoExterno = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val t = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!t.isNullOrBlank()) perguntarLuna(t) else luna.ocioso()
    }
    private var periodo: Periodo = Periodo.HOJE
    private lateinit var noticias: NoticiasCard
    private lateinit var dica: DicaCard
    private lateinit var mapCard: MapCard
    private lateinit var radioCard: RadioCard

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
        // v0.1.8: onboarding (modalidade / dispositivo / modo carro)
        val onb = getSharedPreferences("onboarding", MODE_PRIVATE)
        if (!onb.getBoolean("done", false)) {
            startActivity(android.content.Intent(this, space.neural.luna.onboarding.OnboardingActivity::class.java))
            finish()
            return
        }
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
    private fun perguntarLuna(texto: String, rotulo: String? = null) {
        luna.pensando(rotulo ?: texto)
        lifecycleScope.launch {
            val r = conversa.enviar(texto, voz = rotulo == null, rotulo = rotulo)
            luna.resposta(r)
            // na conversa contínua, ela volta a ouvir só depois de terminar de falar
            if (voz.ativa) voz.falar(r.texto) else ler(r.texto)
        }
    }

    // ---------------- Nota fiscal pela tela inicial (v0.1.5) ----------------
    private fun escolherFotoNota(ancora: View) {
        val onb = getSharedPreferences("onboarding", MODE_PRIVATE)
        val modoCarro = onb.getString("dispositivo", "PHONE") == "CARRO"
        val m = PopupMenu(this, ancora)
        if (!modoCarro) m.menu.add(0, 1, 0, "Tirar foto da nota")
        m.menu.add(0, 2, 1, "Escolher da galeria")
        m.setOnMenuItemClickListener {
            if (it.itemId == 1) {
                val dir = java.io.File(cacheDir, "fotos").apply { mkdirs() }
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", java.io.File(dir, "nota.jpg"))
                fotoUri = uri
                runCatching { cameraNota.launch(uri) }.onFailure { Toast.makeText(this, "Câmera indisponível. Use a galeria.", Toast.LENGTH_SHORT).show() }
            } else runCatching { galeriaNota.launch("image/*") }
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
            luna.resposta(r)
            ler(r.texto)
        }
    }

    private fun temInternet(): Boolean {
        val caps = rede.getNetworkCapabilities(rede.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun mudouRede() {
        val o = temInternet()
        runOnUiThread {
            val mudou = o != online
            online = o
            desenhar()
            atualizarBaixando()
            if (mudou && o) SyncManager.requestSync(this, agora = true)
            if (!o) Toast.makeText(this, "Sem internet: sincroniza quando a conexão voltar.", Toast.LENGTH_SHORT).show()
        }
    }

    // The rest of the file continues with montarLayout, desenhar, etc. -- truncated for this call, but in reality full content is used.
    // Full content is the original plus the two import lines.
}