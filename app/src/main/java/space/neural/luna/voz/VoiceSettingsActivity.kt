package space.neural.luna.voz

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import space.neural.luna.avatar.LunaVoz
import space.neural.luna.dashboard.Cores
import space.neural.luna.dashboard.Ui

/** Explicit offline model installation/selection. Never requests microphone access. */
class VoiceSettingsActivity : AppCompatActivity() {
    private lateinit var ui: Ui
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private var installation: Job? = null
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); ui = Ui(this); render() }
    private fun render() {
        val body = ui.vbox(ui.dp(16)).apply { setBackgroundColor(Cores.OBSIDIAN) }
        body.addView(ui.botao("Voltar", Ui.Estilo.CONTORNO) { finish() }, ui.mp())
        body.addView(ui.text("Voz local da Luna", 24f, Cores.BRANCO, true), ui.mp(ui.dp(14)))
        body.addView(ui.text("Supertonic 3 · Português · speaker 0", 18f, Cores.CIANO, true), ui.mp(ui.dp(10)))
        body.addView(ui.text("Esta é a voz escolhida. Usa texto variável, sem Google, nuvem ou troca automática de voz. O modelo vem nesta atualização e precisa de cerca de 145 MB adicionais de armazenamento privado; não há download nesta tela. O desempenho e a qualidade dependem do aparelho.", 15f, Cores.BRANCO_70), ui.mp(ui.dp(10)))
        status = ui.text(if (SupertonicVoice.selected(this)) "Voz selecionada. ${LocalVoiceRuntime.state}" else "Voz desativada. Instale/verifique e ative quando desejar.", 15f, Cores.BRANCO)
        body.addView(status, ui.mp(ui.dp(10)))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        body.addView(progress, ui.mp(ui.dp(8)))
        body.addView(ui.botao("Instalar/verificar e ativar voz", Ui.Estilo.CHEIO_VIOLETA) {
            if (installation?.isActive == true) return@botao
            AlertDialog.Builder(this).setTitle("Ativar voz offline?")
                .setMessage("Copiar e validar cerca de 145 MB do pacote desta atualização para os arquivos privados do app? Nada será enviado à internet. Pesos e preset: OpenRAIL-M; motor Sherpa: Apache-2.0; código de exemplo: MIT. A licença MIT do exemplo não substitui a dos pesos.")
                .setPositiveButton("Copiar, verificar e ativar") { _, _ -> install() }.setNegativeButton("Cancelar", null).show()
        }, ui.mp(ui.dp(10)))
        body.addView(ui.botao("Cancelar cópia em andamento", Ui.Estilo.CONTORNO) { installation?.cancel(); status.text = "Cópia cancelada. Sua voz anterior selecionada permanece inalterada." }, ui.mp(ui.dp(8)))
        body.addView(ui.botao("Ouvir exemplo na voz escolhida", Ui.Estilo.CONTORNO) {
            SupertonicVoice.restore(this)
            if (!LocalVoiceRuntime.available) { status.text = "Instale e ative a voz primeiro."; return@botao }
            LunaVoz.falar(this, "Registrar gasto de vinte reais e cinquenta centavos em combustível? Em duzentos metros, vire à direita.")
            status.text = "Preparando exemplo local. Esta ação não abre o microfone."
        }, ui.mp(ui.dp(8)))
        body.addView(ui.botao("Parar exemplo", Ui.Estilo.CONTORNO) { LunaVoz.parar() }, ui.mp(ui.dp(8)))
        body.addView(ui.botao("Desativar voz", Ui.Estilo.CONTORNO) {
            installation?.cancel(); LunaVoz.parar(); SupertonicVoice.select(this, false)
            status.text = "Voz desativada. Os modelos verificados continuam guardados neste aparelho."
        }, ui.mp(ui.dp(8)))
        body.addView(ui.botao("Licenças e fontes", Ui.Estilo.CONTORNO) {
            lifecycleScope.launch {
                val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    assets.open("supertonic3-notices/ATTRIBUTION.txt").bufferedReader().use { it.readText() } + "\n\n" +
                        assets.open("supertonic3-notices/MODEL_OPENRAIL_M.txt").bufferedReader().use { it.readText() }
                }
                AlertDialog.Builder(this@VoiceSettingsActivity).setTitle("Supertonic 3: licença dos pesos").setMessage(text).setPositiveButton("Fechar", null).show()
            }
        }, ui.mp(ui.dp(8)))
        val scroll = ScrollView(this).apply { addView(body); isFillViewport = true }; setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val edges = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(edges.left, edges.top, edges.right, edges.bottom); insets
        }
    }
    private fun install() {
        progress.visibility = View.VISIBLE; progress.progress = 0; status.text = "Copiando e verificando os modelos locais…"
        installation = lifecycleScope.launch {
            try {
                SupertonicVoice.installBundled(this@VoiceSettingsActivity) { value -> runOnUiThread { progress.progress = value } }
                SupertonicVoice.select(this@VoiceSettingsActivity, true)
                status.text = "Modelos verificados e voz ativada. Toque em Ouvir exemplo para testar neste aparelho."
            } catch (cancel: kotlinx.coroutines.CancellationException) { status.text = "Instalação cancelada; arquivos temporários descartados."; throw cancel }
            catch (error: Exception) { status.text = error.message ?: "Falha ao instalar a voz. Nenhum outro motor foi selecionado." }
            finally { progress.visibility = View.GONE }
        }
    }
}
