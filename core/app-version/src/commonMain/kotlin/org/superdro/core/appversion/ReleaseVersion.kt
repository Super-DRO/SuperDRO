package org.superdro.core.appversion

/**
 * Parses and compares calendar release versions (`<year>.<week>.<count>`).
 */
object ReleaseVersion {

    private val TAG_PATTERN = Regex("""^v?\d+\.\d+\.\d+$""")

    /**
     * Parses a GitHub release tag.
     *
     * Trims [tag], removes a leading `v`, and returns the version string when [codeOf] accepts it.
     * Returns `null` for each other tag or suffix.
     */
    fun parseTag(tag: String): String? {
        val trimmed = tag.trim()
        if (!TAG_PATTERN.matches(trimmed)) return null
        val version = trimmed.removePrefix("v")
        return version.takeIf { codeOf(it) != null }
    }

    /**
     * Converts `<year>.<week>.<count>` to its integer version code.
     *
     * Returns `(year % 100) * 100000 + week * 1000 + count`, or `null` when [version] is not a
     * valid release version (`year` in `0..99`, `week` in `1..53`, `count` in `1..999`).
     */
    fun codeOf(version: String): Int? {
        val parts = version.split('.')
        if (parts.size != 3) return null
        if (parts.any { part -> part.isEmpty() || !part.all(Char::isDigit) }) return null
        val year = parts[0].toIntOrNull() ?: return null
        val week = parts[1].toIntOrNull() ?: return null
        val count = parts[2].toIntOrNull() ?: return null
        if (year !in 0..99 || week !in 1..53 || count !in 1..999) return null
        return (year % 100) * 100_000 + week * 1_000 + count
    }

    /**
     * Returns `true` when [remoteVersion] has a valid code that is greater than [currentCode].
     */
    fun isNewer(remoteVersion: String, currentCode: Int = AppVersion.CODE): Boolean {
        val remoteCode = codeOf(remoteVersion) ?: return false
        return remoteCode > currentCode
    }
}
