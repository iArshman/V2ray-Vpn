package com.hollow.vpn.vpn

/**
 * Decides when the active server has stalled or collapsed in throughput so the tunnel
 * can automatically switch to the next verified server in the reserve.
 *
 * Idle connections (low uplink) never trigger a switch — only active requests with
 * stalled or severely degraded downlink over [Config.badSamplesToSwitch] consecutive ticks.
 */
class SmartSwitch(
    private val referenceMbps: Double = 0.0,
    private val config: Config = Config()
) {
    data class Config(
        val requestFloorBps: Long = 2_000L,
        val stallFloorBps: Long = 4_000L,
        val slowFraction: Double = 0.25,
        val badSamplesToSwitch: Int = 8,
        val cooldownMillis: Long = 45_000L,
        val maxSwitchesPerHour: Int = 4
    )

    sealed interface Verdict {
        data object Stay : Verdict
        data class Switch(val reason: String) : Verdict
    }

    private var badSamples = 0
    private var lastSwitchMillis = 0L
    private val switchTimes = ArrayDeque<Long>()

    private val slowFloorMbps: Double
        get() = if (referenceMbps <= 0.0) 0.0 else referenceMbps * config.slowFraction

    fun resetSamples() {
        badSamples = 0
    }

    fun onSample(upBytesPerSec: Long, downBytesPerSec: Long, nowMillis: Long): Verdict {
        if (!canSwitch(nowMillis)) {
            badSamples = 0
            return Verdict.Stay
        }

        // Idle phone / pocket state: never switch when no outbound requests are leaving.
        if (upBytesPerSec < config.requestFloorBps) {
            badSamples = 0
            return Verdict.Stay
        }

        val stalled = downBytesPerSec < config.stallFloorBps
        val downMbps = downBytesPerSec / 1_048_576.0
        val slow = slowFloorMbps > 0.0 && downMbps < slowFloorMbps

        if (!stalled && !slow) {
            badSamples = 0
            return Verdict.Stay
        }

        badSamples++
        if (badSamples < config.badSamplesToSwitch) {
            return Verdict.Stay
        }

        badSamples = 0
        lastSwitchMillis = nowMillis
        switchTimes.addLast(nowMillis)
        val reason = if (stalled) {
            "Server stalled (${config.badSamplesToSwitch}s without response)"
        } else {
            "Server slowed below 25% of benchmark"
        }
        return Verdict.Switch(reason)
    }

    private fun canSwitch(nowMillis: Long): Boolean {
        if (lastSwitchMillis > 0L && nowMillis - lastSwitchMillis < config.cooldownMillis) {
            return false
        }
        val oneHourAgo = nowMillis - 3_600_000L
        while (switchTimes.isNotEmpty() && switchTimes.first() < oneHourAgo) {
            switchTimes.removeFirst()
        }
        return switchTimes.size < config.maxSwitchesPerHour
    }
}
