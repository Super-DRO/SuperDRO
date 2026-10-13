package org.superdro.core.store

import kotlinx.coroutines.CoroutineDispatcher

/**
 * The default dispatcher of the blocking file IO and the database IO of the stores (a platform
 * contract). The JVM and Android use `Dispatchers.IO`. A store takes its dispatcher in the
 * constructor, so that a test can give a different one.
 *
 * All database writes go through one writer: `storeIoDispatcher.limitedParallelism(1)`.
 */
internal expect val storeIoDispatcher: CoroutineDispatcher
