package org.superdro.core.mcp

/**
 * A write that needs the operator.
 *
 * @property summary One line, for example "Zero X on Mill (Workspace 1)".
 * @property details The details, one line each: the counts, the names, the client host.
 */
data class WriteRequest(
    val toolTitle: String,
    val summary: String,
    val details: List<String>,
    val client: McpClient,
)

/** The answer of the operator to a [WriteRequest]. */
enum class GateDecision { Allowed, Refused, Timeout }

/** The gate between a write tool and the stored data: the confirmation on the tablet. */
interface WriteGate {
    /** Waits for the operator up to [McpLimits.CONFIRM_TIMEOUT_MS]. */
    suspend fun confirm(request: WriteRequest): GateDecision
}

/** The gate of a server with the confirmation Off, and the gate of the tests. */
object OpenWriteGate : WriteGate {
    override suspend fun confirm(request: WriteRequest): GateDecision = GateDecision.Allowed
}

/** The result texts of a refused or unanswered write. */
object GateTexts {
    /** The tool error of [GateDecision.Refused]. */
    const val REFUSED: String = "The operator refused the call"

    /** The tool error of [GateDecision.Timeout]. */
    const val TIMEOUT: String = "No answer on the tablet"
}

/** Gives the tool error of a decision that is not [GateDecision.Allowed], or null for an allowed write. */
fun GateDecision.toolError(): ToolResult.Error? = when (this) {
    GateDecision.Allowed -> null
    GateDecision.Refused -> ToolResult.Error(GateTexts.REFUSED)
    GateDecision.Timeout -> ToolResult.Error(GateTexts.TIMEOUT)
}
