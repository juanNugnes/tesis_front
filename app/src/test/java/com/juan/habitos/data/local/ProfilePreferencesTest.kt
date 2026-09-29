package com.juan.habitos.data.local

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.juan.habitos.domain.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProfilePreferencesTest {
    @Test fun `profile remote id and active flag survive preferences round trip`() {
        val preferences = mutablePreferencesOf()
        val expected = Profile(
            name = "Juana",
            avatar = "🐸",
            age = 9,
            schoolStart = "08:00",
            schoolEnd = "12:30",
            sleepStart = "22:00",
            sleepEnd = "07:00",
            id = "profile-123",
            active = false,
        )

        preferences.storeProfile(expected)
        val restored = preferences.toStoredProfile()

        assertEquals(expected, restored)
        assertEquals("profile-123", restored?.id)
        assertFalse(restored!!.active)
    }
}
