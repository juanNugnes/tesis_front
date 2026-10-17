package com.juan.habitos.ui.child

import com.juan.habitos.domain.model.AppUsage
import com.juan.habitos.domain.model.UsageMetricStatus
import com.juan.habitos.ui.state.ActivityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChildScreensTest {
    @Test fun `visible time appears once inside the main sentence`() {
        assertEquals("Hoy llevás 2 h 5 min usando tu celular", dayUsageMessage(125, 100, null, timeVisible = true))
    }

    @Test fun `hidden time uses neutral comparison without revealing minutes`() {
        assertEquals("Hoy usaste el celu un poquito más que otros días", dayUsageMessage(125, 100, null, timeVisible = false))
        assertEquals("Ya hay información disponible sobre el uso de hoy", dayUsageMessage(25, null, null, timeVisible = false))
    }

    @Test fun `missing usage preserves repository information message`() {
        assertEquals("Falta el permiso para medir el uso", dayUsageMessage(null, null, "Falta el permiso para medir el uso", timeVisible = true))
        assertEquals("Todavía no hay información de uso para hoy", dayUsageMessage(null, null, null, timeVisible = false))
    }

    @Test fun `known application uses the real installed label when Android exposes it`() {
        assertEquals("YouTube", resolveApplicationDisplayName("com.google.android.youtube", "com.google.android.youtube") { "YouTube" })
    }

    @Test fun `reported readable app name is preserved when installed label is unavailable`() {
        assertEquals("Khan Academy", resolveApplicationDisplayName("Khan Academy", "org.khanacademy.android", null))
    }

    @Test fun `non visible packages get an identifiable name derived from the original package`() {
        assertEquals("Whatsapp W4b", resolveApplicationDisplayName("com.whatsapp.w4b", "com.whatsapp.w4b", null))
        assertEquals("Google Youtube", resolveApplicationDisplayName("com.google.android.youtube", "com.google.android.youtube", null))
    }

    @Test fun `system package uses its label or a safe package derived fallback`() {
        assertEquals("Moto App Launcher", resolveApplicationDisplayName("com.motorola.launcher3", "com.motorola.launcher3") { "Moto App Launcher" })
        assertEquals("Motorola Launcher 3", resolveApplicationDisplayName("com.motorola.launcher3", "com.motorola.launcher3", null))
    }

    @Test fun `metadata lookup errors preserve an identifiable package derived name`() {
        assertEquals("Google Youtube", resolveApplicationDisplayName("com.google.android.youtube", "com.google.android.youtube") { throw SecurityException("filtered") })
        assertEquals("Example Hidden", resolveApplicationDisplayName("com.example.hidden", "com.example.hidden") { throw IllegalStateException("package manager unavailable") })
    }

    @Test fun `application states distinguish permission missing no information and empty data`() {
        assertEquals("Falta el permiso de uso para ver aplicaciones", applicationsStateMessage(null, UsageMetricStatus.PERMISSION_DENIED))
        assertEquals("No hay información disponible sobre las aplicaciones", applicationsStateMessage(null, UsageMetricStatus.UNAVAILABLE))
        assertEquals("No se registraron aplicaciones hoy", applicationsStateMessage(emptyList(), UsageMetricStatus.MEASURED))
        assertNull(applicationsStateMessage(listOf(AppUsage("YouTube", "com.google.android.youtube", 20)), UsageMetricStatus.MEASURED))
    }

    @Test fun `application preview contains the three apps with most usage`() {
        val apps = listOf(
            AppUsage("Dos", "app.two", 20),
            AppUsage("Cuatro", "app.four", 40),
            AppUsage("Uno", "app.one", 10),
            AppUsage("Tres", "app.three", 30),
        )
        val preview = visibleApplications(apps, showAll = false)
        assertEquals(listOf("Cuatro", "Tres", "Dos"), preview.map(AppUsage::name))
        assertEquals(listOf("app.four", "app.three", "app.two"), preview.map(AppUsage::packageName))
        assertEquals(4, visibleApplications(apps, showAll = true).size)
    }

    @Test fun `zero selections shows not selected message`() {
        assertEquals("Hoy todavía no elegiste actividades", activityHistoryMessage(0))
    }

    @Test fun `one selection uses singular`() {
        assertEquals("Hoy elegiste 1 actividad", activityHistoryMessage(1))
    }

    @Test fun `multiple selections use plural with real count`() {
        assertEquals("Hoy elegiste 4 actividades", activityHistoryMessage(4))
    }

    @Test fun `missing confirmed count shows neutral message`() {
        assertEquals("Tus actividades de hoy aparecerán acá", activityHistoryMessage(null))
    }

    @Test fun `eligibility unknown shows preferences message and action when navigation is available`() {
        val presentation = activityUnavailablePresentation(ActivityState.PreferencesRequired, navigationAvailable = true)!!
        assertEquals("Antes de mostrarte actividades, un adulto tiene que completar tus preferencias.", presentation.message)
        assertEquals("Completar preferencias", presentation.actionLabel)
    }

    @Test fun `eligibility unknown omits action when navigation is unavailable`() {
        assertNull(activityUnavailablePresentation(ActivityState.PreferencesRequired, navigationAvailable = false)!!.actionLabel)
    }

    @Test fun `no eligible activities uses neutral text without preferences action`() {
        val presentation = activityUnavailablePresentation(ActivityState.NoEligibleActivities, navigationAvailable = true)!!
        assertEquals("Por ahora no encontramos una actividad para mostrarte.", presentation.message)
        assertNull(presentation.actionLabel)
    }

    @Test fun `technical error never exposes preferences action`() {
        val presentation = activityUnavailablePresentation(ActivityState.Error("Temporal"), navigationAvailable = true)!!
        assertEquals("Temporal", presentation.message)
        assertNull(presentation.actionLabel)
    }
}
