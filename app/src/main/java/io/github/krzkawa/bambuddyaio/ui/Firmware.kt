package io.github.krzkawa.bambuddyaio.ui

import android.content.Context
import android.widget.TextView
import io.github.krzkawa.bambuddyaio.net.Api
import io.github.krzkawa.bambuddyaio.net.Repo
import io.github.krzkawa.bambuddyaio.util.bool
import io.github.krzkawa.bambuddyaio.util.objects
import io.github.krzkawa.bambuddyaio.util.str
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Which firmware each printer runs and whether Bambu has published a newer one.
 *
 * A badge and nothing more: installing firmware belongs on the printer's own
 * screen, where he can see it happen.
 */
object Firmware {

    data class Info(
        val printerId: Int,
        val current: String?,
        val latest: String?,
        val updateAvailable: Boolean,
        val notes: String?
    )

    private val _info = MutableStateFlow<Map<Int, Info>>(emptyMap())
    val info: StateFlow<Map<Int, Info>> = _info.asStateFlow()

    private var loadedAt = 0L

    fun parse(response: JSONObject): Map<Int, Info> =
        response.objects("updates").mapNotNull { u ->
            val id = u.optInt("printer_id", -1)
            if (id < 0) null
            else id to Info(
                printerId = id,
                current = u.str("current_version"),
                latest = u.str("latest_version"),
                // The server compares versions itself; a printer it could not
                // read a version from never counts as out of date.
                updateAvailable = u.bool("update_available") && u.str("current_version") != null,
                notes = u.str("release_notes")
            )
        }.toMap()

    /**
     * Blocking. The server fetches Bambu's download page for this, and a new
     * release comes out every few weeks, so a few times a day is plenty.
     */
    @Synchronized
    fun load(api: Api, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - loadedAt < MIN_GAP_MS) return
        loadedAt = now
        try {
            _info.value = parse(api.firmwareUpdates())
        } catch (e: Exception) {
            loadedAt = now - MIN_GAP_MS + RETRY_MS
        }
    }

    fun update(printerId: Int): Info? = _info.value[printerId]?.takeIf { it.updateAvailable }

    fun signature(printerId: Int): String = update(printerId)?.latest.orEmpty()

    /** The small "Update 01.09.00.00" beside a printer's name, or null when it is current. */
    fun badge(ctx: Context, printerId: Int): TextView? {
        val info = update(printerId) ?: return null
        val badge = Ui.tiny(ctx, if (info.latest != null) "Update ${info.latest}" else "Update available")
        badge.setTextColor(Ui.dimColor(ctx))
        badge.setPadding(Ui.dp(ctx, Ui.XS), Ui.dp(ctx, Ui.XS), Ui.dp(ctx, Ui.XS), Ui.dp(ctx, Ui.XS))
        badge.isClickable = true
        badge.setOnClickListener { explain(ctx, info) }
        return badge
    }

    fun explain(ctx: Context, info: Info) {
        val message = StringBuilder()
        message.append("${Repo.printerName(info.printerId)} runs ${info.current ?: "an unknown version"}")
        if (info.latest != null) message.append("; ${info.latest} is out")
        message.append(". Install it from the printer's screen or from Bambuddy.")
        info.notes?.let { plain(it) }?.takeIf { it.isNotBlank() }?.let {
            message.append("\n\n").append(if (it.length > NOTES_MAX) it.take(NOTES_MAX).trimEnd() + "…" else it)
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Firmware update")
            .setMessage(message.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    /** Bambu's release notes arrive as HTML; a dialog wants the words. */
    fun plain(notes: String): String =
        notes.replace(Regex("(?i)<br\\s*/?>|</p>|</li>"), "\n")
            .replace(Regex("(?i)<li[^>]*>"), "• ")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString("\n")

    private const val MIN_GAP_MS = 6 * 60 * 60_000L
    private const val RETRY_MS = 5 * 60_000L
    private const val NOTES_MAX = 1200

    const val POLL_MS = 5 * 60_000L
}
