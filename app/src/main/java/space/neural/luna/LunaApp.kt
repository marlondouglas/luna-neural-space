package space.neural.luna

import android.app.Application
import space.neural.luna.sync.SyncManager

class LunaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Sem google-services.json o worker termina na hora, sem fazer nada.
        SyncManager.schedulePeriodic(this)
        SyncManager.requestSync(this)
    }
}
