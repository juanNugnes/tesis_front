package com.juan.habitos.data.local

import com.juan.habitos.data.device.F12CaptureAssessment
import com.juan.habitos.data.device.F12CaptureQuality
import com.juan.habitos.data.device.F12RawEvidenceRecord
import com.juan.habitos.data.device.F12RawExportPair
import com.juan.habitos.data.device.sha256
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class F12EvidenceCatalogTest {
    @Test fun `retry of identical revision is idempotent`() {
        val record = record(revision = 1, bytes = "original")

        val decision = F12EvidenceCatalog.insert(listOf(record), record.copy(createdAtEpochMillis = 999))

        assertEquals(F12EvidenceSaveStatus.ALREADY_PRESENT, decision.status)
        assertEquals(record, decision.record)
        assertTrue(decision.evictedCaptureIds.isEmpty())
    }

    @Test fun `same revision cannot silently replace different originals`() {
        val first = record(revision = 1, bytes = "original")
        val changed = record(revision = 1, bytes = "changed")

        assertThrows(IllegalArgumentException::class.java) { F12EvidenceCatalog.insert(listOf(first), changed) }
    }

    @Test fun `correction requires increasing revision and preserves older record`() {
        val first = record(revision = 1, bytes = "original")
        val corrected = record(revision = 2, bytes = "corrected", createdAt = 2)

        val decision = F12EvidenceCatalog.insert(listOf(first), corrected)

        assertEquals(F12EvidenceSaveStatus.CREATED, decision.status)
        assertTrue(decision.evictedCaptureIds.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            F12EvidenceCatalog.insert(listOf(first, corrected), record(revision = 1, bytes = "late"))
        }
    }

    @Test fun `retention is bounded and reports every eviction`() {
        val existing = (1..F12EvidenceCatalog.MAX_RECORDS).map { index ->
            record(revision = 1, bytes = "value-$index", date = "2026-10-${index.toString().padStart(2, '0')}", createdAt = index.toLong())
        }
        val newest = record(revision = 1, bytes = "new", date = "2026-11-01", createdAt = 100)

        val decision = F12EvidenceCatalog.insert(existing, newest)

        assertEquals(1, decision.evictedCaptureIds.size)
        assertEquals(existing.first().captureId, decision.evictedCaptureIds.single())
    }

    private fun record(
        revision: Int,
        bytes: String,
        date: String = "2026-10-01",
        createdAt: Long = 1,
    ): F12RawEvidenceRecord {
        val summary = bytes.toByteArray()
        val events = "$bytes\n".toByteArray()
        val content = (bytes + revision).toByteArray().sha256()
        return F12RawEvidenceRecord(
            captureId = ("$date|$revision|$content").toByteArray().sha256(),
            profileId = "profile-1",
            deviceId = "device-1",
            localDate = date,
            timezoneId = "UTC",
            revision = revision,
            createdAtEpochMillis = createdAt,
            contentSha256 = content,
            quality = F12CaptureQuality(
                F12CaptureAssessment.CAPTURE_OBSERVED_UNVERIFIED,
                successfulRunCount = 1,
                failedRunCount = 0,
                eventCount = 1,
                dayQuerySucceeded = true,
                priorScreenStateObserved = true,
                deviceRestartObserved = false,
                qualityFlags = listOf("DAY_COMPLETENESS_NOT_ASSERTED"),
            ),
            exportPairs = listOf(
                F12RawExportPair(
                    collectorRunId = "e12f9120-7641-4ae2-a988-d6c43d4cfd17",
                    summaryBytes = summary,
                    eventsBytes = events,
                    summarySha256 = summary.sha256(),
                    eventsSha256 = events.sha256(),
                )
            ),
        )
    }
}
