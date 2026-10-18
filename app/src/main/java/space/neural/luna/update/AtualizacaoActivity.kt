package space.neural.luna.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import space.neural.luna.BuildConfig
import space.neural.luna.R
import java.io.File

/**
 * Tela "Atualização": consulta o último release no GitHub, compara com a versão
 * instalada e, se houver uma mais nova, baixa o APK e abre o instalador do Android.
 */
class AtualizacaoActivity : AppCompatActivity() {

    private lateinit var versoes: TextView
    private lateinit var status: TextView
    private lateinit var progresso: ProgressBar
    private lateinit var botaoBaixar: Button
    private lateinit var botaoTentar: Button

    private var release: ReleaseInfo? = null
    private var apkBaixado: File? = null
    private var job: Job? = null

    /** Volta da tela de "instalar apps desconhecidos". */
    private val permissaoLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val apk = apkBaixado
            if (apk != null && podeInstalar()) {
                abrirInstalador(apk)
            } else {
                mostrarErro(
                    "A permissão para instalar apps desconhecidos não foi dada. " +
                        "Sem ela, o Android não deixa instalar a atualização.",
                    textoTentar = "Tentar instalar de novo",
                ) { instalar() }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (24 * d).toInt()
        val mp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(getColor(R.color.obsidian))
        }
        fun texto() = TextView(this).apply {
            setTextColor(getColor(R.color.branco)); textSize = 16f; setPadding(0, 0, 0, pad / 2)
        }
        fun botao(t: String) = Button(this).apply {
            text = t
            setTextColor(getColor(R.color.branco))
            setBackgroundColor(getColor(R.color.violeta))
            minHeight = (56 * d).toInt()
            visibility = View.GONE
        }
        versoes = texto()
        status = texto()
        progresso = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE
        }
        botaoBaixar = botao("Baixar atualização")
        botaoTentar = botao("Tentar de novo")
        root.addView(versoes, mp)
        root.addView(status, mp)
        root.addView(progresso, mp)
        root.addView(botaoBaixar, mp)
        root.addView(botaoTentar, LinearLayout.LayoutParams(mp).apply { topMargin = pad / 2 })
        setContentView(root)

        verificar()
    }

    private fun versaoInstalada() = BuildConfig.VERSION_NAME

    private fun verificar() {
        job?.cancel()
        botaoBaixar.visibility = View.GONE
        botaoTentar.visibility = View.GONE
        progresso.visibility = View.GONE
        versoes.text = "Versão instalada: ${versaoInstalada()}"
        status.text = "Procurando atualização..."
        job = lifecycleScope.launch {
            try {
                val r = UpdateChecker.fetchLatest()
                release = r
                versoes.text = "Versão instalada: ${versaoInstalada()}\nÚltima versão: ${r.version} (${r.tag})"
                if (UpdateChecker.compare(r.version, versaoInstalada()) > 0) {
                    if (r.apkUrl == null) {
                        mostrarErro("Há uma versão nova, mas ela ainda não tem APK para baixar.") { verificar() }
                    } else {
                        status.text = "Há uma versão nova."
                        botaoBaixar.visibility = View.VISIBLE
                        botaoBaixar.setOnClickListener { baixar(r) }
                    }
                } else {
                    status.text = "Você já está na versão mais recente."
                }
            } catch (e: UpdateException) {
                mostrarErro(e.message ?: "Erro ao procurar atualização.") { verificar() }
            } catch (e: Exception) {
                mostrarErro("Erro ao procurar atualização: ${e.message}") { verificar() }
            }
        }
    }

    private fun pastaUpdates(): File =
        File(getExternalFilesDir(null) ?: cacheDir, "updates")

    private fun baixar(r: ReleaseInfo) {
        val url = r.apkUrl ?: return
        job?.cancel()
        botaoBaixar.visibility = View.GONE
        botaoTentar.visibility = View.GONE
        progresso.visibility = View.VISIBLE
        progresso.isIndeterminate = true
        status.text = "Baixando..."
        val dest = File(pastaUpdates(), "luna-${r.version}.apk")
        job = lifecycleScope.launch {
            try {
                UpdateChecker.download(url, dest, r.apkSize) { p ->
                    if (p < 0) {
                        progresso.isIndeterminate = true
                        status.text = "Baixando..."
                    } else {
                        progresso.isIndeterminate = false
                        progresso.progress = p
                        status.text = "Baixando... $p%"
                    }
                }
                apkBaixado = dest
                progresso.visibility = View.GONE
                status.text = "Download concluído."
                instalar()
            } catch (e: UpdateException) {
                progresso.visibility = View.GONE
                mostrarErro(e.message ?: "Falha no download.", "Tentar baixar de novo") { baixar(r) }
            } catch (e: Exception) {
                progresso.visibility = View.GONE
                if (e is kotlinx.coroutines.CancellationException) throw e
                mostrarErro("Falha no download: ${e.message}", "Tentar baixar de novo") { baixar(r) }
            }
        }
    }

    private fun podeInstalar(): Boolean = packageManager.canRequestPackageInstalls()

    private fun instalar() {
        val apk = apkBaixado ?: return
        if (podeInstalar()) {
            abrirInstalador(apk)
            return
        }
        status.text = "Para instalar a atualização, o Android precisa que você permita " +
            "\"instalar apps desconhecidos\" para o Rota GV. Abrindo as configurações..."
        try {
            permissaoLauncher.launch(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
        } catch (e: ActivityNotFoundException) {
            mostrarErro(
                "Não consegui abrir as configurações. Permita \"instalar apps desconhecidos\" " +
                    "para o Rota GV nas configurações do Android e tente de novo.",
                "Tentar instalar de novo",
            ) { instalar() }
        }
    }

    private fun abrirInstalador(apk: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            status.text = "Instalador aberto. Confirme a instalação."
            botaoTentar.text = "Abrir instalador de novo"
            botaoTentar.visibility = View.VISIBLE
            botaoTentar.setOnClickListener { instalar() }
        } catch (e: Exception) {
            mostrarErro("Não foi possível abrir o instalador: ${e.message}", "Tentar instalar de novo") { instalar() }
        }
    }

    private fun mostrarErro(msg: String, textoTentar: String = "Tentar de novo", acao: () -> Unit) {
        status.text = msg
        botaoTentar.text = textoTentar
        botaoTentar.visibility = View.VISIBLE
        botaoTentar.setOnClickListener { acao() }
    }
}
