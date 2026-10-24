package org.superdro.core.ui.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.superdro.core.mcp.GateDecision
import org.superdro.core.mcp.McpLimits
import org.superdro.core.mcp.WriteGate
import org.superdro.core.mcp.WriteRequest

/**
 * The confirmation on the tablet: the [WriteGate] of the app. A write tool calls [confirm]. The
 * request waits in [pending] until the operator taps Allow ([allow]) or Refuse ([refuse]), or
 * until [timeoutMs] passes ([GateDecision.Timeout]).
 *
 * One request shows at a time. The next request waits for the answer of the first one.
 * [waiting] gives the number of requests that wait behind the shown request, so the dialog can
 * show the count.
 */
class McpGate(private val timeoutMs: Long = McpLimits.CONFIRM_TIMEOUT_MS) : WriteGate {

    private val lock = Mutex()
    private var answer: CompletableDeferred<GateDecision>? = null

    private val _pending = MutableStateFlow<WriteRequest?>(null)

    /** The request that the dialog shows, or null. */
    val pending: StateFlow<WriteRequest?> = _pending.asStateFlow()

    private val _waiting = MutableStateFlow(0)

    /** The number of requests that wait behind the shown request. */
    val waiting: StateFlow<Int> = _waiting.asStateFlow()

    override suspend fun confirm(request: WriteRequest): GateDecision {
        _waiting.update { count -> count + 1 }
        return lock.withLock {
            _waiting.update { count -> count - 1 }
            val deferred = CompletableDeferred<GateDecision>()
            answer = deferred
            _pending.value = request
            try {
                withTimeoutOrNull(timeoutMs) { deferred.await() } ?: GateDecision.Timeout
            } finally {
                answer = null
                _pending.value = null
            }
        }
    }

    /** The operator allowed the shown request. */
    fun allow() {
        answer?.complete(GateDecision.Allowed)
    }

    /** The operator refused the shown request. */
    fun refuse() {
        answer?.complete(GateDecision.Refused)
    }
}
