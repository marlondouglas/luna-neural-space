package space.neural.luna.collaboration

import org.junit.Assert.*
import org.junit.Test

class CommunityModelsTest {
    @Test fun onlyConsentingRecentOnlineDriversAppearOnMap() {
        val p = DriverPresence("u", "A", DriverStatus.ONLINE, true, -18.8, -41.9, 100_000, accuracyMeters = 25.0, locationTimestamp = 100_000)
        assertTrue(p.visibleOnMap(130_000))
        assertFalse(p.visibleOnMap(200_001))
        assertFalse(p.copy(status = DriverStatus.OCUPADO).visibleOnMap(130_000))
        assertFalse(p.copy(status = DriverStatus.OFFLINE).visibleOnMap(130_000))
        assertFalse(p.copy(shareLocation = false).visibleOnMap(130_000))
        assertFalse(p.copy(updatedAt = 140_000).visibleOnMap(130_000))
        assertFalse(p.copy(latitude = null).visibleOnMap(130_000))
        assertFalse(p.copy(accuracyMeters = null).visibleOnMap(130_000))
        assertFalse(p.copy(accuracyMeters = 100.1).visibleOnMap(130_000))
        assertFalse(p.copy(accuracyMeters = Double.NaN).visibleOnMap(130_000))
        assertFalse(p.copy(locationTimestamp = null).visibleOnMap(130_000))
        assertFalse(p.copy(locationTimestamp = 140_000).visibleOnMap(130_000))
        assertFalse(p.copy(updatedAt = 129_999, locationTimestamp = 10_000).visibleOnMap(130_000))
    }
    @Test fun roomLabelsPreserveWireCompatibility() {
        assertEquals(CommunityRooms.slug("Geely"), CommunityRooms.slug("Elétricos GV"))
        assertEquals(CommunityRooms.slug("ROTA GV"), CommunityRooms.slug("Geral GV"))
        assertEquals("luna-byd", CommunityRooms.slug("BYD"))
        assertEquals("luna-combustao", CommunityRooms.slug("Combustão"))
    }
    @Test fun closedAndExpiredEventsAreNotActive() {
        val e = CommunityEvent("i", "u", "SOS", 0.0, 0.0, "", 10, 100, true)
        assertTrue(e.valid(99)); assertFalse(e.valid(100)); assertFalse(e.copy(active = false).valid(99))
    }
}
