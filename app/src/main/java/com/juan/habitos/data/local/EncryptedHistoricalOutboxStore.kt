package com.juan.habitos.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
import com.juan.habitos.data.remote.HistoricalOutbox
import com.juan.habitos.data.remote.HistoricalOutboxState
import com.juan.habitos.data.remote.HistoricalOutboxStore
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EncryptedHistoricalOutboxStore(context: Context) : HistoricalOutboxStore {
    private val preferences = context.getSharedPreferences("pending_f12_historical_submissions", Context.MODE_PRIVATE)
    private val gson = Gson()

    override suspend fun load(profileId: String): HistoricalOutbox = withContext(Dispatchers.IO) {
        lock(profileId).withLock { read(profileId) }
    }

    override suspend fun update(
        profileId: String,
        transform: (HistoricalOutbox) -> HistoricalOutbox,
    ): HistoricalOutbox = withContext(Dispatchers.IO) {
        lock(profileId).withLock {
            val updated = transform(read(profileId)).normalized(profileId)
            check(preferences.edit().putString(key(profileId), encrypt(gson.toJson(updated))).commit()) {
                "Unable to persist encrypted F12 historical outbox"
            }
            updated
        }
    }

    private fun read(profileId: String): HistoricalOutbox {
        val encrypted = preferences.getString(key(profileId), null) ?: return HistoricalOutbox()
        return gson.fromJson(decrypt(encrypted), HistoricalOutbox::class.java).normalized(profileId)
    }

    private fun HistoricalOutbox.normalized(profileId: String): HistoricalOutbox {
        require(entries.all { it.profileId == profileId }) { "F12 outbox contains another profile" }
        require(entries.map { it.request.requestId }.distinct().size == entries.size) { "Duplicate F12 request_id" }
        require(entries.map { it.localDate }.distinct().size == entries.size) { "Duplicate F12 local day" }
        return copy(entries = entries.sortedBy { it.localDate }.takeLast(MAX_RETAINED_ENTRIES))
    }

    private fun key(profileId: String) = "f12_$profileId"

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
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }

    private companion object {
        const val KEY_ALIAS = "modera_f12_historical_outbox_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val MAX_RETAINED_ENTRIES = 32
        val locks = ConcurrentHashMap<String, Mutex>()
        fun lock(profileId: String): Mutex = locks.computeIfAbsent(profileId) { Mutex() }
    }
}
