package com.invisiblejourney.data.sensors.esp32

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BLE link to the IJ meter module (ESP32 + 4-digit display + LEDs + MQ-2), phone as central.
 *
 * Finds the first module advertising the Journey Service, connects, turns on `Data` notifications,
 * syncs the clock and starts the stream; reconnects on its own if the link drops. Every GATT call
 * and callback runs on one background thread, so there are no locks, and writes go out one at a
 * time (Android drops a write sent while another is in flight).
 */
class Esp32Link(private val context: Context) {

    enum class State { OFF, UNAVAILABLE, SEARCHING, CONNECTED }

    private val _state = MutableStateFlow(State.OFF)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _packets = MutableSharedFlow<Esp32Protocol.Packet>(extraBufferCapacity = 32)
    val packets: SharedFlow<Esp32Protocol.Packet> = _packets.asSharedFlow()

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    // Everything below is touched only on [handler]'s thread.
    private var running = false
    private var scanCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var command: BluetoothGattCharacteristic? = null
    private var ready = false
    private var writingSince = 0L
    private val queue = ArrayDeque<ByteArray>()
    /** Only the newest display state matters, so it replaces itself instead of queueing up. */
    private var pendingDisplay: ByteArray? = null

    private val retry = Runnable { connectOrScan() }
    private val scanTimeout = Runnable {
        stopScan()
        retryIn(SCAN_PAUSE_MS)
    }

    fun start() {
        if (thread != null) return
        val t = HandlerThread("esp32-ble").also { it.start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        h.post {
            running = true
            connectOrScan()
        }
    }

    /** Disconnects after a moment, so the last display state still reaches the module. */
    fun stop() {
        val t = thread ?: return
        val h = handler ?: return
        thread = null
        handler = null
        h.post {
            running = false
            h.removeCallbacks(retry)
            h.removeCallbacks(scanTimeout)
            stopScan()
        }
        h.postDelayed({
            closeGatt()
            _state.value = State.OFF
            t.quitSafely()
        }, FLUSH_BEFORE_CLOSE_MS)
    }

    fun sendDisplay(packet: ByteArray) {
        handler?.post {
            pendingDisplay = packet
            pump()
        }
    }

    // --- connection -----------------------------------------------------------------------------

    private fun hasPermissions(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun retryIn(ms: Long) {
        val h = handler ?: return
        h.removeCallbacks(retry)
        if (running) h.postDelayed(retry, ms)
    }

    @SuppressLint("MissingPermission") // checked in hasPermissions()
    private fun connectOrScan() {
        if (!running || gatt != null || scanCallback != null) return
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val scanner = adapter?.takeIf { it.isEnabled && hasPermissions() }?.bluetoothLeScanner
        if (scanner == null) {
            // Bluetooth off or no permission yet: keep checking, the rider may switch it on mid-ride.
            _state.value = State.UNAVAILABLE
            retryIn(UNAVAILABLE_RETRY_MS)
            return
        }
        _state.value = State.SEARCHING
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handler?.post {
                    if (scanCallback !== this) return@post
                    stopScan()
                    connect(result.device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                handler?.post {
                    if (scanCallback !== this) return@post
                    scanCallback = null
                    retryIn(SCAN_PAUSE_MS)
                }
            }
        }
        scanCallback = cb
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(Esp32Protocol.SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(listOf(filter), settings, cb) }.onFailure {
            scanCallback = null
            retryIn(SCAN_PAUSE_MS)
            return
        }
        handler?.postDelayed(scanTimeout, SCAN_WINDOW_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        handler?.removeCallbacks(scanTimeout)
        val cb = scanCallback ?: return
        scanCallback = null
        runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(cb)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        if (!running) return
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler)
        if (gatt == null) retryIn(RECONNECT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        val g = gatt ?: return
        gatt = null
        command = null
        ready = false
        writingSince = 0L
        queue.clear()
        runCatching { g.disconnect() }
        runCatching { g.close() }
    }

    // --- writes -------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun pump() {
        val g = gatt ?: return
        val c = command ?: return
        if (!ready) return
        // A write whose callback never came (link half-dead) must not block the queue forever.
        if (writingSince != 0L && SystemClock.elapsedRealtime() - writingSince < WRITE_TIMEOUT_MS) return
        val next = queue.removeFirstOrNull() ?: pendingDisplay?.also { pendingDisplay = null } ?: run {
            writingSince = 0L
            return
        }
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, next, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                c.value = next
                g.writeCharacteristic(c)
            }
        }
        writingSince = if (ok) SystemClock.elapsedRealtime() else 0L
    }

    private fun onData(bytes: ByteArray?) {
        val p = bytes?.let(Esp32Protocol::parse) ?: return
        _packets.tryEmit(p)
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) return
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (!g.discoverServices()) closeAndRetry()
            } else {
                closeAndRetry()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            val service = g.getService(Esp32Protocol.SERVICE)
            val data = service?.getCharacteristic(Esp32Protocol.DATA)
            val cccd = data?.getDescriptor(Esp32Protocol.CCCD)
            command = service?.getCharacteristic(Esp32Protocol.COMMAND)
            if (status != BluetoothGatt.GATT_SUCCESS || data == null || cccd == null || command == null) {
                closeAndRetry()
                return
            }
            g.setCharacteristicNotification(data, true)
            val on = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, on) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = on
                    g.writeDescriptor(cccd)
                }
            }
            if (!ok) closeAndRetry()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (g !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                closeAndRetry()
                return
            }
            ready = true
            _state.value = State.CONNECTED
            queue.addLast(Esp32Protocol.timeSync(System.currentTimeMillis()))
            queue.addLast(Esp32Protocol.startStream())
            pump()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (g !== gatt) return
            writingSince = 0L
            pump()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == Esp32Protocol.DATA) onData(value)
        }

        @Deprecated("Used below Android 13 only")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && characteristic.uuid == Esp32Protocol.DATA) onData(characteristic.value)
        }
    }

    private fun closeAndRetry() {
        closeGatt()
        if (running) {
            _state.value = State.SEARCHING
            retryIn(RECONNECT_MS)
        }
    }

    private companion object {
        const val SCAN_WINDOW_MS = 20_000L
        // Android blocks apps that start more than 5 scans in 30 s, so pauses stay long.
        const val SCAN_PAUSE_MS = 10_000L
        const val UNAVAILABLE_RETRY_MS = 10_000L
        const val RECONNECT_MS = 3_000L
        const val WRITE_TIMEOUT_MS = 3_000L
        const val FLUSH_BEFORE_CLOSE_MS = 1_500L
    }
}
