package space.neural.luna.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

/**
 * Sincronização em segundo plano (reserva do [RealtimeSync]): roda com internet depois de cada alteração,
 * ao abrir o app, ao fazer login e de hora em hora. Faz a sincronização completa do [SyncEngine]
 * (desce, depois sobe só o que vencer no servidor).
 *
 * v0.1.7: com a conta obrigatória ligada, NÃO cria mais usuário anônimo antes do login (a 0.1.6 criava um
 * anônimo a cada instalação). Sem conta, não faz nada.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!Nuvem.firebaseOk(applicationContext)) return Result.success()
        return try {
            if (!space.neural.luna.licenca.Licenca.ativa && FirebaseAuth.getInstance().currentUser == null) {
                FirebaseAuth.getInstance().signInAnonymously().await() // só builds sem conta obrigatória
            }
            val engine = Nuvem.engine(applicationContext) ?: return Result.success()
            engine.sincronizar()
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Falha na sincronização; nova tentativa depois", e)
            Result.retry()
        }
    }

    private companion object { const val TAG = "LunaSync" }
}
