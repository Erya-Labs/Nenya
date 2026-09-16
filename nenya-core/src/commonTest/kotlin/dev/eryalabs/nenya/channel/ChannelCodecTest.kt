package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.seam.SeamCapability
import dev.eryalabs.nenya.tag.NenyaKind
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §7.2's attribution rule and §7.4's envelope, control by control.
 *
 * Every negative control asserts the **reason** and not merely that something was refused, because
 * §7.2 and §7.4 give reasons and an implementer who collapses them tells the user something false.
 * Every one of them is paired with the positive control that proves the rule was modelled rather
 * than inverted: a `type=6` carrying `order` is refused *and* the same rumor without it is
 * accepted; an over-strict codec fails the second half, and a codec that accepts everything fails
 * the first.
 */
class ChannelCodecTest {

    // ---------------------------------------------------------------------------------------
    // §7.2 — the attribution rule.
    // ---------------------------------------------------------------------------------------

    /**
     * §7.2's positive case, and the mode it must be reported in: "MUST report messages in that mode
     * as authenticated-by-decryption only, never as signature-verified."
     */
    @JsName("a_rumor_whose_pubkey_equals_the_seal_s_is_authenticated_by_decryption_only")
    @Test
    fun `a rumor whose pubkey equals the seal's is authenticated by decryption only`() {
        val author = ChannelFixtures.pubkeyFor(0)
        val rumor = AttributedRumor.attribute(
            author,
            ChannelFixtures.checked(author, NenyaKind.RECEIPT, ChannelFixtures.minimalBound()),
        )

        assertEquals(author, rumor.author, "§7.2 attributes to the seal's pubkey")
        assertEquals(Attribution.AUTHENTICATED_BY_DECRYPTION, rumor.attribution)
        assertTrue(
            SeamCapability.BIP340_VERIFICATION in rumor.notPerformedHere,
            "§17 forbids reporting an unverified thing as verified, and no signature was checked",
        )
        assertTrue(
            SeamCapability.NIP44_DECRYPTION in rumor.notPerformedHere,
            "the decryption was the caller's; this library was handed a rumor and a pubkey",
        )
    }

    /**
     * §7.2's own negative: "Implementations MUST verify that the `pubkey` of the `kind:13` seal
     * equals the `pubkey` of the rumor inside it, and MUST discard the message otherwise."
     *
     * Discarded, so there is no value at all for anything downstream to read — which is the
     * enforcement rather than a flag on an object somebody might not look at.
     */
    @JsName("a_rumor_claiming_another_key_is_discarded_naming_impersonation")
    @Test
    fun `a rumor claiming another key is discarded, naming impersonation`() {
        val author = ChannelFixtures.pubkeyFor(0)
        val impostor = ChannelFixtures.pubkeyFor(1)

        val refused = assertFailsWith<ChannelException> {
            AttributedRumor.attribute(
                impostor,
                ChannelFixtures.checked(author, NenyaKind.RECEIPT, ChannelFixtures.minimalBound()),
            )
        }

        assertEquals(ChannelRejection.IMPERSONATION, refused.reason)
        assertFalse(
            author in (refused.message ?: ""),
            "§12 item 2: a counterparty pubkey MUST NOT appear outside an encrypted event, and a " +
                "rejection message is a crash report waiting to happen",
        )
    }

    /**
     * §4.3's accept-and-normalise rule reaching §7.2's comparison: the same key spelled two ways is
     * the same key. Its exception list has exactly two entries and names neither a pubkey nor an
     * event id, so refusing this would be this library calling a conformant peer an impostor.
     */
    @JsName("the_two_pubkeys_are_compared_after_s4_3_normalisation")
    @Test
    fun `the two pubkeys are compared after §4-3 normalisation`() {
        val author = ChannelFixtures.pubkeyFor(0)
        val tags = ChannelFixtures.minimalBound()

        val upperSeal = AttributedRumor.attribute(
            author.uppercase(),
            ChannelFixtures.checked(author, NenyaKind.RECEIPT, tags),
        )
        val upperRumor = AttributedRumor.attribute(
            author,
            ChannelFixtures.checked(author.uppercase(), NenyaKind.RECEIPT, tags),
        )

        assertEquals(author, upperSeal.author)
        assertEquals(author, upperRumor.author)
        // And the id is still computed over the spelling the rumor carried, never over a
        // lowercased copy — §4.1's bytes are not §4.3's comparison.
        assertEquals(author.uppercase(), upperRumor.encode().pubkey)
    }

    /** A seal pubkey that is not 64 hex characters is the caller's bug, and says so. */
    @JsName("a_malformed_seal_pubkey_is_refused_as_the_caller_s_argument")
    @Test
    fun `a malformed seal pubkey is refused as the caller's argument`() {
        val author = ChannelFixtures.pubkeyFor(0)

        val refused = assertFailsWith<ChannelException> {
            AttributedRumor.attribute(
                author.drop(1),
                ChannelFixtures.checked(author, NenyaKind.RECEIPT, ChannelFixtures.minimalBound()),
            )
        }

        assertEquals(ChannelRejection.MALFORMED_SEAL_PUBKEY, refused.reason)
    }

    // ---------------------------------------------------------------------------------------
    // §7.4 — the envelope.
    // ---------------------------------------------------------------------------------------

    /** §7.4 gives four rumor kinds; a listing and a public bid carry their own required sets. */
    @JsName("an_event_that_is_not_one_of_s7_4_s_four_kinds_is_refused")
    @Test
    fun `an event that is not one of §7-4's four kinds is refused`() {
        for (kind in listOf(NenyaKind.PUBLIC_BID, NenyaKind.REQUEST, NenyaKind.OFFER)) {
            val refused = assertFailsWith<ChannelException> {
                ChannelFixtures.attribute(kind, ChannelFixtures.minimalBound())
            }
            assertEquals(ChannelRejection.NOT_A_RUMOR_KIND, refused.reason, "kind:$kind")
        }
        // And all four §7.4 kinds are accepted, so the refusal above is about the kind and not
        // about a decoder that refuses everything.
        for (shape in ChannelFixtures.SHAPES) {
            val rumor = ChannelFixtures.attribute(shape.kind, ChannelFixtures.minimal(shape))
            assertEquals(shape.kind, rumor.kind.kind)
        }
    }

    /**
     * §7.4's required set, proved by removing each tag in turn from a published list rather than by
     * three hand-written controls that could silently stop covering a fourth tag.
     */
    @JsName("every_required_tag_is_refused_by_name_when_it_is_missing")
    @Test
    fun `every required tag is refused by name when it is missing`() {
        val shapes = ChannelFixtures.SHAPES.filter { it.kind != NenyaKind.CHAT }
        var checked = 0
        for (shape in shapes) {
            for (tag in AttributedRumor.requiredTags()) {
                val whole = ChannelFixtures.minimal(shape)
                if (whole.none { it[0] == tag }) continue
                val refused = assertFailsWith<ChannelException> {
                    ChannelFixtures.attribute(shape.kind, ChannelFixtures.without(whole, tag))
                }
                assertEquals(ChannelRejection.MISSING_REQUIRED_TAG, refused.reason, "$shape / $tag")
                assertEquals(tag, refused.tag, "the refusal must name the tag that went missing")
                checked++
            }
        }
        assertTrue(checked >= shapes.size, "the removal loop ran $checked times, which is not the set")
    }

    /**
     * §7.4's single exception, from both sides. The pair is what proves the exception is modelled
     * rather than the rule inverted: one control alone is satisfied by a codec that requires
     * `order` nowhere, and the other by one that requires it everywhere.
     */
    @JsName("a_private_bid_must_not_carry_an_order_tag_and_is_accepted_without_one")
    @Test
    fun `a private bid must not carry an order tag, and is accepted without one`() {
        val type = NenyaKind.OrderMessageType.PRIVATE_BID
        val without = ChannelFixtures.minimalOrderMessage(type)

        val accepted = ChannelFixtures.attribute(NenyaKind.ORDER_MESSAGE, without)
        val bound = assertIs<AttributedRumor.Bound>(accepted)
        assertEquals(OrderMessageKind.PRIVATE_BID, bound.type)
        assertNull(bound.order, "§7.4: no order id exists until the buyer proposes")

        val refused = assertFailsWith<ChannelException> {
            ChannelFixtures.attribute(
                NenyaKind.ORDER_MESSAGE,
                ChannelFixtures.with(without, listOf(ChannelTags.ORDER, ChannelFixtures.orderHexFor(0))),
            )
        }
        assertEquals(ChannelRejection.FORBIDDEN_ORDER_TAG, refused.reason)
        assertEquals(ChannelTags.ORDER, refused.tag)
    }

    /** Every other bound kind requires `order`, including §7.4's deliberately-included `kind:15`. */
    @JsName("a_file_message_an_order_proposal_and_a_receipt_each_require_an_order_tag")
    @Test
    fun `a file message, an order proposal and a receipt each require an order tag`() {
        val shapes = listOf(
            ChannelFixtures.Shape(NenyaKind.FILE_MESSAGE, null),
            ChannelFixtures.Shape(NenyaKind.ORDER_MESSAGE, NenyaKind.OrderMessageType.PROPOSAL),
            ChannelFixtures.Shape(NenyaKind.RECEIPT, null),
        )
        for (shape in shapes) {
            val whole = ChannelFixtures.minimal(shape)
            val refused = assertFailsWith<ChannelException> {
                ChannelFixtures.attribute(shape.kind, ChannelFixtures.without(whole, ChannelTags.ORDER))
            }
            assertEquals(ChannelRejection.MISSING_REQUIRED_TAG, refused.reason, "$shape")
            assertEquals(ChannelTags.ORDER, refused.tag, "$shape")
            assertNotNull(
                assertIs<AttributedRumor.Bound>(ChannelFixtures.attribute(shape.kind, whole)).order,
                "$shape must be accepted with it",
            )
        }
    }

    /**
     * §7.4: "`kind:14` free-text chat is outside this rule... an implementation MAY carry `order` on
     * a `kind:14` and MUST NOT derive anything from its presence or absence."
     *
     * Both halves are accepted, and neither is an [AttributedRumor.Bound] — so there is no decoded
     * order id on either, which is the structural half of that MUST NOT.
     */
    @JsName("a_chat_is_accepted_with_or_without_an_order_tag_and_exposes_neither")
    @Test
    fun `a chat is accepted with or without an order tag, and exposes neither`() {
        val bare = ChannelFixtures.attribute(NenyaKind.CHAT, ChannelFixtures.minimalChat(), "hello")
        val bound = ChannelFixtures.attribute(
            NenyaKind.CHAT,
            ChannelFixtures.with(ChannelFixtures.minimalChat(), listOf(ChannelTags.ORDER, ChannelFixtures.orderHexFor(0))),
            "hello",
        )

        for (chat in listOf(bare, bound)) {
            assertIs<AttributedRumor.Chat>(chat)
            assertFalse(
                chat is AttributedRumor.Bound,
                "a kind:14 must expose no order id at all, so there is nothing to derive one from",
            )
            assertEquals(RumorKind.CHAT, chat.kind)
            assertEquals("hello", chat.content)
        }
        // The tag still survives §4.3's round trip on the one that carried it: not deriving from it
        // is not the same as dropping it, and dropping it would change the event id.
        assertTrue(bound.unknownTags.any { it[0] == ChannelTags.ORDER })
        assertTrue(bare.unknownTags.none { it[0] == ChannelTags.ORDER })
    }

    /** §7.4 reserves `type=4`: "Nenya v1 MUST NOT emit `type=4` and MUST ignore it on read." */
    @JsName("the_reserved_type_is_readable_and_carries_no_special_meaning")
    @Test
    fun `the reserved type is readable and carries no special meaning`() {
        val type = NenyaKind.OrderMessageType.SHIPPING
        val rumor = ChannelFixtures.attribute(
            NenyaKind.ORDER_MESSAGE,
            ChannelFixtures.minimalOrderMessage(type),
        )

        assertEquals(OrderMessageKind.SHIPPING, assertIs<AttributedRumor.Bound>(rumor).type)
        // And unemittable, which is the other half of §7.4's sentence. RumorVocabularyTest asserts
        // the refusal's reason; this is the pairing, on the same constant, in one place.
        assertFailsWith<ChannelException> { ChannelTags.type(OrderMessageKind.SHIPPING) }
    }

    /**
     * §7.4: "An implementation MUST ignore a `kind:16` whose `type` it does not implement, and MUST
     * NOT map an unknown `type` onto the nearest known one — the same rule §11.1 applies to an
     * unknown `status`."
     *
     * `7` is adjacent to the last assigned value and `99` is not, and neither may become
     * [OrderMessageKind.PROPOSAL] or [OrderMessageKind.STATUS_UPDATE].
     */
    @JsName("an_unrecognised_type_lands_in_the_sink_and_not_on_the_nearest_known_one")
    @Test
    fun `an unrecognised type lands in the sink and not on the nearest known one`() {
        for (unknown in listOf("7", "99")) {
            val tags = ChannelFixtures.replacing(
                ChannelFixtures.minimalOrderMessage(NenyaKind.OrderMessageType.PAYMENT_REQUEST),
                ChannelTags.TYPE,
                listOf(ChannelTags.TYPE, unknown),
            )
            val rumor = assertIs<AttributedRumor.Bound>(
                ChannelFixtures.attribute(NenyaKind.ORDER_MESSAGE, tags),
            )

            assertEquals(OrderMessageKind.UNKNOWN, rumor.type, "type=$unknown")
            assertFalse(rumor.type == OrderMessageKind.PROPOSAL)
            assertFalse(rumor.type == OrderMessageKind.STATUS_UPDATE)
            // §7.4's exception names `type=6` and nothing else, so an unknown type still binds.
            assertNotNull(rumor.order, "an unrecognised type is still one of the messages the rule covers")
        }
    }

    /**
     * §7.4's collision rule, and the control an implementer omits: the `nenya` tag is **present**
     * and names a version this implementation does not implement.
     *
     * Written this way deliberately. A `type=5` with no `nenya` tag at all is already refused by the
     * required-tag rule, so the absent form never reaches the version check and would test nothing;
     * the pair below is what distinguishes the two rejections by reason.
     */
    @JsName("a_foreign_version_on_a_type_5_or_type_6_is_ignored_and_not_read_as_a_commitment_or_a_bid")
    @Test
    fun `a foreign version on a type 5 or type 6 is ignored, not read as a commitment or a bid`() {
        val foreign = (dev.eryalabs.nenya.NenyaProtocol.VERSION + 1).toString()
        val types = listOf(
            NenyaKind.OrderMessageType.DELIVERY_COMMITMENT,
            NenyaKind.OrderMessageType.PRIVATE_BID,
        )
        for (type in types) {
            val whole = ChannelFixtures.minimalOrderMessage(type)

            val stranger = assertFailsWith<ChannelException> {
                ChannelFixtures.attribute(
                    NenyaKind.ORDER_MESSAGE,
                    ChannelFixtures.replacing(whole, "nenya", listOf("nenya", foreign)),
                )
            }
            assertEquals(ChannelRejection.UNSUPPORTED_VERSION, stranger.reason, "type=$type")

            val absent = assertFailsWith<ChannelException> {
                ChannelFixtures.attribute(NenyaKind.ORDER_MESSAGE, ChannelFixtures.without(whole, "nenya"))
            }
            assertEquals(
                ChannelRejection.MISSING_REQUIRED_TAG,
                absent.reason,
                "a missing `nenya` and a foreign one are different failures with different fixes",
            )

            // And this implementation's own version is accepted, so the refusal is about the number.
            assertEquals(
                OrderMessageKind.of(type),
                assertIs<AttributedRumor.Bound>(ChannelFixtures.attribute(NenyaKind.ORDER_MESSAGE, whole)).type,
            )
        }
        // The rule is §7.4's and reaches those two types only: a foreign version on a `type=3` is
        // §4.5's business and the caller's, not a rejection this codec invents.
        val status = ChannelFixtures.minimalOrderMessage(NenyaKind.OrderMessageType.STATUS)
        assertEquals(
            foreign.toInt(),
            ChannelFixtures.attribute(
                NenyaKind.ORDER_MESSAGE,
                ChannelFixtures.replacing(status, "nenya", listOf("nenya", foreign)),
            ).nenyaVersion,
        )
    }

    /** §4.3: a value of the wrong length is rejected, never padded — with the accepted pair. */
    @JsName("an_order_id_of_the_wrong_length_is_rejected_and_an_uppercase_one_is_normalised")
    @Test
    fun `an order id of the wrong length is rejected, and an uppercase one is normalised`() {
        val hex = ChannelFixtures.orderHexFor(0)
        val whole = ChannelFixtures.minimalBound()

        val short = assertFailsWith<ChannelException> {
            ChannelFixtures.attribute(
                NenyaKind.FILE_MESSAGE,
                ChannelFixtures.replacing(whole, ChannelTags.ORDER, listOf(ChannelTags.ORDER, hex.drop(1))),
            )
        }
        assertEquals(ChannelRejection.ORDER_ID_WRONG_LENGTH, short.reason)

        val notHex = assertFailsWith<ChannelException> {
            ChannelFixtures.attribute(
                NenyaKind.FILE_MESSAGE,
                ChannelFixtures.replacing(
                    whole,
                    ChannelTags.ORDER,
                    listOf(ChannelTags.ORDER, "z" + hex.drop(1)),
                ),
            )
        }
        assertEquals(ChannelRejection.ORDER_ID_NOT_HEX, notHex.reason)

        val upper = assertIs<AttributedRumor.Bound>(
            ChannelFixtures.attribute(
                NenyaKind.FILE_MESSAGE,
                ChannelFixtures.replacing(
                    whole,
                    ChannelTags.ORDER,
                    listOf(ChannelTags.ORDER, hex.uppercase()),
                ),
            ),
        )
        assertEquals(
            hex,
            upper.order?.toHex(),
            "§4.3's exception list is exhaustive and names only the BOLT-11 string and the " +
                "preimage, so an order id is accepted in either case and normalised",
        )
    }

    /** §4.3 and §4.4: a discriminator read permissively is one two implementations can disagree on. */
    @JsName("a_non_canonical_type_value_is_refused_rather_than_read_as_the_number_it_resembles")
    @Test
    fun `a non-canonical type value is refused rather than read as the number it resembles`() {
        for (spelling in listOf("01", "+1", "1.0", "", " 1")) {
            val tags = ChannelFixtures.replacing(
                ChannelFixtures.minimalOrderMessage(NenyaKind.OrderMessageType.PROPOSAL),
                ChannelTags.TYPE,
                listOf(ChannelTags.TYPE, spelling),
            )
            val refused = assertFailsWith<ChannelException> {
                ChannelFixtures.attribute(NenyaKind.ORDER_MESSAGE, tags)
            }
            assertEquals(ChannelRejection.MALFORMED_TYPE, refused.reason, "`$spelling`")
        }
        // `0` is below the first value §7.4 assigns, and is refused as malformed rather than read
        // into the unknown sink: §7.4's types start at 1 and T9's TagContext already says so.
        val zero = assertFailsWith<ChannelException> {
            ChannelFixtures.attribute(
                NenyaKind.ORDER_MESSAGE,
                ChannelFixtures.replacing(
                    ChannelFixtures.minimalOrderMessage(NenyaKind.OrderMessageType.PROPOSAL),
                    ChannelTags.TYPE,
                    listOf(ChannelTags.TYPE, "0"),
                ),
            )
        }
        assertEquals(ChannelRejection.MALFORMED_TYPE, zero.reason)
    }

    /** A `kind:16` with no `type` at all has no discriminator, so §7.4 cannot say what it is. */
    @JsName("an_order_message_with_no_type_tag_is_refused_naming_it")
    @Test
    fun `an order message with no type tag is refused, naming it`() {
        val refused = assertFailsWith<ChannelException> {
            ChannelFixtures.attribute(
                NenyaKind.ORDER_MESSAGE,
                ChannelFixtures.without(
                    ChannelFixtures.minimalOrderMessage(NenyaKind.OrderMessageType.STATUS),
                    ChannelTags.TYPE,
                ),
            )
        }

        assertEquals(ChannelRejection.MISSING_REQUIRED_TAG, refused.reason)
        assertEquals(ChannelTags.TYPE, refused.tag)
    }

    /**
     * The other side of the duplicate rule, which is §7.4's and not this codec's convenience: a
     * `kind:14` is outside the `order` rule entirely, so two of them is two unknown tags and §4.3
     * requires unknown tags be ignored on read and preserved verbatim.
     *
     * The pair with the control below is the point. Refusing here would be this library making a
     * conformant peer look broken over a value it never decodes, and §4.3's own rationale — two
     * implementations disagreeing about which order a signed message binds to — cannot reach a tag
     * neither of them reads.
     */
    @JsName("a_chat_carrying_two_order_tags_is_accepted_and_both_survive")
    @Test
    fun `a chat carrying two order tags is accepted, and both survive`() {
        val tags = listOf(
            listOf(ChannelTags.ORDER, ChannelFixtures.orderHexFor(0)),
            listOf(ChannelTags.ORDER, ChannelFixtures.orderHexFor(1)),
            // `type` falls under the same rule for the same reason: a kind:14 has no `type` at all,
            // so two of them are two unknown tags rather than a discriminator that disagrees.
            listOf(ChannelTags.TYPE, NenyaKind.OrderMessageType.PROPOSAL.toString()),
            listOf(ChannelTags.TYPE, NenyaKind.OrderMessageType.PAYMENT_REQUEST.toString()),
        )

        val chat = ChannelFixtures.attribute(NenyaKind.CHAT, tags)

        assertIs<AttributedRumor.Chat>(chat)
        assertFalse(chat is AttributedRumor.Bound, "still no decoded order id to derive anything from")
        assertEquals(tags, chat.encode().tags, "§4.3: an unknown tag is preserved verbatim")
    }

    /** §4.3's duplicate rationale over the two tags §5.3's table does not carry. */
    @JsName("a_second_order_or_type_tag_is_refused_rather_than_resolved")
    @Test
    fun `a second order or type tag is refused rather than resolved`() {
        val whole = ChannelFixtures.minimalOrderMessage(NenyaKind.OrderMessageType.STATUS)
        val duplicates = listOf(
            listOf(ChannelTags.ORDER, ChannelFixtures.orderHexFor(1)),
            listOf(ChannelTags.TYPE, NenyaKind.OrderMessageType.PROPOSAL.toString()),
        )
        for (tag in duplicates) {
            val refused = assertFailsWith<ChannelException> {
                ChannelFixtures.attribute(NenyaKind.ORDER_MESSAGE, ChannelFixtures.with(whole, tag))
            }
            assertEquals(ChannelRejection.DUPLICATE_TAG, refused.reason, tag[0])
            assertEquals(tag[0], refused.tag)
        }
    }

    /**
     * §7.4: "A `subject` tag (NIP-17) MAY be present on any rumor. It is display metadata only; an
     * implementation MUST NOT derive any term or state from it."
     *
     * It round-trips, and two rumors differing only in it decode to the same terms — which is the
     * behavioural half of "no term is derived from it". The structural half is
     * `ChannelStructureTest`: no published member of this package is named after it.
     */
    @JsName("a_subject_tag_round_trips_and_no_decoded_term_comes_from_it")
    @Test
    fun `a subject tag round-trips and no decoded term comes from it`() {
        val whole = ChannelFixtures.minimalOrderMessage(NenyaKind.OrderMessageType.STATUS)
        val plain = assertIs<AttributedRumor.Bound>(ChannelFixtures.attribute(NenyaKind.ORDER_MESSAGE, whole))
        val subjects = listOf("a subject", "another subject entirely")
        val decoded = subjects.map { subject ->
            assertIs<AttributedRumor.Bound>(
                ChannelFixtures.attribute(
                    NenyaKind.ORDER_MESSAGE,
                    ChannelFixtures.with(whole, listOf(SUBJECT, subject)),
                ),
            )
        }

        for ((index, rumor) in decoded.withIndex()) {
            assertEquals(plain.type, rumor.type)
            assertEquals(plain.order, rumor.order)
            assertEquals(plain.kind, rumor.kind)
            assertEquals(plain.nenyaVersion, rumor.nenyaVersion)
            assertTrue(
                rumor.unknownTags.any { it[0] == SUBJECT && it[1] == subjects[index] },
                "§4.3 requires an unrecognised tag be preserved verbatim",
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // §12 item 11 and STOP RULE 14.
    // ---------------------------------------------------------------------------------------

    /**
     * §12 item 11 names the order id in one sentence with key material and preimages, as values
     * that MUST NOT appear in the string representation of anything this library exposes.
     *
     * T3 and T5 carry the identical control for their own values; this is it for every type in this
     * package that transitively holds an order id.
     */
    @JsName("no_string_representation_in_this_package_carries_an_order_id")
    @Test
    fun `no string representation in this package carries an order id`() {
        val hex = ChannelFixtures.orderHexFor(0)
        val rumor = assertIs<AttributedRumor.Bound>(
            ChannelFixtures.attribute(NenyaKind.FILE_MESSAGE, ChannelFixtures.minimalBound()),
        )

        for (rendered in listOf(rumor.toString(), rumor.tags.toString(), rumor.order.toString())) {
            assertFalse(hex in rendered, "an order id reached a string representation: $rendered")
            assertFalse(hex.uppercase() in rendered, rendered)
        }
        // And the counterparty pubkey too (§12 item 2), which rides in the same values.
        assertFalse(rumor.counterparties.single().pubkey in rumor.toString())
        // The control's own control: the value really is in this rumor, so the assertions above
        // are about redaction rather than about a fixture that never carried one.
        assertEquals(hex, rumor.order?.toHex())
    }

    // ---------------------------------------------------------------------------------------
    // §5.3, reached through §7.4.
    // ---------------------------------------------------------------------------------------

    /**
     * §5.3's Encoding column is universal, so a malformed `p` on a rumor is refused here with the
     * tag layer's own reason kept on the cause — never flattened away.
     */
    @JsName("s5_3_s_encoding_column_refuses_a_malformed_value_and_keeps_its_reason")
    @Test
    fun `§5-3's encoding column refuses a malformed value and keeps its reason`() {
        val refused = assertFailsWith<ChannelException> {
            ChannelFixtures.attribute(
                NenyaKind.RECEIPT,
                ChannelFixtures.replacing(ChannelFixtures.minimalBound(), "p", listOf("p", "not-a-pubkey")),
            )
        }

        assertEquals(ChannelRejection.MALFORMED_TAG, refused.reason)
        assertIs<dev.eryalabs.nenya.tag.TagException>(
            refused.cause,
            "the §5.3 reason must survive on the cause rather than being flattened",
        )
    }

    /** §7.4's `order` writer takes the minted type, so no caller can assemble one from a string. */
    @JsName("the_order_tag_writer_emits_s4_3_s_canonical_lowercase")
    @Test
    fun `the order tag writer emits §4-3's canonical lowercase`() {
        val rumor = assertIs<AttributedRumor.Bound>(
            ChannelFixtures.attribute(NenyaKind.RECEIPT, ChannelFixtures.minimalBound()),
        )
        val id = assertNotNull(rumor.order)

        assertEquals(listOf(ChannelTags.ORDER, ChannelFixtures.orderHexFor(0)), ChannelTags.order(id))
    }

    private companion object {

        /** NIP-17's display-metadata tag, which §5.3's table does not carry (§7.4). */
        const val SUBJECT: String = "subject"
    }
}
