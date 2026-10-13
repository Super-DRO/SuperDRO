package org.superdro.core.drawing

import kotlin.math.hypot
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import org.superdro.core.drawing.dxf.DxfConverter

/**
 * The ELLIPSE and SPLINE entities of `curves_r2000_mm` against the points of ezdxf
 * (`curves_r2000_mm.points.json`): each point of ezdxf is within the curve tolerance of 1 µm
 * of the Bézier chain, with 0.1 µm more for the round-off to nm and for the search.
 */
class CurveAccuracyTest {

    private fun reference(): List<Pair<String, List<Pair<Double, Double>>>> {
        val root = Json.parseToJsonElement(Fixtures.text("dxf/curves_r2000_mm.points.json")) as JsonArray
        return root.map { item ->
            val entry = item as JsonObject
            val points = (entry["points"] as JsonArray).map { point ->
                val pair = point as JsonArray
                (pair[0] as JsonPrimitive).double to (pair[1] as JsonPrimitive).double
            }
            (entry["type"] as JsonPrimitive).content to points
        }
    }

    /**
     * The distance (nm) from (x, y) to the chain: the nearest of 64 samples on each segment, then a
     * golden-section search near that sample.
     */
    private fun distance(chain: WireframeBezier, x: Double, y: Double): Double {
        val p = chain.points
        fun at(segment: Int, t: Double): Double {
            val i = segment * 3
            val s = 1 - t
            val bx = s * s * s * p[i].x + 3 * s * s * t * p[i + 1].x + 3 * s * t * t * p[i + 2].x + t * t * t * p[i + 3].x
            val by = s * s * s * p[i].y + 3 * s * s * t * p[i + 1].y + 3 * s * t * t * p[i + 2].y + t * t * t * p[i + 3].y
            return hypot(bx - x, by - y)
        }
        var best = Double.MAX_VALUE
        for (segment in 0 until chain.segmentCount) {
            var bestT = 0.0
            var bestHere = Double.MAX_VALUE
            for (step in 0..64) {
                val d = at(segment, step / 64.0)
                if (d < bestHere) {
                    bestHere = d
                    bestT = step / 64.0
                }
            }
            var low = maxOf(0.0, bestT - 1.0 / 64)
            var high = minOf(1.0, bestT + 1.0 / 64)
            val ratio = 0.6180339887498949
            repeat(80) {
                val a = high - (high - low) * ratio
                val b = low + (high - low) * ratio
                if (at(segment, a) < at(segment, b)) high = b else low = a
            }
            best = min(best, min(bestHere, at(segment, (low + high) / 2)))
        }
        return best
    }

    @Test
    fun eachCurveIsWithinTheToleranceOfTheEzdxfPoints() {
        val references = reference()
        val result = DxfConverter.convert(Fixtures.bytes("dxf/curves_r2000_mm.dxf"), now = Fixtures.NOW)
        val chains = result.wireframe!!.entities.map { entity -> entity as WireframeBezier }
        assertEquals(references.size, chains.size)
        assertEquals(emptyList(), result.warnings)
        references.zip(chains).forEachIndexed { index, (reference, chain) ->
            val (type, points) = reference
            var worst = 0.0
            for ((x, y) in points) worst = maxOf(worst, distance(chain, x * 1_000_000, y * 1_000_000))
            assertTrue(worst <= 1100.0, "curve $index ($type): $worst nm")
        }
    }

    @Test
    fun theClosedCurvesAreClosedChains() {
        val chains = DxfConverter.convert(Fixtures.bytes("dxf/curves_r2000_mm.dxb"), now = Fixtures.NOW).wireframe!!.entities.map { entity -> entity as WireframeBezier }
        // The full ellipses (0 and 2) and the closed periodic spline (7).
        assertEquals(listOf(true, false, true, false, false, false, false, true), chains.map { chain -> chain.closed })
    }
}
