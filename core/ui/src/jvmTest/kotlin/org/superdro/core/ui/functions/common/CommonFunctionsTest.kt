package org.superdro.core.ui.functions.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.model.Workspace
import org.superdro.core.resources.Res
import org.superdro.core.resources.fn_blocked_message
import org.superdro.core.resources.fn_blocker_no_workspace
import org.superdro.core.resources.fn_blocker_thread
import org.superdro.core.ui.functions.renderFunctionScreenshot
import org.superdro.core.ui.text.UiText

/** The common parts of the functions area: the fields, the undo offers, and the blocker. */
class CommonFunctionsTest {

    @Test
    fun aLengthFieldKeepsTheFullValueInMicrometersAndChangesItsUnit() {
        val field = LengthFieldState(metric = true).withText("12.7")
        assertEquals(12_700.0, field.valueUm!!, 1e-9)
        val inch = field.withUnit(false)
        assertEquals("0.5", inch.text)
        assertEquals(12_700.0, inch.valueUm!!, 1e-9)
        // A value with more decimals than the text keeps its full value.
        val fine = LengthFieldState(metric = false).withText("1/3")
        assertEquals(25_400.0 / 3.0, fine.valueUm!!, 1e-6)
        assertEquals("8.466667", fine.withUnit(true).text)
        assertEquals(25_400.0 / 3.0, fine.withUnit(true).valueUm!!, 1e-6)
    }

    @Test
    fun aLengthFieldUsesTheEvaluatorAndTheRelativeEntry() {
        val sum = LengthFieldState(metric = true).withText("3+1/4")
        assertEquals(3_250.0, sum.valueUm!!, 1e-9)
        val comma = LengthFieldState(metric = true).withText("1,5")
        assertEquals(1_500.0, comma.valueUm!!, 1e-9)
        // The base of "++x" is the current value in the unit of the field: 10 mm + 2 mm.
        val relative = LengthFieldState(metric = true).withText("++2", currentUm = 10_000.0)
        assertEquals(12_000.0, relative.valueUm!!, 1e-9)
        val bad = LengthFieldState(metric = true).withText("2+")
        assertTrue(bad.valueUm!!.isNaN())
        assertTrue(bad.error is UiText.Key)
        assertTrue(LengthFieldState(metric = true).withText("  ").isEmpty)
    }

    @Test
    fun aNumberFieldGivesTheAngleInArcSecondsAndTheCount() {
        val angle = NumberFieldState().withText("60")
        assertEquals(216_000.0, angle.arcSec!!, 1e-9)
        assertEquals(6, NumberFieldState().withText("6.9").count)
        assertNull(NumberFieldState().withText("").count)
        assertEquals("30", NumberFieldState.ofArcSec(108_000.0).text)
    }

    @Test
    fun theFieldTextHasNoZerosAtTheEndAndNoNegativeZero() {
        assertEquals("100", FieldMath.format(100.0))
        assertEquals("0.1811", FieldMath.format(0.1811))
        assertEquals("-12.5", FieldMath.format(-12.5))
        assertEquals("0", FieldMath.format(-0.0000001))
    }

    @Test
    fun anUndoOfferStaysForTenSeconds() = TestScope().runTest {
        val offers = UndoOffers(backgroundScope)
        var undone = 0
        val first = offers.offer(UiText.Plain("Deleted 3 sub-datums")) { undone++ }
        assertEquals(first, offers.pending.value?.id)
        advanceTimeBy(9_999)
        runCurrent()
        assertEquals(first, offers.pending.value?.id)
        assertTrue(offers.undo(first))
        runCurrent()
        assertEquals(1, undone)
        assertNull(offers.pending.value)
        val second = offers.offer(UiText.Plain("Flip")) { undone++ }
        advanceTimeBy(10_001)
        runCurrent()
        assertNull(offers.pending.value)
        assertFalse(offers.undo(second))
        assertEquals(1, undone)
    }

    @Test
    fun anArmedThreadBlocksNewSubDatums() {
        assertNull(SubDatumBlocker.message(Workspace()))
        assertEquals(
            UiText.of(Res.string.fn_blocked_message, UiText.of(Res.string.fn_blocker_thread)),
            SubDatumBlocker.message(Workspace(armedThreadId = "thread-1")),
        )
        assertEquals(
            UiText.of(Res.string.fn_blocked_message, UiText.of(Res.string.fn_blocker_no_workspace)),
            SubDatumBlocker.message(null),
        )
        assertFalse(SubDatumBlocker.permits(Workspace(armedThreadId = "thread-1")))
    }

    @Test
    fun theStringsOfTheAreaHaveNoHelpLinks() {
        val files = java.io.File("../resources/src/commonMain/composeResources").walkTopDown().filter { file ->
            file.isFile && Regex("strings_(functions.*|tools|subdatums|workspace|import|thread)\\.xml").matches(file.name)
        }.toList()
        assertTrue(files.size >= 6 * 8, "The string files of the area: ${files.size}")
        files.forEach { file -> assertFalse("http" in file.readText(), "A link in ${file.path}") }
    }

    @Test
    fun theCommonPartsRenderInLightAndDarkMode() {
        listOf(false, true).forEach { dark ->
            val mode = if (dark) "dark" else "light"
            renderFunctionScreenshot("functions-common-screen-$mode", dark) {
                FunctionScreen(
                    title = "Hole Circle",
                    onBack = {},
                    primaryLabel = "Create",
                    onPrimary = {},
                    errors = ErrorBlock(
                        UiText.Plain("This operation could not be completed. Please fix the errors below:"),
                        listOf(UiText.Plain("Circle Radius value is too small")),
                    ),
                    preview = {
                        PointPreview(
                            points = List(6) { index ->
                                val angle = Math.toRadians(index * 60.0)
                                PreviewPoint(50_000 * Math.sin(angle), 50_000 * Math.cos(angle), "${index + 1}")
                            },
                            hAxis = "X",
                            vAxis = "Y",
                            origin = PreviewPoint(0.0, 0.0),
                        )
                    },
                ) {
                    LengthField("Circle Radius", LengthFieldState.of(50_000.0, metric = true), {}, {}, tag = "radius")
                    NumberField("Hole Count", NumberFieldState.ofCount(6), {})
                    ChoiceRow(listOf(1 to "XY", 2 to "XZ", 3 to "YZ"), selected = 1, onSelect = {})
                }
            }
            renderFunctionScreenshot("functions-common-sheet-$mode", dark) {
                FunctionSheet(title = "Height Compensation", onDismiss = {}, primaryLabel = "Apply", onPrimary = {}) {
                    Column { Text("Z offset") }
                    LengthField("Z Offset", LengthFieldState.of(-12_500.0, metric = true), {}, {})
                }
            }
            renderFunctionScreenshot("functions-common-sheet-portrait-$mode", dark, widthDp = 800, heightDp = 1280) {
                FunctionSheet(title = "Height Compensation", onDismiss = {}, primaryLabel = "Apply", onPrimary = {}) {
                    LengthField("Z Offset", LengthFieldState.of(-12_500.0, metric = true), {}, {})
                }
            }
        }
    }
}
