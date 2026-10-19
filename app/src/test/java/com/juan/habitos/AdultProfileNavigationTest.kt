package com.juan.habitos

import com.juan.habitos.ui.adult.openProfileEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdultProfileNavigationTest {
    @Test fun `opening a child panel preserves profile list in back stack`() {
        val options = adultPanelNavigationOptions()
        assertNull(options.popUpToRoute)
        assertEquals(-1, options.popUpToId)
        assertTrue(options.shouldLaunchSingleTop())
    }

    @Test fun `return removes child panel and its viewmodels without duplicating profile list`() {
        val options = adultProfileListNavigationOptions()
        assertEquals("adult", options.popUpToRoute)
        assertTrue(options.isPopUpToInclusive())
        assertTrue(options.shouldLaunchSingleTop())
    }

    @Test fun `profile data action opens edit profile route`() {
        var destination: String? = null
        openProfileEditor { destination = EDIT_PROFILE_ROUTE }
        assertEquals("editProfile", destination)
    }
}
