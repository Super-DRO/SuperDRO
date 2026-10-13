package org.superdro.core.ui.tabs.adddevice

/** Android opens the RFCOMM socket of the adapter itself: no hint. */
actual fun platformRfcommBindHint(): Boolean = false
