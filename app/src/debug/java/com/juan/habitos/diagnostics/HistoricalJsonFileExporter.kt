package com.juan.habitos.diagnostics

import java.io.File
import java.time.LocalDate

data class HistoricalJsonShareFile(
    val file: File,
    val mimeType: String,
)

object HistoricalJsonFileExporter {
    const val MIME_TYPE = "application/json"
    const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".diagnostics.fileprovider"
    const val MAX_CLIPBOARD_BYTES = 512 * 1024
    private const val CACHE_DIRECTORY = "historical-diagnostics"

    fun canCopyToClipboard(json: String): Boolean =
        json.toByteArray(Charsets.UTF_8).size <= MAX_CLIPBOARD_BYTES

    fun copyToClipboardIfSafe(json: String, copy: (String) -> Unit): Boolean {
        if (!canCopyToClipboard(json)) return false
        copy(json)
        return true
    }

    fun fileName(date: LocalDate): String = "modera-historical-diagnostic-$date.json"

    fun prepare(cacheDirectory: File, json: String, date: LocalDate): HistoricalJsonShareFile {
        val exportDirectory = File(cacheDirectory, CACHE_DIRECTORY)
        check(exportDirectory.exists() || exportDirectory.mkdirs()) { "Unable to create diagnostic cache directory" }
        val file = File(exportDirectory, fileName(date))
        file.writeBytes(json.toByteArray(Charsets.UTF_8))
        return HistoricalJsonShareFile(file = file, mimeType = MIME_TYPE)
    }
}
