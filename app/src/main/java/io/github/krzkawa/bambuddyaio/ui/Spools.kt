package io.github.krzkawa.bambuddyaio.ui

import io.github.krzkawa.bambuddyaio.nfc.OpenSpool
import io.github.krzkawa.bambuddyaio.util.bool
import io.github.krzkawa.bambuddyaio.util.dbl
import io.github.krzkawa.bambuddyaio.util.int
import io.github.krzkawa.bambuddyaio.util.str
import org.json.JSONObject

/**
 * The arithmetic and text behind the Spools screen, kept clear of Android so it
 * can be unit tested. The screen itself only lays these results out.
 */
object Spools {

    /** Archived spools are soft-deleted: the server stamps `archived_at`. */
    fun isArchived(spool: JSONObject): Boolean = spool.str("archived_at") != null

    /** Everything worth typing into the search box, in one lowercase haystack. */
    private fun haystack(spool: JSONObject): String = listOfNotNull(
        spool.str("brand"),
        spool.str("material"),
        spool.str("subtype"),
        spool.str("color_name"),
        spool.str("category"),
        spool.str("storage_location"),
        spool.str("note")
    ).joinToString(" ").lowercase()

    /**
     * Matches on every word of the query independently, so "pla black" finds
     * "Bambu · PLA Basic · Black" — which a plain substring search would not.
     */
    fun matches(spool: JSONObject, query: String): Boolean {
        val terms = query.trim().lowercase().split(' ').filter { it.isNotBlank() }
        if (terms.isEmpty()) return true
        val hay = haystack(spool)
        return terms.all { hay.contains(it) }
    }

    fun labelWeight(spool: JSONObject): Double = spool.dbl("label_weight") ?: 1000.0

    fun weightUsed(spool: JSONObject): Double = spool.dbl("weight_used") ?: 0.0

    /** What is still on the spool, by the server's own subtraction. */
    fun gramsLeft(spool: JSONObject): Double =
        (labelWeight(spool) - weightUsed(spool)).coerceAtLeast(0.0)

    /**
     * The resettable "total consumed" figure: lifetime use minus the baseline
     * that reset-consumed-counter moves. Not the same as [weightUsed].
     */
    fun consumed(spool: JSONObject): Double =
        (weightUsed(spool) - (spool.dbl("weight_used_baseline") ?: 0.0)).coerceAtLeast(0.0)

    /**
     * True once a manual weight correction has been saved. The server sets this
     * itself whenever `weight_used` is written, and while it is set the AMS no
     * longer syncs this spool's weight.
     */
    fun weightLocked(spool: JSONObject): Boolean = spool.bool("weight_locked")

    fun grams(value: Double?): String = if (value == null) "—" else "${Math.round(value)} g"

    /** The one-line summary under a spool's name on the list. */
    fun summary(spool: JSONObject): String {
        val bits = ArrayList<String>()
        bits.add("${grams(gramsLeft(spool))} left")
        if (spool.str("tray_uuid") != null || spool.str("tag_uid") != null) bits.add("tagged")
        spool.str("storage_location")?.let { bits.add(it) }
        if (weightLocked(spool)) bits.add("weight set by hand")
        if (isArchived(spool)) bits.add("archived")
        return bits.joinToString(" · ")
    }

    /**
     * Builds the PATCH body for an edit, carrying **only** the fields that
     * actually changed.
     *
     * That is not tidiness. Writing `weight_used` makes the server set
     * `weight_locked` too, which stops the AMS keeping this spool's weight up to
     * date — so resending an unchanged weight would quietly freeze a spool the
     * user never meant to touch.
     *
     * Returns null inside a success when nothing changed, and a failure when a
     * typed number is not usable.
     */
    fun editPayload(
        spool: JSONObject,
        gramsLeftText: String,
        labelWeightText: String,
        note: String,
        storageLocation: String?
    ): Result<JSONObject?> {
        val payload = JSONObject()

        val newLabel = labelWeightText.trim().toDoubleOrNull()
            ?: return Result.failure(IllegalArgumentException("Total weight needs to be a number"))
        if (newLabel <= 0) {
            return Result.failure(IllegalArgumentException("Total weight has to be more than zero"))
        }

        val newLeft = gramsLeftText.trim().toDoubleOrNull()
            ?: return Result.failure(IllegalArgumentException("Grams left needs to be a number"))
        if (newLeft < 0) {
            return Result.failure(IllegalArgumentException("Grams left cannot be negative"))
        }
        if (newLeft > newLabel) {
            return Result.failure(
                IllegalArgumentException("Grams left cannot be more than the total weight")
            )
        }

        if (Math.round(newLabel) != Math.round(labelWeight(spool))) {
            payload.put("label_weight", Math.round(newLabel).toInt())
        }

        // Grams left is what the user types; the server stores what was used.
        val newUsed = (newLabel - newLeft).coerceIn(0.0, newLabel)
        if (Math.round(newUsed) != Math.round(weightUsed(spool))) {
            payload.put("weight_used", Math.round(newUsed).toDouble())
        }

        val trimmedNote = note.trim()
        if (trimmedNote != spool.str("note").orEmpty()) {
            // An explicit null is how the server is told to clear a field; an
            // omitted key would leave the old value in place.
            payload.put("note", if (trimmedNote.isBlank()) JSONObject.NULL else trimmedNote)
        }

        val newLocation = storageLocation?.trim().orEmpty()
        if (newLocation != spool.str("storage_location").orEmpty()) {
            payload.put(
                "storage_location",
                if (newLocation.isBlank()) JSONObject.NULL else newLocation
            )
        }

        return Result.success(if (payload.length() == 0) null else payload)
    }

    /** "21 Sep" from an ISO timestamp, or an empty string when it is unreadable. */
    fun shortDate(iso: String?): String {
        val date = iso?.take(10) ?: return ""
        if (date.length != 10 || date[4] != '-' || date[7] != '-') return ""
        val month = date.substring(5, 7).toIntOrNull() ?: return ""
        val day = date.substring(8, 10).toIntOrNull() ?: return ""
        val names = listOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
        )
        if (month !in 1..12) return ""
        return "$day ${names[month - 1]}"
    }

    /** One line of usage history: when, what printed, and how much it took. */
    fun usageLine(entry: JSONObject): String {
        val bits = ArrayList<String>()
        shortDate(entry.str("created_at")).takeIf { it.isNotBlank() }?.let { bits.add(it) }
        bits.add(entry.str("print_name") ?: "Unnamed print")
        bits.add(grams(entry.dbl("weight_used")))
        entry.str("status")?.takeIf { it != "completed" }?.let { bits.add(it) }
        return bits.joinToString(" · ")
    }

    /**
     * The storage locations to offer, as (name to label) in the order the server
     * sorted them. The label carries the spool count; the name is what gets
     * saved, so the two are kept together rather than indexed apart.
     */
    fun locationOptions(locations: List<JSONObject>): List<Pair<String, String>> =
        locations.mapNotNull { loc ->
            val name = loc.str("name") ?: return@mapNotNull null
            val count = loc.int("spool_count") ?: 0
            name to if (count > 0) "$name  ($count)" else name
        }

    // ----------------------------------------------------------- running low

    /** Bambuddy's own default for `low_stock_threshold`, used when settings cannot be read. */
    const val DEFAULT_LOW_PERCENT = 20.0

    /** Per cent of the spool still on it, 0 to 100. */
    fun percentLeft(spool: JSONObject): Double {
        val label = labelWeight(spool)
        if (label <= 0) return 0.0
        return gramsLeft(spool) / label * 100.0
    }

    /**
     * Low by the same rule Bambuddy's inventory page uses: under the spool's own
     * `low_stock_threshold_pct` when it has one, the server-wide threshold otherwise.
     * Archived spools are never low; they are not on the shelf.
     */
    fun isLow(spool: JSONObject, globalPercent: Double): Boolean {
        if (isArchived(spool)) return false
        val threshold = spool.dbl("low_stock_threshold_pct") ?: globalPercent
        return percentLeft(spool) < threshold
    }

    /** The spools that are running low, emptiest first. */
    fun lowList(spools: List<JSONObject>, globalPercent: Double): List<JSONObject> =
        spools.filter { isLow(it, globalPercent) }.sortedBy { percentLeft(it) }

    /**
     * What identifies a filament you would buy again: material, variant, brand and colour,
     * ignoring case. Two rolls of the same Bambu PLA Basic Black are one line to buy.
     */
    private fun buyKey(material: String?, subtype: String?, brand: String?, colour: String?): String =
        listOf(material, subtype, brand, colour).joinToString("|") { it?.trim()?.lowercase().orEmpty() }

    /** True when this spool's filament is already on the list and not yet received. */
    fun onShoppingList(spool: JSONObject, items: List<JSONObject>): Boolean {
        val key = buyKey(spool.str("material"), spool.str("subtype"), spool.str("brand"), spool.str("color_name"))
        return items.any {
            it.str("status") != "received" &&
                buyKey(it.str("material"), it.str("subtype"), it.str("brand"), it.str("color_name")) == key
        }
    }

    /** The body of `POST /inventory/shopping-list` for another roll of this spool. */
    fun shoppingItem(spool: JSONObject): JSONObject {
        val item = JSONObject()
            .put("material", spool.str("material") ?: "Filament")
            .put("quantity_spools", 1)
        spool.str("subtype")?.let { item.put("subtype", it) }
        spool.str("brand")?.let { item.put("brand", it) }
        spool.str("color_name")?.let { item.put("color_name", it) }
        return item
    }

    /** "2 × Bambu · PLA Basic · Black" for one shopping-list line. */
    fun shoppingLine(item: JSONObject): String {
        val name = listOfNotNull(
            item.str("brand"),
            item.str("subtype")?.let { sub ->
                val material = item.str("material")
                if (material != null && !sub.startsWith(material, ignoreCase = true)) "$material $sub" else sub
            } ?: item.str("material"),
            item.str("color_name")
        ).joinToString(" · ").ifBlank { "Filament" }
        val count = item.int("quantity_spools") ?: 1
        return if (count > 1) "$count × $name" else name
    }

    /** The next step for a shopping item, as (status to send, button label), or null once received. */
    fun nextShoppingStep(item: JSONObject): Pair<String, String>? = when (item.str("status") ?: "pending") {
        "pending" -> "purchased" to "Bought"
        "purchased" -> "received" to "Arrived"
        else -> null
    }

    fun shoppingStatusWord(item: JSONObject): String = when (item.str("status") ?: "pending") {
        "purchased" -> "Ordered"
        "received" -> "Arrived"
        else -> "To buy"
    }

    // --------------------------------------------------------------- sticker

    /**
     * The OpenSpool record for a sticker on this spool.
     *
     * Colour and material come from the spool; temperatures from the spool when it has
     * them and from the material's usual range when it does not, so a sticker never goes
     * out without them. Text is trimmed to lengths that keep the record inside an
     * NTAG215 with a wide margin.
     */
    fun stickerRecord(spool: JSONObject): OpenSpool.Record {
        val material = spool.str("material") ?: "PLA"
        val defaults = OpenSpool.defaultTemps(material)
        val colour = spool.str("rgba")?.removePrefix("#")?.take(6)?.uppercase()
            ?.takeIf { it.length == 6 && it.all { c -> c in '0'..'9' || c in 'A'..'F' } }
            ?: "FFFFFF"
        return OpenSpool.Record(
            type = material.take(24),
            colorHex = colour,
            brand = (spool.str("brand") ?: "Generic").take(32),
            minTemp = spool.int("nozzle_temp_min")?.takeIf { it > 0 } ?: defaults?.first,
            maxTemp = spool.int("nozzle_temp_max")?.takeIf { it > 0 } ?: defaults?.second
        )
    }
}
