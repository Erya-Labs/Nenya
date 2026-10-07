package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.seam.EphemeralSigners
import dev.eryalabs.nenya.seam.NenyaClock
import dev.eryalabs.nenya.seam.Nip44Decryption
import dev.eryalabs.nenya.seam.Randomness
import dev.eryalabs.nenya.seam.SeamAnswer
import dev.eryalabs.nenya.seam.SeamCapability
import dev.eryalabs.nenya.seam.Secp256k1Ops
import dev.eryalabs.nenya.seam.SignatureVerdict
import dev.eryalabs.nenya.tag.TagFixtures
import dev.eryalabs.nenya.wire.encodeLowerHex
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The §3 seams, crossed from JavaScript as functions, and the one rule every adapter obeys: an absent
 * or silent function is **not performed**, never a negative result.
 *
 * ### Why this is part of T42 and not of the task that uses it
 *
 * `JsSigner`, `JsEnvironment` and the five adapters behind them are exported in this commit, so they
 * are checkable in this commit, and shipping an exported type with nothing behind it is what the
 * previous tick refused to do. The entry points that *consume* them — §7.1's sealing, §9.2's
 * settlement checks and §11.2's order machine — are a separate task, and their proof is its.
 *
 * ### The rule being proved, and why it is a rule rather than a style
 *
 * §17 requires an implementation that omits a check to say the check was not performed, and
 * `SignatureCheck` has no `INVALID` constant for exactly that reason. So every route by which a page
 * can fail to answer — the whole function absent, the function returning `null`, the function
 * returning a string that is not one of `SignatureVerdict`'s two constants, a `randomBytes` answer of
 * the wrong length — lands on [SeamAnswer.Unavailable] for that capability. Reading any of them as a
 * negative verdict would report a signature as refused that nobody checked, which is the §17
 * over-claim inverted and just as wrong.
 *
 * Nothing any of these functions returns is evidence of anything (§3, §9.1, STOP RULE 12). A page
 * returning a signature from `signEvent` has asserted a signature, and `Secp256k1Ops` is still the only
 * thing that can verify one — which is why the positive cases below assert that the adapter *passed the
 * answer through*, never that the answer is true.
 */
class JsSeamsTest {

    private companion object {

        /** A pubkey this repository computed, rather than hex somebody typed out. */
        val PUBKEY: String = TagFixtures.pubkeyFor(0)

        /**
         * A decrypted plaintext, worded so that finding it in a diagnostic is unambiguous.
         *
         * Deliberately **not** the word "plaintext": the sweep below asserts this string is absent
         * from every `toString`, and a sentinel sharing a substring with the prose a redacting
         * `toString` is allowed to print would have forced that assertion to be hedged. It was —
         * `assertFalse("plaintext" in text && "redacted" !in text)` passed any `toString` that said
         * "redacted", including one that also printed the secret.
         */
        const val SECRET: String = "a private message nobody but the two parties may read"

        /** A signer whose four functions all answer, for the pass-through half of each assertion. */
        fun answering(): JsSigner = JsSigner(
            publicKeyHex = { PUBKEY },
            signEvent = { "signature-over-$it" },
            nip44Encrypt = { _, plaintext -> "ciphertext-of-$plaintext" },
            nip44Decrypt = { _, payload -> JsDecryption(true, "plaintext-of-$payload") },
        )

        /** A signer whose four functions all answer nothing. */
        fun silent(): JsSigner = JsSigner(
            publicKeyHex = { null },
            signEvent = { null },
            nip44Encrypt = { _, _ -> null },
            nip44Decrypt = { _, _ -> null },
        )

        /** The capability an `Unavailable` names, or a loud failure saying it was provided. */
        fun unavailable(answer: SeamAnswer<*>, what: String): SeamCapability = when (answer) {
            is SeamAnswer.Unavailable -> answer.capability
            is SeamAnswer.Provided -> fail("$what answered rather than reporting it was not performed")
        }

        /** What a `Provided` carries, or a loud failure saying it was unavailable. */
        fun <T> provided(answer: SeamAnswer<T>, what: String): T = when (answer) {
            is SeamAnswer.Provided -> answer.value
            is SeamAnswer.Unavailable -> fail("$what was not performed: ${answer.detail}")
        }
    }

    // -----------------------------------------------------------------------------------------
    // The signer.
    // -----------------------------------------------------------------------------------------

    /** Each of the signer's four functions, answering, reaches the `Signer` method unchanged. */
    @Test
    @JsName("the_crossed_signer_passes_each_answer_through_unchanged")
    fun `the crossed signer passes each answer through unchanged`() {
        val signer = answering().asSigner()

        assertEquals(PUBKEY, provided(signer.publicKey(), "publicKey"))
        assertEquals(
            "signature-over-the-serialisation",
            provided(signer.signEvent("the-serialisation"), "signEvent"),
            "the canonical serialisation must reach the page verbatim: §4.1's id is the hash of it",
        )
        assertEquals(
            "ciphertext-of-a message",
            provided(signer.nip44Encrypt(PUBKEY, "a message"), "nip44Encrypt"),
        )
        val decrypted = provided(signer.nip44Decrypt(PUBKEY, "a payload"), "nip44Decrypt")
        assertTrue(decrypted is Nip44Decryption.Decrypted, "an ok JsDecryption is a Decrypted")
        assertEquals("plaintext-of-a payload", decrypted.plaintext)
    }

    /** Each of the signer's four functions, answering nothing, is that capability not performed. */
    @Test
    @JsName("a_silent_signer_function_is_that_capability_not_performed")
    fun `a silent signer function is that capability not performed`() {
        val signer = silent().asSigner()

        assertEquals(SeamCapability.SIGNER_PUBLIC_KEY, unavailable(signer.publicKey(), "publicKey"))
        assertEquals(SeamCapability.EVENT_SIGNATURE, unavailable(signer.signEvent("x"), "signEvent"))
        assertEquals(
            SeamCapability.NIP44_ENCRYPTION,
            unavailable(signer.nip44Encrypt(PUBKEY, "x"), "nip44Encrypt"),
        )
        assertEquals(
            SeamCapability.NIP44_DECRYPTION,
            unavailable(signer.nip44Decrypt(PUBKEY, "x"), "nip44Decrypt"),
        )
    }

    /**
     * NIP-44 decryption has **three** answers and the crossing keeps all three apart.
     *
     * §17 is the reason: a payload the signer checked and refused is not the same fact as a signer that
     * never ran. `null` is the third — the operation was not performed — and collapsing it onto
     * `Refused` would report a MAC failure the page never looked for.
     *
     * The fourth row is the shape that has no meaning: `ok` with no plaintext. There is nothing to hand
     * `Decrypted`, so it is the refusal authenticated encryption produces rather than a third case to
     * invent an answer for.
     */
    @Test
    @JsName("the_crossed_decryption_keeps_decrypted_refused_and_not_performed_apart")
    fun `the crossed decryption keeps decrypted refused and not performed apart`() {
        fun answer(decryption: JsDecryption?): SeamAnswer<Nip44Decryption> =
            JsSigner({ PUBKEY }, { null }, { _, _ -> null }, { _, _ -> decryption })
                .asSigner()
                .nip44Decrypt(PUBKEY, "a payload")

        val decrypted = provided(answer(JsDecryption(true, "the plaintext")), "an ok decryption")
        assertTrue(decrypted is Nip44Decryption.Decrypted, "an ok JsDecryption carrying a plaintext")
        assertEquals("the plaintext", decrypted.plaintext)

        assertSame(
            Nip44Decryption.Refused,
            provided(answer(JsDecryption(false)), "a checked-and-refused decryption"),
            "a JsDecryption that is not ok is NIP-44's own refusal, which is a result",
        )
        assertSame(
            Nip44Decryption.Refused,
            provided(answer(JsDecryption(true, null)), "an ok decryption with no plaintext"),
            "there is nothing to hand `Decrypted`, and inventing a plaintext is not an option",
        )
        assertEquals(
            SeamCapability.NIP44_DECRYPTION,
            unavailable(answer(null), "a signer that answered nothing"),
            "`null` is the operation not being performed, which §17 requires be said rather than " +
                "reported as a negative result",
        )
    }

    /**
     * `SeamAnswer` crosses as decision **P**'s own four fields: `{ ok, value, capability, detail }`.
     *
     * The one translation decision **P** spells out in its own words, so it gets its own test rather
     * than being left to the entry points that will use it. `JsSeamAnswer` is reachable from no entry
     * point in this half — the six that answer one are T43's — and an exported type with nothing
     * proving it is exactly what the previous tick refused to commit. T43 builds on this, so it is
     * checked here, where it was written.
     *
     * [ok] is the discriminator and the two cases have disjoint fields: a `Provided` carries [value]
     * with the other two `null`, an `Unavailable` carries [capability] and [detail] with [value] `null`.
     * Nothing may carry both, because a page branching on `ok` would then be reading a field whose
     * meaning depends on a case it has already decided.
     */
    @Test
    @JsName("a_seam_answer_crosses_as_ok_value_capability_and_detail")
    fun `a seam answer crosses as ok value capability and detail`() {
        val provided = jsSeamAnswer(SeamAnswer.Provided(PUBKEY)) { it as String? }
        assertTrue(provided.ok, "a Provided is `ok`")
        assertEquals(PUBKEY, provided.value, "and carries what the seam said, translated")
        assertNull(provided.capability, "an ok answer names no capability: there is nothing unavailable")
        assertNull(provided.detail, "and carries no detail")

        val unavailable = jsSeamAnswer(
            SeamAnswer.Unavailable(SeamCapability.BIP340_VERIFICATION, "a detail this library wrote"),
        ) { fail("the value translation must not be called for an Unavailable") }
        assertFalse(unavailable.ok, "an Unavailable is not `ok`")
        assertNull(unavailable.value, "and carries no value")
        assertEquals(
            SeamCapability.BIP340_VERIFICATION.name,
            unavailable.capability,
            "it names the capability by its constant's name, so a page can branch on it",
        )
        assertEquals("a detail this library wrote", unavailable.detail)

        // §9.1: `ok` means the seam answered, never that the answer is true. The redaction is the
        // visible half of that — §12 item 11 keeps the value out of a log either way.
        assertFalse(
            PUBKEY in provided.toString(),
            "an ok answer must not print what the seam said: ${provided.toString()}",
        )
        assertTrue("redacted" in provided.toString(), "and must say that it redacted it")
        assertTrue(
            SeamCapability.BIP340_VERIFICATION.name in unavailable.toString(),
            "an unavailable answer may name the capability, which is this library's own word",
        )
    }

    /** No crossed seam prints a key, a function or a payload (§12 item 11, STOP RULE 14). */
    @Test
    @JsName("no_crossed_seam_prints_a_key_a_payload_or_a_plaintext")
    fun `no crossed seam prints a key a payload or a plaintext`() {
        val signer = answering()
        val printed = listOf(
            signer.toString(),
            signer.asSigner().toString(),
            JsEnvironment(nowSeconds = { "1" }).toString(),
            JsDecryption(true, SECRET).toString(),
            JsDecryption(false).toString(),
            jsSeamAnswer(SeamAnswer.Provided(PUBKEY)) { it as String? }.toString(),
        )
        for (text in printed) {
            assertFalse(PUBKEY in text, "a crossed seam printed a pubkey: $text")
            // Unconditional: a `toString` that says "redacted" and prints the secret anyway fails here.
            assertFalse(SECRET in text, "a crossed seam printed a plaintext: $text")
        }
        assertTrue(
            printed.all { it.isNotEmpty() },
            "a seam whose toString is empty would satisfy the assertions above trivially",
        )
    }

    // -----------------------------------------------------------------------------------------
    // The environment: four independently absent seams.
    // -----------------------------------------------------------------------------------------

    /**
     * An environment supplying no function at all is each seam's own `FAIL_CLOSED`, by identity.
     *
     * By identity rather than by behaviour: the library's default is the object to fall back to, and an
     * adapter that built its own equivalent would be a second fail-closed implementation to keep in
     * step with the first.
     */
    @Test
    @JsName("an_empty_environment_is_each_seams_own_FAIL_CLOSED")
    fun `an empty environment is each seam's own FAIL_CLOSED`() {
        val empty = JsEnvironment()
        assertSame(NenyaClock.FAIL_CLOSED, empty.asClock(), "§4.6's clock")
        assertSame(Randomness.FAIL_CLOSED, empty.asRandomness(), "§7.4's randomness")
        assertSame(Secp256k1Ops.FAIL_CLOSED, empty.asSecp(), "§4.1's BIP-340 verification")
        assertSame(EphemeralSigners.FAIL_CLOSED, empty.asEphemeralSigners(), "§7.1's one-time signer")
    }

    /**
     * The four seams are independently absent, so supplying one does not supply the others.
     *
     * Each field is independently nullable and an absent one is `FAIL_CLOSED` for that seam alone. A
     * page that can read a clock and cannot verify a signature must be able to say exactly that.
     */
    @Test
    @JsName("each_environment_seam_is_independently_absent")
    fun `each environment seam is independently absent`() {
        val clockOnly = JsEnvironment(nowSeconds = { "1700000000" })
        assertEquals(1_700_000_000L, provided(clockOnly.asClock().now(), "the clock"))
        assertSame(Randomness.FAIL_CLOSED, clockOnly.asRandomness(), "randomness was not supplied")
        assertSame(Secp256k1Ops.FAIL_CLOSED, clockOnly.asSecp(), "verification was not supplied")
        assertSame(EphemeralSigners.FAIL_CLOSED, clockOnly.asEphemeralSigners(), "no signer was supplied")
    }

    /**
     * §4.6's clock: a reading that is not a decimal string is **unavailable**, not a guessed time.
     *
     * This clock is authoritative for every deadline in §7.5 and §11.2, and a deadline computed from a
     * guess is worse than one that refuses to be computed.
     */
    @Test
    @JsName("a_clock_reading_that_is_not_a_decimal_string_is_not_performed")
    fun `a clock reading that is not a decimal string is not performed`() {
        for (reading in listOf(null, "", "now", "1.0", "007", "1e9")) {
            assertEquals(
                SeamCapability.CLOCK_READING,
                unavailable(JsEnvironment(nowSeconds = { reading }).asClock().now(), "a `$reading` clock"),
                "`$reading` is not a count of unix seconds and must not become one",
            )
        }
        // And the form it does read, at a value far outside the exact `number` range.
        assertEquals(
            Long.MAX_VALUE,
            provided(
                JsEnvironment(nowSeconds = { JsDecimal.of(Long.MAX_VALUE) }).asClock().now(),
                "a clock at Long.MAX_VALUE",
            ),
            "the clock crosses as a decimal string precisely so this value survives",
        )
    }

    /**
     * §7.4's randomness: a short answer is not padded and a long one is not truncated.
     *
     * §7.4's order id needs 32 bytes of entropy and silently supplying fewer is the failure the seam
     * exists to make visible.
     */
    @Test
    @JsName("randomness_of_the_wrong_length_or_the_wrong_alphabet_is_not_performed")
    fun `randomness of the wrong length or the wrong alphabet is not performed`() {
        val wanted = 32
        val short = encodeLowerHex(ByteArray(wanted - 1))
        val long = encodeLowerHex(ByteArray(wanted + 1))
        for (answer in listOf(null, "", short, long, "not hex", short.uppercase())) {
            assertEquals(
                SeamCapability.RANDOM_BYTES,
                unavailable(
                    JsEnvironment(randomBytesHex = { answer }).asRandomness().randomBytes(wanted),
                    "a `$answer` randomness answer",
                ),
                "$wanted bytes were asked for and this answer is not $wanted bytes of lowercase hex",
            )
        }
        val exact = ByteArray(wanted) { (it + 1).toByte() }
        assertEquals(
            exact.toList(),
            provided(
                JsEnvironment(randomBytesHex = { encodeLowerHex(exact) }).asRandomness().randomBytes(wanted),
                "an exact randomness answer",
            ).toList(),
            "the bytes must cross unchanged; this is §7.4's entropy",
        )
    }

    /**
     * §4.1's BIP-340 verification: the two constants cross, and **anything else is not performed**.
     *
     * In both directions, which is the half that matters. Reading an unknown string as `INVALID` would
     * report a signature as refused that nobody checked — §17 forbids it, and `SignatureVerdict` has no
     * constant for it.
     */
    @Test
    @JsName("a_verdict_outside_SignatureVerdicts_constants_is_not_performed")
    fun `a verdict outside SignatureVerdict's constants is not performed`() {
        val key = ByteArray(32)
        val message = ByteArray(32) { 1 }
        val signature = ByteArray(64) { 2 }

        for (verdict in SignatureVerdict.entries) {
            assertEquals(
                verdict,
                provided(
                    JsEnvironment(verifySchnorr = { _, _, _ -> verdict.name })
                        .asSecp()
                        .verifySchnorr(key, message, signature),
                    "a `${verdict.name}` verdict",
                ),
                "a constant `SignatureVerdict` names must cross as itself",
            )
        }
        for (answer in listOf(null, "", "INVALID_SIGNATURE", "valid", "true", "MAYBE")) {
            assertEquals(
                SeamCapability.BIP340_VERIFICATION,
                unavailable(
                    JsEnvironment(verifySchnorr = { _, _, _ -> answer })
                        .asSecp()
                        .verifySchnorr(key, message, signature),
                    "a `$answer` verdict",
                ),
                "`$answer` is not a verdict this library understands, and §17 forbids turning it into " +
                    "one — in either direction",
            )
        }
    }

    /** The three byte arrays reach the page as §4.3's lowercase hex, through the library's own encoder. */
    @Test
    @JsName("the_verification_seam_hands_the_page_section_4_3_lowercase_hex")
    fun `the verification seam hands the page section 4 3 lowercase hex`() {
        val key = ByteArray(32) { it.toByte() }
        val message = ByteArray(32) { (it + 64).toByte() }
        val signature = ByteArray(64) { (255 - it).toByte() }
        val seen = mutableListOf<String>()

        JsEnvironment(
            verifySchnorr = { publicKey, messageHex, signatureHex ->
                seen += listOf(publicKey, messageHex, signatureHex)
                SignatureVerdict.VALID.name
            },
        ).asSecp().verifySchnorr(key, message, signature)

        assertEquals(
            listOf(encodeLowerHex(key), encodeLowerHex(message), encodeLowerHex(signature)),
            seen,
            "the bytes must cross as the hex this library's own encoder writes, not a second one's",
        )
    }

    /** §7.1's one-time signer crosses as its own `JsSigner`, and an absent one is not performed. */
    @Test
    @JsName("the_ephemeral_signer_crosses_as_a_signer_and_an_absent_one_is_not_performed")
    fun `the ephemeral signer crosses as a signer and an absent one is not performed`() {
        val fresh = provided(
            JsEnvironment(freshSigner = { answering() }).asEphemeralSigners().fresh(),
            "a supplied fresh signer",
        )
        assertEquals(
            PUBKEY,
            provided(fresh.publicKey(), "the fresh signer's own key"),
            "the throwaway keypair's key must reach the caller through the same Signer seam",
        )
        assertEquals(
            SeamCapability.EPHEMERAL_SIGNER,
            unavailable(
                JsEnvironment(freshSigner = { null }).asEphemeralSigners().fresh(),
                "a page that minted no keypair",
            ),
            "the capability is minting the keypair; nothing here claims it is fresh (§3)",
        )
    }

    /**
     * Every `Unavailable` these adapters produce carries **this library's** detail, naming the seam.
     *
     * §17 requires the capability statement be this library's, and text an injected seam wrote is text
     * this library cannot promise is free of the ciphertext it was handed (§12 item 11). So there is
     * deliberately no way for a page to supply its own detail, and this is what says so.
     */
    @Test
    @JsName("every_unavailable_detail_is_authored_by_this_library")
    fun `every unavailable detail is authored by this library`() {
        val answers: List<SeamAnswer<*>> = listOf(
            silent().asSigner().publicKey(),
            silent().asSigner().nip44Decrypt(PUBKEY, "x"),
            JsEnvironment(nowSeconds = { null }).asClock().now(),
            JsEnvironment(randomBytesHex = { null }).asRandomness().randomBytes(32),
            JsEnvironment(verifySchnorr = { _, _, _ -> null }).asSecp()
                .verifySchnorr(ByteArray(32), ByteArray(32), ByteArray(64)),
            JsEnvironment(freshSigner = { null }).asEphemeralSigners().fresh(),
        )
        for (answer in answers) {
            val unavailable = answer as? SeamAnswer.Unavailable
                ?: fail("a silent seam answered rather than reporting it was not performed")
            assertTrue(
                "§17" in unavailable.detail,
                "the detail must cite §17's rule that this be reported as not performed: " +
                    unavailable.detail,
            )
            assertTrue(
                unavailable.capability.what in unavailable.detail,
                "and must name the capability that was not performed: ${unavailable.detail}",
            )
        }
        assertEquals(
            6,
            answers.size,
            "every adapter in this package must be represented; an unlisted one is an Unavailable " +
                "nobody has read",
        )
    }
}
