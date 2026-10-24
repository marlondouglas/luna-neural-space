package space.neural.luna.voz

import org.junit.Assert.*
import org.junit.Test

class SpeechQueuePolicyTest {
    @Test fun emergenciesAndManeuversTakePriorityOverLongAssistantSpeech() {
        val q = SpeechQueuePolicy()
        q.offer(SpeechQueuePolicy.Item(1, SpeechPriority.ASSISTANT, "resposta longa"))
        assertEquals(1L, q.next()!!.id)
        assertTrue(q.shouldPreempt(SpeechPriority.MANEUVER))
        q.finish()
        q.offer(SpeechQueuePolicy.Item(2, SpeechPriority.MANEUVER, "vire à direita"))
        q.offer(SpeechQueuePolicy.Item(3, SpeechPriority.SOS, "emergência"))
        assertEquals(3L, q.next()!!.id)
        q.finish(); assertEquals(2L, q.next()!!.id)
    }
    @Test fun radioIsExclusiveAndDoesNotResumeStaleAssistantSpeech() {
        val q = SpeechQueuePolicy()
        q.offer(SpeechQueuePolicy.Item(1, SpeechPriority.ASSISTANT, "longa"))
        q.offer(SpeechQueuePolicy.Item(2, SpeechPriority.MANEUVER, "manobra"))
        q.radioBusy = true; q.interruptForRadio()
        assertNull(q.next())
        q.radioBusy = false
        assertEquals(2L, q.next()!!.id)
        q.finish(); assertNull(q.next())
    }
    @Test fun latestQueuedManeuverReplacesStaleInstructionAndNoDuplicateSpeech() {
        val q = SpeechQueuePolicy()
        q.offer(SpeechQueuePolicy.Item(1, SpeechPriority.MANEUVER, "antiga"))
        q.offer(SpeechQueuePolicy.Item(2, SpeechPriority.MANEUVER, "nova"))
        assertFalse(q.offer(SpeechQueuePolicy.Item(3, SpeechPriority.MANEUVER, "nova")))
        assertEquals(setOf(2L), q.retainedIds)
        assertEquals(2L, q.next()!!.id)
        assertTrue(q.cancel(SpeechPriority.MANEUVER))
        assertNull(q.next())
    }
    @Test fun pendingQueueIsBounded() {
        val q = SpeechQueuePolicy()
        repeat(50) { q.offer(SpeechQueuePolicy.Item(it.toLong(), SpeechPriority.ASSISTANT, "$it")) }
        assertEquals(12, q.retainedIds.size)
    }
    @Test fun unregisteredVoiceIsNeverAuthorized() {
        LocalVoiceRuntime.clearAuthorization()
        assertFalse(LocalVoiceRuntime.authorizeEngine("android-default"))
        assertFalse(LocalVoiceRuntime.available)
    }
}
