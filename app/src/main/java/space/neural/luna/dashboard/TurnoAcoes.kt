package space.neural.luna.dashboard

import space.neural.luna.data.local.EstadoTurno
import space.neural.luna.data.local.Turno
import space.neural.luna.data.repo.LunaRepositories
import java.time.LocalDate

/** Ações do turno (spec 2.1), usadas pelo dashboard e pela conversa com a Luna. */
class TurnoAcoes(private val repo: LunaRepositories) {
    suspend fun iniciar(): Turno {
        val agora = System.currentTimeMillis()
        val t = Turno(data = LocalDate.now().toString(), inicio = agora, ultimoEvento = agora)
        repo.salvarTurno(t)
        return t
    }
    suspend fun pausar(t: Turno) = repo.mudarEstado(t, EstadoTurno.PAUSADO)
    suspend fun cafe(t: Turno) = repo.mudarEstado(t, EstadoTurno.CAFE)
    suspend fun retomar(t: Turno) = repo.mudarEstado(t, EstadoTurno.ATIVO)

    /** Fecha o turno e liga a ele os lançamentos soltos do dia. Depois disso, a tela de correção abre. */
    suspend fun fechar(t: Turno) {
        repo.mudarEstado(t, EstadoTurno.FECHADO)
        repo.vincularSoltos(t.id, t.data)
        val hoje = LocalDate.now().toString()
        if (hoje != t.data) repo.vincularSoltos(t.id, hoje)
    }

    /** Concluir (ou Zerar): o dia vai para o histórico e o painel volta a "Sem turno". */
    suspend fun concluir(t: Turno) {
        val atual = repo.turnoPorId(t.id) ?: t
        if (atual.estado != EstadoTurno.FECHADO) fechar(atual)
        repo.turnoPorId(t.id)?.let { repo.mudarEstado(it, EstadoTurno.CONCLUIDO) }
    }
}
