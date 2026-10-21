package space.neural.luna.config

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import space.neural.luna.BuildConfig
import space.neural.luna.R
import space.neural.luna.chat.ChatActivity
import space.neural.luna.dashboard.AnotacoesActivity
import space.neural.luna.dashboard.Cores
import space.neural.luna.dashboard.Dialogos
import space.neural.luna.dashboard.HistoricoActivity
import space.neural.luna.dashboard.LancamentosActivity
import space.neural.luna.dashboard.Ui
import space.neural.luna.dashboard.telaSimples
import space.neural.luna.data.repo.LunaRepositories
import space.neural.luna.update.AtualizacaoActivity

/**
 * Configurações (v0.1.5): substitui o menu de três pontinhos da tela inicial.
 * Grupos em cards de vidro: Luna, Lançamentos, Planejamento e Sistema.
 */
class ConfiguracoesActivity : AppCompatActivity() {
    private lateinit var ui: Ui
    private val prefs by lazy { getSharedPreferences("dashboard", 0) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val dlg = Dialogos(this, LunaRepositories(this), lifecycleScope)
        val (raiz, c) = telaSimples(this, ui, "Configurações")

        grupo(c, R.drawable.ic_chat, "Luna", Cores.MAGENTA) { g ->
            item(g, R.drawable.ic_chat, "Conversar com a Luna", "Abre a conversa completa") { startActivity(Intent(this, ChatActivity::class.java)) }
            chave(g, R.drawable.ic_volume, "Luna fala os alertas", "Meta batida, R$/hora baixo e outros avisos em voz", "voz_alertas", true)
            chave(g, R.drawable.ic_mic, "Conversa contínua", "Depois de responder, a Luna volta a ouvir até você ficar em silêncio ou dizer “tchau”", "conversa_continua", true)
            chave(g, R.drawable.ic_mic, "Interromper a Luna falando", "Se você começar a falar, ela para e escuta (experimental)", "barge_in", true)
        }
        grupo(c, R.drawable.ic_wallet, "Lançamentos", Cores.CIANO) { g ->
            item(g, R.drawable.ic_trending, "Histórico", "Dias concluídos e turnos") { startActivity(Intent(this, HistoricoActivity::class.java)) }
            item(g, R.drawable.ic_calendar, "Lançar dia esquecido", "Escolha o dia no calendário") {
                dlg.escolherDia { startActivity(LancamentosActivity.dia(this, it.toString())) }
            }
            item(g, R.drawable.ic_news, "Anotações", "Suas notas rápidas") { startActivity(Intent(this, AnotacoesActivity::class.java)) }
        }
        grupo(c, R.drawable.ic_flag, "Planejamento", Cores.VIOLETA) { g ->
            item(g, R.drawable.ic_flag, "Planejamento e metas", "Dias de trabalho, contas fixas, limites e metas") { startActivity(Intent(this, PlanejamentoActivity::class.java)) }
        }
        if (space.neural.luna.licenca.Licenca.ativa) grupo(c, R.drawable.ic_wallet, "Conta e assinatura", Cores.AMARELO) { g ->
            val conta = space.neural.luna.conta.Conta
            item(g, R.drawable.ic_wallet, "Assinatura R$ 7/mês", "Pix, código da conta e \"Já paguei\"") { space.neural.luna.licenca.LicencaActivity.assinar(this) }
            item(g, R.drawable.ic_close, "Sair da conta", listOf(conta.nome(), conta.exibicao()).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Entrar com outra conta" }) {
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Sair da conta?")
                    .setMessage("Seus lançamentos ficam guardados. O teste grátis e a assinatura continuam presos à conta.")
                    .setPositiveButton("Sair") { _, _ -> sairDaConta() }.setNegativeButton("Cancelar", null).show()
            }
        }
        grupo(c, R.drawable.ic_info, "Mapa e Rádio", Cores.CIANO) { g ->
            item(g, R.drawable.ic_info, "Mapa e Rádio", "Mapa + voz LiveKit na mesma tela") { startActivity(Intent(this, space.neural.luna.pages.MapRadioActivity::class.java)) }
            item(g, R.drawable.ic_mic, "Rádio dos Motoristas", "Voz LiveKit entre motoristas") { startActivity(Intent(this, space.neural.luna.pages.RadioActivity::class.java)) }
            item(g, R.drawable.ic_chat, "Página Luna", "Assistente de voz dedicado") { startActivity(Intent(this, space.neural.luna.pages.LunaPageActivity::class.java)) }
        }
        grupo(c, R.drawable.ic_info, "Sistema", Cores.ESMERALDA) { g ->
            item(g, R.drawable.ic_refresh, getString(R.string.atualizacao), "Procurar nova versão do app") { startActivity(Intent(this, AtualizacaoActivity::class.java)) }
            val v = ui.hbox().apply { minimumHeight = ui.dp(48) }
            v.addView(ui.icone(R.drawable.ic_info, Cores.BRANCO_70, 20))
            v.addView(ui.text("Versão ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", 15f, Cores.BRANCO_70), ui.peso(1f, ui.dp(12)))
            g.addView(v, ui.mp(ui.dp(4)))
        }
        setContentView(raiz)
    }

    private fun grupo(c: LinearLayout, ic: Int, titulo: String, cor: Int, f: (LinearLayout) -> Unit) {
        val card = ui.card(acento = cor)
        card.addView(ui.tituloIcone(ic, titulo, cor))
        f(card)
        c.addView(card, ui.mp(if (c.childCount == 0) 0 else ui.dp(14)))
    }

    private fun linha(g: LinearLayout, ic: Int, titulo: String, sub: String): LinearLayout {
        val l = ui.hbox().apply { minimumHeight = ui.dp(56); isClickable = true; isFocusable = true; setPadding(0, ui.dp(6), 0, ui.dp(6)) }
        l.background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Cores.alpha(Cores.BRANCO, 0.18f)), null,
            android.graphics.drawable.ColorDrawable(Cores.BRANCO))
        l.addView(ui.icone(ic, Cores.BRANCO, 22))
        val t = ui.vbox()
        t.addView(ui.text(titulo, 16f, Cores.BRANCO, bold = true))
        t.addView(ui.text(sub, 13f, Cores.BRANCO_70), ui.mp(ui.dp(2)))
        l.addView(t, ui.peso(1f, ui.dp(12)))
        g.addView(l, ui.mp(ui.dp(8)))
        return l
    }

    private fun item(g: LinearLayout, ic: Int, titulo: String, sub: String, f: () -> Unit) {
        val l = linha(g, ic, titulo, sub)
        l.addView(ui.text("›", 22f, Cores.BRANCO_70))
        l.contentDescription = "$titulo. $sub"
        l.setOnClickListener { f() }
    }

    private fun chave(g: LinearLayout, ic: Int, titulo: String, sub: String, pref: String, padrao: Boolean) {
        val l = linha(g, ic, titulo, sub)
        val sw = android.widget.Switch(this).apply {
            isChecked = prefs.getBoolean(pref, padrao)
            thumbTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Cores.CIANO, Cores.BRANCO_70))
            trackTintList = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Cores.alpha(Cores.CIANO, 0.5f), Cores.alpha(Cores.BRANCO, 0.25f)))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(pref, v).apply(); atualizar(l, titulo, v) }
        }
        l.addView(sw)
        atualizar(l, titulo, sw.isChecked)
        l.setOnClickListener { sw.toggle() }
    }

    private fun atualizar(l: View, titulo: String, v: Boolean) { l.contentDescription = "$titulo: ${if (v) "ligado" else "desligado"}" }

    /**
     * v0.1.7: antes de sair, sobe o que ainda não foi para a nuvem. Se não der (sem internet), avisa: se outra
     * conta entrar neste aparelho, os dados locais são apagados (TrocaConta) e o pendente se perderia.
     */
    private fun sairDaConta() {
        lifecycleScope.launch {
            val db = space.neural.luna.data.local.LunaDatabase.get(this@ConfiguracoesActivity)
            var pend = db.syncQueries().pendentes()
            if (pend > 0) {
                android.widget.Toast.makeText(this@ConfiguracoesActivity, "Enviando seus lançamentos para a nuvem…", android.widget.Toast.LENGTH_SHORT).show()
                space.neural.luna.sync.RealtimeSync.enviarAntesDeSair(this@ConfiguracoesActivity)
                pend = db.syncQueries().pendentes()
            }
            if (pend == 0) { sairAgora(); return@launch }
            androidx.appcompat.app.AlertDialog.Builder(this@ConfiguracoesActivity).setTitle("Ainda não foi tudo para a nuvem")
                .setMessage("${if (pend == 1) "1 lançamento ainda não foi enviado" else "$pend lançamentos ainda não foram enviados"} (sem internet).\n\n" +
                    "Se você sair e entrar com OUTRA conta neste aparelho, eles serão apagados daqui. Entrando de novo com esta mesma conta, nada se perde.\n\n" +
                    "O melhor é conectar à internet e tentar de novo.")
                .setPositiveButton("Sair mesmo assim") { _, _ -> sairAgora() }
                .setNegativeButton("Cancelar", null).show()
        }
    }

    private fun sairAgora() {
        space.neural.luna.licenca.Licenca.limpar(this); space.neural.luna.conta.Conta.sair()
        space.neural.luna.conta.LoginActivity.abrir(this, entrar = true)
    }
}
