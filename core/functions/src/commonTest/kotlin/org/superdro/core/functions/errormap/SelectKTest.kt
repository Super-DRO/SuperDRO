package org.superdro.core.functions.errormap

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The fast [MapBuilderSteps.selectK], [MapBuilderSteps.heldOutResiduals],
 * [MapBuilderSteps.fitNodes], and [MapBuilderSteps.isotonic] against the first versions
 * ([ReferenceSelectK]). The results must be equal, with no tolerance.
 */
class SelectKTest {

    /**
     * A fold of [count] samples. The positions are random integers in `[0, range)`, so equal
     * positions occur in a fold and in different folds. The deltas are random half counts, with
     * some `−0.0` values.
     */
    private fun fold(random: Random, count: Int, range: Int): List<MapSample> = List(count) {
        val delta = when (random.nextInt(10)) {
            0 -> -0.0
            1 -> 0.0
            else -> (random.nextInt(-40, 41) / 2.0) + (random.nextInt(0, 3000) % 7)
        }
        MapSample(random.nextInt(range).toDouble(), delta)
    }

    private fun assertSameAsReference(folds: List<List<MapSample>>) {
        for (k in MapBuilderSteps.K_CANDIDATES + listOf(1, 5, 200)) {
            assertEquals(
                ReferenceSelectK.heldOutResiduals(k, folds).map { fold -> fold.map { it.toRawBits() } },
                MapBuilderSteps.heldOutResiduals(k, folds).map { fold -> fold.map { it.toRawBits() } },
                "residuals for K = $k",
            )
            val all = folds.flatten()
            assertEquals(ReferenceSelectK.fitNodes(k, all), MapBuilderSteps.fitNodes(k, all), "nodes for K = $k")
        }
        assertEquals(ReferenceSelectK.selectK(folds), MapBuilderSteps.selectK(folds))
    }

    @Test
    fun randomFoldsGiveTheSameResult() {
        val random = Random(11)
        repeat(20) {
            val count = random.nextInt(1, 6)
            val folds = List(count) { fold(random, random.nextInt(0, 400), random.nextInt(1, 2000)) }
            assertSameAsReference(folds)
        }
    }

    @Test
    fun sortedPassesWithASignalGiveTheSameResult() {
        val random = Random(12)
        val folds = List(4) { p ->
            (0 until 1500).map { i ->
                val x = i * 7.0 + p
                MapSample(x, 10.0 * kotlin.math.sin(x / 900.0) + random.nextInt(-3, 4))
            }
        }
        assertSameAsReference(folds)
        assertSameAsReference(MapBuilderSteps.folds(listOf(folds[0])))
    }

    @Test
    fun edgeCasesGiveTheSameResult() {
        val random = Random(13)
        assertSameAsReference(emptyList())
        assertSameAsReference(listOf(emptyList()))
        assertSameAsReference(listOf(emptyList(), fold(random, 50, 100)))
        assertSameAsReference(listOf(fold(random, 3, 10), fold(random, 2, 10)))
        assertSameAsReference(listOf(fold(random, 40, 1), fold(random, 40, 1)))
        assertSameAsReference(List(3) { (0 until 30).map { MapSample(5.0, 0.0) } })
        assertSameAsReference(MapBuilderSteps.folds(listOf(fold(random, 63, 1000))))
        assertSameAsReference(MapBuilderSteps.folds(listOf(fold(random, 64, 1000))))
    }

    @Test
    fun theIsotonicRegressionGivesTheSameResult() {
        val random = Random(14)
        repeat(50) {
            val values = DoubleArray(random.nextInt(0, 200)) { random.nextInt(-20, 20) + random.nextDouble() }
            assertEquals(
                ReferenceSelectK.isotonic(values).map { it.toRawBits() },
                MapBuilderSteps.isotonic(values).map { it.toRawBits() },
            )
        }
    }
}
