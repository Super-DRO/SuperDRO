package org.superdro.core.ui.graphics.drawing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.superdro.core.drawing.DrawingProblem
import org.superdro.core.resources.Res
import org.superdro.core.resources.drawing_done
import org.superdro.core.resources.drawing_not_imported_message
import org.superdro.core.resources.drawing_not_imported_title
import org.superdro.core.resources.drawing_problems_explanation
import org.superdro.core.resources.drawing_technical_details
import org.superdro.core.resources.ic_error
import org.superdro.core.resources.ic_info
import org.superdro.core.ui.navigation.ScreenScaffold
import org.superdro.core.ui.theme.AccentButtons
import org.superdro.core.ui.theme.LocalTouchTargets

/** The test tag of the "Technical details" button. */
const val DRAWING_DETAILS_TAG: String = "drawing-problems-details"

/** The test tag of the problem list. */
const val DRAWING_PROBLEM_LIST_TAG: String = "drawing-problems-list"

/** The test tag of the "Done" button. */
const val DRAWING_PROBLEMS_DONE_TAG: String = "drawing-problems-done"

/**
 * "Drawing Not Imported": the message, a
 * "Technical details" part with the explanation and the problem list, and "Done". The problem
 * list is technical data with codes, as the converter gives it, so it has no translation.
 *
 * @param detailsShown True when the technical details show at the start.
 */
@Composable
fun DrawingProblemsScreen(problems: List<DrawingProblem>, detailsShown: Boolean = false, onDone: () -> Unit) {
    var details by rememberSaveable { mutableStateOf(detailsShown) }
    ScreenScaffold(title = stringResource(Res.string.drawing_not_imported_title), onBack = onDone) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(Res.drawable.ic_error), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(Res.string.drawing_not_imported_message), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.size(16.dp))
            TextButton(
                onClick = { details = !details },
                colors = AccentButtons.text(),
                modifier = Modifier.heightIn(min = LocalTouchTargets.current.button).testTag(DRAWING_DETAILS_TAG),
            ) {
                Icon(painterResource(Res.drawable.ic_info), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.drawing_technical_details))
            }
            if (details) {
                Text(
                    stringResource(Res.string.drawing_problems_explanation),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag(DRAWING_PROBLEM_LIST_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(problems) { problem ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(problem.code, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.error)
                            Text(problem.message, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            FilledTonalButton(
                onClick = onDone,
                modifier = Modifier.align(Alignment.End).heightIn(min = LocalTouchTargets.current.button).testTag(DRAWING_PROBLEMS_DONE_TAG),
            ) { Text(stringResource(Res.string.drawing_done)) }
        }
    }
}
