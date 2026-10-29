package com.invisiblejourney.data.sensors.esp32

import com.invisiblejourney.domain.MeterDisplay
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * The BLE protocol of docs/05_ESP32_INTEGRATION.md (the source of truth; firmware/IJ_Meter mirrors it).
 * Pure Kotlin, no Android imports, so it is unit-tested. All multi-byte fields are little-endian, and
 * every packet fits in BLE's default 20-byte payload, so no MTU negotiation is needed.
 */
object Esp32Protocol {
    val SERVICE: UUID = UUID.fromString("a3c10001-7f1e-4b6a-9c2d-5e9f3b0d1a00")
    val DEVICE_INFO: UUID = UUID.fromString("a3c10002-7f1e-4b6a-9c2d-5e9f3b0d1a00")
    val DATA: UUID = UUID.fromString("a3c10003-7f1e-4b6a-9c2d-5e9f3b0d1a00")
    val COMMAND: UUID = UUID.fromString("a3c10004-7f1e-4b6a-9c2d-5e9f3b0d1a00")
    val STATUS: UUID = UUID.fromString("a3c10005-7f1e-4b6a-9c2d-5e9f3b0d1a00")

    /** Standard descriptor that switches notifications on. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val VERSION = 1
    const val HEADER_BYTES = 8

    // Data packet types
    const val TYPE_GAS = 0x06
    const val TYPE_HEARTBEAT = 0x7F

    // Command opcodes
    const val OP_START_STREAM = 0x01
    const val OP_STOP_STREAM = 0x02
    const val OP_TIME_SYNC = 0x03
    const val OP_DISPLAY_STATE = 0x13

    sealed interface Packet {
        val seq: Int
        val tMs: Long
    }

    /**
     * MQ-2 reading. [ratio] = Rs/R0 (clean air ≈ 9.83). Only usable once [warm] (heater has run a
     * few minutes) and [calibrated] (the module has an R0 baseline).
     */
    data class Gas(
        override val seq: Int,
        override val tMs: Long,
        val adcRaw: Int,
        val millivolts: Int,
        val ratio: Float,
        val warm: Boolean,
        val calibrated: Boolean,
    ) : Packet {
        val usable: Boolean get() = warm && calibrated && ratio > 0f
    }

    data class Heartbeat(override val seq: Int, override val tMs: Long) : Packet

    /** Decodes one `Data` notification; null for a truncated, wrong-version or unknown packet. */
    fun parse(bytes: ByteArray): Packet? {
        if (bytes.size < HEADER_BYTES) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val type = b.get().toInt() and 0xFF
        val ver = b.get().toInt() and 0xFF
        if (ver != VERSION) return null
        val seq = b.short.toInt() and 0xFFFF
        val tMs = b.int.toLong() and 0xFFFF_FFFFL
        return when (type) {
            TYPE_GAS -> {
                if (b.remaining() < 7) return null
                val raw = b.short.toInt() and 0xFFFF
                val mv = b.short.toInt() and 0xFFFF
                val ratio = (b.short.toInt() and 0xFFFF) / 1000f
                val flags = b.get().toInt()
                Gas(seq, tMs, raw, mv, ratio, warm = flags and 1 != 0, calibrated = flags and 2 != 0)
            }
            TYPE_HEARTBEAT -> Heartbeat(seq, tMs)
            else -> null
        }
    }

    fun startStream(): ByteArray = byteArrayOf(OP_START_STREAM.toByte())

    fun stopStream(): ByteArray = byteArrayOf(OP_STOP_STREAM.toByte())

    fun timeSync(epochMs: Long): ByteArray =
        ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).put(OP_TIME_SYNC.toByte()).putLong(epochMs).array()

    /**
     * What the module's display and LEDs show. [rate] is how fast the journey clock runs (a sped-up
     * demo ride sends >1), so the module's own 1-second ticking between updates keeps pace.
     */
    fun displayState(d: MeterDisplay, rate: Int = 1): ByteArray =
        ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN)
            .put(OP_DISPLAY_STATE.toByte())
            .put(d.mode.code.toByte())
            .putInt(d.etaLeftS)
            .putInt(d.waitS.coerceAtLeast(0))
            .putInt(d.elapsedS.coerceAtLeast(0))
            .put(rate.coerceIn(1, 255).toByte())
            .array()
}
