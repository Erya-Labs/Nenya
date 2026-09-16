package dev.eryalabs.nenya

import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The protocol constants are a wire contract, so they are pinned by literal here
 * rather than compared against themselves. A change that breaks this test is a
 * protocol change and needs a specification update, not a new expected value.
 *
 * This is common code, compiled for the JVM and for JavaScript. Kotlin/JS rejects a
 * function name containing spaces, so each readable backtick name carries a `@JsName`
 * giving JS a valid identifier while the JVM report keeps the readable name. The
 * explicit `import kotlin.js.JsName` is required: without it the JVM test compile
 * fails with "Unresolved reference 'JsName'".
 */
class NenyaProtocolTest {

    @JsName("protocol_version_is_pinned")
    @Test
    fun `protocol version is pinned`() {
        assertEquals(1, NenyaProtocol.VERSION)
    }

    @JsName("protocol_name_is_pinned")
    @Test
    fun `protocol name is pinned`() {
        assertEquals("nenya", NenyaProtocol.NAME)
    }
}
