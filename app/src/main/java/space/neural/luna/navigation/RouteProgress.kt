package space.neural.luna.navigation

import org.osmdroid.util.GeoPoint

data class NavigationProgress(val remainingMeters: Double, val instruction: String, val offRoute: Boolean, val arrived: Boolean, val remainingSeconds: Double = 0.0,
    val nextManeuverMeters: Double? = null, val maneuverType: String? = null, val maneuverModifier: String? = null)

/** Maneuver points are not necessarily vertices of overview geometry; never compare them by equality. */
object RouteProgress {
    fun at(route: Route, position: GeoPoint, destination: GeoPoint): NavigationProgress {
        if (route.points.size < 2) return NavigationProgress(0.0, "Percurso indisponível", true, false)
        val lengths = route.points.zipWithNext().map { (a, b) -> a.distanceToAsDouble(b) }
        val projections = route.points.zipWithNext().map { (a, b) -> project(position, a, b) }
        val nearest = projections.indices.minByOrNull { projections[it].second } ?: 0
        val remaining = lengths[nearest] * (1 - projections[nearest].first) + lengths.drop(nearest + 1).sum()
        val arrived = destination.distanceToAsDouble(position) <= 35 && remaining <= 60
        var nextDistance: Double? = null
        val next = route.steps.firstOrNull { step ->
            val stepSegment = route.points.zipWithNext().indices.minByOrNull { index -> project(step.point, route.points[index], route.points[index + 1]).second } ?: -1
            val ahead = (stepSegment > nearest || stepSegment == nearest && project(step.point, route.points[nearest], route.points[nearest + 1]).first >= projections[nearest].first) && step.point.distanceToAsDouble(position) > 20
            if (ahead) {
                val stepFraction = project(step.point, route.points[stepSegment], route.points[stepSegment + 1]).first
                nextDistance = if (stepSegment == nearest) lengths[nearest] * (stepFraction - projections[nearest].first)
                    else lengths[nearest] * (1 - projections[nearest].first) + lengths.subList(nearest + 1, stepSegment).sum() + lengths[stepSegment] * stepFraction
            }
            ahead
        }
        val totalGeometry = lengths.sum()
        val eta = if (totalGeometry > 0) route.seconds * (remaining / totalGeometry).coerceIn(0.0, 1.0) else 0.0
        val scale = if (totalGeometry > 0) route.meters / totalGeometry else 1.0
        return NavigationProgress(remaining * scale, if (arrived) "Você chegou ao destino" else next?.text ?: "Siga o percurso", projections[nearest].second > 80, arrived, if (arrived) 0.0 else eta,
            nextDistance?.coerceAtLeast(0.0)?.times(scale), next?.maneuverType, next?.modifier)
    }

    private fun project(position: GeoPoint, start: GeoPoint, end: GeoPoint): Pair<Double, Double> {
        val latScale = 111_195.0
        val lonScale = latScale * kotlin.math.cos(Math.toRadians(position.latitude))
        val ax = (start.longitude - position.longitude) * lonScale
        val ay = (start.latitude - position.latitude) * latScale
        val dx = (end.longitude - start.longitude) * lonScale
        val dy = (end.latitude - start.latitude) * latScale
        val lengthSquared = dx * dx + dy * dy
        val fraction = if (lengthSquared == 0.0) 0.0 else (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return fraction to kotlin.math.hypot(ax + fraction * dx, ay + fraction * dy)
    }
}
