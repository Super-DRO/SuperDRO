package org.superdro.core.drawing.dxf

import kotlin.time.Instant

/** Builds small ASCII DXF files for the tests. Each pair is "code value". */
internal object TestDxf {
    val NOW: Instant = Instant.parse("2026-10-12T08:30:00Z")

    /** Gives the ASCII DXF text of the pairs. Each pair has the form `code|value`. */
    fun text(vararg pairs: String): String = pairs.joinToString("") { pair ->
        val separator = pair.indexOf('|')
        "${pair.substring(0, separator).padStart(3)}\n${pair.substring(separator + 1)}\n"
    }

    /**
     * A file with a HEADER ([header] pairs), a LTYPE table and a LAYER table ([layers] pairs), a
     * BLOCKS section ([blocks] pairs), and an ENTITIES section ([entities] pairs).
     */
    fun file(
        entities: List<String>,
        header: List<String> = listOf("9|\$INSUNITS", "70|4"),
        layers: List<String> = emptyList(),
        lineTypes: List<String> = listOf("Continuous", "CENTER", "DASHED"),
        blocks: List<String> = emptyList(),
    ): ByteArray {
        val pairs = ArrayList<String>()
        pairs += listOf("0|SECTION", "2|HEADER", "9|\$ACADVER", "1|AC1015")
        pairs += header
        pairs += listOf("0|ENDSEC", "0|SECTION", "2|TABLES", "0|TABLE", "2|LTYPE")
        for (name in lineTypes) pairs += listOf("0|LTYPE", "2|$name", "70|0")
        pairs += listOf("0|ENDTAB", "0|TABLE", "2|LAYER")
        pairs += layers
        pairs += listOf("0|ENDTAB", "0|ENDSEC", "0|SECTION", "2|BLOCKS")
        pairs += blocks
        pairs += listOf("0|ENDSEC", "0|SECTION", "2|ENTITIES")
        pairs += entities
        pairs += listOf("0|ENDSEC", "0|EOF")
        return text(*pairs.toTypedArray()).encodeToByteArray()
    }

    fun line(x1: Double, y1: Double, x2: Double, y2: Double, layer: String = "0", extra: List<String> = emptyList()): List<String> =
        listOf("0|LINE", "8|$layer") + extra + listOf("10|$x1", "20|$y1", "30|0.0", "11|$x2", "21|$y2", "31|0.0")

    fun layer(name: String, color: Int = 7, flags: Int = 0, lineType: String = "Continuous"): List<String> =
        listOf("0|LAYER", "2|$name", "70|$flags", "62|$color", "6|$lineType")
}
