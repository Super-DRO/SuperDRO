package org.superdro.core.ui.graphics.image

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import org.superdro.core.connection.DeviceId
import org.superdro.core.functions.graphics.ColorMatrix
import org.superdro.core.functions.graphics.ContrastPreservingGray
import org.superdro.core.functions.graphics.GrayWeights
import org.superdro.core.functions.graphics.ImageFitQuality
import org.superdro.core.functions.graphics.ImageFitRefusal
import org.superdro.core.functions.graphics.ImageFitResult
import org.superdro.core.functions.graphics.ImageReference
import org.superdro.core.functions.graphics.Matrix3
import org.superdro.core.functions.graphics.PixelTransform
import org.superdro.core.functions.graphics.PlaneAxes
import org.superdro.core.functions.graphics.ReferenceImageMath
import org.superdro.core.functions.graphics.Vec2
import org.superdro.core.model.AxisId
import org.superdro.core.model.Plane
import org.superdro.core.model.ReferenceImage
import org.superdro.core.model.newId
import org.superdro.core.readout.NumberPattern
import org.superdro.core.readout.ReadoutFrame
import org.superdro.core.readout.operations.AxisOperations
import org.superdro.core.resources.Res
import org.superdro.core.resources.refimage_could_not_set
import org.superdro.core.resources.refimage_enter_values
import org.superdro.core.resources.refimage_fit_cannot
import org.superdro.core.resources.refimage_fit_crossed
import org.superdro.core.resources.refimage_fit_inside_out
import org.superdro.core.resources.refimage_fit_not_diagonal
import org.superdro.core.resources.refimage_fit_same_position
import org.superdro.core.resources.refimage_fit_three
import org.superdro.core.resources.refimage_fit_too_oblique
import org.superdro.core.resources.refimage_fit_wrong_count
import org.superdro.core.resources.refimage_no_pending
import org.superdro.core.resources.refimage_save_failed
import org.superdro.core.resources.refimage_steep_angle
import org.superdro.core.resources.refimage_undo_zero
import org.superdro.core.store.StoreResult
import org.superdro.core.ui.graphics.GraphicsDependencies
import org.superdro.core.ui.graphics.common.LengthInput
import org.superdro.core.ui.graphics.common.evaluateLength
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.readout.structure
import org.superdro.core.ui.text.UiText

/**
 * One reference card of the setup screen.
 *
 * @property index The card number − 1 (0 to 3).
 * @property horizontalText The value of the horizontal plane axis in its display unit (X on a
 *   mill, Z on a lathe).
 * @property verticalText The value of the vertical plane axis (Y on a mill, X on a lathe).
 * @property reference The reference of a set card: the image pixel and the machine values.
 * @property active True for the next card that "Set" fills.
 */
@Immutable
data class ReferenceCardUiState(
    val index: Int,
    val visible: Boolean = index < 2,
    val horizontalText: String = "",
    val verticalText: String = "",
    val horizontalError: UiText? = null,
    val verticalError: UiText? = null,
    val error: UiText? = null,
    val reference: ImageReference? = null,
    val subDatumId: String? = null,
    val active: Boolean = false,
) {
    /** True after "Set". */
    val isSet: Boolean get() = reference != null
}

/** One sub-datum of the sub-datum list of a card. */
@Immutable
data class SubDatumOption(val id: String, val name: String)

/**
 * The state of the reference image setup screen.
 *
 * @property horizontalAxis The horizontal plane axis: X on a mill (plane XY), Z on a lathe.
 * @property horizontalValue The live absolute value of [horizontalAxis] as a shown text.
 * @property scale The screen pixels for each content pixel: the fit scale of the view times the zoom.
 * @property contentMatrix The matrix of the content: the identity, or the preview matrix.
 * @property colorMatrix The preview color matrix of the gray and invert tools, or null.
 * @property reticlePixel The image pixel under the reticle, in image coordinates, or null.
 * @property fitMessage The refusal of the fit of the set references, or null.
 * @property banner The quality banner "Steep angle", or null.
 * @property canApply True when "Apply" and "Preview" are enabled: 2 or 4 set references and an
 *   accepted fit.
 * @property closed True after a successful "Apply": the screen closes.
 */
@Immutable
data class ReferenceImageSetupUiState(
    val hasImage: Boolean = false,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val horizontalAxis: AxisId = AxisId.X,
    val verticalAxis: AxisId = AxisId.Y,
    val horizontalValue: String = "",
    val verticalValue: String = "",
    val horizontalMetric: Boolean = true,
    val verticalMetric: Boolean = true,
    val gray: Boolean = false,
    val invert: Boolean = false,
    val preview: Boolean = false,
    val cards: List<ReferenceCardUiState> = List(CARD_COUNT) { index -> ReferenceCardUiState(index, active = index == 0) },
    val subDatums: List<SubDatumOption> = emptyList(),
    val panX: Double = 0.0,
    val panY: Double = 0.0,
    val scale: Double = 1.0,
    val contentMatrix: Matrix3 = Matrix3.IDENTITY,
    val colorMatrix: ColorMatrix? = null,
    val nudgeStep: Int = NUDGE_STEPS[1],
    val reticlePixel: Vec2? = null,
    val fitMessage: UiText? = null,
    val banner: UiText? = null,
    val canApply: Boolean = false,
    val busy: Boolean = false,
    val message: UiText? = null,
    val closed: Boolean = false,
) {
    companion object {
        /** The number of reference cards. */
        const val CARD_COUNT: Int = 4

        /** The nudge steps in screen pixels. */
        val NUDGE_STEPS: List<Int> = listOf(1, 10, 100)
    }
}

/**
 * The presenter of the reference image setup screen ("Align Reference Image"). The entry of the
 * screen owns it.
 *
 * The view draws the content (the image, or the image through the preview matrix) so that the
 * content point `q` shows at `C + pan + (q − q_c) × scale`. `C` is the view center and `q_c` is
 * the content point of the image center. Thus the fixed reticle at `C` shows the content point
 * `q_c − pan / scale`, and [ReferenceImageMath.reticlePixel] gives its image pixel.
 *
 * The rules:
 * - "Set" on a card records the pixel under the reticle and the machine values of the card. The
 *   machine values are absolute values in µm with no sign: the horizontal and the vertical axis of
 *   the plane (X and Y on a mill, Z and X on a lathe). A lathe X field shows the shown value
 *   (diameter mode), and the card stores the radius.
 * - Reference 3 and Reference 4 show after two cards are set.
 * - Rotate and flip change the pixels of the image and move the set reference pixels with them.
 *   "Flip horizontal" changes left and right, "Flip vertical" changes top and bottom.
 * - Gray uses the weights of the contrast weight search. The preview uses a color filter.
 *   "Apply" changes the pixels: invert first, then gray.
 * - The fit refuses 3 references, points at the same position, two points that are not diagonal,
 *   crossed points, a singular matrix, and an image that turns inside out or is too oblique.
 *   A steep angle gives a banner.
 * - "Apply" writes the JPEG (quality 100) to `images/<uuid>.jpg` and stores the file name and the
 *   matrix in the workspace with the alpha 255. The store deletes the old file.
 *
 * @param initialImage The new picture from [ReferenceImageController.takePendingImage], or null.
 */
class ReferenceImageSetupPresenter(
    val deviceId: DeviceId,
    private val readout: TabReadout,
    private val deps: GraphicsDependencies,
    initialImage: RasterImage?,
) : ScopedPresenter(deps.context), ReferenceImageSetupEvents {

    private data class Session(
        val source: RasterImage?,
        val gray: Boolean = false,
        val invert: Boolean = false,
        val weights: GrayWeights? = null,
        val cards: List<ReferenceCardUiState> = List(ReferenceImageSetupUiState.CARD_COUNT) { index -> ReferenceCardUiState(index) },
        val preview: Boolean = false,
        val viewWidth: Double = 0.0,
        val viewHeight: Double = 0.0,
        val panX: Double = 0.0,
        val panY: Double = 0.0,
        val zoom: Double = 1.0,
        val nudgeStep: Int = ReferenceImageSetupUiState.NUDGE_STEPS[1],
        val busy: Boolean = false,
        val message: UiText? = null,
        val closed: Boolean = false,
    )

    private val session = MutableStateFlow(
        Session(source = initialImage, message = if (initialImage == null) UiText.of(Res.string.refimage_no_pending) else null),
    )
    private val mutableBitmap = MutableStateFlow<ImageBitmap?>(null)

    /** The image of the canvas: the image after rotate and flip, with no color change. */
    val bitmap: StateFlow<ImageBitmap?> = mutableBitmap.asStateFlow()

    /**
     * The state of the screen. Only a change of the session, the profile, or the workspace makes the
     * fit, the sub-datum list, and the color matrix again. Each frame changes only the two live
     * values.
     */
    val uiState: StateFlow<ReferenceImageSetupUiState> = combine(
        combine(session, readout.frame.structure()) { current, frame -> stateOf(current, frame) },
        readout.frame.map(::liveValues).distinctUntilChanged(),
    ) { state, live -> state.copy(horizontalValue = live.first, verticalValue = live.second) }
        .stateIn(scope, SharingStarted.Eagerly, stateOf(session.value, readout.frame.value))

    init {
        initialImage?.let(::updateBitmap)
    }

    // -------------------------------------------------------------------------------------
    // The view

    /** The view of the image has the size [widthPx] × [heightPx]. */
    override fun onViewSize(widthPx: Double, heightPx: Double) {
        session.update { current -> current.copy(viewWidth = widthPx, viewHeight = heightPx) }
    }

    /** A drag by ([dxPx], [dyPx]): the image moves with the finger. */
    override fun onPan(dxPx: Double, dyPx: Double) {
        session.update { current -> current.copy(panX = current.panX + dxPx, panY = current.panY + dyPx) }
    }

    /**
     * A pinch by [factor] about the view center. The pan scales with the zoom, so the reticle
     * stays on the same image point.
     */
    override fun onZoom(factor: Double) {
        if (!factor.isFinite() || factor <= 0.0) return
        session.update { current ->
            val zoom = (current.zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
            val ratio = zoom / current.zoom
            current.copy(zoom = zoom, panX = current.panX * ratio, panY = current.panY * ratio)
        }
    }

    /** A double tap: the pan goes to 0 and the image fits the view. */
    override fun onResetView() {
        session.update { current -> current.copy(panX = 0.0, panY = 0.0, zoom = 1.0) }
    }

    /** Selects the nudge step 1, 10, or 100 px. */
    override fun onNudgeStep(step: Int) {
        if (step in ReferenceImageSetupUiState.NUDGE_STEPS) session.update { current -> current.copy(nudgeStep = step) }
    }

    /** Moves the image by the nudge step in the direction ([dx], [dy]): −1, 0, or 1 for each value. */
    override fun onNudge(dx: Int, dy: Int) {
        session.update { current -> current.copy(panX = current.panX + dx * current.nudgeStep, panY = current.panY + dy * current.nudgeStep) }
    }

    // -------------------------------------------------------------------------------------
    // The image tools

    /** "Gray": the color matrix of the contrast weight search, with the BT.601 fallback. */
    override fun onGray() {
        val current = session.value
        val source = current.source ?: return
        if (current.gray || current.weights != null) {
            session.update { state -> state.copy(gray = !state.gray) }
            return
        }
        session.update { state -> state.copy(busy = true) }
        scope.launch {
            val weights = withContext(deps.context.dispatcher) { ContrastPreservingGray.weights(source.argb, source.width, source.height).weights }
            session.update { state -> state.copy(gray = true, weights = weights, busy = false) }
        }
    }

    /** "Invert": the matrix `[−1 0 0 0 255; 0 −1 0 0 255; 0 0 −1 0 255; 0 0 0 1 0]`. */
    override fun onInvert() {
        session.update { current -> current.copy(invert = !current.invert) }
    }

    /** "Rotate left": 90° counterclockwise on the screen. */
    override fun onRotateLeft() = pixelStep(ReferenceImageMath::rotateLeft)

    /** "Rotate right": 90° clockwise on the screen. */
    override fun onRotateRight() = pixelStep(ReferenceImageMath::rotateRight)

    /** "Flip horizontal": left and right change places. */
    override fun onFlipHorizontal() = pixelStep(ReferenceImageMath::flipHorizontal)

    /** "Flip vertical": top and bottom change places. */
    override fun onFlipVertical() = pixelStep(ReferenceImageMath::flipVertical)

    // -------------------------------------------------------------------------------------
    // The reference cards

    /** The user typed [text] in the horizontal field of the card [index]. */
    override fun onHorizontalText(index: Int, text: String) = updateCard(index) { card ->
        card.copy(horizontalText = text, horizontalError = null, error = null, subDatumId = null)
    }

    /** The user typed [text] in the vertical field of the card [index]. */
    override fun onVerticalText(index: Int, text: String) = updateCard(index) { card ->
        card.copy(verticalText = text, verticalError = null, error = null, subDatumId = null)
    }

    /** The sync button of the horizontal field: the current absolute position of the axis. */
    override fun onSyncHorizontal(index: Int) = sync(index, horizontal = true)

    /** The sync button of the vertical field. */
    override fun onSyncVertical(index: Int) = sync(index, horizontal = false)

    /**
     * Selects the sub-datum [subDatumId] for the card [index]: its coordinates go into the two
     * fields. On a lathe the horizontal field gets Z and the vertical field gets X.
     */
    override fun onSubDatum(index: Int, subDatumId: String) {
        val frame = readout.frame.value ?: return
        val subDatum = frame.workspace.subDatums.firstOrNull { point -> point.id == subDatumId } ?: return
        val (horizontal, vertical) = axesOf(frame)
        val h = PlaneAxes.valueOf(horizontal, subDatum) ?: return
        val v = PlaneAxes.valueOf(vertical, subDatum) ?: return
        updateCard(index) { card ->
            card.copy(
                horizontalText = shownText(frame, horizontal, h),
                verticalText = shownText(frame, vertical, v),
                horizontalError = null,
                verticalError = null,
                error = null,
                subDatumId = subDatumId,
            )
        }
    }

    /**
     * "Set" or "Clear" of the card [index]. "Set" needs the two values ("Please enter X and Y
     * value for Reference N") and records the pixel under the reticle.
     */
    override fun onSetOrClear(index: Int) {
        val current = session.value
        val card = current.cards[index]
        if (card.isSet) {
            updateCard(index) { old -> old.copy(reference = null, error = null) }
            return
        }
        val frame = readout.frame.value ?: return
        val state = stateOf(current, frame)
        val (horizontal, vertical) = axesOf(frame)
        val h = parse(frame, horizontal, card.horizontalText)
        val v = parse(frame, vertical, card.verticalText)
        val missing = card.horizontalText.isBlank() || card.verticalText.isBlank()
        if (missing) {
            updateCard(index) { old ->
                old.copy(error = UiText.of(Res.string.refimage_enter_values, horizontal.label(), vertical.label(), index + 1))
            }
            return
        }
        if (h is LengthInput.Invalid || v is LengthInput.Invalid) {
            updateCard(index) { old ->
                old.copy(horizontalError = (h as? LengthInput.Invalid)?.error, verticalError = (v as? LengthInput.Invalid)?.error)
            }
            return
        }
        val pixel = state.reticlePixel
        val hUm = (h as LengthInput.Value).um
        val vUm = (v as LengthInput.Value).um
        if (pixel == null || !hUm.isFinite() || !vUm.isFinite()) {
            updateCard(index) { old -> old.copy(error = UiText.of(Res.string.refimage_could_not_set, index + 1)) }
            return
        }
        val machineH = frame.postProcessor(horizontal).toInternal(hUm)
        val machineV = frame.postProcessor(vertical).toInternal(vUm)
        updateCard(index) { old -> old.copy(reference = ImageReference.fromCard(pixel, machineH, machineV), error = null) }
    }

    /** A long press on "Cancel": all references clear. */
    override fun onClearAll() {
        session.update { current ->
            current.copy(cards = List(ReferenceImageSetupUiState.CARD_COUNT) { index -> ReferenceCardUiState(index) }, preview = false)
        }
    }

    // -------------------------------------------------------------------------------------
    // The readout, the preview, and "Apply"

    /** The zero button: both absolute values of the plane axes go to 0, with undo. */
    override fun onZero() {
        readout.positionUnavailable()?.let { text ->
            session.update { current -> current.copy(message = text) }
            return
        }
        val frame = readout.latestFrame() ?: return
        val (horizontal, vertical) = axesOf(frame)
        readout.applyWorkspace(UiText.of(Res.string.refimage_undo_zero)) { workspace ->
            val first = AxisOperations.setAxis(frame.with(workspace), horizontal, 0.0).workspace
            AxisOperations.setAxis(frame.with(first), vertical, 0.0).workspace
        }
    }

    /** "Preview" and "Return": the image through the preview matrix of the fit, or the image. */
    override fun onPreview() {
        val state = stateOf(session.value, readout.frame.value)
        if (!state.preview && !state.canApply) return
        session.update { current -> current.copy(preview = !current.preview, panX = 0.0, panY = 0.0, zoom = 1.0) }
    }

    /** "Apply": the JPEG file and the matrix of the workspace. Then the screen closes. */
    override fun onApply() {
        val current = session.value
        val source = current.source ?: return
        val frame = readout.frame.value ?: return
        val fitted = fitOf(current.cards, source) as? ImageFitResult.Fitted ?: return
        if (current.busy) return
        val workspaceId = frame.workspace.id
        session.update { state -> state.copy(busy = true, message = null) }
        scope.launch {
            val fileName = "$IMAGE_FOLDER/${newId()}.jpg"
            val saved = try {
                val bytes = withContext(deps.context.dispatcher) {
                    val processed = RasterImages.process(source, current.invert, if (current.gray) current.weights else null)
                    PlatformImages.encodeJpeg(processed, ReferenceImageMath.JPEG_QUALITY)
                }
                withContext(deps.context.fileDispatcher) {
                    deps.storeIO.sinkFor(fileName).buffered().use { sink -> sink.write(bytes) }
                }
                val image = ReferenceImage(fileName = fileName, matrix = fitted.matrix.toList(), alpha = ReferenceImageMath.START_ALPHA)
                deps.machines.setReferenceImage(workspaceId, image) is StoreResult.Ok
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
            if (saved) {
                session.update { state -> state.copy(busy = false, closed = true) }
            } else {
                withContext(deps.context.fileDispatcher) { deps.storeIO.delete(fileName) }
                session.update { state -> state.copy(busy = false, message = UiText.of(Res.string.refimage_save_failed)) }
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // Private parts

    private fun pixelStep(make: (Int, Int) -> PixelTransform) {
        val current = session.value
        val source = current.source ?: return
        if (current.busy) return
        val transform = make(source.width, source.height)
        session.update { state -> state.copy(busy = true) }
        scope.launch {
            val next = withContext(deps.context.dispatcher) { RasterImages.transform(source, transform) }
            session.update { state ->
                state.copy(
                    source = next,
                    busy = false,
                    preview = false,
                    cards = state.cards.map { card ->
                        val reference = card.reference ?: return@map card
                        card.copy(reference = reference.copy(pixel = transform.matrix.map(reference.pixel)))
                    },
                )
            }
            updateBitmap(next)
        }
    }

    private fun updateBitmap(image: RasterImage) {
        scope.launch {
            mutableBitmap.value = withContext(deps.context.dispatcher) { PlatformImages.toImageBitmap(image) }
        }
    }

    private fun sync(index: Int, horizontal: Boolean) {
        readout.positionUnavailable()?.let { text ->
            session.update { current -> current.copy(message = text) }
            return
        }
        val frame = readout.latestFrame() ?: return
        val axis = if (horizontal) axesOf(frame).first else axesOf(frame).second
        val reading = frame.reading(axis)
        if (!reading.hasValue) return
        val text = shownText(frame, axis, reading.absolute)
        updateCard(index) { card ->
            if (horizontal) card.copy(horizontalText = text, horizontalError = null, error = null, subDatumId = null)
            else card.copy(verticalText = text, verticalError = null, error = null, subDatumId = null)
        }
    }

    private fun updateCard(index: Int, change: (ReferenceCardUiState) -> ReferenceCardUiState) {
        session.update { current ->
            val cards = current.cards.toMutableList()
            cards[index] = change(cards[index])
            current.copy(cards = cards, message = null)
        }
    }

    private fun stateOf(current: Session, frame: ReadoutFrame?): ReferenceImageSetupUiState {
        val source = current.source
        val (horizontal, vertical) = if (frame == null) AxisId.X to AxisId.Y else axesOf(frame)
        val references = current.cards.mapNotNull { card -> card.reference }
        val fit = if (source == null) null else fitOf(current.cards, source)
        val fitted = fit as? ImageFitResult.Fitted
        val preview = current.preview && fitted != null
        val contentMatrix = if (preview) ReferenceImageMath.previewMatrix(fitted.matrix, references) else Matrix3.IDENTITY
        val scale = if (source == null) 1.0 else fitScale(source, contentMatrix, current.viewWidth, current.viewHeight) * current.zoom
        val pixel = source?.let { image ->
            ReferenceImageMath.reticlePixel(image.width.toDouble(), image.height.toDouble(), current.panX, current.panY, scale, contentMatrix)
        }
        val setCount = references.size
        val firstOpen = current.cards.indexOfFirst { card -> !card.isSet && (card.index < 2 || setCount >= 2) }
        val cards = current.cards.map { card ->
            card.copy(visible = card.index < 2 || setCount >= 2 || card.isSet, active = card.index == firstOpen)
        }
        val grayWeights = if (current.gray) current.weights else null
        return ReferenceImageSetupUiState(
            hasImage = source != null,
            imageWidth = source?.width ?: 0,
            imageHeight = source?.height ?: 0,
            horizontalAxis = horizontal,
            verticalAxis = vertical,
            horizontalValue = frame?.let { f -> liveText(f, horizontal) }.orEmpty(),
            verticalValue = frame?.let { f -> liveText(f, vertical) }.orEmpty(),
            horizontalMetric = frame?.metric(horizontal) ?: true,
            verticalMetric = frame?.metric(vertical) ?: true,
            gray = current.gray,
            invert = current.invert,
            preview = preview,
            cards = cards,
            subDatums = frame?.workspace?.subDatums.orEmpty()
                .filter { point -> PlaneAxes.valueOf(horizontal, point) != null && PlaneAxes.valueOf(vertical, point) != null }
                .map { point -> SubDatumOption(point.id, point.name) },
            panX = current.panX,
            panY = current.panY,
            scale = scale,
            contentMatrix = contentMatrix,
            colorMatrix = if (current.invert || grayWeights != null) ColorMatrix.process(current.invert, grayWeights) else null,
            nudgeStep = current.nudgeStep,
            reticlePixel = pixel,
            fitMessage = fit?.let(::refusalText),
            banner = fitted?.takeIf { result -> result.quality == ImageFitQuality.SteepAngle }
                ?.let { result -> UiText.of(Res.string.refimage_steep_angle, mmPerPixel(result.umPerPixel)) },
            canApply = fitted != null,
            busy = current.busy,
            message = current.message,
            closed = current.closed,
        )
    }

    private fun fitOf(cards: List<ReferenceCardUiState>, source: RasterImage): ImageFitResult? {
        val references = cards.mapNotNull { card -> card.reference }
        return when (references.size) {
            0, 1 -> null
            else -> ReferenceImageMath.fit(references, source.width.toDouble(), source.height.toDouble())
        }
    }

    private fun axesOf(frame: ReadoutFrame): Pair<AxisId, AxisId> {
        val plane: Plane = PlaneAxes.planeFor(frame.profile.machineType.isLathe, frame.workspace.plane)
        return PlaneAxes.horizontal(plane) to PlaneAxes.vertical(plane)
    }

    private fun parse(frame: ReadoutFrame, axis: AxisId, text: String): LengthInput {
        val reading = frame.reading(axis)
        val current = if (reading.hasValue) frame.postProcessor(axis).toShown(reading.absolute) else 0.0
        return evaluateLength(text, frame.metric(axis), current)
    }

    /** The live values of the horizontal and the vertical plane axis as shown texts. */
    private fun liveValues(frame: ReadoutFrame?): Pair<String, String> {
        if (frame == null) return "" to ""
        val (horizontal, vertical) = axesOf(frame)
        return liveText(frame, horizontal) to liveText(frame, vertical)
    }

    private fun liveText(frame: ReadoutFrame, axis: AxisId): String {
        val reading = frame.reading(axis)
        return shownText(frame, axis, if (reading.hasValue) reading.absolute else Double.NaN)
    }

    private fun shownText(frame: ReadoutFrame, axis: AxisId, internalUm: Double): String =
        frame.pattern(axis).format(frame.converter(axis).toDisplay(frame.postProcessor(axis).toShown(internalUm)))

    private fun fitScale(source: RasterImage, contentMatrix: Matrix3, viewWidth: Double, viewHeight: Double): Double {
        if (!(viewWidth > 0.0) || !(viewHeight > 0.0)) return 1.0
        val corners = listOf(
            Vec2(0.0, 0.0),
            Vec2(source.width.toDouble(), 0.0),
            Vec2(source.width.toDouble(), source.height.toDouble()),
            Vec2(0.0, source.height.toDouble()),
        ).map { corner -> contentMatrix.map(corner) }
        if (corners.any { point -> !point.x.isFinite() || !point.y.isFinite() }) return 1.0
        val width = corners.maxOf { point -> point.x } - corners.minOf { point -> point.x }
        val height = corners.maxOf { point -> point.y } - corners.minOf { point -> point.y }
        if (!(width > 0.0) || !(height > 0.0)) return 1.0
        return min(viewWidth / width, viewHeight / height)
    }

    private fun refusalText(result: ImageFitResult): UiText? {
        val refused = result as? ImageFitResult.Refused ?: return null
        return when (refused.reason) {
            ImageFitRefusal.WrongCount -> UiText.of(Res.string.refimage_fit_wrong_count)
            ImageFitRefusal.ThreeReferences -> UiText.of(Res.string.refimage_fit_three)
            ImageFitRefusal.SamePosition -> UiText.of(Res.string.refimage_fit_same_position)
            ImageFitRefusal.NotDiagonal -> UiText.of(Res.string.refimage_fit_not_diagonal)
            ImageFitRefusal.CrossedOver -> UiText.of(Res.string.refimage_fit_crossed)
            ImageFitRefusal.CannotFit -> UiText.of(Res.string.refimage_fit_cannot)
            ImageFitRefusal.InsideOut -> UiText.of(Res.string.refimage_fit_inside_out)
            ImageFitRefusal.TooOblique -> UiText.of(Res.string.refimage_fit_too_oblique, mmPerPixel(refused.umPerPixel ?: 0.0))
        }
    }

    private fun AxisId.label(): String = when (this) {
        AxisId.X -> "X"
        AxisId.Y -> "Y"
        AxisId.Z -> "Z"
        else -> name
    }

    companion object {
        /** The folder of the reference image files in the app files. */
        const val IMAGE_FOLDER: String = "images"

        /** The limits of the zoom of the setup view. */
        const val MIN_ZOOM: Double = 0.05
        const val MAX_ZOOM: Double = 100.0

        private val MM_PER_PIXEL = NumberPattern("0.00")

        /** Gives the text of [umPerPixel] in mm for each pixel with two decimals, for example "0.85". */
        fun mmPerPixel(umPerPixel: Double): String = MM_PER_PIXEL.format(max(0.0, umPerPixel) / 1000.0)
    }
}
