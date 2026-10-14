package org.superdro.core.ui.graphics.render

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.superdro.core.functions.graphics.Matrix3
import org.superdro.core.functions.graphics.ScreenMetrics
import org.superdro.core.functions.graphics.ViewTransform

class ScreenPathCacheTest {

    private val metrics = ScreenMetrics(xdpi = 254.0, ydpi = 254.0, density = 1.5)

    /** A square of 10 mm with the corner at (5 mm, 5 mm), in model µm. */
    private val model = Path().apply { addRect(Rect(5_000f, 5_000f, 15_000f, 15_000f)) }

    private fun matrixOf(originX: Double, originY: Double, scale: Double): Matrix =
        ShapePaths.toScreen(ViewTransform(originX, originY, scale, metrics))

    @Test
    fun aPanKeepsTheLinearPartAndTheScreenCopy() {
        val cache = ScreenPathCache()
        val first = matrixOf(100.0, 200.0, 1.0)
        val panned = matrixOf(340.0, -75.0, 1.0)
        val part = assertNotNullPart(first)
        assertEquals(part, LinearPart.of(panned))
        val screen = cache.screenPath(model, part)
        assertSame(screen, cache.screenPath(model, assertNotNullPart(panned)))
        assertEquals(1, cache.size)
    }

    @Test
    fun theCopyAndTheTranslationGiveTheFullScreenPath() {
        val matrix = matrixOf(340.0, -75.0, 2.5)
        val screen = ScreenPathCache().screenPath(model, assertNotNullPart(matrix))
        val moved = screen.getBounds().translate(matrix.values[Matrix.TranslateX], matrix.values[Matrix.TranslateY])
        near(ShapePaths.screenPath(model, matrix).getBounds(), moved)
    }

    @Test
    fun aZoomMakesANewCopy() {
        val cache = ScreenPathCache()
        val first = cache.screenPath(model, assertNotNullPart(matrixOf(0.0, 0.0, 1.0)))
        val zoomed = cache.screenPath(model, assertNotNullPart(matrixOf(0.0, 0.0, 5.0)))
        assertNotSame(first, zoomed)
        assertEquals(1, cache.size)
        near(Rect(first.getBounds().topLeft * 5f, first.getBounds().bottomRight * 5f), zoomed.getBounds())
    }

    @Test
    fun aRotationIsPartOfTheLinearPart() {
        val rotated = ShapePaths.toScreen(ViewTransform(0.0, 0.0, 1.0, metrics), Matrix3.rotationAbout(0.0, 1.0, 0.0, 0.0))
        val part = assertNotNullPart(rotated)
        assertTrue(part.skewX != 0f && part.skewY != 0f)
        assertTrue(part != LinearPart.of(matrixOf(0.0, 0.0, 1.0)))
    }

    @Test
    fun eachModelPathHasItsOwnCopy() {
        val cache = ScreenPathCache()
        val part = assertNotNullPart(matrixOf(0.0, 0.0, 1.0))
        val other = Path().apply { addRect(Rect(0f, 0f, 1_000f, 1_000f)) }
        val a = cache.screenPath(model, part)
        val b = cache.screenPath(other, part)
        assertNotSame(a, b)
        assertSame(a, cache.screenPath(model, part))
        assertEquals(2, cache.size)
    }

    @Test
    fun aProjectiveMatrixHasNoLinearPart() {
        val projective = ShapePaths.composeMatrix(Matrix3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.001, 0.0, 1.0))
        assertNull(LinearPart.of(projective))
    }

    private fun assertNotNullPart(matrix: Matrix): LinearPart = LinearPart.of(matrix) ?: error("No linear part")

    private fun near(expected: Rect, actual: Rect) {
        val tolerance = 0.01f
        assertTrue(
            abs(expected.left - actual.left) < tolerance && abs(expected.top - actual.top) < tolerance &&
                abs(expected.right - actual.right) < tolerance && abs(expected.bottom - actual.bottom) < tolerance,
            "Expected $expected, got $actual",
        )
    }
}
