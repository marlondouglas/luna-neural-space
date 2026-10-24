package space.neural.luna.memory

import org.junit.Assert.*
import org.junit.Test

class MemoryContentPolicyTest {
    @Test fun rejectsCredentialsAndPreciseLocationWithoutBlockingOrdinaryPreference() {
        listOf("senha=supersecreta", "api_key: abcdef", "Bearer abcdefghijklmnop", "latitude=-18.85 longitude=-41.94", "Casa: -18.850123, -41.940123", "moro na rua residencial").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { MemoryContentPolicy.validate(it) }
        }
        assertEquals("Prefiro respostas curtas", MemoryContentPolicy.validate("  Prefiro respostas curtas  "))
        assertEquals("Gosto de trabalhar de manhã em GV", MemoryContentPolicy.validate("Gosto de trabalhar de manhã em GV"))
    }
    @Test fun rejectsFullConversationSizedText() {
        assertThrows(IllegalArgumentException::class.java) { MemoryContentPolicy.validate("a".repeat(281)) }
    }
}
