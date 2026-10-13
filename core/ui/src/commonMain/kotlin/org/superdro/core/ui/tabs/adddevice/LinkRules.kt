package org.superdro.core.ui.tabs.adddevice

import org.superdro.core.model.AdapterProfile
import org.superdro.core.model.TransportKind

/**
 * The address check of a Wi-Fi adapter. SuperDRO accepts an IPv4 address, an IPv6 address,
 * or a host name.
 */
object HostCheck {

    /** The result of the check of a host text. */
    enum class Result { Valid, Blank, NotValid }

    private val hostLabel = Regex("[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?")
    private val digitsAndDots = Regex("[0-9.]+")
    private val hexGroup = Regex("[0-9A-Fa-f]{1,4}")

    /** Checks [text] after a trim. */
    fun check(text: String): Result {
        val host = text.trim()
        return when {
            host.isEmpty() -> Result.Blank
            isValid(host) -> Result.Valid
            else -> Result.NotValid
        }
    }

    /** True when [host] is an IPv4 address, an IPv6 address, or a host name. */
    fun isValid(host: String): Boolean = when {
        ':' in host -> isIpv6(host.removePrefix("[").removeSuffix("]"))
        digitsAndDots.matches(host) -> isIpv4(host)
        else -> isHostName(host)
    }

    /** True for four decimal numbers from 0 to 255 with dots between them. */
    fun isIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { part -> part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) && part.toInt() <= 255 }
    }

    /**
     * True for an IPv6 address: eight groups of 1 to 4 hex digits with colons, or fewer groups with
     * one `::`. The last part can be an IPv4 address. A zone (`%eth0`) is not accepted.
     */
    fun isIpv6(host: String): Boolean {
        if (host.isEmpty() || '%' in host) return false
        val doubleColons = Regex("::").findAll(host).count()
        if (doubleColons > 1) return false
        val parts = if (doubleColons == 1) {
            val (left, right) = host.split("::", limit = 2)
            val leftGroups = if (left.isEmpty()) emptyList() else left.split(':')
            val rightGroups = if (right.isEmpty()) emptyList() else right.split(':')
            if (!groupsValid(leftGroups + rightGroups)) return false
            val count = groupCount(leftGroups + rightGroups)
            return count < 8
        } else {
            host.split(':')
        }
        return groupsValid(parts) && groupCount(parts) == 8
    }

    private fun groupsValid(groups: List<String>): Boolean = groups.withIndex().all { (index, group) ->
        hexGroup.matches(group) || (index == groups.lastIndex && isIpv4(group))
    }

    /** The number of 16-bit groups: an IPv4 part counts as two. */
    private fun groupCount(groups: List<String>): Int = groups.sumOf { group -> if ('.' in group) 2 else 1 }

    /** True for a host name of RFC 1123: labels of 1 to 63 letters, digits, and hyphens, 253 characters or less. */
    fun isHostName(host: String): Boolean {
        val name = host.removeSuffix(".")
        if (name.isEmpty() || name.length > 253) return false
        return name.split('.').all { label -> hostLabel.matches(label) }
    }

    /** Gives the port of [text]: a whole number from 1 to 65535, or null. */
    fun port(text: String): Int? = text.trim().toIntOrNull()?.takeIf { port -> port in 1..65_535 }
}

/**
 * The link types that the add-device sheet shows: Bluetooth, USB, and
 * Wi-Fi, without the types of the app setting `hiddenLinkTypes`. One link type stays shown: when
 * the setting hides all three, the sheet shows all three.
 */
object LinkTypes {

    /** The link types of the add-device sheet, in this order. */
    val SHEET_TYPES: List<TransportKind> = listOf(TransportKind.BluetoothSpp, TransportKind.UsbSerial, TransportKind.Tcp)

    /** Gives the shown link types for the hidden types [hidden]. */
    fun shown(hidden: Set<TransportKind>): List<TransportKind> {
        val shown = SHEET_TYPES.filter { kind -> kind !in hidden }
        return shown.ifEmpty { SHEET_TYPES }
    }
}

/**
 * The alias of a new adapter profile: unique (the case does not matter) and 40
 * characters or less. A second adapter with the same name gets "<name> 2", then "<name> 3".
 */
object AdapterAliases {

    /** Gives a unique alias from [base]. [fallback] replaces a blank [base]. */
    fun unique(base: String, taken: Collection<String>, fallback: String = "Adapter"): String {
        val start = base.trim().ifEmpty { fallback.trim() }.ifEmpty { "Adapter" }
        val lower = taken.mapTo(HashSet()) { alias -> alias.trim().lowercase() }
        val max = AdapterProfile.MAX_ALIAS_LENGTH
        val first = start.take(max).trim()
        if (first.lowercase() !in lower) return first
        var number = 2
        while (true) {
            val suffix = " $number"
            val candidate = start.take(max - suffix.length).trim() + suffix
            if (candidate.lowercase() !in lower) return candidate
            number++
        }
    }
}
