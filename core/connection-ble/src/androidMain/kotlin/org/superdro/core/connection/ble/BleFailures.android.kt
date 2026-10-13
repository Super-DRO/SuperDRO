package org.superdro.core.connection.ble

internal actual fun Throwable.isPermissionDenial(): Boolean = this is SecurityException
