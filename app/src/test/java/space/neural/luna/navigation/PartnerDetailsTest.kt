package space.neural.luna.navigation

import org.junit.Assert.*
import org.junit.Test
import space.neural.luna.partners.*
import space.neural.luna.charging.ConnectorState

class PartnerDetailsTest {
    @Test fun contactsCannotOpenArbitrarySchemesOrImpersonatedHosts() {
        assertEquals("https://wa.me/5533999999999", PartnerLinks.whatsapp("+55 (33) 99999-9999"))
        assertEquals("tel:+5533999999999", PartnerLinks.phone("+55 (33) 99999-9999"))
        assertEquals("https://www.instagram.com/parceiro_gv/", PartnerLinks.instagram("@parceiro_gv"))
        listOf("javascript:alert(1)", "https://instagram.com.evil.example/profile", "https://user@instagram.com/profile", "https://instagram.com/profile?token=x").forEach {
            assertNull(PartnerLinks.instagram(it))
        }
        assertNull(PartnerLinks.phone("tel:190;extra"))
        assertNull(PartnerLinks.whatsapp("https://evil.example"))
    }
    @Test fun onlyLocalApprovedAssetPathsAreEligible() {
        assertEquals("partners/local_photo.webp", PartnerLinks.safeImageAsset("partners/local_photo.webp"))
        listOf("../secret.png", "/absolute.png", "https://example.org/photo.jpg", "other.svg").forEach { assertNull(PartnerLinks.safeImageAsset(it)) }
    }
    @Test fun confirmedOfferNeedsSourceAndValidDateAndMoney() {
        val offer = PartnerOffer("Condição de teste", state = OfferState.CONFIRMED, priceCents = 1500, unit = "serviço", validFrom = 1000, validUntil = 2000, source = "Estabelecimento autorizado")
        assertTrue(offer.usableAt(1500))
        assertFalse(offer.usableAt(2000))
        assertFalse(offer.usableAt(999))
        assertFalse(offer.copy(source = null).usableAt(1500))
        assertFalse(offer.copy(priceCents = -1).usableAt(1500))
        assertFalse(offer.copy(unit = null).usableAt(1500))
    }
    @Test fun openingHoursNeverDetermineConnectorAvailability() {
        val connector = PartnerConnectorReading("ccs-1", "CCS2", 80.0, ConnectorState.OCCUPIED, "Operador autorizado", 1000, 2000)
        val partner = Partner("fixture", "Fixture", null, PartnerCategory.CHARGING, "Fixture source",
            openingHours = PartnerOpeningHours("24 horas", "Estabelecimento", 1000), connectorReadings = listOf(connector))
        assertEquals(ConnectorState.OCCUPIED, partner.connectorReadings.single().statusAt(1500))
        assertEquals(ConnectorState.UNKNOWN, partner.connectorReadings.single().statusAt(2000))
        assertEquals(ConnectorState.UNKNOWN, partner.connectorReadings.single().statusAt(999))
    }
    @Test fun expiredTariffAndUnconfirmedTariffCannotBeShownAsCurrent() {
        val tariff = PartnerTariff(239, "kWh", "Operador autorizado", 1000, 2000, PartnerDataState.LIVE)
        assertTrue(tariff.usableAt(1500))
        assertFalse(tariff.usableAt(2000))
        assertFalse(tariff.copy(state = PartnerDataState.UNCONFIRMED).usableAt(1500))
    }
    @Test fun categoriesExistWithoutInventingBusinesses() {
        assertTrue(PartnerCategory.entries.containsAll(listOf(PartnerCategory.SUPERMARKET, PartnerCategory.TIRES, PartnerCategory.OIL_CHANGE, PartnerCategory.FOOD)))
        assertTrue(PartnerCatalog.search(category = PartnerCategory.TIRES).isEmpty())
    }
}
