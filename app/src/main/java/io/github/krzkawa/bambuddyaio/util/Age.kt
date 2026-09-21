package io.github.krzkawa.bambuddyaio.util

/**
 * How old a reading is, in the shortest words that are still honest.
 *
 * The dashboard's numbers look equally confident whether they arrived a second
 * ago or during the last poll that worked, so every stale figure gets one of
 * these next to it.
 */
fun ago(millis: Long): String {
    val seconds = millis / 1000
    return when {
        millis < 0 -> "just now"
        seconds < 2 -> "just now"
        seconds < 60 -> "${seconds}s ago"
        seconds < 3600 -> "${seconds / 60}m ago"
        seconds < 86_400 -> {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            if (m == 0L) "${h}h ago" else "${h}h ${m}m ago"
        }
        else -> "${seconds / 86_400}d ago"
    }
}
