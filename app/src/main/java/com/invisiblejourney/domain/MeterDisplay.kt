package com.invisiblejourney.domain

import com.invisiblejourney.domain.model.MeterState
import com.invisiblejourney.domain.model.Phase

/**
 * What the ESP32's 4-digit display and its two LEDs should show for a [MeterState].
 *
 * - [Mode.MOVING]: green LED on, the display counts the ETA down ([etaLeftS]; negative = late).
 * - [Mode.WAITING]: red LED blinks, the display counts this wait up ([waitS]).
 * - [Mode.FINISHED]: green LED on, the display holds the total journey time ([elapsedS]).
 */
data class MeterDisplay(
    val mode: Mode,
    val etaLeftS: Int,
    val waitS: Int,
    val elapsedS: Int,
) {
    enum class Mode(val code: Int) { IDLE(0), MOVING(1), WAITING(2), FINISHED(3) }

    companion object {
        fun from(meter: MeterState, promisedEtaMin: Int): MeterDisplay {
            val elapsedS = (meter.elapsedMs / 1000).toInt()
            val mode = when {
                meter.phase == Phase.CANCELLED -> Mode.IDLE
                meter.phase.isFinal -> Mode.FINISHED
                meter.waitingNow -> Mode.WAITING
                else -> Mode.MOVING
            }
            return MeterDisplay(
                mode = mode,
                etaLeftS = promisedEtaMin * 60 - elapsedS,
                waitS = (meter.currentWaitMs / 1000).toInt(),
                elapsedS = elapsedS,
            )
        }
    }
}
