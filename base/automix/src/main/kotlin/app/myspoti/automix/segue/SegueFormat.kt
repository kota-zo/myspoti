/* SPDX-License-Identifier: GPL-3.0-only */
package app.myspoti.automix.segue

/**
 * The scales of the plan format. They define what a stored number means, so they are constants, not
 * tuning; every value is justified in docs/automix/segue-design.md. Policy values are in [SegueTuning].
 */
object SegueFormat {
    const val SCHEMA_PREFIX = "segue/"
    const val MAJOR = 1
    const val SCHEMA = "$SCHEMA_PREFIX$MAJOR"

    /** Nominal speed on the rate scale: parts per million, so rounding stays under 32 µs over 64 s. */
    const val RATE_NOMINAL = 1_000_000L

    /** Knot positions run from 0 to this over the window. */
    const val T_MAX = 1_000_000

    /** Knot values are stored in thousandths of their unit. */
    const val VALUE_SCALE = 1_000L

    /** `ease:<k>` is position^(k / this). */
    const val EASE_UNIT = 1_000

    /** The number a silent knot stands for while a ramp runs towards or away from it. */
    const val LEVEL_FLOOR_DB = -60.0

    /** The cutoff a null sweep knot stands for: the filter is fully open. */
    const val SWEEP_OPEN_HZ = 20_000.0

    /** The cutoff a null rise knot stands for: the high-pass lets everything through. */
    const val RISE_OPEN_HZ = 20.0

    /** The longest echo a plan may ask for. */
    const val ECHO_MAX_US = 2_000_000L
}
