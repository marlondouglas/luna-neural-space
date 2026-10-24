package space.neural.luna.voz

import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/** Small testable installer: exact allowlist, bounded copies, SHA-256 and atomic directory handoff. */
object VerifiedVoiceInstaller {
    suspend fun install(parent: File, modelName: String, entries: Map<String, SupertonicModelFiles.Entry>,
        input: (String) -> InputStream, progress: (Int) -> Unit = {}): File {
        require(modelName.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]*")) && !modelName.contains(".."))
        require(entries.isNotEmpty() && entries.all { (name, entry) ->
            name.matches(Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]*")) && !name.contains("..") && entry.bytes > 0 && entry.sha256.matches(Regex("[a-fA-F0-9]{64}"))
        })
        parent.mkdirs()
        val base = parent.canonicalFile
        val target = File(base, modelName)
        check(target.canonicalFile.parentFile == base) { "Destino de voz fora da pasta privada." }
        val stage = File(base, ".install-${java.util.UUID.randomUUID()}")
        val backup = File(base, ".previous-${java.util.UUID.randomUUID()}")
        check(stage.mkdir()) { "Não foi possível criar a pasta temporária." }
        try {
            var copied = 0L
            var lastProgress = -1
            val total = entries.values.sumOf { it.bytes }
            entries.forEach { (name, entry) ->
                coroutineContext.ensureActive()
                val output = File(stage, name)
                check(output.canonicalFile.parentFile == stage.canonicalFile)
                input(name).use { source -> output.outputStream().use { sink ->
                    val buffer = ByteArray(65536); var countFile = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = source.read(buffer); if (count < 0) break
                        countFile += count
                        check(countFile <= entry.bytes) { "Arquivo de voz excede o tamanho esperado." }
                        sink.write(buffer, 0, count); copied += count
                        val percent = (copied * 100 / total).toInt()
                        if (percent != lastProgress) { lastProgress = percent; progress(percent) }
                    }
                } }
                check(SupertonicModelFiles.verified(output, entry)) { "Integridade inválida do modelo $name." }
            }
            coroutineContext.ensureActive()
            if (target.exists()) check(target.renameTo(backup)) { "Não foi possível preservar a voz anterior." }
            if (!stage.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                error("Não foi possível concluir a instalação da voz.")
            }
            return target
        } finally {
            if (stage.exists() && stage.canonicalFile.parentFile == base) stage.deleteRecursively()
            if (backup.exists() && backup.canonicalFile.parentFile == base) {
                if (target.exists()) backup.deleteRecursively() else backup.renameTo(target)
            }
        }
    }
}
