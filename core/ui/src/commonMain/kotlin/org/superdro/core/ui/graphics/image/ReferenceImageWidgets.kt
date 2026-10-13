package org.superdro.core.ui.graphics.image

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.resources.Res
import org.superdro.core.resources.frame_cancel
import org.superdro.core.resources.ic_refimage_opacity
import org.superdro.core.resources.ic_refimage_visibility
import org.superdro.core.resources.ic_refimage_visibility_off
import org.superdro.core.resources.refimage_alpha_half
import org.superdro.core.resources.refimage_alpha_hidden
import org.superdro.core.resources.refimage_alpha_shown
import org.superdro.core.resources.refimage_delete
import org.superdro.core.resources.refimage_delete_message
import org.superdro.core.resources.refimage_delete_title
import org.superdro.core.resources.refimage_no_image
import org.superdro.core.resources.refimage_opacity
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets

/** The test tag of the opacity button of the reference image. */
const val REFERENCE_OPACITY_TAG: String = "reference-image-opacity"

/** The test tag of the confirm button of the delete question. */
const val REFERENCE_DELETE_CONFIRM_TAG: String = "reference-image-delete-confirm"

/** The opacity states of the reference image. */
enum class ReferenceAlpha(val alpha: Int, val label: StringResource, val icon: DrawableResource) {
    Shown(255, Res.string.refimage_alpha_shown, Res.drawable.ic_refimage_visibility),
    Half(127, Res.string.refimage_alpha_half, Res.drawable.ic_refimage_opacity),
    Hidden(0, Res.string.refimage_alpha_hidden, Res.drawable.ic_refimage_visibility_off),
    ;

    companion object {
        /** Gives the state of [alpha]: 0 is hidden, 255 is shown, and the other values are half. */
        fun of(alpha: Int): ReferenceAlpha = when {
            alpha <= 0 -> Hidden
            alpha >= 255 -> Shown
            else -> Half
        }
    }
}

/**
 * The question before the delete of the reference image: "Delete Reference Image?". A confirm
 * deletes the image file and the matrix.
 */
@Composable
fun DeleteReferenceImageDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.refimage_delete_title)) },
        text = { Text(stringResource(Res.string.refimage_delete_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm, colors = AccentButtons.text(), modifier = Modifier.testTag(REFERENCE_DELETE_CONFIRM_TAG)) {
                Text(stringResource(Res.string.refimage_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = AccentButtons.text()) { Text(stringResource(Res.string.frame_cancel)) }
        },
    )
}

/**
 * The opacity button of the reference image: a tap cycles the alpha 0 → 255 → 127 → 0,
 * and a long press asks "Delete Reference Image?". The button shows the state as an icon and a
 * text. With no image, the button is disabled.
 *
 * @param alpha The stored alpha of the image.
 * @param onLongPress Show the delete question ([DeleteReferenceImageDialog]).
 */
@Composable
fun ReferenceImageOpacityButton(
    alpha: Int,
    hasImage: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = ReferenceAlpha.of(alpha)
    val title = stringResource(Res.string.refimage_opacity)
    val stateText = stringResource(if (hasImage) state.label else Res.string.refimage_no_image)
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    Surface(
        modifier = modifier
            .defaultMinSize(minWidth = LocalTouchTargets.current.button, minHeight = LocalTouchTargets.current.button)
            .clip(shape)
            .combinedClickable(enabled = hasImage, role = Role.Button, onClick = onTap, onLongClick = onLongPress)
            .semantics {
                contentDescription = title
                stateDescription = stateText
            }
            .testTag(REFERENCE_OPACITY_TAG),
        shape = shape,
        color = if (hasImage && state != ReferenceAlpha.Hidden) colors.tertiaryContainer else colors.surfaceVariant,
        contentColor = if (hasImage && state != ReferenceAlpha.Hidden) colors.onTertiaryContainer else colors.onSurfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icon = if (hasImage) state.icon else ReferenceAlpha.Hidden.icon
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
                Text("$title: $stateText", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
