package org.superdro.core.camera

/**
 * The identity of one attached UVC camera.
 * [key] is the key of the mode memory (`CameraModeResult`) and of the camera selection of a
 * profile. Two cameras of the same model have the same key. [deviceName] tells them apart while
 * they are attached.
 *
 * @property vendorId The USB vendor ID (16 bits).
 * @property productId The USB product ID (16 bits).
 * @property deviceName The USB device name of the platform, for example `/dev/bus/usb/001/004`.
 *   The text is empty when the platform gives no name.
 */
data class CameraIdentity(
    val vendorId: Int,
    val productId: Int,
    val manufacturerName: String? = null,
    val productName: String? = null,
    val deviceName: String = "",
) {
    /** The key `%04x:%04x` of the vendor ID and the product ID, for example `046d:0825`. */
    val key: String get() = keyOf(vendorId, productId)

    /**
     * The name for the user: the manufacturer and the product, with one space between them. When
     * both names are empty, the label is the [key].
     */
    val label: String
        get() = listOfNotNull(manufacturerName?.trim(), productName?.trim())
            .filter { name -> name.isNotEmpty() }
            .joinToString(" ")
            .ifEmpty { key }

    /** True when [label] is the [key]. Then a list line shows only the key, one time. */
    val labelIsKey: Boolean get() = label == key

    companion object {
        /** The time limit of one mode test attempt. */
        const val MODE_TEST_TIMEOUT_MS: Long = 3500L

        /** The number of attempts of one mode test. */
        const val MODE_TEST_ATTEMPTS: Int = 2

        /** Gives the key `%04x:%04x`. Each ID uses its low 16 bits. */
        fun keyOf(vendorId: Int, productId: Int): String = "${hex4(vendorId)}:${hex4(productId)}"

        private fun hex4(value: Int): String = (value and 0xFFFF).toString(16).padStart(4, '0')
    }
}

/**
 * The USB class values of a UVC camera.
 *
 * The attach filter uses the class 14 and the subclass 1. The camera list keeps each
 * device that has one or more interfaces of the class 14.
 */
object UsbVideoClass {
    /** The USB class "video" (`0x0E`). */
    const val CLASS_VIDEO: Int = 14

    /** The USB subclass "video control". */
    const val SUBCLASS_VIDEO_CONTROL: Int = 1

    /** True when one or more of [interfaceClasses] is the class 14. */
    fun isCamera(interfaceClasses: Iterable<Int>): Boolean = interfaceClasses.any { value -> value == CLASS_VIDEO }
}

/** The rules of the camera list. */
object CameraList {

    /**
     * Gives the camera list in a fixed order: by the device name, then by the key. [lastAttached]
     * is the camera of the last attach event. The list gets it when the device list of the
     * platform does not have it yet.
     */
    fun of(cameras: Collection<CameraIdentity>, lastAttached: CameraIdentity? = null): List<CameraIdentity> {
        val all = if (lastAttached != null && cameras.none { camera -> camera.deviceName == lastAttached.deviceName }) {
            cameras + lastAttached
        } else {
            cameras
        }
        return all.sortedWith(compareBy<CameraIdentity> { camera -> camera.deviceName }.thenBy { camera -> camera.key })
    }

    /** Gives the camera with [deviceName], else the first camera, else null. */
    fun select(cameras: List<CameraIdentity>, deviceName: String?): CameraIdentity? =
        cameras.firstOrNull { camera -> deviceName != null && camera.deviceName == deviceName } ?: cameras.firstOrNull()
}
