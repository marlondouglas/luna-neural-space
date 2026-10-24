package space.neural.luna.navigation

import org.junit.Assert.*
import org.junit.Test
import org.osmdroid.util.GeoPoint

class RouteProgressTest {
    private val start = GeoPoint(-18.85, -41.94)
    private val end = GeoPoint(-18.85, -41.92)
    private val maneuver = GeoPoint(-18.85, -41.925)
    private val route = Route(listOf(start, end), start.distanceToAsDouble(end), 600.0,
        listOf(RouteStep(start, "Siga em frente", 0.0), RouteStep(maneuver, "Vire à direita", 1500.0)))
    @Test fun maneuverDoesNotNeedToEqualGeometryVertex() {
        val result = RouteProgress.at(route, GeoPoint(-18.85, -41.93), end)
        assertEquals("Vire à direita", result.instruction)
        assertFalse(result.offRoute)
        assertEquals(300.0, result.remainingSeconds, 1.0)
        assertEquals(route.meters / 2, result.remainingMeters, 2.0)
    }
    @Test fun farFromRoadRequiresRecalculation() {
        assertTrue(RouteProgress.at(route, GeoPoint(-18.86, -41.93), end).offRoute)
    }
    @Test fun nextManeuverUsesDistanceAlongBentRoad() {
        val a = GeoPoint(0.0, 0.0)
        val b = GeoPoint(0.0, 0.01)
        val c = GeoPoint(0.01, 0.01)
        val geometry = a.distanceToAsDouble(b) + b.distanceToAsDouble(c)
        val bent = Route(listOf(a, b, c), geometry * 1.1, 800.0,
            listOf(RouteStep(c, "Vire à esquerda", geometry, "turn", "left")))
        val result = RouteProgress.at(bent, a, c)
        assertEquals(geometry * 1.1, result.nextManeuverMeters!!, 2.0)
        assertTrue(result.nextManeuverMeters!! > a.distanceToAsDouble(c))
        assertEquals("turn", result.maneuverType)
        assertEquals("left", result.maneuverModifier)
    }
    @Test fun arrivalRequiresCloseDestinationAndGeometryEnd() {
        val result = RouteProgress.at(route, end, end)
        assertTrue(result.arrived)
        assertEquals(0.0, result.remainingSeconds, 0.0)
    }
}
