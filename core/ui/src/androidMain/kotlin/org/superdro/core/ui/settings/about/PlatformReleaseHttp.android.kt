package org.superdro.core.ui.settings.about

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_RESPONSE_BYTES: Int = 256 * 1024

internal actual suspend fun fetchGitHubReleaseJson(
    url: String,
    timeoutMs: Int,
): String? = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        val opened = URI(url).toURL().openConnection() as HttpURLConnection
        connection = opened
        opened.requestMethod = "GET"
        opened.connectTimeout = timeoutMs
        opened.readTimeout = timeoutMs
        opened.instanceFollowRedirects = true
        opened.setRequestProperty("Accept", "application/vnd.github+json")
        opened.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        opened.setRequestProperty("User-Agent", "SuperDRO")
        if (opened.responseCode == HttpURLConnection.HTTP_OK) {
            opened.inputStream.use { stream -> readUtf8Capped(stream) }
        } else {
            null
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    } finally {
        connection?.disconnect()
    }
}

private fun readUtf8Capped(stream: InputStream): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(4_096)
    var total = 0
    while (total < MAX_RESPONSE_BYTES) {
        val count = stream.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES - total))
        if (count < 0) break
        output.write(buffer, 0, count)
        total += count
    }
    return output.toByteArray().decodeToString()
}
