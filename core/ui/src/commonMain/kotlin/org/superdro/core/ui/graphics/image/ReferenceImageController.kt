package org.superdro.core.ui.graphics.image

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import org.superdro.core.connection.DeviceId
import org.superdro.core.functions.graphics.Matrix3
import org.superdro.core.functions.graphics.ReferenceImageMath
import org.superdro.core.model.ReferenceImage
import org.superdro.core.resources.Res
import org.superdro.core.resources.refimage_decode_failed
import org.superdro.core.ui.graphics.GraphicsDependencies
import org.superdro.core.ui.navigation.ReferenceImageSetupRoute
import org.superdro.core.ui.navigation.TabEntryScope
import org.superdro.core.ui.navigation.TabNavigator
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.readout.TabReadout
import org.superdro.core.ui.text.UiText
import org.superdro.core.util.PickedFile

/**
 * The stored reference image of the active workspace, decoded for the graphical view.
 *
 * @property matrix The map from image pixels to the machine values of the plane in µm
 *   (`ReferenceImage.matrix`). The view applies the vertical sign of the plane.
 * @property alpha The opacity 0, 127, or 255.
 */
@Immutable
class ReferenceImageBitmap(
    val fileName: String,
    val bitmap: ImageBitmap,
    val width: Int,
    val height: Int,
    val matrix: Matrix3,
    val alpha: Int,
)

/** A request of the [ReferenceImageController] to the UI. [id] changes for each new request. */
sealed interface ReferenceImageRequest {
    val id: Long

    /** Open the setup screen ([ReferenceImageSetupRoute]) with the new picture. */
    data class OpenSetup(override val id: Long) : ReferenceImageRequest

    /** Show [text], for example a picture that does not decode. */
    data class Message(override val id: Long, val text: UiText) : ReferenceImageRequest
}

/**
 * The reference image of one tab. The tab owns
 * it ([referenceImageController]), so the graphical view pane, the graphical view screen, and the
 * setup screen use the same object.
 *
 * - [takePhoto] starts the camera app, and [selectFile] opens the system picker for a JPEG or a
 *   PNG file. The decode limits the larger side to 4096 px. Then [request] asks the UI to
 *   open the setup screen, and the setup screen takes the picture with [takePendingImage].
 * - [cycleAlpha] changes the opacity 0 → 255 → 127 → 0. [setAlpha] sets one value for the display
 *   pane. [delete] removes the image and its file.
 * - [image] gives the decoded stored image of the active workspace, with its matrix and alpha.
 */
@OptIn(ExperimentalAtomicApi::class, ExperimentalCoroutinesApi::class)
class ReferenceImageController(
    val deviceId: DeviceId,
    private val readout: TabReadout,
    private val deps: GraphicsDependencies,
) : ScopedPresenter(deps.context) {

    private val ids = AtomicLong(0L)
    private val mutableRequest = MutableStateFlow<ReferenceImageRequest?>(null)
    private val mutableBusy = MutableStateFlow(false)
    private var pending: RasterImage? = null
    private var cache: Pair<String, RasterImage>? = null

    /** The open request to the UI, or null. Call [onRequestHandled] after the UI did it. */
    val request: StateFlow<ReferenceImageRequest?> = mutableRequest.asStateFlow()

    /** True while a picture loads. */
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()

    /** The stored reference image of the active workspace, or null when it has none. */
    val referenceImage: StateFlow<ReferenceImage?> = readout.frame
        .map { frame -> frame?.workspace?.referenceImage?.takeIf { image -> image.fileName.isNotEmpty() } }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, readout.frame.value?.workspace?.referenceImage?.takeIf { image -> image.fileName.isNotEmpty() })

    /** The decoded stored image with its matrix and alpha, or null. */
    val image: StateFlow<ReferenceImageBitmap?> = referenceImage
        .mapLatest { stored -> stored?.let { image -> load(image) } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Starts the camera app for a new reference photo (`IMAGE_CAPTURE`; the platform asks for `CAMERA` first). */
    fun takePhoto() {
        scope.launch { pick { deps.filePicker.capturePhoto() } }
    }

    /** Opens the system picker for a JPEG or a PNG file. */
    fun selectFile() {
        scope.launch { pick { deps.filePicker.openDocument(MIME_TYPES) } }
    }

    /** Gives the new picture to the setup screen one time. A second call gives null. */
    fun takePendingImage(): RasterImage? = pending.also { pending = null }

    /** The opacity button: 0 → 255 → 127 → 0 ([ReferenceImageMath.nextAlpha]). */
    fun cycleAlpha() {
        val current = referenceImage.value ?: return
        setAlpha(ReferenceImageMath.nextAlpha(current.alpha))
    }

    /** Sets the opacity of the stored image: 255 shown, 127 half, 0 hidden. */
    fun setAlpha(alpha: Int) {
        val workspaceId = readout.frame.value?.workspace?.id ?: return
        if (referenceImage.value == null) return
        scope.launch {
            deps.machines.updateWorkspace(workspaceId) { workspace ->
                workspace.copy(referenceImage = workspace.referenceImage?.copy(alpha = alpha.coerceIn(0, 255)))
            }
        }
    }

    /** Deletes the reference image: the file and the matrix. The store deletes the file. */
    fun delete() {
        val workspaceId = readout.frame.value?.workspace?.id ?: return
        scope.launch { deps.machines.setReferenceImage(workspaceId, null) }
    }

    /** The UI did the request [id]. */
    fun onRequestHandled(id: Long) {
        mutableRequest.update { current -> if (current?.id == id) null else current }
    }

    private suspend fun pick(source: suspend () -> PickedFile?) {
        val file = source() ?: return
        mutableBusy.value = true
        try {
            val decoded = withContext(deps.context.dispatcher) { PlatformImages.decode(file.content) }
            if (decoded == null) {
                mutableRequest.value = ReferenceImageRequest.Message(nextId(), UiText.of(Res.string.refimage_decode_failed))
            } else {
                pending = decoded
                mutableRequest.value = ReferenceImageRequest.OpenSetup(nextId())
            }
        } finally {
            mutableBusy.value = false
        }
    }

    private suspend fun load(stored: ReferenceImage): ReferenceImageBitmap? {
        val raster = cache?.takeIf { (name, _) -> name == stored.fileName }?.second ?: withContext(deps.context.dispatcher) {
            val bytes = try {
                if (!deps.storeIO.exists(stored.fileName)) null else deps.storeIO.sourceFor(stored.fileName).buffered().use { source -> source.readByteArray() }
            } catch (_: Exception) {
                null
            }
            bytes?.let { content -> PlatformImages.decode(content) }
        } ?: return null
        cache = stored.fileName to raster
        val matrix = if (stored.matrix.size >= 9) Matrix3.of(stored.matrix) else Matrix3.IDENTITY
        return ReferenceImageBitmap(stored.fileName, PlatformImages.toImageBitmap(raster), raster.width, raster.height, matrix, stored.alpha)
    }

    private fun nextId(): Long = ids.incrementAndFetch()

    companion object {
        /** The name of the controller in the presenters of the tab. */
        const val PRESENTER_NAME: String = "graphics-image"

        /** The types of the system picker. */
        val MIME_TYPES: List<String> = listOf("image/jpeg", "image/jpg", "image/png")
    }
}

/** Gives the reference image controller of the tab of this scope. */
fun TabEntryScope.referenceImageController(deps: GraphicsDependencies): ReferenceImageController =
    tabPresenter(ReferenceImageController.PRESENTER_NAME) { ReferenceImageController(deviceId, readout, deps) }

/**
 * Does the requests of [controller]: it opens the setup screen on [navigator], and it shows a
 * message with [showMessage]. Put it in each composable that calls [ReferenceImageController.takePhoto]
 * or [ReferenceImageController.selectFile].
 */
@Composable
fun ReferenceImageRequests(controller: ReferenceImageController, navigator: TabNavigator, showMessage: (UiText) -> Unit) {
    val request by controller.request.collectAsState()
    LaunchedEffect(request?.id) {
        when (val current = request) {
            null -> Unit
            is ReferenceImageRequest.OpenSetup -> navigator.push(ReferenceImageSetupRoute)
            is ReferenceImageRequest.Message -> showMessage(current.text)
        }
        request?.let { current -> controller.onRequestHandled(current.id) }
    }
}
