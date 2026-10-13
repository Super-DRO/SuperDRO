package org.superdro.core.adapterprobe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.superdro.core.util.Bytes

/**
 * The golden streams of `adapter_probe.py --self-test` (the list `GOLDEN`), copied into the test
 * resource `golden/self-test-streams.txt`. Each stream gives the same messages on the SuperDRO
 * parser as on the parser of the tool, in one block and in blocks of one byte.
 */
class GoldenStreamsTest {

    private class Golden(val stream: ByteArray, val expected: List<String>)

    private fun load(): List<Golden> {
        val text = checkNotNull(javaClass.classLoader.getResource("golden/self-test-streams.txt")) { "No golden streams" }.readText()
        val result = ArrayList<Golden>()
        var stream: ByteArray? = null
        val expected = ArrayList<String>()
        for (line in text.lines()) {
            when {
                line.startsWith("stream ") -> {
                    stream?.let { result += Golden(it, expected.toList()) }
                    stream = Bytes.fromHex(line.removePrefix("stream "))
                    expected.clear()
                }
                line.startsWith("expect ") -> expected += line.removePrefix("expect ")
            }
        }
        stream?.let { result += Golden(it, expected.toList()) }
        return result
    }

    /** The form of the expected lines: `<kind> <key> <value>` (the `simplify` function of the tool). */
    private fun simplify(m: ProbeMessage): String = when (m.kind) {
        MessageKind.Count, MessageKind.Bool, MessageKind.Time -> "${m.kind.pyName} ${m.key} ${m.number}"
        MessageKind.Status, MessageKind.Error -> "${m.kind.pyName} ${m.key} ${m.sub} ${m.code}"
        MessageKind.Info, MessageKind.Signature -> "${m.kind.pyName} ${m.key} ${m.field} ${m.body}".trimEnd()
        MessageKind.Unknown -> "unknown ${m.text}"
    }

    @Test
    fun eachGoldenStreamInOneBlock() {
        val golden = load()
        assertEquals(25, golden.size, "The self-test has 25 golden streams.")
        for (case in golden) {
            val got = MessageFeed().feed(case.stream, 1.0).messages.map(::simplify)
            assertEquals(case.expected, got, "stream ${case.stream.decodeToString()}")
        }
    }

    @Test
    fun eachGoldenStreamInBlocksOfOneByte() {
        for (case in load()) {
            val feed = MessageFeed()
            val got = case.stream.indices.flatMap { index -> feed.feed(byteArrayOf(case.stream[index]), 1.0 + index).messages }.map(::simplify)
            assertEquals(case.expected, got, "stream ${case.stream.decodeToString()}, one byte for each block")
        }
    }

    @Test
    fun theCopyHasTheWorkedStream() {
        val first = load().first()
        assertEquals("X12700;Y-3175;Z0;P1;@1a2b;\$0aa3;", first.stream.decodeToString())
        assertTrue(load().any { it.stream.decodeToString() == "X12Y-5;" })
    }
}
