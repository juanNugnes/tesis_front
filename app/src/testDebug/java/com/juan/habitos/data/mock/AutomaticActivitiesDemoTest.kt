package com.juan.habitos.data.mock

import org.junit.Assert.*
import org.junit.Test

class AutomaticActivitiesDemoTest {
    @Test fun `nothing is generated just by creating or observing the simulator`() {
        val vm = AutomaticActivitiesDemoViewModel("child")
        repeat(10) { assertNull(vm.state.value.offer) }
        assertEquals(0, vm.state.value.rejections)
    }

    @Test fun `daytime scenario is reproducible and explicitly not a real measurement`() {
        val vm = AutomaticActivitiesDemoViewModel("child")
        vm.simulate(AutomaticDemoScenario.DAYTIME)
        val offer = requireNotNull(vm.state.value.offer)
        assertEquals("Dibujá algo que te guste", offer.title)
        assertTrue(offer.reason.contains("45 minutos"))
        assertTrue(offer.reason.contains("08:00"))
        assertTrue(offer.reason.contains("antes de las 20:00"))
        assertTrue(offer.reason.contains("No es una medición"))
        assertEquals(AutomaticDemoStage.GENERATED, offer.stage)
        val second = AutomaticActivitiesDemoViewModel("child")
        second.simulate(AutomaticDemoScenario.DAYTIME)
        assertEquals(offer, second.state.value.offer)
    }

    @Test fun `bedtime scenario uses only illustrative context not device bedtime`() {
        val vm = AutomaticActivitiesDemoViewModel("child")
        vm.simulate(AutomaticDemoScenario.BEDTIME)
        val offer = requireNotNull(vm.state.value.offer)
        assertEquals("Inventá un cuento", offer.title)
        assertTrue(offer.reason.contains("60 minutos"))
        assertTrue(offer.reason.contains("No cambia tu horario real"))
    }

    @Test fun `ineligible and waiting scenarios generate no offer`() {
        val vm = AutomaticActivitiesDemoViewModel("child")
        vm.simulate(AutomaticDemoScenario.INELIGIBLE)
        assertNull(vm.state.value.offer)
        assertEquals(AutomaticDemoScenario.INELIGIBLE, vm.state.value.scenario)
        vm.simulate(AutomaticDemoScenario.WAITING)
        assertNull(vm.state.value.offer)
        assertEquals(AutomaticDemoScenario.WAITING, vm.state.value.scenario)
    }

    @Test fun `generated presented accepted started and completed are separate idempotent transitions`() {
        val vm = AutomaticActivitiesDemoViewModel("child")
        vm.simulate(AutomaticDemoScenario.DAYTIME)
        val id = vm.state.value.offer!!.id
        vm.accept(id)
        vm.start(id)
        vm.finish(id)
        assertEquals(AutomaticDemoStage.GENERATED, vm.state.value.offer!!.stage)
        repeat(5) { vm.presented(id) }
        assertEquals(AutomaticDemoStage.PRESENTED, vm.state.value.offer!!.stage)
        repeat(5) { vm.accept(id) }
        assertEquals(AutomaticDemoStage.SELECTED, vm.state.value.offer!!.stage)
        vm.finish(id)
        assertEquals(AutomaticDemoStage.SELECTED, vm.state.value.offer!!.stage)
        repeat(5) { vm.start(id) }
        assertEquals(AutomaticDemoStage.STARTED, vm.state.value.offer!!.stage)
        repeat(5) { vm.finish(id) }
        assertEquals(AutomaticDemoStage.COMPLETED, vm.state.value.offer!!.stage)
        vm.reject(id)
        assertEquals(0, vm.state.value.rejections)
        assertEquals(listOf(AutomaticDemoEvent.GENERATED, AutomaticDemoEvent.PRESENTED, AutomaticDemoEvent.ACCEPTED, AutomaticDemoEvent.START_DECLARED, AutomaticDemoEvent.COMPLETION_DECLARED), vm.state.value.offer!!.events)
    }

    @Test fun `rapid scenario taps cannot replace active offer and repeated reads cannot duplicate delivery`() {
        val vm = AutomaticActivitiesDemoViewModel("child")
        vm.simulate(AutomaticDemoScenario.DAYTIME)
        val original = vm.state.value.offer!!
        repeat(10) { vm.simulate(AutomaticDemoScenario.DAYTIME); vm.simulate(AutomaticDemoScenario.BEDTIME); vm.simulate(AutomaticDemoScenario.INELIGIBLE) }
        assertEquals(original, vm.state.value.offer)
        repeat(10) { vm.presented(original.id) }
        assertEquals(listOf(AutomaticDemoEvent.GENERATED, AutomaticDemoEvent.PRESENTED), vm.state.value.offer!!.events)
    }

    @Test fun `two explicit rejections represent pause until reset with no duplicate counting`() {
        val vm = AutomaticActivitiesDemoViewModel("child")
        vm.simulate(AutomaticDemoScenario.DAYTIME)
        val first = vm.state.value.offer!!.id
        vm.reject(first)
        assertEquals(0, vm.state.value.rejections)
        vm.presented(first)
        repeat(3) { vm.reject(first) }
        assertEquals(1, vm.state.value.rejections)
        assertFalse(vm.state.value.paused)
        vm.simulate(AutomaticDemoScenario.BEDTIME)
        val second = vm.state.value.offer!!.id
        vm.presented(second)
        vm.reject(second)
        assertEquals(2, vm.state.value.rejections)
        assertTrue(vm.state.value.paused)
        vm.simulate(AutomaticDemoScenario.DAYTIME)
        assertEquals(second, vm.state.value.offer!!.id)
        vm.reset()
        assertEquals(AutomaticActivitiesDemoState("child"), vm.state.value)
    }

    @Test fun `reset is repeatable but late callbacks never affect a new offer`() {
        val vm = AutomaticActivitiesDemoViewModel("child")
        vm.simulate(AutomaticDemoScenario.DAYTIME)
        val old = vm.state.value.offer!!
        vm.reset()
        vm.reset()
        vm.simulate(AutomaticDemoScenario.DAYTIME)
        val new = vm.state.value.offer!!
        assertNotEquals(old.id, new.id)
        assertEquals(old.title, new.title)
        vm.presented(old.id)
        vm.accept(old.id)
        vm.reject(old.id)
        assertEquals(new, vm.state.value.offer)
    }

    @Test fun `profiles have distinct offer identities and independent local states`() {
        val first = AutomaticActivitiesDemoViewModel("child-a")
        val second = AutomaticActivitiesDemoViewModel("child-b")
        first.simulate(AutomaticDemoScenario.DAYTIME)
        second.simulate(AutomaticDemoScenario.DAYTIME)
        val a = first.state.value.offer!!
        val b = second.state.value.offer!!
        assertEquals("child-a", a.profileId)
        assertEquals("child-b", b.profileId)
        assertNotEquals(a.id, b.id)
        second.presented(a.id)
        second.reject(a.id)
        assertEquals(0, second.state.value.rejections)
        assertEquals(AutomaticDemoStage.GENERATED, second.state.value.offer!!.stage)
        first.reset()
        assertEquals(b, second.state.value.offer)
    }

    @Test fun `only current offer is retained after many demonstration rounds`() {
        val vm = AutomaticActivitiesDemoViewModel("child")
        repeat(100) {
            vm.simulate(AutomaticDemoScenario.DAYTIME)
            val id = vm.state.value.offer!!.id
            vm.presented(id); vm.accept(id); vm.start(id); vm.finish(id)
        }
        assertEquals(5, vm.state.value.offer!!.events.size)
        assertFalse(vm.state.value.paused)
    }
}
