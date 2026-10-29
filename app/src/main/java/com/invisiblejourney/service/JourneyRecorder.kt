package com.invisiblejourney.service

import com.invisiblejourney.AppContainer
import com.invisiblejourney.data.RideLogger
import com.invisiblejourney.data.sensors.esp32.Esp32Link
import com.invisiblejourney.data.sensors.esp32.Esp32Protocol
import com.invisiblejourney.domain.EngineRequests
import com.invisiblejourney.domain.JourneyEngine
import com.invisiblejourney.domain.MeterDisplay
import com.invisiblejourney.domain.SampleJourneys
import com.invisiblejourney.domain.model.BumpKind
import com.invisiblejourney.domain.model.MeterState
import com.invisiblejourney.domain.model.MountType
import com.invisiblejourney.domain.model.SourceTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/** What the Rider UI and the notification observe. */
data class ActiveJourneyState(
    val journeyId: String,
    val meter: MeterState,
    val gpsFix: Boolean,
    val conditionsOk: Boolean?,
    val trafficApi: Boolean,
    val finished: Boolean = false,
    /** True when the sensor data comes from a scripted simulated ride, not the phone's sensors. */
    val simulated: Boolean = false,
    /** The ESP32 meter module's link. */
    val module: Esp32Link.State = Esp32Link.State.OFF,
)

/** Process-wide handle on the journey being recorded (the service owns the recorder). */
object ActiveJourney {
    internal val mutable = MutableStateFlow<ActiveJourneyState?>(null)
    val state: StateFlow<ActiveJourneyState?> = mutable.asStateFlow()

    /** Forget a finished journey before starting a new one. */
    fun clearFinished() {
        if (mutable.value?.finished == true) mutable.value = null
    }
}

/**
 * Wires Android sensor flows and network lookups into the pure [JourneyEngine].
 * All engine calls run on one single-threaded dispatcher, so the engine needs no locks.
 */
class JourneyRecorder(
    private val container: AppContainer,
    mount: MountType,
    promisedEtaMin: Int,
    /** When set, a scripted ride replaces GPS and accelerometer (everything else is real: weather, signals, saving). */
    private val simulation: SampleJourneys.Scenario? = null,
    simSpeed: Int = 1,
    private val onFinished: (journeyId: String) -> Unit,
) {
    private val realStart = System.currentTimeMillis()
    private val speed = if (simulation != null) simSpeed.coerceIn(1, 60) else 1

    /** Journey clock. Real time, or sped-up time during a simulated ride. */
    private fun now(): Long = realStart + (System.currentTimeMillis() - realStart) * speed

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "journey-engine") }
    private val engineDispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + engineDispatcher)

    private var gpsFix = false
    private var conditionsOk: Boolean? = null
    private var conditionsJob: Job? = null

    /** Raw-sensor research log (real rides only). */
    private var logger: RideLogger? = null

    private val requests = object : EngineRequests {
        override fun checkSignal(stopId: Int, lat: Double, lng: Double) {
            scope.launch(Dispatchers.IO) {
                val isSignal = runCatching { container.overpass.hasTrafficSignalNear(lat, lng) }.getOrDefault(false)
                withContext(engineDispatcher) { engine.onSignalResult(stopId, isSignal) }
            }
        }

        override fun fetchFreeFlow(segmentId: Int, lat: Double, lng: Double) {
            if (!container.tomTom.isConfigured) return // keep the ESTIMATED value
            scope.launch(Dispatchers.IO) {
                val kmh = runCatching { container.tomTom.freeFlowKmh(lat, lng) }.getOrNull() ?: return@launch
                withContext(engineDispatcher) { engine.onFreeFlow(segmentId, kmh, SourceTag.API) }
            }
        }
    }

    private val engine: JourneyEngine = run {
        val prefix = if (simulation != null) "Simulated ride · " else "Journey · "
        JourneyEngine(
            id = UUID.randomUUID().toString(),
            title = prefix + SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(realStart)),
            startedAt = realStart,
            promisedEtaMin = promisedEtaMin,
            mount = mount,
            requests = requests,
        )
    }

    val journeyId: String get() = engine.id

    fun start() {
        publish()
        startModule()
        val sim = simulation
        if (sim != null) {
            scope.launch { runSimulation(sim) }
        } else {
            scope.launch {
                container.gps.samples().collect { s ->
                    gpsFix = s.accuracyM <= 25f
                    engine.onLocation(s.t, s.lat, s.lng, s.accuracyM, s.speedKmh)
                }
            }
            if (container.imu.isAvailable) {
                val log = runCatching { RideLogger(container.filesDir, engine.id) }.getOrNull()
                logger = log
                scope.launch {
                    container.imu.samples().collect { s ->
                        val hit = engine.onImu(s.t, s.verticalMs2)
                        if (log != null) {
                            val speed = engine.currentSpeedKmh
                            runCatching {
                                log.onSample(s.t, s.ax, s.ay, s.az, s.verticalMs2, speed)
                                if (hit != null) {
                                    val kind = if (hit.kind == BumpKind.SPEED_HUMP) "hump" else "pothole"
                                    log.detected(hit.t, hit.lat, hit.lng, speed, hit.peakG, hit.severity, kind)
                                }
                            }
                        }
                    }
                }
            }
        }
        // 1 Hz journey clock: drives time accounting, snapshots and the UI.
        scope.launch {
            while (isActive) {
                engine.onTick(now())
                publish()
                sendDisplay()
                delay(1_000L / speed)
            }
        }
        // Autosave so a crash or a killed process loses at most ~15 s.
        scope.launch {
            while (isActive) {
                delay(15_000)
                runCatching { container.store.save(engine.record()) }
            }
        }
        conditionsJob = scope.launch {
            while (isActive) {
                if (fetchConditions()) delay(10 * 60_000L / speed) else delay(10_000)
            }
        }
    }

    /**
     * The ESP32 module, if there is one: its MQ-2 readings feed the engine, and the 1 Hz clock sends
     * it the display state. Works in demo rides too (the gas sensor is real even when the ride isn't).
     */
    private fun startModule() {
        container.esp32.start()
        scope.launch {
            container.esp32.packets.collect { p ->
                if (p is Esp32Protocol.Gas && p.usable) engine.onGas(now(), p.ratio)
            }
        }
        scope.launch { container.esp32.state.collect { publish() } }
    }

    private fun sendDisplay() {
        val d = MeterDisplay.from(engine.meter(), engine.promisedEtaMin)
        container.esp32.sendDisplay(Esp32Protocol.displayState(d, speed))
    }

    /** Rider tapped the phase button. Ignored in a simulated ride, where the script advances phases. */
    fun advancePhase() {
        if (simulation != null) return
        scope.launch { advance() }
    }

    /** Rider marked a pothole or speed breaker: store the mark and save a sensor clip around it. */
    fun markBump(kind: BumpKind) {
        scope.launch {
            val t = now()
            val m = engine.onPotholeMark(t, kind) ?: return@launch
            runCatching { logger?.mark(t, m.lat, m.lng, m.speedKmh, if (kind == BumpKind.SPEED_HUMP) "hump" else "pothole") }
            publish()
        }
    }

    /** Returns true if the journey is now finished. */
    private suspend fun advance(): Boolean {
        val phase = engine.advancePhase(now())
        if (phase.isFinal) {
            finish()
            return true
        }
        publish()
        scope.launch { fetchConditions() } // fresh weather at every phase change
        return false
    }

    /** Feeds the scripted ride second by second at [speed]× real time. */
    private suspend fun runSimulation(scenario: SampleJourneys.Scenario) {
        val frames = withContext(Dispatchers.Default) { SampleJourneys.script(scenario) }
        gpsFix = true
        for (f in frames) {
            val t = now()
            engine.onLocation(t, f.lat, f.lng, accuracyM = 6f, rawSpeedKmh = f.speedKmh)
            f.imu.forEachIndexed { i, az -> engine.onImu(t + i * 20L, az) }
            delay(1_000L / speed)
            if (f.advancePhaseAfter && advance()) return
        }
    }

    fun cancel() {
        scope.launch {
            engine.cancel(now())
            finish()
        }
    }

    private suspend fun fetchConditions(): Boolean {
        val loc = engine.meter().location ?: return false
        val reading = withContext(Dispatchers.IO) {
            runCatching { container.openMeteo.conditions(loc.lat, loc.lng, now()) }.getOrNull()
        }
        conditionsOk = reading != null
        reading?.let { engine.onConditions(it) }
        return reading != null
    }

    private suspend fun finish() {
        val record = engine.record()
        runCatching { logger?.close() }
        runCatching { container.store.save(record) }
        container.store.refresh()
        ActiveJourney.mutable.value = ActiveJourney.mutable.value?.copy(meter = engine.meter(), finished = true)
        sendDisplay() // the module holds the final time
        container.esp32.stop()
        onFinished(record.id)
        scope.cancel()
        executor.shutdown()
    }

    /** Hard stop when the service is destroyed without finishing (e.g. the system killed it). */
    fun dispose() {
        if (!executor.isShutdown) {
            runCatching {
                kotlinx.coroutines.runBlocking(engineDispatcher) {
                    runCatching { logger?.close() }
                    container.store.save(engine.record())
                }
            }
            container.esp32.stop()
            scope.cancel()
            executor.shutdown()
        }
    }

    private fun publish() {
        ActiveJourney.mutable.value = ActiveJourneyState(
            journeyId = engine.id,
            meter = engine.meter(),
            gpsFix = gpsFix,
            conditionsOk = conditionsOk,
            trafficApi = container.tomTom.isConfigured,
            finished = engine.phase.isFinal,
            simulated = simulation != null,
            module = container.esp32.state.value,
        )
    }

}
