package org.superdro.core.ui.settings.about

/**
 * Fetches the text of [url] with an HTTPS GET request for the GitHub Releases API.
 * Gives null when the network is offline, the status is not 200, or the call times out.
 */
internal expect suspend fun fetchGitHubReleaseJson(
    url: String,
    timeoutMs: Int = 5_000,
): String?
