package org.superdro.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import org.superdro.core.model.ReadoutFont
import org.superdro.core.resources.Res
import org.superdro.core.resources.cousine_bold
import org.superdro.core.resources.cousine_regular
import org.superdro.core.resources.droid_sans_mono_regular
import org.superdro.core.resources.dseg7_modern_bold_italic
import org.superdro.core.resources.jetbrains_mono_bold
import org.superdro.core.resources.jetbrains_mono_regular
import org.superdro.core.resources.roboto_mono_bold
import org.superdro.core.resources.roboto_mono_regular

/**
 * The text styles of the readout digits. The digits use
 * a monospace font with tabular figures (`tnum`), so that the sign has a fixed column and the
 * decimal mark has a fixed position. The readout row sets the size to fit the row
 * (`TextStyle.copy(fontSize = ...)`).
 *
 * Use the colors `onSurface` on `surface` for the digits.
 *
 * @property digits The style of the readout value.
 * @property unit The style of the unit text after the value.
 */
@Immutable
data class ReadoutTypography(
    val font: ReadoutFont,
    val digits: TextStyle,
    val unit: TextStyle,
)

/** The OpenType feature of tabular figures. */
const val TABULAR_FIGURES: String = "tnum"

/** The readout styles of the current theme. [AppTheme] gives the value. */
val LocalReadoutTypography = staticCompositionLocalOf<ReadoutTypography> {
    error("No ReadoutTypography: put the content in AppTheme.")
}

/** Gives the font family of [font]. The font files are Compose resources in `core:resources`. */
@Composable
fun readoutFontFamily(font: ReadoutFont): FontFamily = when (font) {
    // DSEG7 Modern Bold Italic is the only face of the family. It is registered as the normal
    // face, so that Compose does not make a synthetic bold or italic face from it.
    ReadoutFont.Dseg7 -> FontFamily(Font(Res.font.dseg7_modern_bold_italic))
    ReadoutFont.PlatformMonospace -> FontFamily.Monospace
    ReadoutFont.System -> FontFamily.Default
    ReadoutFont.JetBrainsMono -> FontFamily(
        Font(Res.font.jetbrains_mono_regular, FontWeight.Normal),
        Font(Res.font.jetbrains_mono_bold, FontWeight.Bold),
    )
    ReadoutFont.RobotoMono -> FontFamily(
        Font(Res.font.roboto_mono_regular, FontWeight.Normal),
        Font(Res.font.roboto_mono_bold, FontWeight.Bold),
    )
    ReadoutFont.DroidSansMono -> FontFamily(Font(Res.font.droid_sans_mono_regular, FontWeight.Normal))
    ReadoutFont.Cousine -> FontFamily(
        Font(Res.font.cousine_regular, FontWeight.Normal),
        Font(Res.font.cousine_bold, FontWeight.Bold),
    )
}

/** Gives the readout styles for [font]. The default font is DSEG7. */
@Composable
fun readoutTypography(font: ReadoutFont = ReadoutFont.Dseg7): ReadoutTypography {
    val family = readoutFontFamily(font)
    return ReadoutTypography(
        font = font,
        digits = TextStyle(
            fontFamily = family,
            fontSize = 64.sp,
            fontFeatureSettings = TABULAR_FIGURES,
        ),
        unit = TextStyle(
            fontFamily = FontFamily.Default,
            fontSize = 20.sp,
        ),
    )
}
