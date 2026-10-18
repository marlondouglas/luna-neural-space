package space.neural.luna.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import space.neural.luna.BuildConfig
import space.neural.luna.data.local.LunaDatabase
import space.neural.luna.data.local.Syncable

/**
 * Sincroniza o banco local com o Firestore em users/{uid}/{coleção}/{id}.
 *
 * 1. Faz login anônimo (se ainda não houver usuário).
 * 2. Envia os registros com dirty = true (o campo serverUpdatedAt é preenchido pelo servidor).
 * 3. Baixa os documentos alterados desde a última sincronização (por serverUpdatedAt).
 *    Conflito: vence o registro com updatedAt mais recente; alteração local pendente
 *    mais nova não é sobrescrita.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!BuildConfig.FIREBASE_CONFIGURED) return Result.success()
        if (FirebaseApp.getApps(applicationContext).isEmpty() &&
            FirebaseApp.initializeApp(applicationContext) == null
        ) {
            return Result.success()
        }

        return try {
            val auth = FirebaseAuth.getInstance()
            val uid = auth.currentUser?.uid ?: auth.signInAnonymously().await().user?.uid
                ?: return Result.retry()
            val userDoc = FirebaseFirestore.getInstance().collection("users").document(uid)
            val prefs = applicationContext.getSharedPreferences("luna_sync_$uid", Context.MODE_PRIVATE)

            for (c in collections()) {
                val ref = userDoc.collection(c.name)

                // Envio
                for (item in c.dirty()) {
                    val data = c.toMap(item) + ("serverUpdatedAt" to FieldValue.serverTimestamp())
                    ref.document(item.id).set(data, SetOptions.merge()).await()
                    c.markClean(item.id, item.updatedAt)
                }

                // Recebimento
                val key = "lastPull_${c.name}"
                val since = Timestamp(prefs.getLong("${key}_s", 0), prefs.getInt("${key}_n", 0))
                val snap = ref.whereGreaterThan("serverUpdatedAt", since).get().await()
                var newest = since
                for (doc in snap.documents) {
                    val remote = c.fromMap(doc.id, doc.data ?: continue) ?: continue
                    val local = c.byId(doc.id)
                    if (local == null || remote.updatedAt > local.updatedAt) c.upsert(remote)
                    doc.getTimestamp("serverUpdatedAt")?.let { if (it > newest) newest = it }
                }
                prefs.edit()
                    .putLong("${key}_s", newest.seconds)
                    .putInt("${key}_n", newest.nanoseconds)
                    .apply()
            }
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Falha na sincronização; nova tentativa depois", e)
            Result.retry()
        }
    }

    private fun collections(): List<SyncCollection<*>> {
        val db = LunaDatabase.get(applicationContext)
        return listOf(
            SyncCollection("turnos", db.turnoDao()::dirty, db.turnoDao()::markClean, db.turnoDao()::byId,
                db.turnoDao()::upsert, FirestoreMappers::turno, FirestoreMappers::turno),
            SyncCollection("ganhos", db.ganhoDao()::dirty, db.ganhoDao()::markClean, db.ganhoDao()::byId,
                db.ganhoDao()::upsert, FirestoreMappers::ganho, FirestoreMappers::ganho),
            SyncCollection("gastos", db.gastoDao()::dirty, db.gastoDao()::markClean, db.gastoDao()::byId,
                db.gastoDao()::upsert, FirestoreMappers::gasto, FirestoreMappers::gasto),
            SyncCollection("metas", db.metaDao()::dirty, db.metaDao()::markClean, db.metaDao()::byId,
                db.metaDao()::upsert, FirestoreMappers::meta, FirestoreMappers::meta),
            SyncCollection("anotacoes", db.anotacaoDao()::dirty, db.anotacaoDao()::markClean, db.anotacaoDao()::byId,
                db.anotacaoDao()::upsert, FirestoreMappers::anotacao, FirestoreMappers::anotacao),
            SyncCollection("planejamento", db.planejamentoDao()::dirty, db.planejamentoDao()::markClean, db.planejamentoDao()::byId,
                db.planejamentoDao()::upsert, FirestoreMappers::planejamento, FirestoreMappers::planejamento),
            SyncCollection("contas_fixas", db.contaFixaDao()::dirty, db.contaFixaDao()::markClean, db.contaFixaDao()::byId,
                db.contaFixaDao()::upsert, FirestoreMappers::contaFixa, FirestoreMappers::contaFixa),
            SyncCollection("limites", db.limiteDao()::dirty, db.limiteDao()::markClean, db.limiteDao()::byId,
                db.limiteDao()::upsert, FirestoreMappers::limite, FirestoreMappers::limite),
        )
    }

    private companion object { const val TAG = "LunaSync" }
}

/** Descreve como sincronizar uma coleção. */
internal class SyncCollection<T : Syncable>(
    val name: String,
    private val dirtyFn: suspend () -> List<T>,
    val markClean: suspend (String, Long) -> Unit,
    val byId: suspend (String) -> T?,
    private val upsertFn: suspend (T) -> Unit,
    private val toMapFn: (T) -> Map<String, Any?>,
    val fromMap: (String, Map<String, Any?>) -> T?,
) {
    suspend fun dirty(): List<T> = dirtyFn()
    @Suppress("UNCHECKED_CAST")
    suspend fun upsert(item: Syncable) = upsertFn(item as T)
    @Suppress("UNCHECKED_CAST")
    fun toMap(item: Syncable): Map<String, Any?> = toMapFn(item as T)
}
