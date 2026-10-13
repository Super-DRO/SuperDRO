package org.superdro.core.functions.patterns

import org.superdro.core.model.SubDatum
import org.superdro.core.model.SubDatumGroupKind
import org.superdro.core.model.Workspace

/**
 * The automatic names and the ordinal of a workspace.
 *
 * The first point of a group gets the next free number of the workspace. The workspace keeps the
 * last used number in [Workspace.lastOrdinal]. After a group, SuperDRO stores the last number,
 * so a name does not repeat.
 *
 * When [Workspace.lastOrdinal] is 0, the count of the sub-datums of the workspace is the last
 * number. Thus an imported list of points does not get the names
 * again.
 */
object SubDatumNames {

    /** Gives the last used number of [workspace]. */
    fun lastOrdinal(workspace: Workspace): Int =
        if (workspace.lastOrdinal > 0) workspace.lastOrdinal else workspace.subDatums.size

    /** Gives the name `Point <n>` of one new point and the new last number. */
    fun nextPointName(workspace: Workspace): Pair<String, Int> {
        val ordinal = lastOrdinal(workspace) + 1
        return pointName(ordinal) to ordinal
    }

    /** Gives the name `Point <n>`. */
    fun pointName(ordinal: Int): String = "Point $ordinal"

    /**
     * Gives one new sub-datum at [point] with the name `Point <n>`, and the new last number of
     * [workspace]. The edge finder ("Add Sub-Datum") uses it.
     */
    fun newPoint(workspace: Workspace, point: AxisPoint): Pair<SubDatum, Int> {
        val (name, ordinal) = nextPointName(workspace)
        return SubDatum(name = name, x = point.x, y = point.y, z = point.z) to ordinal
    }

    /** Gives the name `"<prefix> - <n>"` of a pattern point. */
    fun patternName(prefix: String, ordinal: Int): String = "$prefix - $ordinal"
}

/**
 * The tag of a group of pattern points.
 */
object PatternTags {

    /** The tag of a group with a blank name. */
    const val BLANK_GROUP_TAG: String = "Group"

    private val WHITE_SPACE = Regex("""\s+""")

    /**
     * Normalizes a tag: the white space at the two ends goes away, and each run of white space
     * becomes one `_`. A blank text gives null.
     */
    fun normalize(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return trimmed.replace(WHITE_SPACE, "_")
    }

    /** The compare key of a tag: lowercase (`Locale.ROOT`). */
    fun key(tag: String): String = tag.lowercase()

    /**
     * Gives a tag that [usedTags] does not have: [tag], else `<tag>_2`, `<tag>_3`, and so on. The
     * compare ignores the case.
     */
    fun unique(tag: String, usedTags: Collection<String>): String {
        val used = usedTags.mapTo(HashSet()) { used -> key(used) }
        if (key(tag) !in used) return tag
        var index = 2
        while (key("${tag}_$index") in used) index++
        return "${tag}_$index"
    }

    /** Gives the tag of the group [groupName] in [workspace]. */
    fun groupTag(groupName: String, workspace: Workspace): String {
        val base = normalize(groupName) ?: BLANK_GROUP_TAG
        return unique(base, workspace.subDatums.flatMap { subDatum -> subDatum.tags })
    }
}

/**
 * The sub-datums of one pattern call.
 *
 * @property subDatums The new points, in pattern order.
 * @property lastOrdinal The new value of [Workspace.lastOrdinal]: the last used number.
 * @property tag The tag of the group.
 */
data class PatternBatchResult(val subDatums: List<SubDatum>, val lastOrdinal: Int, val tag: String)

/** Makes the sub-datums of a pattern. */
object PatternBatch {

    /**
     * Gives the sub-datums of [group] for [workspace]. All points get the group ID [groupId], the
     * kind [SubDatumGroupKind.Pattern], and one tag. The names are `"<group name> - <n>"` with the
     * next numbers of the workspace.
     */
    fun create(workspace: Workspace, group: PatternGroup, groupId: Long): PatternBatchResult {
        val tag = PatternTags.groupTag(group.groupName, workspace)
        val first = SubDatumNames.lastOrdinal(workspace) + 1
        val subDatums = group.points.mapIndexed { index, point ->
            SubDatum(
                name = SubDatumNames.patternName(group.groupName, first + index),
                tags = listOf(tag),
                x = point.x,
                y = point.y,
                z = point.z,
                groupId = groupId,
                groupKind = SubDatumGroupKind.Pattern,
            )
        }
        val last = if (subDatums.isEmpty()) SubDatumNames.lastOrdinal(workspace) else first + subDatums.size - 1
        return PatternBatchResult(subDatums, last, tag)
    }

    /** Adds the sub-datums of [group] to [workspace] and stores the last used number. */
    fun addTo(workspace: Workspace, group: PatternGroup, groupId: Long): Workspace {
        val batch = create(workspace, group, groupId)
        if (batch.subDatums.isEmpty()) return workspace
        return workspace.copy(subDatums = workspace.subDatums + batch.subDatums, lastOrdinal = batch.lastOrdinal)
    }
}
