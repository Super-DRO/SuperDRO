package org.superdro.core.adapterprobe

/**
 * The report of the probe app. The report is Markdown text. It has one part for the tablet and one
 * part for each adapter:
 *
 * - The adapter data: the model, the firmware version, the build, the date, the MAC, the MAC + 2,
 *   the Bluetooth address, and the six input modes. The MAC + 2 check compares the Bluetooth
 *   address of the link, else the address of the T02 options, else the paired device. This
 *   is the order of the line Q51 of T02.
 * - The test runs (table 8.1) and the summary of each run.
 * - One row for each question of table 8.2: the answer (the summary lines of the evidence tests),
 *   the evidence, a decision for SuperDRO where the result is clear, and the sections to update.
 *   A question with no run gives the reason why it stays open.
 */
object ProbeReport {

    /**
     * One row of table 8.2.
     *
     * @property id The question ID of the row, for example `Q13 (15)`.
     * @property question The question ID that the summary lines start with, for example `Q13`.
     * @property tests The evidence tests.
     * @property sections The sections to update.
     * @property select Selects the lines of the answer from the summary of one evidence test.
     */
    class Question(
        val id: String,
        val question: String,
        val tests: List<ProbeTestId>,
        val sections: String,
        val select: (ProbeTestId, String) -> Boolean = { _, line -> line.startsWith("$question:") },
    )

    /** The rows of table 8.2, in the report order. */
    val QUESTIONS: List<Question> = listOf(
        Question("Q4", "Q4", listOf(ProbeTestId.T10), "11.2, DC15, F20.9"),
        Question("Q13 (`15`)", "Q13", listOf(ProbeTestId.T11), "11.2, 5.12") { _, line -> line.startsWith("Q13:") || line.startsWith("  End:") },
        Question("Q13 (`1000`)", "Q13", listOf(ProbeTestId.T12), "11.2, 5.12, 5.17.3") { _, line ->
            (line.startsWith("Q13:") && !line.startsWith("Q13: if the stream")) || line.startsWith("First valid message after")
        },
        Question("Q13 (`,1`)", "Q13", listOf(ProbeTestId.T16), "11.2") { _, line -> line.startsWith("#get_sig:") },
        Question("Q14", "Q14", listOf(ProbeTestId.T05, ProbeTestId.T01), "11.2, 5.16") { test, line ->
            line.startsWith("Q14:") || (test == ProbeTestId.T01 && line.startsWith("Checklist item 1:"))
        },
        Question("Q15 (`@`)", "Q15", listOf(ProbeTestId.T03, ProbeTestId.T04), "11.2, 5.9") { _, line -> line.startsWith("Q15: '@'") },
        Question("Q15 (`uptime`)", "Q15", listOf(ProbeTestId.T07), "11.2, 5.11") { _, line -> line.startsWith("Q15:") || line.startsWith("The uptime value") },
        Question("Q15 (`T`)", "Q15", listOf(ProbeTestId.T08), "11.2, readout") { _, line ->
            line.startsWith("Q15:") || line.startsWith("T:") || line.endsWith("<- agrees (5 %)")
        },
        Question("Q16", "Q16", listOf(ProbeTestId.T03, ProbeTestId.T04), "11.2, 4.2.1"),
        Question("Q17", "Q17", listOf(ProbeTestId.T09), "11.2, 4.10"),
        Question("Q18", "Q18", listOf(ProbeTestId.T03, ProbeTestId.T09), "11.2, 5.6"),
        Question("Q19", "Q19", listOf(ProbeTestId.T01, ProbeTestId.T02, ProbeTestId.T03), "11.2, 5.20"),
        Question("Q20", "Q20", listOf(ProbeTestId.T06), "11.2, 5.19"),
        Question("Q37", "Q37", listOf(ProbeTestId.T03, ProbeTestId.T04, ProbeTestId.T09), "11.2, 5.9, DC22") { _, line ->
            line.startsWith("Q37:") && !line.startsWith("    edge")
        },
        Question("Q38", "Q38", listOf(ProbeTestId.T01, ProbeTestId.T02), "11.2, 5.2.5"),
        Question("Q39", "Q39", listOf(ProbeTestId.T09), "11.2, F10.14"),
        Question("Q40", "Q40", listOf(ProbeTestId.T04), "11.2, F23.7") { _, line -> line.startsWith("    Pair ") || line.startsWith("Q40:") },
        Question("Q41", "Q41", listOf(ProbeTestId.T12, ProbeTestId.T13), "11.2, F20.18, F26.1") { test, line ->
            (line.startsWith("Q41:") && !line.startsWith("Q41:  key")) ||
                (test == ProbeTestId.T12 && (line.endsWith("  CHANGED") || line.endsWith("  same")))
        },
        Question("Q42", "Q42", listOf(ProbeTestId.TT1), "11.2, F1.32"),
        Question("Q49", "Q49", listOf(ProbeTestId.TT2), "11.2, F28.7"),
        Question("Q50", "Q50", listOf(ProbeTestId.T14, ProbeTestId.T15), "11.2, DC20, 5.15") { _, line ->
            line.startsWith("Q50:") || line.startsWith("Phase 1")
        },
        Question("Q51", "Q51", listOf(ProbeTestId.T02), "11.2, DC25"),
    )

    /** The limits of the tests: the reason why a question can stay open. */
    val LIMITS: Map<String, String> = mapOf(
        "Q13" to "The tests give evidence for the function of each constant. Only the firmware source or the firmware author can confirm the " +
            "function. For `,1` in `#get_sig`, the test gives only the time and the length of the answer.",
        "Q18" to "The test can show only the messages that the adapter sends. If the adapter never sends `.` or `Q`, the purpose stays open.",
        "Q19" to "The test needs an older (one-way) adapter. Without one, the question stays open.",
        "Q37" to "The stream shows only indirect evidence of a latch in the firmware (section 6.9).",
        "Q38" to "The test answers the question only for the firmware version of the adapter under test.",
        "Q41" to "The result applies to the firmware version under test. A different firmware can act differently.",
    )

    /** The section number of the decision table of each test. */
    private fun section(test: ProbeTestId): String = when (test) {
        ProbeTestId.TT1 -> "7.1"
        ProbeTestId.TT2 -> "7.2"
        else -> "6.${test.ordinal + 1}"
    }

    /**
     * Gives the decision for SuperDRO of a question from its answer lines, where the decision tables
     * give a clear decision. Else it gives the section of the decision table.
     */
    fun decision(question: Question, lines: List<String>): String {
        val text = lines.joinToString("\n")
        val table = "See the decision table of the plan, section ${question.tests.joinToString(", ") { section(it) }}."
        if (lines.isEmpty()) return table
        return when (question.question) {
            "Q4" -> when {
                "ACCEPTED" in text && "ACKNOWLEDGED" in text && "NOT " !in text ->
                    "The adapter accepts the configuration commands without #get_sig. DC15 is correct. F20.9 stays \"Not planned\"."
                "NOT accepted" in text || "NOT acknowledged" in text ->
                    "Do the follow-up of T10. If it works, give the result to the user for the approval of F20.9 (Q4)."
                else -> table
            }
            "Q14" -> when {
                "does not need the BEL byte" in text ->
                    "The BEL byte is not necessary on this link. SuperDRO keeps the BEL rule, because it does no harm."
                "needs the BEL byte" in text ->
                    "The BEL byte is necessary on this link. The BEL period of 500 ms must stay well below the time to the stop."
                else -> table
            }
            "Q16" -> when {
                "also when the count does not change" in text ->
                    "Periodic readouts with no change: the no-data watchdog works at rest. The repeat rule of the input stage stays."
                else -> table
            }
            "Q17" -> when {
                "again while the contact stays closed" in text -> "The probe watchdog (Q17 default) is correct."
                "only at the edge" in text -> "The probe watchdog clears the state while the contact stays closed. Report it. The watchdog rule needs a change."
                else -> table
            }
            "Q20" -> when {
                "It ignores them" in text ->
                    "The adapter ignores the quote bytes. SuperDRO sends the protocol form with the quote bytes, for compatibility with other firmware."
                "have an effect" in text -> "The quote bytes have an effect. SuperDRO must send the protocol form exactly. Report the result."
                else -> table
            }
            "Q37" -> when {
                lines.any { it == "Q37: '@' time stamps: none." } ->
                    "No `@` messages: the probe latch and the error map wizard use the arrival time (Q37). Their accuracy is less."
                else -> table
            }
            "Q38" -> when {
                "accepted the link and sent valid messages" in text ->
                    "Q38 is answered for this firmware. The TCP link of SuperDRO (F1.23) needs no change."
                else -> table
            }
            "Q40" -> when {
                "both in the same group in 100.0 %" in text -> "The error map wizard can pair samples with equal time stamps (F23.7)."
                else -> table
            }
            "Q41" -> when {
                "DIFFERENT" in text || "all counts are zero" in text ->
                    "The machine datums (F26.1) and the error maps (F23.3) need a new reference. Keep the warning of F20.18."
                "the counts are the same" in text -> "A reconnect keeps the machine datums. No action is necessary for a reconnect."
                else -> table
            }
            "Q42" -> lines.lastOrNull { it.startsWith("Q42: the link") }?.removePrefix("Q42: ") ?: table
            "Q49" -> lines.lastOrNull { it.startsWith("Q49:") }?.removePrefix("Q49: ") ?: table
            "Q51" -> when {
                "NOT the same" in text -> "Report the difference. SuperDRO then uses the UUID as the identity on USB and TCP for this model."
                ": the same." in text -> "The identity rule of DC25 (MAC + 2 with the carry) is correct for this model."
                else -> table
            }
            else -> table
        }
    }

    /** The adapter identity of a run: the MAC + 2, else the Bluetooth address of the link, else a learned identity, else the link. */
    fun identity(record: RunRecord, learned: Map<String, String>): String {
        record.adapter.mac?.let(ProbeTexts::macPlusTwo)?.let { return it.first }
        if (record.linkKind == "bt") return record.linkKey.removePrefix("bt:")
        return learned[record.linkKey] ?: "link ${record.linkKey}"
    }

    private fun learnedIdentities(records: List<RunRecord>): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        records.forEach { record ->
            record.adapter.mac?.let(ProbeTexts::macPlusTwo)?.let { map[record.linkKey] = it.first }
        }
        return map
    }

    /**
     * Gives the report text.
     *
     * @param records The runs, in their order.
     * @param tablet The tablet data.
     * @param paired The paired Bluetooth devices, for the MAC + 2 check.
     * @param nowEpochMs The time of the report.
     */
    fun build(records: List<RunRecord>, tablet: TabletInfo, paired: List<PairedAdapter>, nowEpochMs: Long): String {
        val out = StringBuilder()
        fun line(text: String = "") = out.append(text).append('\n')
        line("# SuperDRO adapter probe report")
        line()
        line("Report date: ${ProbeTexts.utcMinute(nowEpochMs)} UTC. App: ${tablet.appVersion.ifEmpty { "-" }}.")
        line()
        line("## Tablet")
        line()
        line("| Item | Value |")
        line("|---|---|")
        line("| Tablet model | ${cell("${tablet.manufacturer} ${tablet.model}".trim())} |")
        line("| Android version | ${cell(tablet.androidVersion)} (API ${tablet.sdkInt}) |")
        line("| Security patch level | ${cell(tablet.securityPatch)} |")
        line()
        val tabletRuns = records.filter { it.testId?.onTablet == true }
        questionTable(out, QUESTIONS.filter { q -> q.tests.all { it.onTablet } }, tabletRuns)
        val adapterRuns = records.filter { it.testId != null && it.testId?.onTablet != true }
        val learned = learnedIdentities(adapterRuns)
        val groups = adapterRuns.groupBy { identity(it, learned) }
        if (groups.isEmpty()) {
            line("No adapter test ran.")
            line()
        }
        for ((identity, runs) in groups) {
            adapterPart(out, identity, runs, paired)
        }
        return out.toString()
    }

    private fun adapterPart(out: StringBuilder, identity: String, runs: List<RunRecord>, paired: List<PairedAdapter>) {
        fun line(text: String = "") = out.append(text).append('\n')
        val snapshot = runs.map { it.adapter }.fold(AdapterSnapshot()) { a, b ->
            AdapterSnapshot(
                model = b.model ?: a.model, name = b.name ?: a.name, version = b.version ?: a.version, build = b.build ?: a.build,
                date = b.date ?: a.date, mac = b.mac ?: a.mac, uuid = b.uuid ?: a.uuid, features = b.features ?: a.features,
                uptime = b.uptime ?: a.uptime, modes = a.modes + b.modes,
            )
        }
        val forms = snapshot.mac?.let(ProbeTexts::macPlusTwo)
        val btLink = runs.firstOrNull { it.linkKind == "bt" }?.linkKey?.removePrefix("bt:")
        val pairedMatch = forms?.let { (carry, last) ->
            paired.firstOrNull { ProbeTexts.normMac(it.address) == ProbeTexts.normMac(carry) }
                ?: paired.firstOrNull { ProbeTexts.normMac(it.address) == ProbeTexts.normMac(last) }
        }
        val typed = runs.lastOrNull { !it.btAddress.isNullOrBlank() }?.btAddress
        val bluetooth = btLink ?: typed ?: pairedMatch?.address
        val source = when {
            btLink == null && typed != null -> " (the Bluetooth address of the T02 options)"
            pairedMatch != null && ProbeTexts.normMac(pairedMatch.address) == ProbeTexts.normMac(bluetooth) -> " (paired device \"${pairedMatch.name}\")"
            else -> ""
        }
        line("## Adapter $identity")
        line()
        line("| Item | Value |")
        line("|---|---|")
        line("| Model | ${cell(snapshot.model)} |")
        line("| Bluetooth name | ${cell(snapshot.name)} |")
        line("| Firmware version | ${cell(snapshot.version)} |")
        line("| Firmware build | ${cell(snapshot.build)} |")
        line("| Firmware date | ${cell(snapshot.date?.let { "${ProbeTexts.utcMinute(it * 1000L)} UTC (0x${it.toString(16)})" })} |")
        line("| MAC | ${cell(snapshot.mac)} |")
        line("| MAC + 2 (with the carry) | ${cell(forms?.first)} |")
        line("| MAC + 2 (last byte only) | ${cell(forms?.second)} |")
        line("| Bluetooth address | ${cell(bluetooth?.let { address -> address.uppercase() + source })} |")
        val check = when {
            forms == null -> "no MAC (run T02)"
            bluetooth == null -> "no Bluetooth address: no Bluetooth link and no paired device with the address MAC + 2"
            ProbeTexts.normMac(bluetooth) == ProbeTexts.normMac(forms.first) -> "MAC + 2 = the Bluetooth address"
            else -> "MAC + 2 is NOT the Bluetooth address"
        }
        line("| MAC + 2 check (Q51, AP11) | $check |")
        line("| UUID | ${cell(snapshot.uuid)} |")
        line("| Features | ${cell(snapshot.features?.toString())} |")
        val modes = ProbeTexts.AXES.map { axis -> "$axis=${snapshot.modes[axis.toString()] ?: "-"}" }.joinToString(" ")
        line("| Input modes | $modes |")
        line()
        line("### Test runs (table 8.1)")
        line()
        line("| Test | Link | Date (UTC) | Log file (`.log`) | Result (short) | Done |")
        line("|---|---|---|---|---|---|")
        runs.forEach { run ->
            val short = run.shortResult.orEmpty()
            line(
                "| ${run.test} | ${run.linkKind} | ${ProbeTexts.utcMinute(run.startedAtEpochMs)} | ${cell(run.logFile)} | ${cell(short.take(160))} | " +
                    "${run.status} |",
            )
        }
        line()
        questionTable(out, QUESTIONS.filter { q -> q.tests.none { it.onTablet } }, runs)
        line("### Summaries")
        line()
        runs.forEach { run ->
            line("#### ${run.test} on ${run.linkKind}, ${ProbeTexts.utcMinute(run.startedAtEpochMs)} UTC, ${run.logFile ?: "no log"}")
            line()
            line("```text")
            run.summary.forEach { text -> line(text) }
            line("```")
            line()
        }
    }

    private fun questionTable(out: StringBuilder, questions: List<Question>, runs: List<RunRecord>) {
        fun line(text: String = "") = out.append(text).append('\n')
        line("### Answers to the questions (table 8.2)")
        line()
        line("| ID | Answer | Evidence (test, log file, summary line) | Decision for SuperDRO | Section to update |")
        line("|---|---|---|---|---|")
        for (question in questions) {
            val evidence = ArrayList<String>()
            val answer = ArrayList<String>()
            for (test in question.tests) {
                val done = runs.lastOrNull { it.test == test.name && it.status == RunStatus.Done } ?: continue
                val selected = done.summary.filter { text -> question.select(test, text) }
                if (selected.isNotEmpty()) {
                    answer += selected.map { it.trim() }
                    evidence += "${test.name}, ${done.logFile ?: "no log"}, ${selected.first().trim().take(80)}"
                }
            }
            val answerText = if (answer.isEmpty()) {
                val limit = LIMITS[question.question]?.let { " Limit (plan, 1.2): $it" }.orEmpty()
                "Open: no result of ${question.tests.joinToString(", ") { it.name }}.$limit"
            } else {
                answer.joinToString("<br>")
            }
            line(
                "| ${question.id} | ${cell(answerText, keepBreaks = true)} | ${cell(evidence.joinToString("; ").ifEmpty { question.tests.joinToString(", ") { it.name } })} | " +
                    "${cell(decision(question, answer))} | ${question.sections} |",
            )
        }
        line()
    }

    private fun cell(text: String?, keepBreaks: Boolean = false): String {
        val value = text?.takeIf { it.isNotBlank() } ?: "-"
        val escaped = value.replace("|", "\\|")
        return if (keepBreaks) escaped else escaped.replace("\n", " ")
    }
}
