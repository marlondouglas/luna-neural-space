package space.neural.luna.licenca

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.launch
import space.neural.luna.MainActivity
import space.neural.luna.R
import space.neural.luna.conta.Conta
import space.neural.luna.conta.LoginActivity
import space.neural.luna.conta.Suporte
import space.neural.luna.dashboard.Cores
import space.neural.luna.dashboard.FundoNeon
import space.neural.luna.dashboard.Ui
import space.neural.luna.update.AtualizacaoActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tela de licença: "Seu teste grátis acabou" (Pix R$ 7/mês + "Já paguei"), "Atualize o app",
 * "Conecte-se à internet", limite de aparelhos e "Assine agora" (aberta pelo aviso do teste).
 * Nos estados de bloqueio, voltar não leva ao app.
 */
class LicencaActivity : AppCompatActivity() {
    private lateinit var ui: Ui
    private lateinit var conteudo: LinearLayout
    private var assinar = false
    private var verificando = false
    private var decisao: LicencaLogica.Decisao? = null

    companion object {
        const val EXTRA_ASSINAR = "assinar"
        fun bloquear(ctx: Context) = ctx.startActivity(Intent(ctx, LicencaActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        fun assinar(ctx: Context) = ctx.startActivity(Intent(ctx, LicencaActivity::class.java).putExtra(EXTRA_ASSINAR, true))
        /** Para testes/prints: pula a consulta ao servidor ao abrir. */
        @Volatile var semRede = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        assinar = intent.getBooleanExtra(EXTRA_ASSINAR, false)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (assinar) finish() else moveTaskToBack(true)
            }
        })
        val raiz = FrameLayout(this).apply { background = FundoNeon() }
        conteudo = ui.vbox()
        val scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(conteudo) }
        raiz.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        setContentView(raiz)
        val largo = resources.configuration.screenWidthDp >= 720
        val pad = ui.dp(if (largo) 40 else 20)
        ViewCompat.setOnApplyWindowInsetsListener(raiz) { v, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left + pad, b.top + ui.dp(20), b.right + pad, b.bottom + ui.dp(20)); ins
        }
        desenhar(Licenca.decidir(this))
    }

    override fun onResume() {
        super.onResume()
        if (!semRede && Licenca.ativa && (Licenca.precisaRevalidar(this) || decisao?.liberado == false)) verificar(silencioso = true)
    }

    private fun irParaApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    private fun verificar(silencioso: Boolean) {
        if (verificando) return
        verificando = true
        if (!silencioso || decisao is LicencaLogica.Decisao.SemConexao) desenharVerificando()
        lifecycleScope.launch {
            val r = Licenca.verificar(this@LicencaActivity)
            verificando = false
            when (r) {
                is Licenca.Resultado.Ok -> {
                    if (r.decisao.liberado && !assinar) { irParaApp(); return@launch }
                    if (r.decisao is LicencaLogica.Decisao.Pago && assinar) {
                        Toast.makeText(this@LicencaActivity, "Assinatura ativa. Obrigado!", Toast.LENGTH_LONG).show(); finish(); return@launch
                    }
                    desenhar(r.decisao)
                    if (!silencioso && r.decisao is LicencaLogica.Decisao.Expirado) Toast.makeText(this@LicencaActivity, "Ainda não recebemos a confirmação do Pix.", Toast.LENGTH_LONG).show()
                }
                is Licenca.Resultado.Erro -> {
                    if (r.semConta) { Conta.sair(); LoginActivity.abrir(this@LicencaActivity, entrar = true); finish(); return@launch }
                    desenhar(Licenca.decidir(this@LicencaActivity))
                    if (!silencioso) Toast.makeText(this@LicencaActivity, r.mensagem, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ---------------- telas ----------------
    private fun limpar(): LinearLayout {
        conteudo.removeAllViews()
        conteudo.gravity = Gravity.CENTER_HORIZONTAL
        return conteudo
    }

    private fun topo(c: LinearLayout, fechar: Boolean) {
        val l = ui.hbox()
        l.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher_round); contentDescription = null }, LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)))
        l.addView(ui.text("Rota GV", 18f, Cores.BRANCO, bold = true), ui.peso(1f, ui.dp(10)))
        if (fechar) l.addView(ui.botaoIcone(R.drawable.ic_close, "Fechar") { finish() })
        c.addView(l, ui.mp())
    }

    private fun desenharVerificando() {
        val c = limpar()
        c.gravity = Gravity.CENTER
        c.addView(ProgressBar(this).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(Cores.VIOLETA) }, ui.wc())
        c.addView(ui.text("Confirmando sua assinatura…", 17f, Cores.BRANCO_70).apply { gravity = Gravity.CENTER }, ui.wc().apply { topMargin = ui.dp(16) })
    }

    private fun desenhar(d: LicencaLogica.Decisao) {
        decisao = d
        if (assinar && d !is LicencaLogica.Decisao.Expirado && d !is LicencaLogica.Decisao.Teste && d !is LicencaLogica.Decisao.Pago) assinar = false
        when (d) {
            is LicencaLogica.Decisao.Expirado -> paywall(d.codigo, null)
            is LicencaLogica.Decisao.Teste -> if (assinar) paywall(d.codigo, d) else irParaApp()
            is LicencaLogica.Decisao.Pago -> if (assinar) pago(d) else irParaApp()
            is LicencaLogica.Decisao.Atualizar -> mensagem(
                "Atualize o app", "Esta versão do Rota GV não é mais aceita. Baixe a versão nova para continuar; seus ganhos e sua conta ficam guardados.",
                "Atualizar agora", { startActivity(Intent(this, AtualizacaoActivity::class.java)) },
                "Abrir página de download", { abrirUrl(d.url) }, Cores.CIANO,
            )
            is LicencaLogica.Decisao.LimiteAparelhos -> mensagem(
                "Conta em uso em outros celulares", "Esta conta já está ativa no número máximo de aparelhos. Se você trocou de celular, fale com o suporte para liberar este.",
                "Falar com o suporte", { Suporte.whatsapp(this, "Olá! Troquei de celular e preciso liberar minha conta do Rota GV (código ${Licenca.codigo(this)}).") },
                "Sair da conta", { sair() }, Cores.LARANJA,
            )
            is LicencaLogica.Decisao.SemConexao -> if (d.relogioAlterado) mensagem(
                "Confira a data e a hora", "A data do celular parece ter sido alterada. Ative \"Data e hora automáticas\" nas configurações do Android e conecte-se à internet.",
                "Tentar de novo", { verificar(false) }, "Abrir configurações de data", {
                    runCatching { startActivity(Intent(android.provider.Settings.ACTION_DATE_SETTINGS)) }
                }, Cores.LARANJA,
            ) else mensagem(
                "Conecte-se à internet", "Precisamos confirmar sua conta e seu teste grátis. Ligue os dados móveis ou o Wi-Fi e toque em tentar de novo.",
                "Tentar de novo", { verificar(false) }, null, null, Cores.CIANO,
            )
        }
    }

    private fun mensagem(titulo: String, texto: String, b1: String, f1: () -> Unit, b2: String?, f2: (() -> Unit)?, cor: Int) {
        val c = limpar()
        val col = ui.vbox()
        topo(col, fechar = false)
        val card = ui.card(destaque = true, acento = cor)
        card.addView(ui.text(titulo, 24f, Cores.BRANCO, bold = true))
        card.addView(ui.text(texto, 16f, Cores.BRANCO_70), ui.mp(ui.dp(10)))
        card.addView(ui.botao(b1, Ui.Estilo.CHEIO_VIOLETA, alto = true) { f1() }, ui.mp(ui.dp(20)))
        if (b2 != null && f2 != null) card.addView(ui.botao(b2, Ui.Estilo.CONTORNO) { f2() }, ui.mp(ui.dp(10)))
        col.addView(card, ui.mp(ui.dp(40)))
        col.addView(contaRodape(), ui.mp(ui.dp(16)))
        c.addView(col, LinearLayout.LayoutParams(if (resources.configuration.screenWidthDp > 600) ui.dp(520) else -1, -2))
    }

    private fun pago(d: LicencaLogica.Decisao.Pago) {
        mensagem("Assinatura ativa", "Sua assinatura está em dia até ${data(d.ate)}. Obrigado por apoiar o Rota GV!",
            "Voltar", { finish() }, null, null, Cores.ESMERALDA)
    }

    private fun paywall(codigo: String, teste: LicencaLogica.Decisao.Teste?) {
        val c = limpar()
        val largo = resources.configuration.screenWidthDp >= 720
        val col = ui.vbox()
        topo(col, fechar = assinar)

        val titulo = if (teste != null) "Assine o Rota GV" else "Seu teste grátis acabou"
        val sub = if (teste != null) "Faltam ${teste.diasRestantes} ${if (teste.diasRestantes == 1) "dia" else "dias"} do seu teste grátis. Assine agora e continue sem interrupção."
        else "Continue com seus ganhos, metas e a Luna por apenas R$ 7 por mês."

        val esquerda = ui.vbox()
        esquerda.addView(ui.text(titulo, if (largo) 30f else 26f, Cores.BRANCO, bold = true))
        esquerda.addView(ui.text(sub, 16f, Cores.BRANCO_70), ui.mp(ui.dp(8)))
        // Plano
        val plano = ui.card(destaque = true, acento = Cores.ESMERALDA)
        val pl = ui.hbox()
        val pt = ui.vbox()
        pt.addView(ui.text("PLANO MENSAL", 12f, Cores.ESMERALDA, bold = true).apply { letterSpacing = 0.08f })
        pt.addView(ui.text("Tudo do Rota GV liberado", 15f, Cores.BRANCO_70), ui.mp(ui.dp(2)))
        pl.addView(pt, ui.peso(1f))
        pl.addView(ui.text("R$ 7", 34f, Cores.BRANCO, bold = true))
        pl.addView(ui.text("/mês", 15f, Cores.BRANCO_70), ui.wc(ui.dp(4)))
        plano.addView(pl, ui.mp())
        listOf("Luna por voz e na conversa", "Ganhos, metas, turnos e relatórios", "Nota fiscal por foto e limites de gastos").forEach {
            val l = ui.hbox()
            l.addView(ui.icone(R.drawable.ic_check, Cores.ESMERALDA, 18))
            l.addView(ui.text(it, 15f, Cores.BRANCO), ui.peso(1f, ui.dp(10)))
            plano.addView(l, ui.mp(ui.dp(10)))
        }
        esquerda.addView(plano, ui.mp(ui.dp(18)))
        // Passos
        val passos = ui.vbox()
        listOf("Pague R$ 7,00 no Pix (QR ou chave).", "Toque em \"Já paguei\".", "Liberamos sua conta assim que o Pix for confirmado.").forEachIndexed { i, s ->
            val l = ui.hbox()
            l.addView(ui.text("${i + 1}", 13f, Cores.OBSIDIAN, bold = true).apply {
                gravity = Gravity.CENTER; background = ui.fundo(Cores.VIOLETA, raio = 12f)
            }, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)))
            l.addView(ui.text(s, 15f, Cores.BRANCO_70), ui.peso(1f, ui.dp(10)))
            passos.addView(l, ui.mp(if (i == 0) 0 else ui.dp(8)))
        }
        esquerda.addView(passos, ui.mp(ui.dp(18)))

        // Pix
        val pix = ui.card(acento = Cores.CIANO)
        pix.gravity = Gravity.CENTER_HORIZONTAL
        pix.addView(ui.tituloIcone(R.drawable.ic_wallet, "Pague com Pix", Cores.CIANO), ui.mp())
        val payload = PixBrCode.payload(txid = PixBrCode.txid(codigo))
        qr(payload, ui.dp(if (largo) 220 else 200))?.let { bmp ->
            pix.addView(ImageView(this).apply {
                setImageBitmap(bmp); contentDescription = "QR Code Pix de R$ 7,00"
                background = ui.fundo(Color.WHITE, raio = 14f); setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
            }, LinearLayout.LayoutParams(ui.dp(if (largo) 240 else 220), ui.dp(if (largo) 240 else 220)).apply { topMargin = ui.dp(12) })
        }
        pix.addView(ui.text("Chave Pix (celular)", 13f, Cores.BRANCO_70).apply { gravity = Gravity.CENTER }, ui.mp(ui.dp(12)))
        pix.addView(ui.text(PixBrCode.CHAVE_EXIBICAO, 22f, Cores.BRANCO, bold = true).apply { gravity = Gravity.CENTER; setTextIsSelectable(true) }, ui.mp(ui.dp(2)))
        pix.addView(ui.text("Valor: R$ 7,00", 14f, Cores.BRANCO_70).apply { gravity = Gravity.CENTER }, ui.mp(ui.dp(2)))
        val bts = ui.hbox()
        bts.addView(ui.botao("Copiar chave", Ui.Estilo.CONTORNO) { copiar("Chave Pix", "33987044866", "Chave Pix copiada") }, ui.peso(1f))
        bts.addView(ui.botao("Pix copia e cola", Ui.Estilo.CONTORNO) { copiar("Pix copia e cola", payload, "Código Pix copiado. Cole no app do seu banco.") }, ui.peso(1f, ui.dp(8)))
        pix.addView(bts, ui.mp(ui.dp(12)))
        val cod = ui.hbox().apply { gravity = Gravity.CENTER }
        cod.addView(ui.text("Seu código: ", 14f, Cores.BRANCO_70))
        cod.addView(ui.mono(codigo, 16f, Cores.AMARELO))
        pix.addView(cod, ui.mp(ui.dp(12)))

        val acoes = ui.vbox()
        acoes.addView(ui.botao("Já paguei", Ui.Estilo.CHEIO_ESMERALDA, alto = true) { jaPaguei() }, ui.mp())
        acoes.addView(ui.botao("Verificar liberação", Ui.Estilo.CONTORNO) { verificar(false) }, ui.mp(ui.dp(10)))

        if (largo) {
            val l = ui.hbox().apply { gravity = Gravity.TOP }
            esquerda.addView(acoes, ui.mp(ui.dp(20)))
            l.addView(esquerda, ui.peso(1.1f))
            val dir = ui.vbox(); dir.addView(pix, ui.mp())
            l.addView(dir, ui.peso(1f, ui.dp(32)))
            col.addView(l, ui.mp(ui.dp(24)))
        } else {
            col.addView(esquerda, ui.mp(ui.dp(20)))
            col.addView(pix, ui.mp(ui.dp(16)))
            col.addView(acoes, ui.mp(ui.dp(16)))
        }
        col.addView(contaRodape(), ui.mp(ui.dp(16)))
        c.addView(col, LinearLayout.LayoutParams(if (resources.configuration.screenWidthDp in 601..719) ui.dp(560) else -1, -2))
    }

    private fun contaRodape(): View {
        val l = ui.vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val quem = Conta.exibicao().ifBlank { null }
        if (quem != null) l.addView(ui.text("Conta: $quem", 13f, Cores.BRANCO_40).apply { gravity = Gravity.CENTER })
        val bts = ui.hbox().apply { gravity = Gravity.CENTER }
        bts.addView(ui.botao("Suporte", Ui.Estilo.TEXTO) { Suporte.whatsapp(this, "Olá! Preciso de ajuda com o Rota GV (código ${Licenca.codigo(this)}).") })
        if (quem != null || !semRede) bts.addView(ui.botao("Sair da conta", Ui.Estilo.TEXTO) { sair() })
        l.addView(bts, ui.mp())
        return l
    }

    private fun sair() {
        AlertDialog.Builder(this).setTitle("Sair da conta?")
            .setMessage("Você pode entrar de novo com o mesmo e-mail ou celular. O teste grátis e a assinatura continuam presos à conta.")
            .setPositiveButton("Sair") { _, _ -> Licenca.limpar(this); Conta.sair(); LoginActivity.abrir(this, entrar = true); finish() }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun jaPaguei() {
        lifecycleScope.launch {
            val codigo = Licenca.avisarPagamento(this@LicencaActivity) ?: Licenca.codigo(this@LicencaActivity)
            AlertDialog.Builder(this@LicencaActivity)
                .setTitle("Recebemos seu aviso")
                .setMessage("Seu código é $codigo. Assim que o Pix de R$ 7,00 for confirmado, sua conta é liberada e o app abre normalmente. Se quiser agilizar, mande o comprovante no WhatsApp.")
                .setPositiveButton("Enviar comprovante") { _, _ -> Suporte.whatsapp(this@LicencaActivity, "Olá! Paguei a assinatura do Rota GV (R$ 7,00). Meu código: $codigo. Segue o comprovante.") }
                .setNegativeButton("OK", null).show()
        }
    }

    private fun copiar(rotulo: String, texto: String, aviso: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(rotulo, texto))
        Toast.makeText(this, aviso, Toast.LENGTH_SHORT).show()
    }

    private fun abrirUrl(url: String) {
        val u = url.ifBlank { "https://marlondouglas.github.io/luna-neural-space/download.html" }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
    }

    private fun data(ms: Long) = SimpleDateFormat("dd/MM/yyyy", Locale.forLanguageTag("pt-BR")).format(Date(ms))

    private fun qr(texto: String, tamanho: Int): Bitmap? = runCatching {
        val m = QRCodeWriter().encode(texto, BarcodeFormat.QR_CODE, tamanho, tamanho,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
        val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) Color.BLACK else Color.WHITE }
        Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
    }.getOrNull()

}
