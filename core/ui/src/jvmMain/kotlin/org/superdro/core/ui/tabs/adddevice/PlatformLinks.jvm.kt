package org.superdro.core.ui.tabs.adddevice

/** The desktop app shows the hint on Linux, where `rfcomm bind` gives the serial port of the adapter. */
actual fun platformRfcommBindHint(): Boolean = System.getProperty("os.name").orEmpty().startsWith("Linux", ignoreCase = true)
