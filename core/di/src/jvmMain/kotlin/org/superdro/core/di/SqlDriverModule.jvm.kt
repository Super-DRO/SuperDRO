package org.superdro.core.di

import org.kodein.di.DI

actual val sqlDriverModule: DI.Module = DI.Module("sqlDriverModule") {
    // Empty on purpose: see the KDoc of the expect declaration.
}
