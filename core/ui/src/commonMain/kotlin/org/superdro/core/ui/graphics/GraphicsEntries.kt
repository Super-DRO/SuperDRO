package org.superdro.core.ui.graphics

import org.superdro.core.ui.graphics.drawing.drawingEntries
import org.superdro.core.ui.graphics.image.imageEntries
import org.superdro.core.ui.graphics.measure.measureEntries
import org.superdro.core.ui.graphics.probe.probeEntries
import org.superdro.core.ui.graphics.view.viewEntries
import org.superdro.core.ui.navigation.EntryRegistry
import org.superdro.core.ui.navigation.RouteArea

/**
 * The entries of the graphics area. The central provider
 * ([org.superdro.core.ui.navigation.appEntryRegistry]) calls this function after the functions
 * area, so the entries of the edge finder, the rotate part sheet, the align axes
 * sheet, and the reference instrument sheet replace the placeholders of the
 * functions area.
 *
 * Each part of the area registers its entries in its own package: the graphical view, the
 * reference image, the probe functions, the measurement mode, and the drawings. The four entries
 * of the routes of the functions area keep the area tag [RouteArea.Functions] of the route file.
 */
fun EntryRegistry.graphicsEntries() {
    viewEntries(parts = { scope, deps -> graphicalViewParts(scope, deps) })
    imageEntries()
    probeEntries()
    measureEntries()
    drawingEntries()
    // The camera setup is the work of the camera area.
}
