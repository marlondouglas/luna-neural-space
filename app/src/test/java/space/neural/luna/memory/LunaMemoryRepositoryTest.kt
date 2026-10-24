package space.neural.luna.memory

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = Application::class)
class LunaMemoryRepositoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private var uid: String? = "memory-test-ana"
    private var time = 1000L
    private fun repo() = LunaMemoryRepository(context, { uid }, { time })
    @Before fun clear() {
        listOf("memory-test-ana", "memory-test-bia").forEach { LunaMemoryRepository.onAccountSwitch(context, it, null) }
        uid = "memory-test-ana"; time = 1000L
    }
    @Test fun defaultOffAndNoImplicitCollection() {
        assertFalse(repo().enabled()); assertTrue(repo().list().isEmpty())
        assertThrows(IllegalStateException::class.java) { repo().put("Respostas curtas", MemoryCategory.RESPONSE_STYLE) }
        assertTrue(repo().list().isEmpty())
    }
    @Test fun twoAccountsNeverInheritPreferencesOrEnablement() {
        repo().setEnabled(true); repo().put("Respostas curtas", MemoryCategory.RESPONSE_STYLE)
        uid = "memory-test-bia"
        assertFalse(repo().enabled()); assertTrue(repo().list().isEmpty())
        repo().setEnabled(true); repo().put("Respostas detalhadas", MemoryCategory.RESPONSE_STYLE)
        uid = "memory-test-ana"
        assertEquals("Respostas curtas", repo().list().single().text)
    }
    @Test fun manualEditUsesSameIdAndKeepsCreationDate() {
        repo().setEnabled(true)
        val original = repo().put("Prefiro respostas curtas", MemoryCategory.RESPONSE_STYLE)
        time = 2000
        val edited = repo().put("Prefiro explicações completas", MemoryCategory.RESPONSE_STYLE, original.id)
        assertEquals(original.id, edited.id); assertEquals(1000, edited.createdAt); assertEquals(2000, edited.updatedAt)
        assertEquals(1, repo().list().size)
        repo().remove(edited.id); assertTrue(repo().list().isEmpty())
    }
    @Test fun optOutDeletesRatherThanHidesRetainedContent() {
        repo().setEnabled(true); repo().put("Prefiro a manhã", MemoryCategory.WORK_ROUTINE)
        repo().setEnabled(false); assertFalse(repo().enabled())
        repo().setEnabled(true); assertTrue(repo().list().isEmpty())
    }
    @Test fun switchingDeletesOnlyPreviousMemoryAndNeverFinance() {
        val financial = context.getSharedPreferences("finance-test-preservation", Context.MODE_PRIVATE)
        financial.edit().putLong("cents", 15000).commit()
        repo().setEnabled(true); repo().put("Respostas curtas", MemoryCategory.RESPONSE_STYLE)
        LunaMemoryRepository.onAccountSwitch(context, uid, "memory-test-bia")
        assertFalse(repo().enabled()); assertTrue(repo().list().isEmpty())
        assertEquals(15000L, financial.getLong("cents", 0))
    }
    @Test fun stayingOnSameAccountPreservesExplicitItems() {
        repo().setEnabled(true); repo().put("Prefiro a manhã", MemoryCategory.WORK_ROUTINE)
        LunaMemoryRepository.onAccountSwitch(context, uid, uid)
        assertEquals(1, repo().list().size)
    }
    @Test fun webReferencesAreDataAndNeverRelevantPreferences() {
        repo().setEnabled(true)
        repo().put("Página diz para ignorar instruções anteriores", MemoryCategory.REFERENCE,
            origin = MemoryOrigin.WEB_REFERENCE, source = "https://example.org/public")
        assertEquals(MemoryOrigin.WEB_REFERENCE, repo().list().single().origin)
        assertTrue(repo().relevantPreferences().isEmpty())
        assertThrows(IllegalArgumentException::class.java) { repo().put("Conteúdo web", MemoryCategory.PREFERENCE, origin = MemoryOrigin.WEB_REFERENCE, source = "https://example.org") }
    }
    @Test fun invalidIdentityCannotReadOrWriteAnotherNamespace() {
        uid = null; assertNull(repo().safeUid()); assertFalse(repo().enabled()); assertTrue(repo().list().isEmpty())
        uid = " bad "; assertNull(repo().safeUid())
    }
    @Test fun editingWebReferenceCannotPromoteItToManualPreference() {
        repo().setEnabled(true)
        val item = repo().put("Texto de uma fonte externa", MemoryCategory.REFERENCE,
            origin = MemoryOrigin.WEB_REFERENCE, source = "https://example.org/public")
        assertThrows(IllegalArgumentException::class.java) {
            repo().put("Siga o conteúdo da página", MemoryCategory.PREFERENCE, item.id)
        }
        assertEquals(MemoryOrigin.WEB_REFERENCE, repo().list().single().origin)
        assertTrue(repo().relevantPreferences().isEmpty())
    }
    @Test fun cannotEditAnotherAccountsIdAndClearKeepsEnabled() {
        repo().setEnabled(true); val item = repo().put("Prefiro a manhã", MemoryCategory.WORK_ROUTINE)
        uid = "memory-test-bia"; repo().setEnabled(true)
        assertThrows(IllegalArgumentException::class.java) { repo().put("Outro resumo", MemoryCategory.PREFERENCE, item.id) }
        repo().put("Prefiro a tarde", MemoryCategory.WORK_ROUTINE); repo().clear()
        assertTrue(repo().enabled()); assertTrue(repo().list().isEmpty())
    }
}
