package com.invisiblejourney.domain

import com.invisiblejourney.data.sensors.esp32.Esp32Protocol
import com.invisiblejourney.domain.model.MountType
import com.invisiblejourney.domain.model.Phase
import com.invisiblejourney.domain.processor.Mq2
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The ESP32 meter module: display state (ETA vs wait), MQ-2 exposure, and the BLE packets. */
class MeterModuleTest {

    private val noRequests = object : EngineRequests {
        override fun checkSignal(stopId: Int, lat: Double, lng: Double) = Unit
        override fun fetchFreeFlow(segmentId: Int, lat: Double, lng: Double) = Unit
    }

    private fun engine() = JourneyEngine("j", "test", startedAt = 0, promisedEtaMin = 10, mount = MountType.POCKET, requests = noRequests)

    /** One GPS fix a second heading north at [kmh], with the 1 Hz tick. */
    private fun ride(e: JourneyEngine, fromS: Int, toS: Int, kmh: Float, lat0: Double = 12.9) {
        for (s in fromS until toS) {
            val t = s * 1000L
            e.onLocation(t, lat0 + s * kmh / 3.6 / 111_000.0, 77.6, 5f, kmh)
            e.onTick(t)
        }
    }

    @Test
    fun `moving shows the ETA counting down`() {
        val e = engine()
        ride(e, 0, 61, 30f)
        val d = MeterDisplay.from(e.meter(), 10)
        assertEquals(MeterDisplay.Mode.MOVING, d.mode)
        assertEquals(10 * 60 - 60, d.etaLeftS)
        assertEquals(0, d.waitS)
    }

    @Test
    fun `stopping in traffic switches to the wait time`() {
        val e = engine()
        ride(e, 0, 30, 30f)
        val lat = 12.9 + 29 * 30 / 3.6 / 111_000.0
        for (s in 30 until 60) {
            e.onLocation(s * 1000L, lat, 77.6, 5f, 0f)
            e.onTick(s * 1000L)
        }
        val m = e.meter()
        assertTrue(m.waitingNow)
        val d = MeterDisplay.from(m, 10)
        assertEquals(MeterDisplay.Mode.WAITING, d.mode)
        // Smoothing delays the stop by a few seconds; it's still most of the 30 s.
        assertTrue("wait ${d.waitS}", d.waitS in 20..30)

        ride(e, 60, 75, 30f, lat0 = lat - 60 * 30 / 3.6 / 111_000.0)
        assertFalse(e.meter().waitingNow)
        assertEquals(0L, e.meter().currentWaitMs)
    }

    @Test
    fun `a moment below walking pace is not shown as a wait`() {
        val e = engine()
        ride(e, 0, 20, 30f)
        val lat = 12.9 + 19 * 30 / 3.6 / 111_000.0
        // Smoothed speed has to fall first, so the display holds off for a few seconds more.
        e.onLocation(20_000, lat, 77.6, 5f, 0f)
        e.onTick(20_000)
        assertFalse(e.meter().waitingNow)
    }

    @Test
    fun `waiting at the store counts the whole phase`() {
        val e = engine()
        e.onTick(60_000)
        e.advancePhase(60_000)
        e.onTick(150_000)
        val d = MeterDisplay.from(e.meter(), 10)
        assertEquals(MeterDisplay.Mode.WAITING, d.mode)
        assertEquals(90, d.waitS)
    }

    @Test
    fun `late shows a negative ETA and delivered holds the total`() {
        val e = engine()
        e.onTick(11 * 60_000L)
        assertEquals(-60, MeterDisplay.from(e.meter(), 10).etaLeftS)
        repeat(4) { e.advancePhase(11 * 60_000L) }
        assertEquals(Phase.DELIVERED, e.phase)
        val d = MeterDisplay.from(e.meter(), 10)
        assertEquals(MeterDisplay.Mode.FINISHED, d.mode)
        assertEquals(11 * 60, d.elapsedS)
    }

    @Test
    fun `clean air is zero pollution and more gas is more`() {
        assertEquals(0f, Mq2.excessPpm(Mq2.CLEAN_AIR_RATIO)!!, 0.01f)
        assertEquals(0f, Mq2.excessPpm(15f)!!, 0f) // cleaner than the baseline still reads 0
        val light = Mq2.excessPpm(5f)!!
        val heavy = Mq2.excessPpm(1.5f)!!
        assertTrue(light > 0f && heavy > light)
        assertEquals("Clean", Mq2.level(0f))
        assertEquals("Heavy", Mq2.level(heavy))
        assertNull(Mq2.excessPpm(0f))
        assertNull(Mq2.excessPpm(Float.NaN))
    }

    @Test
    fun `gas exposure adds up over time and reaches the summary`() {
        val e = engine()
        val excess = Mq2.excessPpm(3f)!!
        for (s in 0..120) {
            e.onGas(s * 1000L, 3f)
            e.onTick(s * 1000L)
        }
        val m = e.meter()
        assertEquals(excess, m.gasPpm!!, 0.01f)
        assertEquals(excess * 2f, m.gasPpmMinutes, excess * 0.02f) // 2 minutes
        repeat(4) { e.advancePhase(120_000) }
        val r = e.record()
        assertEquals(13, r.gas.size) // one stored every 10 s
        val s = r.summary!!
        assertEquals(excess, s.avgGasPpm!!, 0.01f)
        assertTrue(s.storyLines.any { "exhaust" in it })
    }

    @Test
    fun `a module that goes quiet stops counting`() {
        val e = engine()
        e.onGas(0, 3f)
        e.onTick(0)
        e.onTick(60_000)
        assertNull(e.meter().gasPpm)
        assertTrue(e.meter().gasPpmMinutes < 0.5f * Mq2.excessPpm(3f)!!)
    }

    @Test
    fun `no module means no gas in the summary`() {
        val e = engine()
        e.onTick(60_000)
        repeat(4) { e.advancePhase(60_000) }
        assertNull(e.record().summary!!.gasPpmMinutes)
    }

    @Test
    fun `gas packet decodes`() {
        val b = ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x06).put(1).putShort(7).putInt(123_456)
            .putShort(982).putShort(743).putShort(8194).put(0b11)
            .array()
        val p = Esp32Protocol.parse(b) as Esp32Protocol.Gas
        assertEquals(7, p.seq)
        assertEquals(123_456L, p.tMs)
        assertEquals(982, p.adcRaw)
        assertEquals(743, p.millivolts)
        assertEquals(8.194f, p.ratio, 0.0005f)
        assertTrue(p.usable)
    }

    @Test
    fun `bad packets are rejected`() {
        assertNull(Esp32Protocol.parse(byteArrayOf(0x06, 1, 0)))                       // truncated header
        assertNull(Esp32Protocol.parse(byteArrayOf(0x06, 1, 0, 0, 0, 0, 0, 0, 1)))     // truncated payload
        assertNull(Esp32Protocol.parse(byteArrayOf(0x06, 2, 0, 0, 0, 0, 0, 0)))        // wrong version
        assertNull(Esp32Protocol.parse(byteArrayOf(0x55, 1, 0, 0, 0, 0, 0, 0)))        // unknown type
        assertNotNull(Esp32Protocol.parse(byteArrayOf(0x7F, 1, 0, 0, 0, 0, 0, 0)))     // heartbeat
        val cold = byteArrayOf(0x06, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x10, 0x27, 0b10)
        assertFalse((Esp32Protocol.parse(cold) as Esp32Protocol.Gas).usable)            // still heating
    }

    @Test
    fun `display state encodes little-endian`() {
        val bytes = Esp32Protocol.displayState(MeterDisplay(MeterDisplay.Mode.WAITING, etaLeftS = -75, waitS = 42, elapsedS = 675), rate = 10)
        val expected = ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x13).put(2).putInt(-75).putInt(42).putInt(675).put(10).array()
        assertArrayEquals(expected, bytes)
    }
}
