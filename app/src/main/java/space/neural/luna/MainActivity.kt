package space.neural.luna

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import space.neural.luna.teste.TesteFirebaseActivity

/** As telas do app ainda serão construídas. Por enquanto só há a entrada para a tela de teste. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(getColor(R.color.obsidian))
        }
        root.addView(
            Button(this).apply {
                text = getString(R.string.teste_firebase)
                setTextColor(getColor(R.color.branco))
                setBackgroundColor(getColor(R.color.violeta))
                minHeight = (56 * resources.displayMetrics.density).toInt()
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, TesteFirebaseActivity::class.java))
                }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        setContentView(root)
    }
}
