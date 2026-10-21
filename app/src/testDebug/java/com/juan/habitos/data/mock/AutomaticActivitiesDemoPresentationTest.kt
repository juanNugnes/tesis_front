package com.juan.habitos.data.mock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.*
import org.junit.Test

class AutomaticActivitiesDemoPresentationTest {
    @Test fun `initial entry opens daytime exactly once despite repeated entry effects`() {
        val vm = AutomaticActivitiesDemoViewModel("child")
        assertFalse(vm.state.value.dialogVisible)
        vm.enterExperience()
        val first = vm.state.value
        assertTrue(first.dialogVisible)
        assertEquals(AutomaticDemoScenario.DAYTIME, first.offer!!.scenario)
        repeat(10) { vm.enterExperience() }
        assertEquals(first, vm.state.value)
    }

    @Test fun `outside back or close dismissal never counts as rejection nor reopens on reentry`() {
        val vm = AutomaticActivitiesDemoViewModel("child")
        vm.enterExperience()
        val id = vm.state.value.offer!!.id
        vm.presented(id)
        val events = vm.state.value.offer!!.events
        repeat(3) { vm.dismissDialog(id); vm.enterExperience() }
        assertFalse(vm.state.value.dialogVisible)
        assertEquals(0, vm.state.value.rejections)
        assertEquals(events, vm.state.value.offer!!.events)
        vm.reopenDialog()
        assertTrue(vm.state.value.dialogVisible)
        assertEquals(id, vm.state.value.offer!!.id)
        assertEquals(events, vm.state.value.offer!!.events)
    }

    @Test fun `retained activity store preserves dismissed offer across UI owner recreation`() {
        val store = ViewModelStore()
        var constructions = 0
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                constructions++
                return AutomaticActivitiesDemoViewModel("child") as T
            }
        }
        try {
            val first = ViewModelProvider(store, factory)["demo-child", AutomaticActivitiesDemoViewModel::class.java]
            first.enterExperience()
            first.dismissDialog(first.state.value.offer!!.id)
            val before = first.state.value
            val recreated = ViewModelProvider(store, factory)["demo-child", AutomaticActivitiesDemoViewModel::class.java]
            recreated.enterExperience()
            assertSame(first, recreated)
            assertEquals(1, constructions)
            assertEquals(before, recreated.state.value)
            assertFalse(recreated.state.value.dialogVisible)
        } finally { store.clear() }
    }

    @Test fun `accept start resume and finish keep declaration stages without real completion claims`() {
        val vm = AutomaticActivitiesDemoViewModel("child")
        vm.enterExperience()
        val id = vm.state.value.offer!!.id
        vm.presented(id); vm.accept(id)
        assertTrue(vm.state.value.dialogVisible)
        assertEquals(AutomaticDemoStage.SELECTED, vm.state.value.offer!!.stage)
        vm.dismissDialog(id); vm.enterExperience()
        assertFalse(vm.state.value.dialogVisible)
        vm.reopenDialog(); vm.start(id)
        assertEquals(AutomaticDemoStage.STARTED, vm.state.value.offer!!.stage)
        vm.dismissDialog(id); vm.reopenDialog()
        assertEquals(AutomaticDemoStage.STARTED, vm.state.value.offer!!.stage)
        vm.finish(id)
        assertFalse(vm.state.value.dialogVisible)
        assertEquals(AutomaticDemoStage.COMPLETED, vm.state.value.offer!!.stage)
        vm.enterExperience(); vm.reopenDialog()
        assertFalse(vm.state.value.dialogVisible)
    }

    @Test fun `manual bedtime opens same presentation while abstention and waiting never open offer`() {
        val vm = AutomaticActivitiesDemoViewModel("child")
        vm.enterExperience(); vm.reset()
        vm.simulate(AutomaticDemoScenario.BEDTIME)
        assertTrue(vm.state.value.dialogVisible)
        assertEquals("Inventá un cuento", vm.state.value.offer!!.title)
        for (scenario in listOf(AutomaticDemoScenario.INELIGIBLE, AutomaticDemoScenario.WAITING)) {
            vm.reset(); vm.simulate(scenario); vm.enterExperience(); vm.reopenDialog()
            assertNull(vm.state.value.offer)
            assertFalse(vm.state.value.dialogVisible)
        }
    }

    @Test fun `two explicit rejects close dialog and pause manual opportunities until reset`() {
        val vm = AutomaticActivitiesDemoViewModel("child")
        vm.enterExperience()
        repeat(2) {
            if (it > 0) vm.simulate(AutomaticDemoScenario.BEDTIME)
            val id = vm.state.value.offer!!.id
            vm.presented(id); vm.reject(id)
            assertFalse(vm.state.value.dialogVisible)
        }
        assertEquals(2, vm.state.value.rejections)
        assertTrue(vm.state.value.paused)
        vm.enterExperience(); vm.simulate(AutomaticDemoScenario.DAYTIME); vm.reopenDialog()
        assertFalse(vm.state.value.dialogVisible)
        vm.reset(); vm.enterExperience()
        assertEquals(AutomaticActivitiesDemoState("child"), vm.state.value)
        vm.simulate(AutomaticDemoScenario.DAYTIME)
        assertTrue(vm.state.value.dialogVisible)
    }

    @Test fun `profile changes never transfer initial offer or dismissal state`() {
        val first = AutomaticActivitiesDemoViewModel("a")
        val second = AutomaticActivitiesDemoViewModel("b")
        first.enterExperience(); first.dismissDialog(first.state.value.offer!!.id)
        second.enterExperience()
        assertFalse(first.state.value.dialogVisible)
        assertTrue(second.state.value.dialogVisible)
        assertEquals("b", second.state.value.offer!!.profileId)
        first.enterExperience()
        assertFalse(first.state.value.dialogVisible)
        second.dismissDialog(first.state.value.offer!!.id)
        assertTrue(second.state.value.dialogVisible)
    }
}
