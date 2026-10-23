package com.invisiblejourney.domain

import com.invisiblejourney.domain.model.MountType
import com.invisiblejourney.domain.model.Phase
import com.invisiblejourney.domain.model.StopType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyEngineTest {

    private val noRequests = object : EngineRequests {
        override fun checkSignal(stopId: Int, lat: Double, lng: Double) = Unit
        override fun fetchFreeFlow(segmentId: Int, lat: Double, lng: Double) = Unit
    }

    private fun engine() = JourneyEngine("j", "test", startedAt = 0, promisedEtaMin = 10, mount = MountType.POCKET, requests = noRequests)

    @Test
    fun `time is attributed to phases`() {
        val e = engine()
        e.onTick(60_000) // 1 min riding to store
        assertEquals(Phase.AT_STORE, e.advancePhase(60_000))
        e.onTick(240_000) // 3 min at store
        assertEquals(Phase.EN_ROUTE, e.advancePhase(240_000))
        e.onTick(600_000) // 6 min riding
        assertEquals(Phase.ARRIVED, e.advancePhase(600_000))
        e.onTick(720_000) // 2 min at the gate
        assertEquals(Phase.DELIVERED, e.advancePhase(720_000))

        val m = e.meter()
        assertEquals(720_000L, m.elapsedMs)
        assertEquals(180_000L, m.waitingByType[StopType.STORE_WAIT])
        assertEquals(120_000L, m.waitingByType[StopType.GATE_OR_DOOR_WAIT])
        assertEquals(420_000L, m.movingMs) // 1 + 6 min riding, no stops

        val summary = e.record().summary
        assertNotNull(summary)
        assertEquals(12f, summary!!.actualMin, 0.01f)
        assertTrue(summary.storyLines.any { "waiting at the store" in it })
    }

    @Test
    fun `cancelled journey has no summary`() {
        val e = engine()
        e.onTick(30_000)
        e.cancel(30_000)
        assertEquals(Phase.CANCELLED, e.phase)
        assertNull(e.record().summary)
    }

    @Test
    fun `signal lookup reclassifies a stop`() {
        var asked: Int? = null
        val e = JourneyEngine("j", "t", 0, 10, MountType.POCKET, object : EngineRequests {
            override fun checkSignal(stopId: Int, lat: Double, lng: Double) { asked = stopId }
            override fun fetchFreeFlow(segmentId: Int, lat: Double, lng: Double) = Unit
        })
        var t = 0L
        val lat = 12.93
        repeat(5) { e.onLocation(t, lat + it * 0.0001, 77.62, 5f, 25f); t += 1000 } // moving
        repeat(40) { e.onLocation(t, lat + 0.0005, 77.62, 5f, 0f); t += 1000 } // 40 s stopped
        repeat(5) { e.onLocation(t, lat + 0.0005 + it * 0.0001, 77.62, 5f, 25f); t += 1000 } // moving again
        e.onTick(t)

        assertNotNull(asked)
        assertTrue((e.meter().waitingByType[StopType.TRAFFIC_STOP] ?: 0) > 30_000)
        e.onSignalResult(asked!!, isSignal = true)
        assertTrue((e.meter().waitingByType[StopType.SIGNAL_WAIT] ?: 0) > 30_000)
        assertEquals(0L, e.meter().waitingByType[StopType.TRAFFIC_STOP])
    }

    @Test
    fun `sample journeys are realistic`() {
        SampleJourneys.Scenario.entries.forEach { scenario ->
            val r = SampleJourneys.generate(scenario)
            val s = r.summary
            assertNotNull(scenario.name, s)
            assertTrue("${scenario.name} distance ${s!!.distanceKm}", s.distanceKm in 2f..6f)
            assertTrue("${scenario.name} actual ${s.actualMin}", s.actualMin > r.promisedEtaMin)
            assertEquals(3, r.stops.size)
            assertEquals(2, r.stops.count { it.type == StopType.SIGNAL_WAIT })
            assertTrue("${scenario.name} potholes ${r.potholes.size}", r.potholes.size >= 3)
            assertTrue(r.snapshots.size > 100)
        }
    }
}
