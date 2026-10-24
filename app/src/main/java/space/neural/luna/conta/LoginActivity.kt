package space.neural.luna.conta

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import space.neural.luna.MainActivity
import space.neural.luna.R
import space.neural.luna.dashboard.Cores
import space.neural.luna.dashboard.FundoNeon
import space.neural.luna.dashboard.Ui
import space.neural.luna.licenca.Licenca

/**
 * Entrar / Criar conta (e-mail OU celular + senha). Obrigatório para usar o app (o teste grátis e a
 * assinatura ficam presos à conta e ao aparelho no servidor).
 */
class LoginActivity : AppCompatActivity() {
    private lateinit var ui: Ui
    private var criar = true
    private lateinit var abaEntrar: TextView
    private lateinit var abaCriar: TextView
    private lateinit var nome: EditText
    private lateinit var id: EditText
    private lateinit var senha: EditText
    private lateinit var confirmar: EditText
    private lateinit var marcaVeiculo: EditText
    private lateinit var tipoVeiculo: android.widget.Spinner
    private lateinit var iconeVeiculo: android.widget.Spinner
    private lateinit var modelo: EditText
    private lateinit var cor: EditText
    private lateinit var placa: EditText
    private lateinit var veiculo: LinearLayout
    private lateinit var erro: TextView
    private lateinit var botao: android.widget.Button
    private lateinit var esqueci: android.widget.Button
    private lateinit var rodape: TextView
    private var ocupado = false

    companion object {
        const val EXTRA_ENTRAR = "entrar"
        fun abrir(ctx: Context, entrar: Boolean = false) =
            ctx.startActivity(Intent(ctx, LoginActivity::class.java).putExtra(EXTRA_ENTRAR, entrar)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        criar = !intent.getBooleanExtra(EXTRA_ENTRAR, false)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { moveTaskToBack(true) } // sem conta não volta para o app
        })
        montar()
        aplicarModo()
    }

    private fun campo(dica: String, tipo: Int, acao: Int = EditorInfo.IME_ACTION_NEXT) = EditText(this).apply {
        hint = dica; inputType = tipo; imeOptions = acao; isSingleLine = true
        typeface = android.graphics.Typeface.DEFAULT // senha sem fonte monoespaçada
        setTextColor(Cores.BRANCO); setHintTextColor(Cores.BRANCO_40); textSize = 16f
        background = ui.fundo(Cores.alpha(Cores.BRANCO, 0.06f), Cores.alpha(Cores.VIOLETA, 0.55f), 14f)
        setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
        minHeight = ui.dp(52)
    }

    private fun rotulo(s: String) = ui.text(s, 13f, Cores.BRANCO_70, bold = true)

    private fun montar() {
        val largo = resources.configuration.screenWidthDp >= 720
        val raiz = FrameLayoutComFundo()
        val scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        val linha = LinearLayout(this).apply {
            orientation = if (largo) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        // Marca
        val marca = ui.vbox().apply { gravity = if (largo) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER_HORIZONTAL }
        marca.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher_round); contentDescription = null
        }, LinearLayout.LayoutParams(ui.dp(if (largo) 112 else 84), ui.dp(if (largo) 112 else 84)))
        marca.addView(ui.text("Rota GV", if (largo) 34f else 28f, Cores.BRANCO, bold = true).apply { gravity = marca.gravity }, ui.wc().apply { topMargin = ui.dp(10) })
        marca.addView(ui.text("Seus ganhos, metas e a Luna, sua copiloto, em qualquer celular.", if (largo) 17f else 15f, Cores.BRANCO_70).apply {
            gravity = if (largo) Gravity.START else Gravity.CENTER_HORIZONTAL
        }, LinearLayout.LayoutParams(if (largo) ui.dp(340) else -1, -2).apply { topMargin = ui.dp(6) })
        if (largo) {
            marca.addView(ui.selo("7 dias grátis · depois R$ 7/mês", Cores.ESMERALDA), ui.wc().apply { topMargin = ui.dp(18) })
        }

        // Cartão do formulário
        val card = ui.card(destaque = true)
        val abas = ui.hbox().apply { background = ui.fundo(Cores.alpha(Cores.BRANCO, 0.05f), raio = 14f); setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) }
        fun aba(s: String, c: Boolean) = ui.text(s, 15f, Cores.BRANCO, bold = true).apply {
            gravity = Gravity.CENTER; minHeight = ui.dp(44); isClickable = true; isFocusable = true
            setOnClickListener { if (!ocupado) { criar = c; aplicarModo() } }
        }
        abaEntrar = aba("Entrar", false); abaCriar = aba("Criar conta", true)
        abas.addView(abaCriar, ui.peso(1f)); abas.addView(abaEntrar, ui.peso(1f, ui.dp(4)))
        card.addView(abas, ui.mp())

        nome = campo("Como você quer ser chamado", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_VARIATION_PERSON_NAME)
        id = campo("voce@email.com ou (33) 98765-4321", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        senha = campo("Mínimo de 6 caracteres", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        confirmar = campo("Repita a senha", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, EditorInfo.IME_ACTION_DONE)

        fun bloco(r: String, e: EditText, tag: String) = ui.vbox().apply {
            this.tag = tag
            addView(rotulo(r)); addView(e, ui.mp(ui.dp(6)))
        }
        card.addView(bloco("Nome", nome, "nome"), ui.mp(ui.dp(16)))
        card.addView(bloco("E-mail ou celular", id, "id"), ui.mp(ui.dp(12)))
        card.addView(bloco("Senha", senha, "senha"), ui.mp(ui.dp(12)))
        card.addView(bloco("Confirmar senha", confirmar, "confirmar"), ui.mp(ui.dp(12)))
        marcaVeiculo = campo("Marca do veículo (opcional, privada)", InputType.TYPE_CLASS_TEXT)
        modelo = campo("Modelo do veículo (opcional)", InputType.TYPE_CLASS_TEXT)
        cor = campo("Cor (opcional)", InputType.TYPE_CLASS_TEXT)
        placa = campo("Placa (opcional)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        fun selection(labels: List<String>) = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@LoginActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            minimumHeight = ui.dp(48)
        }
        tipoVeiculo = selection(space.neural.luna.collaboration.VehiclePowertrain.entries.map { it.label })
        iconeVeiculo = selection(space.neural.luna.collaboration.VehicleIcon.entries.map { it.label })
        veiculo = ui.vbox().apply {
            addView(rotulo("Meu veículo"))
            addView(marcaVeiculo, ui.mp(ui.dp(6))); addView(modelo, ui.mp(ui.dp(6))); addView(cor, ui.mp(ui.dp(6))); addView(placa, ui.mp(ui.dp(6)))
            addView(rotulo("Tipo do veículo (privado)")); addView(tipoVeiculo, ui.mp(ui.dp(6)))
            addView(rotulo("Ícone representativo (privado)")); addView(iconeVeiculo, ui.mp(ui.dp(6)))
            addView(ui.text("Marca, tipo e ícone ficam privados; o desenho é representativo. Modelo e cor aparecem nas salas. A placa começa oculta. Depois de entrar, você pode escolher compartilhá-la em Meu veículo e privacidade.", 12f, Cores.BRANCO_70), ui.mp(ui.dp(6)))
        }
        card.addView(veiculo, ui.mp(ui.dp(12)))

        erro = ui.text("", 14f, Cores.CORAL).apply { visibility = View.GONE }
        card.addView(erro, ui.mp(ui.dp(10)))
        botao = ui.botao("Criar conta", Ui.Estilo.CHEIO_VIOLETA, alto = true) { enviar() }
        card.addView(botao, ui.mp(ui.dp(16)))
        esqueci = ui.botao("Esqueci a senha", Ui.Estilo.TEXTO) { esqueciSenha() }
        card.addView(esqueci, ui.mp(ui.dp(4)))
        rodape = ui.text("", 13f, Cores.BRANCO_70).apply { gravity = Gravity.CENTER_HORIZONTAL }
        card.addView(rodape, ui.mp(ui.dp(8)))
        confirmar.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_DONE) { enviar(); true } else false }
        senha.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_DONE) { enviar(); true } else false }

        if (largo) {
            linha.addView(marca, LinearLayout.LayoutParams(ui.dp(380), -2))
            linha.addView(card, LinearLayout.LayoutParams(ui.dp(440), -2).apply { leftMargin = ui.dp(48) })
        } else {
            linha.addView(marca, ui.mp())
            linha.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(24) })
        }
        val pad = ui.dp(if (largo) 48 else 20)
        scroll.addView(linha, android.widget.FrameLayout.LayoutParams(-1, -2))
        raiz.addView(scroll, android.widget.FrameLayout.LayoutParams(-1, -1))
        setContentView(raiz)
        ViewCompat.setOnApplyWindowInsetsListener(raiz) { v, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left + pad, b.top + ui.dp(24), b.right + pad, b.bottom + ui.dp(24)); ins
        }
    }

    private inner class FrameLayoutComFundo : android.widget.FrameLayout(this@LoginActivity) {
        init { background = FundoNeon() }
    }

    private fun aplicarModo() {
        fun marcar(t: TextView, on: Boolean) {
            t.background = if (on) ui.fundo(Cores.alpha(Cores.VIOLETA, 0.35f), Cores.alpha(Cores.VIOLETA, 0.9f), 12f) else ui.fundo(Color.TRANSPARENT, raio = 12f)
            t.setTextColor(if (on) Cores.BRANCO else Cores.BRANCO_70)
            t.isSelected = on
            t.contentDescription = if (on) "${t.text}, selecionado" else t.text
        }
        marcar(abaCriar, criar); marcar(abaEntrar, !criar)
        (nome.parent as View).visibility = if (criar) View.VISIBLE else View.GONE
        (confirmar.parent as View).visibility = if (criar) View.VISIBLE else View.GONE
        veiculo.visibility = if (criar) View.VISIBLE else View.GONE
        senha.imeOptions = if (criar) EditorInfo.IME_ACTION_NEXT else EditorInfo.IME_ACTION_DONE
        botao.text = if (criar) "Criar conta e começar 7 dias grátis" else "Entrar"
        esqueci.visibility = if (criar) View.GONE else View.VISIBLE
        rodape.text = if (criar) "Sem cartão: são 7 dias grátis. Depois, R$ 7/mês no Pix. Seus ganhos já lançados continuam com você."
        else "Use o mesmo e-mail ou celular do cadastro."
        mostrarErro(null)
    }

    private fun mostrarErro(m: String?) {
        erro.text = m.orEmpty(); erro.visibility = if (m == null) View.GONE else View.VISIBLE
        if (m != null) erro.announceForAccessibility(m)
    }

    private fun ocupar(on: Boolean) {
        ocupado = on
        botao.isEnabled = !on; botao.alpha = if (on) 0.6f else 1f
        botao.text = if (on) "Aguarde…" else if (criar) "Criar conta e começar 7 dias grátis" else "Entrar"
    }

    private fun enviar() {
        if (ocupado) return
        val msg = ContaLogica.validar(criar, nome.text.toString(), id.text.toString(), senha.text.toString(), confirmar.text.toString())
        if (msg != null) { mostrarErro(msg); return }
        val perfil = if (criar) try {
            space.neural.luna.collaboration.DriverProfile(modelo.text.toString(), cor.text.toString(), placa.text.toString(), false, marcaVeiculo.text.toString(),
                space.neural.luna.collaboration.VehiclePowertrain.entries[tipoVeiculo.selectedItemPosition],
                space.neural.luna.collaboration.VehicleIcon.entries[iconeVeiculo.selectedItemPosition]).validated()
        } catch (e: IllegalArgumentException) { mostrarErro(e.message); return } else null
        val ident = ContaLogica.identificar(id.text.toString())!!
        mostrarErro(null); ocupar(true)
        lifecycleScope.launch {
            try {
                if (criar) Conta.criar(nome.text.toString(), ident, senha.text.toString())
                else Conta.entrar(ident, senha.text.toString())
                Licenca.limpar(this@LoginActivity) // licença é por conta: consulta de novo
                // v0.1.7: dados locais de outra conta saem; e já desce o que esta conta tem na nuvem.
                Conta.uid()?.let { TrocaConta.aoEntrar(this@LoginActivity, it) }
                if (perfil != null) runCatching {
                    space.neural.luna.collaboration.DriverProfileRepository(this@LoginActivity).save(perfil)
                }.onFailure {
                    android.widget.Toast.makeText(this@LoginActivity, "Conta criada. Veículo salvo neste aparelho; confira a sincronização em Meu veículo e privacidade.", android.widget.Toast.LENGTH_LONG).show()
                }
                space.neural.luna.sync.SyncManager.requestSync(this@LoginActivity, agora = true)
                space.neural.luna.sync.RealtimeSync.ligar(this@LoginActivity)
                startActivity(Intent(this@LoginActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            } catch (e: Conta.ErroConta) {
                ocupar(false); mostrarErro(e.message)
            }
        }
    }

    private fun esqueciSenha() {
        val ident = ContaLogica.identificar(id.text.toString())
        when (ident) {
            null -> mostrarErro("Digite primeiro o seu e-mail ou celular do cadastro.")
            is ContaLogica.Identificador.Celular -> AlertDialog.Builder(this)
                .setTitle("Conta com celular")
                .setMessage("Para contas criadas com celular, a troca de senha é feita pelo suporte. Fale com a gente no WhatsApp informando o número ${ident.exibicao}.")
                .setPositiveButton("Falar com o suporte") { _, _ -> Suporte.whatsapp(this, "Olá! Esqueci a senha do Rota GV. Minha conta é o celular ${ident.exibicao}.") }
                .setNegativeButton("Cancelar", null).show()
            is ContaLogica.Identificador.Email -> {
                ocupar(true)
                lifecycleScope.launch {
                    val m = try { Conta.redefinirSenha(ident); null } catch (e: Conta.ErroConta) { e.message }
                    ocupar(false)
                    if (m != null) mostrarErro(m)
                    else AlertDialog.Builder(this@LoginActivity).setTitle("E-mail enviado")
                        .setMessage("Se existir uma conta com ${ident.emailFirebase}, você vai receber um link para criar uma senha nova. Confira também o spam.")
                        .setPositiveButton("OK", null).show()
                }
            }
        }
    }
}
