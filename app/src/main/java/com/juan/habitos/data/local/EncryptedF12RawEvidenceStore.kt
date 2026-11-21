package com.juan.habitos.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import com.google.gson.Gson
import com.juan.habitos.data.device.F12CaptureQuality
import com.juan.habitos.data.device.F12EvidenceDelivery
import com.juan.habitos.data.device.F12EvidenceState
import com.juan.habitos.data.device.F12RawEvidenceContract
import com.juan.habitos.data.device.F12RawEvidenceRecord
import com.juan.habitos.data.device.F12RawExportPair
import com.juan.habitos.data.device.sha256
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class F12EvidenceSaveStatus { CREATED, ALREADY_PRESENT }

data class F12EvidenceSaveResult(
    val status: F12EvidenceSaveStatus,
    val record: F12RawEvidenceRecord,
    val evictedCaptureIds: List<String> = emptyList(),
)

interface F12RawEvidenceStore {
    suspend fun save(record: F12RawEvidenceRecord): F12EvidenceSaveResult
    suspend fun list(profileId: String): List<F12RawEvidenceRecord>
    suspend fun read(captureId: String): F12RawEvidenceRecord?
    suspend fun updateDelivery(
        captureId: String,
        transform: (F12EvidenceDelivery) -> F12EvidenceDelivery,
    ): F12RawEvidenceRecord
}

/**
 * App-private encrypted archive. The exact JSON/JSONL bytes are encrypted as one authenticated blob;
 * filenames contain only a content-derived identifier and no profile or date.
 */
class EncryptedF12RawEvidenceStore(context: Context) : F12RawEvidenceStore {
    private val directory = File(context.noBackupFilesDir, DIRECTORY_NAME)
    private val gson = Gson()

    override suspend fun save(record: F12RawEvidenceRecord): F12EvidenceSaveResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val existing = readAll()
            val decision = F12EvidenceCatalog.insert(existing, record)
            if (decision.status == F12EvidenceSaveStatus.ALREADY_PRESENT) {
                return@withLock F12EvidenceSaveResult(decision.status, decision.record)
            }
            require(directory.exists() || directory.mkdirs()) { "Unable to create encrypted F12 evidence directory" }
            val file = file(record.captureId)
            check(!file.exists()) { "F12 evidence file already exists" }
            write(file, record)
            decision.evictedCaptureIds.forEach { captureId ->
                check(file(captureId).delete() || !file(captureId).exists()) { "Unable to apply F12 retention limit" }
            }
            F12EvidenceSaveResult(decision.status, record, decision.evictedCaptureIds)
        }
    }

    override suspend fun list(profileId: String): List<F12RawEvidenceRecord> = withContext(Dispatchers.IO) {
        mutex.withLock { readAll().filter { it.profileId == profileId }.sortedByDescending { it.createdAtEpochMillis } }
    }

    override suspend fun read(captureId: String): F12RawEvidenceRecord? = withContext(Dispatchers.IO) {
        mutex.withLock { file(captureId).takeIf(File::isFile)?.readBytes()?.let(::decrypt)?.let(::decode) }
    }

    override suspend fun updateDelivery(
        captureId: String,
        transform: (F12EvidenceDelivery) -> F12EvidenceDelivery,
    ): F12RawEvidenceRecord = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = file(captureId)
            val current = file.takeIf(File::isFile)?.readBytes()?.let(::decrypt)?.let(::decode)
                ?: throw IllegalArgumentException("F12 evidence not found")
            val updated = current.copy(delivery = transform(current.delivery))
            F12EvidenceCatalog.validate(updated)
            write(file, updated)
            updated
        }
    }

    private fun write(file: File, record: F12RawEvidenceRecord) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(encrypt(encode(record)))
            atomic.finishWrite(stream)
        } catch (failure: Throwable) {
            atomic.failWrite(stream)
            throw failure
        }
    }

    private fun readAll(): List<F12RawEvidenceRecord> {
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles { file -> file.isFile && file.extension == FILE_EXTENSION }
            .orEmpty()
            .sortedBy { it.name }
            .map { decode(decrypt(it.readBytes())) }
    }

    private fun file(captureId: String): File {
        require(captureId.matches(Regex("[0-9a-f]{64}"))) { "Invalid captureId" }
        return File(directory, "$captureId.$FILE_EXTENSION")
    }

    private fun encode(record: F12RawEvidenceRecord): ByteArray = gson.toJson(
        PersistedRecord(
            envelopeVersion = record.envelopeVersion,
            captureId = record.captureId,
            adapterVersion = record.adapterVersion,
            profileId = record.profileId,
            deviceId = record.deviceId,
            localDate = record.localDate,
            timezoneId = record.timezoneId,
            revision = record.revision,
            createdAtEpochMillis = record.createdAtEpochMillis,
            contentSha256 = record.contentSha256,
            quality = record.quality,
            evidenceState = record.evidenceState.name,
            unavailableReason = record.unavailableReason,
            delivery = record.delivery,
            exportPairs = record.exportPairs.map {
                PersistedPair(
                    it.collectorRunId,
                    Base64.encodeToString(it.summaryBytes, Base64.NO_WRAP),
                    Base64.encodeToString(it.eventsBytes, Base64.NO_WRAP),
                    it.summarySha256,
                    it.eventsSha256,
                )
            },
        )
    ).toByteArray(Charsets.UTF_8)

    private fun decode(bytes: ByteArray): F12RawEvidenceRecord {
        val stored = gson.fromJson(bytes.toString(Charsets.UTF_8), PersistedRecord::class.java)
        val record = F12RawEvidenceRecord(
            envelopeVersion = stored.envelopeVersion,
            captureId = stored.captureId,
            adapterVersion = stored.adapterVersion,
            profileId = stored.profileId,
            deviceId = stored.deviceId,
            localDate = stored.localDate,
            timezoneId = stored.timezoneId,
            revision = stored.revision,
            createdAtEpochMillis = stored.createdAtEpochMillis,
            contentSha256 = stored.contentSha256,
            quality = stored.quality,
            evidenceState = stored.evidenceState?.let(F12EvidenceState::valueOf) ?: F12EvidenceState.AVAILABLE,
            unavailableReason = stored.unavailableReason,
            delivery = stored.delivery ?: F12EvidenceDelivery(),
            exportPairs = stored.exportPairs.map {
                F12RawExportPair(
                    collectorRunId = it.collectorRunId,
                    summaryBytes = Base64.decode(it.summaryBase64, Base64.NO_WRAP),
                    eventsBytes = Base64.decode(it.eventsBase64, Base64.NO_WRAP),
                    summarySha256 = it.summarySha256,
                    eventsSha256 = it.eventsSha256,
                )
            },
        )
        F12EvidenceCatalog.validate(record)
        return record
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun decrypt(encrypted: ByteArray): ByteArray {
        require(encrypted.size > IV_BYTES) { "Invalid encrypted F12 evidence" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, encrypted.copyOfRange(0, IV_BYTES)))
        return cipher.doFinal(encrypted.copyOfRange(IV_BYTES, encrypted.size))
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private data class PersistedRecord(
        val envelopeVersion: Int,
        val captureId: String,
        val adapterVersion: String,
        val profileId: String,
        val deviceId: String,
        val localDate: String,
        val timezoneId: String,
        val revision: Int,
        val createdAtEpochMillis: Long,
        val contentSha256: String,
        val quality: F12CaptureQuality,
        val evidenceState: String?,
        val unavailableReason: String?,
        val delivery: F12EvidenceDelivery?,
        val exportPairs: List<PersistedPair>,
    )

    private data class PersistedPair(
        val collectorRunId: String,
        val summaryBase64: String,
        val eventsBase64: String,
        val summarySha256: String,
        val eventsSha256: String,
    )

    private companion object {
        const val DIRECTORY_NAME = "f12_raw_evidence_v1"
        const val FILE_EXTENSION = "f12e"
        const val KEY_ALIAS = "modera_f12_raw_evidence_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        val mutex = Mutex()
    }
}

internal object F12EvidenceCatalog {
    const val MAX_RECORDS = 16
    const val MAX_TOTAL_SOURCE_BYTES = 96L * 1024 * 1024

    data class Decision(
        val status: F12EvidenceSaveStatus,
        val record: F12RawEvidenceRecord,
        val evictedCaptureIds: List<String>,
    )

    fun insert(existing: List<F12RawEvidenceRecord>, record: F12RawEvidenceRecord): Decision {
        validate(record)
        val sameRevision = existing.firstOrNull {
            it.profileId == record.profileId && it.deviceId == record.deviceId &&
                it.localDate == record.localDate && it.revision == record.revision
        }
        if (sameRevision != null) {
            require(sameRevision.captureId == record.captureId && sameRevision.contentSha256 == record.contentSha256) {
                "F12 evidence revision already exists with different original bytes"
            }
            return Decision(F12EvidenceSaveStatus.ALREADY_PRESENT, sameRevision, emptyList())
        }
        val latestRevision = existing.asSequence()
            .filter { it.profileId == record.profileId && it.deviceId == record.deviceId && it.localDate == record.localDate }
            .maxOfOrNull(F12RawEvidenceRecord::revision)
        require(latestRevision == null || record.revision > latestRevision) {
            "A correction must use a revision greater than $latestRevision"
        }
        var retainedBytes = 0L
        val retained = (existing + record)
            .sortedByDescending(F12RawEvidenceRecord::createdAtEpochMillis)
            .filter { candidate ->
                val candidateBytes = candidate.sourceByteSize()
                val fits = retainedBytes + candidateBytes <= MAX_TOTAL_SOURCE_BYTES
                if (fits) retainedBytes += candidateBytes
                fits
            }
            .take(MAX_RECORDS)
        require(retained.any { it.captureId == record.captureId }) { "New evidence falls outside the retention window" }
        val retainedIds = retained.mapTo(mutableSetOf(), F12RawEvidenceRecord::captureId)
        return Decision(
            F12EvidenceSaveStatus.CREATED,
            record,
            existing.map(F12RawEvidenceRecord::captureId).filterNot(retainedIds::contains),
        )
    }

    fun validate(record: F12RawEvidenceRecord) {
        require(record.envelopeVersion == 1 && record.revision > 0)
        require(record.profileId.isNotBlank() && record.deviceId.isNotBlank())
        require(record.captureId.matches(Regex("[0-9a-f]{64}")))
        require(record.contentSha256.matches(Regex("[0-9a-f]{64}")))
        when (record.evidenceState) {
            F12EvidenceState.AVAILABLE -> {
                require(record.exportPairs.isNotEmpty() && record.unavailableReason == null)
                require(record.contentSha256 == F12RawEvidenceContract.contentSha256(record.exportPairs)) {
                    "F12 content SHA-256 does not match original pairs"
                }
            }
            F12EvidenceState.UNAVAILABLE -> {
                require(record.exportPairs.isEmpty())
                require(record.unavailableReason == com.juan.habitos.data.device.F12_UNAVAILABLE_REASON_NOT_EXPORTED)
                require(record.contentSha256 == F12RawEvidenceContract.unavailableContentSha256())
            }
        }
        require(
            record.captureId == F12RawEvidenceContract.captureId(
                record.profileId,
                record.deviceId,
                record.localDate,
                record.revision,
                record.contentSha256,
            )
        ) { "F12 captureId does not match its authenticated binding" }
        require(record.sourceByteSize() <= com.juan.habitos.data.device.F12_MAX_EXPORT_BYTES) {
            "F12 source bytes exceed the Analytics limit"
        }
        record.exportPairs.forEach {
            require(it.summarySha256 == it.summaryBytes.sha256()) { "Summary bytes do not match stored SHA-256" }
            require(it.eventsSha256 == it.eventsBytes.sha256()) { "Event bytes do not match stored SHA-256" }
        }
        record.delivery.receipt?.let { receipt ->
            require(receipt.captureId == record.captureId)
            require(receipt.contentSha256 == record.contentSha256)
            require(receipt.revision == record.revision)
        }
    }

    private fun F12RawEvidenceRecord.sourceByteSize(): Long = exportPairs.sumOf {
        it.summaryBytes.size.toLong() + it.eventsBytes.size
    }
}
