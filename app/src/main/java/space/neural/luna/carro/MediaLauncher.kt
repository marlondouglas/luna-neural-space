package space.neural.luna.carro

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Abre apps externos (Spotify, YouTube, Waze, Google Maps) por intents oficiais.
 * Fallback claro se o app não estiver instalado.
 * PDF: não modificar sistema da Geely; usar apenas APIs autorizadas.
 */
object MediaLauncher {

    fun abrirSpotify(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:"))
            .setPackage("com.spotify.music")
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            Toast.makeText(context, "Spotify não instalado.", Toast.LENGTH_SHORT).show()
        }
    }

    fun abrirYouTube(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://youtube.com"))
            .setPackage("com.google.android.youtube")
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://youtube.com")))
        }
    }

    fun abrirWaze(context: Context, destino: String? = null) {
        val uri = if (destino != null) Uri.parse("https://waze.com/ul?q=${Uri.encode(destino)}") else Uri.parse("waze://")
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.waze")
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            Toast.makeText(context, "Waze não instalado.", Toast.LENGTH_SHORT).show()
        }
    }

    fun abrirMaps(context: Context, destino: String? = null) {
        val uri = if (destino != null) Uri.parse("geo:0,0?q=${Uri.encode(destino)}") else Uri.parse("geo:0,0")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            Toast.makeText(context, "Nenhum app de mapas encontrado.", Toast.LENGTH_SHORT).show()
        }
    }
}
