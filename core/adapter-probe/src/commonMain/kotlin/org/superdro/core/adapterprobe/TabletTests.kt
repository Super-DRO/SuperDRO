package org.superdro.core.adapterprobe

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The local network permission of the tablet (TT1). Android 17 (API 37) and higher has
 * `ACCESS_LOCAL_NETWORK`. The app gives the implementation.
 */
interface LocalNetworkAccess {
    /** True when this Android version has the permission. */
    val exists: Boolean

    /** True when the app has the permission now. */
    fun granted(): Boolean

    /** Shows the Android dialog. Gives true when the user grants the permission. */
    suspend fun request(): Boolean
}

/**
 * One attach of a UVC camera (TT2). The invisible attach activity records it.
 *
 * @property epochMs The wall clock time of the attach.
 * @property bootCount The boot count of the tablet (`Settings.Global.BOOT_COUNT`), or null. A different
 *   value shows an attach after a restart.
 * @property vendorId The USB vendor ID.
 * @property productId The USB product ID.
 * @property name The product name of the camera, or null.
 * @property hasPermission The result of `UsbManager.hasPermission()` at the attach.
 * @property dialogShown The answer of the user: true when Android showed a dialog, or null before the answer.
 */
@Serializable
data class CameraAttach(
    val epochMs: Long,
    val bootCount: Int? = null,
    val vendorId: Int,
    val productId: Int,
    val name: String? = null,
    val hasPermission: Boolean,
    val dialogShown: Boolean? = null,
) {
    /** The record as one line of JSON. */
    fun toJsonLine(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Reads one line of JSON. A line that is not valid gives null. */
        fun fromJsonLine(line: String): CameraAttach? =
            if (line.isBlank()) null else runCatching { json.decodeFromString(serializer(), line) }.getOrNull()
    }
}

/** The summary lines of the tablet tests TT1 and TT2. */
object TabletTests {

    /** The first line of a tablet test: the tablet, the Android version, and the security patch level. */
    fun tabletLine(test: String, tablet: TabletInfo): String =
        "$test: tablet ${tablet.manufacturer} ${tablet.model}, Android ${tablet.androidVersion} (API ${tablet.sdkInt}), security patch " +
            "${tablet.securityPatch.ifEmpty { "not known" }}."

    /** The state of the local network permission as a text. */
    fun permissionState(access: LocalNetworkAccess): String = when {
        !access.exists -> "not in this Android version"
        access.granted() -> "granted"
        else -> "not granted"
    }

    /** The result of one T01 run on the TCP link, as a text. */
    fun t01Result(record: RunRecord): String {
        val first = record.summary.firstOrNull { it.startsWith("First byte") }
        return when {
            record.status == RunStatus.Failed -> record.summary.firstOrNull() ?: "the link did not open"
            first != null -> first.removeSuffix(".")
            else -> "no result (${record.status})"
        }
    }

    /** True when the T01 run got a valid message. */
    fun t01Works(record: RunRecord): Boolean =
        record.status == RunStatus.Done && record.summary.any { it == "Checklist item 1: the adapter sends valid messages with no write from the app: yes." }

    /**
     * The Q42 lines of TT1 from the two T01 runs.
     *
     * @param before The permission state before the request.
     * @param granted True when the app has the permission after the request.
     */
    fun tt1Lines(first: RunRecord, second: RunRecord, exists: Boolean, before: String, granted: Boolean): List<String> {
        val out = ArrayList<String>()
        out += "Q42: T01 before the request (${first.logFile ?: "no log"}): ${t01Result(first)}."
        out += "Q42: T01 after the request (${second.logFile ?: "no log"}): ${t01Result(second)}."
        val worksBefore = t01Works(first)
        val worksAfter = t01Works(second)
        out += when {
            before == "granted" && worksBefore ->
                "Q42: the permission was granted before the test. Remove the grant in the settings and do TT1 again to answer Q42."
            worksBefore && !exists -> "Q42: the link works with no grant; this Android version has no ACCESS_LOCAL_NETWORK."
            worksBefore -> "Q42: the link works with no grant of ACCESS_LOCAL_NETWORK on this Android version."
            worksAfter && granted -> "Q42: the link fails with no grant, and works after the grant. The run-time request of F1.32 is necessary on " +
                "this Android version."
            granted || !exists -> "Q42: the link fails also with the grant. Do T01 on the TCP link from the PC. If T01 works there, report the " +
                "result as a tablet problem."
            else -> "Q42: the link fails with no grant, and the user did not grant ACCESS_LOCAL_NETWORK. Grant it and do TT1 again."
        }
        return out
    }

    /**
     * The steps of TT1 in the app. The app records the Android version, the security patch level,
     * and the permission state itself.
     */
    val TT1_STEPS: List<String> = listOf(
        "Do not give the local network permission before this test. TT1 measures the link before and after the permission request.",
        "Disconnect the PC tool and the other DRO app from the adapter.",
        "On the Link screen, select Wi-Fi TCP. Type the IP address and the TCP port of the adapter.",
        "Push Start. The app records the Android version, the security patch level, and the state of the permission. Then it does T01 " +
            "on the TCP link.",
        "When the app asks for the permission, push Next. Then answer the Android dialog.",
        "The app does T01 again. Read the line Q42 of the summary.",
    )

    /** The steps of TT2 in the app, with the attach filter of the probe app. */
    val TT2_STEPS: List<String> = listOf(
        "Close the camera apps of the tablet.",
        "Connect the UVC camera to the tablet with the USB OTG adapter.",
        "Write down the dialog that Android shows, for example \"Open SuperDRO Probe to handle <camera>?\", and the check box for " +
            "\"always\" or \"use by default\".",
        "Select the check box and push OK.",
        "Disconnect the camera. Wait 5 s. Connect it again.",
        "Tell the app for each attach if Android showed a dialog.",
        "Restart the tablet. Connect the camera again, and tell the app if a dialog showed.",
    )

    /** The summary lines of TT2 (Q49) from the recorded attaches. */
    fun tt2Lines(attaches: List<CameraAttach>, tablet: TabletInfo): List<String> {
        val out = ArrayList<String>()
        out += tabletLine("TT2", tablet)
        out += "TT2: ${attaches.size} attaches of a UVC camera (USB video class 14)."
        attaches.forEachIndexed { index, attach ->
            out += "  attach ${index + 1}: ${ProbeTexts.utcMinute(attach.epochMs)} UTC, vendor 0x${hex4(attach.vendorId)}, product " +
                "0x${hex4(attach.productId)}${attach.name?.let { ", $it" }.orEmpty()}, boot ${attach.bootCount ?: "-"}, hasPermission() " +
                "${attach.hasPermission}, dialog ${when (attach.dialogShown) { true -> "yes"; false -> "no"; null -> "not answered" }}."
        }
        if (attaches.isEmpty()) {
            out += "Q49: no attach recorded. Connect the UVC camera to the tablet."
            return out
        }
        val later = attaches.drop(1)
        val firstBoot = attaches.first().bootCount
        val afterRestart = later.any { it.bootCount != null && firstBoot != null && it.bootCount != firstBoot }
        out += when {
            later.any { it.dialogShown == true || !it.hasPermission } ->
                "Q49: a dialog showed at a later attach, or the app had no permission: the permission is not permanent. F28.7 needs a " +
                    "permission request at each camera start."
            later.isNotEmpty() && later.all { it.dialogShown == false } && afterRestart ->
                "Q49: no dialog after the first \"use by default\", also after a restart: the attach filter gives a permanent permission. " +
                    "F28.7 can use the attach filter."
            later.isNotEmpty() && later.all { it.dialogShown == false } ->
                "Q49: no dialog after the first \"use by default\". Restart the tablet and connect the camera again to complete the test."
            else -> "Q49: not enough data. Connect the camera again, and tell the app if Android showed a dialog."
        }
        return out
    }

    private fun hex4(value: Int): String = value.toString(16).padStart(4, '0')
}
