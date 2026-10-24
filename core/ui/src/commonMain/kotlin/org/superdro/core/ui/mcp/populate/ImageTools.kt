package org.superdro.core.ui.mcp.populate

import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.superdro.core.functions.graphics.ImageFitRefusal
import org.superdro.core.functions.graphics.ImageFitResult
import org.superdro.core.functions.graphics.ImageReference
import org.superdro.core.functions.graphics.Matrix3
import org.superdro.core.functions.graphics.ReferenceImageMath
import org.superdro.core.functions.graphics.Vec2
import org.superdro.core.mcp.McpTool
import org.superdro.core.mcp.ToolResult
import org.superdro.core.mcp.arraySchema
import org.superdro.core.mcp.enumSchema
import org.superdro.core.mcp.numberSchema
import org.superdro.core.mcp.objectSchema
import org.superdro.core.model.ReferenceImage
import org.superdro.core.model.newId
import org.superdro.core.ui.mcp.populate.PopulateSchemas.fileId
import org.superdro.core.ui.mcp.populate.PopulateSchemas.machine
import org.superdro.core.ui.mcp.populate.PopulateSchemas.preview
import org.superdro.core.ui.mcp.populate.PopulateSchemas.units
import org.superdro.core.ui.mcp.populate.PopulateSchemas.workspace

/** The size of an image from its header: a JPEG (SOF marker) or a PNG (IHDR chunk). */
internal data class ImageHeader(val mimeType: String, val width: Int, val height: Int) {
    companion object {
        const val JPEG: String = "image/jpeg"
        const val PNG: String = "image/png"

        /** Reads the header of [bytes], or gives null when the bytes are not a JPEG or a PNG with a readable size. */
        fun read(bytes: ByteArray): ImageHeader? {
            if (bytes.size >= 24 && isPng(bytes)) {
                val width = int32(bytes, 16)
                val height = int32(bytes, 20)
                return if (width > 0 && height > 0) ImageHeader(PNG, width, height) else null
            }
            if (bytes.size >= 4 && isJpeg(bytes)) return readJpeg(bytes)
            return null
        }

        fun isPng(bytes: ByteArray): Boolean =
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()

        fun isJpeg(bytes: ByteArray): Boolean = bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()

        private fun readJpeg(bytes: ByteArray): ImageHeader? {
            var index = 2
            while (index + 3 < bytes.size) {
                if (bytes[index] != 0xFF.toByte()) return null
                val marker = bytes[index + 1].toInt() and 0xFF
                if (marker == 0xFF) {
                    index++
                    continue
                }
                if (marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01) {
                    index += 2
                    continue
                }
                if (marker == 0xD9 || marker == 0xDA) return null
                val length = int16(bytes, index + 2)
                val startOfFrame = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
                if (startOfFrame) {
                    if (index + 8 >= bytes.size) return null
                    val height = int16(bytes, index + 5)
                    val width = int16(bytes, index + 7)
                    return if (width > 0 && height > 0) ImageHeader(JPEG, width, height) else null
                }
                if (length < 2) return null
                index += 2 + length
            }
            return null
        }

        private fun int16(bytes: ByteArray, index: Int): Int = ((bytes[index].toInt() and 0xFF) shl 8) or (bytes[index + 1].toInt() and 0xFF)

        private fun int32(bytes: ByteArray, index: Int): Int =
            ((bytes[index].toInt() and 0xFF) shl 24) or ((bytes[index + 1].toInt() and 0xFF) shl 16) or
                ((bytes[index + 2].toInt() and 0xFF) shl 8) or (bytes[index + 3].toInt() and 0xFF)
    }
}

/** The reference image files `images/<uuid>.jpg` of the app. */
internal object ImageFiles {
    const val FOLDER: String = "images"

    /** The valid `alpha` values. */
    val ALPHAS: List<Int> = listOf(255, 127, 0)

    /** Writes [bytes] as a new image file and gives its name. */
    fun write(deps: PopulateDeps, bytes: ByteArray, header: ImageHeader): String {
        val fileName = "$FOLDER/${newId()}.${if (header.mimeType == ImageHeader.PNG) "png" else "jpg"}"
        deps.storeIO.sinkFor(fileName).buffered().use { sink -> sink.write(bytes) }
        return fileName
    }

    /** Reads the image file [fileName], or gives null when it is not there. */
    fun read(deps: PopulateDeps, fileName: String): ByteArray? {
        if (fileName.isEmpty() || !deps.storeIO.exists(fileName)) return null
        return try {
            deps.storeIO.sourceFor(fileName).buffered().use { source -> source.readByteArray() }
        } catch (_: Exception) {
            null
        }
    }

    fun refusalText(reason: ImageFitRefusal, umPerPixel: Double?): String = when (reason) {
        ImageFitRefusal.WrongCount -> "Give two or four reference pairs."
        ImageFitRefusal.ThreeReferences -> "Three reference pairs cannot be fitted. Give two or four."
        ImageFitRefusal.SamePosition -> "Two reference pairs have the same position."
        ImageFitRefusal.NotDiagonal -> "Two reference pairs must be diagonally apart: different X and different Y."
        ImageFitRefusal.CrossedOver -> "The reference pairs cross over: a machine position belongs to a different image point."
        ImageFitRefusal.CannotFit -> "The reference pairs give no valid matrix."
        ImageFitRefusal.InsideOut -> "The matrix turns the image inside out."
        ImageFitRefusal.TooOblique -> "The image is too oblique: ${umPerPixel?.toLong() ?: "2000 or more"} µm for each pixel at the far edge."
    }

    fun fitJson(matrix: Matrix3, quality: String, umPerPixel: Double?, header: ImageHeader, alpha: Int, fileName: String?): JsonObject = buildJsonObject {
        put("matrix", JsonArray(matrix.toList().map(::JsonPrimitive)))
        put("quality", quality)
        put("umPerPixel", PopulateJson.number(umPerPixel))
        put("width", header.width)
        put("height", header.height)
        put("mimeType", header.mimeType)
        put("alpha", alpha)
        if (fileName != null) put("fileName", fileName)
    }
}

private val referenceSchema = objectSchema(description = "One reference pair: an image pixel and the machine position under it.") {
    property("pixel", arraySchema(numberSchema(), "The image pixel [x, y]. The origin is the top left corner.", minItems = 2, maxItems = 2), required = true)
    property(
        "machine",
        arraySchema(numberSchema(), "The machine position [horizontal, vertical] of the plane in units: X and Y on a mill, Z and X on a lathe.", minItems = 2, maxItems = 2),
        required = true,
    )
}

/** The image tools: `set_reference_image` and `clear_reference_image`. */
internal fun imageTools(deps: PopulateDeps): List<McpTool> = listOf(setReferenceImage(deps), clearReferenceImage(deps))

private fun setReferenceImage(deps: PopulateDeps) = populateTool(
    name = "set_reference_image",
    title = "Set reference image",
    description = "Sets the reference image of a workspace from an uploaded JPEG or PNG. Give either matrix (9 numbers, " +
        "row by row, image pixels to machine µm) or references (two or four pairs of image pixel and machine position). " +
        "With references the server fits the matrix and gives the fit quality. The old image goes away.",
    schema = objectSchema {
        machine()
        workspace()
        fileId()
        property("matrix", PopulateSchemas.matrixSchema("The 3 × 3 matrix from image pixels to machine µm, row by row."))
        array("references", referenceSchema, minItems = 2, maxItems = 4)
        property("alpha", enumSchema(listOf("255", "127", "0"), "The opacity: 255 (the default), 127, or 0."))
        units()
        preview()
    },
    idempotent = true,
) { call, arguments ->
    val profile = deps.lookup.profile(arguments.requiredString("machine"))
    val workspace = deps.lookup.workspace(profile.id, arguments.requiredString("workspace"))
    val units = arguments.units(McpUnits.defaultUnits(profile))
    val alpha = arguments.string("alpha")?.let { text -> text.trim().toIntOrNull()?.takeIf { it in ImageFiles.ALPHAS } ?: toolError("The alpha must be 255, 127, or 0, not \"$text\".") } ?: 255
    val fileId = arguments.requiredString("fileId")
    val bytes = deps.uploadBytes(fileId)
    val header = ImageHeader.read(bytes) ?: toolError("The upload \"$fileId\" is not a JPEG or a PNG image with a readable size.")
    val matrixValues = arguments.doubles("matrix")
    val references = arguments.objects("references")
    if ((matrixValues == null) == (references == null)) toolError("Give either matrix or references, not both and not none.")
    val fit = if (matrixValues != null) {
        if (matrixValues.size != 9) toolError("The matrix needs 9 numbers, not ${matrixValues.size}.")
        ReferenceImageMath.quality(Matrix3.of(matrixValues), header.width.toDouble(), header.height.toDouble())
    } else {
        val pairs = references!!.mapIndexed { index, json ->
            val pair = Arguments(json)
            val pixel = pair.doubles("pixel")?.takeIf { it.size == 2 } ?: toolError("Reference ${index + 1} needs a pixel [x, y].")
            val machine = pair.doubles("machine")?.takeIf { it.size == 2 } ?: toolError("Reference ${index + 1} needs a machine position [horizontal, vertical].")
            ImageReference(Vec2(pixel[0], pixel[1]), Vec2(McpUnits.lengthToUm(machine[0], units), McpUnits.lengthToUm(machine[1], units)))
        }
        ReferenceImageMath.fit(pairs, header.width.toDouble(), header.height.toDouble())
    }
    val fitted = when (fit) {
        is ImageFitResult.Fitted -> fit
        is ImageFitResult.Refused -> toolError(ImageFiles.refusalText(fit.reason, fit.umPerPixel))
    }
    val quality = fitted.quality.name
    val text = "image ${header.width} × ${header.height}, quality $quality, ${fitted.umPerPixel.toLong()} µm for each pixel"
    if (arguments.preview) {
        return@populateTool ToolResult.Ok("Preview: $text", ImageFiles.fitJson(fitted.matrix, quality, fitted.umPerPixel, header, alpha, null))
    }
    deps.confirm(call, risky = false, "Set reference image", "Set the reference image of \"${workspace.name}\" on ${profile.name}", listOf(text))
        ?.let { return@populateTool it }
    val fileName = ImageFiles.write(deps, bytes, header)
    val image = ReferenceImage(fileName = fileName, matrix = fitted.matrix.toList(), alpha = alpha)
    deps.machines.setReferenceImage(workspace.id, image).valueOrError()
    ToolResult.Ok("Reference image set: $text", ImageFiles.fitJson(fitted.matrix, quality, fitted.umPerPixel, header, alpha, fileName))
}

private fun clearReferenceImage(deps: PopulateDeps) = populateTool(
    name = "clear_reference_image",
    title = "Clear reference image",
    description = "Removes the reference image of a workspace and its file.",
    schema = objectSchema {
        machine()
        workspace()
        preview()
    },
    idempotent = true,
    interaction = true,
) { call, arguments ->
    val profile = deps.lookup.profile(arguments.requiredString("machine"))
    val workspace = deps.lookup.workspace(profile.id, arguments.requiredString("workspace"))
    val image = workspace.referenceImage
    val structured = buildJsonObject {
        put("hadImage", image != null)
        if (image != null) put("fileName", image.fileName)
    }
    if (image == null) return@populateTool ToolResult.Ok("The workspace \"${workspace.name}\" has no reference image", structured)
    if (arguments.preview) return@populateTool ToolResult.Ok("Preview: the reference image ${image.fileName} would be removed", structured)
    deps.confirm(call, risky = true, "Clear reference image", "Remove the reference image of \"${workspace.name}\" on ${profile.name}", listOf(image.fileName))
        ?.let { return@populateTool it }
    deps.machines.setReferenceImage(workspace.id, null).valueOrError()
    ToolResult.Ok("Reference image removed", structured)
}
