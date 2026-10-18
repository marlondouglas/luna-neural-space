package space.neural.luna

import android.app.Application
import space.neural.luna.sync.SyncManager

class LunaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // v0.1.6: conta obrigatória + teste grátis de 7 dias controlado pelo servidor.
        space.neural.luna.licenca.Licenca.iniciar(this)
        // Sem google-services.json o worker termina na hora, sem fazer nada.
        SyncManager.schedulePeriodic(this)
        SyncManager.requestSync(this)
    }
}
