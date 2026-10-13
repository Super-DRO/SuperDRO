package org.superdro.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import org.kodein.di.DI
import org.kodein.di.direct
import org.kodein.di.instance

/**
 * The Kodein graph of the composition. The app gives it with [ProvideDI].
 *
 * SuperDRO does not use `kodein-di-framework-compose`: its Android variant brings
 * `androidx.appcompat`, `androidx.fragment`, and the ViewModel libraries. These few functions need
 * only `kodein-di`.
 */
val LocalDI = staticCompositionLocalOf<DI> { error("No DI: put the content in ProvideDI.") }

/** Gives [di] to [content]. */
@Composable
fun ProvideDI(di: DI, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDI provides di, content = content)
}

/** Gives the instance of [T] from the graph. The value stays the same while the graph stays the same. */
@Composable
inline fun <reified T : Any> rememberInstance(): T {
    val di = LocalDI.current
    return remember(di) { di.direct.instance<T>() }
}

/**
 * Gives the instance of [T] from a Kodein factory with the argument [arg], for example a
 * presenter for one device ID.
 */
@Composable
inline fun <reified A : Any, reified T : Any> rememberFactory(arg: A): T {
    val di = LocalDI.current
    return remember(di, arg) { di.direct.instance<A, T>(arg = arg) }
}
