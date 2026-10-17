package com.juan.habitos.data.remote

import com.juan.habitos.data.device.UsageSnapshotCollector
import com.juan.habitos.domain.model.*
import com.juan.habitos.domain.repository.DeviceAuthenticationFailureReason
import com.juan.habitos.domain.repository.DeviceAuthenticationRequiredException
import com.juan.habitos.domain.repository.DeviceCredentialStore
import com.juan.habitos.domain.repository.ProfileLocalStore
import com.juan.habitos.domain.repository.UsageRepository
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

enum class UsageQueueEntryState { PENDING, AUTHORIZATION_BLOCKED, QUARANTINED }
enum class UsageAuthorizationBlockReason { MISSING_CREDENTIAL, EXPIRED_OR_INVALID, DEVICE_DISABLED, MONITORING_NOT_AUTHORIZED, FORBIDDEN }
enum class UsageConflictCode { SNAPSHOT_CONFLICT, STALE_SNAPSHOT, AMBIGUOUS_ORDER }

data class PendingUsageSnapshot(
    val profileId: String,
    val request: UsageSnapshotRequest,
    val state: UsageQueueEntryState = UsageQueueEntryState.PENDING,
    val authorizationBlockReason: UsageAuthorizationBlockReason? = null,
    val blockedCredentialFingerprint: String? = null,
    val conflictCode: UsageConflictCode? = null,
    val attemptCount: Int = 0,
    val lastAttemptAtEpochMillis: Long? = null,
)

data class UsageSnapshotQueue(
    val entries: List<PendingUsageSnapshot> = emptyList(),
    val lastCaptureAtEpochMillis: Long? = null,
    val lastSnapshot: UsageSnapshotRequest? = null,
) {
    val pendingCount: Int get() = entries.count { it.state == UsageQueueEntryState.PENDING }
    val quarantinedCount: Int get() = entries.count { it.state == UsageQueueEntryState.QUARANTINED }
}

interface PendingUsageSnapshotStore {
    suspend fun load(profileId: String): UsageSnapshotQueue
    suspend fun update(profileId: String, transform: (UsageSnapshotQueue) -> UsageSnapshotQueue): UsageSnapshotQueue
}

enum class UsageSyncStatus { SYNCED, RETRYABLE_FAILURE, AUTHORIZATION_PAUSED, MISSING_CONFIGURATION, PERMISSION_MISSING, QUEUE_FULL, PERMANENT_FAILURE }

data class UsageSyncOutcome(
    val status: UsageSyncStatus,
    val overview: UsageOverview,
    val pendingCount: Int,
    val quarantinedCount: Int,
    val authorizationBlockReason: UsageAuthorizationBlockReason? = null,
)

class UsageSyncCoordinator(
    private val profileStore: ProfileLocalStore,
    private val credentialStore: DeviceCredentialStore,
    private val collector: UsageSnapshotCollector,
    private val hasUsageAccess: () -> Boolean,
    private val api: HabitsApi,
    private val authorization: RequestAuthorization,
    private val queueStore: PendingUsageSnapshotStore,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val synchronization: Mutex = Mutex(),
    private val onAutomaticPause: suspend () -> Unit = {},
) {
    suspend fun isAuthorizationPaused(profileId: String, token: String): Boolean =
        queueStore.load(profileId).entries.any {
            it.state == UsageQueueEntryState.AUTHORIZATION_BLOCKED &&
                it.blockedCredentialFingerprint == credentialFingerprint(token)
        }

    suspend fun synchronize(): UsageSyncOutcome = synchronization.withLock {
        val profile = profileStore.profile.first()
            ?: return@withLock outcome(UsageSyncStatus.MISSING_CONFIGURATION, null, "No hay un perfil infantil configurado.")
        val profileId = profile.id
            ?: return@withLock outcome(UsageSyncStatus.MISSING_CONFIGURATION, null, "El dispositivo todavía no está vinculado al perfil.")
        if (!profile.active || !profileStore.onboardingComplete.first()) {
            return@withLock outcome(UsageSyncStatus.MISSING_CONFIGURATION, profileId, "La configuración infantil todavía no está completa.")
        }
        val token = credentialStore.credentials.first().accessToken
        if (token == null) {
            val reason = UsageAuthorizationBlockReason.MISSING_CREDENTIAL
            blockAuthorization(profileId, null, reason)
            onAutomaticPause()
            return@withLock outcome(UsageSyncStatus.AUTHORIZATION_PAUSED, profileId, authorizationMessage(reason), reason)
        }
        if (!runCatching(hasUsageAccess).getOrDefault(false)) {
            return@withLock outcome(UsageSyncStatus.PERMISSION_MISSING, profileId, "Falta el permiso de acceso a estadísticas de uso.")
        }

        val fingerprint = credentialFingerprint(token)
        unblockForNewCredential(profileId, fingerprint)
        var queue = queueStore.load(profileId)
        queue.entries.firstOrNull { it.state == UsageQueueEntryState.AUTHORIZATION_BLOCKED }?.let { blocked ->
            return@withLock outcome(UsageSyncStatus.AUTHORIZATION_PAUSED, profileId, authorizationMessage(blocked.authorizationBlockReason), blocked.authorizationBlockReason)
        }

        var latestOverview: UsageOverview? = queue.lastSnapshot?.toOverview()
        var capturedThisRun = false
        while (true) {
            queue = queueStore.load(profileId)
            val next = queue.entries.asSequence()
                .filter { it.state == UsageQueueEntryState.PENDING }
                .minByOrNull { Instant.parse(it.request.capturedAt) }

            if (next == null) {
                if (!capturedThisRun) {
                    when (captureIfDue(profile, queue)) {
                        CaptureResult.ADDED -> {
                            capturedThisRun = true
                            latestOverview = queueStore.load(profileId).lastSnapshot?.toOverview()
                            continue
                        }
                        CaptureResult.FULL -> return@withLock outcome(UsageSyncStatus.QUEUE_FULL, profileId, "La cola de métricas está completa; se conservaron todas las instantáneas existentes.", overview = latestOverview)
                        CaptureResult.NOT_DUE -> Unit
                    }
                }
                return@withLock outcome(UsageSyncStatus.SYNCED, profileId, null, overview = latestOverview)
            }

            markAttempt(profileId, next)
            try {
                val response = authorization.execute { header -> api.postUsageSnapshot(profileId, header, next.request) }
                latestOverview = response.usage.toOverview()
                removeEntry(profileId, next.request.snapshotId)
            } catch (exception: DeviceAuthenticationRequiredException) {
                val reason = when (exception.reason) {
                    DeviceAuthenticationFailureReason.MISSING_CREDENTIAL -> UsageAuthorizationBlockReason.MISSING_CREDENTIAL
                    DeviceAuthenticationFailureReason.EXPIRED_OR_INVALID -> UsageAuthorizationBlockReason.EXPIRED_OR_INVALID
                }
                blockAuthorization(profileId, fingerprint, reason)
                onAutomaticPause()
                return@withLock outcome(UsageSyncStatus.AUTHORIZATION_PAUSED, profileId, authorizationMessage(reason), reason, latestOverview)
            } catch (exception: HttpException) {
                when (exception.code()) {
                    401, 403 -> {
                        val reason = authorizationReason(exception)
                        blockAuthorization(profileId, fingerprint, reason)
                        onAutomaticPause()
                        return@withLock outcome(UsageSyncStatus.AUTHORIZATION_PAUSED, profileId, authorizationMessage(reason), reason, latestOverview)
                    }
                    409 -> {
                        quarantine(profileId, next.request.snapshotId, conflictCode(exception))
                        continue
                    }
                    in 500..599 -> {
                        val capture = if (!capturedThisRun) captureIfDue(profile, queueStore.load(profileId)) else CaptureResult.NOT_DUE
                        capturedThisRun = capturedThisRun || capture == CaptureResult.ADDED
                        val status = if (capture == CaptureResult.FULL) UsageSyncStatus.QUEUE_FULL else UsageSyncStatus.RETRYABLE_FAILURE
                        return@withLock outcome(status, profileId, if (status == UsageSyncStatus.QUEUE_FULL) "La cola está completa y el servidor continúa temporalmente inaccesible." else "Las métricas están guardadas y pendientes de sincronización.", overview = latestOverview)
                    }
                    else -> return@withLock outcome(UsageSyncStatus.PERMANENT_FAILURE, profileId, "El servidor rechazó la sincronización; la instantánea se conservó.", overview = latestOverview)
                }
            } catch (_: IOException) {
                val capture = if (!capturedThisRun) captureIfDue(profile, queueStore.load(profileId)) else CaptureResult.NOT_DUE
                capturedThisRun = capturedThisRun || capture == CaptureResult.ADDED
                val status = if (capture == CaptureResult.FULL) UsageSyncStatus.QUEUE_FULL else UsageSyncStatus.RETRYABLE_FAILURE
                return@withLock outcome(status, profileId, if (status == UsageSyncStatus.QUEUE_FULL) "La cola está completa y no hay conexión." else "Las métricas están guardadas y pendientes de sincronización.", overview = latestOverview)
            } catch (exception: Exception) {
                if (!exception.hasCause<IOException>()) throw exception
                val capture = if (!capturedThisRun) captureIfDue(profile, queueStore.load(profileId)) else CaptureResult.NOT_DUE
                capturedThisRun = capturedThisRun || capture == CaptureResult.ADDED
                val status = if (capture == CaptureResult.FULL) UsageSyncStatus.QUEUE_FULL else UsageSyncStatus.RETRYABLE_FAILURE
                return@withLock outcome(status, profileId, if (status == UsageSyncStatus.QUEUE_FULL) "La cola está completa y no hay conexión." else "Las métricas están guardadas y pendientes de sincronización.", overview = latestOverview)
            }
        }
        error("Unreachable usage synchronization state")
    }

    private suspend fun captureIfDue(profile: Profile, queue: UsageSnapshotQueue): CaptureResult {
        val now = nowEpochMillis()
        if (queue.lastCaptureAtEpochMillis?.let { now - it < CAPTURE_INTERVAL_MILLIS } == true) return CaptureResult.NOT_DUE
        if (queue.entries.size >= MAX_QUEUE_ENTRIES) return CaptureResult.FULL
        val request = collector.capture(profile, Instant.ofEpochMilli(now))
        var result = CaptureResult.NOT_DUE
        queueStore.update(requireNotNull(profile.id)) { current ->
            when {
                current.lastCaptureAtEpochMillis?.let { now - it < CAPTURE_INTERVAL_MILLIS } == true -> current
                current.entries.size >= MAX_QUEUE_ENTRIES -> current.also { result = CaptureResult.FULL }
                else -> current.copy(
                    entries = (current.entries + PendingUsageSnapshot(profile.id, request)).sortedBy { Instant.parse(it.request.capturedAt) },
                    lastCaptureAtEpochMillis = now,
                    lastSnapshot = request,
                ).also { result = CaptureResult.ADDED }
            }
        }
        return result
    }

    private suspend fun unblockForNewCredential(profileId: String, fingerprint: String) {
        queueStore.update(profileId) { queue -> queue.copy(entries = queue.entries.map { entry ->
            if (entry.state == UsageQueueEntryState.AUTHORIZATION_BLOCKED && entry.blockedCredentialFingerprint != fingerprint) {
                entry.copy(state = UsageQueueEntryState.PENDING, authorizationBlockReason = null, blockedCredentialFingerprint = null)
            } else entry
        }) }
    }

    private suspend fun markAttempt(profileId: String, entry: PendingUsageSnapshot) {
        queueStore.update(profileId) { queue -> queue.replace(entry.request.snapshotId) { it.copy(attemptCount = it.attemptCount + 1, lastAttemptAtEpochMillis = nowEpochMillis()) } }
    }

    private suspend fun removeEntry(profileId: String, snapshotId: String) {
        queueStore.update(profileId) { it.copy(entries = it.entries.filterNot { entry -> entry.request.snapshotId == snapshotId }) }
    }

    private suspend fun quarantine(profileId: String, snapshotId: String, code: UsageConflictCode) {
        queueStore.update(profileId) { queue -> queue.replace(snapshotId) { it.copy(state = UsageQueueEntryState.QUARANTINED, conflictCode = code) } }
    }

    private suspend fun blockAuthorization(profileId: String, fingerprint: String?, reason: UsageAuthorizationBlockReason) {
        queueStore.update(profileId) { queue -> queue.copy(entries = queue.entries.map { entry ->
            if (entry.state == UsageQueueEntryState.PENDING) entry.copy(state = UsageQueueEntryState.AUTHORIZATION_BLOCKED, authorizationBlockReason = reason, blockedCredentialFingerprint = fingerprint) else entry
        }) }
    }

    private suspend fun outcome(
        status: UsageSyncStatus,
        profileId: String?,
        message: String?,
        authorizationReason: UsageAuthorizationBlockReason? = null,
        overview: UsageOverview? = null,
    ): UsageSyncOutcome {
        val queue = profileId?.let { queueStore.load(it) } ?: UsageSnapshotQueue()
        val base = overview ?: queue.lastSnapshot?.toOverview() ?: unavailableOverview(message ?: "Todavía no hay métricas disponibles.")
        return UsageSyncOutcome(status, if (message == null) base else base.withMessage(message), queue.pendingCount, queue.quarantinedCount, authorizationReason)
    }

    private fun authorizationReason(exception: HttpException): UsageAuthorizationBlockReason {
        if (exception.code() == 401) return UsageAuthorizationBlockReason.EXPIRED_OR_INVALID
        val body = errorBody(exception).lowercase()
        return when {
            "monitoring" in body || "consent" in body -> UsageAuthorizationBlockReason.MONITORING_NOT_AUTHORIZED
            "inactive" in body || "disabled" in body || "device" in body -> UsageAuthorizationBlockReason.DEVICE_DISABLED
            else -> UsageAuthorizationBlockReason.FORBIDDEN
        }
    }

    private fun conflictCode(exception: HttpException): UsageConflictCode {
        val body = errorBody(exception).lowercase()
        return when {
            "stale_snapshot" in body -> UsageConflictCode.STALE_SNAPSHOT
            "ambiguous_order" in body -> UsageConflictCode.AMBIGUOUS_ORDER
            else -> UsageConflictCode.SNAPSHOT_CONFLICT
        }
    }

    private fun errorBody(exception: HttpException) = runCatching { exception.response()?.errorBody()?.string().orEmpty() }.getOrDefault("")
    private fun authorizationMessage(reason: UsageAuthorizationBlockReason?) = when (reason) {
        UsageAuthorizationBlockReason.MISSING_CREDENTIAL -> "El dispositivo debe volver a vincularse."
        UsageAuthorizationBlockReason.EXPIRED_OR_INVALID -> "La credencial del dispositivo venció o dejó de ser válida."
        UsageAuthorizationBlockReason.DEVICE_DISABLED -> "El dispositivo fue desactivado por el servidor."
        UsageAuthorizationBlockReason.MONITORING_NOT_AUTHORIZED -> "La autorización de monitoreo no está activa."
        else -> "La sincronización automática está pausada por autorización."
    }

    private enum class CaptureResult { ADDED, NOT_DUE, FULL }
    companion object {
        const val MAX_QUEUE_ENTRIES = 48
        const val CAPTURE_INTERVAL_MILLIS = 60 * 60 * 1_000L
    }
}

private fun UsageSnapshotQueue.replace(snapshotId: String, transform: (PendingUsageSnapshot) -> PendingUsageSnapshot) =
    copy(entries = entries.map { if (it.request.snapshotId == snapshotId) transform(it) else it })

private fun credentialFingerprint(token: String): String = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean = generateSequence(this) { it.cause }.any { it is T }

class DeviceUsageRepository(private val coordinator: UsageSyncCoordinator) : UsageRepository {
    private var latest: UsageOverview? = null
    override suspend fun overview(period: DashboardPeriod): UsageOverview = coordinator.synchronize().overview.also { latest = it }
    override suspend fun daily(period: DashboardPeriod): DailyUsage = latest?.usage ?: overview(period).usage
    override suspend fun apps(): List<AppUsage> = latest?.applications.orEmpty()
    override suspend fun recommendation() = Recommendation("", "", "")
}

class AdultUsageRepository(
    private val profileStore: ProfileLocalStore,
    private val api: HabitsApi,
    private val authorization: RequestAuthorization,
    private val currentDate: () -> LocalDate = LocalDate::now,
) : UsageRepository {
    private var latest: UsageOverview? = null
    override suspend fun overview(period: DashboardPeriod): UsageOverview {
        val profileId = profileStore.profile.first()?.id ?: return unavailableOverview("Seleccioná un perfil vinculado.")
        val end = currentDate()
        val start = if (period == DashboardPeriod.WEEK) end.minusDays(6) else end
        val records = authorization.execute { token -> api.getUsageRange(profileId, token, start.toString(), end.toString()) }
        return aggregate(records, if (period == DashboardPeriod.WEEK) 7 else 1).also { latest = it }
    }
    override suspend fun daily(period: DashboardPeriod): DailyUsage = latest?.usage ?: overview(period).usage
    override suspend fun apps(): List<AppUsage> = latest?.applications.orEmpty()
    override suspend fun recommendation() = Recommendation("", "", "")
}

internal fun aggregate(records: List<DailyUsageReadDto>, expectedDays: Int): UsageOverview {
    if (records.isEmpty()) return unavailableOverview("Todavía no hay mediciones para el período seleccionado.")
    val periodsUniform = records.all(DailyUsageReadDto::periodsUniform)
    fun status(values: List<String>): UsageMetricStatus {
        val combined = combineStatus(values, records.size == expectedDays)
        return if (!periodsUniform && combined == UsageMetricStatus.MEASURED) UsageMetricStatus.INCOMPLETE else combined
    }
    val statuses = UsageMetricStatuses(
        status(records.map { it.metricStatuses.totalUsageMinutes }), status(records.map { it.metricStatuses.sessionCount }),
        status(records.map { it.metricStatuses.nightUsageMinutes }), status(records.map { it.metricStatuses.maxSessionMinutes }),
        status(records.map { it.metricStatuses.unlockCount }), status(records.map { it.metricStatuses.applications }),
    )
    val incomplete = listOf(statuses.totalUsageMinutes, statuses.sessionCount, statuses.nightUsageMinutes, statuses.maxSessionMinutes, statuses.unlockCount, statuses.applications).any { it == UsageMetricStatus.INCOMPLETE } || !periodsUniform
    val apps = records.flatMap { it.applications.orEmpty() }.groupBy(AppUsageReadDto::packageName).map { (packageName, values) ->
        AppUsage(values.mapNotNull(AppUsageReadDto::appName).firstOrNull() ?: packageName, packageName, values.sumOf(AppUsageReadDto::usageMinutes), values.mapNotNull(AppUsageReadDto::category).distinct().singleOrNull())
    }.sortedByDescending(AppUsage::minutes).takeIf { records.any { it.applications != null } }
    val windows = records.mapNotNull(DailyUsageReadDto::nightWindow).distinct()
    return UsageOverview(
        DailyUsage(
            sumAvailable(records.map(DailyUsageReadDto::totalUsageMinutes)), null,
            sumAvailable(records.map(DailyUsageReadDto::sessionCount)), sumAvailable(records.map(DailyUsageReadDto::nightUsageMinutes)),
            records.mapNotNull(DailyUsageReadDto::maxSessionMinutes).maxOrNull(), sumAvailable(records.map(DailyUsageReadDto::unlockCount)),
            statuses, if (records.any { it.coverageStatus == "PARTIAL" }) UsageCoverageStatus.PARTIAL else UsageCoverageStatus.UNKNOWN,
            periodsUniform, records.sumOf(DailyUsageReadDto::sourceCount), when {
                !periodsUniform -> "Las fuentes tienen períodos o métodos incompatibles; se muestra su detalle sin asumir uniformidad."
                records.size < expectedDays -> "Faltan mediciones para completar el período."
                incomplete -> "La medición del período es parcial."
                else -> null
            },
        ), apps, statuses.applications, windows.singleOrNull()?.startLocalTime, windows.singleOrNull()?.endLocalTime, records.size,
    )
}

internal fun UsageSnapshotRequest.toOverview(message: String? = null): UsageOverview = UsageOverview(
    DailyUsage(totalUsageMinutes, null, sessionCount, nightUsageMinutes, maxSessionMinutes, unlockCount, metricStatuses.toDomain(), coverageStatus.toCoverage(), informationMessage = message),
    applications?.map { AppUsage(it.appName ?: it.packageName, it.packageName, it.usageMinutes, null) },
    metricStatuses.applications.toMetricStatus(), nightWindow?.startLocalTime, nightWindow?.endLocalTime,
)

internal fun DailyUsageReadDto.toOverview() = aggregate(listOf(this), 1)
private fun UsageMetricStatusesDto.toDomain() = UsageMetricStatuses(totalUsageMinutes.toMetricStatus(), sessionCount.toMetricStatus(), nightUsageMinutes.toMetricStatus(), maxSessionMinutes.toMetricStatus(), unlockCount.toMetricStatus(), applications.toMetricStatus())
private fun String.toMetricStatus() = runCatching { UsageMetricStatus.valueOf(this) }.getOrDefault(UsageMetricStatus.UNAVAILABLE)
private fun String.toCoverage() = runCatching { UsageCoverageStatus.valueOf(this) }.getOrDefault(UsageCoverageStatus.UNKNOWN)
private fun sumAvailable(values: List<Int?>): Int? = values.filterNotNull().takeIf(List<Int>::isNotEmpty)?.sum()
private fun combineStatus(statuses: List<String>, complete: Boolean): UsageMetricStatus {
    val values = statuses.map(String::toMetricStatus)
    if (values.isEmpty()) return UsageMetricStatus.UNAVAILABLE
    if (!complete || values.any { it == UsageMetricStatus.INCOMPLETE }) return UsageMetricStatus.INCOMPLETE
    return if (values.distinct().size == 1) values.first() else UsageMetricStatus.INCOMPLETE
}

internal fun unavailableOverview(message: String) = UsageOverview(DailyUsage(null, null, null, informationMessage = message), null, UsageMetricStatus.UNAVAILABLE, recordCount = 0)
private fun UsageOverview.withMessage(message: String) = copy(usage = usage.copy(informationMessage = message))
