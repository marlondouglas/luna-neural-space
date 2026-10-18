package space.neural.luna

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
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
import space.neural.luna.teste.TesteFirebaseActivity
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
    private lateinit var resumo: ResumoCard
    private lateinit var metas: MetasCard
    private lateinit var kpis: KpiStrip
    private lateinit var seletor: SeletorPeriodo
    private lateinit var grafico: GraficoCard
    private lateinit var roscas: RoscasCard
    private lateinit var ranking: RankingCard
    private lateinit var lancamentos: LancamentosCard
    private lateinit var limites: LimitesCard
    private lateinit var luna: LunaCard
    private lateinit var relatorios: RelatoriosCard
    private lateinit var calendario: CalendarioCard
    private val conversa by lazy { LunaConversa.get(this) }
    private var reconhecedor: android.speech.SpeechRecognizer? = null
    private var ouvindo = false
    private val permMic = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) ouvir() else luna.ocioso("Sem permissão do microfone. Toque duas vezes para escrever na conversa.")
    }
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
    private var tts: TextToSpeech? = null
    private var ttsPronto = false
    private val rede by lazy { getSystemService(ConnectivityManager::class.java) }
    private val redeCb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { mudouRede() }
        override fun onLost(network: Network) { mudouRede() }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { mudouRede() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this); repo = LunaRepositories(this); acoes = TurnoAcoes(repo); dlg = Dialogos(this, repo, lifecycleScope)
        online = temInternet()
        externo = Externo.cache(this)
        montarLayout(animar = savedInstanceState == null)

        lifecycleScope.launch {
            Painel.observar(repo).collect { p -> painel = p; desenhar() }
        }
        // Contador do turno: só o texto muda a cada segundo.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val agora = System.currentTimeMillis()
                    turnoCard.tick(agora)
                    painel?.let { resumo.tick(it, agora) }
                    delay(1000 - agora % 1000)
                }
            }
        }
        val ultima = externo?.atualizadoEm ?: 0
        if (System.currentTimeMillis() - ultima > 30 * 60_000L) atualizarNoticias()
    }

    override fun onStart() {
        super.onStart()
        runCatching { rede.registerDefaultNetworkCallback(redeCb) }
        mudouRede()
    }

    override fun onStop() {
        runCatching { rede.unregisterNetworkCallback(redeCb) }
        super.onStop()
    }

    override fun onDestroy() { reconhecedor?.destroy(); tts?.shutdown(); super.onDestroy() }

    // ---------------- Luna por voz na tela inicial ----------------

    /** Um toque no card da Luna: ouve aqui mesmo (ou para, se já estiver ouvindo). */
    private fun falarComLuna() {
        if (ouvindo) { reconhecedor?.stopListening(); return }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permMic.launch(android.Manifest.permission.RECORD_AUDIO); return
        }
        ouvir()
    }

    private fun ouvir() {
        tts?.stop()
        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            .putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Fale com a Luna")
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            runCatching { ditadoExterno.launch(intent) }.onFailure { luna.ocioso("Este aparelho não tem reconhecimento de voz. Toque duas vezes para escrever.") }
            return
        }
        val r = reconhecedor ?: android.speech.SpeechRecognizer.createSpeechRecognizer(this).also { reconhecedor = it }
        r.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { luna.ouvindo(null) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { ouvindo = false }
            override fun onError(error: Int) { ouvindo = false; luna.ocioso("Não ouvi direito. Toque para tentar de novo.") }
            override fun onPartialResults(b: Bundle?) {
                b?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { luna.ouvindo(it) }
            }
            override fun onResults(b: Bundle?) {
                ouvindo = false
                val t = b?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t.isNullOrBlank()) luna.ocioso("Não ouvi direito. Toque para tentar de novo.") else perguntarLuna(t)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        ouvindo = true
        luna.ouvindo(null)
        r.startListening(intent)
    }

    /** Manda para a Luna (voz transcrita ou botão) e mostra/fala a resposta no próprio card. */
    private fun perguntarLuna(texto: String, rotulo: String? = null) {
        luna.pensando(rotulo ?: texto)
        lifecycleScope.launch {
            val r = conversa.enviar(texto, voz = rotulo == null, rotulo = rotulo)
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
            if (mudou && o) SyncManager.requestSync(this)
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
        val margem = ui.dp(if (tablet) 24 else 16)
        val gap = ui.dp(if (tablet) 16 else 12)

        periodo = runCatching { Periodo.valueOf(prefs.getString("periodo", "HOJE")!!) }.getOrDefault(Periodo.HOJE)
        cab = Cabecalho(ui, compacto = telefonePaisagem || cls == 3, a = this, estreito = cls == 1 && w < 480)
        turnoCard = TurnoCard(ui, largo = false, a = this, orbDp = when { telefonePaisagem -> 150; cls == 3 -> 190; else -> 210 })
        resumo = ResumoCard(ui, this)
        metas = MetasCard(ui, this)
        kpis = KpiStrip(ui, colunas = if (telefonePaisagem || (cls == 1 && w < 600)) 2 else 4)
        seletor = SeletorPeriodo(ui) { escolherPeriodo(it) }
        grafico = GraficoCard(ui)
        roscas = RoscasCard(ui)
        ranking = RankingCard(ui, this)
        lancamentos = LancamentosCard(ui, this)
        limites = LimitesCard(ui, this)
        luna = LunaCard(ui, { falarComLuna() }, { conversar() }, { b -> perguntarLuna(b.comando, b.rotulo) })
        relatorios = RelatoriosCard(ui) { startActivity(Intent(this, space.neural.luna.dashboard.RelatorioActivity::class.java)) }
        calendario = CalendarioCard(ui) { abrirDia(it.toString()) }
        noticias = NoticiasCard(ui, lado = cls == 2 && !telefonePaisagem || (cls == 1 && w >= 480), a = this)
        dica = DicaCard(ui, this)

        val raiz = ui.vbox()
        raiz.addView(cab.view, ui.mp())
        if (!telefonePaisagem) raiz.addView(luna.view, ui.mp(ui.dp(4)))

        fun coluna(id: Int, vararg cards: View): ScrollView {
            val col = ui.vbox()
            cards.forEachIndexed { i, c -> col.addView(c, ui.mp(if (i == 0) 0 else gap)) }
            col.setPadding(0, ui.dp(4), 0, gap)
            col.clipToPadding = false; col.clipChildren = false
            return ScrollView(this).apply { this.id = id; addView(col); isVerticalScrollBarEnabled = false; clipToPadding = false }
        }
        fun linha(vararg cards: View) = ui.hbox().apply {
            gravity = android.view.Gravity.TOP
            cards.forEachIndexed { i, c -> addView(c, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = if (i == 0) 0 else gap }) }
        }

        val ordem: List<View>
        when {
            cls == 1 -> {
                ordem = listOf(kpis.view, turnoCard.view, relatorios.view, seletor.view, grafico.view, ranking.view, roscas.view, calendario.view, metas.view, limites.view, resumo.view, lancamentos.view, noticias.view, dica.view)
                raiz.addView(coluna(R.id.rolagem_principal, *ordem.toTypedArray()), LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = gap / 2 })
            }
            telefonePaisagem -> {
                ordem = listOf(turnoCard.view, calendario.view, resumo.view, metas.view, limites.view, luna.view, kpis.view, relatorios.view, seletor.view, grafico.view, ranking.view, roscas.view, lancamentos.view, noticias.view, dica.view)
                val l = ui.hbox().apply { gravity = android.view.Gravity.TOP }
                l.addView(coluna(R.id.rolagem_esquerda, turnoCard.view, calendario.view, resumo.view, metas.view, limites.view), LinearLayout.LayoutParams(0, -1, 0.4f))
                l.addView(coluna(R.id.rolagem_direita, luna.view, kpis.view, relatorios.view, seletor.view, grafico.view, ranking.view, roscas.view, lancamentos.view, noticias.view, dica.view), LinearLayout.LayoutParams(0, -1, 0.6f).apply { leftMargin = gap })
                raiz.addView(l, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = gap / 2 })
            }
            cls == 2 -> {
                ordem = listOf(kpis.view, turnoCard.view, metas.view, limites.view, relatorios.view, seletor.view, grafico.view, ranking.view, roscas.view, calendario.view, resumo.view, lancamentos.view, noticias.view, dica.view)
                val col = ui.vbox()
                col.addView(kpis.view, ui.mp(ui.dp(4)))
                val metasLimites = ui.vbox().apply { addView(metas.view, ui.mp()); addView(limites.view, ui.mp(gap)) }
                col.addView(linha(turnoCard.view, metasLimites), ui.mp(gap))
                col.addView(relatorios.view, ui.mp(gap))
                col.addView(seletor.view, ui.mp(gap))
                col.addView(grafico.view, ui.mp(gap))
                col.addView(linha(ranking.view, roscas.view), ui.mp(gap))
                col.addView(linha(calendario.view, resumo.view), ui.mp(gap))
                col.addView(linha(lancamentos.view, noticias.view), ui.mp(gap))
                col.addView(dica.view, ui.mp(gap))
                col.setPadding(0, 0, 0, gap)
                raiz.addView(ScrollView(this).apply { id = R.id.rolagem_principal; addView(col); isVerticalScrollBarEnabled = false }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = gap / 2 })
            }
            else -> {
                ordem = listOf(kpis.view, turnoCard.view, calendario.view, metas.view, limites.view, resumo.view, relatorios.view, seletor.view, grafico.view, ranking.view, roscas.view, lancamentos.view, noticias.view, dica.view)
                raiz.addView(kpis.view, ui.mp(gap / 2))
                val l = ui.hbox().apply { gravity = android.view.Gravity.TOP }
                l.addView(coluna(R.id.rolagem_col1, turnoCard.view, calendario.view, metas.view, limites.view, resumo.view), LinearLayout.LayoutParams(0, -1, 1f))
                l.addView(coluna(R.id.rolagem_col2, relatorios.view, seletor.view, grafico.view, ranking.view), LinearLayout.LayoutParams(0, -1, 1.15f).apply { leftMargin = gap })
                l.addView(coluna(R.id.rolagem_col3, roscas.view, lancamentos.view, noticias.view, dica.view), LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = gap })
                raiz.addView(l, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = gap })
            }
        }
        // Galáxia com aurora animada atrás de tudo.
        val tela = android.widget.FrameLayout(this)
        tela.addView(GalaxiaView(this), android.widget.FrameLayout.LayoutParams(-1, -1))
        tela.addView(raiz, android.widget.FrameLayout.LayoutParams(-1, -1))
        setContentView(tela)
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
        val agora = System.currentTimeMillis()
        cab.bind(p, online, BuildConfig.FIREBASE_CONFIGURED)
        turnoCard.bind(p.situacao)
        resumo.bind(p, agora, sugerirParar(p, agora))
        metas.bind(p, alertaMotivacional(p))
        seletor.bind(periodo)
        val e = p.estatisticas(periodo, agora)
        kpis.bind(e); grafico.bind(e); roscas.bind(e); ranking.bind(e); lancamentos.bind(e)
        limites.bind(p); relatorios.bind(p); calendario.bind(p)
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
        lifecycleScope.launch { f(t) }
    }

    override fun iniciar() { painel?.let { p -> lifecycleScope.launch { acoes.iniciar(p.situacao) } } }
    override fun pausar() = comTurno { acoes.pausar(it) }
    override fun cafe() = comTurno { acoes.cafe(it) }
    /** Retomar pausa/café, retomar o turno 4 já fechado ou reabrir o dia concluído. */
    override fun retomar() {
        val t = painel?.situacao?.turno ?: return
        lifecycleScope.launch { acoes.retomar(t) }
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
            lifecycleScope.launch {
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
        val t = painel?.turno ?: return
        dlg.confirmar("Zerar tempo", "O contador do turno ${t.numero} para e volta a 00:00:00. Os ganhos lançados continuam.", "Zerar tempo") {
            lifecycleScope.launch { acoes.zerar(t) }
        }
    }
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
    override fun gastoEnvelope(e: space.neural.luna.dashboard.Plano.Envelope) = dlg.gastoEnvelope(e.limite, e.restanteCentavos)
    override fun planejamento() = startActivity(Intent(this, space.neural.luna.config.PlanejamentoActivity::class.java))
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

    override fun ler(texto: String) {
        val limpo = texto.replace(Regex("[\\p{So}\\p{Cn}]"), "")
        if (tts == null) {
            tts = TextToSpeech(this) { st ->
                ttsPronto = st == TextToSpeech.SUCCESS
                if (ttsPronto) { tts?.language = Locale.forLanguageTag("pt-BR"); tts?.speak(limpo, TextToSpeech.QUEUE_FLUSH, null, "luna") }
            }
        } else if (ttsPronto) tts?.speak(limpo, TextToSpeech.QUEUE_FLUSH, null, "luna")
    }

    override fun compartilhar(texto: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, texto), "Compartilhar"))
    }

    override fun conversar() = startActivity(Intent(this, ChatActivity::class.java))

    override fun sincronizar() {
        SyncManager.requestSync(this)
        Toast.makeText(this, if (online) "Sincronizando…" else "Sem internet: sincroniza quando a conexão voltar.", Toast.LENGTH_SHORT).show()
    }

    override fun menu(ancora: View) {
        val m = PopupMenu(this, ancora)
        m.menu.add(0, 1, 0, "Conversar com a Luna")
        m.menu.add(0, 2, 1, "Histórico")
        m.menu.add(0, 3, 2, "Lançar dia esquecido")
        m.menu.add(0, 4, 3, "Anotações")
        m.menu.add(0, 8, 4, "Configurações / Planejamento")
        m.menu.add(0, 5, 4, "Luna fala os alertas").apply { isCheckable = true; isChecked = prefs.getBoolean("voz_alertas", true) }
        m.menu.add(0, 6, 5, getString(R.string.teste_firebase))
        m.menu.add(0, 7, 6, getString(R.string.atualizacao))
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> conversar()
                2 -> startActivity(Intent(this, HistoricoActivity::class.java))
                3 -> diaEsquecido()
                4 -> startActivity(Intent(this, AnotacoesActivity::class.java))
                5 -> prefs.edit().putBoolean("voz_alertas", !prefs.getBoolean("voz_alertas", true)).apply()
                6 -> startActivity(Intent(this, TesteFirebaseActivity::class.java))
                7 -> startActivity(Intent(this, AtualizacaoActivity::class.java))
                8 -> planejamento()
            }
            true
        }
        m.show()
    }

    companion object {
        /** Sugestão de parar abaixo de R$ 20/h de líquido (depois de 2 h de trabalho). */
        const val LIMITE_POR_HORA_PADRAO = 2000L
    }
}
