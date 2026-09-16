package dev.eryalabs.nenya.order

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The §11.2 totality test that needs the JVM, on top of the common ones in
 * [PortableTransitionTotalityTest].
 *
 * It enumerates every declared [OrderEvent] class from `build/classes/kotlin/jvm/main` through
 * `java.lang.reflect` (`OrderStructure`), which common Kotlin cannot do.
 */
class TransitionTotalityTest : PortableTransitionTotalityTest() {

    /**
     * Every declared event class is actually exercised.
     *
     * Enumerated by reflection over the main output tree (`build/classes/kotlin/jvm/main`) rather than listed by hand, so an
     * event added later without a sample turns this red instead of sitting outside every proof
     * in the file. `kotlin-reflect` is not on the classpath (STOP RULE 11), so this walks the
     * class files the way `OrderStructureTest` does.
     */
    @Test
    fun `every declared event class appears in the cross-product`() {
        val declared = OrderStructure.mainClasses()
            .filter { OrderEvent::class.java.isAssignableFrom(it) && !it.isInterface }
            .map { it.name }
            .toSet()
        assertTrue(declared.isNotEmpty(), "no OrderEvent implementations found in the main output tree")

        assertEquals(
            declared,
            events.map { it.javaClass.name }.toSet(),
            "every OrderEvent class in the package must be sampled by this cross-product, and " +
                "nothing outside the package may be",
        )
    }
}
