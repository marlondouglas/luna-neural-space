package space.neural.luna.onboarding

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import space.neural.luna.MainActivity

/**
 * Onboarding melhorado: modalidade, dispositivo, preferências da Luna e meta inicial.
 * Pode pular. Não apaga dados existentes. Salva só preferências novas.
 */
class OnboardingActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("onboarding", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        setContentView(root)

        root.addView(TextView(this).apply {
            text = "Configuração inicial — Rota GV"
            textSize = 22f
            setPadding(0, 0, 0, 24)
        })

        root.addView(TextView(this).apply { text = "Modalidade de trabalho"; textSize = 16f; setPadding(0, 12, 0, 6) })
        val rgMod = RadioGroup(this)
        listOf("Motorista de aplicativo", "Gestão financeira / outro", "Particular").forEachIndexed { i, t ->
            rgMod.addView(RadioButton(this).apply { text = t; id = 100 + i })
        }
        rgMod.check(100)
        root.addView(rgMod)

        root.addView(TextView(this).apply { text = "Tipo de dispositivo"; textSize = 16f; setPadding(0, 20, 0, 6) })
        val rgDev = RadioGroup(this)
        listOf("Telefone", "Tablet / tela grande", "Multimídia do carro (Geely)").forEachIndexed { i, t ->
            rgDev.addView(RadioButton(this).apply { text = t; id = 200 + i })
        }
        rgDev.check(200)
        root.addView(rgDev)

        root.addView(TextView(this).apply { text = "Preferências da Luna"; textSize = 16f; setPadding(0, 20, 0, 6) })
        val chkVoz = CheckBox(this).apply { text = "Ativar respostas por voz"; isChecked = true }
        val chkTips = CheckBox(this).apply { text = "Mostrar dicas e versículos"; isChecked = true }
        root.addView(chkVoz)
        root.addView(chkTips)

        root.addView(TextView(this).apply { text = "Meta diária inicial (opcional)"; textSize = 16f; setPadding(0, 20, 0, 6) })
        // Simple note; real meta is set later in Planejamento
        root.addView(TextView(this).apply {
            text = "Você pode definir metas detalhadas depois no Planejamento."
            textSize = 14f
            setPadding(0, 0, 0, 16)
        })

        val btnOk = Button(this).apply {
            text = "Salvar e continuar"
            setOnClickListener {
                val mod = when (rgMod.checkedRadioButtonId) {
                    101 -> "FINANCEIRO"
                    102 -> "PARTICULAR"
                    else -> "APP"
                }
                val dev = when (rgDev.checkedRadioButtonId) {
                    201 -> "TABLET"
                    202 -> "CARRO"
                    else -> "PHONE"
                }
                prefs.edit()
                    .putBoolean("done", true)
                    .putString("modalidade", mod)
                    .putString("dispositivo", dev)
                    .putBoolean("voz", chkVoz.isChecked)
                    .putBoolean("tips", chkTips.isChecked)
                    .apply()
                startActivity(Intent(this@OnboardingActivity, MainActivity::class.java))
                finish()
            }
        }
        root.addView(btnOk)

        val btnSkip = Button(this).apply {
            text = "Pular por agora"
            setOnClickListener {
                prefs.edit().putBoolean("done", true).apply()
                startActivity(Intent(this@OnboardingActivity, MainActivity::class.java))
                finish()
            }
        }
        root.addView(btnSkip)
    }
}
