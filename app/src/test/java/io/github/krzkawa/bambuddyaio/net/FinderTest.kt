package io.github.krzkawa.bambuddyaio.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class FinderTest {

    private fun ip(s: String) = InetAddress.getByName(s) as Inet4Address

    @Test
    fun theWholeSlash24IsSearchedExceptThePhoneItself() {
        val hosts = Finder.neighbours(ip("192.168.1.50"))
        assertEquals(253, hosts.size)
        assertFalse("192.168.1.50" in hosts)
        assertTrue("192.168.1.1" in hosts)
        assertTrue("192.168.1.254" in hosts)
        assertFalse(hosts.any { it.endsWith(".0") || it.endsWith(".255") })
        // Nearest first: a home server usually sits beside the phone's own lease.
        assertEquals(listOf("192.168.1.49", "192.168.1.51"), hosts.take(2).sorted())
    }

    @Test
    fun onlyBambuddysOwnReplyCounts() {
        val found = Finder.parse("http://192.168.1.20:8000", """{"auth_enabled": true, "requires_setup": false}""")
        assertEquals("http://192.168.1.20:8000", found?.url)
        assertTrue(found!!.authEnabled)
        assertFalse(found.needsSetup)

        val open = Finder.parse("http://192.168.1.20:8000", """{"auth_enabled": false, "requires_setup": false}""")
        assertFalse(open!!.authEnabled)

        // A router's status page or any other JSON on port 80 is not a Bambuddy.
        assertNull(Finder.parse("http://192.168.1.1", """{"status": "ok"}"""))
        assertNull(Finder.parse("http://192.168.1.1", "<html>Router login</html>"))
    }

    @Test
    fun port80NeedsNoNumberInTheAddress() {
        assertEquals("http://10.0.0.5", Finder.urlFor("10.0.0.5", 80))
        assertEquals("http://10.0.0.5:8000", Finder.urlFor("10.0.0.5", 8000))
    }

    @Test
    fun bambuddysOwnPortIsTriedFirstAndAlone() {
        assertEquals(listOf(8000), Finder.PORT_ROUNDS.first())
    }
}
