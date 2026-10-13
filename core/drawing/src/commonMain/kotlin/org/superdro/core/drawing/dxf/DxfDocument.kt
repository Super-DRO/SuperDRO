package org.superdro.core.drawing.dxf

/**
 * One DXF entity. [tags] has the geometry tags of the entity: all tags after the type tag, with
 * no APP DATA groups (code 102) and no XDATA (codes 1000 and more).
 *
 * @property children The VERTEX entities of a POLYLINE, or the ATTRIB entities of an INSERT.
 */
internal class DxfEntity(
    val type: String,
    val tags: List<DxfTag>,
    val handle: String?,
    val owner: String?,
    val layer: String,
    val lineType: String?,
    val paperSpace: Boolean,
    val children: List<DxfEntity> = emptyList(),
) {
    fun first(code: Int): DxfTag? = tags.firstOrNull { tag -> tag.code == code }

    fun double(code: Int, default: Double = 0.0): Double = first(code)?.number ?: default

    fun int(code: Int, default: Long = 0L): Long = first(code)?.int ?: default

    fun string(code: Int): String? = first(code)?.text

    fun all(code: Int): List<DxfTag> = tags.filter { tag -> tag.code == code }

    override fun toString(): String = "DxfEntity($type, layer=$layer, ${tags.size} tags)"
}

/** One block of the BLOCKS section. */
internal class DxfBlock(
    val name: String,
    val flags: Int,
    val baseX: Double,
    val baseY: Double,
    val entities: List<DxfEntity>,
) {
    /** True for an external reference (flags 4 or 8). The converter does not read it. */
    val isExternal: Boolean get() = flags and 0x0C != 0

    /** True for `*Model_Space`, `*Paper_Space…`, `$Model_Space`, and `$Paper_Space`. */
    val isLayout: Boolean get() = isLayoutName(name)

    /** True for the block of the model space. */
    val isModelSpace: Boolean get() = name.uppercase().let { upper -> upper == "*MODEL_SPACE" || upper == "\$MODEL_SPACE" }

    companion object {
        fun isLayoutName(name: String): Boolean {
            val upper = name.uppercase()
            return upper == "*MODEL_SPACE" || upper == "\$MODEL_SPACE" || upper.startsWith("*PAPER_SPACE") ||
                upper.startsWith("\$PAPER_SPACE")
        }
    }
}

/** One record of the LAYER table. */
internal class DxfLayer(val name: String, val color: Int, val flags: Int, val lineType: String) {
    /** True when the layer is on (color not negative) and not frozen (flag 1). */
    val visible: Boolean get() = color >= 0 && flags and 1 == 0
}

/** The parts of a DXF file that the converter uses. */
internal class DxfDocument(
    val header: Map<String, List<DxfTag>>,
    val layers: List<DxfLayer>,
    val lineTypes: Set<String>,
    val blockRecords: Map<String, String>,
    val blocks: Map<String, DxfBlock>,
    val entities: List<DxfEntity>,
) {
    /** The first value of the header variable [name] with the group [code], or null. */
    fun headerValue(name: String, code: Int): DxfTag? =
        header[name.uppercase()]?.firstOrNull { tag -> tag.code == code }

    private val layerMap: Map<String, DxfLayer> = layers.associateBy { layer -> layer.name.uppercase() }

    fun layer(name: String): DxfLayer? = layerMap[name.uppercase()]

    fun block(name: String): DxfBlock? = blocks[name.uppercase()]

    /** True when the LTYPE table has [name]. The test ignores case. */
    fun hasLineType(name: String): Boolean = name.uppercase() in lineTypes
}

/**
 * The DXF reader. It reads the sections HEADER, TABLES, BLOCKS, and ENTITIES, and skips the other
 * sections. It refuses structure errors with `PARSE_ERROR`: a duplicate entity handle, the handle
 * 0, a bad XDATA or APP DATA structure, ENDBLK without BLOCK, and POLYLINE without SEQEND.
 */
internal class DxfReader(private val tags: List<DxfTag>, private val budget: ParseBudget) {
    private var index = 0
    private val header = LinkedHashMap<String, List<DxfTag>>()
    private val layers = ArrayList<DxfLayer>()
    private val lineTypes = LinkedHashSet<String>()
    private val blockRecords = HashMap<String, String>()
    private val blocks = LinkedHashMap<String, DxfBlock>()
    private val entities = ArrayList<DxfEntity>()
    private val handles = HashSet<String>()

    fun read(): DxfDocument {
        while (index < tags.size) {
            val tag = tags[index]
            if (tag.code != 0) parseError("tag ${index + 1}: group code ${tag.code} outside a section")
            when (val keyword = tag.trimmed.uppercase()) {
                "SECTION" -> {
                    index++
                    val nameTag = tags.getOrNull(index)
                    if (nameTag == null || nameTag.code != 2) parseError("a SECTION has no name")
                    index++
                    readSection(nameTag.trimmed.uppercase())
                }
                "EOF" -> break
                else -> parseError("$keyword outside a section")
            }
        }
        return DxfDocument(header, layers, lineTypes, blockRecords, blocks, entities)
    }

    private fun atKeyword(keyword: String): Boolean {
        val tag = tags.getOrNull(index) ?: return false
        return tag.code == 0 && tag.trimmed.equals(keyword, ignoreCase = true)
    }

    private fun requireNotEnd(section: String) {
        if (index >= tags.size) parseError("section $section has no ENDSEC")
    }

    private fun readSection(name: String) {
        when (name) {
            "HEADER" -> readHeader()
            "TABLES" -> readTables()
            "BLOCKS" -> readBlocks()
            "ENTITIES" -> {
                while (true) {
                    requireNotEnd(name)
                    if (atKeyword("ENDSEC")) break
                    entities += readEntity(name)
                }
            }
            else -> {
                while (true) {
                    requireNotEnd(name)
                    if (atKeyword("ENDSEC")) break
                    index++
                }
            }
        }
        index++
    }

    private fun readHeader() {
        while (true) {
            requireNotEnd("HEADER")
            if (atKeyword("ENDSEC")) return
            val tag = tags[index]
            if (tag.code != 9) {
                index++
                continue
            }
            val name = tag.trimmed.uppercase()
            index++
            val values = ArrayList<DxfTag>()
            while (index < tags.size && tags[index].code != 9 && tags[index].code != 0) values += tags[index++]
            header[name] = values
        }
    }

    /** Collects the tags from the current index to the next tag with the code 0. */
    private fun collect(): List<DxfTag> {
        val start = index
        while (index < tags.size && tags[index].code != 0) {
            budget.tick()
            index++
        }
        return tags.subList(start, index)
    }

    private fun readTables() {
        while (true) {
            requireNotEnd("TABLES")
            if (atKeyword("ENDSEC")) return
            if (!atKeyword("TABLE")) parseError("TABLES has ${tags[index].trimmed} where a TABLE must be")
            index++
            val tableTags = collect()
            val tableName = tableTags.firstOrNull { tag -> tag.code == 2 }?.trimmed?.uppercase().orEmpty()
            while (true) {
                if (index >= tags.size) parseError("table $tableName has no ENDTAB")
                if (atKeyword("ENDTAB")) break
                if (atKeyword("ENDSEC")) parseError("table $tableName has no ENDTAB")
                val recordType = tags[index].trimmed.uppercase()
                index++
                val record = stripGroups(recordType, collect())
                readTableRecord(tableName, recordType, record)
            }
            index++
        }
    }

    private fun readTableRecord(table: String, recordType: String, record: List<DxfTag>) {
        fun first(code: Int) = record.firstOrNull { tag -> tag.code == code }
        when {
            table == "LAYER" && recordType == "LAYER" -> {
                val name = first(2)?.text ?: return
                layers += DxfLayer(
                    name = name,
                    color = first(62)?.int?.toInt() ?: 7,
                    flags = first(70)?.int?.toInt() ?: 0,
                    lineType = first(6)?.text?.trim()?.takeIf { text -> text.isNotEmpty() } ?: "Continuous",
                )
            }
            table == "LTYPE" && recordType == "LTYPE" -> first(2)?.text?.trim()?.let { name -> lineTypes += name.uppercase() }
            table == "BLOCK_RECORD" && recordType == "BLOCK_RECORD" -> {
                val handle = first(5)?.trimmed ?: return
                val name = first(2)?.text ?: return
                blockRecords[handle.uppercase()] = name
            }
        }
    }

    private fun readBlocks() {
        while (true) {
            requireNotEnd("BLOCKS")
            if (atKeyword("ENDSEC")) return
            if (atKeyword("ENDBLK")) parseError("ENDBLK without a previous BLOCK")
            if (!atKeyword("BLOCK")) {
                // An entity outside a block: refuse it as a structure error.
                parseError("BLOCKS has ${tags[index].trimmed} outside a BLOCK")
            }
            index++
            val blockTags = stripGroups("BLOCK", collect())
            fun first(code: Int) = blockTags.firstOrNull { tag -> tag.code == code }
            val name = first(2)?.text ?: first(3)?.text ?: parseError("a BLOCK has no name")
            val content = ArrayList<DxfEntity>()
            while (true) {
                if (index >= tags.size || atKeyword("ENDSEC")) parseError("BLOCK $name has no ENDBLK")
                if (atKeyword("ENDBLK")) break
                if (atKeyword("BLOCK")) parseError("BLOCK $name has no ENDBLK")
                content += readEntity("BLOCK $name")
            }
            index++
            stripGroups("ENDBLK", collect())
            blocks[name.uppercase()] = DxfBlock(
                name = name,
                flags = first(70)?.int?.toInt() ?: 0,
                baseX = first(10)?.number ?: 0.0,
                baseY = first(20)?.number ?: 0.0,
                entities = content,
            )
        }
    }

    /** Reads one entity at the current index, with the VERTEX and ATTRIB entities that follow. */
    private fun readEntity(where: String): DxfEntity {
        budget.tick()
        val type = tags[index].trimmed.uppercase()
        if (type == "VERTEX") parseError("VERTEX without a previous POLYLINE in $where")
        if (type == "SEQEND") parseError("SEQEND without a previous POLYLINE or INSERT in $where")
        val entity = readOne(type)
        return when {
            type == "POLYLINE" -> {
                val vertices = ArrayList<DxfEntity>()
                while (atKeyword("VERTEX")) vertices += readOne("VERTEX")
                if (!atKeyword("SEQEND")) parseError("POLYLINE without SEQEND in $where")
                readOne("SEQEND")
                entity.withChildren(vertices)
            }
            type == "INSERT" && entity.int(66) == 1L -> {
                val attributes = ArrayList<DxfEntity>()
                while (atKeyword("ATTRIB")) attributes += readOne("ATTRIB")
                if (atKeyword("SEQEND")) readOne("SEQEND")
                entity.withChildren(attributes)
            }
            else -> entity
        }
    }

    private fun DxfEntity.withChildren(children: List<DxfEntity>) =
        DxfEntity(type, tags, handle, owner, layer, lineType, paperSpace, children)

    private fun readOne(type: String): DxfEntity {
        index++
        val geometry = stripGroups(type, collect())
        var handle: String? = null
        var owner: String? = null
        var layer: String? = null
        var lineType: String? = null
        var paperSpace = false
        for (tag in geometry) {
            when (tag.code) {
                5 -> if (handle == null) handle = tag.trimmed
                330 -> if (owner == null) owner = tag.trimmed
                8 -> if (layer == null) layer = tag.text
                6 -> if (lineType == null) lineType = tag.trimmed
                67 -> paperSpace = tag.int == 1L
            }
        }
        handle?.let { value -> checkHandle(type, value) }
        return DxfEntity(type, geometry, handle, owner, layer ?: "0", lineType, paperSpace)
    }

    private fun checkHandle(type: String, handle: String) {
        val key = handle.uppercase().trimStart('0')
        if (key.isEmpty()) parseError("$type has the handle 0")
        if (!handles.add(key)) parseError("duplicate entity handle $handle ($type)")
    }

    /**
     * Gives the tags with no APP DATA groups and no XDATA. Refuses a bad structure: a group `{`
     * with no `}`, a `}` with no `{`, XDATA that does not start with 1001, a tag below 1000 after
     * the XDATA, or a 1002 group with no end.
     */
    private fun stripGroups(type: String, input: List<DxfTag>): List<DxfTag> {
        var inGroup = false
        var inXdata = false
        var xdataDepth = 0
        val output = ArrayList<DxfTag>(input.size)
        for (tag in input) {
            if (inXdata) {
                if (tag.code < 1000) parseError("bad XDATA structure in $type: group code ${tag.code} after the XDATA")
                when (tag.code) {
                    1001 -> {
                        if (xdataDepth != 0) parseError("bad XDATA structure in $type: a 1002 group has no end")
                    }
                    1002 -> {
                        when (tag.trimmed) {
                            "{" -> xdataDepth++
                            "}" -> {
                                if (xdataDepth == 0) parseError("bad XDATA structure in $type: a 1002 \"}\" has no start")
                                xdataDepth--
                            }
                            else -> parseError("bad XDATA structure in $type: 1002 \"${tag.trimmed}\"")
                        }
                    }
                }
                continue
            }
            if (tag.code >= 1000) {
                if (tag.code != 1001) parseError("bad XDATA structure in $type: the XDATA does not start with 1001")
                inXdata = true
                continue
            }
            if (tag.code == 102) {
                val value = tag.trimmed
                when {
                    value.startsWith("{") -> {
                        if (inGroup) parseError("bad APP DATA structure in $type: a group in a group")
                        inGroup = true
                    }
                    value == "}" -> {
                        if (!inGroup) parseError("bad APP DATA structure in $type: \"}\" has no start")
                        inGroup = false
                    }
                    else -> parseError("bad APP DATA structure in $type: 102 \"$value\"")
                }
                continue
            }
            if (!inGroup) output += tag
        }
        if (inGroup) parseError("bad APP DATA structure in $type: a group has no end")
        if (xdataDepth != 0) parseError("bad XDATA structure in $type: a 1002 group has no end")
        return output
    }
}
