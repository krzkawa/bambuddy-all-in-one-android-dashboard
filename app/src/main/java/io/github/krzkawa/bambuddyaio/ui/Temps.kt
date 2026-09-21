package io.github.krzkawa.bambuddyaio.ui

/**
 * Working out what temperature he actually asked for.
 *
 * Every send here is an MQTT command to the printer, so the screen aims to
 * send one per intention rather than one per tap: the step buttons move a
 * pending figure and a single command follows once he stops, and the keypad
 * sets it outright. Android-free so the arithmetic can be tested.
 */
object Temps {

    /** How long to wait after the last tap before telling the printer. */
    const val SETTLE_MS = 600L

    /** Ceilings the app will not send past, per heater. */
    const val NOZZLE_MAX = 300
    const val BED_MAX = 120
    const val CHAMBER_MAX = 60

    fun clamp(value: Int, max: Int): Int = value.coerceIn(0, max)

    /**
     * Where a step lands.
     *
     * Steps run from what he last asked for, not from what the printer happens
     * to be reporting at that instant: a nozzle climbing through 160° would
     * otherwise turn a second +10 into 170 rather than the 220 he was
     * building up to.
     */
    fun step(pending: Int, by: Int, max: Int): Int = clamp(pending + by, max)

    /**
     * A typed target, or null when it is not a temperature.
     *
     * A number above the heater's ceiling is a slip, not an instruction, so it
     * is refused rather than quietly clamped — he would never learn that the
     * printer was sent something else.
     */
    fun typed(text: String?, max: Int): Int? {
        val digits = text?.trim()?.removeSuffix("°")?.trim() ?: return null
        val value = digits.toIntOrNull() ?: return null
        return if (value in 0..max) value else null
    }

    /** Where a pending figure starts: the target the printer already has. */
    fun startingPoint(target: Double?): Int = ((target ?: 0.0).toInt()).coerceAtLeast(0)
}
