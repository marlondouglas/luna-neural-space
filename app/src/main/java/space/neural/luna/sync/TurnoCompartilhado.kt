package space.neural.luna.sync

import space.neural.luna.data.local.EstadoTurno
import space.neural.luna.data.local.Turno

/**
 * Turno compartilhado entre aparelhos da mesma conta (v0.1.7).
 *
 * users/{uid}/estado/turnoAtual guarda o último turno mexido (todos os campos do [Turno]) + [Estado.versao]
 * + o aparelho que mexeu. Toda ação de turno (iniciar, pausar, café, retomar, fechar, zerar, voltar,
 * concluir) passa por uma transação que confere a versão que ESTE aparelho viu por último:
 * - versão igual (ou foi o próprio aparelho que mexeu): grava e soma 1 na versão;
 * - versão maior, de outro aparelho: o turno mudou lá e este aparelho ainda não sabia. A ação NÃO é aplicada;
 *   o estado do outro aparelho vem para cá e o motorista vê, por exemplo, "O turno já foi pausado no outro aparelho."
 * Sem internet, a ação vale no aparelho e é reconciliada depois pelo "último a alterar vence".
 */
object TurnoCompartilhado {
    const val PATH = "estado/turnoAtual"

    data class Estado(val turno: Turno, val versao: Long, val deviceId: String?)

    sealed interface Decisao {
        data class Gravar(val novaVersao: Long) : Decisao
        data class Conflito(val remoto: Estado) : Decisao
    }

    fun decidir(remoto: Estado?, versaoVista: Long, eu: String): Decisao = when {
        remoto == null -> Decisao.Gravar(1)
        remoto.versao <= versaoVista || remoto.deviceId == eu -> Decisao.Gravar(remoto.versao + 1)
        else -> Decisao.Conflito(remoto)
    }

    /** O turno que representa o resultado de uma ação com vários turnos alterados. */
    fun representante(alterados: List<Turno>): Turno? =
        alterados.filter { !it.deleted }.maxByOrNull { it.ultimoEvento } ?: alterados.maxByOrNull { it.updatedAt }

    fun mensagem(remoto: Estado): String {
        val t = remoto.turno
        val n = "turno ${t.numero}"
        return when {
            t.deleted -> "O dia foi zerado no outro aparelho. Confira o painel antes de continuar."
            t.estado == EstadoTurno.PAUSADO -> "O turno já foi pausado no outro aparelho."
            t.estado == EstadoTurno.CAFE -> "O turno já está em pausa para o café no outro aparelho."
            t.estado == EstadoTurno.ATIVO -> "O $n já está contando no outro aparelho."
            t.estado == EstadoTurno.FECHADO -> "O $n já foi fechado no outro aparelho."
            t.estado == EstadoTurno.CONCLUIDO -> "O dia já foi concluído no outro aparelho."
            t.estado == EstadoTurno.PRONTO -> "O tempo do $n foi zerado no outro aparelho."
            else -> "O turno mudou no outro aparelho."
        } + " Atualizei aqui: confira e toque de novo se precisar."
    }

    fun paraMapa(t: Turno, versao: Long, deviceId: String): Map<String, Any?> =
        FirestoreMappers.turno(t) + mapOf("turnoId" to t.id, "versao" to versao, "deviceId" to deviceId)

    fun deMapa(m: Map<String, Any?>): Estado? {
        val id = m["turnoId"] as? String ?: return null
        val t = FirestoreMappers.turno(id, m) ?: return null
        return Estado(t, (m["versao"] as? Number)?.toLong() ?: 0, m["deviceId"] as? String)
    }
}

/** Erro mostrado ao motorista quando o turno mudou no outro aparelho. */
class TurnoNoOutroAparelho(msg: String) : Exception(msg)
