package com.juan.habitos

import com.juan.habitos.domain.model.DeviceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileNavigationTest {
    @Test fun `child device without profile opens create or link choice`() {
        assertEquals(CHILD_PROFILE_SETUP_ROUTE, childEntryRoute(profileId = null, onboardingComplete = false))
    }

    @Test fun `create profile choice opens existing onboarding`() {
        assertEquals("profile", childSetupDestination(linkExistingProfile = false))
    }

    @Test fun `link profile choice opens temporary code screen`() {
        assertEquals(DEVICE_PROFILE_LINK_ROUTE, childSetupDestination(linkExistingProfile = true))
    }

    @Test fun `stored profile never opens recovery choice`() {
        assertEquals("profile", childEntryRoute(profileId = "profile-1", onboardingComplete = false))
        assertEquals("child", childEntryRoute(profileId = "profile-1", onboardingComplete = true))
        assertNotEquals(CHILD_PROFILE_SETUP_ROUTE, childEntryRoute(profileId = "profile-1", onboardingComplete = true))
    }

    @Test fun `adult OTP returns to same-device authorization only for a child device`() {
        assertEquals(SAME_DEVICE_MONITORING_ROUTE, postAdultAuthenticationDestination(DeviceMode.CHILD))
        assertEquals("adultLink", postAdultAuthenticationDestination(DeviceMode.ADULT))
        assertEquals("adultLink", postAdultAuthenticationDestination(null))
    }

    @Test fun `experimental PMUM is enabled only for adult mode`() {
        assertTrue(experimentalPmumEnabledFor(true, DeviceMode.ADULT, adultAuthenticated = true))
        assertFalse(experimentalPmumEnabledFor(true, DeviceMode.ADULT, adultAuthenticated = false))
        assertFalse(experimentalPmumEnabledFor(true, DeviceMode.CHILD, adultAuthenticated = true))
        assertFalse(experimentalPmumEnabledFor(false, DeviceMode.ADULT, adultAuthenticated = true))
        assertFalse(experimentalPmumEnabledFor(false, DeviceMode.CHILD, adultAuthenticated = false))
    }
}
