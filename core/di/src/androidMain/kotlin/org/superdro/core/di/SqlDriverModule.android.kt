package org.superdro.core.di

import org.kodein.di.DI

actual val sqlDriverModule: DI.Module = DI.Module("sqlDriverModule") {
    // Empty on purpose: platformModule of platform:android:di binds the SqlDriver.
}
