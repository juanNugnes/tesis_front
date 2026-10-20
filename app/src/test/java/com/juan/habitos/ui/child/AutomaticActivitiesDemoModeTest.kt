package com.juan.habitos.ui.child

import com.juan.habitos.BuildConfig
import org.junit.Assert.*
import org.junit.Test

class AutomaticActivitiesDemoModeTest {
    @Test fun `mode defaults to disabled even in debug`() {
        assertFalse(automaticActivitiesDemoEnabled(true))
        assertFalse(automaticActivitiesDemoEnabled(false))
    }

    @Test fun `only independent request in debug can enable mode`() {
        assertFalse(automaticActivitiesDemoEnabled(false, true))
        assertFalse(automaticActivitiesDemoEnabled(false, false))
        assertFalse(automaticActivitiesDemoEnabled(true, false))
        assertTrue(automaticActivitiesDemoEnabled(true, true))
    }

    @Test fun `compiled flag cannot be enabled outside debug`() {
        assertTrue(!BuildConfig.AUTOMATIC_ACTIVITIES_DEMO_MODE || BuildConfig.DEBUG)
    }
}
