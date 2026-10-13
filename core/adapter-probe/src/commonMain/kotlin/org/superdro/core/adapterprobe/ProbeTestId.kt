package org.superdro.core.adapterprobe

/**
 * The tests of the adapter probe app. The order of the values is the test order. [onTablet] is
 * true for the tests that only the tablet can do.
 *
 * The values agree with the `TESTS` list of `scripts/adapter_probe.py`, so that the two tools write
 * the same mode tags (`<ID>-<toolName>`) and the same first lines.
 *
 * @property title The title of the test.
 * @property toolName The name of the test in the tool, for example `link`. The log file name has it.
 * @property toolQuestions The question text of the tool, for example `Q14, Q19, Q38`.
 * @property purpose The purpose text of the tool (`--list-tests`).
 * @property questions The question IDs of the test.
 * @property startKeepAlive The BEL byte state at the start, or null for the option of the user.
 * @property keepMessages True when the phases keep their messages (T09: the edge analysis).
 * @property needsAxis True when the test needs a channel (T10, T11).
 * @property usesQueue True when the test sends requests through the SuperDRO command queue. The
 *   queue then sends the start queries 2000 ms after the first valid message.
 * @property group The group of the order of the tests.
 */
enum class ProbeTestId(
    val title: String,
    val onTablet: Boolean = false,
    val toolName: String,
    val toolQuestions: String,
    val purpose: String,
    val questions: List<String>,
    val startKeepAlive: Boolean? = null,
    val keepMessages: Boolean = false,
    val needsAxis: Boolean = false,
    val usesQueue: Boolean = false,
    val group: Char,
) {
    T01(
        "Link check", toolName = "link", toolQuestions = "Q14, Q19, Q38",
        purpose = "Link check: open, first byte, first valid message, 5 s with no write, then 10 s with the BEL byte",
        questions = listOf("Q14", "Q19", "Q38"), startKeepAlive = false, group = 'A',
    ),
    T02(
        "Identity and connect sequence", toolName = "identity", toolQuestions = "Q19, Q51 (Q4 setup)",
        purpose = "Connect sequence of 3.32.00: five \"#get:\", six #get_mode, then name, version, build, date",
        questions = listOf("Q51", "Q19", "Q38"), usesQueue = true, group = 'A',
    ),
    T03(
        "Idle stream", toolName = "idle", toolQuestions = "Q15, Q16, Q18, Q19, Q37",
        purpose = "Idle stream (default 30 s): readouts when no axis moves",
        questions = listOf("Q16", "Q15", "Q18", "Q19", "Q37"), group = 'A',
    ),
    T04(
        "Motion stream and readout groups", toolName = "motion", toolQuestions = "Q15, Q16, Q37, Q40",
        purpose = "Motion stream (default 30 s): rate and readout groups (--pair X,W)",
        questions = listOf("Q16", "Q40", "Q15", "Q37"), group = 'A',
    ),
    T05(
        "BEL byte (keep-alive)", toolName = "keepalive", toolQuestions = "Q14",
        purpose = "BEL byte off for --duration (default 60 s): does the adapter stop or close",
        questions = listOf("Q14"), startKeepAlive = false, group = 'A',
    ),
    T06(
        "Quote bytes of #get:", toolName = "quotes", toolQuestions = "Q20",
        purpose = "\"#get:mac;\" with and without the quote bytes",
        questions = listOf("Q20"), usesQueue = true, group = 'A',
    ),
    T07(
        "Unit of uptime", toolName = "uptime", toolQuestions = "Q15",
        purpose = "uptime unit: 7 queries, 10 s apart",
        questions = listOf("Q15"), usesQueue = true, group = 'A',
    ),
    T08(
        "Unit of the T value", toolName = "tach", toolQuestions = "Q15",
        purpose = "T value at a known spindle speed",
        questions = listOf("Q15"), group = 'A',
    ),
    T09(
        "Probe", toolName = "probe", toolQuestions = "Q17, Q18, Q37, Q39",
        purpose = "Probe: P1 repeats, polarity, bounce, messages at the edge",
        questions = listOf("Q17", "Q37", "Q39", "Q18"), keepMessages = true, group = 'A',
    ),
    T10(
        "Configuration commands without #get_sig", toolName = "no-sig", toolQuestions = "Q4",
        purpose = "#set_mode (same mode) and #clear_counter without #get_sig (needs --axis)",
        questions = listOf("Q4"), needsAxis = true, usesQueue = true, group = 'B',
    ),
    T11(
        "Reference mark search and the constant 15", toolName = "ref-axis", toolQuestions = "Q13",
        purpose = "#ref_axis:<axis>,15; time to the end of the search (needs --axis)",
        questions = listOf("Q13"), needsAxis = true, usesQueue = true, group = 'B',
    ),
    T12(
        "Restart and the constant 1000", toolName = "reboot", toolQuestions = "Q13, Q41",
        purpose = "#reboot:1000; restart delay, uptime, counts after the restart",
        questions = listOf("Q13", "Q41"), usesQueue = true, group = 'B',
    ),
    T13(
        "Counts after a reconnect and after a power cycle", toolName = "counter-keep", toolQuestions = "Q41",
        purpose = "Counts after a reconnect and after a power cycle",
        questions = listOf("Q41"), usesQueue = true, group = 'B',
    ),
    T14(
        "Open again after 750 ms, and a second link", toolName = "second-link", toolQuestions = "Q50",
        purpose = "Open again after 750 ms; second link while the first link stays open",
        questions = listOf("Q50"), group = 'C',
    ),
    T15(
        "Link lost with no close", toolName = "hard-drop", toolQuestions = "Q50",
        purpose = "Link lost with no close: time to a new link",
        questions = listOf("Q50"), group = 'C',
    ),
    T16(
        "Times of #get_sig (optional)", toolName = "get-sig", toolQuestions = "Q13",
        purpose = "#get_sig times and answer length (optional)",
        questions = listOf("Q13"), usesQueue = true, group = 'D',
    ),
    TT1(
        "Wi-Fi link and ACCESS_LOCAL_NETWORK", onTablet = true, toolName = "local-network", toolQuestions = "Q42",
        purpose = "T01 on the TCP link before and after the request of ACCESS_LOCAL_NETWORK",
        questions = listOf("Q42"), group = 'E',
    ),
    TT2(
        "Permanent USB permission of a UVC camera", onTablet = true, toolName = "uvc-permission", toolQuestions = "Q49",
        purpose = "Attaches of a UVC camera: hasPermission() and the Android dialog",
        questions = listOf("Q49"), group = 'E',
    ),
    ;

    /** The mode tag of the log: `<ID>-<toolName>`, for example `T03-idle`. */
    val tag: String get() = "$name-$toolName"

    companion object {
        /** Gives the test of a text: the ID (`T03`, `t3`) or the tool name (`idle`), as `find_test` of the tool. */
        fun find(text: String): ProbeTestId? {
            val lower = text.trim().lowercase()
            return entries.firstOrNull { id ->
                lower == id.name.lowercase() || lower == id.toolName || lower == id.name.lowercase().replace("t0", "t")
            }
        }
    }
}
