package org.superdro.core.ui.mcp.populate

import kotlin.io.encoding.Base64
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.superdro.core.drawing.DrawingConversion
import org.superdro.core.drawing.WireframeReader
import org.superdro.core.mcp.McpTool
import org.superdro.core.mcp.ToolResult
import org.superdro.core.mcp.enumSchema
import org.superdro.core.mcp.objectSchema
import org.superdro.core.model.Drawing
import org.superdro.core.model.DrawingFormat
import org.superdro.core.model.DrawingSource
import org.superdro.core.model.Guide
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.ReferenceImage
import org.superdro.core.model.SubDatum
import org.superdro.core.model.SuperDroJson
import org.superdro.core.model.Tool
import org.superdro.core.model.Workspace
import org.superdro.core.model.newId
import org.superdro.core.store.files.ExportContext
import org.superdro.core.store.files.ExportFileNames
import org.superdro.core.store.files.FileSource
import org.superdro.core.store.files.ImportOperation
import org.superdro.core.store.files.ImportPreview
import org.superdro.core.store.files.ImportReport
import org.superdro.core.store.files.JsonFiles
import org.superdro.core.store.files.JsonKinds
import org.superdro.core.store.files.MachineNames
import org.superdro.core.store.files.RowAction
import org.superdro.core.store.files.Severity
import org.superdro.core.store.files.settings.ProfileFile
import org.superdro.core.store.files.settings.ProfileFileImporter
import org.superdro.core.store.files.settings.SettingRow
import org.superdro.core.store.files.settings.SettingsImportPreview
import org.superdro.core.store.files.settings.SettingsTarget
import org.superdro.core.store.files.subdatum.SubDatumImportPlan
import org.superdro.core.ui.mcp.populate.PopulateSchemas.fileId
import org.superdro.core.ui.mcp.populate.PopulateSchemas.machine
import org.superdro.core.ui.mcp.populate.PopulateSchemas.preview

/** The settings object of a profile as the import of a profile file reads it. */
internal object ProfileSettings {

    /** The names of the machine types in the tools: `VERTICAL_MILL`, `LATHE`, `HORIZONTAL_MILL`. */
    val MACHINE_TYPES: List<String> = MachineType.entries.map(MachineNames::name)

    fun machineType(text: String): MachineType = MachineNames.parse(text) ?: toolError("The machine type must be one of ${MACHINE_TYPES.joinToString()}, not \"$text\".")

    /** Reads [settings] with the validation of the profile file import. */
    fun read(settings: JsonObject, target: SettingsTarget, now: () -> kotlin.time.Instant): SettingsImportPreview {
        val text = buildJsonObject {
            put(FileSource.SuperDro.key, JsonKinds.PROFILE)
            put("format", JsonFiles.FORMAT)
            put("settings", settings)
        }.toString()
        return ProfileFileImporter(now).read(text, target)
    }

    fun row(row: SettingRow): JsonObject = buildJsonObject {
        put("key", row.key)
        put("fileValue", row.fileValue)
        put("currentValue", row.currentValue?.let(::JsonPrimitive) ?: JsonNull)
        put("newValue", row.newValue?.let(::JsonPrimitive) ?: JsonNull)
    }

    /** The preview JSON of the settings rows: `toImport`, `skipped`, `warnings`. */
    fun preview(preview: SettingsImportPreview): JsonObject = PopulateJson.preview(preview.preview, ::row)

    /** The usable preview, or a [ToolError] with the file problems. */
    fun usable(preview: SettingsImportPreview): SettingsImportPreview {
        if (!preview.usable) {
            val errors = preview.problems.filter { problem -> problem.severity == Severity.Error }
            toolError(errors.map(PopulateJson::problemText).ifEmpty { listOf("The settings are not usable.") }.joinToString(" "))
        }
        return preview
    }
}

/** The profile bundle: one JSON object with the settings, the tools, and the workspaces with their data. */
internal object ProfileBundle {
    const val KIND: String = "bundle"
    const val FORMAT: Int = 1

    /** The bundle of the full [profile]. */
    suspend fun write(deps: PopulateDeps, profile: MachineProfile, tools: List<Tool>): JsonObject {
        val context = ExportContext(deps.appVersion, deps.now())
        return buildJsonObject {
            put(FileSource.SuperDro.key, KIND)
            put("format", FORMAT)
            put("app", context.appText)
            put("exported", context.exportedText)
            put(
                "profile",
                buildJsonObject {
                    put("id", profile.id)
                    put("name", profile.name)
                    put("machineType", MachineNames.name(profile.machineType))
                    put("settings", ProfileFile.settingsOf(profile))
                },
            )
            put("tools", JsonArray(tools.map { tool -> SuperDroJson.encodeToJsonElement(Tool.serializer(), tool) }))
            put(
                "workspaces",
                buildJsonArray {
                    profile.workspaces.forEach { workspace ->
                        val base = SuperDroJson.encodeToJsonElement(Workspace.serializer(), workspace) as JsonObject
                        add(
                            JsonObject(
                                base + mapOf(
                                    "referenceImage" to image(deps, workspace.referenceImage),
                                    "drawing" to (deps.drawings.loadDrawing(workspace.id)?.let(DrawingJson::wireframe) ?: JsonNull),
                                ),
                            ),
                        )
                    }
                },
            )
        }
    }

    private fun image(deps: PopulateDeps, image: ReferenceImage?): JsonElement {
        if (image == null) return JsonNull
        val bytes = ImageFiles.read(deps, image.fileName) ?: return JsonNull
        val header = ImageHeader.read(bytes)
        return buildJsonObject {
            put("matrix", JsonArray(image.matrix.map(::JsonPrimitive)))
            put("alpha", image.alpha)
            put("mimeType", header?.mimeType ?: ImageHeader.JPEG)
            put("base64", Base64.encode(bytes))
        }
    }

    /** One workspace of a bundle, decoded. */
    class BundleWorkspace(val workspace: Workspace, val drawing: JsonObject?, val image: BundleImage?)

    class BundleImage(val matrix: List<Double>, val alpha: Int, val bytes: ByteArray, val header: ImageHeader)

    /** The content of a bundle before the write. */
    class Content(val id: String?, val name: String?, val machineType: MachineType?, val settings: JsonObject, val tools: List<Tool>, val workspaces: List<BundleWorkspace>)

    /** Reads the bundle [bytes]. A bad bundle throws [ToolError]. */
    fun read(bytes: ByteArray): Content {
        val root = JsonFiles.parseObject(bytes.decodeToString()) ?: toolError("The upload is not a JSON object.")
        val (_, kind) = JsonFiles.sourceAndKind(root) ?: toolError("The file is not a SuperDRO bundle.")
        if (kind != KIND) toolError("The file contains \"$kind\". This import needs \"$KIND\".")
        val format = JsonFiles.number(root["format"])?.toInt() ?: FORMAT
        if (format > FORMAT) toolError("A newer version made this bundle (format $format).")
        val profile = JsonFiles.obj(root["profile"]) ?: toolError("The bundle has no \"profile\" object.")
        val settings = JsonFiles.obj(profile["settings"]) ?: toolError("The bundle profile has no \"settings\" object.")
        val machineType = JsonFiles.string(profile["machineType"])?.let(ProfileSettings::machineType)
        val tools = JsonFiles.array(root["tools"]).orEmpty().mapIndexed { index, element ->
            try {
                SuperDroJson.decodeFromJsonElement(Tool.serializer(), element)
            } catch (error: Exception) {
                toolError("Tool ${index + 1} of the bundle is not valid: ${error.message}")
            }
        }
        val workspaces = JsonFiles.array(root["workspaces"]).orEmpty().mapIndexed { index, element ->
            val json = element as? JsonObject ?: toolError("Workspace ${index + 1} of the bundle is not an object.")
            val workspace = try {
                SuperDroJson.decodeFromJsonElement(Workspace.serializer(), JsonObject(json - "referenceImage" - "drawing"))
            } catch (error: Exception) {
                toolError("Workspace ${index + 1} of the bundle is not valid: ${error.message}")
            }
            BundleWorkspace(workspace.copy(referenceImage = null), JsonFiles.obj(json["drawing"]), bundleImage(json["referenceImage"], index + 1))
        }
        return Content(JsonFiles.string(profile["id"]), JsonFiles.string(profile["name"]), machineType, settings, tools, workspaces)
    }

    private fun bundleImage(element: JsonElement?, index: Int): BundleImage? {
        val json = JsonFiles.obj(element) ?: return null
        val matrix = JsonFiles.array(json["matrix"])?.map { value -> JsonFiles.number(value) ?: toolError("The image matrix of workspace $index has a value that is not a number.") }
            ?: ReferenceImage.IDENTITY
        if (matrix.size != 9) toolError("The image matrix of workspace $index needs 9 values.")
        val alpha = JsonFiles.long(json["alpha"])?.toInt()?.takeIf { it in ImageFiles.ALPHAS } ?: 255
        val text = JsonFiles.string(json["base64"]) ?: toolError("The image of workspace $index has no base64 data.")
        val bytes = try {
            Base64.decode(text)
        } catch (_: IllegalArgumentException) {
            toolError("The image of workspace $index is not valid base64.")
        }
        val header = ImageHeader.read(bytes) ?: toolError("The image of workspace $index is not a JPEG or a PNG.")
        return BundleImage(matrix, alpha, bytes, header)
    }

    /** The drawing of [json] for the workspace [workspaceId], or a [ToolError]. */
    fun drawing(json: JsonObject, workspaceId: String, now: kotlin.time.Instant): Drawing {
        val read = WireframeReader.readText(json.toString())
        val document = read.document ?: toolError("The drawing of a bundle workspace is not valid: ${read.problems.joinToString("; ") { it.message }}")
        return DrawingConversion.toDrawing(document, workspaceId, DrawingSource("bundle", DrawingFormat.Wireframe), createdOn = now)
    }
}

/** The profile tools: `put_profile_settings`, `create_profile`, `export_profile_bundle`, `import_profile_bundle`. */
internal fun profileTools(deps: PopulateDeps): List<McpTool> = listOf(
    putProfileSettings(deps),
    createProfile(deps),
    exportProfileBundle(deps),
    importProfileBundle(deps),
)

private fun putProfileSettings(deps: PopulateDeps) = populateTool(
    name = "put_profile_settings",
    title = "Put profile settings",
    description = "Applies a settings object (the content of get_profile_settings or of the settings key of a SuperDRO " +
        "profile file) to a machine profile, with the validation and the preview of the profile import. A bad value gets " +
        "the default and a warning. The call is refused while the machine is online. The machine type cannot change here.",
    schema = objectSchema {
        machine()
        property("settings", objectSchema(additionalProperties = true, description = "The settings object: the keys of the profile file."), required = true)
        preview()
    },
    idempotent = true,
    interaction = true,
) { call, arguments ->
    val profile = deps.lookup.profile(arguments.requiredString("machine"))
    val settings = arguments.obj("settings") ?: toolError("The argument \"settings\" is required.")
    if (deps.isOnline(profile)) {
        toolError("The machine \"${profile.name}\" is online. Disconnect it before you change the profile settings: an axis or encoder change during a session changes the readout scale.")
    }
    val preview = ProfileSettings.usable(ProfileSettings.read(settings, SettingsTarget.SelectedProfile(profile), deps::now))
    val next = preview.profile(profile.name)
    if (next.machineType != profile.machineType) {
        toolError("The settings change the machine type from ${MachineNames.name(profile.machineType)} to ${MachineNames.name(next.machineType)}. Change the machine type in the app.")
    }
    val structured = buildJsonObject {
        put("changed", preview.changeCount)
        ProfileSettings.preview(preview).forEach { (key, element) -> put(key, element) }
    }
    val text = "${PopulateJson.count(preview.changeCount, "setting")} changed, ${PopulateJson.count(preview.preview.skipped.size, "key")} skipped, " +
        PopulateJson.count(preview.preview.warnings.size, "warning")
    if (arguments.preview) return@populateTool ToolResult.Ok("Preview: $text", structured)
    val changedKeys = preview.rows.filter { row -> row.action == RowAction.Update }.map(SettingRow::key)
    deps.confirm(call, risky = true, "Put profile settings", "Change ${PopulateJson.count(preview.changeCount, "setting")} of ${profile.name}", changedKeys)
        ?.let { return@populateTool it }
    deps.machines.updateProfile(next.copy(id = profile.id)).valueOrError()
    ToolResult.Ok(text, structured)
}

private fun createProfile(deps: PopulateDeps) = populateTool(
    name = "create_profile",
    title = "Create profile",
    description = "Makes a new machine profile from a name, a machine type, and an optional settings object. Gives the ID.",
    schema = objectSchema {
        string("name", required = true, description = "The name of the new profile. It must be new.")
        property("machineType", enumSchema(ProfileSettings.MACHINE_TYPES), required = true)
        property("settings", objectSchema(additionalProperties = true, description = "An optional settings object: the keys of the profile file."))
        preview()
    },
    idempotent = false,
    interaction = true,
) { call, arguments ->
    val name = arguments.requiredString("name")
    val machineType = ProfileSettings.machineType(arguments.requiredString("machineType"))
    val settings = arguments.obj("settings")
    var warnings: JsonArray = JsonArray(emptyList())
    val profile = if (settings == null) {
        MachineProfile.create(machineType, name)
    } else {
        val withType = JsonObject(settings + ("machineType" to SuperDroJson.encodeToJsonElement(MachineType.serializer(), machineType)))
        val preview = ProfileSettings.usable(ProfileSettings.read(withType, SettingsTarget.NewProfile, deps::now))
        warnings = PopulateJson.problems(preview.preview.warnings)
        preview.profile(name).copy(machineType = machineType)
    }
    if (arguments.preview) {
        return@populateTool ToolResult.Ok(
            "Preview: profile \"$name\" (${MachineNames.name(machineType)}) would be created",
            buildJsonObject {
                put("toImport", PopulateJson.simpleRows(listOf(name to "Add")))
                put("skipped", JsonArray(emptyList()))
                put("warnings", warnings)
            },
        )
    }
    deps.confirm(call, risky = false, "Create profile", "Create profile \"$name\" (${MachineNames.name(machineType)})", emptyList())
        ?.let { return@populateTool it }
    val created = deps.machines.createProfile(profile).valueOrError()
    ToolResult.Ok(
        "Profile \"${created.name}\" created with ID ${created.id}",
        buildJsonObject {
            put("id", created.id)
            put("name", created.name)
            put("machineType", MachineNames.name(created.machineType))
            put("warnings", warnings)
        },
    )
}

private fun exportProfileBundle(deps: PopulateDeps) = populateTool(
    name = "export_profile_bundle",
    title = "Export profile bundle",
    description = "Writes one JSON bundle of a machine: the settings, the tools, the workspaces with their sub-datums and " +
        "guides, the drawings as wireframe documents, and the reference images as base64. Gives the download path.",
    schema = objectSchema {
        machine()
    },
    idempotent = true,
    readOnly = true,
) { _, arguments ->
    val profile = deps.lookup.fullProfile(arguments.requiredString("machine"))
    val tools = deps.tools.loadLibrary(profile.id)
    val bundle = ProfileBundle.write(deps, profile, tools)
    val context = ExportContext(deps.appVersion, deps.now())
    val fileName = ExportFileNames.name(listOf(ProfileBundle.KIND, profile.name), context.localTime, "json")
    val fileId = deps.files.putDownload(fileName, "application/json", bundle.toString().encodeToByteArray())
    ToolResult.Ok(
        "Bundle of \"${profile.name}\" with ${PopulateJson.count(profile.workspaces.size, "workspace")} and ${PopulateJson.count(tools.size, "tool")} at /download/$fileId",
        buildJsonObject {
            put("fileId", fileId)
            put("path", "/download/$fileId")
            put("fileName", fileName)
            put("mimeType", "application/json")
            put("workspaceCount", profile.workspaces.size)
            put("toolCount", tools.size)
        },
    )
}

private fun importProfileBundle(deps: PopulateDeps) = populateTool(
    name = "import_profile_bundle",
    title = "Import profile bundle",
    description = "Imports a bundle of export_profile_bundle from an upload into a new profile. The IDs of the bundle stay " +
        "when they are new on this device. When the profile ID of the bundle exists and name is the name of that profile, " +
        "the import updates that profile: its settings, its tools, and its workspaces with their data.",
    schema = objectSchema {
        fileId()
        string("name", description = "The name of the profile. The default is the name in the bundle.")
        preview()
    },
    idempotent = true,
    interaction = true,
) { call, arguments ->
    val bytes = deps.uploadBytes(arguments.requiredString("fileId"))
    val bundle = ProfileBundle.read(bytes)
    val name = arguments.string("name")?.trim()?.ifEmpty { null } ?: bundle.name?.trim()?.ifEmpty { null } ?: toolError("Give a name: the bundle has none.")
    val existing = bundle.id?.let { id -> deps.machines.loadProfile(id) }
    val update = existing != null && existing.name.trim().equals(name, ignoreCase = true)
    val machineType = bundle.machineType ?: existing?.machineType ?: MachineType.VerticalMill
    if (update && existing!!.machineType != machineType) {
        toolError("The bundle is for a ${MachineNames.name(machineType)}. The profile \"${existing.name}\" is a ${MachineNames.name(existing.machineType)}.")
    }
    val target = if (update) SettingsTarget.SelectedProfile(existing!!) else SettingsTarget.NewProfile
    val withType = JsonObject(bundle.settings + ("machineType" to SuperDroJson.encodeToJsonElement(MachineType.serializer(), machineType)))
    val settingsPreview = ProfileSettings.usable(ProfileSettings.read(withType, target, deps::now))
    val keepIds = !update && existing == null
    val now = deps.now()
    val existingWorkspaceIds = if (update) deps.machines.workspaces(existing!!.id).first().map(Workspace::id) else emptyList()

    val structured = buildJsonObject {
        put(
            "profile",
            buildJsonObject {
                put("name", name)
                put("machineType", MachineNames.name(machineType))
                put("action", if (update) "Update" else "Add")
                put("keepIds", keepIds || update)
            },
        )
        put("toolCount", bundle.tools.size)
        put(
            "workspaces",
            buildJsonArray {
                bundle.workspaces.forEach { item ->
                    add(
                        buildJsonObject {
                            put("name", item.workspace.name)
                            put("action", if (item.workspace.id in existingWorkspaceIds) "Update" else "Add")
                            put("subDatumCount", item.workspace.subDatums.size)
                            put("guideCount", item.workspace.guides.size)
                            put("hasDrawing", item.drawing != null)
                            put("hasReferenceImage", item.image != null)
                        },
                    )
                }
            },
        )
        put("settings", ProfileSettings.preview(settingsPreview))
    }
    val text = "${if (update) "update" else "new"} profile \"$name\" with ${PopulateJson.count(bundle.workspaces.size, "workspace")} and ${PopulateJson.count(bundle.tools.size, "tool")}"
    if (arguments.preview) return@populateTool ToolResult.Ok("Preview: $text", structured)
    deps.confirm(call, risky = update, "Import profile bundle", "Import a bundle: $text", bundle.workspaces.map { item -> item.workspace.name })
        ?.let { return@populateTool it }

    val profileId = if (update) {
        updateFromBundle(deps, existing!!, settingsPreview.profile(name), bundle, now)
    } else {
        createFromBundle(deps, settingsPreview.profile(name).copy(machineType = machineType), bundle, keepIds, now)
    }
    ToolResult.Ok(
        "Imported $text (ID $profileId)",
        JsonObject(structured + ("id" to JsonPrimitive(profileId))),
    )
}

/** Writes the image file of [image] and gives the reference image record. */
private fun storeImage(deps: PopulateDeps, image: ProfileBundle.BundleImage?): ReferenceImage? {
    if (image == null) return null
    return ReferenceImage(ImageFiles.write(deps, image.bytes, image.header), image.matrix, image.alpha)
}

private suspend fun createFromBundle(deps: PopulateDeps, settings: MachineProfile, bundle: ProfileBundle.Content, keepIds: Boolean, now: kotlin.time.Instant): String {
    fun id(bundleId: String): String = if (keepIds) bundleId else newId()
    val workspaces = bundle.workspaces.map { item ->
        val workspace = item.workspace
        val pointIds = workspace.subDatums.associate { point -> point.id to id(point.id) }
        workspace.copy(
            id = id(workspace.id),
            subDatums = workspace.subDatums.map { point -> point.copy(id = pointIds.getValue(point.id)) },
            guides = workspace.guides.map { guide -> guide.copy(id = id(guide.id)) },
            activeSubDatumId = workspace.activeSubDatumId?.let(pointIds::get),
            activeToolId = null,
            referenceImage = storeImage(deps, item.image),
        )
    }
    val toolIds = bundle.tools.associate { tool -> tool.id to id(tool.id) }
    val tools = bundle.tools.map { tool -> tool.copy(id = toolIds.getValue(tool.id)) }
    val profile = settings.copy(
        id = bundle.id?.takeIf { keepIds } ?: newId(),
        workspaces = workspaces,
        activeWorkspaceId = workspaces.firstOrNull()?.id.orEmpty(),
        tools = tools,
        createdOn = now,
        updatedOn = now,
    )
    val created = deps.machines.createProfile(profile).valueOrError()
    bundle.workspaces.forEachIndexed { index, item ->
        val drawing = item.drawing ?: return@forEachIndexed
        deps.drawings.replaceDrawing(ProfileBundle.drawing(drawing, workspaces[index].id, now)).valueOrError()
    }
    return created.id
}

private suspend fun updateFromBundle(deps: PopulateDeps, existing: MachineProfile, settings: MachineProfile, bundle: ProfileBundle.Content, now: kotlin.time.Instant): String {
    deps.machines.updateProfile(settings.copy(id = existing.id, machineType = existing.machineType, activeWorkspaceId = existing.activeWorkspaceId)).valueOrError()
    if (bundle.tools.isNotEmpty()) deps.tools.saveTools(existing.id, bundle.tools).valueOrError()
    val profileWorkspaceIds = deps.machines.workspaces(existing.id).first().map(Workspace::id)
    bundle.workspaces.forEach { item ->
        val wanted = item.workspace
        val stored = if (wanted.id in profileWorkspaceIds) deps.machines.loadWorkspace(wanted.id) else null
        val workspaceId = if (stored != null) {
            if (stored.name != wanted.name) deps.machines.renameWorkspace(stored.id, wanted.name).valueOrError()
            stored.id
        } else {
            deps.machines.createWorkspace(existing.id, wanted.name).valueOrError().id
        }
        val image = storeImage(deps, item.image)
        deps.machines.updateWorkspace(workspaceId) { current ->
            current.copy(
                axisStates = wanted.axisStates,
                activeSubDatumId = wanted.activeSubDatumId,
                activeToolId = wanted.activeToolId,
                toolXUm = wanted.toolXUm,
                toolZUm = wanted.toolZUm,
                toothCount = wanted.toothCount,
                proximity = wanted.proximity,
                graphical = wanted.graphical,
                plane = wanted.plane,
                referenceImage = image,
                lastOrdinal = wanted.lastOrdinal,
                partRotation = wanted.partRotation,
                axisAlignment = wanted.axisAlignment,
                compoundProjection = wanted.compoundProjection,
                headTilt = wanted.headTilt,
            )
        }.valueOrError()
        deps.machines.applySubDatumImport(workspaceId, replacePlan(wanted.subDatums, wanted.guides)).valueOrError()
        if (item.drawing != null) {
            deps.drawings.replaceDrawing(ProfileBundle.drawing(item.drawing, workspaceId, now)).valueOrError()
        } else {
            deps.drawings.deleteDrawing(workspaceId)
        }
    }
    return existing.id
}

/** The plan that replaces all sub-datums and guides of a workspace with [subDatums] and [guides]. */
private fun replacePlan(subDatums: List<SubDatum>, guides: List<Guide>): SubDatumImportPlan = SubDatumImportPlan(
    operation = ImportOperation.ReplaceAll,
    preview = ImportPreview(emptyList(), emptyList(), emptyList()),
    addSubDatums = subDatums,
    updateSubDatums = emptyList(),
    addGuides = guides,
    deleteAll = true,
    report = ImportReport(subDatums.size + guides.size, emptyList(), emptyList(), emptyList(), emptyList()),
)
