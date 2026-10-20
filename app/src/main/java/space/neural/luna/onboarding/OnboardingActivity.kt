package space.neural.luna.onboarding

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import space.neural.luna.MainActivity

/**
 * Onboarding v0.1.8: modalidade (motorista de app), tipo de dispositivo (telefone/tablet/carro), modo carro.
 * Pode pular. Salva preferências e vai para MainActivity.
 */
class OnboardingActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("onboarding", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        setContentView(root)

        root.addView(TextView(this).apply {
            text = "Bem-vindo ao LUNA Neural Space"
            textSize = 22f
            setPadding(0, 0, 0, 32)
        })

        root.addView(TextView(this).apply { text = "Qual sua modalidade?"; textSize = 16f; setPadding(0, 16, 0, 8) })
        val rgMod = RadioGroup(this)
        val rbApp = RadioButton(this).apply { text = "Motorista de aplicativo (99 / Uber / InDrive)"; id = 1 }
        val rbOutro = RadioButton(this).apply { text = "Outro / Particular"; id = 2 }
        rgMod.addView(rbApp); rgMod.addView(rbOutro)
        rgMod.check(1)
        root.addView(rgMod)

        root.addView(TextView(this).apply { text = "Tipo de dispositivo"; textSize = 16f; setPadding(0, 24, 0, 8) })
        val rgDev = RadioGroup(this)
        val rbPhone = RadioButton(this).apply { text = "Telefone"; id = 10 }
        val rbTablet = RadioButton(this).apply { text = "Tablet / Tela grande"; id = 11 }
        val rbCar = RadioButton(this).apply { text = "Modo Carro (Geely / Flyme Auto)"; id = 12 }
        rgDev.addView(rbPhone); rgDev.addView(rbTablet); rgDev.addView(rbCar)
        rgDev.check(10)
        root.addView(rgDev)

        val btnOk = Button(this).apply {
            text = "Continuar"
            setOnClickListener {
                val mod = if (rgMod.checkedRadioButtonId == 1) "APP" else "OUTRO"
                val dev = when (rgDev.checkedRadioButtonId) {
                    11 -> "TABLET"
                    12 -> "CARRO"
                    else -> "PHONE"
                }
                prefs.edit()
                    .putBoolean("done", true)
                    .putString("modalidade", mod)
                    .putString("dispositivo", dev)
                    .apply()
                startActivity(Intent(this@OnboardingActivity, MainActivity::class.java))
                finish()
            }
        }
        root.addView(btnOk)

        val btnSkip = Button(this).apply {
            text = "Pular"
            setOnClickListener {
                prefs.edit().putBoolean("done", true).apply()
                startActivity(Intent(this@OnboardingActivity, MainActivity::class.java))
                finish()
            }
        }
        root.addView(btnSkip)
    }
}
