package org.superdro.core.ui.functions.subdatums

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.superdro.core.functions.patterns.PatternTags
import org.superdro.core.model.SubDatum
import org.superdro.core.resources.Res
import org.superdro.core.resources.sd_tag_cleared
import org.superdro.core.resources.sd_tag_set
import org.superdro.core.ui.functions.common.FunctionServices
import org.superdro.core.ui.functions.common.UndoOffers
import org.superdro.core.ui.presenter.ScopedPresenter
import org.superdro.core.ui.text.UiText

/**
 * The UI state of the bulk tag sheet "Set Sub-Datum Tag".
 *
 * @property count The number of the selected points.
 * @property suggestions The tags of the workspace, for a tap that fills the field.
 */
@Immutable
data class TagSheetUiState(val text: String = "", val count: Int = 0, val suggestions: List<String> = emptyList())

/**
 * The presenter of the bulk tag sheet "Set Sub-Datum Tag". The typed tags go to each
 * selected point in one transaction. An empty field removes the tags. The old tags of each point come back with "Undo" for 10 s.
 *
 * @param points The selected points.
 * @param workspaceTags The tags of the workspace.
 */
class TagSheetPresenter(
    private val points: List<SubDatum>,
    workspaceTags: List<String>,
    private val services: FunctionServices,
    private val undo: UndoOffers,
) : ScopedPresenter(services.context) {

    private val mutable = MutableStateFlow(
        TagSheetUiState(
            text = points.map { it.tags }.distinct().singleOrNull()?.let(SubDatumNames::tagText).orEmpty(),
            count = points.size,
            suggestions = workspaceTags.distinctBy(PatternTags::key).sortedWith(String.CASE_INSENSITIVE_ORDER),
        ),
    )

    /** The UI state of the sheet. */
    val uiState: StateFlow<TagSheetUiState> = mutable.asStateFlow()

    fun onText(text: String) = mutable.update { it.copy(text = text) }

    /** A tap on the suggestion [tag]: the field gets it. */
    fun onSuggestion(tag: String) = mutable.update { it.copy(text = tag) }

    /** Writes the tags. Gives true when the sheet can close. */
    suspend fun apply(): Boolean {
        if (points.isEmpty()) return true
        val tags = SubDatumNames.parseTags(mutable.value.text)
        val old = points.groupBy({ it.tags }, { it.id })
        services.machines.setTags(points.map { it.id }, tags)
        val label = if (tags.isEmpty()) {
            UiText.of(Res.string.sd_tag_cleared, points.size)
        } else {
            UiText.of(Res.string.sd_tag_set, SubDatumNames.tagText(tags), points.size)
        }
        undo.offer(label) { old.forEach { (oldTags, ids) -> services.machines.setTags(ids, oldTags) } }
        return true
    }
}
