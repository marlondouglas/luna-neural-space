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
import space.neural.luna.dashboard.AppsCard
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
 * Layout: 1 coluna (< 600dp), 2 colunas (600–839dp) e 3 colunas (≥ 840dp).
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
    private lateinit var apps: AppsCard
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

    override fun onDestroy() { tts?.shutdown(); super.onDestroy() }

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

        cab = Cabecalho(ui, compacto = telefonePaisagem || cls == 3, a = this)
        turnoCard = TurnoCard(ui, largo = cls == 2 && !telefonePaisagem, a = this)
        resumo = ResumoCard(ui, this)
        metas = MetasCard(ui, this)
        apps = AppsCard(ui, horizontal = cls != 3 && (cls == 2 || w >= 400), a = this)
        noticias = NoticiasCard(ui, lado = cls == 2 && !telefonePaisagem || (cls == 1 && w >= 480), a = this)
        dica = DicaCard(ui, this)

        val raiz = ui.vbox().apply { setBackgroundColor(Cores.OBSIDIAN) }
        raiz.addView(cab.view, ui.mp())

        fun coluna(id: Int, vararg cards: View): ScrollView {
            val col = ui.vbox()
            cards.forEachIndexed { i, c -> col.addView(c, ui.mp(if (i == 0) 0 else gap)) }
            col.setPadding(0, 0, 0, gap)
            return ScrollView(this).apply { this.id = id; addView(col); isVerticalScrollBarEnabled = false }
        }
        fun linha(vararg cards: View) = ui.hbox().apply {
            gravity = android.view.Gravity.TOP
            cards.forEachIndexed { i, c -> addView(c, ui.peso(1f, if (i == 0) 0 else gap)) }
        }

        val ordem: List<View>
        when {
            cls == 1 -> {
                ordem = listOf(turnoCard.view, resumo.view, metas.view, apps.view, noticias.view, dica.view)
                raiz.addView(coluna(R.id.rolagem_principal, *ordem.toTypedArray()), LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = gap })
            }
            telefonePaisagem -> {
                ordem = listOf(turnoCard.view, resumo.view, metas.view, apps.view, noticias.view, dica.view)
                val l = ui.hbox().apply { gravity = android.view.Gravity.TOP }
                l.addView(coluna(R.id.rolagem_esquerda, turnoCard.view, resumo.view), LinearLayout.LayoutParams(0, -1, 0.4f))
                l.addView(coluna(R.id.rolagem_direita, metas.view, apps.view, noticias.view, dica.view), LinearLayout.LayoutParams(0, -1, 0.6f).apply { leftMargin = gap })
                raiz.addView(l, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = gap })
            }
            cls == 2 -> {
                ordem = listOf(turnoCard.view, resumo.view, metas.view, apps.view, noticias.view, dica.view)
                val col = ui.vbox()
                col.addView(turnoCard.view, ui.mp())
                col.addView(linha(resumo.view, metas.view), ui.mp(gap))
                col.addView(apps.view, ui.mp(gap))
                col.addView(linha(noticias.view, dica.view), ui.mp(gap))
                col.setPadding(0, 0, 0, gap)
                raiz.addView(ScrollView(this).apply { id = R.id.rolagem_principal; addView(col) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = gap })
            }
            else -> {
                ordem = listOf(turnoCard.view, resumo.view, metas.view, apps.view, noticias.view, dica.view)
                val l = ui.hbox().apply { gravity = android.view.Gravity.TOP }
                l.addView(coluna(R.id.rolagem_col1, turnoCard.view, resumo.view), LinearLayout.LayoutParams(0, -1, 1f))
                l.addView(coluna(R.id.rolagem_col2, metas.view, apps.view), LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = gap })
                l.addView(coluna(R.id.rolagem_col3, noticias.view, dica.view), LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = gap })
                raiz.addView(l, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = gap })
            }
        }
        setContentView(raiz)
        ViewCompat.setOnApplyWindowInsetsListener(raiz) { v, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left + margem, b.top + margem / 2, b.right + margem, b.bottom); ins
        }
        if (animar) { Anima.entrada(cab.view, 0); ordem.forEachIndexed { i, v -> Anima.entrada(v, i + 1) } }
    }

    private fun desenhar() {
        val p = painel ?: run {
            noticias.bind(externo, online, carregandoExterno); dica.bind(java.time.LocalDate.now()); return
        }
        val agora = System.currentTimeMillis()
        cab.bind(p, online, BuildConfig.FIREBASE_CONFIGURED)
        turnoCard.bind(p.turno)
        resumo.bind(p, agora, sugerirParar(p, agora))
        metas.bind(p, alertaMotivacional(p))
        apps.bind(p)
        noticias.bind(externo, online, carregandoExterno)
        dica.bind(p.hoje)
    }

    private fun sugerirParar(p: Painel, agora: Long): Boolean {
        if (agora < prefs.getLong("parar_agora_nao_ate", 0)) return false
        val trab = Calculos.trabalhoMs(p.turno, agora)
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

    override fun iniciar() { lifecycleScope.launch { acoes.iniciar() } }
    override fun pausar() = comTurno { acoes.pausar(it) }
    override fun cafe() = comTurno { acoes.cafe(it) }
    override fun retomar() = comTurno { acoes.retomar(it) }
    override fun fecharTurno() = comTurno {
        acoes.fechar(it)
        startActivity(LancamentosActivity.correcao(this, it.id)) // sempre abre a tela de correção
    }
    override fun concluir() = comTurno {
        acoes.concluir(it)
        Toast.makeText(this, "Dia concluído e salvo no histórico.", Toast.LENGTH_SHORT).show()
    }
    override fun corrigir() { painel?.turno?.let { startActivity(LancamentosActivity.correcao(this, it.id)) } }
    override fun zerar() {
        dlg.confirmar("Zerar turno", "O turno recomeça do zero. Os ganhos continuam no histórico.", "Zerar") {
            comTurno { acoes.concluir(it) }
        }
    }
    override fun diaEsquecido() = dlg.escolherDia { startActivity(LancamentosActivity.dia(this, it.toString())) }
    override fun detalheDia() = startActivity(LancamentosActivity.dia(this, java.time.LocalDate.now().toString()))
    override fun detalheApp(app: String) = startActivity(LancamentosActivity.dia(this, java.time.LocalDate.now().toString(), app))
    override fun lancarGanho(app: String?) = dlg.ganho(java.time.LocalDate.now().toString(), painel?.turno?.id, app)
    override fun lancarGasto(categoria: String?) = dlg.gasto(java.time.LocalDate.now().toString(), painel?.turno?.id, categoria)
    override fun editarMeta(tipo: String, atual: Long?) = dlg.meta(tipo, atual)
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
