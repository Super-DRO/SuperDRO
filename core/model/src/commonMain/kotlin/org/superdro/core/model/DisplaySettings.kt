package org.superdro.core.model

import kotlinx.serialization.Serializable

/**
 * The readout number format (`display_format`). The code gives the
 * decimals: 2 is `00.000` in and `000.00` mm, 3 is `00.0000` in and `000.000` mm, 5 is
 * `00.00000` in and `000.0000` mm.
 */
@Serializable
enum class ReadoutFormat(val code: Int, val inchDecimals: Int, val mmDecimals: Int) {
    Format2(code = 2, inchDecimals = 3, mmDecimals = 2),
    Format3(code = 3, inchDecimals = 4, mmDecimals = 3),
    Format5(code = 5, inchDecimals = 5, mmDecimals = 4);

    companion object {
        /**
         * Gives the format of a TouchDRO `display_format` value. The values 0 and 1 give 2 and 3.
         * An unknown value gives null.
         */
        fun fromTouchDroCode(code: Int): ReadoutFormat? = when (code) {
            0, 2 -> Format2
            1, 3 -> Format3
            4, 5 -> Format5
            else -> null
        }
    }
}

/**
 * The readout font. The font files are Compose resources in
 * `core:resources`. [PlatformMonospace] and [System] use the fonts of the platform.
 */
@Serializable
enum class ReadoutFont {
    /** DSEG7 Modern Bold Italic (SIL OFL 1.1). The default. */
    Dseg7,
    PlatformMonospace,
    JetBrainsMono,
    System,
    RobotoMono,
    DroidSansMono,
    Cousine,
}

/** The display settings of a machine profile. */
@Serializable
data class DisplaySettings(
    val readoutFormat: ReadoutFormat = ReadoutFormat.Format3,
    val readoutFont: ReadoutFont = ReadoutFont.Dseg7,
    /** `show_sliders`: the position indicators. */
    val showSliders: Boolean = true,
    /** `short_press_zero_set`. */
    val shortPressZero: Boolean = false,
    /** `activate_added_subdatum`. */
    val activateNewSubDatum: Boolean = true,
    /**
     * `camera_keep_overlays`: the sub-datums, the guides, the tool, and the drawing stay on the camera
     * video. Default false.
     */
    val cameraKeepOverlays: Boolean = false,
    /**
     * `camera_crosshair_color`: the key of the crosshair color while the video is on. The keys are
     * the keys of `CrosshairColor` of `core:camera`. Default `"default"`.
     */
    val cameraCrosshairColor: String = "default",
    /** The part of the window width that the readout column uses in the expanded layout. */
    val readoutPaneFraction: Float = 0.45f,
    /** The part of the content height that the graphical view uses in portrait. */
    val graphicalPaneWeight: Float = 0.6f,
)

/**
 * The function strip switches (`show_*`). All are on for a new profile.
 */
@Serializable
data class FunctionButtonSettings(
    val edgeFinder: Boolean = true,
    val zReference: Boolean = true,
    val radiusCompensation: Boolean = true,
    val heightCompensation: Boolean = true,
    val holeCircle: Boolean = true,
    val arbitraryHoleCircle: Boolean = true,
    val holeGrid: Boolean = true,
    val equidistantArray: Boolean = true,
    val dimensionMeasure: Boolean = true,
    val angleMeasure: Boolean = true,
    val arcCut: Boolean = true,
    /** `show_head_tilt`. */
    val headTilt: Boolean = true,
    /** `show_compound_angle`. */
    val compoundAngle: Boolean = true,
)
