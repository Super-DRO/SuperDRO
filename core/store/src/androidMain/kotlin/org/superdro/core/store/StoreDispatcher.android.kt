package org.superdro.core.store

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual val storeIoDispatcher: CoroutineDispatcher = Dispatchers.IO
