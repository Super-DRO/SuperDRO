package org.superdro.core.ui.graphics

import org.superdro.core.ui.camera.CameraVideoPresenter
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.functions.graphics.PlaneAxes
import org.superdro.core.resources.Res
import org.superdro.core.resources.route_align_axes
import org.superdro.core.resources.route_instrument
import org.superdro.core.ui.graphics.drawing.DeleteDrawingDialog
import org.superdro.core.ui.graphics.drawing.DeleteShapeDialog
import org.superdro.core.ui.graphics.drawing.DrawingEventEffect
import org.superdro.core.ui.graphics.drawing.DrawingLayerList
import org.superdro.core.ui.graphics.drawing.DxfExportDialog
import org.superdro.core.ui.graphics.drawing.drawingController
import org.superdro.core.ui.graphics.image.DeleteReferenceImageDialog
import org.superdro.core.ui.graphics.image.ReferenceImageOpacityButton
import org.superdro.core.ui.graphics.image.ReferenceImageRequests
import org.superdro.core.ui.graphics.image.referenceImageController
import org.superdro.core.ui.graphics.measure.ControllerMeasurementEvents
import org.superdro.core.ui.graphics.measure.MeasurementLogButton
import org.superdro.core.ui.graphics.measure.MeasurementModeToggle
import org.superdro.core.ui.graphics.measure.MeasurementPane
import org.superdro.core.ui.graphics.measure.MeasurementTheme
import org.superdro.core.ui.graphics.measure.measurementController
import org.superdro.core.ui.graphics.view.DisplayPaneEvents
import org.superdro.core.ui.graphics.view.GraphicalViewParts
import org.superdro.core.ui.graphics.view.MeasurementMark
import org.superdro.core.ui.graphics.view.ReferenceLayer
import org.superdro.core.ui.navigation.AlignAxesRoute
import org.superdro.core.ui.navigation.InstrumentRoute
import org.superdro.core.ui.navigation.TabEntryScope
import org.superdro.core.camera.CameraViewSettings
import org.superdro.core.model.GraphicalDisplay
import org.superdro.core.ui.camera.CameraBackdrop
import org.superdro.core.ui.camera.rememberCameraVideo
import org.superdro.core.ui.graphics.view.CameraSceneOverlay
import org.superdro.core.ui.graphics.view.graphicalTab

/**
 * Gives the parts of the graphical view from the other packages of the graphics area:
 *
 * - the measurement mode switch, the log button, the align axes and instrument buttons, the
 *   measurement pane, the measurement points layer, the tap on a sub-datum as a point
 *   source, and the measurement accent;
 * - the reference image layer and the actions of the reference image row;
 * - the actions of the drawing row, the layer list, the "Delete Geometry?" question, and the DXF
 *   export;
 * - the video of the USB camera behind the canvas, with its overlay rules.
 */
@Composable
fun graphicalViewParts(scope: TabEntryScope, deps: GraphicsDependencies): GraphicalViewParts {
    val measurement = scope.measurementController(deps)
    val image = scope.referenceImageController(deps)
    val drawing = scope.drawingController(deps)
    val measure by measurement.uiState.collectAsState()
    val bitmap by image.image.collectAsState()
    // The USB camera: the camera row of the display pane starts and stops the video,
    // and the video goes behind the canvas of the view.
    val tab = scope.graphicalTab(deps)
    val controls by tab.controls.collectAsState()
    val video = scope.rememberCameraVideo()
    // The frame rate of the video state changes four times each second. The parts use only the
    // play state and the settings, so they collect only these two values.
    val videoOverlay by remember(video) {
        video?.state?.map { state -> CameraSceneOverlay(active = state.active, settings = state.settings) }?.distinctUntilChanged() ?: flowOf(null)
    }.collectAsState(initial = video?.state?.value?.let { state -> CameraSceneOverlay(active = state.active, settings = state.settings) })
    val cameraShown = controls.display.cameraShown && video != null
    CameraBackdropSync(video, controls.display.cameraShown)
    val camera = CameraSceneOverlay(active = cameraShown && videoOverlay?.active == true, settings = videoOverlay?.settings ?: CameraViewSettings())
    var deleteDrawing by remember { mutableStateOf(false) }
    var deleteImage by remember { mutableStateOf(false) }
    var export by remember { mutableStateOf(false) }
    var includeHidden by remember { mutableStateOf(false) }
    val measureEvents = remember(measurement) { ControllerMeasurementEvents(measurement) }
    val measurementMarks = remember(measure.points, measure.plane) {
        measure.points.map { point -> MeasurementMark(PlaneAxes.toView(measure.plane, point.xUm, point.yUm), point.suspect) }
    }

    LaunchedEffect(measurement) { measurement.notices.collect { text -> scope.showMessage(text) } }
    ReferenceImageRequests(image, scope.navigator, scope::showMessage)
    DrawingEventEffect(drawing, scope.navigator, scope::showMessage)

    return GraphicalViewParts(
        pointTapInterceptor = measurement::onSubDatumTap,
        extraTopStrip = {
            val reference = bitmap
            if (reference != null) {
                ReferenceImageOpacityButton(alpha = reference.alpha, hasImage = true, onTap = image::cycleAlpha, onLongPress = { deleteImage = true })
            }
            if (measure.available) {
                MeasurementModeToggle(on = measure.modeOn, onToggle = { measurement.toggleMeasurementMode() })
                if (measure.modeOn) {
                    MeasurementLogButton(measure.logCount, onClick = { measurement.onLogButton()?.let(scope.navigator::push) })
                    AssistChip(
                        onClick = { scope.navigator.push(AlignAxesRoute) },
                        label = { Text(stringResource(Res.string.route_align_axes)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                    AssistChip(
                        onClick = { scope.navigator.push(InstrumentRoute) },
                        label = { Text(measure.instrumentName ?: stringResource(Res.string.route_instrument)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        },
        leftPanel = if (measure.modeOn) ({ MeasurementPane(measure, measureEvents) }) else null,
        referenceImage = bitmap?.takeIf { camera.referenceImageShown }?.let { value -> ReferenceLayer(value.bitmap, value.matrix, value.alpha) },
        measurementMarks = measurementMarks,
        displayEvents = { base ->
            object : DisplayPaneEvents by base {
                override fun onDisplay(change: (GraphicalDisplay) -> GraphicalDisplay) {
                    val before = tab.controls.value.display
                    base.onDisplay(change)
                    val after = change(before)
                    if (after.cameraShown != before.cameraShown) video?.setWanted(after.cameraShown)
                }
                override fun onImportDrawing() = drawing.importFile()
                override fun onExportDrawing() {
                    export = true
                }
                override fun onClearDrawing() {
                    deleteDrawing = true
                }
                override fun onLayerHidden(index: Int, hidden: Boolean) = drawing.setLayerHidden(index, hidden)
                override fun onReferenceAlpha(alpha: Int) = image.setAlpha(alpha)
                override fun onClearReference() {
                    deleteImage = true
                }
                override fun onTakePhoto() = image.takePhoto()
                override fun onSelectImage() = image.selectFile()
            }
        },
        dialogs = {
            if (deleteDrawing) {
                DeleteDrawingDialog(
                    onConfirm = {
                        deleteDrawing = false
                        drawing.deleteDrawing()
                    },
                    onDismiss = { deleteDrawing = false },
                )
            }
            if (deleteImage) {
                DeleteReferenceImageDialog(
                    onConfirm = {
                        deleteImage = false
                        image.delete()
                    },
                    onDismiss = { deleteImage = false },
                )
            }
            if (export) {
                DxfExportDialog(
                    includeHidden = includeHidden,
                    onIncludeHidden = { value -> includeHidden = value },
                    onExport = {
                        export = false
                        drawing.exportDxf(includeHidden)
                    },
                    onDismiss = { export = false },
                )
            }
        },
        theme = { content -> MeasurementTheme(active = measure.modeOn, content = content) },
        deleteShapeDialog = { _, type, onConfirm, onDismiss -> DeleteShapeDialog(type, onConfirm, onDismiss) },
        layerList = { layers -> DrawingLayerList(layers, onToggle = { index, hidden -> drawing.setLayerHidden(index, hidden) }) },
        edgeFinderHidden = measure.verb != null,
        backdrop = if (cameraShown) ({ scope.CameraBackdrop() }) else null,
        camera = camera,
    )
}

/**
 * Keeps the video of the backdrop in step with the stored camera row of the display pane: the video
 * plays while the row is "Shown", also after a restart of the app and after a return from the camera screen. A
 * request that the video already has does nothing ([CameraVideoPresenter.setWanted]).
 */
@Composable
internal fun CameraBackdropSync(video: CameraVideoPresenter?, shown: Boolean) {
    LaunchedEffect(video, shown) { video?.setWanted(shown) }
}
