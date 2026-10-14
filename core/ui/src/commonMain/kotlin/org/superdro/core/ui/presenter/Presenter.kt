package org.superdro.core.ui.presenter

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import org.superdro.core.util.Clock
import org.superdro.core.util.MonotonicClock

/**
 * The state holder of one screen, sheet, or dialog. SuperDRO has no
 * androidx `ViewModel` class.
 *
 * A presenter is a plain Kotlin class with a [CoroutineScope] and one `StateFlow` of its UI
 * state. The composable of the screen is stateless: it gets the UI state and calls the event
 * functions of the presenter.
 *
 * The tab back stack entry owns the presenter. When the entry leaves the back stack, the entry
 * calls [close], and the presenter cancels its scope. A configuration change does not close a
 * presenter, because the back stacks and their presenters live in an app-scope holder
 * ([org.superdro.core.ui.navigation.NavigationHolder]).
 */
interface Presenter : AutoCloseable {

    /** Stops the work of the presenter. A second call does nothing. */
    override fun close()
}

/**
 * The context of the presenters: the dispatcher of their scopes, the dispatcher of their blocking
 * file IO, and the clock of their timers. `core:di` binds one value. A test gives a test dispatcher
 * and a clock of the virtual time.
 *
 * @property dispatcher The context of each presenter scope.
 * @property clock A monotonic clock in milliseconds, for example the time of the app start.
 * @property fileDispatcher The context of a blocking read or write of a file. `core:di` gives
 *   `Dispatchers.IO`. The default is [dispatcher], so a test with a test dispatcher keeps the
 *   virtual time.
 */
class PresenterContext(
    val dispatcher: CoroutineContext = Dispatchers.Default,
    val clock: Clock = MonotonicClock(),
    val fileDispatcher: CoroutineContext = dispatcher,
)

/**
 * A [Presenter] with its own [scope]. The scope has a `SupervisorJob`, so a failure of one
 * collector does not stop the other collectors.
 */
abstract class ScopedPresenter(context: PresenterContext) : Presenter {

    /** The clock of the timers of the presenter. */
    protected val clock: Clock = context.clock

    /** The scope of the presenter. [close] cancels it. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + context.dispatcher)

    /** True after [close]. */
    val isClosed: Boolean get() = !scope.isActive

    override fun close() {
        scope.cancel()
    }
}
