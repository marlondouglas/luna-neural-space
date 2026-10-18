package space.neural.luna.sync

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import space.neural.luna.data.local.LunaDatabase
import java.io.IOException

/** Servidor Firestore em memória, compartilhado pelos "aparelhos" de um teste. */
class FakeNuvem {
    private var relogio = 0L
    val docs = linkedMapOf<String, RemoteStore.Doc>()
    private val ouvintes = mutableListOf<Pair<String, (RemoteStore.Doc) -> Unit>>()
    var escritas = 0

    @Synchronized fun gravar(p: String, data: Map<String, Any?>): RemoteStore.Doc {
        relogio++; escritas++
        return RemoteStore.Doc(p, data, RemoteStore.Cursor(relogio, 0)).also { docs[p] = it }
    }

    fun notificar(d: RemoteStore.Doc) { ouvintes.toList().filter { d.path.startsWith(it.first) }.forEach { it.second(d) } }

    @Synchronized fun ouvir(prefixo: String, f: (RemoteStore.Doc) -> Unit): () -> Unit {
        val par = prefixo to f; ouvintes += par; return { synchronized(this) { ouvintes.remove(par) } }
    }
}

/** Um aparelho falando com a [FakeNuvem]. [online] = false simula modo avião. */
class FakeRemote(private val nuvem: FakeNuvem) : RemoteStore {
    var online = true

    private fun rede() { if (!online) throw IOException("sem internet") }

    override suspend fun transacao(leitura: List<String>, bloco: (Map<String, RemoteStore.Doc?>) -> Map<String, Map<String, Any?>>) {
        rede()
        val gravados = synchronized(nuvem) {
            val lidos = leitura.associateWith { nuvem.docs[it] }
            bloco(lidos).map { (p, d) -> nuvem.gravar(p, d) }
        }
        gravados.forEach { nuvem.notificar(it) }
    }

    override suspend fun mudancasDesde(colecao: String, desde: RemoteStore.Cursor): List<RemoteStore.Doc> {
        rede()
        return synchronized(nuvem) { nuvem.docs.values.filter { it.path.startsWith("$colecao/") && it.cursor!! > desde }.sortedBy { it.cursor } }
    }

    override suspend fun ler(path: String): RemoteStore.Doc? { rede(); return synchronized(nuvem) { nuvem.docs[path] } }

    override fun ouvir(colecao: String, desde: RemoteStore.Cursor, aoMudar: (List<RemoteStore.Doc>) -> Unit): () -> Unit =
        nuvem.ouvir("$colecao/") { if (online) aoMudar(listOf(it)) }

    override fun ouvirDoc(path: String, aoMudar: (RemoteStore.Doc?) -> Unit): () -> Unit = nuvem.ouvir(path) { if (online) aoMudar(it) }

    override suspend fun horaServidor(deviceId: String): Long { rede(); return System.currentTimeMillis() }
}

/** Preferências separadas por aparelho dentro do mesmo processo de teste. */
class ContextoAparelho(base: Context, private val nome: String) : ContextWrapper(base) {
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences("$nome::$name", mode)
    override fun getApplicationContext(): Context = this
}

/** Aparelho de teste: Room próprio em memória + motor de sincronização. */
class AparelhoTeste(base: Context, val nome: String, nuvem: FakeNuvem, val uid: String = "uidAna") {
    val ctx = ContextoAparelho(base, nome)
    val db: LunaDatabase = Room.inMemoryDatabaseBuilder(base, LunaDatabase::class.java).allowMainThreadQueries().build()
    val remote = FakeRemote(nuvem)
    val engine = SyncEngine(ctx, db, remote, uid, "dev-$nome")
}
