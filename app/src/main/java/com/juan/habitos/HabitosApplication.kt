package com.juan.habitos

import android.app.Application
import com.juan.habitos.data.local.*
import com.juan.habitos.data.mock.*
import com.juan.habitos.data.remote.HabitsApiClient

class HabitosApplication : Application() {
    val profileRepository by lazy { DataStoreProfileRepository(this) }
    val pinStore by lazy { HashedPinStore(this) }
    val usageRepository by lazy { FakeUsageRepository() }
    val alertRepository by lazy { FakeAlertRepository() }
    val habitsApi by lazy { HabitsApiClient.api }
}
