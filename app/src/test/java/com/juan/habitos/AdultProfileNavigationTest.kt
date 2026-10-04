package com.juan.habitos

import com.juan.habitos.ui.adult.openProfileEditor
import org.junit.Assert.assertEquals
import org.junit.Test

class AdultProfileNavigationTest {
    @Test fun `profile data action opens edit profile route`() {
        var destination: String? = null
        openProfileEditor { destination = EDIT_PROFILE_ROUTE }
        assertEquals("editProfile", destination)
    }
}
