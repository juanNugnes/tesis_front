package com.juan.habitos.data.remote

import com.juan.habitos.data.device.UsageSnapshotCollector
import com.juan.habitos.domain.model.*
import com.juan.habitos.domain.repository.DeviceCredentialStore
import com.juan.habitos.domain.repository.ProfileLocalStore
import java.io.IOException
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
    override suspend fun completeOnboarding() { onboardingComplete.value = true }
}

private class UsageTestCredentialStore(token: String? = "device-jwt") : DeviceCredentialStore {
    override val credentials = MutableStateFlow(DeviceCredentials(accessToken = token))
    override suspend fun saveBootstrap(token: String, expiresAt: String) = Unit
    override suspend fun clearBootstrap() = Unit
    override suspend fun saveAuthenticatedLink(profileId: String, deviceId: String, accessToken: String) {
        credentials.value = DeviceCredentials(accessToken = accessToken)
    }
    override suspend fun clearDeviceAccessToken() { credentials.value = DeviceCredentials() }
}

private class MemoryPendingUsageStore : PendingUsageSnapshotStore {
    val values = mutableMapOf<String, UsageSnapshotQueue>()
    override suspend fun load(profileId: String) = values[profileId] ?: UsageSnapshotQueue()
    override suspend fun update(profileId: String, transform: (UsageSnapshotQueue) -> UsageSnapshotQueue): UsageSnapshotQueue =
        transform(load(profileId)).also { values[profileId] = it }
}

private class FixedUsageAuthorization(override val principal: RequestPrincipal) : RequestAuthorization {
    override suspend fun <T> execute(request: suspend (authorization: String) -> T): T = request("Bearer device-jwt")
}

@Suppress("UNCHECKED_CAST")
private fun usageApi(handler: (String, Array<out Any?>) -> Any?): HabitsApi = Proxy.newProxyInstance(
    HabitsApi::class.java.classLoader, arrayOf(HabitsApi::class.java),
) { _, method, arguments -> handler(method.name, arguments.orEmpty()) } as HabitsApi

private fun request(id: String = "snapshot-1", capturedAt: String = "2026-10-16T20:00:00Z") = UsageSnapshotRequest(
    snapshotId = id, capturedAt = capturedAt, date = "2026-10-16",
    periodStartAt = "2026-10-16T03:00:00Z", periodEndAt = capturedAt,
    timezone = "America/Argentina/Buenos_Aires", coverageStatus = "UNKNOWN",
    totalUsageMinutes = 120, sessionCount = 4, nightUsageMinutes = 10,
    maxSessionMinutes = 40, unlockCount = 5,
    applications = listOf(AppUsageCreateDto("com.video", "Video", null, 60)),
    metricStatuses = statuses(), nightWindow = NightWindowDto(startLocalTime = "22:00", endLocalTime = "07:00"),
)

private fun statuses(value: String = "MEASURED") = UsageMetricStatusesDto(value, value, value, value, value, value)

private fun read(
    profileId: String = "profile-1", date: String = "2026-10-16", total: Int? = 120,
    sessions: Int? = 4, night: Int? = 10, maxSession: Int? = 40, unlocks: Int? = 5,
    apps: List<AppUsageReadDto>? = listOf(AppUsageReadDto(packageName = "com.video", appName = "Video", usageMinutes = 60)),
    metricStatuses: UsageMetricStatusesDto = statuses(), periodsUniform: Boolean = true,
) = DailyUsageReadDto(
    id = "daily-$date", profileId = profileId, date = date, totalUsageMinutes = total,
    sessionCount = sessions, nightUsageMinutes = night, maxSessionMinutes = maxSession,
    unlockCount = unlocks, applications = apps, metricStatuses = metricStatuses,
    coverageStatus = "UNKNOWN", periodStartAt = "${date}T03:00:00Z", periodEndAt = "${date}T20:00:00Z",
    timezone = "America/Argentina/Buenos_Aires", durationUnit = "MINUTES",
    measurementMethod = "ANDROID_USAGE_STATS_AGGREGATES", calculationVersion = "android-collector-1.0",
    nightWindow = NightWindowDto(startLocalTime = "22:00", endLocalTime = "07:00"), periodsUniform = periodsUniform, sourceCount = 1,
    createdAt = "2026-10-16T20:00:00Z", updatedAt = "2026-10-16T20:00:00Z",
)

private fun httpFailure(code: Int, detail: String = "error") = HttpException(
    Response.error<Unit>(code, ResponseBody.create(MediaType.parse("application/json"), "{\"detail\":\"$detail\"}"))
)

private fun coordinator(
    profiles: UsageTestProfileStore = UsageTestProfileStore(Profile("Juana", "fox", 9, id = "profile-1")),
    credentials: UsageTestCredentialStore = UsageTestCredentialStore(),
    collector: UsageSnapshotCollector,
    api: HabitsApi,
    store: MemoryPendingUsageStore = MemoryPendingUsageStore(),
    now: () -> Long = { Instant.parse("2026-10-16T20:00:00Z").toEpochMilli() },
    permission: () -> Boolean = { true },
    paused: suspend () -> Unit = {},
) = UsageSyncCoordinator(
    profiles, credentials, collector, permission, api,
    FixedUsageAuthorization(RequestPrincipal.DEVICE), store, now, onAutomaticPause = paused,
)

class UsageRepositoriesTest {
    @Test fun `valid adult monitoring authorization resumes only consent-blocked snapshots without changing payloads`() = runTest {
        val store = MemoryPendingUsageStore()
        val consentBlocked = PendingUsageSnapshot(
            "profile-1",
            request("consent-blocked", "2026-10-16T18:00:00Z"),
            state = UsageQueueEntryState.AUTHORIZATION_BLOCKED,
            authorizationBlockReason = UsageAuthorizationBlockReason.MONITORING_NOT_AUTHORIZED,
            blockedCredentialFingerprint = "same-device-token",
            attemptCount = 2,
        )
        val credentialBlocked = PendingUsageSnapshot(
            "profile-1",
            request("credential-blocked", "2026-10-16T19:00:00Z"),
            state = UsageQueueEntryState.AUTHORIZATION_BLOCKED,
            authorizationBlockReason = UsageAuthorizationBlockReason.EXPIRED_OR_INVALID,
            blockedCredentialFingerprint = "expired-token",
        )
        store.values["profile-1"] = UsageSnapshotQueue(entries = listOf(consentBlocked, credentialBlocked))
        val sync = coordinator(
            collector = UsageSnapshotCollector { _, _ -> error("not used") },
            api = usageApi { _, _ -> error("not used") },
            store = store,
        )

        sync.resumeAfterMonitoringAuthorization("profile-1")

        val entries = store.load("profile-1").entries
        assertEquals(listOf("consent-blocked", "credential-blocked"), entries.map { it.request.snapshotId })
        assertEquals(consentBlocked.request, entries[0].request)
        assertEquals(UsageQueueEntryState.PENDING, entries[0].state)
        assertNull(entries[0].authorizationBlockReason)
        assertEquals(2, entries[0].attemptCount)
        assertEquals(credentialBlocked, entries[1])
    }

    @Test fun `retry preserves immutable snapshot id and exact payload`() = runTest {
        val sent = mutableListOf<UsageSnapshotRequest>()
        var calls = 0
        val api = usageApi { name, args ->
            if (name != "postUsageSnapshot") error("Unexpected $name")
            sent += args[2] as UsageSnapshotRequest
            if (++calls == 1) throw IOException("offline")
            UsageWriteResultDto("created", read())
        }
        var captures = 0
        val store = MemoryPendingUsageStore()
        val sync = coordinator(collector = UsageSnapshotCollector { _, _ -> request("snapshot-${++captures}") }, api = api, store = store)

        assertEquals(UsageSyncStatus.RETRYABLE_FAILURE, sync.synchronize().status)
        assertEquals(UsageSyncStatus.SYNCED, sync.synchronize().status)
        assertEquals(1, captures)
        assertEquals(2, sent.size)
        assertEquals(sent[0], sent[1])
        assertTrue(store.load("profile-1").entries.isEmpty())
    }

    @Test fun `FIFO conflicts quarantine only affected snapshots and later entries continue`() = runTest {
        val store = MemoryPendingUsageStore()
        val entries = listOf(
            request("one", "2026-10-16T17:00:00Z"), request("two", "2026-10-16T18:00:00Z"),
            request("three", "2026-10-16T19:00:00Z"), request("four", "2026-10-16T20:00:00Z"),
        ).map { PendingUsageSnapshot("profile-1", it) }
        store.values["profile-1"] = UsageSnapshotQueue(entries, Instant.parse("2026-10-16T20:00:00Z").toEpochMilli(), entries.last().request)
        val sent = mutableListOf<String>()
        val api = usageApi { name, args ->
            if (name != "postUsageSnapshot") error("Unexpected $name")
            when (val id = (args[2] as UsageSnapshotRequest).snapshotId.also(sent::add)) {
                "one" -> throw httpFailure(409, "snapshot_conflict")
                "two" -> throw httpFailure(409, "stale_snapshot")
                "three" -> throw httpFailure(409, "ambiguous_order")
                else -> UsageWriteResultDto("created", read())
            }
        }

        val outcome = coordinator(collector = UsageSnapshotCollector { _, _ -> error("not due") }, api = api, store = store).synchronize()

        assertEquals(listOf("one", "two", "three", "four"), sent)
        assertEquals(3, outcome.quarantinedCount)
        assertEquals(0, outcome.pendingCount)
        assertEquals(
            listOf(UsageConflictCode.SNAPSHOT_CONFLICT, UsageConflictCode.STALE_SNAPSHOT, UsageConflictCode.AMBIGUOUS_ORDER),
            store.load("profile-1").entries.map { it.conflictCode },
        )
    }

    @Test fun `403 pauses retries until a different credential is stored`() = runTest {
        val store = MemoryPendingUsageStore()
        val credentials = UsageTestCredentialStore("old-token")
        var calls = 0
        var pauses = 0
        var reject = true
        val api = usageApi { name, _ ->
            if (name != "postUsageSnapshot") error("Unexpected $name")
            calls++
            if (reject) throw httpFailure(403, "monitoring consent revoked")
            UsageWriteResultDto("created", read())
        }
        val collector = UsageSnapshotCollector { _, _ -> request() }
        coordinator(credentials = credentials, collector = collector, api = api, store = store, paused = { pauses++ }).synchronize()

        val recreated = coordinator(credentials = credentials, collector = collector, api = api, store = store, paused = { pauses++ })
        val stillPaused = recreated.synchronize()
        assertEquals(UsageSyncStatus.AUTHORIZATION_PAUSED, stillPaused.status)
        assertEquals(UsageAuthorizationBlockReason.MONITORING_NOT_AUTHORIZED, stillPaused.authorizationBlockReason)
        assertEquals(1, calls)

        credentials.credentials.value = DeviceCredentials(accessToken = "new-token")
        reject = false
        assertEquals(UsageSyncStatus.SYNCED, recreated.synchronize().status)
        assertEquals(2, calls)
        assertEquals(1, pauses)
        assertTrue(store.load("profile-1").entries.isEmpty())
    }

    @Test fun `401 records expired credential and preserves pending snapshot`() = runTest {
        val store = MemoryPendingUsageStore()
        val outcome = coordinator(
            collector = UsageSnapshotCollector { _, _ -> request() },
            api = usageApi { name, _ -> if (name == "postUsageSnapshot") throw httpFailure(401) else error("Unexpected $name") },
            store = store,
        ).synchronize()

        assertEquals(UsageSyncStatus.AUTHORIZATION_PAUSED, outcome.status)
        assertEquals(UsageAuthorizationBlockReason.EXPIRED_OR_INVALID, outcome.authorizationBlockReason)
        assertEquals("snapshot-1", store.load("profile-1").entries.single().request.snapshotId)
        assertEquals(UsageQueueEntryState.AUTHORIZATION_BLOCKED, store.load("profile-1").entries.single().state)
    }

    @Test fun `prolonged outage fills bounded queue without discarding existing snapshots`() = runTest {
        var now = Instant.parse("2026-10-16T00:00:00Z").toEpochMilli()
        var captures = 0
        val store = MemoryPendingUsageStore()
        val api = usageApi { name, _ -> if (name == "postUsageSnapshot") throw IOException("offline") else error("Unexpected $name") }
        val collector = UsageSnapshotCollector { _, instant -> request("snapshot-${++captures}", instant.toString()) }

        repeat(UsageSyncCoordinator.MAX_QUEUE_ENTRIES) {
            coordinator(collector = collector, api = api, store = store, now = { now }).synchronize()
            now += UsageSyncCoordinator.CAPTURE_INTERVAL_MILLIS
        }
        val before = store.load("profile-1").entries.map { it.request }
        val full = coordinator(collector = collector, api = api, store = store, now = { now }).synchronize()

        assertEquals(UsageSyncStatus.QUEUE_FULL, full.status)
        assertEquals(UsageSyncCoordinator.MAX_QUEUE_ENTRIES, before.size)
        assertEquals(before, store.load("profile-1").entries.map { it.request })
        assertEquals(UsageSyncCoordinator.MAX_QUEUE_ENTRIES, captures)
    }

    @Test fun `shared coordinator serializes simultaneous UI and worker runs`() = runTest {
        val sent = mutableListOf<String>()
        var captures = 0
        val api = usageApi { name, args ->
            if (name != "postUsageSnapshot") error("Unexpected $name")
            sent += (args[2] as UsageSnapshotRequest).snapshotId
            UsageWriteResultDto("created", read())
        }
        val sync = coordinator(collector = UsageSnapshotCollector { _, _ -> request("snapshot-${++captures}") }, api = api)

        val ui = async { sync.synchronize() }
        val worker = async { sync.synchronize() }
        ui.await(); worker.await()

        assertEquals(1, captures)
        assertEquals(listOf("snapshot-1"), sent)
    }

    @Test fun `pending queue survives coordinator recreation and remains isolated by profile`() = runTest {
        val profiles = UsageTestProfileStore(Profile("One", "fox", 9, id = "profile-1"))
        val store = MemoryPendingUsageStore()
        var online = false
        val sentProfiles = mutableListOf<String>()
        val api = usageApi { name, args ->
            if (name != "postUsageSnapshot") error("Unexpected $name")
            if (!online) throw IOException("offline")
            val profileId = args[0] as String
            sentProfiles += profileId
            UsageWriteResultDto("created", read(profileId = profileId))
        }
        val collector = UsageSnapshotCollector { profile, instant -> request("${profile.id}-${instant.toEpochMilli()}", instant.toString()) }
        coordinator(profiles = profiles, collector = collector, api = api, store = store).synchronize()

        profiles.profile.value = Profile("Two", "owl", 10, id = "profile-2")
        online = true
        coordinator(profiles = profiles, collector = collector, api = api, store = store).synchronize()

        assertEquals(1, store.load("profile-1").pendingCount)
        assertTrue(store.load("profile-2").entries.isEmpty())
        assertEquals(listOf("profile-2"), sentProfiles)
    }

    @Test fun `missing permission preserves queue and does not capture or send`() = runTest {
        val store = MemoryPendingUsageStore()
        store.values["profile-1"] = UsageSnapshotQueue(listOf(PendingUsageSnapshot("profile-1", request())))
        var captures = 0
        var calls = 0
        val outcome = coordinator(
            collector = UsageSnapshotCollector { _, _ -> captures++; request() },
            api = usageApi { _, _ -> calls++; error("must not send") }, store = store, permission = { false },
        ).synchronize()

        assertEquals(UsageSyncStatus.PERMISSION_MISSING, outcome.status)
        assertEquals(1, store.load("profile-1").pendingCount)
        assertEquals(0, captures)
        assertEquals(0, calls)
    }

    @Test fun `adult reads selected profile and aggregates cumulative days`() = runTest {
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
        val repository = AdultUsageRepository(profiles, api, FixedUsageAuthorization(RequestPrincipal.ADULT)) { LocalDate.of(2026, 10, 16) }

        val overview = repository.overview(DashboardPeriod.WEEK)

        assertEquals("profile-1", calls.single()[0])
        assertEquals("2026-10-10", calls.single()[2])
        assertEquals(180, overview.usage.currentMinutes)
        assertEquals(6, overview.usage.sessionCount)
        assertEquals(90, overview.applications?.single()?.minutes)
        profiles.profile.value = Profile("Otro", "owl", 10, id = "profile-2")
        repository.overview(DashboardPeriod.TODAY)
        assertEquals("profile-2", calls.last()[0])
    }

    @Test fun `non uniform backend periods are surfaced as incomplete`() {
        val overview = aggregate(listOf(read(periodsUniform = false)), expectedDays = 1)
        assertFalse(overview.usage.periodsUniform)
        assertTrue(overview.usage.informationMessage!!.contains("incompatibles"))
    }
}
