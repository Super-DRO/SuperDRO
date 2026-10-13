package org.superdro.core.ui.text

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.superdro.core.functions.compensation.CompensationTexts
import org.superdro.core.readout.ReadoutMessage
import org.superdro.core.readout.ReadoutText
import org.superdro.core.readout.expression.ExpressionError
import org.superdro.core.resources.Res
import org.superdro.core.resources.route_hole_circle
import org.superdro.core.store.StoreRefusal

/** The resolver maps the key of a message of a lower module to its string resource. */
@OptIn(ExperimentalTestApi::class)
class UiTextTest {

    @Test
    fun eachKeyOfTheLowerModulesHasAResource() {
        ReadoutText.entries.forEach { text -> assertNotNull(stringResourceForKey(text.key), text.key) }
        StoreRefusal.entries.forEach { refusal -> assertNotNull(stringResourceForKey(refusal.stringKey), refusal.stringKey) }
        CompensationTexts.all.forEach { template -> assertNotNull(stringResourceForKey(template.key), template.key) }
        assertNotNull(stringResourceForKey(ExpressionError.DivisionByZero.key))
        assertNull(stringResourceForKey("no_such_key"))
    }

    @Test
    fun aKeyResolvesToTheTextOfTheResourceWithItsArguments() = runComposeUiTest {
        setContent {
            Text(ReadoutMessage(ReadoutText.CantSetRpm).toUiText().resolve())
            Text(ReadoutMessage(ReadoutText.MapDuplicatePosition, listOf(12)).toUiText().resolve())
            Text(ExpressionError.UndefinedVariable("q").toUiText().resolve())
            Text(StoreRefusal.ProfileNameExists.toUiText().resolve())
            Text(UiText.Key("no_such_key", "Fallback %1\$s", listOf(UiText.of(Res.string.route_hole_circle))).resolve())
            Text(UiText.Plain("Mill").resolve())
        }
        onNodeWithText("Can't set RPM").assertIsDisplayed()
        onNodeWithText("Position 12 occurs more than one time.").assertIsDisplayed()
        onNodeWithText("Undefined variable 'q'").assertIsDisplayed()
        onNodeWithText("A profile with this name already exists.").assertIsDisplayed()
        onNodeWithText("Fallback Hole circle").assertIsDisplayed()
        onNodeWithText("Mill").assertIsDisplayed()
    }

    @Test
    fun aFunctionMessageKeepsItsKeyAndItsEnglishText() {
        val text = CompensationTexts.Z_OFFSET_ZERO.message().toUiText() as UiText.Key
        assertEquals("compensation_error_z_offset_zero", text.key)
        assertEquals("Z offset can't be 0", text.fallback)
    }
}
