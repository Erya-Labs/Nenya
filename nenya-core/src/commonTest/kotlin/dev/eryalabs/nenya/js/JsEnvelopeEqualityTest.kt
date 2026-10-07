package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.envelope.EnvelopeFixtures
import dev.eryalabs.nenya.envelope.EnvelopeRejection
import dev.eryalabs.nenya.envelope.EnvelopeSide
import dev.eryalabs.nenya.envelope.GiftWrap
import dev.eryalabs.nenya.envelope.SealedMessage
import dev.eryalabs.nenya.seam.EphemeralSigners
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.seam.FakeSecp256k1Ops
import dev.eryalabs.nenya.seam.NenyaClock
import dev.eryalabs.nenya.seam.Nip44PayloadEphemeralSigners
import dev.eryalabs.nenya.seam.Randomness
import dev.eryalabs.nenya.seam.RecordingRandomness
import dev.eryalabs.nenya.seam.SeamCapability
import dev.eryalabs.nenya.seam.Secp256k1Ops
import dev.eryalabs.nenya.seam.Signer
import dev.eryalabs.nenya.wire.WireEvent
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * §7.1's two procedures across the JavaScript boundary: [seal] and [open] answer exactly what
 * `GiftWrap.seal` and `GiftWrap.open` answer, over the same inputs and the same seams.
 *
 * ### Why both sides get their own seams
 *
 * §7.1 step 5 draws four independent random timestamps and step 3 mints two throwaway keypairs, so
 * sealing the same rumor twice with one `Randomness` produces two **different** messages — which is
 * the construction working, not a defect. Every case below therefore builds a fresh seam set per
 * side from the same pinned seed, and the equality is over the bytes two identically seeded runs
 * produce. A test that shared one source would compare a message against its successor and fail
 * for the one reason that is not interesting.
 *
 * ### In `commonTest`, so the scheduled job runs it on JavaScript
 *
 * Which is the whole point of the equality: `JsSurfaceTest` is a JVM reflection sweep over the
 * compiled *shape* of the facade, and this is the file that says the shape carries the right values
 * on the target a page actually uses.
 */
class JsEnvelopeEqualityTest {

    // -----------------------------------------------------------------------------------------
    // §7.1's write procedure.
    // -----------------------------------------------------------------------------------------

    /**
     * The ordinary case, and the one that carries the most: two copies, two seals, two throwaway
     * keys, four randomised timestamps — all of it byte-identical across the boundary.
     *
     * The equality is over `OutgoingWrap.json`, which is the signed object form a relay would be
     * handed, and over the recomputed id, which is the SHA-256 of that form's canonical
     * serialisation. Equal JSON is equal bytes, so this cannot be satisfied by two messages that
     * merely look alike.
     */
    @Test
    @JsName("seal_crosses_the_whole_of_section_7_1_s_write_procedure")
    fun `seal crosses the whole of §7_1's write procedure`() {
        for (hint in listOf(null, "wss://relay.example")) {
            sealBothWays(
                what = if (hint == null) "the ordinary rumor" else "the ordinary rumor with a relay hint",
                rumor = EnvelopeFixtures.rumor(),
                relayHint = hint,
            )
        }
        sealBothWays("the minimal rumor", EnvelopeFixtures.minimalRumor())
    }

    /**
     * §17's two answers about BIP-340, crossed: an injected verifier that answers, and none at all.
     *
     * The second is the one §17 is about. A page with no verifier still gets its message — §7.1's
     * verify-on-emit is conditional — and `notPerformedHere` is what says so. The assertion below
     * is not merely that the two agree with their Kotlin counterparts but that they **differ from
     * each other**, because a facade that returned an empty set unconditionally would agree with
     * the equipped case and silently claim a check nobody made.
     */
    @Test
    @JsName("seal_crosses_section_17_s_statement_about_what_was_not_checked")
    fun `seal crosses §17's statement about what was not checked`() {
        val equipped = sealBothWays("an injected verifier", EnvelopeFixtures.rumor(), secp = { FakeSecp256k1Ops() })
        val unequipped = sealBothWays("no verifier at all", EnvelopeFixtures.rumor())

        assertEquals(emptySet(), equipped.notPerformedHere.toSet(), "a verdict was obtained for all four")
        assertEquals(
            setOf(SeamCapability.BIP340_VERIFICATION.name),
            unequipped.notPerformedHere.toSet(),
            "§17 requires an implementation that omits BIP-340 verification say so",
        )
        assertNotEquals(
            equipped.notPerformedHere.toSet(),
            unequipped.notPerformedHere.toSet(),
            "the two §17 answers must differ, or the field is decorative and a page cannot tell " +
                "a verified message from an unverified one",
        )
    }

    /**
     * Every refusal §7.1's write procedure can reach from the arguments and seams this boundary
     * carries, returned as a value and equal to the `EnvelopeException` a JVM caller gets.
     *
     * The three seam refusals are the ones T43 names: each absent plug-in fails closed exactly as
     * its library default does, so a page that has not finished wiring itself up gets a named
     * refusal and never a message signed by the wrong key or timestamped from a guess.
     */
    @Test
    @JsName("seal_returns_section_7_1_s_write_refusals_as_values")
    fun `seal returns §7_1's write refusals as values`() {
        val reached = mutableSetOf<String>()
        val sender = EnvelopeFixtures.senderKey()

        reached += sealRefusal(
            "the recipient is the sender",
            EnvelopeRejection.RECIPIENT_IS_SENDER,
            recipient = sender,
        )
        reached += sealRefusal(
            "the rumor claims a stranger's key",
            EnvelopeRejection.RUMOR_NOT_SENDERS,
            rumor = EnvelopeFixtures.rumor(pubkey = EnvelopeFixtures.strangerKey()),
        )
        reached += sealRefusal(
            "the recipient key is not 64 lowercase hex",
            EnvelopeRejection.RECIPIENT_KEY_MALFORMED,
            recipient = "not a key",
        )
        reached += sealRefusal(
            "no one-time signer was supplied",
            EnvelopeRejection.EPHEMERAL_SIGNER_UNAVAILABLE,
            ephemeral = null,
        )
        reached += sealRefusal(
            "no clock was supplied",
            EnvelopeRejection.CLOCK_UNAVAILABLE,
            clock = null,
        )
        reached += sealRefusal(
            "no randomness was supplied",
            EnvelopeRejection.RANDOMNESS_UNAVAILABLE,
            randomness = null,
        )

        assertEquals(6, reached.size, "each control must reach its own refusal, not a shared one")
        assertTrue(
            reached.all { name ->
                EnvelopeRejection.entries.first { it.name == name }.side == EnvelopeSide.SEAL
            },
            "every refusal here must be on §7.1's write side: $reached",
        )
    }

    // -----------------------------------------------------------------------------------------
    // §7.1's read procedure.
    // -----------------------------------------------------------------------------------------

    /**
     * A wrap this library sealed, opened across the boundary — §7.2's attribution, §7.1's two
     * verdicts, the de-duplication id and every field of the rumor.
     *
     * Both verifier cases, because §7.2 requires the two attributions be told apart: a reader with
     * a verifier gets `SIGNATURE_VERIFIED` and one without gets `AUTHENTICATED_BY_DECRYPTION`, and
     * the assertion is that they differ as well as that each matches its Kotlin counterpart.
     */
    @Test
    @JsName("open_crosses_the_whole_of_section_7_1_s_read_procedure")
    fun `open crosses the whole of §7_1's read procedure`() {
        val wrapJson = sealedWrapJson()

        val verified = openBothWays("an injected verifier", wrapJson, secp = { FakeSecp256k1Ops() })
        val unchecked = openBothWays("no verifier at all", wrapJson)

        assertEquals("VERIFIED", verified.sealSignature, "§7.1 step 6's verdict")
        assertEquals("VERIFIED", verified.wrapSignature, "§7.1 step 4's verdict")
        assertEquals("NOT_CHECKED", unchecked.sealSignature, "§17: no verdict was obtained")
        assertEquals("NOT_CHECKED", unchecked.wrapSignature, "§17: no verdict was obtained")
        assertEquals(
            "SIGNATURE_VERIFIED",
            verified.rumor?.attribution,
            "§7.2: the seal's signature is what binds",
        )
        assertEquals(
            "AUTHENTICATED_BY_DECRYPTION",
            unchecked.rumor?.attribution,
            "§7.2 requires a reader without a verifier report the weaker claim",
        )
        assertEquals(
            verified.rumor?.idHex,
            unchecked.rumor?.idHex,
            "the same wrap must yield the same rumor whichever verdict was obtained",
        )
    }

    /**
     * §7.1's read refusals, returned as values and equal to the exception a JVM caller gets.
     *
     * `NOT_ADDRESSED_TO_ME` is the one worth naming: §7.1 step 3 requires rejecting **before any
     * decryption**, so a stranger's wrap costs the page's signer nothing. The facade cannot have
     * reordered that — it makes one call — and the refusal below is the evidence that the call is
     * the one `GiftWrap.open` makes.
     */
    @Test
    @JsName("open_returns_section_7_1_s_read_refusals_as_values")
    fun `open returns §7_1's read refusals as values`() {
        val wrapJson = sealedWrapJson()
        val reached = mutableSetOf<String>()

        reached += openRefusal(
            "a wrap addressed to somebody else",
            EnvelopeRejection.NOT_ADDRESSED_TO_ME,
            wrapJson,
            reader = EnvelopeFixtures.senderSigner(),
        )
        reached += openRefusal("rubbish instead of an event", EnvelopeRejection.MALFORMED_WRAP, "{")
        reached += openRefusal(
            "the rumor itself rather than its wrap",
            EnvelopeRejection.WRAP_UNSIGNED,
            JsBoundaryFixtures.json(EnvelopeFixtures.rumor()),
        )

        assertEquals(3, reached.size, "each control must reach its own refusal")
        assertTrue(
            reached.all { name ->
                EnvelopeRejection.entries.first { it.name == name }.side == EnvelopeSide.OPEN
            },
            "every refusal here must be on §7.1's read side: $reached",
        )
    }

    /**
     * The round trip, end to end, **entirely across the boundary**: a rumor one of T42's builders
     * produced, sealed by [seal], opened by [open], and the same rumor comes back.
     *
     * This is the property a page actually depends on, and it is the one no single-entry-point
     * equality can state: T42 proved thirteen builders, this task proves six more, and the thing a
     * trade is made of is the two halves meeting. §7.2's author is checked explicitly, because a
     * crossing that lost it would still round-trip a message with nobody's name on it.
     */
    @Test
    @JsName("a_rumor_built_sealed_and_opened_entirely_across_the_boundary_survives")
    fun `a rumor built sealed and opened entirely across the boundary survives`() {
        val envelope = JsRumorEnvelope(
            authorPubkey = EnvelopeFixtures.senderKey(),
            createdAtSeconds = EnvelopeFixtures.NOW.toString(),
            counterparties = arrayOf(arrayOf(EnvelopeFixtures.recipientKey())),
            content = "the whole of the flow, across the boundary",
        )
        val built = buildProposal(
            envelope = envelope,
            orderIdHex = EnvelopeFixtures.ORDER_ID_HEX,
            itemCoordinate = EnvelopeFixtures.ITEM_COORDINATE,
            terms = JsOrderTerms(priceMsat = "90000000", feeBasisPoints = 250),
            feeRecipient = EnvelopeFixtures.strangerKey(),
        )
        val event = built.event ?: fail("the proposal must build: ${built.reason} ${built.detail}")

        val sealed = seal(
            rumor = event,
            recipientPubkey = EnvelopeFixtures.recipientKey(),
            sender = JsFlowFixtures.crossed(EnvelopeFixtures.senderSigner()),
            environment = sealEnvironment(),
        )
        assertTrue(sealed.ok, "the message must seal: ${sealed.reason} ${sealed.detail}")
        val wrap = sealed.toRecipient ?: fail("a sealed message carries a recipient copy")
        val self = sealed.toSelf ?: fail("a sealed message carries a self copy")

        // §7.1 step 4's two properties, both observable and both load-bearing: the copies are
        // addressed to different keys, and they are *different messages* — each has its own seal
        // and its own throwaway keypair, so a key appearing on both would link them.
        assertEquals(EnvelopeFixtures.recipientKey(), wrap.addressee, "the recipient's copy")
        assertEquals(EnvelopeFixtures.senderKey(), self.addressee, "the sender's own copy")
        assertNotEquals(wrap.idHex, self.idHex, "§7.1 step 4: two copies, never one published twice")
        assertNotEquals(wrap.json, self.json, "§7.1 step 4: each copy has its own seal")

        val opened = open(wrap.json, JsFlowFixtures.crossed(EnvelopeFixtures.recipientSigner()))
        assertTrue(opened.ok, "the message must open: ${opened.reason} ${opened.detail}")
        val rumor = opened.rumor ?: fail("an opened message carries a rumor")

        assertEquals(EnvelopeFixtures.senderKey(), rumor.author, "§7.2's author must survive")
        assertEquals(envelope.content, rumor.content, "§4.1's content must survive")
        assertEquals(event.kind, rumor.kindNumber, "§7.4's kind must survive")
        assertEquals("PROPOSAL", rumor.messageType, "§7.4's `type` must survive")
        assertEquals(
            EnvelopeFixtures.ORDER_ID_HEX,
            rumor.orderIdHex,
            "§7.4's order tag must survive, or the message binds to nothing",
        )
        assertEquals(wrap.idHex, opened.wrapIdHex, "the id the write path computed is the one the read path did")
        JsBoundaryFixtures.assertSameEvent(
            rumor.event,
            event.event,
            "the rumor §7.4 re-emits is the rumor T42's builder produced",
        )
    }

    /**
     * §7.4's `kind:14`, which is outside the required-tag rule — so the opened chat reports no
     * `type` and no `order` whatever the builder put on it.
     *
     * The control for a crossing that looked tidier: a `JsRumor.orderIdHex` that read the tag off
     * any rumor would publish a binding for a message §7.4 says an implementation "MUST NOT derive
     * anything from" — and the tag is still there, on the re-emitted event, for a page that wants
     * to display the thread. The library draws that line with the `Bound`/`Chat` split and this
     * asserts the crossing draws it in the same place.
     */
    @Test
    @JsName("an_opened_chat_reports_no_type_and_no_order_whatever_tags_it_carries")
    fun `an opened chat reports no type and no order whatever tags it carries`() {
        val built = buildChat(
            JsRumorEnvelope(
                authorPubkey = EnvelopeFixtures.senderKey(),
                createdAtSeconds = EnvelopeFixtures.NOW.toString(),
                counterparties = arrayOf(arrayOf(EnvelopeFixtures.recipientKey())),
                content = "a chat that carries an order tag",
            ),
            orderIdHex = EnvelopeFixtures.ORDER_ID_HEX,
        )
        val event = built.event ?: fail("the chat must build: ${built.reason} ${built.detail}")
        val sealed = seal(
            rumor = event,
            recipientPubkey = EnvelopeFixtures.recipientKey(),
            sender = JsFlowFixtures.crossed(EnvelopeFixtures.senderSigner()),
            environment = sealEnvironment(),
        )
        val wrap = sealed.toRecipient ?: fail("the chat must seal: ${sealed.reason} ${sealed.detail}")
        val rumor = open(wrap.json, JsFlowFixtures.crossed(EnvelopeFixtures.recipientSigner())).rumor
            ?: fail("the chat must open")

        assertEquals("CHAT", rumor.kind, "§7.4's kind:14 row")
        assertEquals(null, rumor.messageType, "§7.4 gives a chat no `type` to carry")
        assertEquals(null, rumor.orderIdHex, "§7.4: nothing may be derived from a chat's order tag")
        assertTrue(
            rumor.event.tags.any { it.firstOrNull() == "order" },
            "the tag is still on the re-emitted event, which is what a page displays the thread from",
        )
    }

    // -----------------------------------------------------------------------------------------
    // The two calls, made twice.
    // -----------------------------------------------------------------------------------------

    /**
     * [rumor] sealed by the facade and by `GiftWrap.seal`, with a fresh identically seeded seam set
     * for each, and the two answers held equal.
     */
    private fun sealBothWays(
        what: String,
        rumor: WireEvent,
        relayHint: String? = null,
        secp: () -> Secp256k1Ops? = { null },
    ): JsSealResult {
        val expected = JsFlowFixtures.sealAnswerOf {
            GiftWrap.seal(
                rumor = rumor,
                recipientPubkey = EnvelopeFixtures.recipientKey(),
                sender = EnvelopeFixtures.senderSigner(),
                ephemeral = Nip44PayloadEphemeralSigners(),
                clock = FakeClock(EnvelopeFixtures.NOW),
                randomness = RecordingRandomness(),
                secp = secp() ?: Secp256k1Ops.FAIL_CLOSED,
                recipientRelayHint = relayHint,
            )
        }
        val crossed = seal(
            rumor = JsWireEvent(rumor),
            recipientPubkey = EnvelopeFixtures.recipientKey(),
            sender = JsFlowFixtures.crossed(EnvelopeFixtures.senderSigner()),
            environment = sealEnvironment(secp()),
            recipientRelayHint = relayHint,
        )
        JsFlowFixtures.assertSameSeal(crossed, expected, what)
        return crossed
    }

    /** One seal refusal, named, with everything else held at the working configuration. */
    private fun sealRefusal(
        what: String,
        expectedReason: EnvelopeRejection,
        rumor: WireEvent = EnvelopeFixtures.rumor(),
        recipient: String = EnvelopeFixtures.recipientKey(),
        clock: NenyaClock? = FakeClock(EnvelopeFixtures.NOW),
        randomness: Randomness? = RecordingRandomness(),
        ephemeral: EphemeralSigners? = Nip44PayloadEphemeralSigners(),
    ): String {
        val expected = JsFlowFixtures.sealAnswerOf {
            GiftWrap.seal(
                rumor = rumor,
                recipientPubkey = recipient,
                sender = EnvelopeFixtures.senderSigner(),
                ephemeral = ephemeral ?: EphemeralSigners.FAIL_CLOSED,
                clock = clock ?: NenyaClock.FAIL_CLOSED,
                randomness = randomness ?: Randomness.FAIL_CLOSED,
            )
        }
        val crossed = seal(
            rumor = JsWireEvent(rumor),
            recipientPubkey = recipient,
            sender = JsFlowFixtures.crossed(EnvelopeFixtures.senderSigner()),
            environment = JsFlowFixtures.crossedEnvironment(
                clock = clock,
                randomness = randomness,
                ephemeral = ephemeral,
            ),
        )
        JsFlowFixtures.assertSameSeal(crossed, expected, what)
        assertFalse(crossed.ok, "$what must refuse")
        assertEquals(expectedReason.name, crossed.reason, "$what must refuse for §7.1's own reason")
        assertEquals("EnvelopeRejection", crossed.reasonVocabulary, "$what: §7.1's vocabulary")
        return crossed.reason ?: fail("$what: a refusal with no reason")
    }

    /** [wrapJson] opened by the facade and by `GiftWrap.open`, and the two answers held equal. */
    private fun openBothWays(
        what: String,
        wrapJson: String,
        reader: Signer = EnvelopeFixtures.recipientSigner(),
        secp: () -> Secp256k1Ops? = { null },
    ): JsOpenResult {
        val expected = JsFlowFixtures.openAnswerOf {
            GiftWrap.open(wrapJson, reader, secp() ?: Secp256k1Ops.FAIL_CLOSED)
        }
        val crossed = open(
            wrapJson = wrapJson,
            me = JsFlowFixtures.crossed(reader),
            environment = JsFlowFixtures.crossedEnvironment(secp = secp()),
        )
        JsFlowFixtures.assertSameOpen(crossed, expected, what)
        return crossed
    }

    /** One read refusal, named. */
    private fun openRefusal(
        what: String,
        expectedReason: EnvelopeRejection,
        wrapJson: String,
        reader: Signer = EnvelopeFixtures.recipientSigner(),
    ): String {
        val crossed = openBothWays(what, wrapJson, reader)
        assertFalse(crossed.ok, "$what must refuse")
        assertEquals(expectedReason.name, crossed.reason, "$what must refuse for §7.1's own reason")
        assertEquals("EnvelopeRejection", crossed.reasonVocabulary, "$what: §7.1's vocabulary")
        return crossed.reason ?: fail("$what: a refusal with no reason")
    }

    /** The working seam set, as the functions a page supplies. */
    private fun sealEnvironment(secp: Secp256k1Ops? = null): JsEnvironment =
        JsFlowFixtures.crossedEnvironment(
            clock = FakeClock(EnvelopeFixtures.NOW),
            randomness = RecordingRandomness(),
            secp = secp,
            ephemeral = Nip44PayloadEphemeralSigners(),
        )

    /** One `kind:1059` this library sealed, for the read path to be measured against. */
    private fun sealedWrapJson(): String = sealedMessage().toRecipient.json

    private fun sealedMessage(): SealedMessage = GiftWrap.seal(
        rumor = EnvelopeFixtures.rumor(),
        recipientPubkey = EnvelopeFixtures.recipientKey(),
        sender = EnvelopeFixtures.senderSigner(),
        ephemeral = Nip44PayloadEphemeralSigners(),
        clock = FakeClock(EnvelopeFixtures.NOW),
        randomness = RecordingRandomness(),
    )
}
