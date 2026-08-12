package dev.axon.android.inference

import android.content.Context
import android.os.BatteryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Measures the energy a piece of work costs (spec §14.2, extending it).
 *
 * ## Why this exists when the spec does not ask for it
 *
 * On-device agent papers report latency and task success. Almost none report
 * **joules**, and on a battery-powered device joules are the binding constraint:
 * a user does not abandon an assistant because it took 60 s, they abandon it
 * because it ate 8% of their battery doing so.
 *
 * The omission is also what makes it worth measuring. It reframes contribution
 * C1′ in terms that matter to the person holding the phone — a compiled skill
 * replay costs *no inference energy at all*, so the saving is not merely time
 * but charge — and it gives the scaffolding-versus-scale trade a second axis,
 * since a bigger model does not merely take longer, it costs more per task.
 *
 * ## What the numbers mean, and what they do not
 *
 * `BATTERY_PROPERTY_CURRENT_NOW` is instantaneous current in microamps, sampled
 * by the kernel from the fuel gauge. Multiplied by pack voltage and integrated
 * over a window it gives energy — but it is **whole-device** current, not
 * per-process. Anything else running on the phone lands in the measurement.
 *
 * The honest way to use it is therefore differential: sample an idle baseline,
 * sample during the work, and report the delta. That subtracts the screen, the
 * radios and background apps to first order. It is not a power monitor, and the
 * paper should say so — but it is enough to compare configurations on the same
 * device, which is the comparison that matters.
 *
 * Sign conventions vary by OEM (some report discharge as negative, some
 * positive), and the value can be reported in mA rather than µA on older
 * kernels. [isPlausible] guards against publishing a number from a device whose
 * fuel gauge is not reporting usefully at all.
 */
class EnergyProbe(context: Context) {

    private val batteryManager =
        context.applicationContext.getSystemService(BatteryManager::class.java)

    /** Instantaneous current draw in microamps. Sign is normalised to positive = draining. */
    fun currentMicroAmps(): Long {
        val raw = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            ?: return 0
        return kotlin.math.abs(raw)
    }

    /** Remaining charge in microamp-hours, the most reliable cumulative signal. */
    fun chargeCounterMicroAmpHours(): Long =
        batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: 0

    /**
     * Is the fuel gauge reporting usefully?
     *
     * Many budget devices return 0 or a constant. Publishing energy figures from
     * such a device would be fabrication, so callers check this and omit the
     * metric rather than reporting a plausible-looking zero.
     */
    fun isPlausible(): Boolean = currentMicroAmps() > 1_000L

    /**
     * Measure the energy consumed by [block], relative to an idle baseline.
     *
     * The baseline is sampled immediately before, on the assumption that
     * background load is roughly stationary across the few seconds involved. That
     * assumption is weakest for short blocks, which is why [EnergyMeasurement]
     * carries the duration — a reader can discount a 200 ms sample themselves.
     */
    suspend fun <T> measure(
        baselineSamples: Int = 3,
        baselineDelayMs: Long = 200,
        block: suspend () -> T,
    ): Pair<T, EnergyMeasurement> {
        // Any failure in the probe itself degrades to "unavailable" and lets the
        // work proceed. Instrumentation that can break the experiment is worse
        // than no instrumentation.
        val chargeBefore = runCatching { chargeCounterMicroAmpHours() }.getOrDefault(0L)

        var baselineSum = 0L
        repeat(baselineSamples) {
            baselineSum += currentMicroAmps()
            delay(baselineDelayMs)
        }
        val baselineUa = baselineSum / baselineSamples.coerceAtLeast(1)

        val startMs = System.currentTimeMillis()
        // Concurrent, because the sampler writes from another dispatcher while
        // the measured work runs here. A plain ArrayList corrupted under that
        // access and took the whole run down — an energy figure is a
        // nice-to-have and must never destroy the measurement it decorates.
        val samples = java.util.concurrent.CopyOnWriteArrayList<Long>()

        // Sampled on a separate coroutine so the measured work is not slowed by
        // the measurement — which would inflate exactly the number being taken.
        val scope = CoroutineScope(Dispatchers.IO)
        val sampler = scope.launch {
            while (isActive) {
                runCatching { samples.add(currentMicroAmps()) }
                delay(SAMPLE_INTERVAL_MS)
            }
        }

        val value = try {
            block()
        } finally {
            sampler.cancelAndJoin()
        }

        val durationMs = System.currentTimeMillis() - startMs
        val chargeAfter = runCatching { chargeCounterMicroAmpHours() }.getOrDefault(0L)
        val snapshot = samples.toList()
        val meanUa = if (snapshot.isEmpty()) 0L else snapshot.average().toLong()

        return value to EnergyMeasurement(
            durationMs = durationMs,
            meanCurrentUa = meanUa,
            baselineCurrentUa = baselineUa,
            chargeDeltaUah = chargeBefore - chargeAfter,
            samples = snapshot.size,
            plausible = meanUa > 1_000L,
        )
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 250L

        /**
         * Assumed pack voltage.
         *
         * Android exposes charge and current reliably but not instantaneous
         * voltage on every device. 3.85 V is the nominal midpoint for a Li-ion
         * cell and introduces at most a few percent error across the usable
         * range — acceptable when the figures are used comparatively, and stated
         * so that nobody mistakes these for absolute measurements.
         */
        const val NOMINAL_VOLTAGE_MV = 3_850
    }
}

/**
 * One energy measurement.
 *
 * Carries the baseline and the sample count alongside the result so a reader can
 * judge how much to trust it, rather than being handed a bare number.
 */
data class EnergyMeasurement(
    val durationMs: Long,
    val meanCurrentUa: Long,
    val baselineCurrentUa: Long,
    val chargeDeltaUah: Long,
    val samples: Int,

    /** False when the fuel gauge is not reporting usefully; omit the metric. */
    val plausible: Boolean,
) {
    /** Current attributable to the work, above idle. */
    val deltaCurrentUa: Long get() = (meanCurrentUa - baselineCurrentUa).coerceAtLeast(0)

    /**
     * Energy above idle, in joules.
     *
     * `J = A × V × s`. Reported as a delta so the screen, radios and background
     * apps are subtracted to first order.
     */
    val joulesAboveIdle: Double
        get() = (deltaCurrentUa / 1_000_000.0) * (3.85) * (durationMs / 1000.0)

    /** Total energy including idle draw, for context. */
    val joulesTotal: Double
        get() = (meanCurrentUa / 1_000_000.0) * (3.85) * (durationMs / 1000.0)

    /**
     * Tasks per full charge, extrapolated from a 5000 mAh pack.
     *
     * The figure a user would actually feel, and the one that makes C1′ concrete:
     * if a cold planning step costs *n* joules and a compiled replay costs
     * approximately none, the difference is how many times the assistant can be
     * asked before the phone is flat.
     */
    fun tasksPerCharge(packMah: Int = 5_000): Int {
        val packJoules = (packMah / 1000.0) * 3.85 * 3600
        return if (joulesAboveIdle <= 0) 0 else (packJoules / joulesAboveIdle).toInt()
    }

    fun render(): String = buildString {
        if (!plausible) {
            append("energy: unavailable (fuel gauge not reporting)")
            return@buildString
        }
        append("energy: %.2f J above idle".format(joulesAboveIdle))
        append(" (%.2f J total, ".format(joulesTotal))
        append("${deltaCurrentUa / 1000} mA over ${baselineCurrentUa / 1000} mA idle, ")
        append("$samples samples)")
    }
}
