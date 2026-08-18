package com.juan.habitos.data.local

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import com.juan.habitos.domain.model.Profile
import com.juan.habitos.domain.model.BedtimeRange
import com.juan.habitos.domain.model.SchoolSchedule
import com.juan.habitos.domain.repository.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

private val Context.dataStore by preferencesDataStore("settings")

class DataStoreProfileRepository(private val context: Context) : ProfileRepository {
    private object Keys { val name = stringPreferencesKey("name"); val avatar = stringPreferencesKey("avatar"); val age = intPreferencesKey("age"); val schoolSchedules = stringSetPreferencesKey("school_schedules"); val bedtime = stringPreferencesKey("bedtime"); val done = booleanPreferencesKey("onboarding_done") }
    override val profile = context.dataStore.data.map { p -> p[Keys.name]?.let { Profile(it, p[Keys.avatar] ?: "🦊", p[Keys.age] ?: 9, p[Keys.schoolSchedules].orEmpty().mapNotNull { value -> SchoolSchedule.entries.find { it.name == value } }.toSet(), p[Keys.bedtime]?.let { value -> BedtimeRange.entries.find { it.name == value } }) } }
    override val onboardingComplete = context.dataStore.data.map { it[Keys.done] ?: false }
    override suspend fun save(profile: Profile) { context.dataStore.edit { it[Keys.name] = profile.name; it[Keys.avatar] = profile.avatar; it[Keys.age] = profile.age; it[Keys.schoolSchedules] = profile.schoolSchedules.map { schedule -> schedule.name }.toSet(); profile.bedtime?.let { bedtime -> it[Keys.bedtime] = bedtime.name } ?: it.remove(Keys.bedtime) } }
    override suspend fun completeOnboarding() { context.dataStore.edit { it[Keys.done] = true } }
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
