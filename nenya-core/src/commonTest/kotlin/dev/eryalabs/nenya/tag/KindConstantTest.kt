package dev.eryalabs.nenya.tag

import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The half of `KindConstantTest` that is common Kotlin: §5.2's request kind, parsed out of the
 * specification at test time, is the value [NenyaKind.REQUEST] carries.
 *
 * Abstract, and run as `KindConstantTest` on every target, so the test keeps its name. The rule that
 * the literal appears in exactly one main source line is a sweep of the source tree on disk, and
 * stays in the JVM `KindConstantTest`.
 */
abstract class PortableKindConstantTest {

    @JsName("the_request_kind_is_the_one_the_specification_decided")
    @Test
    fun `the request kind is the one the specification decided`() {
        assertEquals(
            Section53.requestKind(),
            NenyaKind.REQUEST,
            "§5.2 closed `OPEN-1` on this value and Appendix B forbids it changing after the " +
                "first public release; read from ${Section53.specPath()}",
        )
    }
}
