package space.neural.luna.sync

/**
 * Regra de conflito da sincronização (v0.1.7): "o último a alterar vence", por registro.
 *
 * - A versão de um registro é (updatedAt, deviceId). updatedAt é o carimbo do [Relogio] corrigido
 *   pelo servidor; deviceId desempata dois aparelhos que alteraram no mesmo milissegundo.
 * - Recebendo: o remoto só substitui o local se for estritamente mais novo.
 * - Enviando: o aparelho só grava no servidor se a sua versão for mais nova ou igual (reenvio da mesma
 *   alteração). Se o servidor tem algo mais novo, o envio é descartado e o remoto vem para o aparelho.
 *   (Na 0.1.6 o envio sobrescrevia sempre: um aparelho que voltava do modo avião apagava alterações mais novas.)
 * - Exclusão é um registro como outro qualquer (deleted = true), então ela também segue a regra.
 */
object Conflito {
    data class Versao(val updatedAt: Long, val deviceId: String?)

    fun compara(a: Versao, b: Versao): Int {
        val c = a.updatedAt.compareTo(b.updatedAt)
        return if (c != 0) c else (a.deviceId ?: "").compareTo(b.deviceId ?: "")
    }

    /** O registro remoto deve substituir o local? */
    fun remotoVence(local: Versao?, remoto: Versao): Boolean = local == null || compara(remoto, local) > 0

    /** O aparelho pode gravar a sua versão no servidor? */
    fun envioVence(local: Versao, remoto: Versao?): Boolean = remoto == null || compara(local, remoto) >= 0
}
