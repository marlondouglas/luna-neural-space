package space.neural.luna.update

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpdateCheckerTest {
    @Test fun acceptsHttpsAndRejectsUnsafeAddresses() {
        UpdateChecker.requireHttps("https://example.com/update.apk")
        listOf("http://example.com/a.apk", "file:///tmp/a.apk", "https://user:pass@example.com/a.apk").forEach {
            assertTrue(runCatching { UpdateChecker.requireHttps(it) }.exceptionOrNull() is UpdateException)
        }
    }

    @Test fun hashMustMatchPublishedContent() {
        val file = File.createTempFile("rota-update", ".apk")
        try {
            file.writeText("abc")
            UpdateChecker.verifyHash(file, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
            assertTrue(runCatching { UpdateChecker.verifyHash(file, "0".repeat(64)) }.exceptionOrNull() is UpdateException)
            assertTrue(runCatching { UpdateChecker.verifyHash(file, null) }.exceptionOrNull() is UpdateException)
        } finally { file.delete() }
    }

    @Test fun versionsCompareNumerically() {
        assertTrue(UpdateChecker.compare("0.1.15", "0.1.14") > 0)
        assertTrue(UpdateChecker.compare("0.1.10", "0.1.9") > 0)
    }
}
