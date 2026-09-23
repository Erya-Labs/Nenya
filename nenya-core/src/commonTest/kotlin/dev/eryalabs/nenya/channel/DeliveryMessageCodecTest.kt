package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.delivery.DeliveryCheck
import dev.eryalabs.nenya.order.OrderEvent
import dev.eryalabs.nenya.order.OrderFixtures
import dev.eryalabs.nenya.order.OrderState
import dev.eryalabs.nenya.order.OrderStateException
import dev.eryalabs.nenya.order.OrderStateRejection
import dev.eryalabs.nenya.order.Party
import dev.eryalabs.nenya.order.TransitionRejection
import dev.eryalabs.nenya.seam.OrderId
import dev.eryalabs.nenya.tag.NenyaKind
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §10.1's and §10.3's decoders, held to the two rules T4 could not enforce and to §10.2's encodings.
 *
 * Every fixture is computed — see `DeliveryMessageFixtures` — and the tag-name sets are held equal to
 * the two worked JSON examples parsed out of `spec/NENYA-1.md` at test time, so a §10 revision that
 * adds or drops a tag turns this file red rather than leaving a codec agreeing with itself.
 */
class DeliveryMessageCodecTest {

    private val fixtures = DeliveryMessageFixtures

    // ------------------------------------------------------------------ anchored to the document

    /**
     * §10.1's worked example, by tag **name**, equal to what the commitment decoder reads.
     *
     * Equality in both directions and never containment: containment passes over a codec that reads
     * two of the ten. The one documented extra is `subject`, §7.4's display-only MAY that may appear
     * on any rumor and that §4.3 requires round-trip — named here with the clause it comes from
     * rather than waved through by a pattern.
     */
    @JsName("the_commitment_readable_set_is_section_10_1_s_example")
    @Test
    fun `the commitment readable set is §10 1's example`() {
        val example = Section10.commitmentTagNames.toSet()
        val readable = DeliveryCommitmentMessage.readableTags().toSet()

        assertTrue(example.isNotEmpty(), "§10.1's example parsed to no tag names at all")
        assertEquals(
            example,
            readable - ChannelVocabulary.SUBJECT,
            "the tags §10.1's worked commitment prints must be exactly the tags this codec reads, " +
                "minus §7.4's `${ChannelVocabulary.SUBJECT}`, which §7.4 makes display metadata on " +
                "any rumor and which the example does not print",
        )
        assertTrue(
            DeliveryCommitmentMessage.requiredTags().toSet().all { it in readable },
            "every required tag must also be readable",
        )
    }

    /** §10.3's worked example, by tag name, equal to what the release decoder reads. */
    @JsName("the_release_readable_set_is_section_10_3_s_example")
    @Test
    fun `the release readable set is §10 3's example`() {
        val example = Section10.releaseTagNames.toSet()
        val readable = DeliverableReleaseMessage.readableTags().toSet()

        assertTrue(example.isNotEmpty(), "§10.3's example parsed to no tag names at all")
        assertEquals(example, readable - ChannelVocabulary.SUBJECT)
        assertTrue(
            DeliverableReleaseMessage.requiredTags().toSet().all { it in readable },
            "every required tag must also be readable",
        )
        assertFalse(
            DeliveryTags.MIME_TYPE in readable,
            "§10.3: an implementation MUST NOT look for `m` on the release",
        )
        assertFalse(
            DeliveryTags.FILE_TYPE in DeliveryCommitmentMessage.readableTags(),
            "§10.3: nor `${DeliveryTags.FILE_TYPE}` on the commitment",
        )
    }

    // ------------------------------------------------------------------ the happy path

    @JsName("a_worked_commitment_decodes_to_section_10_1_s_values")
    @Test
    fun `a worked commitment decodes to §10 1's values`() {
        val message = fixtures.commitment(fixtures.commitmentTags())

        assertEquals(OrderId.ofHex(fixtures.orderHex(0)), message.order)
        assertEquals(fixtures.servedHash(0), message.commitment.x.toHex())
        assertEquals(fixtures.plaintextHash(0), message.commitment.ox.toHex())
        assertEquals(DeliveryMessageFixtures.MIME_TYPE, message.commitment.mimeType)
        assertEquals(DeliveryMessageFixtures.SIZE.toLong(), message.commitment.sizeBytes)
        assertEquals(fixtures.url(0), message.url)
        assertEquals(fixtures.pubkey(0), message.sender)
    }

    @JsName("a_worked_release_decodes_to_section_10_3_s_values")
    @Test
    fun `a worked release decodes to §10 3's values`() {
        val message = fixtures.release(fixtures.releaseTags())

        assertEquals(OrderId.ofHex(fixtures.orderHex(0)), message.order)
        assertEquals(fixtures.servedHash(0), message.release.x.toHex())
        assertEquals(fixtures.plaintextHash(0), message.release.ox.toHex())
        assertEquals(DeliveryMessageFixtures.MIME_TYPE, message.release.fileType)
        assertEquals(DeliveryMessageFixtures.SIZE.toLong(), message.release.sizeBytes)
    }

    /** §10.3's identity case: the matching pair diverges on none of the four operands. */
    @JsName("a_matching_pair_diverges_on_nothing")
    @Test
    fun `a matching pair diverges on nothing`() {
        val commitment = fixtures.commitment(fixtures.commitmentTags())
        val release = fixtures.release(fixtures.releaseTags())

        assertEquals(emptyList(), release.divergenceFrom(commitment))
        // And T4's parsed-value check agrees on the identity case, which is what makes the
        // leading-zero control below a statement about the *bytes* rather than about a bug.
        commitment.commitment.checkReleaseIdentity(release.release)
    }

    // ------------------------------------- §10.1's key-absence rule, the one T4 could not enforce

    /**
     * §10.1: "The commitment message MUST NOT contain `decryption-key` or `decryption-nonce`. An
     * implementation MUST reject a commitment that does."
     *
     * Both tags, each on its own, each refused naming *that* tag. A loop rather than two tests, so a
     * third forbidden tag added to §10.1 lands here by being added to one list.
     */
    @JsName("a_commitment_carrying_key_material_is_refused_naming_the_tag")
    @Test
    fun `a commitment carrying key material is refused naming the tag`() {
        val forbidden = listOf(
            DeliveryTags.DECRYPTION_KEY to fixtures.hex(fixtures.keyBytes(0)),
            DeliveryTags.DECRYPTION_NONCE to fixtures.hex(fixtures.nonceBytes(0)),
        )

        for ((name, value) in forbidden) {
            val tags = fixtures.with(fixtures.commitmentTags(), listOf(name, value))
            val refused = assertFailsWith<ChannelException> { fixtures.commitment(tags) }

            assertEquals(ChannelRejection.COMMITMENT_CARRIES_KEY, refused.reason)
            assertEquals(name, refused.tag, "the refusal must name which of the two arrived")
        }
    }

    /**
     * And the rule is not satisfied by the tag merely being unreadable: a `decryption-key` carrying
     * no value at all is still a commitment that contains one.
     *
     * Written because the obvious implementation reads the value first and refuses on its shape,
     * which would let `["decryption-key"]` through the one rule §10.5 says the construction rests on.
     */
    @JsName("a_commitment_carrying_a_valueless_key_tag_is_still_refused")
    @Test
    fun `a commitment carrying a valueless key tag is still refused`() {
        val tags = fixtures.with(fixtures.commitmentTags(), listOf(DeliveryTags.DECRYPTION_KEY))
        val refused = assertFailsWith<ChannelException> { fixtures.commitment(tags) }

        assertEquals(ChannelRejection.COMMITMENT_CARRIES_KEY, refused.reason)
    }

    // ------------------------------------------------------------------ the required-tag walks

    /**
     * Every tag the commitment decoder marks REQUIRED, removed in turn, refused naming *that* tag.
     *
     * A loop over the published list rather than ten hand-written controls, so an eleventh cannot be
     * added to the codec without being covered here. The list is asserted non-empty first, because a
     * loop over nothing passes.
     */
    @JsName("every_required_commitment_tag_removed_in_turn_is_refused_by_name")
    @Test
    fun `every required commitment tag removed in turn is refused by name`() {
        val required = DeliveryCommitmentMessage.requiredTags()
        assertTrue(required.size >= REQUIRED_COMMITMENT_FLOOR, "only ${required.size} required tags")

        for (name in required) {
            val tags = fixtures.without(fixtures.commitmentTags(), name)
            val refused = assertFailsWith<ChannelException>("removing `$name` must be refused") {
                fixtures.commitment(tags)
            }
            assertTrue(
                refused.tag == name || refused.reason == ChannelRejection.NOT_A_DELIVERY_COMMITMENT,
                "removing `$name` was refused as ${refused.reason} naming `${refused.tag}`; only " +
                    "the `${ChannelTags.TYPE}` removal may answer about the message kind, because " +
                    "a rumor with no `${ChannelTags.TYPE}` is not a `type=5` at all",
            )
        }
    }

    /** The same walk on §10.3's release. */
    @JsName("every_required_release_tag_removed_in_turn_is_refused_by_name")
    @Test
    fun `every required release tag removed in turn is refused by name`() {
        val required = DeliverableReleaseMessage.requiredTags()
        assertTrue(required.size >= REQUIRED_RELEASE_FLOOR, "only ${required.size} required tags")

        for (name in required) {
            val tags = fixtures.without(fixtures.releaseTags(), name)
            val refused = assertFailsWith<ChannelException>("removing `$name` must be refused") {
                fixtures.release(tags)
            }
            assertEquals(name, refused.tag, "removing `$name` was refused naming `${refused.tag}`")
        }
    }

    // ------------------------------------------------------------------ §10.2's encodings

    /**
     * §10.2: both encodings accepted at the right length, both refused at the wrong one.
     *
     * The four cases are one test because the pairing is the statement: a library that refused
     * everything would pass a wrong-length control on its own, and one that accepted everything
     * would pass the two positive ones.
     */
    @JsName("section_10_2_s_two_encodings_are_accepted_and_a_wrong_length_is_not")
    @Test
    fun `§10 2's two encodings are accepted and a wrong length is not`() {
        val key = fixtures.keyBytes(0)

        fixtures.release(fixtures.releaseTags(key = fixtures.hex(key)))
        fixtures.release(fixtures.releaseTags(key = fixtures.base64(key)))

        // 63 hex characters: one short, and §4.3 requires rejection rather than padding.
        val short = fixtures.hex(key).dropLast(1)
        assertEquals(HEX_KEY_LENGTH - 1, short.length)
        val refusedHex = assertFailsWith<ChannelException> {
            fixtures.release(fixtures.releaseTags(key = short))
        }
        assertEquals(ChannelRejection.MALFORMED_KEY_MATERIAL, refusedHex.reason)
        assertEquals(DeliveryTags.DECRYPTION_KEY, refusedHex.tag)

        // Base64 of 31 bytes, which is well-formed base64 and the wrong length — §10.2's own case.
        val thirtyOne = fixtures.base64(key.copyOf(key.size - 1))
        val refusedBase64 = assertFailsWith<ChannelException> {
            fixtures.release(fixtures.releaseTags(key = thirtyOne))
        }
        assertEquals(ChannelRejection.MALFORMED_KEY_MATERIAL, refusedBase64.reason)
        assertEquals(DeliveryTags.DECRYPTION_KEY, refusedBase64.tag)
    }

    /** The same four cases for the 96-bit nonce, whose length is the other one §10.2 fixes. */
    @JsName("the_nonce_takes_both_encodings_at_twelve_bytes_and_neither_at_eleven")
    @Test
    fun `the nonce takes both encodings at twelve bytes and neither at eleven`() {
        val nonce = fixtures.nonceBytes(0)

        fixtures.release(fixtures.releaseTags(nonce = fixtures.hex(nonce)))
        fixtures.release(fixtures.releaseTags(nonce = fixtures.base64(nonce)))

        for (wrong in listOf(fixtures.hex(nonce).dropLast(2), fixtures.base64(nonce.copyOf(11)))) {
            val refused = assertFailsWith<ChannelException> {
                fixtures.release(fixtures.releaseTags(nonce = wrong))
            }
            assertEquals(ChannelRejection.MALFORMED_KEY_MATERIAL, refused.reason)
            assertEquals(DeliveryTags.DECRYPTION_NONCE, refused.tag)
        }
    }

    /**
     * Hex is tried **before** base64, and this is what would break if the order were swapped.
     *
     * Every hex character is also a base64 character, so §10.2's mandatory 64-character lowercase
     * hex key is itself well-formed base64 — for 48 bytes. A reader that tried base64 first would
     * refuse every conformant key in the one encoding §10.2 makes mandatory on write.
     */
    @JsName("a_hex_key_is_not_read_as_base64_first")
    @Test
    fun `a hex key is not read as base64 first`() {
        val hexKey = fixtures.hex(fixtures.keyBytes(0))

        assertEquals(HEX_KEY_LENGTH, hexKey.length)
        assertEquals(0, hexKey.length % BASE64_GROUP, "the trap needs a length base64 would accept")
        fixtures.release(fixtures.releaseTags(key = hexKey))
    }

    /** §10.2: `encryption-algorithm` is `aes-gcm` in v1 and there is no second value. */
    @JsName("an_encryption_algorithm_other_than_aes_gcm_is_refused")
    @Test
    fun `an encryption-algorithm other than aes-gcm is refused`() {
        val commitment = assertFailsWith<ChannelException> {
            fixtures.commitment(fixtures.commitmentTags(algorithm = OTHER_ALGORITHM))
        }
        assertEquals(ChannelRejection.UNSUPPORTED_ENCRYPTION_ALGORITHM, commitment.reason)
        assertEquals(DeliveryTags.ENCRYPTION_ALGORITHM, commitment.tag)

        val release = assertFailsWith<ChannelException> {
            fixtures.release(fixtures.releaseTags(algorithm = OTHER_ALGORITHM))
        }
        assertEquals(ChannelRejection.UNSUPPORTED_ENCRYPTION_ALGORITHM, release.reason)
    }

    // ------------------------------------------------------------------ §10.3's binding

    /**
     * §10.3: "A release whose `order` tag names no open order of this implementation's own, or names
     * an order not in state `paid`, MUST NOT advance any state."
     *
     * Both refusals, paired with the accepting case, because either one alone is satisfied by a
     * library that refuses every release.
     */
    @JsName("a_release_binds_only_to_this_order_and_only_in_paid")
    @Test
    fun `a release binds only to this order and only in paid`() {
        val orders = OrderFixtures.orders()
        val paid = orders.getValue(OrderState.PAID)
        val matching = fixtures.release(fixtures.releaseTags(order = paid.id.toHex()))

        // Accepted: this order, in `paid`.
        val event = matching.asOrderEvent(paid, Party.PROVIDER)
        assertEquals(
            OrderEvent.DeliverableReleased.DECLARABLE_CHECKS,
            event.checksPerformed,
            "§10.3's binding was checked here, so the event says so (§17)",
        )

        // Refused: another order's id, on the same order in the same state.
        val other = fixtures.release(fixtures.releaseTags(order = fixtures.orderHex(OTHER_INDEX)))
        val wrongOrder = assertFailsWith<ChannelException> { other.asOrderEvent(paid, Party.PROVIDER) }
        assertEquals(ChannelRejection.RELEASE_FOR_ANOTHER_ORDER, wrongOrder.reason)

        // Refused: the right order, in every state but `paid`.
        for ((state, order) in orders) {
            if (state == OrderState.PAID) continue
            val bound = fixtures.release(fixtures.releaseTags(order = order.id.toHex()))
            val refused = assertFailsWith<ChannelException>("$state must not accept a release") {
                bound.asOrderEvent(order, Party.PROVIDER)
            }
            assertEquals(
                ChannelRejection.RELEASE_ORDER_NOT_PAID,
                refused.reason,
                "a release naming an order in `$state` must be refused for the state",
            )
        }
    }

    /** And the refusal names no order id, whatever it was about (§12 item 11). */
    @JsName("a_binding_refusal_names_no_order_id")
    @Test
    fun `a binding refusal names no order id`() {
        val paid = OrderFixtures.orders().getValue(OrderState.PAID)
        val hex = fixtures.orderHex(OTHER_INDEX)
        val other = fixtures.release(fixtures.releaseTags(order = hex))

        val refused = assertFailsWith<ChannelException> { other.asOrderEvent(paid, Party.PROVIDER) }

        assertFalse(hex in (refused.message ?: ""), "the message names the order id a stranger chose")
        assertFalse(paid.id.toHex() in (refused.message ?: ""), "nor this order's own")
    }

    // ------------------------------------------------------- §10.3's bytes, which T4 cannot see

    /**
     * `["size", "018342912"]` against a commitment's `["size", "18342912"]` is a **divergence**,
     * and T4's numeric comparison cannot see it.
     *
     * The pairing is the whole test. §4.3 fixes a size as a non-negative decimal integer and nothing
     * more, so the leading-zero form is legal on the wire and decodes to the same `Long` — which is
     * why `checkReleaseIdentity` passes it and why §10.3, which says *byte-identical*, does not.
     */
    @JsName("a_leading_zero_size_is_a_divergence_over_the_bytes")
    @Test
    fun `a leading-zero size is a divergence over the bytes`() {
        val padded = "0${DeliveryMessageFixtures.SIZE}"
        val commitment = fixtures.commitment(fixtures.commitmentTags())
        val release = fixtures.release(fixtures.releaseTags(size = padded))

        assertEquals(
            DeliveryMessageFixtures.SIZE.toLong(),
            release.release.sizeBytes,
            "the two spellings are the same number, which is what makes this about bytes",
        )
        // T4's four-operand check, over parsed values: it passes, and says so in its own KDoc.
        commitment.commitment.checkReleaseIdentity(release.release)
        // §10.3's, over the bytes: it does not.
        assertEquals(listOf(DeliveryTags.SIZE), release.divergenceFrom(commitment))
    }

    /** Each of §10.3's four operands, broken on its own, named on its own. */
    @JsName("each_of_section_10_3_s_four_operands_diverges_by_name")
    @Test
    fun `each of §10 3's four operands diverges by name`() {
        val commitment = fixtures.commitment(fixtures.commitmentTags())
        val broken = listOf(
            DeliveryTags.FILE_TYPE to fixtures.releaseTags(fileType = OTHER_MIME_TYPE),
            DeliveryTags.SERVED_HASH to fixtures.releaseTags(x = fixtures.servedHash(OTHER_INDEX)),
            DeliveryTags.PLAINTEXT_HASH to
                fixtures.releaseTags(ox = fixtures.plaintextHash(OTHER_INDEX)),
            DeliveryTags.SIZE to fixtures.releaseTags(size = OTHER_SIZE),
        )

        assertEquals(
            DeliveryMessageFixtures.OPERAND_NAMES,
            broken.map { it.first },
            "the controls must cover §10.3's four operands in §10.3's order",
        )
        for ((name, tags) in broken) {
            assertEquals(listOf(name), fixtures.release(tags).divergenceFrom(commitment))
        }
    }

    /**
     * §10.3: an implementation "MUST NOT skip the check because the tag it looked for was absent".
     *
     * A release carrying no `file-type` decodes — T4 made that absence representable on purpose, so
     * that the case reaches the identity check to be refused there — and is a divergence here.
     */
    @JsName("an_absent_file_type_decodes_and_is_a_divergence")
    @Test
    fun `an absent file-type decodes and is a divergence`() {
        val commitment = fixtures.commitment(fixtures.commitmentTags())
        val release = fixtures.release(fixtures.without(fixtures.releaseTags(), DeliveryTags.FILE_TYPE))

        assertEquals(null, release.release.fileType)
        assertEquals(listOf(DeliveryTags.FILE_TYPE), release.divergenceFrom(commitment))
    }

    // ------------------------------------------------------------------ §12 item 11

    /** The release's `toString` carries neither the key nor the nonce — nor anything else. */
    @JsName("a_release_names_no_key_and_no_nonce")
    @Test
    fun `a release names no key and no nonce`() {
        val key = fixtures.hex(fixtures.keyBytes(0))
        val nonce = fixtures.hex(fixtures.nonceBytes(0))
        val printed = fixtures.release(fixtures.releaseTags(key = key, nonce = nonce)).toString()

        assertFalse(key in printed, "§12 item 11: a decryption key MUST NOT appear in any toString")
        assertFalse(nonce in printed, "nor the nonce")
        assertFalse(fixtures.orderHex(0) in printed, "nor the order id")
        assertFalse(fixtures.plaintextHash(0) in printed, "nor `ox`, which §10.5 calls a handle")
    }

    /** And neither does the commitment's, including the blob URL §12 item 10 is about. */
    @JsName("a_commitment_names_no_url_and_no_hash")
    @Test
    fun `a commitment names no url and no hash`() {
        val printed = fixtures.commitment(fixtures.commitmentTags()).toString()

        assertFalse(fixtures.url(0) in printed)
        assertFalse(fixtures.servedHash(0) in printed)
        assertFalse(fixtures.orderHex(0) in printed)
    }

    // ------------------------------------------------------------------ the doors and the bridges

    /** Each decoder refuses a well-formed message of the other's kind as what it is. */
    @JsName("each_decoder_refuses_the_other_s_message")
    @Test
    fun `each decoder refuses the other's message`() {
        val commitmentRumor = fixtures.bound(fixtures.commitmentTags(), NenyaKind.ORDER_MESSAGE)
        val releaseRumor = fixtures.bound(fixtures.releaseTags(), NenyaKind.FILE_MESSAGE)

        assertEquals(
            ChannelRejection.NOT_A_RELEASE,
            assertFailsWith<ChannelException> {
                DeliverableReleaseMessage.decode(commitmentRumor)
            }.reason,
        )
        assertEquals(
            ChannelRejection.NOT_A_DELIVERY_COMMITMENT,
            assertFailsWith<ChannelException> {
                DeliveryCommitmentMessage.decode(releaseRumor)
            }.reason,
        )
    }

    /**
     * §17: what the decoder checked travels onto the event, and what nobody checked does not.
     *
     * The pairing is the point. An event built from a bare `DeliverableCommitment` records an empty
     * set — a caller that assembled four typed values applied §10.1's rule or did not, and nothing
     * here knows which — while one built through the decoded message records the check it made.
     */
    @JsName("the_checks_a_decoder_made_travel_and_the_ones_nobody_made_do_not")
    @Test
    fun `the checks a decoder made travel and the ones nobody made do not`() {
        val decoded = fixtures.commitment(fixtures.commitmentTags()).asOrderEvent(Party.PROVIDER)
        val assembled = OrderEvent.DeliveryCommitted(decoded.commitment, Party.PROVIDER)

        assertEquals(setOf(DeliveryCheck.COMMITMENT_CARRIES_NO_KEY), decoded.checksPerformed)
        assertEquals(emptySet(), assembled.checksPerformed)
        assertEquals(
            OrderEvent.DeliveryCommitted.DECLARABLE_CHECKS,
            decoded.checksPerformed,
            "the bridge passes the event's own declarable set, not a second copy of it",
        )
        assertFalse(
            DeliveryCheck.ENCRYPTION_PARAMETERS in decoded.checksPerformed,
            "§10.2 is AES-256-GCM whole, and a matching algorithm token is not it (§17)",
        )

        val paid = OrderFixtures.orders().getValue(OrderState.PAID)
        val release = fixtures.release(fixtures.releaseTags(order = paid.id.toHex()))
        val released = release.asOrderEvent(paid, Party.PROVIDER)
        val bare = OrderEvent.DeliverableReleased(released.release, Party.PROVIDER)

        assertEquals(setOf(DeliveryCheck.RELEASE_ORDER_BINDING), released.checksPerformed)
        assertEquals(emptySet(), bare.checksPerformed)
        assertEquals(paid.id, released.order, "the id the binding was checked against travels")
        assertEquals(null, bare.order, "and a bare release claims no order at all")
        assertFalse(
            DeliveryCheck.RELEASE_IDENTITY in released.checksPerformed,
            "§10.3's comparison is a separate call the caller may not have made",
        )
    }

    /**
     * §17's one prohibition, made structural: a caller cannot assert a check this library did not
     * perform.
     *
     * This is the hole the first draft had. `checksPerformed` was a **public** constructor
     * parameter, so `OrderEvent.DeliverableReleased(release, PROVIDER, checksPerformed =
     * setOf(GCM_AUTHENTICATION))` reached a `settled` order that reported AES-GCM authentication as
     * performed **and deleted it** from the not-performed set a real `DeliveryEvidence` had honestly
     * published — while nothing decrypted anything. Two things close it and both are asserted here:
     * the published constructors have no such parameter, and the internal ones refuse a claim
     * outside the set the message could have discharged.
     */
    @JsName("a_caller_cannot_assert_a_check_this_library_did_not_perform")
    @Test
    fun `a caller cannot assert a check this library did not perform`() {
        val commitment = OrderFixtures.blob.commitment
        val release = OrderFixtures.matchingRelease()

        // §10.4 step 2 is the caller's decryption and §10.4 step 1's provenance needs a socket.
        // Neither is a thing a `type=5` or a `kind:15` could ever establish.
        for (check in listOf(DeliveryCheck.GCM_AUTHENTICATION, DeliveryCheck.SERVED_BYTES_PROVENANCE)) {
            val refusedCommitment = assertFailsWith<OrderStateException> {
                OrderEvent.DeliveryCommitted(commitment, Party.PROVIDER, null, setOf(check))
            }
            assertEquals(OrderStateRejection.UNDECLARABLE_CHECK, refusedCommitment.reason)

            val refusedRelease = assertFailsWith<OrderStateException> {
                OrderEvent.DeliverableReleased(
                    release,
                    Party.PROVIDER,
                    null,
                    OrderFixtures.ORDER_ID,
                    setOf(check),
                )
            }
            assertEquals(OrderStateRejection.UNDECLARABLE_CHECK, refusedRelease.reason)
        }

        // And each event refuses the *other's* declarable check, so the cap is per message and not
        // a single shared allow-list that happens to hold both.
        assertEquals(
            OrderStateRejection.UNDECLARABLE_CHECK,
            assertFailsWith<OrderStateException> {
                OrderEvent.DeliveryCommitted(
                    commitment,
                    Party.PROVIDER,
                    null,
                    OrderEvent.DeliverableReleased.DECLARABLE_CHECKS,
                )
            }.reason,
        )

        // §10.3's binding cannot be claimed with nothing to have bound to.
        assertEquals(
            OrderStateRejection.UNBOUND_CAPABILITY_CLAIM,
            assertFailsWith<OrderStateException> {
                OrderEvent.DeliverableReleased(
                    release,
                    Party.PROVIDER,
                    null,
                    null,
                    OrderEvent.DeliverableReleased.DECLARABLE_CHECKS,
                )
            }.reason,
        )
    }

    /**
     * §10.3's binding again, from the direction the event can still be misused: an event built
     * against one order and then offered to another.
     *
     * `asOrderEvent` checks the binding against the [dev.eryalabs.nenya.order.Order] it is handed,
     * but the event it returns is an ordinary value. Two orders with the same provider can carry the
     * same commitment — §10.3 says so in as many words, which is why it routes on the tag — so every
     * hash on the `paid → released` row would match a misrouted release. The machine therefore
     * compares the id again.
     */
    @JsName("an_event_built_against_one_order_does_not_release_another")
    @Test
    fun `an event built against one order does not release another`() {
        val machine = OrderFixtures.machineBeforeDeadlines()
        val here = OrderFixtures.orders().getValue(OrderState.PAID)
        val elsewhere = OrderFixtures.orders(index = OrderFixtures.OTHER_ORDER_INDEX)
            .getValue(OrderState.PAID)
        val release = fixtures.release(
            fixtures.releaseTags(
                order = elsewhere.id.toHex(),
                fileType = OrderFixtures.blob.commitment.mimeType,
                x = OrderFixtures.blob.commitment.x.toHex(),
                ox = OrderFixtures.blob.commitment.ox.toHex(),
                size = OrderFixtures.blob.commitment.sizeBytes.toString(),
            ),
        )

        // Legitimately bound to the *other* order, whose commitment is the same one.
        val event = release.asOrderEvent(elsewhere, Party.PROVIDER)

        val refused = OrderFixtures.refusal(machine, here, event)
        assertEquals(TransitionRejection.RELEASE_FOR_ANOTHER_ORDER, refused.reason)
        // And it does release the order it was actually bound to, or the line above is a statement
        // about a machine that refuses every release.
        assertEquals(OrderState.RELEASED, OrderFixtures.advanced(machine, elsewhere, event).state)
    }

    /**
     * §10.3 compares the operand's **value**, so a tag carrying a legal extra element is not a
     * mismatch.
     *
     * nostr permits a tag to carry more elements than a codec reads and §10.3 names the value, not
     * the arity — and §10.3 says any mismatch MUST move the order to `disputed`, so a comparison
     * over the whole tag would dispute a conformant peer over an element neither party's rule
     * mentions. Paired with a genuine value divergence on the same tag, because a comparison that
     * looked at nothing would pass the first half alone.
     */
    @JsName("a_trailing_tag_element_is_not_a_section_10_3_divergence")
    @Test
    fun `a trailing tag element is not a §10 3 divergence`() {
        val commitment = fixtures.commitment(fixtures.commitmentTags())
        val extended = fixtures.replacing(
            fixtures.releaseTags(),
            listOf(DeliveryTags.SERVED_HASH, fixtures.servedHash(0), "nip94-extension"),
        )

        assertEquals(emptyList(), fixtures.release(extended).divergenceFrom(commitment))

        val diverged = fixtures.replacing(
            fixtures.releaseTags(),
            listOf(DeliveryTags.SERVED_HASH, fixtures.servedHash(OTHER_INDEX), "nip94-extension"),
        )
        assertEquals(
            listOf(DeliveryTags.SERVED_HASH),
            fixtures.release(diverged).divergenceFrom(commitment),
        )
    }

    /**
     * And the order carries them forward: `committed` records §10.1's rule, `released` adds §10.3's
     * binding, and `settled` keeps both while still naming what §10 genuinely asks for and this
     * library does not do.
     */
    @JsName("the_order_records_what_the_decoders_checked_all_the_way_to_settled")
    @Test
    fun `the order records what the decoders checked all the way to settled`() {
        val machine = OrderFixtures.machineBeforeDeadlines()
        val orders = OrderFixtures.orders()
        val accepted = orders.getValue(OrderState.ACCEPTED)

        val commitmentTags = fixtures.commitmentTags(
            order = accepted.id.toHex(),
            x = OrderFixtures.blob.commitment.x.toHex(),
            ox = OrderFixtures.blob.commitment.ox.toHex(),
        )
        val committed = OrderFixtures.advanced(
            machine,
            accepted,
            fixtures.commitment(commitmentTags).asOrderEvent(Party.PROVIDER),
        )
        assertEquals(setOf(DeliveryCheck.COMMITMENT_CARRIES_NO_KEY), committed.deliveryChecksPerformed)

        val paid = orders.getValue(OrderState.PAID)
        // §10.3's four operands match the order's own commitment, so this is the `paid → released`
        // row and not §10.3's `paid → disputed` one. Asserted below rather than assumed: a release
        // that diverged would still *advance* the order, to `disputed`, and would carry no checks —
        // which is the shape that would make the line after it a statement about nothing.
        val release = fixtures.release(
            fixtures.releaseTags(
                order = paid.id.toHex(),
                fileType = OrderFixtures.blob.commitment.mimeType,
                x = OrderFixtures.blob.commitment.x.toHex(),
                ox = OrderFixtures.blob.commitment.ox.toHex(),
                size = OrderFixtures.blob.commitment.sizeBytes.toString(),
            ),
        )
        val released = OrderFixtures.advanced(machine, paid, release.asOrderEvent(paid, Party.PROVIDER))
        assertEquals(OrderState.RELEASED, released.state)
        assertTrue(DeliveryCheck.RELEASE_ORDER_BINDING in released.deliveryChecksPerformed)
        assertTrue(
            DeliveryCheck.COMMITMENT_CARRIES_NO_KEY !in released.deliveryChecksPerformed,
            "this order's commitment arrived as a bare `DeliverableCommitment`, so §10.1's rule " +
                "was applied by nobody and the order must not claim it",
        )

        val settled = OrderFixtures.advanced(
            machine,
            released,
            OrderEvent.DeliveryVerified(OrderFixtures.evidence()),
        )
        assertTrue(
            DeliveryCheck.RELEASE_ORDER_BINDING in settled.deliveryChecksPerformed,
            "§17 does not lapse on success: a check performed two transitions ago is still performed",
        )
        assertFalse(
            DeliveryCheck.RELEASE_ORDER_BINDING in settled.deliveryChecksNotPerformedHere,
            "and it MUST NOT appear in both sets, which is the order saying two things at once",
        )
        assertTrue(
            DeliveryCheck.GCM_AUTHENTICATION in settled.deliveryChecksNotPerformedHere,
            "while §10.4 step 2 is still the caller's and is still reported as unperformed",
        )
    }

    private companion object {

        /** §7.4's three, plus `type`, plus §10.1's and §10.2's six. */
        const val REQUIRED_COMMITMENT_FLOOR: Int = 10

        /** §7.4's three, plus §10.2's and §10.3's six. */
        const val REQUIRED_RELEASE_FLOOR: Int = 9

        /** 32 bytes of hex. */
        const val HEX_KEY_LENGTH: Int = 64

        /** Four base64 characters carry three bytes. */
        const val BASE64_GROUP: Int = 4

        /** A second index, so "another order" and "another blob" are generated rather than typed. */
        const val OTHER_INDEX: Int = 7

        /** A real algorithm §10.2 does not name, so the refusal is about the token and not the shape. */
        const val OTHER_ALGORITHM: String = "aes-cbc"

        const val OTHER_MIME_TYPE: String = "image/png"

        const val OTHER_SIZE: String = "18342913"
    }
}
