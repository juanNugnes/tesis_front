package com.juan.habitos.data.remote

import com.google.gson.GsonBuilder
import com.juan.habitos.data.device.F12CaptureAssessment
import com.juan.habitos.data.device.F12CaptureQuality
import com.juan.habitos.data.device.F12EvidenceDelivery
import com.juan.habitos.data.device.F12EvidenceDeliveryState
import com.juan.habitos.data.device.F12EvidenceReceipt
import com.juan.habitos.data.device.F12EvidenceState
import com.juan.habitos.data.device.F12RawEvidenceContract
import com.juan.habitos.data.device.F12RawEvidenceRecord
import com.juan.habitos.data.device.F12RawExportPair
import com.juan.habitos.data.device.sha256
import com.juan.habitos.data.local.F12EvidenceSaveResult
import com.juan.habitos.data.local.F12EvidenceSaveStatus
import com.juan.habitos.data.local.F12RawEvidenceStore
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class F12EvidenceTransportTest {
    private lateinit var server: MockWebServer

    @Before fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After fun stopServer() {
        server.shutdown()
    }

    @Test fun `multipart keeps pair order original bytes authentication and known content length`() = runTest {
        val record = availableRecord(pairCount = 2, revision = 2)
        server.enqueue(MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json").setBody(receiptJson(record)))

        val result = repository().deliver(record)
        val request = server.takeRequest()
        val wire = request.body.readUtf8()

        assertTrue(result is F12EvidenceDeliveryResult.Received)
        assertEquals("/api/v1/profiles/${record.profileId}/f12-evidence/v1", request.path)
        assertEquals("Bearer device-token", request.getHeader("Authorization"))
        assertEquals(request.bodySize, request.getHeader("Content-Length")?.toLong())
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data; boundary="))
        assertTrue(wire.contains("name=\"manifest\""))
        assertFalse(wire.substringBefore(record.exportPairs[0].summaryBytes.toString(Charsets.UTF_8)).contains("filename=\"manifest"))
        val positions = listOf(
            record.exportPairs[0].summaryBytes.toString(Charsets.UTF_8),
            record.exportPairs[0].eventsBytes.toString(Charsets.UTF_8).trim(),
            record.exportPairs[1].summaryBytes.toString(Charsets.UTF_8),
            record.exportPairs[1].eventsBytes.toString(Charsets.UTF_8).trim(),
        ).map(wire::indexOf)
        assertEquals(positions.sorted(), positions)
        assertTrue(positions.all { it >= 0 })
        assertTrue(wire.contains("\"capture_id\":\"${record.captureId}\""))
        assertTrue(wire.contains("\"content_sha256\":\"${record.contentSha256}\""))
        assertTrue(wire.contains("\"revision\":2"))
    }

    @Test fun `unavailable evidence sends manifest without fabricated file parts`() = runTest {
        val record = F12RawEvidenceContract.unavailableRecord(
            PROFILE_ID, DEVICE_ID, "2026-11-18", "UTC", 1, Instant.parse("2026-11-19T00:02:00Z")
        )
        server.enqueue(MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json").setBody(receiptJson(record)))

        val result = repository().deliver(record)
        val wire = server.takeRequest().body.readUtf8()

        assertTrue(result is F12EvidenceDeliveryResult.Received)
        assertTrue(wire.contains("\"evidence_state\":\"UNAVAILABLE\""))
        assertTrue(wire.contains("\"unavailable_reason\":\"NOT_EXPORTED\""))
        assertFalse(wire.contains("name=\"summary\""))
        assertFalse(wire.contains("name=\"events\""))
    }

    @Test fun `repository distinguishes conflicts authentication permanent and retryable responses`() = runTest {
        val record = availableRecord()
        val cases = listOf(
            401 to F12EvidenceDeliveryResult.AuthenticationRequired::class.java,
            403 to F12EvidenceDeliveryResult.Forbidden::class.java,
            409 to F12EvidenceDeliveryResult.Conflict::class.java,
            422 to F12EvidenceDeliveryResult.PermanentFailure::class.java,
            503 to F12EvidenceDeliveryResult.Retryable::class.java,
        )
        cases.forEach { (code, expected) ->
            server.enqueue(
                MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json")
                    .setBody("{\"detail\":{\"code\":\"test_$code\",\"message\":\"controlled\"}}")
            )
            val result = repository().deliver(record)
            assertTrue("HTTP $code returned ${result.javaClass}", expected.isInstance(result))
        }
        assertEquals("test_409", (repositoryResultFor(409, record) as F12EvidenceDeliveryResult.Conflict).errorCode)
    }

    @Test fun `response loss retries identical evidence and stores receipt only after confirmation`() = runTest {
        val initial = availableRecord(pairCount = 2)
        val store = MemoryEvidenceStore(initial)
        val sent = mutableListOf<F12RawEvidenceRecord>()
        val receipt = receipt(initial)
        val answers = ArrayDeque<F12EvidenceDeliveryResult>().apply {
            add(F12EvidenceDeliveryResult.Retryable())
            add(F12EvidenceDeliveryResult.Received(200, receipt))
        }
        val coordinator = F12EvidenceTransportCoordinator(
            enabled = true,
            store = store,
            repository = F12EvidenceDeliverer { record -> sent += record; answers.removeFirst() },
            clock = Clock.fixed(Instant.ofEpochMilli(99_000), ZoneOffset.UTC),
        )

        val first = coordinator.deliver(initial.captureId)
        assertEquals(F12EvidenceTransportStatus.RETRYABLE_FAILURE, first.status)
        assertEquals(F12EvidenceDeliveryState.RETRY_PENDING, store.record.delivery.state)
        assertEquals(1, store.record.delivery.attemptCount)
        assertEquals(null, store.record.delivery.receipt)
        assertArrayEquals(initial.exportPairs[0].eventsBytes, store.record.exportPairs[0].eventsBytes)

        val second = coordinator.deliver(initial.captureId)
        assertEquals(F12EvidenceTransportStatus.DELIVERED, second.status)
        assertEquals(2, store.record.delivery.attemptCount)
        assertEquals(receipt, store.record.delivery.receipt)
        assertEquals(listOf(initial.captureId, initial.captureId), sent.map(F12RawEvidenceRecord::captureId))
        assertEquals(listOf(initial.contentSha256, initial.contentSha256), sent.map(F12RawEvidenceRecord::contentSha256))
        sent.zipWithNext().forEach { (left, right) ->
            left.exportPairs.zip(right.exportPairs).forEach { (a, b) ->
                assertArrayEquals(a.summaryBytes, b.summaryBytes)
                assertArrayEquals(a.eventsBytes, b.eventsBytes)
            }
        }
    }

    @Test fun `conflict is terminal for same capture and never deletes local evidence`() = runTest {
        val initial = availableRecord()
        val store = MemoryEvidenceStore(initial)
        var calls = 0
        val coordinator = F12EvidenceTransportCoordinator(
            true,
            store,
            F12EvidenceDeliverer { calls++; F12EvidenceDeliveryResult.Conflict("evidence_revision_conflict") },
        )

        assertEquals(F12EvidenceTransportStatus.CONFLICT, coordinator.deliver(initial.captureId).status)
        assertEquals(F12EvidenceTransportStatus.CONFLICT, coordinator.deliver(initial.captureId).status)
        assertEquals(1, calls)
        assertEquals(initial.captureId, store.record.captureId)
        assertEquals(F12EvidenceDeliveryState.CONFLICT, store.record.delivery.state)
    }

    @Test fun `multipart factory rejects a body whose known size exceeds configured limit`() {
        val record = availableRecord()
        assertThrows(IllegalArgumentException::class.java) {
            F12EvidenceMultipartFactory(maxHttpBodyBytes = 1).create(record)
        }
    }

    private suspend fun repositoryResultFor(code: Int, record: F12RawEvidenceRecord): F12EvidenceDeliveryResult {
        server.enqueue(
            MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json")
                .setBody("{\"detail\":{\"code\":\"test_$code\"}}")
        )
        return repository().deliver(record)
    }

    private fun repository(): F12EvidenceRepository {
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create(GsonBuilder().serializeNulls().create()))
            .build()
            .create(F12EvidenceApi::class.java)
        return F12EvidenceRepository(api, FakeDeviceAuthorization)
    }

    private fun availableRecord(pairCount: Int = 1, revision: Int = 1): F12RawEvidenceRecord {
        val pairs = (0 until pairCount).map { index ->
            val summary = "{\"collectorRunId\":\"${RUN_IDS[index]}\",\"pair\":$index}".toByteArray()
            val events = "{\"pair\":$index,\"event\":\"original-$index\"}\n".toByteArray()
            F12RawExportPair(RUN_IDS[index], summary, events, summary.sha256(), events.sha256())
        }
        val content = F12RawEvidenceContract.contentSha256(pairs)
        return F12RawEvidenceRecord(
            captureId = F12RawEvidenceContract.captureId(PROFILE_ID, DEVICE_ID, "2026-11-18", revision, content),
            profileId = PROFILE_ID,
            deviceId = DEVICE_ID,
            localDate = "2026-11-18",
            timezoneId = "UTC",
            revision = revision,
            createdAtEpochMillis = Instant.parse("2026-11-19T00:02:00Z").toEpochMilli(),
            contentSha256 = content,
            quality = F12CaptureQuality(
                F12CaptureAssessment.CAPTURE_OBSERVED_UNVERIFIED,
                pairCount,
                0,
                pairCount,
                true,
                true,
                false,
                listOf("DAY_COMPLETENESS_NOT_ASSERTED"),
            ),
            exportPairs = pairs,
        )
    }

    private fun receipt(record: F12RawEvidenceRecord) = F12EvidenceReceipt(
        receiptId = "a12f9120-7641-4ae2-a988-d6c43d4cfd17",
        captureId = record.captureId,
        status = "RECEIVED",
        evidenceState = record.evidenceState,
        localDate = record.localDate,
        revision = record.revision,
        contentSha256 = record.contentSha256,
        pairCount = record.exportPairs.size,
        totalBytes = record.exportPairs.sumOf { it.summaryBytes.size.toLong() + it.eventsBytes.size },
        receivedAt = "2026-11-19T03:03:00Z",
        expiresAt = null,
    )

    private fun receiptJson(record: F12RawEvidenceRecord): String = GsonBuilder().serializeNulls().create().toJson(
        F12EvidenceReceiptDto(
            receiptId = receipt(record).receiptId,
            captureId = record.captureId,
            status = "RECEIVED",
            evidenceState = record.evidenceState.name,
            localDate = record.localDate,
            revision = record.revision,
            contentSha256 = record.contentSha256,
            pairCount = record.exportPairs.size,
            totalBytes = record.exportPairs.sumOf { it.summaryBytes.size.toLong() + it.eventsBytes.size },
            receivedAt = "2026-11-19T03:03:00Z",
            expiresAt = null,
        )
    )

    private object FakeDeviceAuthorization : RequestAuthorization {
        override val principal = RequestPrincipal.DEVICE
        override suspend fun <T> execute(request: suspend (authorization: String) -> T): T =
            request("Bearer device-token")
    }

    private class MemoryEvidenceStore(initial: F12RawEvidenceRecord) : F12RawEvidenceStore {
        var record = initial

        override suspend fun save(record: F12RawEvidenceRecord) =
            F12EvidenceSaveResult(F12EvidenceSaveStatus.CREATED, record).also { this.record = record }

        override suspend fun list(profileId: String) = listOf(record).filter { it.profileId == profileId }
        override suspend fun read(captureId: String) = record.takeIf { it.captureId == captureId }
        override suspend fun updateDelivery(
            captureId: String,
            transform: (F12EvidenceDelivery) -> F12EvidenceDelivery,
        ): F12RawEvidenceRecord {
            require(record.captureId == captureId)
            record = record.copy(delivery = transform(record.delivery))
            return record
        }
    }

    private companion object {
        const val PROFILE_ID = "4ff83bca-72b1-4a9d-b944-b07985053947"
        const val DEVICE_ID = "11111111-2222-4333-8444-555555555555"
        val RUN_IDS = listOf(
            "e12f9120-7641-4ae2-a988-d6c43d4cfd17",
            "a12f9120-7641-4ae2-a988-d6c43d4cfd17",
        )
    }
}
