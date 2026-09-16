package dev.eryalabs.nenya.platform

import dev.eryalabs.nenya.conformance.Capabilities
import dev.eryalabs.nenya.payment.PaymentCheck
import dev.eryalabs.nenya.seam.SeamAnswer
import dev.eryalabs.nenya.seam.SeamCapability
import dev.eryalabs.nenya.seam.WalletPaymentClaim
import dev.eryalabs.nenya.seam.WalletPaymentState
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/**
 * The two published strings built from a platform type-name lookup (`TypeNames.kt`), pinned whole
 * in common Kotlin so that each target's `actual` is held to the same answer.
 *
 * On the JVM these lookups are `java.lang.Class` reads; on JavaScript they walk Kotlin/JS class
 * metadata and the prototype chain (`src/jsMain`). A test that only ran on the JVM would leave the
 * JavaScript `actual`s, the only code in this library written for one target alone, unexercised.
 */
class TypeNamesTest {

    /**
     * `Capabilities.qualify` publishes the **declaring** enum's name. An enum constant with a body
     * is an anonymous subclass of its enum on both targets, so a lookup that named the constant's
     * own runtime class would print something other than `QualifyProbe` for [QualifyProbe.WITH_BODY]
     * while still passing for every constant the library declares today, none of which has a body.
     */
    @JsName("qualify_names_the_declaring_enum_including_for_a_constant_with_a_body")
    @Test
    fun `qualify names the declaring enum, including for a constant with a body`() {
        // The control: the constant with a body really is a different runtime class from its enum's
        // plain constants, or the assertion below would be the plain case twice.
        assertNotEquals<Any>(
            QualifyProbe.PLAIN::class,
            QualifyProbe.WITH_BODY::class,
            "a constant with a body must compile to its own subclass, or this test proves nothing about one",
        )

        assertEquals("QualifyProbe.PLAIN", Capabilities.qualify(QualifyProbe.PLAIN))
        assertEquals("QualifyProbe.WITH_BODY", Capabilities.qualify(QualifyProbe.WITH_BODY))
        assertEquals("PaymentCheck.INVOICE_AMOUNT", Capabilities.qualify(PaymentCheck.INVOICE_AMOUNT))
        assertEquals("SeamCapability.BIP340_VERIFICATION", Capabilities.qualify(SeamCapability.BIP340_VERIFICATION))
    }

    /**
     * `SeamAnswer.Provided.toString` names the runtime type of what it holds, and nothing else.
     *
     * Limited to types whose simple name is the same through `Class.getSimpleName` and through
     * Kotlin's `simpleName`. `TypeNames.kt` records the ones that are not (a boxed `Int` is `Integer`
     * to the JVM, a `ByteArray` is `byte[]`), and the JavaScript `actual` reads Kotlin's names, so
     * those would print differently on the two targets and are not a cross-platform contract.
     */
    @JsName("provided_names_the_runtime_type_and_redacts_the_value_on_every_target")
    @Test
    fun `Provided names the runtime type and redacts the value, on every target`() {
        val secret = "the buyer's decrypted message"
        val cases: List<Pair<Any?, String>> = listOf(
            secret to "String",
            7_000L to "Long",
            true to "Boolean",
            WalletPaymentState.CLAIMS_SETTLED to "WalletPaymentState",
            WalletPaymentClaim(WalletPaymentState.CLAIMS_FAILED, null) to "WalletPaymentClaim",
            Envelope(secret) to "Envelope",
            // An anonymous object has no simple name: the JVM prints "" for one, and the JavaScript
            // actual is written to match.
            object { override fun toString(): String = secret } to "",
        )

        for ((value, name) in cases) {
            val printed = SeamAnswer.Provided(value).toString()
            assertEquals("SeamAnswer.Provided($name, redacted)", printed)
            assertFalse(printed.contains(secret), "the value reached the string: $printed")
        }
        assertEquals("SeamAnswer.Provided(null, redacted)", SeamAnswer.Provided(null).toString())
    }

    private class Envelope(val contents: String) {
        override fun toString(): String = "Envelope($contents)"
    }
}

/** One plain constant and one with a body, which compiles to an anonymous subclass of this enum. */
private enum class QualifyProbe {
    PLAIN,
    WITH_BODY {
        override fun toString(): String = "a constant with a body"
    },
}
