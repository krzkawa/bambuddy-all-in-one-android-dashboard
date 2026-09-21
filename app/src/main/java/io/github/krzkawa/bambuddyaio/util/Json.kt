package io.github.krzkawa.bambuddyaio.util

import org.json.JSONArray
import org.json.JSONObject

/** Null-friendly readers. The API omits or nulls plenty of fields per model. */

fun JSONObject.str(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

fun JSONObject.int(key: String): Int? = if (isNull(key)) null else optInt(key)

fun JSONObject.dbl(key: String): Double? = if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

fun JSONObject.bool(key: String): Boolean = optBoolean(key, false)

fun JSONObject.objects(key: String): List<JSONObject> = optJSONArray(key).objects()

fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    val out = ArrayList<JSONObject>(length())
    for (i in 0 until length()) optJSONObject(i)?.let { out.add(it) }
    return out
}

/** Temperature from the status's `temperatures` map. */
fun JSONObject.temp(key: String): Double? = optJSONObject("temperatures")?.dbl(key)
