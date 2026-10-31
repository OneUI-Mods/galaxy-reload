package dev.ryuya.galaxyreload

import java.util.Properties
import org.junit.Assert.*
import org.junit.Test

open class ReloadTicketTest {
    private val ticket = ReloadTicket(216, 0, 100000, "8b759534-eefe-4ea5-a890-133951f8a9a1")

    @Test
    fun roundTripAcceptsOnlyTheCurrentUnlockedSession() {
        assertEquals(ticket, ReloadTicket.parse(ticket.properties()))
        assertTrue(ticket.accepts(216, 0, 100000, true, true))
        assertTrue(ticket.accepts(216, 0, 112000, true, true))
    }

    @Test
    fun expiredOrFutureRequestsCannotResume() {
        assertFalse(ticket.accepts(216, 0, 112001, true, true))
        assertFalse(ticket.accepts(216, 0, 99999, true, true))
    }

    @Test
    fun rebootUserSwitchOrScreenOffInvalidatesRequest() {
        assertFalse(ticket.accepts(217, 0, 100001, true, true))
        assertFalse(ticket.accepts(216, 10, 100001, true, true))
        assertFalse(ticket.accepts(216, 0, 100001, false, true))
        assertFalse(ticket.accepts(216, 0, 100001, true, false))
    }

    @Test
    fun malformedAndUnknownProtocolsAreRejected() {
        assertNull(ReloadTicket.parse(Properties()))
        var p = ticket.properties()
        p.setProperty("protocol", "2")
        assertNull(ReloadTicket.parse(p))
        p = ticket.properties()
        p.setProperty("nonce", "invalid")
        assertNull(ReloadTicket.parse(p))
        p = ticket.properties()
        p.setProperty("created", "NaN")
        assertNull(ReloadTicket.parse(p))
    }
}
