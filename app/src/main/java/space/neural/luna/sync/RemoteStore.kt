package space.neural.luna.sync

/**
 * Nuvem vista pela sincronização: documentos em users/{uid}/... endereçados por caminho relativo
 * ("ganhos/abc", "estado/turnoAtual"). A implementação real é o [FirestoreRemote]; os testes usam um
 * servidor em memória com dois "aparelhos" ligados a ele.
 */
interface RemoteStore {
    /** Posição na ordem de gravação do servidor (serverUpdatedAt). */
    data class Cursor(val seconds: Long, val nanos: Int) : Comparable<Cursor> {
        override fun compareTo(other: Cursor) = compareValuesBy(this, other, { it.seconds }, { it.nanos })
        companion object { val ZERO = Cursor(0, 0) }
    }

    data class Doc(val path: String, val data: Map<String, Any?>, val cursor: Cursor?) {
        val id: String get() = path.substringAfterLast('/')
        val updatedAt: Long? get() = (data["updatedAt"] as? Number)?.toLong()
        val deviceId: String? get() = data["deviceId"] as? String
        val versao: Conflito.Versao? get() = updatedAt?.let { Conflito.Versao(it, deviceId) }
    }

    /**
     * Transação: lê [leitura], [bloco] decide o que gravar (caminho → dados, ou vazio para não gravar).
     * Cada documento gravado ganha serverUpdatedAt do servidor. Lança exceção sem rede.
     */
    suspend fun transacao(leitura: List<String>, bloco: (Map<String, Doc?>) -> Map<String, Map<String, Any?>>)

    /** Documentos de [colecao] gravados depois de [desde], em ordem de gravação. */
    suspend fun mudancasDesde(colecao: String, desde: Cursor): List<Doc>

    suspend fun ler(path: String): Doc?

    /** Escuta em tempo real os documentos de [colecao] gravados depois de [desde]. Retorna o "desligar". */
    fun ouvir(colecao: String, desde: Cursor, aoMudar: (List<Doc>) -> Unit): () -> Unit

    fun ouvirDoc(path: String, aoMudar: (Doc?) -> Unit): () -> Unit

    /** Hora do servidor (ms), para o [Relogio]. */
    suspend fun horaServidor(deviceId: String): Long
}
