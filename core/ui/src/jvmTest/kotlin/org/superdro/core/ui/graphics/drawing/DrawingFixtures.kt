package org.superdro.core.ui.graphics.drawing

/** The drawing files of the tests of the drawing screens. */
object DrawingFixtures {

    /** Gives an ASCII DXF file in mm with the entities [entities] (tag pairs, one pair for each line pair). */
    fun asciiDxf(vararg entities: String): ByteArray {
        val header = listOf("0", "SECTION", "2", "HEADER", "9", "\$INSUNITS", "70", "4", "0", "ENDSEC")
        val body = listOf("0", "SECTION", "2", "ENTITIES") + entities.flatMap { entity -> entity.trim().lines().map { line -> line.trim() } } +
            listOf("0", "ENDSEC", "0", "EOF")
        return (header + body).joinToString("\r\n", postfix = "\r\n").encodeToByteArray()
    }

    /** A LINE from ([x1], [y1]) to ([x2], [y2]) mm on the layer [layer]. */
    fun line(x1: Double, y1: Double, x2: Double, y2: Double, layer: String = "0"): String =
        "0\nLINE\n8\n$layer\n10\n$x1\n20\n$y1\n30\n0.0\n11\n$x2\n21\n$y2\n31\n0.0"

    /** A CIRCLE at ([x], [y]) mm with the radius [r] mm. */
    fun circle(x: Double, y: Double, r: Double, layer: String = "0"): String =
        "0\nCIRCLE\n8\n$layer\n10\n$x\n20\n$y\n30\n0.0\n40\n$r"

    /** A POINT at ([x], [y]) mm. */
    fun point(x: Double, y: Double, layer: String = "0"): String =
        "0\nPOINT\n8\n$layer\n10\n$x\n20\n$y\n30\n0.0"

    /**
     * The part of the setup tests: a line from (0, 0) to (20, 0) mm, a hole of Ø 6 mm at (10, 5)
     * mm, and a point at (5, 5) mm.
     */
    val part: ByteArray = asciiDxf(line(0.0, 0.0, 20.0, 0.0), circle(10.0, 5.0, 3.0), point(5.0, 5.0))

    /** A closed triangle of three lines with a corner at the origin: a cutter of 10 mm × 4 mm. */
    val cutter: ByteArray = asciiDxf(line(0.0, 0.0, 10.0, 2.0), line(10.0, 2.0, 10.0, -2.0), line(10.0, -2.0, 0.0, 0.0))

    /** A wireframe document with no entities: the reader gives a problem list. */
    val brokenWireframe: ByteArray = """{"schema":"touchdro.wireframe","version":"3.0","layers":[]}""".encodeToByteArray()

    /** The binary DXF fixture of `core:drawing` (R12, mm). */
    fun binaryDxf(): ByteArray =
        DrawingFixtures::class.java.getResourceAsStream("/graphics/drawing/r12_mm.dxb")!!.use { stream -> stream.readBytes() }
}
