package org.superdro.core.connection

import kotlin.jvm.JvmInline

/**
 * The ID of one device: one machine with its adapter link. Each machine
 * profile is one device, so the value is the ID of the machine profile.
 */
@JvmInline
value class DeviceId(val value: String) {
    override fun toString(): String = value
}
