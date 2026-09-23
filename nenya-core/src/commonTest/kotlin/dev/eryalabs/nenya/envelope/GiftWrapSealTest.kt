package dev.eryalabs.nenya.envelope

import dev.eryalabs.nenya.seam.AllOnesRandomness
import dev.eryalabs.nenya.seam.EmptyCiphertextSigner
import dev.eryalabs.nenya.seam.EphemeralSigners
import dev.eryalabs.nenya.seam.FakeClock
import dev.eryalabs.nenya.seam.FakeKey
import dev.eryalabs.nenya.seam.FakeKeyRanges
import dev.eryalabs.nenya.seam.FakeSecp256k1Ops
import dev.eryalabs.nenya.seam.FixedEphemeralSigners
import dev.eryalabs.nenya.seam.LyingNip44PayloadEphemeralSigners
import dev.eryalabs.nenya.seam.NenyaClock
import dev.eryalabs.nenya.seam.Nip44PayloadEphemeralSigners
import dev.eryalabs.nenya.seam.Nip44PayloadSigner
import dev.eryalabs.nenya.seam.NoEncryptionSigner
import dev.eryalabs.nenya.seam.NoSignatureSigner
import dev.eryalabs.nenya.seam.NonAsciiCiphertextSigner
import dev.eryalabs.nenya.seam.OversizedCiphertextSigner
import dev.eryalabs.nenya.seam.Randomness
import dev.eryalabs.nenya.seam.RecordingRandomness
import dev.eryalabs.nenya.seam.ScriptedEphemeralSigners
import dev.eryalabs.nenya.seam.SeamCapability
import dev.eryalabs.nenya.seam.Secp256k1Ops
import dev.eryalabs.nenya.seam.ShortPublicKeySigner
import dev.eryalabs.nenya.seam.ShortSignatureSigner
import dev.eryalabs.nenya.seam.Signer
import dev.eryalabs.nenya.seam.WrongLengthRandomness
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.wire.EventId
import dev.eryalabs.nenya.wire.EventJson
import dev.eryalabs.nenya.wire.WireEvent
import dev.eryalabs.nenya.wire.WireLimits
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * §7.1's write procedure: a rumor sealed and wrapped twice, opened again by the parties it was
 * addressed to, and refused by name everywhere §7.1 says it must be.
 *
 * ### What the positive direction proves, and how
 *
 * Not "the function returned something". Every emitted wrap is **opened again** by the addressee's
 * own signer, through `EventJson`, `CheckedEvent.checkEventId` and `Signer.nip44Decrypt` — all of
 * them functions that existed before this task — and the rumor that comes out the far end is held
 * byte-equal to the JSON of the rumor that went in. See [EnvelopeFixtures.open] for why the reader
 * is the test's and not `GiftWrap`'s.
 *
 * ### The non-vacuity floor
 *
 * Every `EnvelopeRejection` whose side is [EnvelopeSide.SEAL] is produced by at least one control
 * below, and the expected set is `EnvelopeRejection.entries.filter { it.side == SEAL }` — read off
 * the enum, never a list written out here. A constant added to the write side without a control is
 * therefore a red suite rather than a refusal nobody has shown the code can raise. That is what
 * [EnvelopeSide] exists for, and it is why the constants declare their side with no default.
 */
class GiftWrapSealTest {

    // -----------------------------------------------------------------------------------------
    // The positive direction.
    // -----------------------------------------------------------------------------------------

    /**
     * §7.1 steps 1 to 4, both copies, opened by the two keys they were addressed to.
     *
     * The seal is checked for the two things §7.1 step 2 fixes and the read procedure's step 6
     * checks again — kind 13 and **empty** tags — and the wrap for step 3's kind and its single
     * `p` tag. The two copies carry different seals, because a seal is encrypted to exactly one
     * reader, and the rumor inside both is the same bytes.
     */
    @JsName("both_copies_open_to_the_same_rumor_for_the_keys_they_are_addressed_to")
    @Test
    fun `both copies open to the same rumor, for the keys they are addressed to`() {
        val sender = EnvelopeFixtures.senderSigner()
        val recipient = EnvelopeFixtures.recipientSigner()
        val ephemeral = Nip44PayloadEphemeralSigners()
        val rumor = EnvelopeFixtures.rumor()

        val message = GiftWrap.seal(
            rumor = rumor,
            recipientPubkey = EnvelopeFixtures.recipientKey(),
            sender = sender,
            ephemeral = ephemeral,
            clock = FakeClock(EnvelopeFixtures.NOW),
            randomness = RecordingRandomness(),
        )

        assertEquals(EnvelopeFixtures.recipientKey(), message.toRecipient.addressee)
        assertEquals(EnvelopeFixtures.senderKey(), message.toSelf.addressee)

        val expectedRumorJson = EventJson.writeUnsigned(rumor, EventId.of(rumor))
        val toRecipient = EnvelopeFixtures.open(message.toRecipient.json, recipient)
        val toSelf = EnvelopeFixtures.open(message.toSelf.json, sender)

        for (opened in listOf(toRecipient, toSelf)) {
            assertEquals(
                expectedRumorJson,
                opened.rumorJson,
                "§7.1: the rumor's content must survive the round trip byte for byte",
            )
            assertFalse("\"sig\"" in opened.rumorJson, "§7.1 step 1: no `sig` key at all")
            assertEquals(NenyaKind.SEAL, opened.seal.event.kind)
            assertEquals(
                emptyList(),
                opened.seal.event.tags,
                "§7.1 step 2 requires the seal's tags be empty, and step 6 rejects a seal whose " +
                    "tags are not",
            )
            assertEquals(
                EnvelopeFixtures.senderKey(),
                opened.seal.event.pubkey,
                "§7.2 attributes every term to the seal's pubkey, so it is the sender's real key",
            )
            assertEquals(NenyaKind.GIFT_WRAP, opened.wrap.event.kind)
            assertEquals(1, opened.wrap.event.tags.size, "§7.1 step 3: exactly one tag")
        }

        assertEquals(
            listOf("p", EnvelopeFixtures.recipientKey()),
            EnvelopeFixtures.recipientTagOf(toRecipient.wrap),
        )
        assertEquals(
            listOf("p", EnvelopeFixtures.senderKey()),
            EnvelopeFixtures.recipientTagOf(toSelf.wrap),
        )
        assertNotEquals(
            toRecipient.sealJson,
            toSelf.sealJson,
            "§7.1 step 4: each copy gets its own seal, encrypted to that copy's addressee",
        )
        assertEquals(message.toRecipient.id, toRecipient.wrapChecked.id, "§4.1: the id recomputes")
        assertEquals(message.toSelf.id, toSelf.wrapChecked.id)
        assertEquals(
            EventId.of(rumor),
            toRecipient.rumorChecked.id,
            "and the rumor's own id recomputes to the one the sender built",
        )
    }

    /** §7.1 step 4's two throwaway keys, and §3's rule about how often each seam was asked. */
    @JsName("each_copy_uses_one_fresh_throwaway_key_once")
    @Test
    fun `each copy uses one fresh throwaway key, once`() {
        val sender = EnvelopeFixtures.senderSigner()
        val ephemeral = Nip44PayloadEphemeralSigners()

        val message = sealWith(sender = sender, ephemeral = ephemeral)

        assertEquals(2, ephemeral.handedOut.size, "§7.1 step 4: one wrap per copy, one key per wrap")
        for (throwaway in ephemeral.handedOut) {
            assertEquals(1, throwaway.signEventCalls, "one signature per throwaway key")
            assertEquals(1, throwaway.encryptCalls, "one encryption per throwaway key")
            assertEquals(0, throwaway.decryptCalls, "a throwaway signer never decrypts")
        }
        assertEquals(2, sender.signEventCalls, "the sender signs one seal per copy")
        assertEquals(2, sender.encryptCalls, "and encrypts one seal per copy")
        assertEquals(0, sender.decryptCalls)

        val keys = ephemeral.keysHandedOut
        assertEquals(2, keys.toSet().size, "§7.1 step 4: the keypair MUST differ between the copies")
        assertFalse(EnvelopeFixtures.senderKey() in keys)
        assertFalse(EnvelopeFixtures.recipientKey() in keys)
        // The keys really are the ones on the wire, rather than merely handed out.
        val onTheWire = listOf(message.toRecipient, message.toSelf).map {
            EventJson.read(it.json, EnvelopeFixtures.WRAP_LIMITS).event.pubkey
        }
        assertEquals(keys.toSet(), onTheWire.toSet())
    }

    /** §7.1 step 3's third tag element, emitted verbatim when given and absent when not. */
    @JsName("the_relay_hint_is_the_third_element_of_the_p_tag_when_given")
    @Test
    fun `the relay hint is the third element of the p tag when given`() {
        val hint = "wss://relay.example.invalid/"

        val withHint = sealWith(hint = hint)
        val without = sealWith()

        assertEquals(
            listOf("p", EnvelopeFixtures.recipientKey(), hint),
            EnvelopeFixtures.recipientTagOf(
                EventJson.read(withHint.toRecipient.json, EnvelopeFixtures.WRAP_LIMITS),
            ),
        )
        assertEquals(
            listOf("p", EnvelopeFixtures.recipientKey()),
            EnvelopeFixtures.recipientTagOf(
                EventJson.read(without.toRecipient.json, EnvelopeFixtures.WRAP_LIMITS),
            ),
            "a hint nobody gave must not become an empty third element",
        )
    }

    /**
     * §17's rule in the direction nobody else is watching: what this library did **not** check
     * while emitting is reported, and what it did check is not reported as unchecked.
     */
    @JsName("a_verifier_that_answers_unavailable_emits_and_records_the_check_as_not_performed")
    @Test
    fun `a verifier that answers unavailable emits, and records the check as not performed`() {
        val notChecked = sealWith(secp = Secp256k1Ops.FAIL_CLOSED)
        val verifier = FakeSecp256k1Ops()

        val checked = sealWith(secp = verifier)

        assertEquals(
            setOf(SeamCapability.BIP340_VERIFICATION),
            notChecked.notPerformedHere,
            "§17 forbids reporting an unverified thing as verified, and requires the statement be " +
                "machine-readable",
        )
        assertEquals(
            emptySet(),
            checked.notPerformedHere,
            "and a library that did obtain a verdict for every signature it emitted must not claim " +
                "otherwise",
        )
        assertEquals(
            4,
            verifier.calls,
            "two seals and two wraps: every signature this library emits is verified on emit",
        )
    }

    // -----------------------------------------------------------------------------------------
    // The non-vacuity floor.
    // -----------------------------------------------------------------------------------------

    /**
     * Every write-side constant is reachable, and the expected set is read off the enum.
     *
     * The mutation this is aimed at is a constant added to [EnvelopeRejection] with
     * [EnvelopeSide.SEAL] and no control — which a hand-written list would never notice.
     */
    @JsName("every_write_side_rejection_constant_is_produced_by_a_control")
    @Test
    fun `every write-side rejection constant is produced by a control`() {
        val expected = EnvelopeRejection.entries.filter { it.side == EnvelopeSide.SEAL }.toSet()
        assertTrue(expected.isNotEmpty(), "the write side declares no constants, so this proves nothing")

        val produced = mutableSetOf<EnvelopeRejection>()
        for (control in controls()) {
            val refused = refusalOf(control)
            assertEquals(refused.reason, control.reason, "${control.label} refused for another reason")
            produced += refused.reason
        }

        assertEquals(
            expected,
            produced,
            "every EnvelopeRejection whose side is SEAL must be produced by a control, and no " +
                "control may produce a read-side one",
        )
    }

    /**
     * §12 items 2 and 11 and §7.2, swept over every refusal this write path can make.
     *
     * A refusal message is the most likely place a key or a private term escapes: it is written
     * once, read in a log, and nobody looks at it again. So every control's message **and** its
     * `toString` is searched for both parties' keys, every throwaway key the fixtures hand out, the
     * rumor's `content` and an order id.
     *
     * **The cause chain is walked too.** Three write-side refusals keep the wire layer's own
     * exception as their cause, and a client that prints a stack trace prints those messages: a
     * sweep that stopped at the outermost exception would not notice the day one of them started
     * naming the value it refused.
     */
    @JsName("no_refusal_names_a_key_a_private_term_or_an_order_id")
    @Test
    fun `no refusal names a key, a private term or an order id`() {
        val forbidden = forbiddenStrings()
        assertTrue(forbidden.size > 5, "the sweep must have something to look for")
        var causesSeen = 0

        for (control in controls()) {
            val refused = refusalOf(control)
            val chain = StringBuilder("${refused.message} $refused")
            var cause: Throwable? = refused.cause
            while (cause != null) {
                causesSeen++
                chain.append(' ').append(cause.message).append(' ').append(cause)
                cause = cause.cause
            }
            val text = chain.toString()
            for (secret in forbidden) {
                assertFalse(
                    secret in text,
                    "${control.label} put a value §12 forbids into its refusal: $text",
                )
            }
        }

        assertTrue(
            causesSeen > 0,
            "no control produced a refusal carrying a cause, so the chain half of this sweep is " +
                "checking nothing",
        )
    }

    /** And the same rule on the success path, where the value lives longest. */
    @JsName("a_sealed_message_names_nothing_in_its_string_form")
    @Test
    fun `a sealed message names nothing in its string form`() {
        val message = sealWith()

        val text = "$message ${message.toRecipient} ${message.toSelf}"

        for (secret in forbiddenStrings()) {
            assertFalse(secret in text, "SealedMessage.toString carried a forbidden value: $text")
        }
        assertFalse(
            message.toRecipient.json in text,
            "and it must not print the wrap itself either",
        )
    }

    // -----------------------------------------------------------------------------------------
    // The controls that carry an assertion of their own.
    // -----------------------------------------------------------------------------------------

    /**
     * §7.2 mirrored onto the write path, and the "before" in "before anything is encrypted or
     * signed" made into an assertion.
     */
    @JsName("a_rumor_claiming_another_key_is_refused_before_any_seam_is_asked_to_encrypt")
    @Test
    fun `a rumor claiming another key is refused before any seam is asked to encrypt`() {
        val sender = EnvelopeFixtures.senderSigner()
        val ephemeral = Nip44PayloadEphemeralSigners()

        val refused = refusalOf(
            Control("a rumor claiming a stranger's key", EnvelopeRejection.RUMOR_NOT_SENDERS) {
                sealWith(
                    rumor = EnvelopeFixtures.rumor(pubkey = EnvelopeFixtures.strangerKey()),
                    sender = sender,
                    ephemeral = ephemeral,
                )
            },
        )

        assertEquals(EnvelopeRejection.RUMOR_NOT_SENDERS, refused.reason)
        assertEquals(0, sender.encryptCalls, "§7.2's check costs no encryption")
        assertEquals(0, sender.signEventCalls, "and no signature")
        assertEquals(0, ephemeral.handedOut.size, "and no throwaway keypair")
    }

    /** The clock and the randomness source are consulted before anything is encrypted. */
    @JsName("an_unusable_clock_or_source_is_refused_before_any_seam_is_asked_to_encrypt")
    @Test
    fun `an unusable clock or source is refused before any seam is asked to encrypt`() {
        val cases = listOf<Pair<EnvelopeRejection, (Nip44PayloadSigner) -> Unit>>(
            EnvelopeRejection.CLOCK_UNAVAILABLE to { s -> sealWith(sender = s, clock = NenyaClock.FAIL_CLOSED) },
            EnvelopeRejection.CLOCK_READING_BEFORE_EPOCH to { s -> sealWith(sender = s, clock = FakeClock(-1L)) },
            EnvelopeRejection.RANDOMNESS_UNAVAILABLE to { s ->
                sealWith(sender = s, randomness = Randomness.FAIL_CLOSED)
            },
        )

        for ((reason, body) in cases) {
            val sender = EnvelopeFixtures.senderSigner()
            val refused = refusalOf(Control(reason.name, reason) { body(sender) })
            assertEquals(reason, refused.reason)
            assertEquals(0, sender.encryptCalls, "$reason must cost no encryption")
        }
    }

    /**
     * §7.1 step 5's "no fallback to the true time", proved by the source that can never land in
     * range: it terminates, and it terminates as a refusal.
     */
    @JsName("a_source_that_never_lands_in_range_terminates_as_a_refusal")
    @Test
    fun `a source that never lands in range terminates as a refusal`() {
        val sender = EnvelopeFixtures.senderSigner()

        val refused = refusalOf(
            Control("all ones", EnvelopeRejection.RANDOMNESS_UNUSABLE) {
                sealWith(sender = sender, randomness = AllOnesRandomness())
            },
        )

        assertEquals(EnvelopeRejection.RANDOMNESS_UNUSABLE, refused.reason)
        assertEquals(0, sender.encryptCalls, "and no seal was built from the true time instead")
    }

    /** §7.1 step 1's ceiling, at the byte, from both sides. */
    @JsName("a_rumor_at_the_ceiling_seals_and_one_byte_over_it_does_not")
    @Test
    fun `a rumor at the ceiling seals, and one byte over it does not`() {
        val atTheCeiling = EnvelopeFixtures.rumorOfJsonBytes(EnvelopeLimits.MAX_RUMOR_JSON_BYTES)
        val overIt = EnvelopeFixtures.rumorOfJsonBytes(EnvelopeLimits.MAX_RUMOR_JSON_BYTES + 1)
        val recipient = EnvelopeFixtures.recipientSigner()

        val sealed = sealWith(rumor = atTheCeiling)
        val refused = refusalOf(
            Control("one byte over", EnvelopeRejection.RUMOR_TOO_LARGE) { sealWith(rumor = overIt) },
        )

        assertEquals(EnvelopeRejection.RUMOR_TOO_LARGE, refused.reason)
        val opened = EnvelopeFixtures.open(sealed.toRecipient.json, recipient)
        assertEquals(
            EventJson.writeUnsigned(atTheCeiling, EventId.of(atTheCeiling)),
            opened.rumorJson,
            "a rumor exactly at §7.1 step 1's bound must still open on the far side",
        )
        assertTrue(
            opened.sealJson.length <= EnvelopeLimits.MAX_NIP44_PLAINTEXT_BYTES,
            "which is what the bound is derived from: the seal is still a NIP-44 plaintext",
        )
    }

    /** The one-shot guard, on its own, because nothing on a correct path can reach it. */
    @JsName("a_throwaway_signer_refuses_a_second_use")
    @Test
    fun `a throwaway signer refuses a second use`() {
        val guarded = GiftWrap.OneShotSigner(EnvelopeFixtures.senderSigner())

        guarded.signEvent(SERIALISATION)
        guarded.nip44Encrypt(EnvelopeFixtures.recipientKey(), "once")

        for (second in listOf<() -> Unit>(
            { guarded.signEvent(SERIALISATION) },
            { guarded.nip44Encrypt(EnvelopeFixtures.recipientKey(), "twice") },
            { guarded.nip44Decrypt(EnvelopeFixtures.recipientKey(), "anything") },
        )) {
            val refused = try {
                second()
                fail("a throwaway signer must refuse a second use")
            } catch (thrown: EnvelopeException) {
                thrown
            }
            assertEquals(EnvelopeRejection.THROWAWAY_SIGNER_REUSED, refused.reason)
        }
        assertEquals(
            EnvelopeSide.SEAL,
            EnvelopeRejection.THROWAWAY_SIGNER_REUSED.side,
            "the guard's refusal belongs to the write side like every other one here",
        )
    }

    // -----------------------------------------------------------------------------------------
    // The controls.
    // -----------------------------------------------------------------------------------------

    private class Control(
        val label: String,
        val reason: EnvelopeRejection,
        val run: () -> Unit,
    )

    /**
     * One control per write-side constant, each isolating that one refusal.
     *
     * Where a constant cannot be reached through an honest seam, the control injects a misbehaving
     * fake from `SeamMisbehavingFakes` — each of which misbehaves in exactly one answer, so the
     * control proves the check it names rather than whichever check happens to run first.
     */
    private fun controls(): List<Control> = listOf(
        Control("a signer with no public key", EnvelopeRejection.SIGNER_PUBLIC_KEY_UNAVAILABLE) {
            sealWith(sender = Signer.FAIL_CLOSED)
        },
        Control("a 63-character public key", EnvelopeRejection.SIGNER_PUBLIC_KEY_MALFORMED) {
            sealWith(sender = ShortPublicKeySigner(FakeKey(EnvelopeFixtures.SENDER_STREAM)))
        },
        Control("a 63-character recipient key", EnvelopeRejection.RECIPIENT_KEY_MALFORMED) {
            sealWith(recipient = EnvelopeFixtures.recipientKey().dropLast(1))
        },
        Control("the recipient is the sender", EnvelopeRejection.RECIPIENT_IS_SENDER) {
            sealWith(recipient = EnvelopeFixtures.senderKey())
        },
        Control("a rumor claiming a stranger's key", EnvelopeRejection.RUMOR_NOT_SENDERS) {
            sealWith(rumor = EnvelopeFixtures.rumor(pubkey = EnvelopeFixtures.strangerKey()))
        },
        Control("a rumor of a kind NIP-01 has no room for", EnvelopeRejection.RUMOR_MALFORMED) {
            sealWith(
                rumor = WireEvent(
                    EnvelopeFixtures.senderKey(),
                    EnvelopeFixtures.NOW,
                    EventJson.MAX_KIND + 1,
                    emptyList(),
                    "",
                ),
            )
        },
        Control("a rumor one byte over §7.1's ceiling", EnvelopeRejection.RUMOR_TOO_LARGE) {
            sealWith(rumor = EnvelopeFixtures.rumorOfJsonBytes(EnvelopeLimits.MAX_RUMOR_JSON_BYTES + 1))
        },
        Control("a relay hint one byte over §4.3's bound", EnvelopeRejection.RELAY_HINT_TOO_LONG) {
            sealWith(hint = "r".repeat(WireLimits.DEFAULT_MAX_TAG_VALUE_BYTES + 1))
        },
        Control("a one-character relay hint that is half a surrogate pair", EnvelopeRejection.RELAY_HINT_MALFORMED) {
            sealWith(hint = LONE_HIGH_SURROGATE.toString())
        },
        Control("no clock", EnvelopeRejection.CLOCK_UNAVAILABLE) {
            sealWith(clock = NenyaClock.FAIL_CLOSED)
        },
        Control("a clock before 1970", EnvelopeRejection.CLOCK_READING_BEFORE_EPOCH) {
            sealWith(clock = FakeClock(-1L))
        },
        Control("no randomness", EnvelopeRejection.RANDOMNESS_UNAVAILABLE) {
            sealWith(randomness = Randomness.FAIL_CLOSED)
        },
        Control("a source that never lands in range", EnvelopeRejection.RANDOMNESS_UNUSABLE) {
            sealWith(randomness = AllOnesRandomness())
        },
        Control("a source that answers the wrong length", EnvelopeRejection.RANDOMNESS_UNUSABLE) {
            sealWith(randomness = WrongLengthRandomness(size = 3))
        },
        Control("a signer that will not encrypt", EnvelopeRejection.ENCRYPTION_UNAVAILABLE) {
            sealWith(sender = NoEncryptionSigner(FakeKey(EnvelopeFixtures.SENDER_STREAM)))
        },
        Control("a signer returning an empty payload", EnvelopeRejection.CIPHERTEXT_MALFORMED) {
            sealWith(sender = EmptyCiphertextSigner(FakeKey(EnvelopeFixtures.SENDER_STREAM)))
        },
        // The other half of the same constant, and the one the check's KDoc is written about: a
        // payload carrying half a surrogate pair has no UTF-8 encoding, so without this check it
        // would reach the event writer as WireRejection.UNPAIRED_SURROGATE rather than as a named
        // envelope refusal.
        Control("a signer returning half a surrogate pair", EnvelopeRejection.CIPHERTEXT_MALFORMED) {
            sealWith(sender = NonAsciiCiphertextSigner(FakeKey(EnvelopeFixtures.SENDER_STREAM)))
        },
        Control("a signer that will not sign", EnvelopeRejection.SIGNATURE_UNAVAILABLE) {
            sealWith(sender = NoSignatureSigner(FakeKey(EnvelopeFixtures.SENDER_STREAM)))
        },
        Control("a 127-character signature", EnvelopeRejection.SIGNATURE_MALFORMED) {
            sealWith(sender = ShortSignatureSigner(FakeKey(EnvelopeFixtures.SENDER_STREAM)))
        },
        Control("a one-time signer that lies about its key", EnvelopeRejection.SIGNATURE_DOES_NOT_VERIFY) {
            sealWith(ephemeral = LyingNip44PayloadEphemeralSigners(), secp = FakeSecp256k1Ops())
        },
        Control("a signer returning more than NIP-44 can", EnvelopeRejection.SEAL_TOO_LARGE_TO_EMIT) {
            sealWith(sender = OversizedCiphertextSigner(FakeKey(EnvelopeFixtures.SENDER_STREAM)))
        },
        Control("a throwaway signer returning more than NIP-44 can", EnvelopeRejection.WRAP_TOO_LARGE_TO_EMIT) {
            sealWith(
                ephemeral = ScriptedEphemeralSigners(
                    listOf(OversizedCiphertextSigner(FakeKey(FakeKeyRanges.EPHEMERAL))),
                ),
            )
        },
        Control("no one-time signer", EnvelopeRejection.EPHEMERAL_SIGNER_UNAVAILABLE) {
            sealWith(ephemeral = EphemeralSigners.FAIL_CLOSED)
        },
        Control("a throwaway key that is the sender's", EnvelopeRejection.EPHEMERAL_KEY_IS_IDENTITY) {
            sealWith(ephemeral = FixedEphemeralSigners.identity(EnvelopeFixtures.senderSigner()))
        },
        Control("a throwaway key that is the recipient's", EnvelopeRejection.EPHEMERAL_KEY_IS_IDENTITY) {
            sealWith(ephemeral = FixedEphemeralSigners.recipientKey(EnvelopeFixtures.recipientSigner()))
        },
        Control("a one-time signer that is not one", EnvelopeRejection.EPHEMERAL_KEY_REUSED) {
            sealWith(
                ephemeral = FixedEphemeralSigners.reusing(
                    Nip44PayloadSigner(FakeKey(FakeKeyRanges.REUSED)),
                ),
            )
        },
        Control("a throwaway signer used twice", EnvelopeRejection.THROWAWAY_SIGNER_REUSED) {
            val guarded = GiftWrap.OneShotSigner(EnvelopeFixtures.senderSigner())
            guarded.signEvent(SERIALISATION)
            guarded.signEvent(SERIALISATION)
        },
    )

    // -----------------------------------------------------------------------------------------
    // Helpers.
    // -----------------------------------------------------------------------------------------

    /**
     * Every honest default in one place, so a control varies exactly the one thing it is about and
     * a reader can see which.
     */
    private fun sealWith(
        rumor: WireEvent = EnvelopeFixtures.rumor(),
        recipient: String = EnvelopeFixtures.recipientKey(),
        sender: Signer = EnvelopeFixtures.senderSigner(),
        ephemeral: EphemeralSigners = Nip44PayloadEphemeralSigners(),
        clock: NenyaClock = FakeClock(EnvelopeFixtures.NOW),
        randomness: Randomness = RecordingRandomness(),
        secp: Secp256k1Ops = Secp256k1Ops.FAIL_CLOSED,
        hint: String? = null,
        limits: WireLimits = WireLimits.DEFAULT,
    ): SealedMessage = GiftWrap.seal(
        rumor = rumor,
        recipientPubkey = recipient,
        sender = sender,
        ephemeral = ephemeral,
        clock = clock,
        randomness = randomness,
        secp = secp,
        recipientRelayHint = hint,
        limits = limits,
    )

    private fun refusalOf(control: Control): EnvelopeException = try {
        control.run()
        fail("${control.label} produced a message; §7.1 requires it be refused as ${control.reason}")
    } catch (refused: EnvelopeException) {
        refused
    }

    /**
     * The values §12 and §7.2 say may never appear in a message: both parties' keys, a stranger's,
     * every throwaway key these fixtures can hand out, the rumor's private `content` and an order
     * id.
     */
    private fun forbiddenStrings(): List<String> = listOf(
        EnvelopeFixtures.senderKey(),
        EnvelopeFixtures.recipientKey(),
        EnvelopeFixtures.strangerKey(),
        EnvelopeFixtures.RUMOR_CONTENT,
        EnvelopeFixtures.ORDER_ID_HEX,
        EnvelopeFixtures.ITEM_COORDINATE,
        FakeKey(FakeKeyRanges.EPHEMERAL).hex,
        FakeKey(FakeKeyRanges.EPHEMERAL + 1).hex,
        FakeKey(FakeKeyRanges.REUSED).hex,
        FakeKey(FakeKeyRanges.LYING).hex,
        FakeKey(FakeKeyRanges.LYING + 1).hex,
    )

    private companion object {

        /** Any canonical serialisation at all; the guard under test never reads it. */
        const val SERIALISATION: String = "[0,\"\",0,13,[],\"\"]"

        /** U+D800 as a code point, so it never appears as an invisible character in this file. */
        val LONE_HIGH_SURROGATE: Char = 0xD800.toChar()
    }
}
