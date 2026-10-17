package com.juan.habitos.data.local

import com.juan.habitos.domain.model.DeviceMode
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceSessionModeTest {
    @Test fun `temporary adult authentication preserves an established child device mode`() {
        assertEquals(DeviceMode.CHILD, modeAfterAdultAuthentication(DeviceMode.CHILD))
    }

    @Test fun `adult setup remains adult when it is not a child device`() {
        assertEquals(DeviceMode.ADULT, modeAfterAdultAuthentication(DeviceMode.ADULT))
        assertEquals(DeviceMode.ADULT, modeAfterAdultAuthentication(null))
    }
}
