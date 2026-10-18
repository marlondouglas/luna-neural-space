package space.neural.luna.sync

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await

/** [RemoteStore] no Firestore, em users/{uid}/... */
class FirestoreRemote(private val fs: FirebaseFirestore, uid: String) : RemoteStore {
    private val raiz = fs.collection("users").document(uid)

    private fun ref(path: String): DocumentReference = raiz.collection(path.substringBefore('/')).document(path.substringAfter('/'))

    private fun doc(path: String, s: DocumentSnapshot): RemoteStore.Doc? {
        val m = s.data ?: return null
        val ts = s.getTimestamp("serverUpdatedAt")
        val data = m.filterKeys { it != "serverUpdatedAt" }.mapValues { (_, v) -> if (v is Timestamp) v.toDate().time else v }
        return RemoteStore.Doc(path, data, ts?.let { RemoteStore.Cursor(it.seconds, it.nanoseconds) })
    }

    override suspend fun transacao(leitura: List<String>, bloco: (Map<String, RemoteStore.Doc?>) -> Map<String, Map<String, Any?>>) {
        fs.runTransaction { tx ->
            val lidos = leitura.associateWith { p -> tx.get(ref(p)).let { if (it.exists()) doc(p, it) else null } }
            for ((p, dados) in bloco(lidos)) tx.set(ref(p), dados + ("serverUpdatedAt" to FieldValue.serverTimestamp()))
            null
        }.await()
    }

    override suspend fun mudancasDesde(colecao: String, desde: RemoteStore.Cursor): List<RemoteStore.Doc> =
        raiz.collection(colecao).whereGreaterThan("serverUpdatedAt", Timestamp(desde.seconds, desde.nanos))
            .get(Source.SERVER).await().documents.mapNotNull { doc("$colecao/${it.id}", it) }

    override suspend fun ler(path: String): RemoteStore.Doc? =
        ref(path).get(Source.SERVER).await().let { if (it.exists()) doc(path, it) else null }

    override fun ouvir(colecao: String, desde: RemoteStore.Cursor, aoMudar: (List<RemoteStore.Doc>) -> Unit): () -> Unit {
        val reg = raiz.collection(colecao).whereGreaterThan("serverUpdatedAt", Timestamp(desde.seconds, desde.nanos))
            .addSnapshotListener(MetadataChanges.EXCLUDE) { snap, _ ->
                if (snap == null) return@addSnapshotListener
                val docs = snap.documentChanges
                    .filter { it.type != DocumentChange.Type.REMOVED && !it.document.metadata.hasPendingWrites() }
                    .mapNotNull { doc("$colecao/${it.document.id}", it.document) }
                if (docs.isNotEmpty()) aoMudar(docs)
            }
        return { reg.remove() }
    }

    override fun ouvirDoc(path: String, aoMudar: (RemoteStore.Doc?) -> Unit): () -> Unit {
        val reg = ref(path).addSnapshotListener(MetadataChanges.EXCLUDE) { s, _ ->
            if (s != null && !s.metadata.hasPendingWrites()) aoMudar(if (s.exists()) doc(path, s) else null)
        }
        return { reg.remove() }
    }

    override suspend fun horaServidor(deviceId: String): Long {
        val r = ref("estado/relogio_$deviceId")
        r.set(mapOf("em" to FieldValue.serverTimestamp())).await()
        return r.get(Source.SERVER).await().getTimestamp("em")!!.toDate().time
    }
}
