package space.neural.luna.sync

import space.neural.luna.data.local.Anotacao
import space.neural.luna.data.local.Ganho
import space.neural.luna.data.local.Gasto
import space.neural.luna.data.local.Meta
import space.neural.luna.data.local.TipoMeta
import space.neural.luna.data.local.Turno

/** Conversão entre entidades locais e documentos do Firestore. */
internal object FirestoreMappers {
    private fun Map<String, Any?>.long(k: String): Long? = (this[k] as? Number)?.toLong()
    private fun Map<String, Any?>.str(k: String): String? = this[k] as? String
    private fun Map<String, Any?>.bool(k: String): Boolean = this[k] as? Boolean ?: false
    private fun Map<String, Any?>.dbl(k: String): Double? = (this[k] as? Number)?.toDouble()

    fun turno(t: Turno) = mapOf(
        "data" to t.data, "inicio" to t.inicio, "fim" to t.fim, "estado" to t.estado,
        "trabalhoMs" to t.trabalhoMs, "paradoMs" to t.paradoMs, "ultimoEvento" to t.ultimoEvento,
        "updatedAt" to t.updatedAt, "deleted" to t.deleted,
    )
    fun turno(id: String, m: Map<String, Any?>): Turno? = runCatching {
        Turno(
            id = id, data = m.str("data")!!, inicio = m.long("inicio")!!, fim = m.long("fim"),
            estado = m.str("estado")!!, trabalhoMs = m.long("trabalhoMs") ?: 0,
            paradoMs = m.long("paradoMs") ?: 0, ultimoEvento = m.long("ultimoEvento") ?: m.long("inicio")!!,
            updatedAt = m.long("updatedAt")!!, dirty = false, deleted = m.bool("deleted"),
        )
    }.getOrNull()

    fun ganho(g: Ganho) = mapOf(
        "turnoId" to g.turnoId, "data" to g.data, "app" to g.app, "valorCentavos" to g.valorCentavos,
        "descricao" to g.descricao, "updatedAt" to g.updatedAt, "deleted" to g.deleted,
    )
    fun ganho(id: String, m: Map<String, Any?>): Ganho? = runCatching {
        Ganho(
            id = id, turnoId = m.str("turnoId"), data = m.str("data")!!, app = m.str("app")!!,
            valorCentavos = m.long("valorCentavos")!!, descricao = m.str("descricao"),
            updatedAt = m.long("updatedAt")!!, dirty = false, deleted = m.bool("deleted"),
        )
    }.getOrNull()

    fun gasto(g: Gasto) = mapOf(
        "turnoId" to g.turnoId, "data" to g.data, "categoria" to g.categoria,
        "valorCentavos" to g.valorCentavos, "kwh" to g.kwh, "descricao" to g.descricao,
        "updatedAt" to g.updatedAt, "deleted" to g.deleted,
    )
    fun gasto(id: String, m: Map<String, Any?>): Gasto? = runCatching {
        Gasto(
            id = id, turnoId = m.str("turnoId"), data = m.str("data")!!, categoria = m.str("categoria")!!,
            valorCentavos = m.long("valorCentavos")!!, kwh = m.dbl("kwh"), descricao = m.str("descricao"),
            updatedAt = m.long("updatedAt")!!, dirty = false, deleted = m.bool("deleted"),
        )
    }.getOrNull()

    fun meta(x: Meta) = mapOf(
        "data" to x.data, "valorCentavos" to x.valorCentavos, "tipo" to x.tipo,
        "updatedAt" to x.updatedAt, "deleted" to x.deleted,
    )
    fun meta(id: String, m: Map<String, Any?>): Meta? = runCatching {
        Meta(
            id = id, data = m.str("data")!!, valorCentavos = m.long("valorCentavos")!!,
            tipo = m.str("tipo") ?: TipoMeta.DIA,
            updatedAt = m.long("updatedAt")!!, dirty = false, deleted = m.bool("deleted"),
        )
    }.getOrNull()

    fun anotacao(a: Anotacao) = mapOf(
        "texto" to a.texto, "criadaEm" to a.criadaEm, "updatedAt" to a.updatedAt, "deleted" to a.deleted,
    )
    fun anotacao(id: String, m: Map<String, Any?>): Anotacao? = runCatching {
        Anotacao(
            id = id, texto = m.str("texto")!!, criadaEm = m.long("criadaEm") ?: m.long("updatedAt")!!,
            updatedAt = m.long("updatedAt")!!, dirty = false, deleted = m.bool("deleted"),
        )
    }.getOrNull()
}
