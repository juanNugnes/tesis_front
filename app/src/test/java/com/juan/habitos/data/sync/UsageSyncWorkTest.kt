package com.juan.habitos.data.sync

import com.juan.habitos.data.device.UsageSnapshotCollector
import com.juan.habitos.data.remote.*
import com.juan.habitos.domain.model.*
import com.juan.habitos.domain.repository.DeviceIdentityLocalStore
import com.juan.habitos.domain.repository.DeviceSessionRepository
import com.juan.habitos.domain.repository.ProfileLocalStore
import java.lang.reflect.Proxy
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

private class WorkProfileStore : ProfileLocalStore {
    override val profile = MutableStateFlow<Profile?>(Profile("Child", "fox", 9, id = "profile-1"))
    override val onboardingComplete = MutableStateFlow(true)
    override suspend fun save(profile: Profile) { this.profile.value = profile }
    override suspend fun completeOnboarding() { onboardingComplete.value = true }
}

private class WorkSessionStore : DeviceSessionRepository {
    override val session = MutableStateFlow(DeviceSession(mode = DeviceMode.CHILD))
    override val adultAccessToken = MutableStateFlow<String?>(null)
    override suspend fun setMode(mode: DeviceMode) { session.value = session.value.copy(mode = mode) }
    override suspend fun authenticateAdult(email: String) = Unit
    override suspend fun addLinkedProfile(profileKey: String) = Unit
    override suspend fun saveAdultAccessToken(token: String) = Unit
    override suspend fun clearAdultAuthentication() = Unit
}

private class WorkIdentityStore : DeviceIdentityLocalStore {
    override val identity = MutableStateFlow(DeviceIdentity("installation", "device-1", "profile-1"))
    override val credentials = MutableStateFlow(DeviceCredentials(accessToken = "token-1"))
    override suspend fun getOrCreateInstallationId() = "installation"
    override suspend fun saveBootstrap(token: String, expiresAt: String) = Unit
    override suspend fun clearBootstrap() = Unit
    override suspend fun saveAuthenticatedLink(profileId: String, deviceId: String, accessToken: String) {
        identity.value = identity.value.copy(profileId = profileId, deviceId = deviceId)
        credentials.value = DeviceCredentials(accessToken = accessToken)
    }
    override suspend fun clearDeviceAccessToken() { credentials.value = DeviceCredentials() }
}

private class WorkQueueStore : PendingUsageSnapshotStore {
    var queue = UsageSnapshotQueue()
    override suspend fun load(profileId: String) = queue
    override suspend fun update(profileId: String, transform: (UsageSnapshotQueue) -> UsageSnapshotQueue) = transform(queue).also { queue = it }
}

private class RecordingScheduler : UsageWorkScheduler {
    var scheduled = 0
    var cancelled = 0
    override fun ensureScheduled() { scheduled++ }
    override fun cancel() { cancelled++ }
}

private class WorkAuthorization : RequestAuthorization {
    override val principal = RequestPrincipal.DEVICE
    override suspend fun <T> execute(request: suspend (authorization: String) -> T) = request("Bearer token")
}

@Suppress("UNCHECKED_CAST")
private fun workApi(handler: (String, Array<out Any?>) -> Any?): HabitsApi = Proxy.newProxyInstance(
    HabitsApi::class.java.classLoader, arrayOf(HabitsApi::class.java),
) { _, method, arguments -> handler(method.name, arguments.orEmpty()) } as HabitsApi

private fun workRequest() = UsageSnapshotRequest(
    snapshotId = "snapshot-1", capturedAt = "2026-10-16T20:00:00Z", date = "2026-10-16",
    periodStartAt = "2026-10-16T03:00:00Z", periodEndAt = "2026-10-16T20:00:00Z",
    timezone = "America/Argentina/Buenos_Aires", coverageStatus = "UNKNOWN",
    totalUsageMinutes = 10, sessionCount = 1, nightUsageMinutes = 0, maxSessionMinutes = 10, unlockCount = 1,
    applications = emptyList(),
    metricStatuses = UsageMetricStatusesDto("MEASURED", "MEASURED", "MEASURED", "MEASURED", "MEASURED", "MEASURED"),
    nightWindow = null,
)

private fun forbidden() = HttpException(
    Response.error<Unit>(403, ResponseBody.create(MediaType.parse("application/json"), "{\"detail\":\"device disabled\"}"))
)

private fun workCoordinator(
    profiles: WorkProfileStore,
    identity: WorkIdentityStore,
    queue: WorkQueueStore,
    api: HabitsApi = workApi { _, _ -> error("not expected") },
) = UsageSyncCoordinator(
    profiles, identity, UsageSnapshotCollector { _, _ -> workRequest() }, { true }, api,
    WorkAuthorization(), queue, { Instant.parse("2026-10-16T20:00:00Z").toEpochMilli() },
)

class UsageSyncWorkTest {
    @Test fun `only eligible child configuration keeps periodic work scheduled`() = runTest {
        val profiles = WorkProfileStore()
        val session = WorkSessionStore()
        val identity = WorkIdentityStore()
        val scheduler = RecordingScheduler()
        var permission = true
        val controller = UsageWorkController(session, profiles, identity, { permission }, scheduler, workCoordinator(profiles, identity, WorkQueueStore()))

        controller.reconcile()
        assertEquals(1, scheduler.scheduled)

        session.setMode(DeviceMode.ADULT)
        controller.reconcile()
        profiles.profile.value = profiles.profile.value!!.copy(id = "profile-2")
        session.setMode(DeviceMode.CHILD)
        controller.reconcile()
        profiles.profile.value = profiles.profile.value!!.copy(id = "profile-1")
        permission = false
        controller.reconcile()

        assertEquals(3, scheduler.cancelled)
        assertEquals(1, scheduler.scheduled)
    }

    @Test fun `authorization pause cancels work and only a new credential permits scheduling`() = runTest {
        val profiles = WorkProfileStore()
        val session = WorkSessionStore()
        val identity = WorkIdentityStore()
        val queue = WorkQueueStore()
        val scheduler = RecordingScheduler()
        val api = workApi { name, _ -> if (name == "postUsageSnapshot") throw forbidden() else error("Unexpected $name") }
        val sync = workCoordinator(profiles, identity, queue, api)
        val controller = UsageWorkController(session, profiles, identity, { true }, scheduler, sync)

        val paused = sync.synchronize()
        assertEquals(UsageAuthorizationBlockReason.DEVICE_DISABLED, paused.authorizationBlockReason)
        controller.reconcile()
        assertEquals(1, scheduler.cancelled)
        assertEquals(0, scheduler.scheduled)

        identity.credentials.value = DeviceCredentials(accessToken = "token-2")
        controller.reconcile()
        assertEquals(1, scheduler.scheduled)
    }
}
