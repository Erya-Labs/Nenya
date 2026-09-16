package dev.eryalabs.nenya

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The protocol constants are a wire contract, so they are pinned by literal here
 * rather than compared against themselves. A change that breaks this test is a
 * protocol change and needs a specification update, not a new expected value.
 */
class NenyaProtocolTest {

    @Test
    fun `protocol version is pinned`() {
        assertEquals(1, NenyaProtocol.VERSION)
    }

    @Test
    fun `protocol name is pinned`() {
        assertEquals("nenya", NenyaProtocol.NAME)
    }
}
