package com.juan.habitos.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
import com.juan.habitos.data.remote.PendingUsageSnapshot
import com.juan.habitos.data.remote.PendingUsageSnapshotStore
import com.juan.habitos.data.remote.UsageAuthorizationBlockReason
import com.juan.habitos.data.remote.UsageQueueEntryState
import com.juan.habitos.data.remote.UsageSnapshotQueue
import com.juan.habitos.data.remote.UsageSnapshotRequest
import java.security.KeyStore
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EncryptedUsageSnapshotStore(context: Context) : PendingUsageSnapshotStore {
    private val preferences = context.getSharedPreferences("pending_usage_snapshots", Context.MODE_PRIVATE)
    private val gson = Gson()

    override suspend fun load(profileId: String): UsageSnapshotQueue = withContext(Dispatchers.IO) {
        lock(profileId).withLock { readUnlocked(profileId) }
    }

    override suspend fun update(
        profileId: String,
        transform: (UsageSnapshotQueue) -> UsageSnapshotQueue,
    ): UsageSnapshotQueue = withContext(Dispatchers.IO) {
        lock(profileId).withLock {
            val updated = transform(readUnlocked(profileId)).normalized(profileId)
            check(preferences.edit().putString(key(profileId), encrypt(gson.toJson(updated))).commit()) {
                "Unable to persist encrypted usage queue"
            }
            updated
        }
    }

    private fun readUnlocked(profileId: String): UsageSnapshotQueue {
        val encrypted = preferences.getString(key(profileId), null) ?: return UsageSnapshotQueue()
        val json = decrypt(encrypted)
        val queue = runCatching { gson.fromJson(json, UsageSnapshotQueue::class.java) }
            .getOrNull()
            ?.takeIf { runCatching { it.entries.size }.isSuccess }
        if (queue != null) return queue.normalized(profileId)

        val legacy = gson.fromJson(json, LegacyPendingUsageSnapshot::class.java)
        val state = if (legacy.authorizationBlocked) UsageQueueEntryState.AUTHORIZATION_BLOCKED else UsageQueueEntryState.PENDING
        return UsageSnapshotQueue(
            entries = listOf(
                PendingUsageSnapshot(
                    profileId = profileId,
                    request = legacy.request,
                    state = state,
                    authorizationBlockReason = UsageAuthorizationBlockReason.FORBIDDEN.takeIf { legacy.authorizationBlocked },
                )
            ),
            lastCaptureAtEpochMillis = runCatching { Instant.parse(legacy.request.capturedAt).toEpochMilli() }.getOrNull(),
            lastSnapshot = legacy.request,
        )
    }

    private fun UsageSnapshotQueue.normalized(profileId: String): UsageSnapshotQueue {
        require(entries.all { it.profileId == profileId }) { "Usage queue contains another profile" }
        val profileEntries = entries
        require(profileEntries.map { it.request.snapshotId }.distinct().size == profileEntries.size) {
            "Duplicate usage snapshot UUID in encrypted queue"
        }
        return copy(entries = profileEntries.sortedBy { Instant.parse(it.request.capturedAt) })
    }

    private fun key(profileId: String) = "pending_$profileId"

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, IV_BYTES)))
        return cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)).toString(Charsets.UTF_8)
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

    private data class LegacyPendingUsageSnapshot(
        val profileId: String,
        val request: UsageSnapshotRequest,
        val authorizationBlocked: Boolean = false,
    )

    private companion object {
        const val KEY_ALIAS = "modera_pending_usage_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        val locks = ConcurrentHashMap<String, Mutex>()
        fun lock(profileId: String): Mutex = locks.computeIfAbsent(profileId) { Mutex() }
    }
}
