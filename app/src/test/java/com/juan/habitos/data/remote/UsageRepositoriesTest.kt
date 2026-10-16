package com.juan.habitos.data.remote

import com.juan.habitos.data.device.UsageSnapshotCollector
import com.juan.habitos.domain.model.DashboardPeriod
import com.juan.habitos.domain.model.Profile
import com.juan.habitos.domain.repository.ProfileLocalStore
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType
import okhttp3.ResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

private class UsageTestProfileStore(profile: Profile?) : ProfileLocalStore {
    override val profile = MutableStateFlow(profile)
    override val onboardingComplete = MutableStateFlow(true)
    override suspend fun save(profile: Profile) { this.profile.value = profile }
    override suspend fun completeOnboarding() = Unit
}

private class MemoryPendingUsageStore : PendingUsageSnapshotStore {
    var value: PendingUsageSnapshot? = null
    var saves = 0
    override suspend fun load(profileId: String) = value?.takeIf { it.profileId == profileId }
    override suspend fun save(snapshot: PendingUsageSnapshot) { value = snapshot; saves++ }
    override suspend fun clear(profileId: String) { if (value?.profileId == profileId) value = null }
}

private class FixedUsageAuthorization(override val principal: RequestPrincipal) : RequestAuthorization {
    val tokens = mutableListOf<String>()
    override suspend fun <T> execute(request: suspend (authorization: String) -> T): T {
        val token = if (principal == RequestPrincipal.DEVICE) "Bearer device-jwt" else "Bearer adult-jwt"
        tokens += token
        return request(token)
    }
}

@Suppress("UNCHECKED_CAST")
private fun usageApi(handler: (String, Array<out Any?>) -> Any?): HabitsApi = Proxy.newProxyInstance(
    HabitsApi::class.java.classLoader,
    arrayOf(HabitsApi::class.java),
) { _, method, arguments -> handler(method.name, arguments.orEmpty()) } as HabitsApi

private fun request(id: String = "snapshot-1") = UsageSnapshotRequest(
    snapshotId = id,
    capturedAt = "2026-10-16T17:00:00-03:00",
    date = "2026-10-16",
    periodStartAt = "2026-10-16T00:00:00-03:00",
    periodEndAt = "2026-10-16T17:00:00-03:00",
    timezone = "America/Argentina/Buenos_Aires",
    coverageStatus = "UNKNOWN",
    totalUsageMinutes = 120,
    sessionCount = 4,
    nightUsageMinutes = 10,
    maxSessionMinutes = 40,
    unlockCount = 5,
    applications = listOf(AppUsageCreateDto("com.video", "Video", null, 60)),
    metricStatuses = statuses(),
    nightWindow = NightWindowDto(startLocalTime = "22:00", endLocalTime = "07:00"),
)

private fun statuses(value: String = "MEASURED") = UsageMetricStatusesDto(value, value, value, value, value, value)

private fun read(
    profileId: String = "profile-1",
    date: String = "2026-10-16",
    total: Int? = 120,
    sessions: Int? = 4,
    night: Int? = 10,
    maxSession: Int? = 40,
    unlocks: Int? = 5,
    apps: List<AppUsageReadDto>? = listOf(AppUsageReadDto(packageName = "com.video", appName = "Video", usageMinutes = 60)),
    metricStatuses: UsageMetricStatusesDto = statuses(),
    periodsUniform: Boolean = true,
) = DailyUsageReadDto(
    id = "daily-$date",
    profileId = profileId,
    date = date,
    totalUsageMinutes = total,
    sessionCount = sessions,
    nightUsageMinutes = night,
    maxSessionMinutes = maxSession,
    unlockCount = unlocks,
    applications = apps,
    metricStatuses = metricStatuses,
    coverageStatus = "UNKNOWN",
    periodStartAt = "${date}T03:00:00Z",
    periodEndAt = "${date}T20:00:00Z",
    timezone = "America/Argentina/Buenos_Aires",
    durationUnit = "MINUTES",
    measurementMethod = "ANDROID_USAGE_STATS_AGGREGATES",
    calculationVersion = "android-collector-1.0",
    nightWindow = NightWindowDto(startLocalTime = "22:00", endLocalTime = "07:00"),
    periodsUniform = periodsUniform,
    sourceCount = 1,
    createdAt = "2026-10-16T20:00:00Z",
    updatedAt = "2026-10-16T20:00:00Z",
)

private fun httpFailure(code: Int, detail: String = "error") = HttpException(
    Response.error<Unit>(code, ResponseBody.create(MediaType.parse("application/json"), "{\"detail\":\"$detail\"}"))
)

class UsageRepositoriesTest {
    @Test fun `retry preserves snapshot id and exact body after server failure`() = runTest {
        val sent = mutableListOf<UsageSnapshotRequest>()
        var calls = 0
        val api = usageApi { name, args ->
            if (name == "postUsageSnapshot") {
                sent += args[2] as UsageSnapshotRequest
                calls++
                if (calls == 1) throw httpFailure(503)
                UsageWriteResultDto("created", read())
            } else error("Unexpected $name")
        }
        var captures = 0
        val collector = UsageSnapshotCollector { _, _: Instant -> captures++; request() }
        val pending = MemoryPendingUsageStore()
        val repository = DeviceUsageRepository(
            UsageTestProfileStore(Profile("Juana", "fox", 9, id = "profile-1")),
            collector,
            api,
            FixedUsageAuthorization(RequestPrincipal.DEVICE),
            pending,
        )

        val offline = repository.overview()
        val synced = repository.overview()

        assertEquals("Los datos están guardados y pendientes de sincronización.", offline.usage.informationMessage)
        assertNull(synced.usage.informationMessage)
        assertEquals(1, captures)
        assertEquals(2, sent.size)
        assertEquals(sent[0], sent[1])
        assertEquals("snapshot-1", sent[1].snapshotId)
        assertNull(pending.value)
    }

    @Test fun `concurrent refreshes cannot send captures out of order`() = runTest {
        val sent = mutableListOf<String>()
        val api = usageApi { name, args ->
            if (name == "postUsageSnapshot") {
                sent += (args[2] as UsageSnapshotRequest).snapshotId
                UsageWriteResultDto("created", read())
            } else error("Unexpected $name")
        }
        var captures = 0
        val repository = DeviceUsageRepository(
            UsageTestProfileStore(Profile("Juana", "fox", 9, id = "profile-1")),
            UsageSnapshotCollector { _, _ -> request("snapshot-${++captures}") },
            api,
            FixedUsageAuthorization(RequestPrincipal.DEVICE),
            MemoryPendingUsageStore(),
        )

        val first = async { repository.overview() }
        val second = async { repository.overview() }
        first.await(); second.await()

        assertEquals(listOf("snapshot-1", "snapshot-2"), sent)
    }

    @Test fun `stale snapshot conflict is terminal and removes pending payload`() = runTest {
        val pending = MemoryPendingUsageStore()
        val api = usageApi { name, _ -> if (name == "postUsageSnapshot") throw httpFailure(409, "stale_snapshot") else error("Unexpected $name") }
        val repository = DeviceUsageRepository(
            UsageTestProfileStore(Profile("Juana", "fox", 9, id = "profile-1")),
            UsageSnapshotCollector { _, _ -> request() },
            api,
            FixedUsageAuthorization(RequestPrincipal.DEVICE),
            pending,
        )

        try { repository.overview(); fail("Expected stale conflict") }
        catch (exception: UsageSyncException) { assertEquals(UsageSyncFailureReason.STALE_SNAPSHOT, exception.reason) }
        assertNull(pending.value)
    }

    @Test fun `authorization failure pauses automatic retries and keeps exact encrypted-store candidate`() = runTest {
        var calls = 0
        var captures = 0
        val pending = MemoryPendingUsageStore()
        val api = usageApi { name, _ ->
            if (name == "postUsageSnapshot") { calls++; throw httpFailure(403) }
            else error("Unexpected $name")
        }
        val repository = DeviceUsageRepository(
            UsageTestProfileStore(Profile("Juana", "fox", 9, id = "profile-1")),
            UsageSnapshotCollector { _, _ -> captures++; request() },
            api,
            FixedUsageAuthorization(RequestPrincipal.DEVICE),
            pending,
        )

        repository.overview()
        repository.overview()

        assertEquals(1, calls)
        assertEquals(1, captures)
        assertTrue(pending.value!!.authorizationBlocked)
        assertEquals("snapshot-1", pending.value!!.request.snapshotId)
    }

    @Test fun `adult reads authorized selected profile and aggregates cumulative days`() = runTest {
        val calls = mutableListOf<List<Any?>>()
        val api = usageApi { name, args ->
            if (name == "getUsageRange") {
                calls += args.take(4)
                listOf(
                    read(date = "2026-10-15", total = 60, sessions = 2, maxSession = 20, apps = listOf(AppUsageReadDto(packageName = "com.video", appName = "Video", usageMinutes = 30))),
                    read(date = "2026-10-16", total = 120, sessions = 4, maxSession = 40, apps = listOf(AppUsageReadDto(packageName = "com.video", appName = "Video", usageMinutes = 60))),
                )
            } else error("Unexpected $name")
        }
        val profiles = UsageTestProfileStore(Profile("Juana", "fox", 9, id = "profile-1"))
        val authorization = FixedUsageAuthorization(RequestPrincipal.ADULT)
        val repository = AdultUsageRepository(profiles, api, authorization) { LocalDate.of(2026, 10, 16) }

        val overview = repository.overview(DashboardPeriod.WEEK)

        assertEquals("profile-1", calls.single()[0])
        assertEquals("Bearer adult-jwt", calls.single()[1])
        assertEquals("2026-10-10", calls.single()[2])
        assertEquals("2026-10-16", calls.single()[3])
        assertEquals(180, overview.usage.currentMinutes)
        assertEquals(6, overview.usage.sessionCount)
        assertEquals(40, overview.usage.maxSessionMinutes)
        assertEquals(90, overview.applications?.single()?.minutes)
        assertEquals("Faltan mediciones para completar el período.", overview.usage.informationMessage)

        profiles.profile.value = Profile("Otro", "owl", 10, id = "profile-2")
        repository.overview(DashboardPeriod.TODAY)
        assertEquals("profile-2", calls.last()[0])
    }

    @Test fun `non uniform backend periods are surfaced as incomplete`() {
        val overview = aggregate(listOf(read(periodsUniform = false)), expectedDays = 1)
        assertFalse(overview.usage.periodsUniform)
        assertEquals(com.juan.habitos.domain.model.UsageMetricStatus.INCOMPLETE, overview.usage.metricStatuses.totalUsageMinutes)
        assertTrue(overview.usage.informationMessage!!.contains("incompatibles"))
    }
}
