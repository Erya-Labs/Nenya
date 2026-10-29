package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.envelope.EnvelopeFixtures
import dev.eryalabs.nenya.envelope.EnvelopeRejection
import dev.eryalabs.nenya.envelope.GiftWrap
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.seam.FakeSecp256k1Ops
import dev.eryalabs.nenya.seam.Nip44PayloadEphemeralSigners
import dev.eryalabs.nenya.seam.RecordingRandomness
import dev.eryalabs.nenya.seam.Secp256k1Ops
import dev.eryalabs.nenya.seam.Signer
import dev.eryalabs.nenya.wire.WireEvent
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * T55: [sealAsync] and [openAsync] answer exactly what [seal] and [open] answer, however and whenever
 * the page's signer completes — at once, held back, interleaved with another call's, twice, or never.
 *
 * ### Why each side gets its own seams, again
 *
 * For [JsEnvelopeEqualityTest]'s reason: §7.1 step 5 draws four random timestamps and step 3 mints two
 * throwaway keys, so one source shared by both sides would compare a message with its successor. Each
 * side gets a fresh, identically seeded set, and the equality is over the bytes the two produce. That
 * is also what makes this the memo's proof: the async side re-runs `GiftWrap.seal` once per question
 * the sender is asked, and its bytes equal the single sync run's only if every re-run was handed the
 * same clock reading, the same draws and the same throwaway signers.
 *
 * ### Exactly once, everywhere
 *
 * Every answer is collected by [Delivered], and every helper asserts it was delivered exactly once —
 * so a driver that delivered twice, or delivered a stopped run's refusal before the real answer, fails
 * every test in this file rather than one.
 */
class JsAsyncEnvelopeEqualityTest {

    // -----------------------------------------------------------------------------------------
    // §7.1's write procedure.
    // -----------------------------------------------------------------------------------------

    /**
     * Both copies byte for byte, over every shape [JsEnvelopeEqualityTest] seals, with the sender
     * answering at once and with every answer held back until the test fires it.
     *
     * The sender is asked each of its five questions exactly once, in §7.1's order — the key, then
     * per copy the encryption and the seal's signature — however many runs the driver made.
     */
    @Test
    @JsName("seal_async_equals_seal_byte_for_byte_on_both_wraps")
    fun `sealAsync equals seal byte for byte on both wraps`() {
        for (immediate in listOf(true, false)) {
            for (hint in listOf(null, "wss://relay.example")) {
                sealAsyncBothWays("the ordinary rumor, hint=$hint, immediate=$immediate", EnvelopeFixtures.rumor(), immediate, hint)
            }
            sealAsyncBothWays("the minimal rumor, immediate=$immediate", EnvelopeFixtures.minimalRumor(), immediate)
            sealAsyncBothWays(
                "an injected verifier, immediate=$immediate",
                EnvelopeFixtures.rumor(),
                immediate,
                secp = { FakeSecp256k1Ops() },
            )
        }
    }

    /**
     * §7.1's write refusals, as values, equal to [seal]'s over a sync signer answering the same — the
     * three missing seams, the two key rules, and each of the sender's questions completed `null`.
     */
    @Test
    @JsName("seal_async_returns_seal_s_refusals_as_values")
    fun `sealAsync returns seal's refusals as values`() {
        val honest = JsFlowFixtures.crossed(EnvelopeFixtures.senderSigner())
        val reached = mutableSetOf<String>()

        reached += sealAsyncRefusal("signEvent completed null", EnvelopeRejection.SIGNATURE_UNAVAILABLE, sender = JsSigner(honest.publicKeyHex, { null }, honest.nip44Encrypt, honest.nip44Decrypt))
        reached += sealAsyncRefusal("nip44Encrypt completed null", EnvelopeRejection.ENCRYPTION_UNAVAILABLE, sender = JsSigner(honest.publicKeyHex, honest.signEvent, { _, _ -> null }, honest.nip44Decrypt))
        reached += sealAsyncRefusal("publicKeyHex completed null", EnvelopeRejection.SIGNER_PUBLIC_KEY_UNAVAILABLE, sender = JsSigner({ null }, honest.signEvent, honest.nip44Encrypt, honest.nip44Decrypt))
        reached += sealAsyncRefusal("the recipient is the sender", EnvelopeRejection.RECIPIENT_IS_SENDER, recipient = EnvelopeFixtures.senderKey())
        reached += sealAsyncRefusal("the recipient key is malformed", EnvelopeRejection.RECIPIENT_KEY_MALFORMED, recipient = "not a key")
        reached += sealAsyncRefusal("no clock", EnvelopeRejection.CLOCK_UNAVAILABLE, environment = { JsFlowFixtures.crossedEnvironment(randomness = RecordingRandomness(), ephemeral = Nip44PayloadEphemeralSigners()) })
        reached += sealAsyncRefusal("no randomness", EnvelopeRejection.RANDOMNESS_UNAVAILABLE, environment = { JsFlowFixtures.crossedEnvironment(clock = FakeClock(EnvelopeFixtures.NOW), ephemeral = Nip44PayloadEphemeralSigners()) })
        reached += sealAsyncRefusal("no one-time signer", EnvelopeRejection.EPHEMERAL_SIGNER_UNAVAILABLE, environment = { JsFlowFixtures.crossedEnvironment(clock = FakeClock(EnvelopeFixtures.NOW), randomness = RecordingRandomness()) })

        assertEquals(8, reached.size, "each control must reach its own refusal: $reached")
    }

    // -----------------------------------------------------------------------------------------
    // §7.1's read procedure.
    // -----------------------------------------------------------------------------------------

    /**
     * A wrap opened field for field as [open] opens it, with and without a verifier, at once and held
     * back — and the reader asked its key and the two decryptions, each exactly once.
     */
    @Test
    @JsName("open_async_equals_open_field_for_field")
    fun `openAsync equals open field for field`() {
        val wrapJson = sealedWrapJson()
        for (immediate in listOf(true, false)) {
            for (secp in listOf<() -> Secp256k1Ops?>({ null }, { FakeSecp256k1Ops() })) {
                val completions = JsFlowFixtures.Completions(immediate)
                val opened = openAsyncBothWays("immediate=$immediate", wrapJson, completions, secp = secp)
                assertTrue(opened.ok, "the wrap must open: ${opened.reason}")
                assertEquals(
                    listOf("publicKeyHex", "nip44Decrypt", "nip44Decrypt"),
                    completions.asked,
                    "§7.1's read asks the reader's key, then decrypts the wrap and the seal — once each",
                )
            }
        }
    }

    /**
     * §7.1's read refusals as values — and the `p` tag still checked before the first decryption, so a
     * stranger's wrap costs the reader one question and no decryption at all.
     */
    @Test
    @JsName("open_async_returns_open_s_refusals_as_values_and_decrypts_nothing_for_a_stranger")
    fun `openAsync returns open's refusals as values and decrypts nothing for a stranger`() {
        val wrapJson = sealedWrapJson()

        val stranger = JsFlowFixtures.Completions()
        val notMine = openAsyncBothWays("addressed to somebody else", wrapJson, stranger, reader = EnvelopeFixtures.senderSigner())
        assertEquals(EnvelopeRejection.NOT_ADDRESSED_TO_ME.name, notMine.reason)
        assertEquals(listOf("publicKeyHex"), stranger.asked, "§7.1 step 3: rejected before any decryption")

        val rubbish = JsFlowFixtures.Completions()
        assertEquals(EnvelopeRejection.MALFORMED_WRAP.name, openAsyncBothWays("rubbish", "{", rubbish).reason)
        assertEquals(emptyList(), rubbish.asked, "a wrap that is not JSON asks the reader nothing")

        val honest = JsFlowFixtures.crossed(EnvelopeFixtures.recipientSigner())
        for ((what, reason, decrypt) in listOf<Triple<String, EnvelopeRejection, (String, String) -> JsDecryption?>>(
            Triple("nip44Decrypt completed null", EnvelopeRejection.COULD_NOT_DECRYPT_WRAP, { _, _ -> null }),
            Triple("nip44Decrypt refused", EnvelopeRejection.WRAP_DECRYPTION_REFUSED, { _, _ -> JsDecryption(false) }),
        )) {
            val reader = JsSigner(honest.publicKeyHex, honest.signEvent, honest.nip44Encrypt, decrypt)
            val sync = open(wrapJson, reader)
            val async = Delivered<JsOpenResult>()
            val completions = JsFlowFixtures.Completions()
            openAsync(wrapJson, JsFlowFixtures.crossedAsync(reader, completions), onResult = async.sink)
            completions.drain()
            assertSameOpenResult(async.only(what), sync, what)
            assertEquals(reason.name, sync.reason, "$what: §7.1's own reason")
        }
    }

    // -----------------------------------------------------------------------------------------
    // The two together, and the completions a page gets wrong.
    // -----------------------------------------------------------------------------------------

    /**
     * T42's builder → [sealAsync] → [openAsync], with one sealing and one opening in flight at once and
     * their completions fired alternately from one queue — the round trip a page makes, with
     * `window.nostr` answering both in whatever order it likes.
     */
    @Test
    @JsName("build_seal_async_and_open_async_round_trip_with_interleaved_completions")
    fun `build sealAsync and openAsync round trip with interleaved completions`() {
        val first = proposal("the first message")
        val second = proposal("the second message")
        val queue = JsFlowFixtures.Completions()

        val sealedFirst = Delivered<JsSealResult>()
        sealAsync(first, EnvelopeFixtures.recipientKey(), JsFlowFixtures.crossedAsync(EnvelopeFixtures.senderSigner(), queue, "seal1."), sealedFirst.sink, sealEnvironment())
        queue.drain()
        val wrap = sealedFirst.only("the first seal").toRecipient ?: fail("the first message must seal")

        // Both in flight: each asks one question, and firing oldest first alternates between them.
        val sealedSecond = Delivered<JsSealResult>()
        val openedFirst = Delivered<JsOpenResult>()
        sealAsync(second, EnvelopeFixtures.recipientKey(), JsFlowFixtures.crossedAsync(EnvelopeFixtures.senderSigner(), queue, "seal2."), sealedSecond.sink, sealEnvironment())
        openAsync(wrap.json, JsFlowFixtures.crossedAsync(EnvelopeFixtures.recipientSigner(), queue, "open1."), onResult = openedFirst.sink)
        assertEquals(2, queue.pending, "one question from each call is waiting")
        queue.drain()

        val asked = queue.asked
        val opens = asked.indices.filter { asked[it].startsWith("open1.") }
        val seals = asked.indices.filter { asked[it].startsWith("seal2.") }
        assertTrue(
            opens.any { it > seals.first() && it < seals.last() },
            "the opening's questions must fall between the second sealing's: $asked",
        )

        assertRoundTrip(openedFirst.only("the first open"), first, "the first message")
        val secondWrap = sealedSecond.only("the second seal").toRecipient ?: fail("the second message must seal")
        val openedSecond = Delivered<JsOpenResult>()
        openAsync(secondWrap.json, JsFlowFixtures.crossedAsync(EnvelopeFixtures.recipientSigner(), queue, "open2."), onResult = openedSecond.sink)
        queue.drain()
        assertRoundTrip(openedSecond.only("the second open"), second, "the second message")
    }

    /**
     * A signer that never completes: no answer, nothing thrown, and only the first question asked —
     * once. No timeout delivers a guess instead.
     */
    @Test
    @JsName("a_signer_that_never_completes_gets_no_answer_and_nothing_thrown")
    fun `a signer that never completes gets no answer and nothing thrown`() {
        val sealing = JsFlowFixtures.Completions()
        val sealed = Delivered<JsSealResult>()
        sealAsync(JsWireEvent(EnvelopeFixtures.rumor()), EnvelopeFixtures.recipientKey(), JsFlowFixtures.crossedAsync(EnvelopeFixtures.senderSigner(), sealing), sealed.sink, sealEnvironment())
        assertEquals(emptyList(), sealed.answers, "no completion, so no answer")
        assertEquals(listOf("publicKeyHex"), sealing.asked, "the first question, asked once")
        assertEquals(1, sealing.pending, "and still waiting")

        val opening = JsFlowFixtures.Completions()
        val opened = Delivered<JsOpenResult>()
        openAsync(sealedWrapJson(), JsFlowFixtures.crossedAsync(EnvelopeFixtures.recipientSigner(), opening), onResult = opened.sink)
        assertEquals(emptyList(), opened.answers, "no completion, so no answer")
        assertEquals(listOf("publicKeyHex"), opening.asked, "the first question, asked once")
    }

    /**
     * A completion fired twice changes nothing, before the answer or after it: the question is not
     * asked again, the answer is delivered once, and it is still [seal]'s.
     */
    @Test
    @JsName("a_duplicate_or_stale_completion_changes_nothing")
    fun `a duplicate or stale completion changes nothing`() {
        val completions = JsFlowFixtures.Completions()
        val sealed = Delivered<JsSealResult>()
        sealAsync(JsWireEvent(EnvelopeFixtures.rumor()), EnvelopeFixtures.recipientKey(), JsFlowFixtures.crossedAsync(EnvelopeFixtures.senderSigner(), completions), sealed.sink, sealEnvironment())

        completions.fireOldest()
        val askedAfterFirst = completions.asked.toList()
        completions.fired.single()()
        assertEquals(askedAfterFirst, completions.asked, "a duplicate completion must not start a run that asks again")

        completions.drain()
        val answer = sealed.only("the duplicated seal")
        for (stale in completions.fired.toList()) stale()
        assertEquals(1, sealed.answers.size, "a stale completion after delivery must deliver nothing")
        JsFlowFixtures.assertSameSeal(answer, expectedSeal(EnvelopeFixtures.rumor()), "after duplicate completions")
    }

    // -----------------------------------------------------------------------------------------
    // The helpers.
    // -----------------------------------------------------------------------------------------

    /** Every answer a call delivered; [only] is the exactly-once pin every test here goes through. */
    private class Delivered<R> {
        val answers: MutableList<R> = mutableListOf()
        val sink: (R) -> Unit = { answers += it }

        fun only(what: String): R {
            assertEquals(1, answers.size, "$what: onResult must be called exactly once")
            return answers.single()
        }
    }

    /** [rumor] sealed by `GiftWrap.seal`, by [seal] and by [sealAsync], each over fresh seams. */
    private fun sealAsyncBothWays(
        what: String,
        rumor: WireEvent,
        immediate: Boolean,
        relayHint: String? = null,
        secp: () -> Secp256k1Ops? = { null },
    ) {
        val syncRandomness = RecordingRandomness()
        val expected = JsFlowFixtures.sealAnswerOf {
            GiftWrap.seal(
                rumor = rumor,
                recipientPubkey = EnvelopeFixtures.recipientKey(),
                sender = EnvelopeFixtures.senderSigner(),
                ephemeral = Nip44PayloadEphemeralSigners(),
                clock = FakeClock(EnvelopeFixtures.NOW),
                randomness = syncRandomness,
                secp = secp() ?: Secp256k1Ops.FAIL_CLOSED,
                recipientRelayHint = relayHint,
            )
        }
        val sync = seal(
            JsWireEvent(rumor),
            EnvelopeFixtures.recipientKey(),
            JsFlowFixtures.crossed(EnvelopeFixtures.senderSigner()),
            sealEnvironment(secp()),
            relayHint,
        )

        val asyncRandomness = RecordingRandomness()
        val ephemeral = Nip44PayloadEphemeralSigners()
        val completions = JsFlowFixtures.Completions(immediate)
        val delivered = Delivered<JsSealResult>()
        sealAsync(
            JsWireEvent(rumor),
            EnvelopeFixtures.recipientKey(),
            JsFlowFixtures.crossedAsync(EnvelopeFixtures.senderSigner(), completions),
            delivered.sink,
            JsFlowFixtures.crossedEnvironment(FakeClock(EnvelopeFixtures.NOW), asyncRandomness, secp(), ephemeral),
            relayHint,
        )
        completions.drain()
        val async = delivered.only(what)

        JsFlowFixtures.assertSameSeal(async, expected, what)
        assertTrue(async.ok, "$what must seal: ${async.reason}")
        assertSameSealResult(async, sync, what)
        assertEquals(syncRandomness.requested, asyncRandomness.requested, "$what: each draw made once, as the sync call made it")
        assertEquals(2, ephemeral.handedOut.size, "$what: one throwaway key per copy, however many runs")
        assertEquals(
            listOf("publicKeyHex", "nip44Encrypt", "signEvent", "nip44Encrypt", "signEvent"),
            completions.asked,
            "$what: each of the sender's questions asked once, in §7.1's order",
        )
    }

    /** One write refusal: [sealAsync] answers what [seal] answers over a sync [sender] saying the same. */
    private fun sealAsyncRefusal(
        what: String,
        expectedReason: EnvelopeRejection,
        sender: JsSigner = JsFlowFixtures.crossed(EnvelopeFixtures.senderSigner()),
        recipient: String = EnvelopeFixtures.recipientKey(),
        environment: () -> JsEnvironment = { sealEnvironment() },
    ): String {
        val rumor = JsWireEvent(EnvelopeFixtures.rumor())
        val sync = seal(rumor, recipient, sender, environment())
        val completions = JsFlowFixtures.Completions()
        val delivered = Delivered<JsSealResult>()
        sealAsync(rumor, recipient, JsFlowFixtures.crossedAsync(sender, completions), delivered.sink, environment())
        completions.drain()
        val async = delivered.only(what)
        assertSameSealResult(async, sync, what)
        assertEquals(expectedReason.name, async.reason, "$what must refuse for §7.1's own reason")
        assertEquals("EnvelopeRejection", async.reasonVocabulary, "$what: §7.1's vocabulary")
        return async.reason ?: fail("$what: a refusal with no reason")
    }

    /** [wrapJson] opened by `GiftWrap.open`, by [open] and by [openAsync]; the async answer returned. */
    private fun openAsyncBothWays(
        what: String,
        wrapJson: String,
        completions: JsFlowFixtures.Completions,
        reader: Signer = EnvelopeFixtures.recipientSigner(),
        secp: () -> Secp256k1Ops? = { null },
    ): JsOpenResult {
        val expected = JsFlowFixtures.openAnswerOf { GiftWrap.open(wrapJson, reader, secp() ?: Secp256k1Ops.FAIL_CLOSED) }
        val sync = open(wrapJson, JsFlowFixtures.crossed(reader), JsFlowFixtures.crossedEnvironment(secp = secp()))
        val delivered = Delivered<JsOpenResult>()
        openAsync(
            wrapJson,
            JsFlowFixtures.crossedAsync(reader, completions),
            delivered.sink,
            JsFlowFixtures.crossedEnvironment(secp = secp()),
        )
        completions.drain()
        val async = delivered.only(what)
        JsFlowFixtures.assertSameOpen(async, expected, what)
        assertSameOpenResult(async, sync, what)
        return async
    }

    /** Every field [JsSealResult] publishes, the two copies down to their JSON. */
    private fun assertSameSealResult(async: JsSealResult, sync: JsSealResult, what: String) {
        assertEquals(sync.ok, async.ok, "$what: ok")
        assertEquals(sync.kind, async.kind, "$what: kind")
        assertEquals(sync.reason, async.reason, "$what: reason")
        assertEquals(sync.reasonVocabulary, async.reasonVocabulary, "$what: reasonVocabulary")
        assertEquals(sync.tag, async.tag, "$what: tag")
        assertEquals(sync.detail, async.detail, "$what: detail")
        assertContentEquals(sync.notPerformedHere, async.notPerformedHere, "$what: notPerformedHere")
        for ((copy, pair) in listOf("toRecipient" to (sync.toRecipient to async.toRecipient), "toSelf" to (sync.toSelf to async.toSelf))) {
            assertEquals(pair.first?.json, pair.second?.json, "$what: $copy's bytes")
            assertEquals(pair.first?.idHex, pair.second?.idHex, "$what: $copy's id")
            assertEquals(pair.first?.addressee, pair.second?.addressee, "$what: $copy's addressee")
        }
    }

    /** Every field [JsOpenResult] publishes, and the rumor's through [JsFlowFixtures.assertSameRumor]. */
    private fun assertSameOpenResult(async: JsOpenResult, sync: JsOpenResult, what: String) {
        assertEquals(sync.ok, async.ok, "$what: ok")
        assertEquals(sync.kind, async.kind, "$what: kind")
        assertEquals(sync.sealSignature, async.sealSignature, "$what: sealSignature")
        assertEquals(sync.wrapSignature, async.wrapSignature, "$what: wrapSignature")
        assertEquals(sync.wrapIdHex, async.wrapIdHex, "$what: wrapIdHex")
        assertContentEquals(sync.notPerformedHere, async.notPerformedHere, "$what: notPerformedHere")
        assertEquals(sync.reason, async.reason, "$what: reason")
        assertEquals(sync.reasonVocabulary, async.reasonVocabulary, "$what: reasonVocabulary")
        assertEquals(sync.tag, async.tag, "$what: tag")
        assertEquals(sync.detail, async.detail, "$what: detail")
        val expected = sync.rumor
        if (expected == null) {
            assertEquals(null, async.rumor, "$what: a refusal carries no rumor")
        } else {
            JsFlowFixtures.assertSameRumor(async.rumor ?: fail("$what: no rumor"), expected.rumor, what)
        }
    }

    /** The opened rumor is the one T42's builder produced, author and binding included. */
    private fun assertRoundTrip(opened: JsOpenResult, built: JsWireEvent, what: String) {
        assertTrue(opened.ok, "$what must open: ${opened.reason} ${opened.detail}")
        val rumor = opened.rumor ?: fail("$what: an opened message carries a rumor")
        assertEquals(EnvelopeFixtures.senderKey(), rumor.author, "$what: §7.2's author")
        assertEquals("PROPOSAL", rumor.messageType, "$what: §7.4's `type`")
        assertEquals(EnvelopeFixtures.ORDER_ID_HEX, rumor.orderIdHex, "$what: §7.4's order tag")
        JsBoundaryFixtures.assertSameEvent(rumor.event, built.event, "$what: the rumor the builder produced")
    }

    /** A §7.5 proposal from T42's builder, carrying [content]. */
    private fun proposal(content: String): JsWireEvent {
        val built = buildProposal(
            envelope = JsRumorEnvelope(
                authorPubkey = EnvelopeFixtures.senderKey(),
                createdAtSeconds = EnvelopeFixtures.NOW.toString(),
                counterparties = arrayOf(arrayOf(EnvelopeFixtures.recipientKey())),
                content = content,
            ),
            orderIdHex = EnvelopeFixtures.ORDER_ID_HEX,
            itemCoordinate = EnvelopeFixtures.ITEM_COORDINATE,
            terms = JsOrderTerms(priceMsat = "90000000", feeBasisPoints = 250),
            feeRecipient = EnvelopeFixtures.strangerKey(),
        )
        return built.event ?: fail("the proposal must build: ${built.reason} ${built.detail}")
    }

    /** `GiftWrap.seal`'s answer over the working seams, fresh. */
    private fun expectedSeal(rumor: WireEvent): JsFlowFixtures.SealAnswer = JsFlowFixtures.sealAnswerOf {
        GiftWrap.seal(
            rumor = rumor,
            recipientPubkey = EnvelopeFixtures.recipientKey(),
            sender = EnvelopeFixtures.senderSigner(),
            ephemeral = Nip44PayloadEphemeralSigners(),
            clock = FakeClock(EnvelopeFixtures.NOW),
            randomness = RecordingRandomness(),
        )
    }

    /** The working seam set, fresh, as the functions a page supplies. */
    private fun sealEnvironment(secp: Secp256k1Ops? = null): JsEnvironment =
        JsFlowFixtures.crossedEnvironment(
            clock = FakeClock(EnvelopeFixtures.NOW),
            randomness = RecordingRandomness(),
            secp = secp,
            ephemeral = Nip44PayloadEphemeralSigners(),
        )

    /** One `kind:1059` this library sealed, for the read path. */
    private fun sealedWrapJson(): String = GiftWrap.seal(
        rumor = EnvelopeFixtures.rumor(),
        recipientPubkey = EnvelopeFixtures.recipientKey(),
        sender = EnvelopeFixtures.senderSigner(),
        ephemeral = Nip44PayloadEphemeralSigners(),
        clock = FakeClock(EnvelopeFixtures.NOW),
        randomness = RecordingRandomness(),
    ).toRecipient.json
}
