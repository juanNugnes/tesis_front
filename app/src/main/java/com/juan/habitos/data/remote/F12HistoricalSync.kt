package com.juan.habitos.data.remote

import com.juan.habitos.data.device.F12HistoricalRequestCollector
import com.juan.habitos.domain.repository.DeviceIdentityLocalStore
import com.juan.habitos.domain.repository.ProfileLocalStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class F12SyncStatus { DISABLED, SYNCED, RETRYABLE_FAILURE, AUTHORIZATION_PAUSED, PERMANENT_FAILURE, MISSING_CONFIGURATION }

data class F12SyncOutcome(
    val status: F12SyncStatus,
    val localDate: String? = null,
    val requestId: String? = null,
    val analyticsEvaluationId: String? = null,
)

class F12HistoricalSyncCoordinator(
    private val enabled: Boolean,
    private val profileStore: ProfileLocalStore,
    private val identityStore: DeviceIdentityLocalStore,
    private val collector: F12HistoricalRequestCollector,
    private val repository: HistoricalSubmissionRepository,
    private val store: HistoricalOutboxStore,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
    private val now: () -> Instant = Instant::now,
    private val mutex: Mutex = Mutex(),
) {
    suspend fun synchronize(): F12SyncOutcome = mutex.withLock {
        if (!enabled) return@withLock F12SyncOutcome(F12SyncStatus.DISABLED)
        val profile = profileStore.profile.first()
            ?: return@withLock F12SyncOutcome(F12SyncStatus.MISSING_CONFIGURATION)
        val profileId = profile.id
            ?: return@withLock F12SyncOutcome(F12SyncStatus.MISSING_CONFIGURATION)
        if (!profile.active || !profileStore.onboardingComplete.first()) {
            return@withLock F12SyncOutcome(F12SyncStatus.MISSING_CONFIGURATION)
        }
        val identity = identityStore.identity.first()
        val credentials = identityStore.credentials.first()
        val deviceId = identity.deviceId
            ?: return@withLock F12SyncOutcome(F12SyncStatus.MISSING_CONFIGURATION)
        if (identity.profileId != profileId || credentials.accessToken == null) {
            return@withLock F12SyncOutcome(F12SyncStatus.MISSING_CONFIGURATION)
        }

        val zone = zoneId()
        val currentDate = now().atZone(zone).toLocalDate()
        val closedDate = currentDate.minusDays(1)
        var outbox = store.load(profileId)
        outbox.entries.firstOrNull { it.state == HistoricalOutboxState.AUTHORIZATION_BLOCKED }?.let {
            return@withLock F12SyncOutcome(F12SyncStatus.AUTHORIZATION_PAUSED, it.localDate, it.request.requestId)
        }
        if (outbox.entries.none { it.localDate == closedDate.toString() }) {
            val request = collector.collect(profileId, deviceId, closedDate, zone)
            store.update(profileId) { current ->
                if (current.entries.any { it.localDate == closedDate.toString() }) current
                else current.copy(entries = current.entries + HistoricalOutboxEntry(profileId, closedDate.toString(), request))
            }
            outbox = store.load(profileId)
        }

        val entry = outbox.entries
            .filter { it.state == HistoricalOutboxState.PENDING }
            .minByOrNull(HistoricalOutboxEntry::localDate)
            ?: return@withLock F12SyncOutcome(F12SyncStatus.SYNCED, closedDate.toString())
        val attempted = entry.copy(attemptCount = entry.attemptCount + 1, lastAttemptAtEpochMillis = now().toEpochMilli())
        replace(profileId, attempted)
        when (val delivery = repository.deliver(attempted)) {
            is HistoricalDeliveryResult.Received -> handleResponse(profileId, attempted, delivery.response)
            HistoricalDeliveryResult.AuthenticationRequired -> {
                replace(profileId, attempted.copy(state = HistoricalOutboxState.AUTHORIZATION_BLOCKED))
                identityStore.clearDeviceAccessToken()
                F12SyncOutcome(F12SyncStatus.AUTHORIZATION_PAUSED, attempted.localDate, attempted.request.requestId)
            }
            HistoricalDeliveryResult.Forbidden -> {
                replace(profileId, attempted.copy(state = HistoricalOutboxState.AUTHORIZATION_BLOCKED))
                F12SyncOutcome(F12SyncStatus.AUTHORIZATION_PAUSED, attempted.localDate, attempted.request.requestId)
            }
            is HistoricalDeliveryResult.ContractRejected -> {
                replace(profileId, attempted.copy(state = HistoricalOutboxState.PERMANENT_FAILURE))
                F12SyncOutcome(F12SyncStatus.PERMANENT_FAILURE, attempted.localDate, attempted.request.requestId)
            }
            is HistoricalDeliveryResult.RetryableTransport ->
                F12SyncOutcome(F12SyncStatus.RETRYABLE_FAILURE, attempted.localDate, attempted.request.requestId)
        }
    }

    suspend fun resumeAuthorization(profileId: String) = mutex.withLock {
        store.update(profileId) { outbox -> outbox.copy(entries = outbox.entries.map {
            if (it.state == HistoricalOutboxState.AUTHORIZATION_BLOCKED) it.copy(state = HistoricalOutboxState.PENDING) else it
        }) }
    }

    private suspend fun handleResponse(
        profileId: String,
        entry: HistoricalOutboxEntry,
        response: HistoricalSubmissionResponse,
    ): F12SyncOutcome {
        val state = when (response.status) {
            HistoricalSubmissionStatus.PROCESSED -> HistoricalOutboxState.PROCESSED
            HistoricalSubmissionStatus.CONFLICT -> HistoricalOutboxState.CONFLICT
            HistoricalSubmissionStatus.TECHNICAL_ERROR -> if (response.retryable) HistoricalOutboxState.PENDING else HistoricalOutboxState.PERMANENT_FAILURE
            HistoricalSubmissionStatus.PENDING, HistoricalSubmissionStatus.SENDING -> HistoricalOutboxState.PENDING
        }
        replace(profileId, entry.copy(state = state, submissionId = response.id, response = response))
        val status = when (state) {
            HistoricalOutboxState.PROCESSED -> F12SyncStatus.SYNCED
            HistoricalOutboxState.PENDING -> F12SyncStatus.RETRYABLE_FAILURE
            HistoricalOutboxState.AUTHORIZATION_BLOCKED -> F12SyncStatus.AUTHORIZATION_PAUSED
            HistoricalOutboxState.CONFLICT, HistoricalOutboxState.PERMANENT_FAILURE -> F12SyncStatus.PERMANENT_FAILURE
        }
        return F12SyncOutcome(status, entry.localDate, entry.request.requestId, response.analyticsEvaluationId)
    }

    private suspend fun replace(profileId: String, entry: HistoricalOutboxEntry) {
        store.update(profileId) { outbox -> outbox.copy(entries = outbox.entries.map {
            if (it.request.requestId == entry.request.requestId) entry else it
        }) }
    }
}
