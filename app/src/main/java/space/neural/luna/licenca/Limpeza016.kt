package space.neural.luna.licenca

import android.content.Context
import android.util.Log
import java.io.File

/**
 * v0.1.6 = recomeço do zero (decisão do dono do app): na PRIMEIRA abertura desta versão apaga todos os dados
 * locais das versões de teste (banco Room com ganhos/turnos/anotações/conversa, preferências, cache de voz/fotos,
 * APKs baixados) e sai da conta anônima antiga, para o motorista começar no login/cadastro.
 * Roda uma única vez por instalação (marca em "migracao"), antes de qualquer acesso ao banco.
 */
object Limpeza016 {
    const val PREFS = "migracao"
    const val CHAVE = "limpeza_v016_feita"

    /** Preferências que não são do app (Firebase, WorkManager, Play Services) ficam. */
    private val MANTER = listOf(PREFS, "com.google", "androidx.work", "FirebaseHeartBeat", "WorkManager", "FirebaseApp", "PersistedInstallation")

    /** @return true se apagou agora. [sair] = sair da conta anônima (Firebase), injetável nos testes. */
    fun executarSeNecessario(ctx: Context, sair: () -> Unit): Boolean {
        val marca = ctx.getSharedPreferences(PREFS, 0)
        if (marca.getBoolean(CHAVE, false)) return false
        runCatching { sair() }
        // Banco do app e cache local do Firestore (dados da conta anônima antiga).
        ctx.deleteDatabase("luna.db")
        ctx.databaseList().filter { it.startsWith("firestore.") && !it.endsWith("-journal") && !it.endsWith("-wal") && !it.endsWith("-shm") }
            .forEach { ctx.deleteDatabase(it) }
        // Preferências do app.
        File(ctx.applicationInfo.dataDir, "shared_prefs").listFiles()
            ?.map { it.name.removeSuffix(".xml") }
            ?.filter { n -> MANTER.none { n.startsWith(it) } }
            ?.forEach { n -> ctx.getSharedPreferences(n, 0).edit().clear().commit(); ctx.deleteSharedPreferences(n) }
        // Arquivos em cache (voz da Luna, fotos de nota) e atualizações baixadas.
        ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        ctx.getExternalFilesDir(null)?.let { File(it, "updates").deleteRecursively() }
        marca.edit().putBoolean(CHAVE, true).commit()
        Log.i("RotaGV", "Dados das versões de teste apagados (v0.1.6)")
        return true
    }
}
