package org.superdro.core.connection.tcp

import org.superdro.core.model.AdapterAddress

/**
 * The rules of the address of a Wi-Fi TCP adapter.
 *
 * SuperDRO accepts an IPv4 address, an IPv6 address, or a host name. The port range is 1 to
 * 65535, and the default port is 8080. The add-device sheet and the profile import use these rules. [TcpTransport] does not try to connect to an address
 * that is not valid: its open fails at once with `LinkFailure.Refused`.
 */
object TcpAddressRules {

    /** The form of a valid host text. */
    enum class HostKind { Ipv4, Ipv6, HostName }

    /** The longest host name (RFC 1123), without a dot at the end. */
    const val MAX_HOST_NAME_LENGTH: Int = 253

    /** The longest label of a host name (RFC 1123). */
    const val MAX_LABEL_LENGTH: Int = 63

    /**
     * Gives the form of [host], or null when [host] is not a valid IPv4 address, IPv6 address, or
     * host name. An IPv6 address can have square brackets and a zone, for example `[fe80::1%wlan0]`.
     */
    fun hostKind(host: String): HostKind? {
        val text = host.unbracketed() ?: return null
        return when {
            text.isEmpty() -> null
            isIpv4(text) -> HostKind.Ipv4
            ':' in text -> if (isIpv6(text)) HostKind.Ipv6 else null
            host != text -> null
            isHostName(text) -> HostKind.HostName
            else -> null
        }
    }

    /** True when [host] is an IPv4 address, an IPv6 address, or a host name. */
    fun isValidHost(host: String): Boolean = hostKind(host) != null

    /** True when [port] is in the range 1 to 65535. */
    fun isValidPort(port: Int): Boolean = port in TcpLinkDefaults.MIN_PORT..TcpLinkDefaults.MAX_PORT

    /** True when the host and the port of [address] are valid. */
    fun isValid(address: AdapterAddress.Tcp): Boolean = isValidHost(address.host) && isValidPort(address.port)

    /**
     * Reads an address that the user typed. The forms are `<host>`, `<host>:<port>`, an IPv6
     * address with no port (`fe80::1`), and an IPv6 address in brackets with a port
     * (`[fe80::1]:8080`). A text with no port gets the port 8080. The host of the result has no
     * brackets and no spaces at the start or the end. Gives null for a text that is not valid.
     *
     * An IPv6 address with a port must have the brackets: `fe80::1:8080` is the IPv6 address
     * `fe80::1:8080` with the port 8080.
     */
    fun parse(text: String): AdapterAddress.Tcp? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val host: String
        val portText: String?
        if (trimmed.startsWith('[')) {
            val end = trimmed.indexOf(']')
            if (end < 0) return null
            host = trimmed.substring(1, end)
            val rest = trimmed.substring(end + 1)
            portText = when {
                rest.isEmpty() -> null
                rest.startsWith(':') -> rest.substring(1)
                else -> return null
            }
            if (!isIpv6(host)) return null
        } else {
            when (trimmed.count { character -> character == ':' }) {
                0 -> {
                    host = trimmed
                    portText = null
                }
                1 -> {
                    host = trimmed.substringBefore(':')
                    portText = trimmed.substringAfter(':')
                }
                else -> {
                    host = trimmed
                    portText = null
                }
            }
        }
        if (!isValidHost(host)) return null
        val port = if (portText == null) TcpLinkDefaults.PORT else parsePort(portText) ?: return null
        return AdapterAddress.Tcp(host = host, port = port)
    }

    /**
     * The device ID of the link: `"<host>:<port>"`. The port is after the last `:`
     * of the text.
     */
    fun deviceId(address: AdapterAddress.Tcp): String = "${address.host}:${address.port}"

    private fun parsePort(text: String): Int? {
        if (text.isEmpty() || text.length > 5 || text.any { character -> character !in '0'..'9' }) return null
        return text.toInt().takeIf(::isValidPort)
    }

    /** Removes one pair of square brackets. Gives null for a bracket with no partner. */
    private fun String.unbracketed(): String? = when {
        startsWith('[') && endsWith(']') && length >= 2 -> substring(1, length - 1)
        startsWith('[') || endsWith(']') -> null
        else -> this
    }

    private fun isIpv4(text: String): Boolean {
        val parts = text.split('.')
        return parts.size == 4 && parts.all { part ->
            part.length in 1..3 && part.all { character -> character in '0'..'9' } && part.toInt() <= 255
        }
    }

    private fun isIpv6(text: String): Boolean {
        val zoneStart = text.indexOf('%')
        val address = if (zoneStart >= 0) text.substring(0, zoneStart) else text
        if (zoneStart >= 0) {
            val zone = text.substring(zoneStart + 1)
            if (zone.isEmpty() || zone.any { character -> !character.isZoneCharacter() }) return false
        }
        if (':' !in address) return false
        val compression = address.indexOf("::")
        if (compression >= 0 && address.indexOf("::", compression + 1) >= 0) return false
        val groups = if (compression >= 0) {
            val head = address.substring(0, compression)
            val tail = address.substring(compression + 2)
            head.groups() + tail.groups()
        } else {
            address.split(':')
        }
        val last = groups.lastOrNull()
        val endsInIpv4 = last != null && '.' in last
        if (endsInIpv4 && !isIpv4(last)) return false
        val hexGroups = if (endsInIpv4) groups.dropLast(1) else groups
        if (hexGroups.any { group -> group.length !in 1..4 || group.any { character -> !character.isHexDigit() } }) {
            return false
        }
        val count = hexGroups.size + if (endsInIpv4) 2 else 0
        return if (compression >= 0) count <= 7 else count == 8
    }

    private fun String.groups(): List<String> = if (isEmpty()) emptyList() else split(':')

    private fun isHostName(text: String): Boolean {
        val name = text.removeSuffix(".")
        if (name.isEmpty() || name.length > MAX_HOST_NAME_LENGTH) return false
        val labels = name.split('.')
        val labelsValid = labels.all { label ->
            label.length in 1..MAX_LABEL_LENGTH &&
                label.first() != '-' &&
                label.last() != '-' &&
                label.all { character -> character.isLetterOrDigitAscii() || character == '-' }
        }
        // A name whose last label has only digits is a bad IPv4 address, for example 192.168.1.300.
        return labelsValid && !labels.last().all { character -> character in '0'..'9' }
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun Char.isLetterOrDigitAscii(): Boolean = this in '0'..'9' || this in 'a'..'z' || this in 'A'..'Z'

    private fun Char.isZoneCharacter(): Boolean = isLetterOrDigitAscii() || this == '.' || this == '_' || this == '-'
}
