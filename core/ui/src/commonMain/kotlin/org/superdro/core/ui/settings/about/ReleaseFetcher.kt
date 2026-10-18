package org.superdro.core.ui.settings.about

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.superdro.core.appversion.AppVersion
import org.superdro.core.appversion.ReleaseVersion
import org.superdro.core.model.SuperDroJson

/** The GitHub Releases API URL for the latest release of SuperDRO. */
internal const val LATEST_RELEASE_API_URL: String =
    "https://api.github.com/repos/Super-DRO/SuperDRO/releases/latest"

/** The GitHub Releases page URL for SuperDRO. */
internal const val DEFAULT_RELEASES_URL: String =
    "https://github.com/Super-DRO/SuperDRO/releases"

private const val RELEASES_URL_PREFIX: String = "$DEFAULT_RELEASES_URL/"

/** Gives the GitHub Release page URL for [version]. */
internal fun releaseTagUrl(version: String): String = "${RELEASES_URL_PREFIX}tag/v$version"

/**
 * The version and the web page URL of a release on GitHub.
 *
 * @property version The clean version text (for example `"26.42.2"`).
 * @property htmlUrl The URL of the GitHub Release page.
 */
@Immutable
data class RemoteRelease(
    val version: String,
    val htmlUrl: String,
)

/** Fetches the latest published release of SuperDRO. */
fun interface ReleaseFetcher {
    /** Gives the latest release, or `null` when the check fails. */
    suspend fun fetchLatest(): RemoteRelease?

    companion object {
        /** A fetcher that makes no network call and always gives `null`. */
        val None: ReleaseFetcher = ReleaseFetcher { null }

        /**
         * Gives [GitHubReleaseFetcher] for a release build, or [None] when [versionName] ends with
         * `-dev`.
         */
        fun default(
            json: Json = SuperDroJson,
            versionName: String = AppVersion.NAME,
        ): ReleaseFetcher = if (versionName.endsWith("-dev")) None else GitHubReleaseFetcher(json)
    }
}

/** The JSON payload of `GET /repos/Super-DRO/SuperDRO/releases/latest`. */
@Serializable
internal data class GitHubLatestReleasePayload(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
)

/**
 * Parses [raw] from the GitHub Releases API into a [RemoteRelease].
 *
 * Gives `null` when [raw] is not valid JSON, when the release is a draft or a prerelease, or when
 * the tag is not a valid release version.
 */
internal fun parseLatestReleaseJson(
    raw: String,
    json: Json = SuperDroJson,
): RemoteRelease? {
    val payload = try {
        json.decodeFromString<GitHubLatestReleasePayload>(raw)
    } catch (_: Exception) {
        return null
    }
    if (payload.draft || payload.prerelease) return null
    val version = ReleaseVersion.parseTag(payload.tagName) ?: return null
    val htmlUrl = if (payload.htmlUrl.startsWith(RELEASES_URL_PREFIX)) {
        payload.htmlUrl
    } else {
        releaseTagUrl(version)
    }
    return RemoteRelease(version = version, htmlUrl = htmlUrl)
}

/**
 * Reads the latest release of SuperDRO from the GitHub Releases API.
 */
class GitHubReleaseFetcher(
    private val json: Json = SuperDroJson,
    private val url: String = LATEST_RELEASE_API_URL,
    private val getJson: suspend (String) -> String? = { target -> fetchGitHubReleaseJson(target) },
) : ReleaseFetcher {

    override suspend fun fetchLatest(): RemoteRelease? =
        getJson(url)?.let { raw -> parseLatestReleaseJson(raw, json) }

    companion object {
        const val LATEST_RELEASE_API_URL: String =
            "https://api.github.com/repos/Super-DRO/SuperDRO/releases/latest"
        const val DEFAULT_RELEASES_URL: String =
            "https://github.com/Super-DRO/SuperDRO/releases"
    }
}
