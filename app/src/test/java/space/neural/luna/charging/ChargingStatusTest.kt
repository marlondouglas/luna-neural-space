package space.neural.luna.charging

import org.junit.Assert.*
import org.junit.Test

class ChargingStatusTest {
    @Test fun expiredTelemetryNeverStaysAvailable() {
        val live = ChargingLiveStatus(listOf(ChargingConnector("1", "CCS2", 120.0, ConnectorState.AVAILABLE, 1000)), 2000)
        assertEquals(ConnectorState.AVAILABLE, live.effectiveConnectors(1999).single().state)
        assertEquals(ConnectorState.UNKNOWN, live.effectiveConnectors(2000).single().state)
        assertEquals(ConnectorState.UNKNOWN, live.effectiveConnectors(10000).single().state)
    }
}
