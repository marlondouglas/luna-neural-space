package space.neural.luna.navigation

enum class TrafficSignalPhase { RED, YELLOW, GREEN, FLASHING, OFF, UNKNOWN }

/** Accept only observations from an explicitly authorized source; never infer a signal from map geometry. */
data class TrafficSignalReading(
    val id: String,
    val intersection: String,
    val direction: String,
    val group: String,
    val phase: TrafficSignalPhase?,
    val remainingSeconds: Int?,
    val updatedAt: Long,
    val validUntil: Long,
) {
    /** Pure validation. Missing/stale/expired phase data produces UNKNOWN and no timer. */
    fun effective(nowMillis: Long, maxAgeMillis: Long = 5_000L): TrafficSignalState {
        fun unknown() = TrafficSignalState(TrafficSignalPhase.UNKNOWN, null)
        if (id.isBlank() || intersection.isBlank() || direction.isBlank() || group.isBlank() ||
            updatedAt <= 0 || validUntil <= updatedAt || nowMillis < updatedAt || nowMillis >= validUntil ||
            maxAgeMillis <= 0 || nowMillis - updatedAt > maxAgeMillis || phase == null || phase == TrafficSignalPhase.UNKNOWN ||
            remainingSeconds?.let { it < 0 } == true) return unknown()
        if (phase == TrafficSignalPhase.FLASHING || phase == TrafficSignalPhase.OFF) return TrafficSignalState(phase, null)
        val reported = remainingSeconds ?: return TrafficSignalState(phase, null)
        // Decrease only the source-reported phase's timer. At zero, do not guess the next phase.
        val remaining = reported.toLong() - (nowMillis - updatedAt) / 1_000
        if (remaining <= 0) return unknown()
        return TrafficSignalState(phase, remaining.toInt())
    }
}

data class TrafficSignalState(val phase: TrafficSignalPhase, val remainingSeconds: Int?)
