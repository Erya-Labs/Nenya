package dev.eryalabs.nenya.seam

import dev.eryalabs.nenya.conformance.Capabilities
import dev.eryalabs.nenya.platform.runtimeSimpleName
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one-time signer seam's fail-closed default, and the self-tests for the fakes every later
 * envelope task will build on.
 *
 * ### Why the fakes are tested at all
 *
 * T32 and T33 will assert things like "a wrap this reader was not addressed to does not open" and
 * "a `pubkey` that did not sign the wrap is refused", and every one of those assertions is only as
 * good as the fake underneath it. A fake whose conversation key were asymmetric would make "the
 * recipient can decrypt the seal" fail for a reason that has nothing to do with Nenya; one whose
 * verifier accepted any message length would make §4.1's 32-byte rule untestable. So the
 * properties those tasks lean on are pinned here, once, where a failure names the fake rather than
 * the code under test.
 *
 * The limit of what any of this proves is stated in [FakeCrypto]'s header, and it matters: these
 * fakes model confidentiality against an adversary holding a **key**, never against one holding
 * the conversation key. Nothing below forges under a conversation key directly.
 */
class EphemeralSignerTest {

    private companion object {

        /** A §4.1 canonical serialisation is opaque to these fakes; any distinctive text will do. */
        const val SERIALISATION: String = "[0,\"a\",1,1059,[],\"the fakes never parse this\"]"

        /**
         * The fakes the exercise covers, by name. See [every fake in this file is exercised].
         *
         * The first eight are `SeamCryptoFakes.kt`'s; the rest are the misbehaving ones beside
         * them in `SeamMisbehavingFakes.kt`, which §7.1's write path needs and which the same
         * floor covers — see [exerciseEveryCryptoFake] for why they are one set and not two.
         */
        val EXERCISED_BY_NAME: Set<String> = setOf(
            "FakeKey",
            "FakeCryptoSigner",
            "LyingCryptoSigner",
            "FakeSecp256k1Ops",
            "FakeEphemeralSigners",
            "FixedEphemeralSigners",
            "LyingEphemeralSigners",
            "AllOnesRandomness",
            "Nip44PayloadSigner",
            "Nip44PayloadEphemeralSigners",
            "ScriptedEphemeralSigners",
            "ShortPublicKeySigner",
            "NoEncryptionSigner",
            "EmptyCiphertextSigner",
            "NonAsciiCiphertextSigner",
            "OversizedCiphertextSigner",
            "OversizedPlaintextSigner",
            "DecryptUnavailableSigner",
            "SealDecryptUnavailableSigner",
            "MacFailureAsUnavailableSigner",
            "NoSignatureSigner",
            "ShortSignatureSigner",
            "LyingNip44PayloadSigner",
            "LyingNip44PayloadEphemeralSigners",
        )
    }

    // -----------------------------------------------------------------------------------------
    // The seam itself.
    // -----------------------------------------------------------------------------------------

    @JsName("the_fail_closed_one_time_signer_mints_nothing_and_names_the_capability")
    @Test
    fun `the fail-closed one-time signer mints nothing, and names the capability`() {
        val answer = EphemeralSigners.FAIL_CLOSED.fresh().unavailable()

        assertEquals(SeamCapability.EPHEMERAL_SIGNER, answer.capability)
        assertTrue(
            answer.detail.contains(SeamCapability.EPHEMERAL_SIGNER.what),
            "§17 requires the statement be actionable: the detail must say which capability was " +
                "not attempted. Got '${answer.detail}'",
        )
    }

    /**
     * It takes no arguments, so there is nothing for it to echo — and that is worth an assertion
     * rather than a shrug, because `fresh()` acquiring a parameter later is exactly how a key or a
     * counterparty pubkey would arrive at a seam whose diagnostic is designed to be logged
     * (§12 item 11).
     */
    @JsName("the_fail_closed_one_time_signer_s_diagnostic_carries_no_key_at_all")
    @Test
    fun `the fail-closed one-time signer's diagnostic carries no key at all`() {
        val answer = EphemeralSigners.FAIL_CLOSED.fresh().unavailable()
        val key = FakeKey(stream = 41L)

        for (rendering in listOf(key.hex, key.bytes.contentToString())) {
            assertTrue(rendering !in answer.detail, "the detail echoed a key: ${answer.detail}")
            assertTrue(rendering !in answer.toString(), "the toString echoed a key")
        }
        assertEquals("EphemeralSigners.FAIL_CLOSED", EphemeralSigners.FAIL_CLOSED.toString())
    }

    @JsName("the_capability_surface_reports_the_one_time_signer_as_not_performed_here")
    @Test
    fun `the capability surface reports the one-time signer as not performed here`() {
        assertTrue(
            SeamCapability.EPHEMERAL_SIGNER in Capabilities.NOT_PERFORMED_HERE,
            "this library mints no keypair itself; §17 requires it publish that rather than let a " +
                "client assume the seam is live",
        )
        assertTrue(
            "SeamCapability.EPHEMERAL_SIGNER" in Capabilities.report(),
            "the report is the machine-readable form of that surface",
        )
    }

    // -----------------------------------------------------------------------------------------
    // The signer fake.
    // -----------------------------------------------------------------------------------------

    /**
     * The contract [Signer.signEvent] states, made executable: the signature is over the SHA-256
     * of the serialisation, not over the serialisation.
     *
     * This is the assertion the reworded KDoc exists for. Against a fake that signed the
     * serialisation's own bytes, the verifier below answers INVALID.
     */
    @JsName("a_signature_is_over_the_sha_256_of_the_serialisation_and_verifies_under_the_signer_s_key")
    @Test
    fun `a signature is over the SHA-256 of the serialisation, and verifies under the signer's key`() {
        val signer = FakeCryptoSigner(FakeKey(stream = 1L))
        val verifier = FakeSecp256k1Ops()

        val signature = FakeCrypto.hexToBytes(signer.signEvent(SERIALISATION).provided())!!
        val eventId = FakeCrypto.eventId(SERIALISATION)

        assertEquals(32, eventId.size, "§4.1's event id is the 32-byte SHA-256 of the serialisation")
        assertEquals(64, signature.size)
        assertEquals(
            SignatureVerdict.VALID,
            verifier.verifySchnorr(signer.key.bytes, eventId, signature).provided(),
        )
        assertEquals(1, signer.signEventCalls, "the counter is what proves one call, not several")
        assertEquals(1, verifier.calls)
    }

    @JsName("the_verifier_refuses_a_flipped_signature_bit_a_flipped_message_bit_and_the_wrong_key")
    @Test
    fun `the verifier refuses a flipped signature bit, a flipped message bit and the wrong key`() {
        val signer = FakeCryptoSigner(FakeKey(stream = 1L))
        val stranger = FakeKey(stream = 2L)
        val verifier = FakeSecp256k1Ops()
        val eventId = FakeCrypto.eventId(SERIALISATION)
        val signature = FakeCrypto.hexToBytes(signer.signEvent(SERIALISATION).provided())!!

        val flippedSignature = signature.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val flippedMessage = eventId.copyOf().also { it[31] = (it[31].toInt() xor 1).toByte() }

        assertEquals(
            SignatureVerdict.INVALID,
            verifier.verifySchnorr(signer.key.bytes, eventId, flippedSignature).provided(),
            "one bit of the signature",
        )
        assertEquals(
            SignatureVerdict.INVALID,
            verifier.verifySchnorr(signer.key.bytes, flippedMessage, signature).provided(),
            "one bit of the message: a signature does not carry over to a different event",
        )
        assertEquals(
            SignatureVerdict.INVALID,
            verifier.verifySchnorr(stranger.bytes, eventId, signature).provided(),
            "the wrong key, which is the whole property §7.1 step 4's refusals rest on",
        )
    }

    /**
     * The message-length control. §4.1 says a nostr signature is over a 32-byte event id and
     * nothing else, and [Secp256k1Ops.verifySchnorr]'s reworded KDoc narrows the contract to
     * exactly that — so a fake that accepted any length would leave the narrowing untested.
     */
    @JsName("the_verifier_refuses_a_33_byte_message_because_a_nostr_signature_is_over_an_event_id")
    @Test
    fun `the verifier refuses a 33-byte message, because a nostr signature is over an event id`() {
        val signer = FakeCryptoSigner(FakeKey(stream = 1L))
        val verifier = FakeSecp256k1Ops()
        val signature = FakeCrypto.hexToBytes(signer.signEvent(SERIALISATION).provided())!!
        val tooLong = FakeCrypto.eventId(SERIALISATION) + ByteArray(1)

        assertEquals(33, tooLong.size)
        assertEquals(
            SignatureVerdict.INVALID,
            verifier.verifySchnorr(signer.key.bytes, tooLong, signature).provided(),
        )
    }

    /**
     * Every other operand length the guard covers, so a mutation narrowing it to `message.size <=
     * 32` — or dropping the key or signature length checks entirely — does not survive.
     *
     * Asserting only the 33-byte case leaves the short side, and both other operands, unmeasured.
     */
    @JsName("the_verifier_refuses_every_operand_of_the_wrong_length_short_as_well_as_long")
    @Test
    fun `the verifier refuses every operand of the wrong length, short as well as long`() {
        val signer = FakeCryptoSigner(FakeKey(stream = 1L))
        val verifier = FakeSecp256k1Ops()
        val eventId = FakeCrypto.eventId(SERIALISATION)
        val signature = FakeCrypto.hexToBytes(signer.signEvent(SERIALISATION).provided())!!

        val wrong = listOf(
            Triple(signer.key.bytes, eventId.copyOf(31), signature) to "a 31-byte message",
            Triple(signer.key.bytes, ByteArray(0), signature) to "an empty message",
            Triple(signer.key.bytes.copyOf(31), eventId, signature) to "a 31-byte public key",
            Triple(signer.key.bytes + ByteArray(1), eventId, signature) to "a 33-byte public key",
            Triple(signer.key.bytes, eventId, signature.copyOf(63)) to "a 63-byte signature",
            Triple(signer.key.bytes, eventId, signature + ByteArray(1)) to "a 65-byte signature",
        )

        for ((operands, what) in wrong) {
            val (key, message, sig) = operands
            assertEquals(
                SignatureVerdict.INVALID,
                verifier.verifySchnorr(key, message, sig).provided(),
                "$what must not verify",
            )
        }
        // The floor: the untouched operands do verify, so the six refusals above are the lengths
        // and not a verifier that refuses everything.
        assertEquals(
            SignatureVerdict.VALID,
            verifier.verifySchnorr(signer.key.bytes, eventId, signature).provided(),
        )
    }

    // -----------------------------------------------------------------------------------------
    // The NIP-44 fake.
    // -----------------------------------------------------------------------------------------

    /**
     * The one property of ECDH the envelope code depends on: both parties derive the same
     * conversation key from their own key and the other's, in either order.
     *
     * The mutation that proves this test is doing something: drop the sort in
     * [FakeCrypto.conversationKey] and this goes red, and with it every round trip below.
     */
    @JsName("the_conversation_key_is_symmetric_so_either_party_derives_it")
    @Test
    fun `the conversation key is symmetric, so either party derives it`() {
        val sender = FakeKey(stream = 10L)
        val recipient = FakeKey(stream = 11L)

        assertContentEquals(
            FakeCrypto.conversationKey(sender.hex, recipient.hex),
            FakeCrypto.conversationKey(recipient.hex, sender.hex),
        )
        assertTrue(
            !FakeCrypto.conversationKey(sender.hex, recipient.hex)
                .contentEquals(FakeCrypto.conversationKey(sender.hex, FakeKey(stream = 12L).hex)),
            "and a third party's key gives a different one, or symmetry would be the only property " +
                "it had",
        )
    }

    @JsName("a_seal_encrypted_to_the_recipient_is_decrypted_by_the_recipient")
    @Test
    fun `a seal encrypted to the recipient is decrypted by the recipient`() {
        val sender = FakeCryptoSigner(FakeKey(stream = 10L))
        val recipient = FakeCryptoSigner(FakeKey(stream = 11L))
        val plaintext = "the rumor's JSON, which these fakes never parse"

        val payload = sender.nip44Encrypt(recipient.key.hex, plaintext).provided()

        assertNotEquals(plaintext, payload, "an identity 'encryption' would pass every test below")
        assertEquals(plaintext, recipient.nip44Decrypt(sender.key.hex, payload).decrypted())
        assertEquals(1, sender.encryptCalls)
        assertEquals(1, recipient.decryptCalls)
    }

    /**
     * **Declared expectation change, T35, STOP RULE 1.** This test expected
     * [SeamAnswer.Unavailable] until T35 and now expects [Nip44Decryption.Refused]: the stranger
     * holds a key and performed the decryption, so §17 requires the answer say the check *ran* and
     * failed rather than that it was never attempted. Nothing this fake accepted before is accepted
     * now — only the reporting is exact.
     */
    @JsName("decrypting_with_the_wrong_counterparty_is_refused_as_a_check_that_ran")
    @Test
    fun `decrypting with the wrong counterparty is refused as a check that ran`() {
        val sender = FakeCryptoSigner(FakeKey(stream = 10L))
        val recipient = FakeCryptoSigner(FakeKey(stream = 11L))
        val stranger = FakeCryptoSigner(FakeKey(stream = 12L))

        val payload = sender.nip44Encrypt(recipient.key.hex, "for the recipient alone").provided()

        // The stranger holds its own fake, as FakeCrypto's header requires of an adversary.
        val answer = stranger.nip44Decrypt(sender.key.hex, payload)
        answer.refused()

        assertEquals(1, stranger.decryptCalls, "the refusal must be one the signer actually performed")
        val logged = "$answer ${answer.refused()}"
        assertTrue(
            payload !in logged && "for the recipient alone" !in logged,
            "the refusal must carry neither the payload nor the plaintext into a log (§12 item 11)",
        )
    }

    /**
     * The other half of [decrypting with the wrong counterparty is refused as a check that ran]: the
     * key is right and the **bytes** are wrong.
     *
     * **Declared expectation change, T35, STOP RULE 1** — [SeamAnswer.Unavailable] until T35, now
     * [Nip44Decryption.Refused], for the reason given there.
     */
    @JsName("flipping_one_ciphertext_bit_is_refused_rather_than_decoding_rubbish")
    @Test
    fun `flipping one ciphertext bit is refused rather than decoding rubbish`() {
        val sender = FakeCryptoSigner(FakeKey(stream = 10L))
        val recipient = FakeCryptoSigner(FakeKey(stream = 11L))
        val payload = sender.nip44Encrypt(recipient.key.hex, "authenticated, or refused").provided()

        // The last hex character, which is inside the ciphertext rather than the MAC.
        val disturbed = payload.dropLast(1) + if (payload.last() == '0') '1' else '0'

        assertNotEquals(payload, disturbed)
        recipient.nip44Decrypt(sender.key.hex, disturbed).refused()
    }

    /**
     * The other half of the payload. The test above disturbs the ciphertext; this disturbs the
     * **MAC**, which is the region a decryption that compared only part of it would ignore.
     *
     * **Declared expectation change, T35, STOP RULE 1** — same case, same reason as the two above.
     */
    @JsName("flipping_one_mac_bit_is_refused_too")
    @Test
    fun `flipping one MAC bit is refused too`() {
        val sender = FakeCryptoSigner(FakeKey(stream = 10L))
        val recipient = FakeCryptoSigner(FakeKey(stream = 11L))
        val payload = sender.nip44Encrypt(recipient.key.hex, "authenticated, or refused").provided()

        // Every hex character of the MAC in turn, so a comparison covering only some of its bytes
        // is caught wherever the gap is.
        for (index in 0 until FakeCrypto.MAC_LENGTH * 2) {
            val disturbed = payload.replaceRange(
                index,
                index + 1,
                if (payload[index] == '0') "1" else "0",
            )
            assertNotEquals(payload, disturbed)
            assertEquals(
                Nip44Decryption.Refused,
                recipient.nip44Decrypt(sender.key.hex, disturbed).refused(),
                "a payload whose MAC differs at hex character $index must not decrypt",
            )
        }
        // The floor: the undisturbed payload still opens, so the loop is measuring the MAC.
        assertEquals(
            "authenticated, or refused",
            recipient.nip44Decrypt(sender.key.hex, payload).decrypted(),
        )
    }

    /**
     * **Declared expectation change, T35, STOP RULE 1** — [SeamAnswer.Unavailable] until T35, now
     * [Nip44Decryption.Refused]. Same case as the three above and the same single mechanism:
     * [FakeCrypto.decrypt] answers `null` for a payload that is malformed **or** whose MAC does not
     * match, and the signer turns every one of those into a decryption it performed and refused.
     * A payload that is not hex is one this signer looked at and rejected, so reporting it as *not
     * attempted* would be the same §17 over-claim in the same direction.
     */
    @JsName("a_payload_that_is_not_hex_or_is_shorter_than_the_mac_is_refused_too")
    @Test
    fun `a payload that is not hex, or is shorter than the MAC, is refused too`() {
        val sender = FakeCryptoSigner(FakeKey(stream = 10L))
        val recipient = FakeCryptoSigner(FakeKey(stream = 11L))
        val conversationKey = FakeCrypto.conversationKey(sender.key.hex, recipient.key.hex)

        assertNull(FakeCrypto.decrypt(conversationKey, "not hex at all"))
        assertNull(FakeCrypto.decrypt(conversationKey, "abc"), "an odd number of hex digits")
        assertNull(FakeCrypto.decrypt(conversationKey, "00"), "shorter than the MAC")
        recipient.nip44Decrypt(sender.key.hex, "not hex at all").refused()
        assertEquals(1, recipient.decryptCalls, "and it was a call the signer actually made")
    }

    /**
     * The fake that is **not** the one above: no NIP-44 decryption capability at all.
     *
     * T35's split has two sides and a suite that only ever saw the refusing one would not notice a
     * seam that had stopped being able to say "not attempted" — which is the answer §17's capability
     * surface is built on. [DecryptUnavailableSigner] and [SealDecryptUnavailableSigner] are the two
     * fakes that still produce it, and each is what makes one of `GiftWrap.open`'s
     * `COULD_NOT_DECRYPT_*` refusals reachable.
     */
    @JsName("the_two_decryption_shy_signers_report_not_attempted_rather_than_refused")
    @Test
    fun `the two decryption-shy signers report not attempted rather than refused`() {
        val sender = Nip44PayloadSigner(FakeKey(stream = 10L))
        val reader = FakeKey(stream = 11L)
        val payload = sender.nip44Encrypt(reader.hex, "an honest payload").provided()

        assertEquals(
            SeamCapability.NIP44_DECRYPTION,
            DecryptUnavailableSigner(reader).nip44Decrypt(sender.key.hex, payload).unavailable().capability,
            "it holds the key and could decrypt this payload; it reports that it did not try",
        )

        val selective = SealDecryptUnavailableSigner(reader, wrapPublicKeyHex = sender.key.hex)
        assertEquals("an honest payload", selective.nip44Decrypt(sender.key.hex, payload).decrypted())
        assertEquals(
            SeamCapability.NIP44_DECRYPTION,
            selective.nip44Decrypt(FakeKey(stream = 12L).hex, payload).unavailable().capability,
            "§7.1 step 7's counterparty is the seal's key, and this signer has no conversation with it",
        )
        assertEquals(1, selective.honestDecryptions, "exactly one decryption was performed")
    }

    // -----------------------------------------------------------------------------------------
    // The one-time signer fakes.
    // -----------------------------------------------------------------------------------------

    /**
     * A thousand calls, a thousand distinct keys — and the record to prove it.
     *
     * Not a claim about the real seam, which §3 says may never be believed on freshness. It is a
     * claim about this fake, so that an envelope test asserting "the two copies got different
     * keys" is measuring the code under test rather than a source that happened to repeat.
     */
    @JsName("a_thousand_fresh_calls_give_a_thousand_distinct_public_keys")
    @Test
    fun `a thousand fresh calls give a thousand distinct public keys`() {
        val signers = FakeEphemeralSigners()

        val keys = (1..1_000).map { (signers.fresh().provided() as FakeCryptoSigner).key.hex }

        assertEquals(1_000, keys.size)
        assertEquals(1_000, keys.toSet().size, "a reused key on any call links the two wraps it signs")
        assertEquals(keys, signers.keysHandedOut, "and the source recorded every one, in order")
        assertTrue(keys.all { it.length == 64 }, "§4.3: an x-only public key is 64 hex characters")
    }

    @JsName("the_reusing_identity_and_recipient_key_variants_all_hand_out_one_fixed_key")
    @Test
    fun `the reusing, identity and recipient-key variants all hand out one fixed key`() {
        val user = FakeCryptoSigner(FakeKey(stream = 20L))
        val recipient = FakeCryptoSigner(FakeKey(stream = 21L))

        val reusing = FixedEphemeralSigners.reusing()
        val identity = FixedEphemeralSigners.identity(user)
        val recipientKey = FixedEphemeralSigners.recipientKey(recipient)

        for (source in listOf(reusing, identity, recipientKey)) {
            val first = source.fresh().provided()
            val second = source.fresh().provided()
            assertEquals(2, source.calls)
            assertEquals(
                keyOf(first),
                keyOf(second),
                "this is the misbehaviour §7.1 step 4 forbids, and the envelope tests need it to " +
                    "actually misbehave",
            )
        }
        assertEquals(user.key.hex, keyOf(identity.fresh().provided()), "identity hands out the sender's key")
        assertEquals(recipient.key.hex, keyOf(recipientKey.fresh().provided()))
        assertNotEquals(user.key.hex, keyOf(reusing.fresh().provided()), "reusing is a throwaway key, reused")
    }

    /**
     * The lying variant's negative control: the pubkey it reports does not verify against the
     * signature it produced.
     *
     * This is the property §3's "no claim about *what* was signed" is written for. Every value the
     * signer returns is well-formed and its signature is genuine — by a key it did not name.
     */
    @JsName("the_lying_signer_s_reported_pubkey_fails_against_its_own_signature")
    @Test
    fun `the lying signer's reported pubkey fails against its own signature`() {
        val signers = LyingEphemeralSigners()
        val verifier = FakeSecp256k1Ops()

        val signer = signers.fresh().provided()
        val reportedKey = FakeCrypto.hexToBytes(signer.publicKey().provided())!!
        val signature = FakeCrypto.hexToBytes(signer.signEvent(SERIALISATION).provided())!!
        val eventId = FakeCrypto.eventId(SERIALISATION)

        assertEquals(
            SignatureVerdict.INVALID,
            verifier.verifySchnorr(reportedKey, eventId, signature).provided(),
            "a wrap whose `pubkey` is not the key that signed it must not verify",
        )
        // And the floor under it: the key it *actually* signed with does verify, so the INVALID
        // above is the mismatch and not a fake that refuses everything.
        val honest = FakeCryptoSigner(signers.handedOut.single().key)
        assertEquals(
            SignatureVerdict.VALID,
            verifier.verifySchnorr(
                honest.key.bytes,
                eventId,
                FakeCrypto.hexToBytes(honest.signEvent(SERIALISATION).provided())!!,
            ).provided(),
        )
    }

    /**
     * The three sources of throwaway keys hand out disjoint keys, across a run far longer than any
     * envelope task will make.
     *
     * [FakeKey] is a pure function of its stream, so overlapping ranges would make "these two wraps
     * used different keys" fail for a reason that has nothing to do with the code under test. T32's
     * two-copy rule needs two keys per message, so 4 000 calls is well past what it will draw.
     */
    @JsName("the_three_sources_of_throwaway_keys_never_hand_out_the_same_key")
    @Test
    fun `the three sources of throwaway keys never hand out the same key`() {
        val fresh = FakeEphemeralSigners()
        val lying = LyingEphemeralSigners()
        val reused = FixedEphemeralSigners.reusing()

        val keys = mutableListOf<String>()
        repeat(4_000) { keys += keyOf(fresh.fresh().provided()) }
        repeat(4_000) { keys += keyOf(lying.fresh().provided()) }
        keys += keyOf(reused.fresh().provided())

        assertEquals(8_001, keys.size)
        assertEquals(
            keys.size,
            keys.toSet().size,
            "two sources handed out the same key, so a test asserting two wraps differ would fail " +
                "for a reason that is not about Nenya at all",
        )
    }

    @JsName("the_all_ones_randomness_source_returns_the_count_it_was_asked_for_and_only_0xff")
    @Test
    fun `the all-ones randomness source returns the count it was asked for, and only 0xFF`() {
        val randomness = AllOnesRandomness()

        val bytes = randomness.randomBytes(32).provided()

        assertEquals(32, bytes.size)
        assertTrue(bytes.all { it == 0xFF.toByte() }, "the boundary control for §7.1 step 5")
    }

    /**
     * The non-vacuity floor the queue asks for: every fake in `SeamCryptoFakes.kt` is exercised by
     * at least one self-test above, **found by name**.
     *
     * A count would be satisfied by eight tests of one fake. The name set is cross-checked against
     * reflection over the compiled test tree in `SeamFakeCoverageTest` (JVM), which is what stops
     * this list from being eight strings naming nothing, and what turns a ninth fake added without
     * a self-test red.
     */
    @JsName("every_fake_in_this_file_is_exercised")
    @Test
    fun `every fake in this file is exercised`() {
        assertEquals(
            EXERCISED_BY_NAME,
            exerciseEveryCryptoFake(),
            "every fake this file declares must be exercised by a self-test here; a fake nothing " +
                "calls is a fake the envelope tasks would be the first to run",
        )
    }

    /** The reported public key of whatever a one-time signer handed out. */
    private fun keyOf(signer: Signer): String = signer.publicKey().provided()
}

/**
 * Calls every fake in `SeamCryptoFakes.kt` **and** in `SeamMisbehavingFakes.kt` at least once, and
 * returns the names of the objects it actually called.
 *
 * The names come from [runtimeSimpleName] over the **instances**, never from a list written out
 * beside them: a hand-written return value would name whatever its author last remembered, and
 * this returns what the function just exercised. Shared by the common floor above and by the
 * reflective one in `SeamFakeCoverageTest`, so the two cannot drift — the JVM side asserts every
 * name here is a real class in the compiled test tree, and that every `EphemeralSigners`
 * implementation in that tree is among them.
 *
 * The misbehaving fakes beside T30's are folded in through [exerciseEveryMisbehavingFake] rather
 * than given a second floor of their own, precisely because that reflective rule reads **one**
 * function: a second set nobody unioned in would leave every fake in the second file outside the
 * sweep, which is the shape of coverage claim this floor exists to refuse.
 */
internal fun exerciseEveryCryptoFake(): Set<String> {
    val serialisation = "[0,\"a\",1,1059,[],\"coverage\"]"
    val exercised = mutableSetOf<String>()

    // The name is recorded *after* `use` returns, never at construction. Recording it at
    // construction would let the call be deleted while this floor stayed green, which is the one
    // failure mode a coverage floor must not have.
    fun <T : Any> exercise(fake: T, use: (T) -> Unit): T {
        use(fake)
        exercised += runtimeSimpleName(fake)
        return fake
    }

    val key = exercise(FakeKey(stream = 90L)) { check(it.hex.length == 64) }
    val counterparty = FakeKey(stream = 91L)

    val signer = exercise(FakeCryptoSigner(key)) {
        it.publicKey().provided()
        it.signEvent(serialisation).provided()
    }
    val payload = signer.nip44Encrypt(counterparty.hex, "coverage").provided()
    FakeCryptoSigner(counterparty).nip44Decrypt(key.hex, payload).decrypted()

    exercise(LyingCryptoSigner(key, counterparty)) { it.signEvent(serialisation).provided() }
    exercise(FakeSecp256k1Ops()) {
        it.verifySchnorr(key.bytes, FakeCrypto.eventId(serialisation), ByteArray(64)).provided()
    }
    exercise(FakeEphemeralSigners()) { it.fresh().provided() }
    exercise(FixedEphemeralSigners.reusing()) { it.fresh().provided() }
    exercise(LyingEphemeralSigners()) { it.fresh().provided() }
    exercise(AllOnesRandomness()) { it.randomBytes(1).provided() }

    return exercised + exerciseEveryMisbehavingFake()
}
