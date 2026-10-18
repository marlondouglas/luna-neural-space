package space.neural.luna.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Agenda a sincronização com o Firestore. Só roda quando há internet. */
object SyncManager {
    private const val ONE_TIME = "luna-sync-now"
    private const val PERIODIC = "luna-sync-periodic"

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * Pede uma sincronização (após cada alteração local, ao abrir o app e ao fazer login).
     * Com o tempo real ligado, a alteração já sobe na hora; o worker fica como reserva, 20 s depois.
     */
    fun requestSync(context: Context, agora: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .apply { if (!agora && RealtimeSync.ativo) setInitialDelay(20, TimeUnit.SECONDS) }
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        if (!agora) RealtimeSync.pedirEnvio()
    }

    /** Sincronização periódica, para trazer dados da nuvem mesmo sem alterações locais. */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
