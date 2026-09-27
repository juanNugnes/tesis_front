package com.juan.habitos.data.local

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
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

private val Context.dataStore by preferencesDataStore("settings")

class DataStoreProfileRepository(private val context: Context) : ProfileRepository {
    private object Keys { val name = stringPreferencesKey("name"); val avatar = stringPreferencesKey("avatar"); val age = intPreferencesKey("age"); val schoolSchedules = stringSetPreferencesKey("school_schedules"); val bedtime = stringPreferencesKey("bedtime"); val schoolStart = stringPreferencesKey("school_start"); val schoolEnd = stringPreferencesKey("school_end"); val sleepStart = stringPreferencesKey("sleep_start"); val sleepEnd = stringPreferencesKey("sleep_end"); val done = booleanPreferencesKey("onboarding_done") }
    override val profile = context.dataStore.data.map { p -> p[Keys.name]?.let { Profile(it, p[Keys.avatar] ?: "🦊", p[Keys.age] ?: 9, p[Keys.schoolSchedules].orEmpty().mapNotNull { value -> SchoolSchedule.entries.find { it.name == value } }.toSet(), p[Keys.bedtime]?.let { value -> BedtimeRange.entries.find { it.name == value } }, p[Keys.schoolStart], p[Keys.schoolEnd], p[Keys.sleepStart], p[Keys.sleepEnd]) } }
    override val onboardingComplete = context.dataStore.data.map { it[Keys.done] ?: false }
    override suspend fun save(profile: Profile) { context.dataStore.edit { preferences -> preferences[Keys.name] = profile.name; preferences[Keys.avatar] = profile.avatar; preferences[Keys.age] = profile.age; preferences[Keys.schoolSchedules] = profile.schoolSchedules.map { schedule -> schedule.name }.toSet(); profile.bedtime?.let { preferences[Keys.bedtime] = it.name } ?: preferences.remove(Keys.bedtime); profile.schoolStart?.let { preferences[Keys.schoolStart] = it } ?: preferences.remove(Keys.schoolStart); profile.schoolEnd?.let { preferences[Keys.schoolEnd] = it } ?: preferences.remove(Keys.schoolEnd); profile.sleepStart?.let { preferences[Keys.sleepStart] = it } ?: preferences.remove(Keys.sleepStart); profile.sleepEnd?.let { preferences[Keys.sleepEnd] = it } ?: preferences.remove(Keys.sleepEnd) } }
    override suspend fun completeOnboarding() { context.dataStore.edit { it[Keys.done] = true } }
}

class DataStoreChildPreferencesRepository(private val context: Context) : ChildPreferencesRepository {
    private object Keys {
        val interests = stringSetPreferencesKey("child_preferences_interests")
        val adaptationType = stringPreferencesKey("child_preferences_adaptation_type")
        val considerations = stringSetPreferencesKey("child_preferences_considerations")
        val restrictions = stringSetPreferencesKey("child_preferences_restrictions")
        val notes = stringPreferencesKey("child_preferences_notes")
        val validUntil = longPreferencesKey("child_preferences_valid_until")
    }
    override val preferences = context.dataStore.data.map { values ->
        ChildPreferences(
            interests = values[Keys.interests].orEmpty().mapNotNull { name -> ChildInterest.entries.find { it.name == name } }.toSet(),
            adaptationType = values[Keys.adaptationType]?.let { name -> AdaptationType.entries.find { it.name == name } } ?: AdaptationType.NONE,
            considerations = values[Keys.considerations].orEmpty().mapNotNull { name -> ChildConsideration.entries.find { it.name == name } }.toSet(),
            activitiesToAvoid = values[Keys.restrictions].orEmpty().mapNotNull { name -> ActivityRestriction.entries.find { it.name == name } }.toSet(),
            additionalNotes = values[Keys.notes],
            validUntil = values[Keys.validUntil]?.let(LocalDate::ofEpochDay),
        )
    }
    override suspend fun save(preferences: ChildPreferences) { context.dataStore.edit { values ->
        values[Keys.interests] = preferences.interests.map { it.name }.toSet()
        values[Keys.adaptationType] = preferences.adaptationType.name
        values[Keys.considerations] = preferences.considerations.map { it.name }.toSet()
        values[Keys.restrictions] = preferences.activitiesToAvoid.map { it.name }.toSet()
        preferences.additionalNotes?.takeIf { it.isNotBlank() }?.let { values[Keys.notes] = it } ?: values.remove(Keys.notes)
        preferences.validUntil?.toEpochDay()?.let { values[Keys.validUntil] = it } ?: values.remove(Keys.validUntil)
    } }
}

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
