package space.neural.luna.navigation

import org.junit.Assert.*
import org.junit.Test

class NavigationValidationTest {
    @Test fun endpointRejectsInsecureAndEmbeddedCredentials() {
        assertTrue(NavigationRepository.approvedEndpointFormat("https://routes.example.org/osrm"))
        listOf("", "http://routes.example.org", "https://user:secret@routes.example.org", "https://routes.example.org?key=secret", "https://routes.example.org#secret").forEach {
            assertFalse(it, NavigationRepository.approvedEndpointFormat(it))
        }
    }
    @Test fun coordinatesRejectNonFiniteAndOutOfRange() {
        assertTrue(NavigationRepository.validPoint(-18.85, -41.94))
        assertFalse(NavigationRepository.validPoint(Double.NaN, 0.0))
        assertFalse(NavigationRepository.validPoint(0.0, Double.POSITIVE_INFINITY))
        assertFalse(NavigationRepository.validPoint(91.0, 0.0))
        assertFalse(NavigationRepository.validPoint(0.0, -181.0))
    }
}
