package com.juan.habitos.data.local

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class DeviceIdentityPreferencesTest {
    @Test fun `new installation id is a random UUID`() {
        val first = newInstallationId()
        val second = newInstallationId()

        assertEquals(first, UUID.fromString(first).toString())
        assertEquals(second, UUID.fromString(second).toString())
        assertNotEquals(first, second)
    }

    @Test fun `installation id is generated once and reused`() {
        val preferences = mutablePreferencesOf()
        var generated = 0
        val factory = { "installation-${++generated}" }

        val first = preferences.getOrCreateInstallationId(factory)
        val second = preferences.getOrCreateInstallationId(factory)

        assertEquals("installation-1", first)
        assertEquals(first, second)
        assertEquals(1, generated)
        assertEquals(first, preferences.toStoredDeviceIdentity().installationId)
    }

    @Test fun `profile device and installation ids use independent keys`() {
        val preferences = mutablePreferencesOf()
        val installationId = preferences.getOrCreateInstallationId { "installation-1" }
        preferences.storeDeviceLink(profileId = "profile-1", deviceId = "device-1")
        val identity = preferences.toStoredDeviceIdentity()

        assertEquals("installation-1", installationId)
        assertEquals("installation-1", identity.installationId)
        assertEquals("profile-1", identity.profileId)
        assertEquals("device-1", identity.deviceId)
        assertNotEquals(identity.profileId, identity.deviceId)
        assertNull(preferences[ProfilePreferenceKeys.id])
    }

    @Test fun `empty preferences simulate a new installation with another id`() {
        val firstInstall = mutablePreferencesOf()
        val secondInstall = mutablePreferencesOf()

        val first = firstInstall.getOrCreateInstallationId { "installation-a" }
        val second = secondInstall.getOrCreateInstallationId { "installation-b" }

        assertNotEquals(first, second)
    }
}
