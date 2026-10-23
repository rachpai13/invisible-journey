package com.invisiblejourney.domain

import com.invisiblejourney.domain.model.MountType
import com.invisiblejourney.domain.model.Roughness
import com.invisiblejourney.domain.model.TrackPoint
import com.invisiblejourney.domain.processor.CongestionProcessor
import com.invisiblejourney.domain.processor.DistanceProcessor
import com.invisiblejourney.domain.processor.G
import com.invisiblejourney.domain.processor.Geo
import com.invisiblejourney.domain.processor.IndianAqi
import com.invisiblejourney.domain.processor.BumpClassifier
import com.invisiblejourney.domain.processor.BumpDetector
import com.invisiblejourney.domain.processor.RoughnessProcessor
import com.invisiblejourney.domain.processor.Spectrum
import com.invisiblejourney.domain.processor.StopDetector
import com.invisiblejourney.domain.processor.VerticalAccel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessorsTest {

    @Test
    fun `one degree of latitude is about 111 km`() {
        assertEquals(111_195.0, Geo.distanceM(12.0, 77.0, 13.0, 77.0), 200.0)
    }

    @Test
    fun `distance ignores inaccurate fixes and GPS jumps`() {
        val d = DistanceProcessor()
        assertTrue(d.accept(TrackPoint(0, 12.0, 77.0, 5f, 20f), countDistance = true))
        // 100 m north after 10 s = 36 km/h: accepted
        assertTrue(d.accept(TrackPoint(10_000, 12.0 + 100 / 111_195.0, 77.0, 5f, 36f), countDistance = true))
        assertEquals(100.0, d.totalM, 1.0)
        // bad accuracy
        assertFalse(d.accept(TrackPoint(11_000, 12.01, 77.0, 60f, 20f), countDistance = true))
        // 1 km in 1 s: a jump
        assertFalse(d.accept(TrackPoint(11_000, 12.0 + 1100 / 111_195.0, 77.0, 5f, 20f), countDistance = true))
        assertEquals(100.0, d.totalM, 1.0)
    }

    @Test
    fun `stop needs 20 seconds below 3 kmh`() {
        val s = StopDetector()
        for (i in 0..10) assertNull(s.update(i * 1000L, 0f, 0.0, 0.0))
        assertNull(s.update(11_000, 15f, 0.0, 0.0)) // only 11 s: not a stop

        for (i in 20..50) s.update(i * 1000L, 1f, 0.0, 0.0)
        assertNotNull(s.ongoingStart(50_000))
        val stop = s.update(51_000, 12f, 0.0, 0.0)
        assertNotNull(stop)
        assertEquals(31_000L, stop!!.end - stop.start)
    }

    /** Feeds [ms] of samples at 100 Hz from [g] (in g units), starting at [t0]. Returns the end time and any bumps. */
    private fun BumpDetector.feed(t0: Long, ms: Int, speed: Float, g: (Int) -> Float): Pair<Long, List<BumpDetector.Bump>> {
        val out = mutableListOf<BumpDetector.Bump>()
        var t = t0
        for (i in 0 until ms / 10) {
            onSample(t, g(i) * G, speed)?.let { out += it }
            t += 10
        }
        return t to out
    }

    private val road = { i: Int -> if (i % 2 == 0) 0.05f else -0.05f }

    @Test
    fun `a pothole is one event`() {
        val d = BumpDetector(MountType.HANDLEBAR)
        var (t, _) = d.feed(0, 5_000, 20f, road)
        // 60 ms jolt with some ringing after it
        val (t2, hits) = d.feed(t, 2_000, 20f) { i -> if (i in 0..5) 1.8f else if (i in 6..20) -0.7f else road(i) }
        t = t2
        assertEquals(1, hits.size)
        assertTrue(hits[0].prominence > 6f)
    }

    @Test
    fun `phone knocks and slow handling are ignored`() {
        val d = BumpDetector(MountType.CAR)
        val (t, _) = d.feed(0, 5_000, 20f, road)
        // single-sample 4 g spike = phone tapped / knocked
        val (t2, knock) = d.feed(t, 2_000, 20f) { i -> if (i == 0) 4f else road(i) }
        assertEquals(0, knock.size)
        // real-looking jolt but car almost stopped
        val (_, slow) = d.feed(t2, 2_000, 2f) { i -> if (i in 0..5) 1f else road(i) }
        assertEquals(0, slow.size)
    }

    @Test
    fun `constant rattle on a rough road does not keep firing`() {
        val d = BumpDetector(MountType.CAR)
        // 30 s of rough road: ±0.4 g, with a slightly bigger hit every 2 s
        val (_, hits) = d.feed(0, 30_000, 25f) { i -> if (i % 200 == 0) 0.6f else if (i % 2 == 0) 0.4f else -0.4f }
        assertTrue("fired ${hits.size} times", hits.size <= 1)
    }

    @Test
    fun `slow heave is a speed breaker, sharp hit is a pothole`() {
        // ±1 s clips at 100 Hz: a 2.5 Hz heave (speed breaker) vs a 9 Hz ringing hit (pothole)
        val ts = List(200) { it * 10L }
        val heave = List(200) { i -> (0.6 * kotlin.math.sin(2 * Math.PI * 2.5 * i / 100.0)).toFloat() }
        val hit = List(200) { i -> (0.6 * kotlin.math.exp(-(i - 100).let { it * it } / 200.0) * kotlin.math.sin(2 * Math.PI * 9 * i / 100.0)).toFloat() }
        val (cHeave, lowHeave) = Spectrum.shape(ts, heave)
        val (cHit, lowHit) = Spectrum.shape(ts, hit)
        assertTrue(BumpClassifier.isSpeedHump(MountType.CAR, cHeave, lowHeave, minSpeedDuring = 13f))
        assertFalse(BumpClassifier.isSpeedHump(MountType.CAR, cHit, lowHit, minSpeedDuring = 13f))
        // a slow heave at highway speed isn't treated as a speed breaker
        assertFalse(BumpClassifier.isSpeedHump(MountType.CAR, cHeave, lowHeave, minSpeedDuring = 60f))
    }

    @Test
    fun `roughness buckets by rms`() {
        val r = RoughnessProcessor(MountType.HANDLEBAR)
        var w: RoughnessProcessor.Window? = null
        // ±3 m/s² square wave → rms 3 → ROUGH for handlebar thresholds (1, 2, 3.5)
        for (i in 0..260) {
            val out = r.onSample(i * 20L, if (i % 2 == 0) 3f else -3f, speedKmh = 20f)
            if (out != null) w = out
        }
        assertNotNull(w)
        assertEquals(3f, w!!.rms, 0.01f)
        assertEquals(Roughness.ROUGH, w.bucket)
    }

    @Test
    fun `congestion index is clamped`() {
        assertEquals(1f, CongestionProcessor.index(30f, 45f), 0.001f)
        assertEquals(3f, CongestionProcessor.index(30f, 10f), 0.001f)
        assertEquals(5f, CongestionProcessor.index(30f, 1f), 0.001f)
    }

    @Test
    fun `indian aqi from pm`() {
        assertEquals(50, IndianAqi.compute(30f, null))
        assertEquals(150, IndianAqi.compute(75f, null))
        assertEquals(150, IndianAqi.compute(75f, 60f)) // max of sub-indices
        assertNull(IndianAqi.compute(null, null))
        assertEquals("Moderate", IndianAqi.category(150))
    }

    @Test
    fun `vertical accel ignores phone orientation and forward jolts`() {
        // Phone lying on its side: gravity along +x.
        val v = VerticalAccel()
        var t = 0L
        repeat(300) { v.update(t, 9.81f, 0f, 0f); t += 10 } // settle
        val up = v.update(t, 9.81f + 15f, 0f, 0f) // 15 m/s² jolt along gravity
        t += 10
        assertEquals(15f, up, 1f)
        repeat(300) { v.update(t, 9.81f, 0f, 0f); t += 10 }
        val forward = v.update(t, 9.81f, 15f, 0f) // same jolt, but sideways/forward
        assertEquals(0f, forward, 1f)
    }
}
