package space.neural.luna.carro

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

object MediaLauncher {
    fun abrirWaze(ctx: Context) {
        val uri = Uri.parse("waze://")
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.waze")
        runCatching { ctx.startActivity(intent) }.onFailure {
            val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://waze.com/ul"))
            runCatching { ctx.startActivity(web) }.onFailure {
                Toast.makeText(ctx, "Waze não encontrado.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
