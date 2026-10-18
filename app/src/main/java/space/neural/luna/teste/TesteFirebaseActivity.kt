package space.neural.luna.teste

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import space.neural.luna.BuildConfig
import space.neural.luna.R
import space.neural.luna.data.local.AppCorrida
import space.neural.luna.data.local.Ganho
import space.neural.luna.data.repo.LunaRepositories
import java.time.LocalDate

/**
 * Tela de teste do Firebase.
 *
 * "Lançar ganho de teste":
 * 1. faz login anônimo;
 * 2. grava um documento em users/{uid}/earnings (coleção de teste; a sincronização
 *    normal do app usa users/{uid}/ganhos);
 * 3. salva um Ganho no banco local (Room), que depois sincroniza em users/{uid}/ganhos;
 * 4. mostra o resultado ou o erro na tela.
 */
class TesteFirebaseActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var botao: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (24 * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(getColor(R.color.obsidian))
        }
        botao = Button(this).apply {
            text = getString(R.string.lancar_ganho_teste)
            setTextColor(getColor(R.color.branco))
            setBackgroundColor(getColor(R.color.violeta))
            minHeight = (56 * d).toInt()
            setOnClickListener { lancarGanhoDeTeste() }
        }
        status = TextView(this).apply {
            setTextColor(getColor(R.color.branco))
            textSize = 16f
            setPadding(0, pad, 0, 0)
            setTextIsSelectable(true)
        }
        val mp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        root.addView(botao, mp)
        root.addView(status, mp)
        setContentView(root)
    }

    private fun lancarGanhoDeTeste() {
        botao.isEnabled = false
        status.setTextColor(getColor(R.color.branco))
        status.text = "Enviando..."
        lifecycleScope.launch {
            val linhas = mutableListOf<String>()
            var ok = true
            try {
                if (!BuildConfig.FIREBASE_CONFIGURED ||
                    (FirebaseApp.getApps(applicationContext).isEmpty() &&
                        FirebaseApp.initializeApp(applicationContext) == null)
                ) {
                    error("Firebase não configurado (falta app/google-services.json no build).")
                }
                val auth = FirebaseAuth.getInstance()
                val uid = withTimeout(20_000) {
                    auth.currentUser?.uid ?: auth.signInAnonymously().await().user?.uid
                } ?: error("Login anônimo não retornou usuário.")
                linhas += "Login anônimo OK (uid: $uid)"

                val doc = mapOf(
                    "valor" to 10.00,
                    "moeda" to "BRL",
                    "descricao" to "ganho de teste",
                    "app" to "99",
                    "timestamp" to Timestamp.now(),
                )
                val ref = withTimeout(20_000) {
                    FirebaseFirestore.getInstance()
                        .collection("users").document(uid).collection("earnings")
                        .add(doc).await()
                }
                linhas += "Firestore OK: ${ref.path}"
            } catch (e: Throwable) {
                ok = false
                linhas += "Erro no Firebase: ${e::class.java.simpleName}: ${e.message}"
            }

            try {
                LunaRepositories(applicationContext).salvarGanho(
                    Ganho(
                        data = LocalDate.now().toString(),
                        app = AppCorrida.NOVENTA_E_NOVE,
                        valorCentavos = 1000,
                        descricao = "ganho de teste",
                    )
                )
                linhas += "Ganho salvo no celular (Room); sincroniza em users/{uid}/ganhos."
            } catch (e: Throwable) {
                ok = false
                linhas += "Erro ao salvar no celular: ${e::class.java.simpleName}: ${e.message}"
            }

            status.setTextColor(getColor(if (ok) R.color.esmeralda else R.color.branco))
            status.text = (if (ok) "Sucesso!\n" else "Falhou.\n") + linhas.joinToString("\n")
            botao.isEnabled = true
        }
    }
}
