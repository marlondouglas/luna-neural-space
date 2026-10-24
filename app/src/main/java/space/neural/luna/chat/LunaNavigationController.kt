package space.neural.luna.chat

import android.app.Activity
import android.content.Intent
import space.neural.luna.MainActivity
import space.neural.luna.config.ConfiguracoesActivity
import space.neural.luna.config.PlanejamentoActivity
import space.neural.luna.pages.MapRadioActivity
import space.neural.luna.pages.ParceirosActivity

object LunaNavigationController {
    fun open(activity: Activity, destination: String?) {
        val target = when (destination) {
            "mapa" -> MapRadioActivity::class.java
            "parceiros" -> ParceirosActivity::class.java
            "inicio" -> MainActivity::class.java
            "planejamento" -> PlanejamentoActivity::class.java
            "configuracoes" -> ConfiguracoesActivity::class.java
            else -> return
        }
        activity.startActivity(Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }
}
