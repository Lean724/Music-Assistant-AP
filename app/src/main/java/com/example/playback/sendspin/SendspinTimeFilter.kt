package com.example.playback.sendspin

/**
 * Two-dimensional Kalman filter for NTP-style client/server time synchronization, matching the
 * algorithm documented for the Sendspin protocol (github.com/Sendspin/time-filter,
 * github.com/Sendspin/aiosendspin's time_sync.py).
 *
 * Tracks a 2D state [offset, drift] between the local (client) clock and the server's clock:
 *  - offset: how far ahead/behind the server clock is vs. the local clock, in microseconds.
 *  - drift: how fast that offset is changing, in microseconds of offset per microsecond of time
 *    (i.e. a dimensionless clock-rate-difference ratio).
 *
 * Each round-trip exchange (client/time -> server/time) yields:
 *  - T1: client's local time when the request was sent
 *  - T2: server's local time when it received the request
 *  - T3: server's local time when it sent the response
 *  - T4: client's local time when the response arrived
 *
 * From these:
 *  - measurement = ((T2 - T1) + (T3 - T4)) / 2   (estimated clock offset, server ahead if positive)
 *  - max_error   = ((T4 - T1) - (T3 - T2)) / 2   (half the round-trip network delay; the
 *                  measurement's uncertainty envelope)
 *
 * This is not a byte-for-byte port of the reference implementation (which isn't fully published),
 * but a faithful reimplementation of the documented two-stage predict/correct Kalman algorithm
 * with the same adaptive-forgetting behavior for recovering from outliers/clock jumps.
 */
class SendspinTimeFilter(
    private val processStdDevUs: Double = 50.0,
    private val driftProcessStdDev: Double = 1e-6,
    private val forgetFactor: Double = 4.0,
    private val adaptiveForgettingCutoff: Double = 0.75,
    private val minSamplesBeforeAdaptive: Int = 20
) {
    // State vector: [offset (us), drift (unitless rate)]
    private var offset = 0.0
    private var drift = 0.0

    // 2x2 covariance matrix, stored flat: [p00, p01, p10, p11]
    private var p00 = 1e12 // start very uncertain about offset
    private var p01 = 0.0
    private var p10 = 0.0
    private var p11 = 1e-6 // start fairly confident drift is near zero

    private var lastUpdateUs: Long = 0L
    private var sampleCount = 0

    @Volatile var isSynced: Boolean = false
        private set

    /**
     * Feeds a new NTP-style measurement into the filter.
     *
     * @param measurementUs estimated offset ((T2-T1)+(T3-T4))/2, in microseconds
     * @param maxErrorUs half round-trip delay ((T4-T1)-(T3-T2))/2, in microseconds (uncertainty)
     * @param timeAddedUs client-local timestamp (e.g. T4) when this measurement was taken, in microseconds
     */
    @Synchronized
    fun update(measurementUs: Double, maxErrorUs: Double, timeAddedUs: Long) {
        if (timeAddedUs == lastUpdateUs && sampleCount > 0) return // avoid divide-by-zero on duplicate timestamps

        val dtSec = if (sampleCount == 0) 0.0 else (timeAddedUs - lastUpdateUs) / 1_000_000.0

        // --- Predict step: project the offset forward using the drift model ---
        val offsetPred = offset + drift * (dtSec * 1_000_000.0)
        val driftPred = drift

        // Process noise scales with elapsed time (more time -> more uncertainty accrued)
        val qOffset = processStdDevUs * processStdDevUs * (1.0 + kotlin.math.abs(dtSec))
        val qDrift = driftProcessStdDev * driftProcessStdDev * (1.0 + kotlin.math.abs(dtSec))

        // P_pred = F P F^T + Q, with F = [[1, dt_us],[0,1]]
        val dtUs = dtSec * 1_000_000.0
        var pp00 = p00 + dtUs * (p10 + p01) + dtUs * dtUs * p11 + qOffset
        var pp01 = p01 + dtUs * p11
        var pp10 = p10 + dtUs * p11
        var pp11 = p11 + qDrift

        // --- Adaptive forgetting: if this measurement is a big outlier relative to its own
        // uncertainty, inflate the covariance so the filter "trusts" the new data more and
        // recovers quickly from clock jumps / long disconnects, instead of clinging to a stale estimate. ---
        val residual = measurementUs - offsetPred
        if (sampleCount >= minSamplesBeforeAdaptive && maxErrorUs > 0 &&
            kotlin.math.abs(residual) > adaptiveForgettingCutoff * maxErrorUs
        ) {
            pp00 *= forgetFactor
            pp01 *= forgetFactor
            pp10 *= forgetFactor
            pp11 *= forgetFactor
        }

        // --- Correct step: standard 1D-measurement Kalman update (H = [1, 0]) ---
        val r = maxErrorUs * maxErrorUs + 1.0 // measurement variance floor to avoid div-by-zero
        val s = pp00 + r
        val k0 = pp00 / s
        val k1 = pp10 / s

        offset = offsetPred + k0 * residual
        drift = driftPred + k1 * residual

        p00 = (1 - k0) * pp00
        p01 = (1 - k0) * pp01
        p10 = pp10 - k1 * pp00
        p11 = pp11 - k1 * pp01

        lastUpdateUs = timeAddedUs
        sampleCount++
        if (sampleCount >= 3) isSynced = true
    }

    /** Converts a local (client) timestamp, in microseconds, to the corresponding server timestamp. */
    @Synchronized
    fun computeServerTime(clientTimeUs: Long): Long {
        val dtUs = clientTimeUs - lastUpdateUs
        val projectedOffset = offset + drift * dtUs
        return clientTimeUs + projectedOffset.toLong()
    }

    /** Converts a server timestamp, in microseconds, to the corresponding local (client) timestamp. */
    @Synchronized
    fun computeClientTime(serverTimeUs: Long): Long {
        // Approximate inverse: since offset/drift are small relative to dt, one pass is sufficient.
        val approxClientTime = serverTimeUs - offset.toLong()
        val dtUs = approxClientTime - lastUpdateUs
        val projectedOffset = offset + drift * dtUs
        return serverTimeUs - projectedOffset.toLong()
    }

    @Synchronized
    fun reset() {
        offset = 0.0
        drift = 0.0
        p00 = 1e12
        p01 = 0.0
        p10 = 0.0
        p11 = 1e-6
        lastUpdateUs = 0L
        sampleCount = 0
        isSynced = false
    }
}
