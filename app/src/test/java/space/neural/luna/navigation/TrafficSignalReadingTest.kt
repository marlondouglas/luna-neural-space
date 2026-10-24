package space.neural.luna.navigation

import org.junit.Assert.*
import org.junit.Test

class TrafficSignalReadingTest {
    private val reading = TrafficSignalReading("signal-1", "Intersection fixture", "northbound", "vehicles-1",
        TrafficSignalPhase.RED, 10, 10_000L, 15_000L)
    @Test fun retainsOnlySourceReportedPhaseWhileFresh() {
        assertEquals(TrafficSignalState(TrafficSignalPhase.RED, 8), reading.effective(12_000))
    }
    @Test fun expiryNeverProducesAnotherPhaseOrTimer() {
        assertEquals(TrafficSignalState(TrafficSignalPhase.UNKNOWN, null), reading.effective(15_000))
        assertEquals(TrafficSignalState(TrafficSignalPhase.UNKNOWN, null), reading.copy(remainingSeconds = 1).effective(11_000))
    }
    @Test fun absentTimerIsNotFabricated() {
        assertEquals(TrafficSignalState(TrafficSignalPhase.RED, null), reading.copy(remainingSeconds = null).effective(12_000))
        assertEquals(TrafficSignalState(TrafficSignalPhase.UNKNOWN, null), reading.copy(phase = null).effective(12_000))
    }
    @Test fun invalidStaleAndFutureReadingsBecomeUnknown() {
        val invalid = listOf(reading.copy(id = ""), reading.copy(group = ""), reading.copy(direction = ""),
            reading.copy(intersection = ""), reading.copy(remainingSeconds = -1), reading.copy(updatedAt = 13_000),
            reading.copy(validUntil = 9_000), reading.copy(updatedAt = 1_000, validUntil = 20_000))
        invalid.forEach { assertEquals(TrafficSignalState(TrafficSignalPhase.UNKNOWN, null), it.effective(12_000)) }
    }
    @Test fun flashingSignalDoesNotDisplayCountdown() {
        assertEquals(TrafficSignalState(TrafficSignalPhase.FLASHING, null), reading.copy(phase = TrafficSignalPhase.FLASHING).effective(12_000))
    }
}
