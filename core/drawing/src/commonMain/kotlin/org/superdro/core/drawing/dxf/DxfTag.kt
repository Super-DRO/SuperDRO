package org.superdro.core.drawing.dxf

/** The value type of a DXF group code (DXF reference, "Group Code Value Types"). */
enum class DxfValueType {
    /** A string. In a binary file it ends with a NUL byte. */
    Text,

    /** A 16-bit integer. */
    Int16,

    /** A 32-bit integer. */
    Int32,

    /** A 64-bit integer. */
    Int64,

    /** An 8-byte IEEE double. */
    Real,

    /** A boolean: one byte in a binary file (codes 290 to 299). */
    Bool,

    /** A binary chunk: a length byte and the data in a binary file, hexadecimal text in an ASCII file. */
    Binary,
    ;

    /** True for the integer types and [Bool]. */
    val isInteger: Boolean get() = this == Int16 || this == Int32 || this == Int64 || this == Bool

    companion object {
        /** The highest group code that SuperDRO accepts. */
        const val MAX_GROUP_CODE: Int = 1071

        /** Gives the value type of the group [code]. */
        fun of(code: Int): DxfValueType = when (code) {
            in 10..59, in 110..149, in 210..239, in 460..469, in 1010..1059 -> Real
            in 60..79, in 170..179, in 270..289, in 370..389, in 400..409, in 1060..1070 -> Int16
            in 90..99, in 420..429, in 440..459, 1071 -> Int32
            in 160..169 -> Int64
            in 290..299 -> Bool
            in 310..319, 1004 -> Binary
            else -> Text
        }
    }
}

/**
 * One DXF tag: a group code and its value. Both tokenizers give the same tags for the same
 * drawing.
 *
 * - [DxfValueType.Text]: [text].
 * - The integer types and [DxfValueType.Bool]: [integer].
 * - [DxfValueType.Real]: [real].
 * - [DxfValueType.Binary]: [text] as uppercase hexadecimal digits.
 */
data class DxfTag(
    val code: Int,
    val text: String = "",
    val integer: Long = 0L,
    val real: Double = 0.0,
) {
    /** The value type of [code]. */
    val type: DxfValueType get() = DxfValueType.of(code)

    /** The value as a number: [real], [integer], or the number of [text]. */
    val number: Double
        get() = when (type) {
            DxfValueType.Real -> real
            DxfValueType.Text, DxfValueType.Binary -> text.trim().toDoubleOrNull() ?: 0.0
            else -> integer.toDouble()
        }

    /** The value as an integer: [integer], [real] truncated, or the number of [text]. */
    val int: Long
        get() = when (type) {
            DxfValueType.Real -> real.toLong()
            DxfValueType.Text, DxfValueType.Binary -> text.trim().toLongOrNull() ?: 0L
            else -> integer
        }

    /** The value as a string, with no white space at the two ends. */
    val trimmed: String
        get() = when (type) {
            DxfValueType.Text, DxfValueType.Binary -> text.trim()
            DxfValueType.Real -> real.toString()
            else -> integer.toString()
        }

    override fun toString(): String = "($code, ${if (type == DxfValueType.Text || type == DxfValueType.Binary) "\"$text\"" else trimmed})"

    companion object {
        fun text(code: Int, value: String): DxfTag = DxfTag(code, text = value)

        fun integer(code: Int, value: Long): DxfTag = DxfTag(code, integer = value)

        fun real(code: Int, value: Double): DxfTag = DxfTag(code, real = value)
    }
}
