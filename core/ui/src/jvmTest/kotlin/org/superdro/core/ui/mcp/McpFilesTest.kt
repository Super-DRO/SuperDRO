package org.superdro.core.ui.mcp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.superdro.core.mcp.McpLimits

/** The file store of the MCP server on the staging files. */
@OptIn(ExperimentalCoroutinesApi::class)
class McpFilesTest {

    @Test
    fun anUploadGetsAHexIdTheSizeAndTheSha256() = runTest {
        val harness = McpHarness(this)
        val receipt = harness.files.putUpload("text/plain", "abc".encodeToByteArray())
        assertEquals(32, receipt.fileId.length)
        assertTrue(receipt.fileId.all { char -> char in '0'..'9' || char in 'a'..'f' }, receipt.fileId)
        assertEquals(3L, receipt.bytes)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", receipt.sha256Hex)
        val stored = assertNotNull(harness.files.upload(receipt.fileId))
        assertEquals("text/plain", stored.contentType)
        assertContentEquals("abc".encodeToByteArray(), stored.bytes)
        assertNull(harness.files.download(receipt.fileId), "An upload is not a download.")
        harness.close()
    }

    @Test
    fun aDownloadKeepsItsBytesAndABadIdGivesNull() = runTest {
        val harness = McpHarness(this)
        val id = harness.files.putDownload("tools.csv", "text/csv", "id,name\n".encodeToByteArray())
        val stored = assertNotNull(harness.files.download(id))
        assertEquals("text/csv", stored.contentType)
        assertEquals("id,name\n", stored.bytes.decodeToString())
        assertNull(harness.files.download("../secret"))
        assertNull(harness.files.download("unknown"))
        harness.close()
    }

    @Test
    fun theExpiryRemovesTheFilesAfterTheTimeToLive() = runTest {
        val harness = McpHarness(this)
        val receipt = harness.files.putUpload("application/dxf", ByteArray(10))
        harness.files.startExpiry(backgroundScope)
        runCurrent()
        assertNotNull(harness.files.upload(receipt.fileId))
        harness.store.wallClock.current += McpLimits.FILE_TTL_MS.milliseconds
        advanceTimeBy(McpFiles.EXPIRY_PERIOD_MS + 1)
        runCurrent()
        assertNull(harness.files.upload(receipt.fileId), "The upload expired.")
        harness.close()
    }
}
