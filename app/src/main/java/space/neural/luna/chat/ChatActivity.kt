package space.neural.luna.chat

import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import space.neural.luna.R
import space.neural.luna.dashboard.Anima
import space.neural.luna.dashboard.Cores
import space.neural.luna.dashboard.Fmt
import space.neural.luna.dashboard.LunaOrbView
import space.neural.luna.dashboard.Painel
import space.neural.luna.dashboard.TurnoAcoes
import space.neural.luna.dashboard.Ui
import space.neural.luna.data.local.EstadoTurno
import space.neural.luna.data.local.Ganho
import space.neural.luna.data.repo.LunaRepositories
import java.util.Locale

/**
 * Conversar com a Luna: bolhas, campo de texto, enviar e microfone (ditado do
 * Android). Usa a IA do servidor (/chat) quando há internet e cai nas respostas
 * locais quando não há. Lançamentos ("30 na Uber") e comandos do turno são
 * feitos aqui mesmo, no aparelho.
 */
class ChatActivity : AppCompatActivity() {
    private lateinit var ui: Ui
    private lateinit var repo: LunaRepositories
    private lateinit var lista: LinearLayout
    private lateinit var rolagem: ScrollView
    private lateinit var entrada: EditText
    private lateinit var orb: space.neural.luna.avatar.LunaAvatarView
    private lateinit var status: TextView
    private var digitando: View? = null
    private lateinit var conversa: LunaConversa
    private var botoes: View? = null
    private var falar = false
    private var job: Job? = null
    private var fotoUri: android.net.Uri? = null
    private lateinit var micBtn: android.widget.ImageButton
    /** v0.1.5: conversa por voz contínua (ouve de novo quando ela termina; barge-in enquanto fala). */
    private val voz by lazy {
        space.neural.luna.voz.ConversaVoz(this, object : space.neural.luna.voz.ConversaVoz.Ouvinte {
            override fun ouvindo(parcial: String?) = mostrarOuvindo(true, parcial)
            override fun ouviu(texto: String) { mostrarOuvindo(false, null); enviar(texto, voz = true) }
            override fun encerrou(motivo: String?) { mostrarOuvindo(false, null); motivo?.let { status.text = it } }
        })
    }
    private val permMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) ditar() else Toast.makeText(this, "Sem permissão do microfone.", Toast.LENGTH_SHORT).show() }

    private val ditado = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val t = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!t.isNullOrBlank()) enviar(t, voz = true)
    }
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) fotoUri?.let { lerNota(it) } }
    private val galeria = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { lerNota(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this); repo = LunaRepositories(this); conversa = LunaConversa.get(this)
        falar = getSharedPreferences("dashboard", 0).getBoolean("chat_falar", true)
        val raiz = ui.vbox()

        // Cabeçalho
        val cab = ui.hbox().apply { setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(8)) }
        cab.addView(ui.botaoIcone(R.drawable.ic_close, "Voltar ao painel") { finish() })
        // v0.1.5: Luna animada maior no topo, falando cada resposta
        orb = space.neural.luna.avatar.LunaAvatarView(this, focoY = 0.45f, zoom = 0.84f)
        val lado = if (resources.configuration.smallestScreenWidthDp >= 600) 148 else 116
        cab.addView(orb, LinearLayout.LayoutParams(ui.dp(lado), ui.dp(lado)).apply { leftMargin = ui.dp(4) })
        val tit = ui.vbox()
        tit.addView(ui.text("Luna", 22f, Cores.BRANCO, bold = true).apply { setShadowLayer(16f, 0f, 0f, Cores.alpha(Cores.VIOLETA, 0.8f)) })
        status = ui.text("", 13f, Cores.CIANO)
        tit.addView(status)
        cab.addView(tit, ui.peso(1f, ui.dp(10)))
        val som = ui.botaoIcone(R.drawable.ic_volume, "", if (falar) Cores.CIANO else Cores.BRANCO_40) {}
        fun pintarSom() {
            som.imageTintList = android.content.res.ColorStateList.valueOf(if (falar) Cores.CIANO else Cores.BRANCO_40)
            som.contentDescription = if (falar) "Luna lê as respostas: ligado" else "Luna lê as respostas: desligado"
        }
        som.setOnClickListener {
            falar = !falar; pintarSom()
            getSharedPreferences("dashboard", 0).edit().putBoolean("chat_falar", falar).apply()
            if (!falar) space.neural.luna.avatar.LunaVoz.parar()
        }
        pintarSom()
        cab.addView(som)
        raiz.addView(cab, ui.mp())
        atualizarStatus(null)

        // Mensagens
        lista = ui.vbox().apply { setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8)) }
        rolagem = ScrollView(this).apply { addView(lista); isFillViewport = true }
        raiz.addView(rolagem, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Sugestões rápidas
        val chips = ui.hbox().apply { setPadding(ui.dp(12), 0, ui.dp(12), 0) }
        listOf("Quanto fiz hoje?", "Quanto falta pra meta?", "Qual app rendeu mais?", "Uma dica", "Versículo do dia").forEach { s ->
            chips.addView(ui.botao(s) { enviar(s) }.apply { textSize = 13f }, ui.wc(ui.dp(8)))
        }
        raiz.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }, ui.mp())

        // Entrada
        val barra = ui.hbox().apply { setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(12)) }
        barra.addView(ui.botaoIcone(R.drawable.ic_camera, "Foto de nota fiscal: câmera ou galeria", Cores.MAGENTA) { escolherFoto(it) })
        micBtn = ui.botaoIcone(R.drawable.ic_mic, "Falar com a Luna", Cores.CIANO) { alternarVoz() }
        barra.addView(micBtn)
        orb.setOnClickListener { if (voz.ativa || voz.escutando) pararVoz() else if (space.neural.luna.avatar.LunaVoz.ocupada) space.neural.luna.avatar.LunaVoz.parar() else alternarVoz() }
        entrada = EditText(this).apply {
            hint = "Fale com a Luna…"; setTextColor(Cores.BRANCO); setHintTextColor(Cores.BRANCO_70)
            textSize = 16f; minHeight = ui.dp(48); maxLines = 4
            background = ui.fundo(Cores.CARD, Cores.alpha(Cores.VIOLETA, 0.4f), 24f)
            setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10))
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEND) { enviarCampo(); true } else false }
        }
        barra.addView(entrada, ui.peso(1f, ui.dp(4)))
        barra.addView(ui.botaoIcone(R.drawable.ic_send, "Enviar", Cores.VIOLETA) { enviarCampo() }, ui.wc(ui.dp(4)))
        raiz.addView(barra, ui.mp())
        val tela = FrameLayout(this)
        tela.addView(space.neural.luna.dashboard.GalaxiaView(this), FrameLayout.LayoutParams(-1, -1))
        tela.addView(raiz, FrameLayout.LayoutParams(-1, -1))
        setContentView(tela)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(raiz) { v, ins ->
            val b = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom); ins
        }

        // Histórico salvo no Room: a conversa continua depois de fechar o app.
        lifecycleScope.launch {
            val h = conversa.historico()
            if (h.isEmpty()) {
                bolha("Oi, Marlon! Sou a Luna. Posso te contar como está o dia, quanto falta para a meta, ler a foto de uma nota fiscal ou lançar uma corrida — é só dizer, por exemplo, \"30 na Uber\".", false)
            } else h.forEach { bolha(it.content, it.role == "user", animar = false) }
            intent.getStringExtra(EXTRA_PERGUNTA)?.takeIf { savedInstanceState == null }?.let { enviar(it) }
        }
    }

    override fun onDestroy() { voz.destruir(); if (isFinishing) space.neural.luna.avatar.LunaVoz.parar(); super.onDestroy() }
    override fun onStop() { if (voz.ativa || voz.escutando) voz.parar(null); super.onStop() }

    private fun alternarVoz() {
        if (voz.ativa || voz.escutando) { pararVoz(); return }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permMic.launch(android.Manifest.permission.RECORD_AUDIO); return
        }
        ditar()
    }

    private fun pararVoz() { voz.parar(null); space.neural.luna.avatar.LunaVoz.parar(); atualizarStatus(null) }

    /** Estado "Ouvindo…": aro ciano pulsando na Luna, status e microfone acesos. */
    private fun mostrarOuvindo(sim: Boolean, parcial: String?) {
        orb.ouvindo = sim
        micBtn.background = if (sim) ui.fundo(Cores.alpha(Cores.CIANO, 0.25f), Cores.CIANO, 24f) else null
        micBtn.contentDescription = if (sim) "Ouvindo. Toque para parar" else "Falar com a Luna"
        if (sim) { status.text = "🎙 Ouvindo…" + (parcial?.let { " “$it”" } ?: " (toque na Luna para parar)"); status.setTextColor(Cores.CIANO) }
        else atualizarStatus(null)
    }

    private fun atualizarStatus(usouIa: Boolean?) {
        status.text = when {
            !conversa.remotoDisponivel -> "ⓘ Respostas locais (servidor não configurado)"
            usouIa == false -> "ⓘ Sem internet — respostas locais"
            else -> "● Online"
        }
    }

    private fun ditar() {
        if (voz.disponivel()) { space.neural.luna.avatar.LunaVoz.parar(); voz.iniciar(); return }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Fale com a Luna")
        try { ditado.launch(i) } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Este aparelho não tem reconhecimento de voz. Use o teclado.", Toast.LENGTH_LONG).show()
        }
    }

    private fun enviarCampo() {
        val t = entrada.text.toString().trim()
        if (t.isEmpty()) return
        entrada.setText(""); enviar(t)
    }

    private fun enviar(texto: String, voz: Boolean = false, rotulo: String? = null) {
        if (job?.isActive == true) return
        botoes?.let { lista.removeView(it) }; botoes = null
        bolha(rotulo ?: texto, true)
        job = lifecycleScope.launch {
            mostrarDigitando(true)
            val r = conversa.enviar(texto, voz, rotulo)
            mostrarDigitando(false)
            if (r.usouIa != null) atualizarStatus(r.usouIa)
            responder(r)
        }
    }

    private fun responder(r: LunaConversa.Resposta) {
        bolha(r.texto, false)
        if (falar || voz.ativa) ler(r.texto)
        if (r.botoes.isEmpty()) return
        val grade = ui.vbox()
        r.botoes.chunked(2).forEach { par ->
            val l = ui.hbox()
            par.forEachIndexed { i, b ->
                l.addView(ui.botao(b.rotulo, if (b.principal) Ui.Estilo.CHEIO_VIOLETA else Ui.Estilo.CONTORNO) { enviar(b.comando, rotulo = b.rotulo) }, ui.peso(1f, if (i == 0) 0 else ui.dp(8)))
            }
            grade.addView(l, ui.mp(ui.dp(6)))
        }
        botoes = grade
        lista.addView(grade, ui.mp(ui.dp(4))); rolar()
    }

    private fun escolherFoto(ancora: View) {
        val m = android.widget.PopupMenu(this, ancora)
        m.menu.add(0, 1, 0, "Tirar foto da nota")
        m.menu.add(0, 2, 1, "Escolher da galeria")
        m.setOnMenuItemClickListener {
            if (it.itemId == 1) {
                val dir = java.io.File(cacheDir, "fotos").apply { mkdirs() }
                val f = java.io.File(dir, "nota.jpg")
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
                fotoUri = uri
                runCatching { camera.launch(uri) }.onFailure { Toast.makeText(this, "Câmera indisponível. Use a galeria.", Toast.LENGTH_SHORT).show() }
            } else runCatching { galeria.launch("image/*") }
            true
        }
        m.show()
    }

    private fun lerNota(uri: android.net.Uri) {
        if (job?.isActive == true) return
        botoes?.let { lista.removeView(it) }; botoes = null
        bolha("📷 Foto de nota fiscal", true)
        job = lifecycleScope.launch {
            mostrarDigitando(true)
            val jpeg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { Foto.reduzir(this@ChatActivity, uri) }.getOrNull() }
            val r = if (jpeg == null) LunaConversa.Resposta("Não consegui abrir essa foto.") else conversa.lerNota(jpeg)
            mostrarDigitando(false)
            responder(r)
        }
    }

    private fun bolha(texto: String, minha: Boolean, animar: Boolean = true) {
        val tv = ui.text(texto, 16f, if (minha) Cores.OBSIDIAN else Cores.BRANCO).apply {
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
            setTextIsSelectable(true)
            background = if (minha) android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(Cores.VIOLETA, Cores.mix(Cores.VIOLETA, Cores.MAGENTA, 0.45f)),
            ).apply { cornerRadii = raios(true) }
            else android.graphics.drawable.GradientDrawable().apply {
                orientation = android.graphics.drawable.GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(Cores.mix(Cores.OBSIDIAN, Cores.VIOLETA, 0.22f), Cores.mix(Cores.OBSIDIAN, Cores.CIANO, 0.08f))
                setStroke(ui.dp(1), Cores.alpha(Cores.CIANO, 0.35f)); cornerRadii = raios(false)
            }
            contentDescription = (if (minha) "Você: " else "Luna: ") + texto
            elevation = ui.dpf(6f)
            if (android.os.Build.VERSION.SDK_INT >= 28) outlineSpotShadowColor = if (minha) Cores.MAGENTA else Cores.CIANO
        }
        val wrap = FrameLayout(this)
        wrap.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            if (minha) Gravity.END else Gravity.START).apply {
            if (minha) leftMargin = ui.dp(48) else rightMargin = ui.dp(48)
        })
        lista.addView(wrap, ui.mp(ui.dp(8)))
        if (animar) Anima.entrada(wrap, 0)
        rolar()
    }

    private fun raios(minha: Boolean): FloatArray {
        val r = ui.dpf(18f); val s = ui.dpf(4f)
        return if (minha) floatArrayOf(r, r, r, r, s, s, r, r) else floatArrayOf(r, r, r, r, r, r, s, s)
    }

    private fun mostrarDigitando(sim: Boolean) {
        orb.ativa = sim
        digitando?.let { lista.removeView(it) }; digitando = null
        if (!sim) return
        val pontos = ui.text("●  ●  ●", 14f, Cores.CIANO).apply {
            setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10))
            background = ui.fundo(Cores.mix(Cores.OBSIDIAN, Cores.VIOLETA, 0.16f), Cores.alpha(Cores.VIOLETA, 0.35f), 18f)
            contentDescription = "Luna está digitando"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        if (Anima.ligadas()) ValueAnimator.ofFloat(0.35f, 1f).apply {
            duration = 600; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { pontos.alpha = it.animatedValue as Float }
            pontos.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) { cancel() }
            })
            start()
        }
        val wrap = FrameLayout(this).apply { addView(pontos, FrameLayout.LayoutParams(-2, -2, Gravity.START)) }
        digitando = wrap
        lista.addView(wrap, ui.mp(ui.dp(8))); rolar()
    }

    private fun rolar() = rolagem.post { rolagem.fullScroll(View.FOCUS_DOWN) }

    private fun ler(texto: String) {
        if (voz.ativa) { voz.falar(texto); return } // conversa contínua: volta a ouvir quando ela terminar
        orb.ativa = true
        space.neural.luna.avatar.LunaVoz.falar(this, texto) { orb.ativa = false }
    }

    companion object { const val EXTRA_PERGUNTA = "pergunta" }
}
