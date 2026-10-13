package org.superdro.core.ui.functions.patterns

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.AxisId
import org.superdro.core.model.MachineProfile
import org.superdro.core.model.MachineType
import org.superdro.core.model.PatternAngleConvention
import org.superdro.core.model.ReadoutFormat
import org.superdro.core.model.SubDatum
import org.superdro.core.model.Workspace
import org.superdro.core.ui.functions.FunctionsHarness
import org.superdro.core.ui.functions.common.LengthTexts

/** The machines of the pattern tests. */
object PatternMachines {

    /** A metric vertical mill with the LEGACY angles. */
    fun legacyMill(use3d: Boolean = true, workspace: (Workspace) -> Workspace = { it }): MachineProfile =
        mill(PatternAngleConvention.Legacy, use3d, workspace)

    /** A metric vertical mill with the STANDARD angles (the default of a new mill). */
    fun standardMill(use3d: Boolean = true, workspace: (Workspace) -> Workspace = { it }): MachineProfile =
        mill(PatternAngleConvention.Standard, use3d, workspace)

    /** A metric lathe (always LEGACY, plane ZX, X in the diameter mode). */
    fun lathe(diameterMode: Boolean = true, workspace: (Workspace) -> Workspace = { it }): MachineProfile {
        val base = MachineProfile.create(MachineType.Lathe, "Lathe").copy(metricDefault = true)
        val first = base.workspaces.first()
        val states = first.axisStates.toMutableMap()
        states[AxisId.X] = states.getValue(AxisId.X).copy(diameterMode = diameterMode)
        val changed = workspace(first.copy(axisStates = states))
        return base.copy(workspaces = listOf(changed), activeWorkspaceId = changed.id)
    }

    private fun mill(convention: PatternAngleConvention, use3d: Boolean, workspace: (Workspace) -> Workspace): MachineProfile {
        val base = MachineProfile.create(MachineType.VerticalMill, "Mill")
            .copy(metricDefault = true, patternAngleConvention = convention, use3dSubDatums = use3d)
        val changed = workspace(base.workspaces.first())
        return base.copy(workspaces = listOf(changed), activeWorkspaceId = changed.id)
    }
}

/** Gives the text of [um] in mm with 3 decimals, as the tables of the worked examples. */
fun mm(um: Double?): String = if (um == null) "" else LengthTexts.format(um, true, ReadoutFormat.Format3)

/** The X and Y of [point] in mm with 3 decimals. */
fun xy(point: SubDatum): Pair<String, String> = mm(point.x) to mm(point.y)

/**
 * Runs [block] with a started harness and a new presenter of [kind] for [machine]. Each call has
 * its own test scope (virtual time).
 */
fun withPattern(
    machine: MachineProfile,
    kind: PatternKind,
    sourceIds: List<String> = emptyList(),
    block: suspend TestScope.(PatternPresenter, FunctionsHarness) -> Unit,
) {
    val scope = TestScope()
    val harness = FunctionsHarness(scope)
    try {
        scope.runTest {
            harness.start(machine)
            val presenter = PatternPresenter(kind, harness.presenter, harness.services, sourceIds)
            try {
                advanceUntilIdle()
                block(presenter, harness)
            } finally {
                presenter.close()
            }
        }
    } finally {
        harness.close()
    }
}

/** Types [text] into [input] and lets the flows run. */
fun TestScope.type(presenter: PatternPresenter, input: PatternInput, text: String) {
    presenter.onText(input, text)
    advanceUntilIdle()
}

/**
 * The English texts of the base string files of `core:resources`. The tests read them directly,
 * because `getString` of the Compose resources needs a screen on the JVM.
 */
object EnglishStrings {
    private val texts: Map<String, String> by lazy {
        val directory = java.io.File("../resources/src/commonMain/composeResources/values")
        val pattern = Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        directory.listFiles { file -> file.name.startsWith("strings_") && file.name.endsWith(".xml") }.orEmpty()
            .flatMap { file -> pattern.findAll(file.readText()).map { match -> match.groupValues[1] to unescape(match.groupValues[2]) }.toList() }
            .toMap()
    }

    private fun unescape(text: String): String = text
        .replace("\\u0027", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    /** Gives the English text of [text]. */
    fun of(text: org.superdro.core.ui.text.UiText): String = when (text) {
        is org.superdro.core.ui.text.UiText.Plain -> text.text
        is org.superdro.core.ui.text.UiText.Resource -> format(texts.getValue(text.resource.key), text.args)
        is org.superdro.core.ui.text.UiText.Key -> format(texts[text.key] ?: text.fallback, text.args)
    }

    private fun format(pattern: String, args: List<Any>): String =
        org.superdro.core.readout.ReadoutMessage.formatText(pattern, args.map { arg -> if (arg is org.superdro.core.ui.text.UiText) of(arg) else arg })
}

/** The English text of this text. */
val org.superdro.core.ui.text.UiText.english: String get() = EnglishStrings.of(this)

/**
 * A pattern presenter on a started harness, for the Compose UI tests and the screenshots: the
 * test runs the work of the presenter with [idle] (virtual time, no `runTest`).
 */
class LivePattern(
    machine: MachineProfile,
    kind: PatternKind,
    sourceIds: List<String> = emptyList(),
    inputs: Map<AxisId, Double> = emptyMap(),
) : AutoCloseable {
    val scope = TestScope()
    val harness = FunctionsHarness(scope)
    val presenter: PatternPresenter

    init {
        scope.launch { harness.start(machine) }
        idle()
        if (inputs.isNotEmpty()) harness.inputs(*inputs.toList().toTypedArray())
        presenter = PatternPresenter(kind, harness.presenter, harness.services, sourceIds)
        idle()
    }

    /** Runs the work of the presenter and of the store. */
    fun idle() = scope.testScheduler.advanceUntilIdle()

    /** Does [action] on the presenter and runs the work. */
    fun act(action: PatternPresenter.() -> Unit) {
        presenter.action()
        idle()
    }

    /** The stored sub-datums of the active workspace. */
    fun subDatums(): List<SubDatum> {
        var result: List<SubDatum> = emptyList()
        scope.launch { result = harness.readout.workspace().subDatums }
        idle()
        return result
    }

    override fun close() {
        presenter.close()
        harness.close()
    }
}
