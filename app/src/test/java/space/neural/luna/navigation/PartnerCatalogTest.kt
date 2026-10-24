package space.neural.luna.navigation

import org.junit.Assert.*
import org.junit.Test
import space.neural.luna.partners.*
import space.neural.luna.charging.*

class PartnerCatalogTest {
    @Test fun requestedBusinessesNeverPretendConfirmedOffer() {
        listOf("gpcar-insulfilm", "studio-tiffany-carla").forEach {
            val partner = requireNotNull(PartnerCatalog.find(it))
            assertNull(partner.address)
            assertNull(partner.offer?.code)
            assertFalse(requireNotNull(partner.offer).usable)
            assertEquals(PartnerEvidence.USER_REQUESTED, partner.evidence)
        }
    }
    @Test fun directoryListingsRemainUnconfirmedAndAttributed() {
        assertTrue(ChargingCatalog.stations.size >= 5)
        ChargingCatalog.stations.forEach {
            assertTrue(it.source.startsWith("https://"))
            assertNull(it.verifiedAt)
            assertEquals(ChargingEvidence.UNCONFIRMED, it.evidence)
        }
    }
    @Test fun categoryAndSearchDoNotDropKnownBusinesses() {
        assertEquals(5, PartnerCatalog.search(category = PartnerCategory.CHARGING).size)
        assertEquals("gpcar-insulfilm", PartnerCatalog.search("insulfilm").single().id)
    }
}
