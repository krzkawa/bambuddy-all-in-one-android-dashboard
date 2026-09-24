package io.github.krzkawa.bambuddyaio.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The running-low list, the shopping list and the sticker record, all off the Spools screen. */
class RunningLowTest {

    private fun spool(
        id: Int = 1,
        left: Double,
        label: Int = 1000,
        own: Int? = null,
        archived: Boolean = false
    ): JSONObject {
        val o = JSONObject()
            .put("id", id)
            .put("brand", "Bambu").put("material", "PLA").put("subtype", "Basic").put("color_name", "Black")
            .put("label_weight", label)
            .put("weight_used", label - left)
        own?.let { o.put("low_stock_threshold_pct", it) }
        if (archived) o.put("archived_at", "2026-09-20T10:00:00")
        return o
    }

    @Test
    fun `low means under the threshold, not at it`() {
        assertTrue(Spools.isLow(spool(left = 199.0), 20.0))
        assertFalse(Spools.isLow(spool(left = 200.0), 20.0))
    }

    @Test
    fun `a spool's own threshold beats the server's`() {
        assertTrue(Spools.isLow(spool(left = 400.0, own = 50), 20.0))
        assertFalse(Spools.isLow(spool(left = 100.0, own = 5), 20.0))
    }

    @Test
    fun `archived spools are never running low`() {
        assertFalse(Spools.isLow(spool(left = 0.0, archived = true), 20.0))
    }

    @Test
    fun `the list runs emptiest first`() {
        val list = Spools.lowList(
            listOf(spool(1, left = 150.0), spool(2, left = 900.0), spool(3, left = 20.0)),
            20.0
        )
        assertEquals(listOf(3, 1), list.map { it.optInt("id") })
    }

    @Test
    fun `a spool already on the list is recognised, ignoring case`() {
        val items = listOf(
            JSONObject().put("material", "pla").put("subtype", "basic").put("brand", "BAMBU")
                .put("color_name", "black").put("status", "pending")
        )
        assertTrue(Spools.onShoppingList(spool(left = 10.0), items))
    }

    @Test
    fun `a received roll no longer counts as on the list`() {
        val items = listOf(Spools.shoppingItem(spool(left = 10.0)).put("status", "received"))
        assertFalse(Spools.onShoppingList(spool(left = 10.0), items))
    }

    @Test
    fun `shopping item carries the fields the server's model has`() {
        val item = Spools.shoppingItem(spool(left = 10.0))
        assertEquals("PLA", item.getString("material"))
        assertEquals("Basic", item.getString("subtype"))
        assertEquals("Bambu", item.getString("brand"))
        assertEquals("Black", item.getString("color_name"))
        assertEquals(1, item.getInt("quantity_spools"))
    }

    @Test
    fun `shopping line reads like a person would say it`() {
        val item = JSONObject().put("material", "PLA").put("subtype", "Basic").put("brand", "Bambu")
            .put("color_name", "Black").put("quantity_spools", 2)
        assertEquals("2 × Bambu · PLA Basic · Black", Spools.shoppingLine(item))
        // A subtype that already names the material is not doubled.
        item.put("subtype", "PLA Matte").put("quantity_spools", 1)
        assertEquals("Bambu · PLA Matte · Black", Spools.shoppingLine(item))
    }

    @Test
    fun `shopping steps go to buy, ordered, arrived`() {
        val item = JSONObject().put("status", "pending")
        assertEquals("purchased" to "Bought", Spools.nextShoppingStep(item))
        item.put("status", "purchased")
        assertEquals("received" to "Arrived", Spools.nextShoppingStep(item))
        item.put("status", "received")
        assertNull(Spools.nextShoppingStep(item))
    }

    // ---------------------------------------------------------------- sticker

    @Test
    fun `sticker takes the spool's colour, brand and temperatures`() {
        val s = spool(left = 500.0).put("rgba", "1a2b3cff").put("nozzle_temp_min", 200).put("nozzle_temp_max", 225)
        val r = Spools.stickerRecord(s)
        assertEquals("PLA", r.type)
        assertEquals("1A2B3C", r.colorHex)
        assertEquals("Bambu", r.brand)
        assertEquals(200, r.minTemp)
        assertEquals(225, r.maxTemp)
    }

    @Test
    fun `sticker falls back to the material's usual range and a generic brand`() {
        val s = JSONObject().put("id", 4).put("material", "PETG")
        val r = Spools.stickerRecord(s)
        assertEquals("Generic", r.brand)
        assertEquals("FFFFFF", r.colorHex)
        assertEquals(230, r.minTemp)
        assertEquals(260, r.maxTemp)
    }
}
