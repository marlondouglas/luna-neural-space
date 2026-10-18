package space.neural.luna.chat

import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
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
    private lateinit var orb: LunaOrbView
    private lateinit var status: TextView
    private var digitando: View? = null
    private var painel: Painel? = null
    private val historico = mutableListOf<Msg>()
    private var pendente: Comandos.Comando.Ganho? = null
    private var tts: TextToSpeech? = null
    private var falar = false
    private var job: Job? = null
    private val remoto = RemotoCerebro()
    private val local = LocalCerebro()

    private val ditado = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val t = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!t.isNullOrBlank()) enviar(t)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this); repo = LunaRepositories(this)
        falar = getSharedPreferences("dashboard", 0).getBoolean("chat_falar", false)
        val raiz = ui.vbox().apply { setBackgroundColor(Cores.OBSIDIAN) }

        // Cabeçalho
        val cab = ui.hbox().apply { setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(8)) }
        cab.addView(ui.botaoIcone(R.drawable.ic_close, "Voltar ao painel") { finish() })
        orb = LunaOrbView(this)
        cab.addView(orb, LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)).apply { leftMargin = ui.dp(4) })
        val tit = ui.vbox()
        tit.addView(ui.text("Luna", 20f, Cores.BRANCO, bold = true))
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
            if (!falar) tts?.stop()
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
        barra.addView(ui.botaoIcone(R.drawable.ic_mic, "Falar com a Luna", Cores.CIANO) { ditar() })
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
        setContentView(raiz)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(raiz) { v, ins ->
            val b = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom); ins
        }

        lifecycleScope.launch { Painel.observar(repo).collect { painel = it } }

        // Restaura a conversa ao girar a tela.
        val roles = savedInstanceState?.getStringArrayList("roles")
        val textos = savedInstanceState?.getStringArrayList("textos")
        if (roles != null && textos != null && roles.size == textos.size) {
            roles.indices.forEach { i -> historico += Msg(roles[i], textos[i]); bolha(textos[i], roles[i] == "user", animar = false) }
        } else {
            val boasVindas = "Oi, Marlon! Sou a Luna. Posso te contar como está o dia, quanto falta para a meta, dar uma dica ou lançar uma corrida — é só dizer, por exemplo, \"30 na Uber\"."
            historico += Msg("assistant", boasVindas); bolha(boasVindas, false)
        }
        intent.getStringExtra(EXTRA_PERGUNTA)?.takeIf { savedInstanceState == null }?.let { enviar(it) }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putStringArrayList("roles", ArrayList(historico.map { it.role }))
        out.putStringArrayList("textos", ArrayList(historico.map { it.content }))
    }

    override fun onDestroy() { tts?.shutdown(); super.onDestroy() }

    private fun atualizarStatus(usouIa: Boolean?) {
        status.text = when {
            !remoto.disponivel -> "ⓘ Respostas locais (servidor não configurado)"
            usouIa == false -> "ⓘ Sem internet — respostas locais"
            else -> "● Online"
        }
    }

    private fun ditar() {
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

    private fun enviar(texto: String) {
        if (job?.isActive == true) return
        historico += Msg("user", texto); bolha(texto, true)
        job = lifecycleScope.launch {
            val p = painel ?: Painel.observar(repo).first()
            val resposta = tratarLocal(texto, p) ?: run {
                mostrarDigitando(true)
                val r = try {
                    if (!remoto.disponivel) error("sem servidor")
                    remoto.responder(historico, p).also { atualizarStatus(true) }
                } catch (e: Exception) {
                    atualizarStatus(false)
                    local.responder(historico, p)
                }
                mostrarDigitando(false); r
            }
            historico += Msg("assistant", resposta); bolha(resposta, false)
            if (falar) ler(resposta)
        }
    }

    /** Comandos que rodam no aparelho. Retorna a resposta ou null para conversar normalmente. */
    private suspend fun tratarLocal(texto: String, p: Painel?): String? {
        if (p == null) return null
        pendente?.let { pend ->
            when (Comandos.totalOuMais(texto)) {
                "TOTAL" -> {
                    pendente = null
                    val atual = p.porApp.first { it.app == pend.app }.centavos
                    val dif = pend.centavos - atual
                    if (dif <= 0) return "O ${Fmt.nomeApp(pend.app)} já está com ${Fmt.reais(atual)} hoje, então não lancei nada."
                    repo.salvarGanho(Ganho(turnoId = p.turno?.id, data = p.hoje.toString(), app = pend.app, valorCentavos = dif, descricao = "Ajuste para o total do dia"))
                    return "Pronto! Lancei ${Fmt.reais(dif)} no ${Fmt.nomeApp(pend.app)}, e o total do dia ficou ${Fmt.reais(pend.centavos)}."
                }
                "MAIS" -> {
                    pendente = null
                    repo.salvarGanho(Ganho(turnoId = p.turno?.id, data = p.hoje.toString(), app = pend.app, valorCentavos = pend.centavos))
                    return "Lancei mais ${Fmt.reais(pend.centavos)} no ${Fmt.nomeApp(pend.app)}. 💜"
                }
                else -> pendente = null
            }
        }
        val acoes = TurnoAcoes(repo)
        return when (val c = Comandos.interpretar(texto)) {
            is Comandos.Comando.Ganho -> {
                pendente = c
                perguntaTotalOuMais()
                "${Fmt.reais(c.centavos)} no ${Fmt.nomeApp(c.app)}: é o total do dia ou um valor a mais?"
            }
            is Comandos.Comando.Turno -> {
                val t = p.turno
                when (c.acao) {
                    "INICIAR" -> if (t != null && t.estado != EstadoTurno.FECHADO) "Já tem um turno aberto." else { acoes.iniciar(); "Turno iniciado! Boa rota. ▶" }
                    "PAUSAR" -> if (t?.estado == EstadoTurno.ATIVO) { acoes.pausar(t); "Turno pausado. ⏸" } else "Não há turno trabalhando para pausar."
                    "CAFE" -> if (t?.estado == EstadoTurno.ATIVO || t?.estado == EstadoTurno.PAUSADO) { acoes.cafe(t); "Bom café! ☕ O tempo conta como parado." } else "Não há turno aberto."
                    "RETOMAR" -> if (t?.estado == EstadoTurno.PAUSADO || t?.estado == EstadoTurno.CAFE) { acoes.retomar(t); "De volta ao trabalho! ▶" } else "O turno não está pausado."
                    else -> null
                }
            }
            null -> null
        }
    }

    private fun perguntaTotalOuMais() {
        val linha = ui.hbox().apply { gravity = Gravity.START }
        linha.addView(ui.botao("É o total", Ui.Estilo.CONTORNO) { v -> (v.parent as? ViewGroup)?.let { lista.removeView(it) }; enviar("total") })
        linha.addView(ui.botao("É a mais", Ui.Estilo.CHEIO_VIOLETA) { v -> (v.parent as? ViewGroup)?.let { lista.removeView(it) }; enviar("a mais") }, ui.wc(ui.dp(8)))
        lista.postDelayed({ lista.addView(linha, ui.mp(ui.dp(4))); rolar() }, 50)
    }

    private fun bolha(texto: String, minha: Boolean, animar: Boolean = true) {
        val tv = ui.text(texto, 16f, if (minha) Cores.OBSIDIAN else Cores.BRANCO).apply {
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
            setTextIsSelectable(true)
            background = if (minha) android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(Cores.VIOLETA, Cores.mix(Cores.VIOLETA, Cores.CIANO, 0.35f)),
            ).apply { cornerRadii = raios(true) }
            else android.graphics.drawable.GradientDrawable().apply {
                setColor(Cores.mix(Cores.OBSIDIAN, Cores.VIOLETA, 0.16f)); setStroke(ui.dp(1), Cores.alpha(Cores.VIOLETA, 0.35f)); cornerRadii = raios(false)
            }
            contentDescription = (if (minha) "Você: " else "Luna: ") + texto
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
        val limpo = texto.replace(Regex("[\\p{So}\\p{Cn}]"), "")
        val t = tts
        if (t == null) {
            tts = TextToSpeech(this) { st ->
                if (st == TextToSpeech.SUCCESS) { tts?.language = Locale.forLanguageTag("pt-BR"); tts?.speak(limpo, TextToSpeech.QUEUE_FLUSH, null, "luna") }
            }
        } else t.speak(limpo, TextToSpeech.QUEUE_FLUSH, null, "luna")
    }

    companion object { const val EXTRA_PERGUNTA = "pergunta" }
}
