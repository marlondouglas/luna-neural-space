package space.neural.luna.sync

import android.content.Context
import androidx.room.withTransaction
import space.neural.luna.data.local.LunaDatabase
import space.neural.luna.data.local.Turno

/** Lado "nuvem" do turno compartilhado: transação de cada ação, estado recebido e reconciliação depois do offline. */
class TurnoRemoto(
    ctx: Context,
    private val db: LunaDatabase,
    private val remote: RemoteStore,
    uid: String,
    private val deviceId: String,
) : TurnoGuard {
    private val prefs = ctx.getSharedPreferences("turno_compartilhado_$uid", 0)

    fun versaoVista(): Long = prefs.getLong("versao", 0)
    private fun ver(v: Long) { if (v > versaoVista()) prefs.edit().putLong("versao", v).commit() }

    override suspend fun confirmar(alterados: List<Turno>): TurnoGuard.Resultado {
        val rep = TurnoCompartilhado.representante(alterados) ?: return TurnoGuard.Resultado.Ok(false)
        var conflito: TurnoCompartilhado.Estado? = null
        var versao = 0L
        val vista = versaoVista()
        remote.transacao(listOf(TurnoCompartilhado.PATH)) { lidos ->
            val r = lidos[TurnoCompartilhado.PATH]?.let { TurnoCompartilhado.deMapa(it.data) }
            when (val d = TurnoCompartilhado.decidir(r, vista, deviceId)) {
                is TurnoCompartilhado.Decisao.Gravar -> {
                    conflito = null; versao = d.novaVersao
                    mapOf(TurnoCompartilhado.PATH to TurnoCompartilhado.paraMapa(rep, versao, deviceId)) +
                        alterados.associate { "turnos/${it.id}" to FirestoreMappers.turno(it) + ("deviceId" to deviceId) }
                }
                is TurnoCompartilhado.Decisao.Conflito -> { conflito = d.remoto; emptyMap() }
            }
        }
        conflito?.let { aplicar(it); return TurnoGuard.Resultado.Conflito(it, TurnoCompartilhado.mensagem(it)) }
        ver(versao)
        return TurnoGuard.Resultado.Ok(enviado = true)
    }

    /** Estado recebido da nuvem (ouvinte, descida ou conflito): entra no Room se for mais novo. */
    suspend fun aplicar(e: TurnoCompartilhado.Estado) {
        db.withTransaction {
            val local = db.turnoDao().byId(e.turno.id)
            val vLocal = local?.let { Conflito.Versao(it.updatedAt, if (it.dirty) deviceId else null) }
            if (Conflito.remotoVence(vLocal, Conflito.Versao(e.turno.updatedAt, e.deviceId))) db.turnoDao().upsert(e.turno)
        }
        ver(e.versao)
    }

    suspend fun aoReceber(d: RemoteStore.Doc?) { d?.let { TurnoCompartilhado.deMapa(it.data) }?.let { aplicar(it) } }

    suspend fun baixar() = aoReceber(remote.ler(TurnoCompartilhado.PATH))

    /**
     * Depois de ações feitas sem internet ([pendentes] = turnos alterados aqui que ainda não tinham subido):
     * se o mais recente deles é mais novo que o estado compartilhado, ele passa a ser o estado (nova versão).
     */
    suspend fun reconciliar(pendentes: List<Turno>) {
        val local = pendentes.maxByOrNull { it.updatedAt } ?: return
        var versao = 0L
        remote.transacao(listOf(TurnoCompartilhado.PATH)) { lidos ->
            val r = lidos[TurnoCompartilhado.PATH]?.let { TurnoCompartilhado.deMapa(it.data) }
            versao = 0
            val mesmo = r != null && r.turno.id == local.id && r.turno.updatedAt == local.updatedAt
            val vence = r == null || Conflito.compara(Conflito.Versao(local.updatedAt, deviceId), Conflito.Versao(r.turno.updatedAt, r.deviceId)) > 0
            if (mesmo || !vence) emptyMap()
            else { versao = (r?.versao ?: 0) + 1; mapOf(TurnoCompartilhado.PATH to TurnoCompartilhado.paraMapa(local, versao, deviceId)) }
        }
        if (versao > 0) ver(versao)
    }
}
