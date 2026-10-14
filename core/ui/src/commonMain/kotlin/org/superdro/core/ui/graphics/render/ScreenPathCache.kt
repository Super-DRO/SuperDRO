package org.superdro.core.ui.graphics.render

import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate

/**
 * The linear part of an affine matrix from model µm to screen pixels: the scale, the rotation,
 * and the skew, with no translation. Two matrices with the same linear part make the same screen
 * path, only at a different position.
 */
data class LinearPart(val scaleX: Float, val skewX: Float, val skewY: Float, val scaleY: Float) {

    /** Gives the matrix of this linear part with no translation. */
    fun toMatrix(): Matrix = Matrix().also { matrix ->
        val v = matrix.values
        v[Matrix.ScaleX] = scaleX
        v[Matrix.SkewX] = skewX
        v[Matrix.SkewY] = skewY
        v[Matrix.ScaleY] = scaleY
    }

    companion object {
        /** Gives the linear part of [matrix], or null for a projective matrix. */
        fun of(matrix: Matrix): LinearPart? {
            val v = matrix.values
            if (v[Matrix.Perspective0] != 0f || v[Matrix.Perspective1] != 0f || v[Matrix.Perspective2] != 1f) return null
            return LinearPart(v[Matrix.ScaleX], v[Matrix.SkewX], v[Matrix.SkewY], v[Matrix.ScaleY])
        }
    }
}

/**
 * The screen copies of model paths for one [LinearPart]. A pan or follow mode changes only the
 * translation of the view matrix, so the copies stay valid and the draw only moves them. A new
 * zoom or rotation clears the cache.
 *
 * The cache compares the model paths by identity. Use one cache for each set of model paths, for
 * example in the `remember` that makes the paths. The draw thread is the only user.
 */
class ScreenPathCache {
    private var part: LinearPart? = null
    private val models = ArrayList<Path>()
    private val screens = ArrayList<Path>()

    /** The number of screen copies in the cache. */
    val size: Int get() = screens.size

    /** Gives the copy of the model [path] through [part], with no translation. */
    fun screenPath(path: Path, part: LinearPart): Path {
        if (part != this.part) {
            clear()
            this.part = part
        }
        val index = models.indexOfFirst { model -> model === path }
        if (index >= 0) return screens[index]
        if (models.size >= MAX_PATHS) clear()
        val screen = ShapePaths.screenPath(path, part.toMatrix())
        models += path
        screens += screen
        return screen
    }

    private fun clear() {
        models.clear()
        screens.clear()
    }

    private companion object {
        /** The largest number of copies. A new selected shape adds a path, so the cache has a limit. */
        const val MAX_PATHS: Int = 32
    }
}

/**
 * Draws the model [path] through [matrix] with [draw]. The screen copy comes from [cache] for the
 * linear part of [matrix]. The translation of [matrix] moves the canvas, so a stroke width stays
 * in screen pixels. A projective matrix gets a new copy each time.
 */
inline fun DrawScope.drawModelPath(cache: ScreenPathCache, path: Path, matrix: Matrix, draw: DrawScope.(Path) -> Unit) {
    val part = LinearPart.of(matrix)
    if (part == null) {
        draw(ShapePaths.screenPath(path, matrix))
        return
    }
    val screen = cache.screenPath(path, part)
    translate(matrix.values[Matrix.TranslateX], matrix.values[Matrix.TranslateY]) { draw(screen) }
}
