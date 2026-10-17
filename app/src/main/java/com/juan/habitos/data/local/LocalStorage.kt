package com.juan.habitos.data.local

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.juan.habitos.domain.model.Profile
import com.juan.habitos.domain.model.BedtimeRange
import com.juan.habitos.domain.model.SchoolSchedule
import com.juan.habitos.domain.model.*
import com.juan.habitos.domain.repository.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.util.Base64
import java.util.UUID

private val Context.dataStore by preferencesDataStore("settings")

internal object ProfilePreferenceKeys {
    val id = stringPreferencesKey("profile_id")
    val name = stringPreferencesKey("name")
    val avatar = stringPreferencesKey("avatar")
    val age = intPreferencesKey("age")
    val active = booleanPreferencesKey("profile_active")
    val schoolSchedules = stringSetPreferencesKey("school_schedules")
    val bedtime = stringPreferencesKey("bedtime")
    val schoolStart = stringPreferencesKey("school_start")
    val schoolEnd = stringPreferencesKey("school_end")
    val sleepStart = stringPreferencesKey("sleep_start")
    val sleepEnd = stringPreferencesKey("sleep_end")
    val done = booleanPreferencesKey("onboarding_done")
}

internal fun Preferences.toStoredProfile(): Profile? = this[ProfilePreferenceKeys.name]?.let { name ->
    Profile(
        name = name,
        avatar = this[ProfilePreferenceKeys.avatar] ?: "🦊",
        age = this[ProfilePreferenceKeys.age] ?: 9,
        schoolSchedules = this[ProfilePreferenceKeys.schoolSchedules].orEmpty().mapNotNull { value -> SchoolSchedule.entries.find { it.name == value } }.toSet(),
        bedtime = this[ProfilePreferenceKeys.bedtime]?.let { value -> BedtimeRange.entries.find { it.name == value } },
        schoolStart = this[ProfilePreferenceKeys.schoolStart],
        schoolEnd = this[ProfilePreferenceKeys.schoolEnd],
        sleepStart = this[ProfilePreferenceKeys.sleepStart],
        sleepEnd = this[ProfilePreferenceKeys.sleepEnd],
        id = this[ProfilePreferenceKeys.id],
        active = this[ProfilePreferenceKeys.active] ?: true,
    )
}

internal fun MutablePreferences.storeProfile(profile: Profile) {
    this[ProfilePreferenceKeys.name] = profile.name
    this[ProfilePreferenceKeys.avatar] = profile.avatar
    this[ProfilePreferenceKeys.age] = profile.age
    this[ProfilePreferenceKeys.active] = profile.active
    this[ProfilePreferenceKeys.schoolSchedules] = profile.schoolSchedules.map { it.name }.toSet()
    profile.id?.let { this[ProfilePreferenceKeys.id] = it } ?: remove(ProfilePreferenceKeys.id)
    profile.bedtime?.let { this[ProfilePreferenceKeys.bedtime] = it.name } ?: remove(ProfilePreferenceKeys.bedtime)
    profile.schoolStart?.let { this[ProfilePreferenceKeys.schoolStart] = it } ?: remove(ProfilePreferenceKeys.schoolStart)
    profile.schoolEnd?.let { this[ProfilePreferenceKeys.schoolEnd] = it } ?: remove(ProfilePreferenceKeys.schoolEnd)
    profile.sleepStart?.let { this[ProfilePreferenceKeys.sleepStart] = it } ?: remove(ProfilePreferenceKeys.sleepStart)
    profile.sleepEnd?.let { this[ProfilePreferenceKeys.sleepEnd] = it } ?: remove(ProfilePreferenceKeys.sleepEnd)
}

class DataStoreProfileRepository(private val context: Context) : ProfileLocalStore {
    override val profile = context.dataStore.data.map(Preferences::toStoredProfile)
    override val onboardingComplete = context.dataStore.data.map { it[ProfilePreferenceKeys.done] ?: false }
    override suspend fun save(profile: Profile) { context.dataStore.edit { it.storeProfile(profile) } }
    override suspend fun completeOnboarding() { context.dataStore.edit { it[ProfilePreferenceKeys.done] = true } }
}

class DataStoreChildPreferencesRepository(private val context: Context) : ChildPreferencesLocalStore {
    private object Keys {
        val interests = stringSetPreferencesKey("child_preferences_interests")
        val considerationsAnswered = booleanPreferencesKey("child_preferences_considerations_answered")
        val considerations = stringSetPreferencesKey("child_preferences_considerations")
        val restrictions = stringSetPreferencesKey("child_preferences_restrictions")
        val notes = stringPreferencesKey("child_preferences_notes")
    }
    override val preferences = context.dataStore.data.map { values ->
        val restrictions = values[Keys.restrictions].orEmpty().mapNotNull { name -> ActivityRestriction.entries.find { it.name == name } }.toSet()
        ChildPreferences(
            interests = values[Keys.interests].orEmpty().mapNotNull { name -> ChildInterest.entries.find { it.name == name } }.toSet(),
            considerationsAnswered = values[Keys.considerationsAnswered] ?: false,
            considerations = values[Keys.considerations].orEmpty().mapNotNull(::decodeConsiderationPreference).toMap(),
            activitiesToAvoid = restrictions.ifEmpty { setOf(ActivityRestriction.NONE) },
            additionalNotes = values[Keys.notes],
        )
    }
    override suspend fun save(preferences: ChildPreferences) { context.dataStore.edit { values ->
        values[Keys.interests] = preferences.interests.map { it.name }.toSet()
        values[Keys.considerationsAnswered] = preferences.considerationsAnswered
        values[Keys.considerations] = preferences.considerations.map { (type, preference) ->
            listOf(type.name, preference.durationType.name, preference.validUntil?.toString().orEmpty()).joinToString("|")
        }.toSet()
        values[Keys.restrictions] = preferences.activitiesToAvoid.map { it.name }.toSet()
        preferences.additionalNotes?.takeIf { it.isNotBlank() }?.let { values[Keys.notes] = it } ?: values.remove(Keys.notes)
    } }

    private fun decodeConsiderationPreference(value: String): Pair<ChildConsideration, ConsiderationPreference>? {
        val parts = value.split('|')
        if (parts.size != 3) return null
        val type = ChildConsideration.entries.find { it.name == parts[0] } ?: return null
        val duration = ConsiderationDuration.entries.find { it.name == parts[1] } ?: return null
        val validUntil = parts[2].takeIf(String::isNotEmpty)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        return type to ConsiderationPreference(duration, validUntil)
    }
}

class DataStoreProfileContextStore(private val context: Context) : ProfileContextLocalStore {
    private val contextsKey = stringPreferencesKey("profile_contexts_json")
    private val gson = Gson()
    private val storedListType = object : TypeToken<List<StoredProfileContext>>() {}.type

    override val contexts = context.dataStore.data.map { values ->
        values[contextsKey]?.let(::decodeContexts).orEmpty()
    }

    override suspend fun save(contexts: List<ProfileContext>) {
        val serialized = contexts.map(ProfileContext::toStoredContext)
        context.dataStore.edit { values -> values[contextsKey] = gson.toJson(serialized) }
    }

    private fun decodeContexts(value: String): List<ProfileContext> = runCatching {
        val stored: List<StoredProfileContext> = gson.fromJson(value, storedListType) ?: emptyList()
        stored.map(StoredProfileContext::toDomainContext)
    }.getOrElse { emptyList() }
}

private data class StoredProfileContext(
    val id: String?,
    val type: String,
    val startDate: String,
    val endDate: String?,
    val description: String?,
)

private fun ProfileContext.toStoredContext() = StoredProfileContext(
    id = id,
    type = when (type) {
        ProfileContextType.VACATION -> "VACATION"
        ProfileContextType.ILLNESS -> "ILLNESS"
        ProfileContextType.SCHOOL_ACTIVITY -> "SCHOOL_ACTIVITY"
        ProfileContextType.ROUTINE_CHANGE -> "ROUTINE_CHANGE"
        ProfileContextType.OTHER -> "OTHER"
    },
    startDate = startDate.toString(),
    endDate = endDate?.toString(),
    description = description,
)

private fun StoredProfileContext.toDomainContext() = ProfileContext(
    id = id,
    type = when (type) {
        "VACATION" -> ProfileContextType.VACATION
        "ILLNESS" -> ProfileContextType.ILLNESS
        "SCHOOL_ACTIVITY" -> ProfileContextType.SCHOOL_ACTIVITY
        "ROUTINE_CHANGE" -> ProfileContextType.ROUTINE_CHANGE
        "OTHER" -> ProfileContextType.OTHER
        else -> error("Unknown cached profile context type: $type")
    },
    startDate = LocalDate.parse(startDate),
    endDate = endDate?.let(LocalDate::parse),
    description = description,
)

internal fun modeAfterAdultAuthentication(currentMode: DeviceMode?): DeviceMode =
    if (currentMode == DeviceMode.CHILD) DeviceMode.CHILD else DeviceMode.ADULT

class DataStoreDeviceSessionRepository(private val context: Context) : DeviceSessionRepository {
    private object Keys { val mode = stringPreferencesKey("device_mode"); val email = stringPreferencesKey("adult_email"); val authenticated = booleanPreferencesKey("adult_authenticated"); val linkedProfiles = stringSetPreferencesKey("adult_linked_profiles"); val accessToken = stringPreferencesKey("adult_access_token") }
    override val session = context.dataStore.data.map { values -> DeviceSession(values[Keys.mode]?.let { name -> DeviceMode.entries.find { it.name == name } }, values[Keys.email], values[Keys.authenticated] ?: false, values[Keys.linkedProfiles].orEmpty()) }
    override val adultAccessToken = context.dataStore.data.map { values -> values[Keys.accessToken] }
    override suspend fun setMode(mode: DeviceMode) { context.dataStore.edit { it[Keys.mode] = mode.name } }
    override suspend fun authenticateAdult(email: String) {
        context.dataStore.edit { preferences ->
            preferences[Keys.email] = email
            preferences[Keys.authenticated] = true
            // An adult can authenticate temporarily on the child's phone. Keeping CHILD here
            // preserves the device identity and prevents the usage worker from being cancelled.
            val currentMode = preferences[Keys.mode]?.let { stored -> DeviceMode.entries.find { it.name == stored } }
            preferences[Keys.mode] = modeAfterAdultAuthentication(currentMode).name
        }
    }
    override suspend fun addLinkedProfile(profileKey: String) { context.dataStore.edit { values -> values[Keys.linkedProfiles] = values[Keys.linkedProfiles].orEmpty() + profileKey } }
    override suspend fun saveAdultAccessToken(token: String) { context.dataStore.edit { it[Keys.accessToken] = token } }
    override suspend fun clearAdultAuthentication() { context.dataStore.edit { values -> values.remove(Keys.accessToken); values[Keys.authenticated] = false } }
}

internal object DeviceIdentityPreferenceKeys {
    val installationId = stringPreferencesKey("device_installation_id")
    val deviceId = stringPreferencesKey("device_id")
    val profileId = stringPreferencesKey("device_profile_id")
    val bootstrapToken = stringPreferencesKey("device_bootstrap_token")
    val bootstrapExpiresAt = stringPreferencesKey("device_bootstrap_expires_at")
    val accessToken = stringPreferencesKey("device_access_token")
}

internal fun Preferences.toStoredDeviceIdentity() = DeviceIdentity(
    installationId = this[DeviceIdentityPreferenceKeys.installationId],
    deviceId = this[DeviceIdentityPreferenceKeys.deviceId],
    profileId = this[DeviceIdentityPreferenceKeys.profileId],
)

internal fun Preferences.toStoredDeviceCredentials() = DeviceCredentials(
    bootstrapToken = this[DeviceIdentityPreferenceKeys.bootstrapToken],
    bootstrapExpiresAt = this[DeviceIdentityPreferenceKeys.bootstrapExpiresAt],
    accessToken = this[DeviceIdentityPreferenceKeys.accessToken],
)

internal fun MutablePreferences.getOrCreateInstallationId(factory: () -> String): String {
    this[DeviceIdentityPreferenceKeys.installationId]?.let { return it }
    return factory().also { this[DeviceIdentityPreferenceKeys.installationId] = it }
}

internal fun MutablePreferences.storeBootstrap(token: String, expiresAt: String) {
    this[DeviceIdentityPreferenceKeys.bootstrapToken] = token
    this[DeviceIdentityPreferenceKeys.bootstrapExpiresAt] = expiresAt
}

internal fun MutablePreferences.clearBootstrap() {
    remove(DeviceIdentityPreferenceKeys.bootstrapToken)
    remove(DeviceIdentityPreferenceKeys.bootstrapExpiresAt)
}

internal fun MutablePreferences.storeAuthenticatedDeviceLink(profileId: String, deviceId: String, accessToken: String) {
    this[DeviceIdentityPreferenceKeys.profileId] = profileId
    this[DeviceIdentityPreferenceKeys.deviceId] = deviceId
    this[DeviceIdentityPreferenceKeys.accessToken] = accessToken
    clearBootstrap()
}

class DataStoreDeviceIdentityStore(
    private val context: Context,
    private val installationIdFactory: () -> String = ::newInstallationId,
) : DeviceIdentityLocalStore {
    override val identity = context.dataStore.data.map(Preferences::toStoredDeviceIdentity)
    override val credentials = context.dataStore.data.map(Preferences::toStoredDeviceCredentials)

    override suspend fun getOrCreateInstallationId(): String {
        var installationId: String? = null
        context.dataStore.edit { values ->
            installationId = values.getOrCreateInstallationId(installationIdFactory)
        }
        return checkNotNull(installationId)
    }

    override suspend fun saveBootstrap(token: String, expiresAt: String) {
        context.dataStore.edit { values -> values.storeBootstrap(token, expiresAt) }
    }

    override suspend fun clearBootstrap() {
        context.dataStore.edit(MutablePreferences::clearBootstrap)
    }

    override suspend fun saveAuthenticatedLink(profileId: String, deviceId: String, accessToken: String) {
        context.dataStore.edit { values -> values.storeAuthenticatedDeviceLink(profileId, deviceId, accessToken) }
    }

    override suspend fun clearDeviceAccessToken() {
        context.dataStore.edit { values -> values.remove(DeviceIdentityPreferenceKeys.accessToken) }
    }
}

internal fun newInstallationId(): String = UUID.randomUUID().toString()

class HashedPinStore(private val context: Context) : PinStore {
    private val saltKey = stringPreferencesKey("pin_salt")
    private val hashKey = stringPreferencesKey("pin_hash")
    override suspend fun save(pin: String) {
        require(pin.matches(Regex("\\d{4}")))
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        context.dataStore.edit { it[saltKey] = Base64.getEncoder().encodeToString(salt); it[hashKey] = hash(pin, salt) }
    }
    override suspend fun verify(pin: String): Boolean {
        val p = context.dataStore.data.first()
        val salt = p[saltKey]?.let(Base64.getDecoder()::decode) ?: return false
        return MessageDigest.isEqual(hash(pin, salt).toByteArray(), (p[hashKey] ?: "").toByteArray())
    }
    private fun hash(pin: String, salt: ByteArray): String = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(salt + pin.toByteArray()))
}

@Entity(tableName = "usage") data class UsageEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val packageName: String, val minutes: Int, val date: String)
@Dao interface UsageDao { @Query("SELECT * FROM usage WHERE date = :date") suspend fun forDate(date: String): List<UsageEntity>; @Insert suspend fun insertAll(items: List<UsageEntity>) }
@Database(entities = [UsageEntity::class], version = 1) abstract class HabitsDatabase : RoomDatabase() { abstract fun usageDao(): UsageDao }
