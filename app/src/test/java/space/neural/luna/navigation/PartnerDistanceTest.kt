package space.neural.luna.navigation

import org.junit.Assert.*
import org.junit.Test
import space.neural.luna.partners.*

class PartnerDistanceTest {
    private val position = PartnerPosition(0.0, 0.0, 100_000, 20f)
    @Test fun straightLineRequiresFreshAccuratePositionAndValidCoordinates() {
        assertEquals(111_195.0, PartnerDistance.meters(1.0, 0.0, position, 100_000)!!, 2.0)
        assertNull(PartnerDistance.meters(1.0, 0.0, null, 100_000))
        assertNull(PartnerDistance.meters(1.0, 0.0, position, 160_001))
        assertNull(PartnerDistance.meters(1.0, 0.0, position, 99_999))
        assertNull(PartnerDistance.meters(1.0, 0.0, position.copy(accuracyMeters = 101f), 100_000))
        assertNull(PartnerDistance.meters(Double.NaN, 0.0, position, 100_000))
        assertNull(PartnerDistance.meters(null, 0.0, position, 100_000))
    }
}
