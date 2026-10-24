package space.neural.luna.voz

import java.io.File
import java.security.MessageDigest

/** Pinned files from the official archive verified in the Windows proof. No arbitrary paths. */
object SupertonicModelFiles {
    const val ARCHIVE_SHA256 = "82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427"
    const val ARCHIVE_BYTES = 128774318L
    data class Entry(val bytes: Long, val sha256: String)
    val required = linkedMapOf(
        "duration_predictor.int8.onnx" to Entry(3700147, "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db"),
        "text_encoder.int8.onnx" to Entry(36416150, "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff"),
        "vector_estimator.int8.onnx" to Entry(78400833, "20cd86fa5c6effedfda0e7cffe5b0569ca401c440a0c3a1d72bf39286c0db3fd"),
        "vocoder.int8.onnx" to Entry(25991073, "e923d60f53f95eb1ce235f1dc33ec56d9c057823c96fa6f8acf98f32b0da6152"),
        "tts.json" to Entry(8253, "42078d3aef1cd43ab43021f3c54f47d2d75ceb4e75f627f118890128b06a0d09"),
        "unicode_indexer.bin" to Entry(262144, "8402ca48e5189a8950138580b0fff64db6f072f24ac07cd54ba8b2fbb9883b30"),
        "voice.bin" to Entry(517168, "67d5209b0ee8ce6c74105ffbe12fe6a7628aea3b4ba2fcb308a4a67938a93ce8"),
    )
    val totalBytes get() = required.values.sumOf { it.bytes }
    fun verified(file: File, expected: Entry): Boolean {
        if (!file.isFile || file.length() != expected.bytes) return false
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }.equals(expected.sha256, true)
    }
}
