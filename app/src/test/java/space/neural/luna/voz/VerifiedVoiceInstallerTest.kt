package space.neural.luna.voz

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

class VerifiedVoiceInstallerTest {
    private val abc = SupertonicModelFiles.Entry(3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    @Test fun fixedVoiceContractUsesApprovedPresetAndNoReferenceAudio() {
        assertEquals("supertonic-3-pt-sid-0", LocalVoiceRuntime.APPROVED_ENGINE_ID)
        assertEquals(0, LocalVoiceRuntime.APPROVED_SPEAKER)
        assertEquals("pt", LocalVoiceRuntime.SYNTHESIS_LANGUAGE)
        assertEquals(7, SupertonicModelFiles.required.size)
        assertEquals(145295768L, SupertonicModelFiles.totalBytes)
        assertTrue(SupertonicModelFiles.required.values.all { it.sha256.matches(Regex("[a-f0-9]{64}")) })
    }
    @Test fun validCopyIsVerifiedAndReplacesOnlyOwnModelDirectory() = runBlocking {
        val parent = Files.createTempDirectory("voice-install").toFile()
        try {
            val unrelated = java.io.File(parent, "financial-data").apply { writeText("preserve") }
            val result = VerifiedVoiceInstaller.install(parent, "model", mapOf("test.bin" to abc), { ByteArrayInputStream("abc".toByteArray()) })
            assertEquals("abc", java.io.File(result, "test.bin").readText())
            assertEquals("preserve", unrelated.readText())
            assertTrue(parent.listFiles()!!.none { it.name.startsWith(".install-") || it.name.startsWith(".previous-") })
        } finally { parent.deleteRecursively() }
    }
    @Test fun corruptOrOversizedCopyPreservesInstalledModel() = runBlocking {
        val parent = Files.createTempDirectory("voice-install").toFile()
        try {
            val model = java.io.File(parent, "model").apply { mkdir() }
            val old = java.io.File(model, "old.bin").apply { writeText("old") }
            listOf("xyz", "too-long").forEach { bad ->
                val failure = runCatching { VerifiedVoiceInstaller.install(parent, "model", mapOf("test.bin" to abc), { ByteArrayInputStream(bad.toByteArray()) }) }
                assertTrue(failure.isFailure); assertEquals("old", old.readText())
            }
            assertEquals(listOf("model"), parent.listFiles()!!.map { it.name })
        } finally { parent.deleteRecursively() }
    }
    @Test fun cancellationDiscardsStageAndNeverChangesPreviousFiles() = runBlocking {
        val parent = Files.createTempDirectory("voice-install").toFile()
        try {
            val failure = runCatching { VerifiedVoiceInstaller.install(parent, "model", mapOf("test.bin" to abc),
                { ByteArrayInputStream("abc".toByteArray()) }, { throw kotlinx.coroutines.CancellationException("user cancelled") }) }
            assertTrue(failure.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertTrue(parent.listFiles()!!.isEmpty())
        } finally { parent.deleteRecursively() }
    }
    @Test fun pathsOutsideAllowlistAreRejectedBeforeWriting() = runBlocking {
        val parent = Files.createTempDirectory("voice-install").toFile()
        try {
            assertTrue(runCatching { VerifiedVoiceInstaller.install(parent, "model", mapOf("../escape" to abc), { ByteArrayInputStream(byteArrayOf()) }) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { VerifiedVoiceInstaller.install(parent, "../model", mapOf("x" to abc), { ByteArrayInputStream(byteArrayOf()) }) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(parent.listFiles()!!.isEmpty())
        } finally { parent.deleteRecursively() }
    }
}
