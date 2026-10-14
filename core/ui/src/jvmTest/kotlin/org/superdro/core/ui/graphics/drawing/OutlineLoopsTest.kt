package org.superdro.core.ui.graphics.drawing

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.core.functions.graphics.Vec2
import org.superdro.core.model.DrawingShape
import org.superdro.core.model.ShapeType

/**
 * The tests of the join of the outline loops. The grid join must give the same loops, in the same
 * order and with the same direction, as the first join that compared each pair of chains.
 */
class OutlineLoopsTest {

    /** The first join: O(n²). The tests use it as the reference. */
    private fun referenceJoin(chains: List<List<Vec2>>, tolerance: Double): List<List<Vec2>> {
        val loops = ArrayList<List<Vec2>>()
        val open = chains.mapTo(ArrayList()) { chain -> chain.toMutableList() }
        fun near(a: Vec2, b: Vec2) = a.distanceTo(b) <= tolerance
        while (open.isNotEmpty()) {
            val chain = open.removeAt(open.size - 1)
            var grown = true
            while (grown && !(chain.size > 2 && near(chain.first(), chain.last()))) {
                grown = false
                val iterator = open.listIterator()
                while (iterator.hasNext()) {
                    val other = iterator.next()
                    when {
                        near(chain.last(), other.first()) -> chain.addAll(other.drop(1))
                        near(chain.last(), other.last()) -> chain.addAll(other.reversed().drop(1))
                        near(chain.first(), other.last()) -> chain.addAll(0, other.dropLast(1))
                        near(chain.first(), other.first()) -> chain.addAll(0, other.reversed().dropLast(1))
                        else -> continue
                    }
                    iterator.remove()
                    grown = true
                    break
                }
            }
            if (chain.size > 2 && near(chain.first(), chain.last())) loops += chain.dropLast(1)
        }
        return loops
    }

    /** Gives the segments of a polygon with [count] corners, with a middle point in some segments. */
    private fun polygon(random: Random, center: Vec2, radius: Double, count: Int): List<List<Vec2>> {
        val corners = (0 until count).map { index ->
            val angle = 2.0 * PI * index / count
            Vec2(center.x + radius * cos(angle), center.y + radius * sin(angle))
        }
        return corners.indices.map { index ->
            val a = corners[index]
            val b = corners[(index + 1) % count]
            if (random.nextBoolean()) listOf(a, b) else listOf(a, (a + b) / 2.0 + Vec2(1.0, -1.0), b)
        }
    }

    /** Moves the end points by a distance near the tolerance: a little less or a little more. */
    private fun jitter(random: Random, chain: List<Vec2>, tolerance: Double): List<Vec2> {
        fun move(point: Vec2): Vec2 {
            val angle = random.nextDouble(0.0, 2.0 * PI)
            val distance = tolerance * random.nextDouble(0.45, 0.55)
            return point + Vec2(cos(angle), sin(angle)) * distance
        }
        val out = chain.toMutableList()
        if (random.nextInt(4) == 0) out[0] = move(out[0])
        if (random.nextInt(4) == 0) out[out.size - 1] = move(out[out.size - 1])
        return out
    }

    private fun mixed(random: Random, polygons: Int, tolerance: Double, nearTolerance: Boolean): List<List<Vec2>> {
        val chains = ArrayList<List<Vec2>>()
        repeat(polygons) { index ->
            val center = Vec2(index * 50.0 + random.nextDouble(-5.0, 5.0), random.nextDouble(-20.0, 20.0))
            chains += polygon(random, center, random.nextDouble(5.0, 40.0), random.nextInt(3, 12))
        }
        // Open chains that do not close, and chains that branch at one point.
        repeat(polygons) {
            val a = Vec2(random.nextDouble(-100.0, 1000.0), random.nextDouble(-100.0, 100.0))
            chains += listOf(a, a + Vec2(random.nextDouble(-30.0, 30.0), random.nextDouble(-30.0, 30.0)))
        }
        val shuffled = chains.shuffled(random).map { chain -> if (random.nextBoolean()) chain.reversed() else chain }
        return if (nearTolerance) shuffled.map { chain -> jitter(random, chain, tolerance) } else shuffled
    }

    @Test
    fun theGridJoinIsTheSameAsTheReferenceForShuffledAndReversedChains() {
        for (seed in 0 until 300) {
            val random = Random(seed)
            val tolerance = listOf(10.0, 1.0, 0.0, 25.4)[seed % 4]
            val chains = mixed(random, random.nextInt(1, 12), tolerance, nearTolerance = false)
            assertEquals(referenceJoin(chains, tolerance), OutlineLoops.join(chains, tolerance), "seed $seed")
        }
    }

    @Test
    fun theGridJoinIsTheSameAsTheReferenceNearTheTolerance() {
        for (seed in 0 until 300) {
            val random = Random(1_000 + seed)
            val tolerance = listOf(10.0, 2.0, 25.4)[seed % 3]
            val chains = mixed(random, random.nextInt(1, 12), tolerance, nearTolerance = true)
            assertEquals(referenceJoin(chains, tolerance), OutlineLoops.join(chains, tolerance), "seed $seed")
        }
    }

    @Test
    fun endPointsJustInsideTheToleranceJoinAndJustOutsideDoNot() {
        val tolerance = OutlineLoops.JOIN_TOLERANCE_UM
        val square = listOf(
            listOf(Vec2(0.0, 0.0), Vec2(100.0, 0.0)),
            listOf(Vec2(100.0, 0.0), Vec2(100.0, 100.0)),
            listOf(Vec2(100.0, 100.0), Vec2(0.0, 100.0)),
        )
        val inside = square + listOf(listOf(Vec2(0.0, 100.0), Vec2(0.0, tolerance)))
        val outside = square + listOf(listOf(Vec2(0.0, 100.0), Vec2(0.0, tolerance + 1e-6)))
        assertEquals(1, OutlineLoops.join(inside, tolerance).size)
        assertEquals(referenceJoin(inside, tolerance), OutlineLoops.join(inside, tolerance))
        assertEquals(0, OutlineLoops.join(outside, tolerance).size)
        // Points at the two sides of a cell border join.
        val border = listOf(
            listOf(Vec2(-0.5, 0.0), Vec2(50.0, 50.0)),
            listOf(Vec2(50.0, 50.0), Vec2(0.0, 50.0)),
            listOf(Vec2(0.0, 50.0), Vec2(0.5, 0.0)),
        )
        assertEquals(referenceJoin(border, tolerance), OutlineLoops.join(border, tolerance))
        assertEquals(1, OutlineLoops.join(border, tolerance).size)
    }

    @Test
    fun aLargeShuffledOutlineIsTheSameAsTheReference() {
        val random = Random(7)
        val chains = polygon(random, Vec2(0.0, 0.0), 10_000.0, 500).shuffled(random)
            .map { chain -> if (random.nextBoolean()) chain.reversed() else chain }
        val expected = referenceJoin(chains, 10.0)
        assertEquals(1, expected.size)
        assertEquals(expected, OutlineLoops.join(chains, 10.0))
    }

    @Test
    fun theLoopsOfShapesKeepTheClosedShapesFirst() {
        val shapes = listOf(
            DrawingShape(type = ShapeType.Line, x1 = 0, y1 = 0, x2 = 1_000_000, y2 = 0),
            DrawingShape(type = ShapeType.Circle, x1 = 0, y1 = 0, radius = 100_000),
            DrawingShape(type = ShapeType.Line, x1 = 1_000_000, y1 = 0, x2 = 0, y2 = 1_000_000),
            DrawingShape(type = ShapeType.Line, x1 = 0, y1 = 1_000_000, x2 = 0, y2 = 0),
        )
        val loops = OutlineLoops.loops(shapes)
        assertEquals(2, loops.size)
        assertTrue(loops[0].size in 72..73)
        assertEquals(listOf(Vec2(0.0, 1_000.0), Vec2(0.0, 0.0), Vec2(1_000.0, 0.0)), loops[1])
    }
}
