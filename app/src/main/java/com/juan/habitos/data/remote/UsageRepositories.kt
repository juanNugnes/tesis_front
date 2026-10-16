package com.juan.habitos.data.remote

import com.juan.habitos.data.device.UsageSnapshotCollector
import com.juan.habitos.domain.model.AppUsage
import com.juan.habitos.domain.model.DailyUsage
import com.juan.habitos.domain.model.DashboardPeriod
import com.juan.habitos.domain.model.Recommendation
import com.juan.habitos.domain.model.UsageCoverageStatus
import com.juan.habitos.domain.model.UsageMetricStatus
import com.juan.habitos.domain.model.UsageMetricStatuses
import com.juan.habitos.domain.model.UsageOverview
import com.juan.habitos.domain.repository.ProfileLocalStore
import com.juan.habitos.domain.repository.UsageRepository
import com.juan.habitos.domain.repository.DeviceAuthenticationRequiredException
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

data class PendingUsageSnapshot(
    val profileId: String,
    val request: UsageSnapshotRequest,
    val authorizationBlocked: Boolean = false,
)

interface PendingUsageSnapshotStore {
    suspend fun load(profileId: String): PendingUsageSnapshot?
    suspend fun save(snapshot: PendingUsageSnapshot)
    suspend fun clear(profileId: String)
}

enum class UsageSyncFailureReason { SNAPSHOT_CONFLICT, STALE_SNAPSHOT, AMBIGUOUS_ORDER, AUTHORIZATION, SERVER }
class UsageSyncException(val reason: UsageSyncFailureReason, message: String, cause: Throwable? = null) : Exception(message, cause)

class DeviceUsageRepository(
    private val profileStore: ProfileLocalStore,
    private val collector: UsageSnapshotCollector,
    private val api: HabitsApi,
    private val authorization: RequestAuthorization,
    private val pendingStore: PendingUsageSnapshotStore,
) : UsageRepository {
    private val synchronization = Mutex()
    private var latest: UsageOverview? = null

    override suspend fun overview(period: DashboardPeriod): UsageOverview = synchronization.withLock {
        val profile = profileStore.profile.first() ?: return@withLock unavailableOverview("No hay un perfil infantil configurado.")
        val profileId = profile.id ?: return@withLock unavailableOverview("El dispositivo todavía no está vinculado al perfil.")
        val pending = pendingStore.load(profileId) ?: PendingUsageSnapshot(profileId, collector.capture(profile, Instant.now())).also { created ->
            pendingStore.save(created)
        }
        val local = pending.request.toOverview(if (pending.authorizationBlocked) "La sincronización está pausada por autorización." else null)
        if (pending.authorizationBlocked) return@withLock local.also { latest = it }

        val result = try {
            authorization.execute { token -> api.postUsageSnapshot(profileId, token, pending.request) }
        } catch (exception: DeviceAuthenticationRequiredException) {
            pendingStore.save(pending.copy(authorizationBlocked = true))
            return@withLock local.withMessage("La sincronización está pausada: el dispositivo debe volver a vincularse.").also { latest = it }
        } catch (exception: HttpException) {
            when (exception.code()) {
                401, 403 -> {
                    pendingStore.save(pending.copy(authorizationBlocked = true))
                    return@withLock local.withMessage("La sincronización está pausada por autorización.").also { latest = it }
                }
                409 -> {
                    pendingStore.clear(profileId)
                    throw UsageSyncException(conflictReason(exception), "La instantánea fue rechazada por conflicto.", exception)
                }
                in 500..599 -> return@withLock local.withMessage("Los datos están guardados y pendientes de sincronización.").also { latest = it }
                else -> throw exception
            }
        } catch (_: IOException) {
            return@withLock local.withMessage("Los datos están guardados y pendientes de sincronización.").also { latest = it }
        }
        pendingStore.clear(profileId)
        result.usage.toOverview().also { latest = it }
    }

    override suspend fun daily(period: DashboardPeriod): DailyUsage = latest?.usage ?: overview(period).usage
    override suspend fun apps(): List<AppUsage> = latest?.applications.orEmpty()
    override suspend fun recommendation() = Recommendation("", "", "")

    private fun conflictReason(exception: HttpException): UsageSyncFailureReason {
        val body = runCatching { exception.response()?.errorBody()?.string().orEmpty() }.getOrDefault("")
        return when {
            "stale_snapshot" in body -> UsageSyncFailureReason.STALE_SNAPSHOT
            "ambiguous_order" in body -> UsageSyncFailureReason.AMBIGUOUS_ORDER
            else -> UsageSyncFailureReason.SNAPSHOT_CONFLICT
        }
    }
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
        return aggregate(records, expectedDays = if (period == DashboardPeriod.WEEK) 7 else 1).also { latest = it }
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
    val totalStatus = status(records.map { it.metricStatuses.totalUsageMinutes })
    val sessionStatus = status(records.map { it.metricStatuses.sessionCount })
    val nightStatus = status(records.map { it.metricStatuses.nightUsageMinutes })
    val maxStatus = status(records.map { it.metricStatuses.maxSessionMinutes })
    val unlockStatus = status(records.map { it.metricStatuses.unlockCount })
    val applicationsStatus = status(records.map { it.metricStatuses.applications })
    val incomplete = listOf(totalStatus, sessionStatus, nightStatus, maxStatus, unlockStatus, applicationsStatus).any { it == UsageMetricStatus.INCOMPLETE } || !periodsUniform
    val apps = records.flatMap { it.applications.orEmpty() }
        .groupBy(AppUsageReadDto::packageName)
        .map { (packageName, values) ->
            AppUsage(values.mapNotNull(AppUsageReadDto::appName).firstOrNull() ?: packageName, packageName, values.sumOf(AppUsageReadDto::usageMinutes), values.mapNotNull(AppUsageReadDto::category).distinct().singleOrNull())
        }
        .sortedByDescending(AppUsage::minutes)
        .takeIf { records.any { it.applications != null } }
    val windows = records.mapNotNull(DailyUsageReadDto::nightWindow).distinct()
    return UsageOverview(
        usage = DailyUsage(
            currentMinutes = sumAvailable(records.map(DailyUsageReadDto::totalUsageMinutes)),
            expectedMinutes = null,
            sessionCount = sumAvailable(records.map(DailyUsageReadDto::sessionCount)),
            nightUsageMinutes = sumAvailable(records.map(DailyUsageReadDto::nightUsageMinutes)),
            maxSessionMinutes = records.mapNotNull(DailyUsageReadDto::maxSessionMinutes).maxOrNull(),
            unlockCount = sumAvailable(records.map(DailyUsageReadDto::unlockCount)),
            metricStatuses = UsageMetricStatuses(totalStatus, sessionStatus, nightStatus, maxStatus, unlockStatus, applicationsStatus),
            coverageStatus = if (records.any { it.coverageStatus == "PARTIAL" }) UsageCoverageStatus.PARTIAL else UsageCoverageStatus.UNKNOWN,
            periodsUniform = periodsUniform,
            sourceCount = records.sumOf(DailyUsageReadDto::sourceCount),
            informationMessage = when {
                !periodsUniform -> "Las fuentes tienen períodos o métodos incompatibles; se muestra su detalle sin asumir uniformidad."
                records.size < expectedDays -> "Faltan mediciones para completar el período."
                incomplete -> "La medición del período es parcial."
                else -> null
            },
        ),
        applications = apps,
        applicationsStatus = applicationsStatus,
        nightWindowStart = windows.singleOrNull()?.startLocalTime,
        nightWindowEnd = windows.singleOrNull()?.endLocalTime,
        recordCount = records.size,
    )
}

internal fun UsageSnapshotRequest.toOverview(message: String? = null): UsageOverview = UsageOverview(
    usage = DailyUsage(
        currentMinutes = totalUsageMinutes,
        sessionCount = sessionCount,
        nightUsageMinutes = nightUsageMinutes,
        maxSessionMinutes = maxSessionMinutes,
        unlockCount = unlockCount,
        metricStatuses = metricStatuses.toDomain(),
        coverageStatus = coverageStatus.toCoverage(),
        informationMessage = message,
    ),
    applications = applications?.map { AppUsage(it.appName ?: it.packageName, it.packageName, it.usageMinutes, null) },
    applicationsStatus = applicationsStatus(),
    nightWindowStart = nightWindow?.startLocalTime,
    nightWindowEnd = nightWindow?.endLocalTime,
)

internal fun DailyUsageReadDto.toOverview() = aggregate(listOf(this), 1)
private fun UsageSnapshotRequest.applicationsStatus() = metricStatuses.applications.toMetricStatus()
private fun UsageMetricStatusesDto.toDomain() = UsageMetricStatuses(totalUsageMinutes.toMetricStatus(), sessionCount.toMetricStatus(), nightUsageMinutes.toMetricStatus(), maxSessionMinutes.toMetricStatus(), unlockCount.toMetricStatus(), applications.toMetricStatus())
private fun String.toMetricStatus() = runCatching { UsageMetricStatus.valueOf(this) }.getOrDefault(UsageMetricStatus.UNAVAILABLE)
private fun String.toCoverage() = runCatching { UsageCoverageStatus.valueOf(this) }.getOrDefault(UsageCoverageStatus.UNKNOWN)
private fun sumAvailable(values: List<Int?>): Int? = values.filterNotNull().takeIf(List<Int>::isNotEmpty)?.sum()

private fun combineStatus(statuses: List<String>, completePeriod: Boolean): UsageMetricStatus {
    val values = statuses.map(String::toMetricStatus)
    if (values.isEmpty()) return UsageMetricStatus.UNAVAILABLE
    if (!completePeriod || values.any { it == UsageMetricStatus.INCOMPLETE }) return UsageMetricStatus.INCOMPLETE
    return if (values.distinct().size == 1) values.first() else UsageMetricStatus.INCOMPLETE
}

internal fun unavailableOverview(message: String) = UsageOverview(
    usage = DailyUsage(null, null, null, informationMessage = message),
    applications = null,
    applicationsStatus = UsageMetricStatus.UNAVAILABLE,
    recordCount = 0,
)

private fun UsageOverview.withMessage(message: String) = copy(usage = usage.copy(informationMessage = message))
