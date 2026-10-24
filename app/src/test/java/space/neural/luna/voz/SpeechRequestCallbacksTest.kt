package space.neural.luna.voz

import org.junit.Assert.*
import org.junit.Test

class SpeechRequestCallbacksTest {
    @Test fun startBelongsToFirstFrameAndDoneIsExactlyOnce() {
        var starts = 0; var finishes = 0
        val request = SpeechRequestCallbacks({ true }, { starts++ }, { finishes++ })
        assertEquals(0, starts)
        request.firstFrame(); request.firstFrame()
        assertEquals(1, starts)
        request.complete(); request.complete(); request.firstFrame()
        assertEquals(1, starts); assertEquals(1, finishes)
    }
    @Test fun failureOrCancellationRevealsViaDoneWithoutStartingAudio() {
        var starts = 0; var finishes = 0
        val request = SpeechRequestCallbacks({ true }, { starts++ }, { finishes++ })
        request.complete(); request.firstFrame()
        assertEquals(0, starts); assertEquals(1, finishes)
    }
    @Test fun accountSwitchAndAnotherRequestCannotTriggerOriginalStart() {
        var owner = "a"; var firstStarts = 0; var secondStarts = 0; var finishes = 0
        val first = SpeechRequestCallbacks({ owner == "a" }, { firstStarts++ }, { finishes++ })
        owner = "b"
        val second = SpeechRequestCallbacks({ owner == "b" }, { secondStarts++ }, {})
        second.firstFrame(); first.firstFrame(); first.complete()
        assertEquals(0, firstStarts); assertEquals(1, secondStarts); assertEquals(1, finishes)
    }
    @Test fun throwingConsumerDoesNotAllowRepeatedCallbacks() {
        var starts = 0; var finishes = 0
        val request = SpeechRequestCallbacks({ true }, { starts++; error("consumer") }, { finishes++; error("consumer") })
        request.firstFrame(); request.firstFrame(); request.complete(); request.complete()
        assertEquals(1, starts); assertEquals(1, finishes)
    }
}
