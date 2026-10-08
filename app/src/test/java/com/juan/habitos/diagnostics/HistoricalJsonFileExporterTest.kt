package com.juan.habitos.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate

class HistoricalJsonFileExporterTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `prepared file contains exactly the generated json without truncation`() {
        val json = """{"message":"diagnóstico completo","events":[1,2,3]}"""

        val prepared = HistoricalJsonFileExporter.prepare(
            cacheDirectory = temporaryFolder.root,
            json = json,
            date = LocalDate.of(2026, 10, 7),
        )

        assertEquals(json, prepared.file.readText(Charsets.UTF_8))
        assertEquals(json.toByteArray(Charsets.UTF_8).toList(), prepared.file.readBytes().toList())
    }

    @Test fun `share file uses deterministic json name and application json mime`() {
        val prepared = HistoricalJsonFileExporter.prepare(
            cacheDirectory = temporaryFolder.root,
            json = "{}",
            date = LocalDate.of(2026, 10, 7),
        )

        assertEquals("modera-historical-diagnostic-2026-10-07.json", prepared.file.name)
        assertTrue(prepared.file.name.endsWith(".json"))
        assertEquals("application/json", prepared.mimeType)
        assertEquals(".diagnostics.fileprovider", HistoricalJsonFileExporter.FILE_PROVIDER_AUTHORITY_SUFFIX)
    }

    @Test fun `large json is rejected for clipboard before invoking android clipboard`() {
        val largeJson = "x".repeat(HistoricalJsonFileExporter.MAX_CLIPBOARD_BYTES + 1)
        var clipboardInvoked = false

        assertFalse(HistoricalJsonFileExporter.canCopyToClipboard(largeJson))
        assertFalse(HistoricalJsonFileExporter.copyToClipboardIfSafe(largeJson) { clipboardInvoked = true })
        assertFalse(clipboardInvoked)
        assertTrue(HistoricalJsonFileExporter.canCopyToClipboard("{}"))
    }

    @Test fun `eleven megabyte json can be prepared completely for sharing`() {
        val largeJson = "x".repeat(11 * 1024 * 1024)

        val prepared = HistoricalJsonFileExporter.prepare(
            cacheDirectory = temporaryFolder.root,
            json = largeJson,
            date = LocalDate.of(2026, 10, 7),
        )

        assertEquals(largeJson.toByteArray(Charsets.UTF_8).size.toLong(), prepared.file.length())
        assertEquals(largeJson, prepared.file.readText(Charsets.UTF_8))
        assertEquals(HistoricalJsonFileExporter.MIME_TYPE, prepared.mimeType)
    }
}
