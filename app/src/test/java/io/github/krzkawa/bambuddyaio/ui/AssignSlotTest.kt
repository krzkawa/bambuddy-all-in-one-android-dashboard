package io.github.krzkawa.bambuddyaio.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AssignSlotTest {

    private fun slot(amsId: Int, trayId: Int) = Assign.Slot(amsId, trayId, "", null)

    @Test
    fun `an ordinary AMS packs four trays per unit`() {
        assertEquals(0, slot(0, 0).globalTrayId)
        assertEquals(3, slot(0, 3).globalTrayId)
        assertEquals(4, slot(1, 0).globalTrayId)
        assertEquals(9, slot(2, 1).globalTrayId)
    }

    @Test
    fun `an AMS-HT unit id is already the global tray id`() {
        // Multiplying 128 by four would address tray 512 and quietly do nothing.
        assertEquals(128, slot(128, 0).globalTrayId)
        assertEquals(135, slot(135, 0).globalTrayId)
    }

    @Test
    fun `the external holder keeps its own two ids`() {
        assertEquals(254, slot(255, 0).globalTrayId)
        assertEquals(255, slot(255, 1).globalTrayId)
    }

    @Test
    fun `units are named the way the printer names them`() {
        assertEquals("AMS 1", Assign.unitName(0))
        assertEquals("AMS 4", Assign.unitName(3))
        assertEquals("AMS HT 1", Assign.unitName(128))
        assertEquals("AMS HT 8", Assign.unitName(135))
        assertEquals("External spool", Assign.unitName(255))
    }

    @Test
    fun `a slot in an ordinary AMS is named by unit and slot`() {
        assertEquals("AMS 1 · slot 1", Assign.slotName(0, 0))
        assertEquals("AMS 2 · slot 3", Assign.slotName(1, 2))
    }

    @Test
    fun `an HT holds one spool, so it has no slot number`() {
        assertEquals("AMS HT 1", Assign.slotName(128, 0))
    }

    @Test
    fun `a global tray id turns back into the words for that slot`() {
        assertEquals("AMS 1 · slot 1", Assign.trayWords(0))
        assertEquals("AMS 1 · slot 4", Assign.trayWords(3))
        assertEquals("AMS 2 · slot 1", Assign.trayWords(4))
        assertEquals("AMS 3 · slot 2", Assign.trayWords(9))
        assertEquals("AMS HT 1", Assign.trayWords(128))
        assertEquals("External spool", Assign.trayWords(254))
        assertEquals("External spool", Assign.trayWords(255))
    }

    @Test
    fun `naming a global id is the inverse of computing one`() {
        for (ams in 0..3) {
            for (tray in 0..3) {
                assertEquals(Assign.slotName(ams, tray), Assign.trayWords(slot(ams, tray).globalTrayId))
            }
        }
    }
}
