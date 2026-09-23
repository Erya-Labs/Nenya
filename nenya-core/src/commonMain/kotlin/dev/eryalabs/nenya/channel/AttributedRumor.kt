package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.NenyaProtocol
import dev.eryalabs.nenya.collections.readOnlySetOf
import dev.eryalabs.nenya.seam.OrderId
import dev.eryalabs.nenya.seam.SeamCapability
import dev.eryalabs.nenya.seam.SeamException
import dev.eryalabs.nenya.seam.SeamRejection
import dev.eryalabs.nenya.tag.PubkeyRef
import dev.eryalabs.nenya.tag.TagContext
import dev.eryalabs.nenya.tag.TagException
import dev.eryalabs.nenya.tag.TagLimits
import dev.eryalabs.nenya.tag.TagRejection
import dev.eryalabs.nenya.tag.TagSet
import dev.eryalabs.nenya.tag.readPubkeyHex
import dev.eryalabs.nenya.tag.readStrictDecimal
import dev.eryalabs.nenya.wire.CheckedEvent
import dev.eryalabs.nenya.wire.EventId
import dev.eryalabs.nenya.wire.WireEvent

/**
 * A NIP-17 rumor whose terms this library has attributed to a key by §7.2's rule, with §7.4's
 * envelope decoded.
 *
 * ### What this package is, and the half of §7 it deliberately is not
 *
 * §7.1's sealing and §7.3's `kind:10050` publication both need machinery this library may not
 * build: NIP-44 encryption is a seam whose default answers `Unavailable`, and nothing here opens a
 * socket. §7.2's rule is a **string comparison** and §7.4's rules are **structure**, and §7.2 says
 * in so many words that the comparison is binding even on an implementation that can do nothing
 * else: "An implementation that cannot verify the seal's signature (no secp256k1; see §17) still
 * MUST perform the pubkey-equality check, and MUST report messages in that mode as
 * authenticated-by-decryption only, never as signature-verified."
 *
 * So this package performs that half and says so, and **nothing here decrypts, encrypts, seals or
 * wraps**. A rumor is an unsigned event with an `id` and a `created_at` and no `sig` (§7.1 step 1),
 * which means T8's `checkEventId` applies to it unchanged — and that is why [attribute] takes a
 * [CheckedEvent] exactly as the listing and bid codecs do.
 *
 * ### The gift wrap's pubkey is unrepresentable here
 *
 * §7.2: "The gift wrap's `pubkey` is random and carries **no** identity. Implementations MUST NOT
 * display it, index by it, or use it in any comparison." A rule expressed as a prohibition needs a
 * shape that cannot break it, so [attribute] takes the **seal's** pubkey and the rumor, and there
 * is no second pubkey parameter a wrap key could arrive in by mistake. `ChannelStructureTest`
 * asserts that by reflection: exactly one `String` on the entry point, and no published member of
 * this package named after a wrap or an ephemeral key.
 *
 * ### Unforgeable through the published API
 *
 * Same shape and same reason as `VerifiedPayment`, `DeliveryEvidence` and `CheckedEvent`: a public
 * `sealed interface` whose implementations are `private` classes nested inside its companion. An
 * interface has no constructor to synthesise an accessor for, so `Class.getConstructors()` on it is
 * empty by construction and stays empty; a `private constructor` plus a companion factory emits a
 * public synthetic constructor a Java client can call with a `null` marker, and a `data class` with
 * a private primary constructor is still reachable through the generated `copy()`. [Chat] and
 * [Bound] are public because a caller has to be able to name what it received, and they are
 * themselves `sealed`, so the JVM's `PermittedSubclasses` attribute is what stops another module
 * implementing one.
 *
 * The claim is "unforgeable **through the published API**", not unforgeable full stop: a `private`
 * nested class compiles to a package-private JVM class with a public constructor, so a client that
 * declares itself into `dev.eryalabs.nenya.channel` on the same classloader can still reach it.
 * Nenya protects its user against counterparties and relays, not against the client embedding it.
 *
 * ### What it claims and what it does not
 *
 * It claims exactly this: T8 recomputed the event's id; the seal pubkey the caller supplied equals
 * the pubkey the rumor claims (§7.2); the rumor is one of §7.4's four kinds; it carries §7.4's
 * required tags for that kind and not the one §7.4 forbids; and its §5.3 values decode under §5.3's
 * Encoding column.
 *
 * It claims **nothing** about a signature of its own: [attribute] is handed a pubkey and a rumor,
 * never a seal or a verifier, so [Attribution.AUTHENTICATED_BY_DECRYPTION] is the only mode it can
 * report and `ChannelCodecTest` pins that. [Attribution.SIGNATURE_VERIFIED] exists for the one
 * caller that genuinely obtained a verdict — `GiftWrap.open`, through the `internal` path below —
 * and [notPerformedHere] says what **this package** did not do either way; see it for why that set
 * is the same on both paths.
 *
 * Pure computation: no clock, no randomness, no I/O.
 */
public sealed interface AttributedRumor {

    /**
     * The key every term in this rumor is attributed to: the **seal's** pubkey, in §4.3's canonical
     * lowercase.
     *
     * §7.2: "An order term is attributed to the **seal's** `pubkey`, never to the rumor's claimed
     * `pubkey`, never to the gift wrap's ephemeral `pubkey`." The two agree by the time this value
     * exists — that is the check — so which one is published matters only in that the seal's is the
     * one §7.2 names, and it is the one stored.
     */
    public val author: String

    /** The id T8 recomputed and found to match the rumor's own (§4.1, §7.1 step 1). */
    public val id: EventId

    /**
     * §4.1's `created_at`, verbatim.
     *
     * A **claim**, and §4.6 says so: a counterparty's `created_at` orders nothing and decides no
     * deadline. Nothing here reads a clock or compares this against one.
     */
    public val createdAt: Long

    /**
     * §4.1's `content`, verbatim, carrying **no** machine meaning to this codec.
     *
     * On a [Chat] it is the message the user typed; on a `kind:16` §7.5's worked example shows it
     * empty. This package never parses it — a parser that never looks cannot be fooled by what it
     * would have found — and re-emits it byte for byte in [encode].
     */
    public val content: String

    /** Which of §7.4's four rumor kinds this is. */
    public val kind: RumorKind

    /** §7.2's mode. [Attribution.AUTHENTICATED_BY_DECRYPTION] unless a seal signature verified. */
    public val attribution: Attribution

    /**
     * What **this package** did not check to produce this value (§17): BIP-340 verification and
     * NIP-44 decryption, neither of which happens in this file on any path.
     *
     * The same set on both of [Attribution]'s modes, and that is the rule rather than an oversight.
     * "Here" means this codec: it performs §7.2's string comparison over two keys and §7.4's
     * envelope, and it neither decrypts nor verifies on either path. On the public [attribute] the
     * decryption was the **caller's** — this library was handed a rumor and a pubkey and takes no
     * position on where they came from. On `GiftWrap.open`'s path the decryption is **orchestrated**
     * one layer out and performed by the injected signer, and the signature verdict is obtained one
     * layer out too, from the injected `Secp256k1Ops` — so `OpenedMessage` is where both are
     * reported, as `sealSignature`, `wrapSignature` and its own `notPerformedHere`. Restating either
     * as performed *here* would attribute somebody else's work to this codec, which is the §17
     * over-claim in miniature; understating it costs a caller nothing, because the precise statement
     * is published on the value that actually did the work.
     *
     * Published for the reason `VerifiedPayment.checksNotPerformedHere` is: §17 forbids reporting
     * unverified things as verified and requires the statement be machine-readable, and a caller
     * that must know which half it is holding reads the constants rather than inferring them from
     * the existence of this value.
     */
    public val notPerformedHere: Set<SeamCapability>

    /** Every tag, decoded under §5.3's Encoding column and preserved verbatim for [encode] (§4.3). */
    public val tags: TagSet

    /** §4.5's `nenya` version. REQUIRED on a `kind:15`, `kind:16` and `kind:17` (§7.4). */
    public val nenyaVersion: Int?

    /** §7.4's `["p", ...]` counterparties, as §5.3's Encoding column decoded them. */
    public val counterparties: List<PubkeyRef>

    /**
     * The tags §5.3's table does not name, verbatim and in order (§4.3).
     *
     * Includes §7.4's own `order` and `type`, because §5.3's table is the listing vocabulary and
     * neither is a row in it — the same relationship §6's scope tags have to `Bid.unknownTags`.
     * They are read by this codec **and** are here, which is not a contradiction: "unknown" names
     * the table they are absent from, and §4.3's round-trip guarantee is what keeps them byte-exact
     * on the way back out.
     */
    public val unknownTags: List<List<String>>

    /**
     * The rumor this was decoded from, byte for byte — same pubkey spelling, same tags, same order,
     * same id.
     *
     * §4.3 requires unknown tags be preserved verbatim when re-publishing an event this
     * implementation did not author, and states the consequence itself: dropping one changes the
     * id. `ChannelPropertyTest` proves this the way the rule states it, by recomputing the id
     * through T8 over ten thousand generated rumors rather than by comparing lists.
     *
     * The pubkey is the **rumor's own spelling**, not [author]'s normalised form. §4.3's
     * accept-and-normalise rule is about comparison, and §4.1's id is about bytes: re-emitting a
     * lowercased copy of an uppercase pubkey would compute a different id and detach the seal that
     * carried it.
     */
    public fun encode(): WireEvent

    /**
     * §7.4's `kind:14` free-text chat, which is **outside** the required-tag rule.
     *
     * §7.4: chat "carries no terms and moves no state, so binding it is a display convenience; an
     * implementation MAY carry `order` on a `kind:14` and MUST NOT derive anything from its
     * presence or absence."
     *
     * That last clause is made structural rather than tested: this type declares **no** order id
     * and no `type`, and this codec never parses a `kind:14`'s `order` tag into an [OrderId] at
     * all, so there is no decoded value for a caller to derive anything from. §4.3 still requires
     * the raw tag survive [encode] verbatim, and it does — a byte string a caller would have to
     * parse itself, which is precisely the derivation §7.4 forbids and precisely what nothing here
     * does for it.
     */
    public sealed interface Chat : AttributedRumor

    /**
     * §7.4's `kind:15`, `kind:16` and `kind:17` — the three kinds its required-tag rule is stated
     * over.
     *
     * §7.4: "Every `kind:15`, `kind:16` and `kind:17` rumor MUST carry `["nenya", "1"]` and
     * `["p", ...]`. Every one of them MUST **additionally** carry `["order", ...]`, with the single
     * exception of `kind:16` `type=6`, which MUST NOT carry it."
     */
    public sealed interface Bound : AttributedRumor {

        /** §7.4's `type`, present exactly on a `kind:16` and `null` on a `kind:15` or `kind:17`. */
        public val type: OrderMessageKind?

        /**
         * §7.4's order id, `null` on exactly one shape: the `kind:16` `type=6` private bid, which
         * §7.4 says MUST NOT carry one "because no order exists yet".
         *
         * Nullable rather than split into a further type because the absence is enforced by
         * rejection and not by shape: a `type=6` carrying an `order` tag is
         * [ChannelRejection.FORBIDDEN_ORDER_TAG], so this is `null` exactly when §7.4 says it must
         * be and never because a tag was missed.
         */
        public val order: OrderId?
    }

    public companion object {

        /**
         * §7.2's own check and §7.4's envelope, in that order — and the order is the rule.
         *
         * §7.2 is applied **first**, before the kind, before a tag is read, so that a rumor
         * claiming somebody else's key is always refused as [ChannelRejection.IMPERSONATION] and
         * never as whatever else happened to be wrong with it. No value is produced for one at all:
         * "Implementations MUST verify that the `pubkey` of the `kind:13` seal equals the `pubkey`
         * of the rumor inside it, and MUST discard the message otherwise."
         *
         * @param sealPubkey the `kind:13` seal's x-only pubkey, 64 hex characters in either case
         *   (§4.3). The **seal's**, never the gift wrap's — §7.2 forbids the wrap's being used in
         *   any comparison, and there is no parameter here for it to arrive in.
         * @param rumor the unsigned rumor, with its id already recomputed by T8 (§4.1, §7.1).
         * @param limits §4.3's fifth resource bound, injected because §4.3 says the bounds SHOULD
         *   be configurable. Exceeding it is a rejection, never a truncation.
         * @throws ChannelException naming which §7.2, §7.4, §5.3 or §4.3 rule refused the rumor,
         *   and the tag it was about. The tag layer's own [TagRejection] is preserved on the cause.
         */
        public fun attribute(
            sealPubkey: String,
            rumor: CheckedEvent,
            limits: TagLimits = TagLimits.DEFAULT,
        ): AttributedRumor = attribute(sealPubkey, rumor, limits, Attribution.AUTHENTICATED_BY_DECRYPTION)

        /**
         * The same rule, reporting [Attribution.SIGNATURE_VERIFIED] — for the one caller that
         * actually obtained a verdict.
         *
         * `internal`, and that visibility is the whole of how §7.2's over-claim stays
         * unrepresentable through the published API: the only call site is `GiftWrap.open`, which
         * reaches it after the injected `Secp256k1Ops` answered `SignatureVerdict.VALID` for the
         * `kind:13` seal's signature, against the seal's `pubkey`, over the id the envelope package
         * recomputed itself (§4.1). Every §7.2 and §7.4 check below runs identically on both paths;
         * the parameter decides nothing except which mode is reported, which is why it is a mode and
         * not a flag that could switch a check off.
         *
         * There is deliberately no parameter for the **wrap's** verdict. §7.2: the wrap's signature
         * "attributes nothing, it does not corroborate the seal, and an implementation MUST NOT
         * report a message as signature-verified on the strength of it" — so it is not something
         * this function could be handed even by mistake.
         */
        internal fun attributeSignatureVerified(
            sealPubkey: String,
            rumor: CheckedEvent,
            limits: TagLimits = TagLimits.DEFAULT,
        ): AttributedRumor = attribute(sealPubkey, rumor, limits, Attribution.SIGNATURE_VERIFIED)

        private fun attribute(
            sealPubkey: String,
            rumor: CheckedEvent,
            limits: TagLimits,
            attribution: Attribution,
        ): AttributedRumor {
            val event = rumor.event
            val author = checkAttribution(sealPubkey, event)

            val kind = RumorKind.of(event.kind) ?: throw ChannelException(
                ChannelRejection.NOT_A_RUMOR_KIND,
                null,
                "§7.4 gives four rumor kinds — ${RumorKind.entries.map { it.kind }} — and this event " +
                    "is kind:${event.kind}; §7.4's required set MUST NOT be applied to a listing or " +
                    "to a public bid, which carry their own",
            )

            val raw = event.tags
            refuseDuplicates(kind, raw)

            val typeNumber = if (kind == RumorKind.ORDER_MESSAGE) readTypeNumber(raw) else null
            val message = typeNumber?.let { OrderMessageKind.of(it) }
            val context = try {
                if (typeNumber == null) TagContext.rumor(kind.kind) else TagContext.orderMessage(typeNumber)
            } catch (refused: TagException) {
                throw ChannelException(
                    ChannelRejection.MALFORMED_TYPE,
                    ChannelVocabulary.TYPE,
                    "§7.4's `${ChannelVocabulary.TYPE}` values start at 1 and this one does not",
                    refused,
                )
            }

            val tags = try {
                TagSet.read(rumor, context, limits)
            } catch (refused: TagException) {
                throw refused.asChannelRejection(raw)
            }

            if (kind.carriesRequiredTags) {
                if (tags.nenyaVersion == null) throw missing(kind, ChannelVocabulary.VERSION)
                if (tags.pubkeyRefs.isEmpty()) throw missing(kind, ChannelVocabulary.COUNTERPARTY)
            }
            val order = readOrder(kind, message, raw)
            checkVersionCollision(message, tags.nenyaVersion)

            return if (kind == RumorKind.CHAT) {
                ChatRumor(author, rumor.id, event, kind, tags, attribution)
            } else {
                BoundRumor(author, rumor.id, event, kind, tags, attribution, message, order)
            }
        }

        /**
         * §7.4's required set, published because it is the set this decoder enforces and because
         * `ChannelCodecTest` walks it, removing each tag in turn and asserting the refusal names
         * *that* tag — so the proof is a loop over a published list rather than over one kept in
         * step by hand.
         *
         * Unlike `Listing.requiredTags` this is **not** derived from §5.3's Requirement column, and
         * that is the rule rather than an omission: §5.3 says that column is a listing rule and
         * MUST NOT be applied to an order rumor. §7.4 states this set in its own fenced block, and
         * the tests hold this list equal to that block parsed out of the document.
         */
        public fun requiredTags(): List<String> = ChannelVocabulary.REQUIRED

        /**
         * §17: neither BIP-340 verification nor NIP-44 decryption happens in this file.
         *
         * On the public [attribute] the decryption was the caller's. On `GiftWrap.open`'s path it is
         * orchestrated one layer out and **performed by the seam**, as the signature check is — so
         * the set is the same on both, and [notPerformedHere] says why that is honest rather than
         * stale.
         */
        public val NOT_PERFORMED_HERE: Set<SeamCapability> = readOnlySetOf(
            linkedSetOf(SeamCapability.NIP44_DECRYPTION, SeamCapability.BIP340_VERIFICATION),
        )

        /**
         * §7.2's pubkey-equality check, and nothing else.
         *
         * Both sides go through §4.3's accept-and-normalise reader before being compared, so a peer
         * that spelled the same key in uppercase on the seal and lowercase in the rumor is not
         * refused for it: §4.3's no-normalisation exception list has exactly two entries and names
         * neither a pubkey nor an event id.
         */
        private fun checkAttribution(sealPubkey: String, event: WireEvent): String {
            val sealed = sealed(sealPubkey)
            // The rumor's pubkey passed T8's 64-hex check on the way into WireEvent, so this reader
            // cannot refuse it; it is here to normalise, which is what makes the comparison §4.3's
            // rather than a byte comparison that would call a legal peer an impersonator.
            val claimed = try {
                readPubkeyHex(event.pubkey, "the rumor's claimed pubkey")
            } catch (refused: TagException) {
                throw refused.asChannelRejection(event.tags)
            }
            if (sealed != claimed) {
                throw ChannelException(
                    ChannelRejection.IMPERSONATION,
                    null,
                    "§7.2: the rumor is unsigned, so its `pubkey` is a claim and not a proof, and " +
                        "it does not equal the kind:13 seal's. The message MUST be discarded — " +
                        "without this check any sender can impersonate any other by writing " +
                        "someone else's pubkey into the rumor.",
                )
            }
            return sealed
        }

        /** §4.3's accept-and-normalise hex, read through T9's reader rather than a second copy. */
        private fun sealed(sealPubkey: String): String = try {
            readPubkeyHex(sealPubkey, "the seal's pubkey")
        } catch (refused: TagException) {
            throw ChannelException(
                ChannelRejection.MALFORMED_SEAL_PUBKEY,
                null,
                "§7.2 compares the kind:13 seal's `pubkey` with the rumor's, and §4.3 fixes an " +
                    "x-only pubkey as exactly 64 hex characters; this one is not one",
                refused,
            )
        }

        /**
         * §4.3's duplicate rationale over the two tags §5.3's table does not carry.
         *
         * §4.3 states the rule over "any tag **this document** marks with cardinality `1` or `0–1`",
         * and `order` and `type` are §7.4's rather than §5.3's, so the tag codec does not reach them
         * and this codec must. The rationale reaches them unchanged: "First wins" and "last wins"
         * are both defensible, which is exactly the problem. A byte-identical repeat is refused too,
         * for the reason `BidVocabulary.SINGLE_OCCURRENCE` gives — de-duplicating means deciding
         * which duplicates count as identical, which is §4.3's normalisation question again with no
         * rule to appeal to.
         *
         * **Not on a `kind:14`**, and that exclusion is §7.4's rather than a convenience. §7.4 puts
         * chat outside its required-tag rule — "an implementation MAY carry `order` on a `kind:14` and
         * MUST NOT derive anything from its presence or absence" — and this codec reads neither tag
         * there, so on a chat they are unknown tags and §4.3's "unknown tags MUST be ignored on
         * read" is what governs. §4.3's own rationale does not reach them either: two
         * implementations cannot disagree about a value neither of them decodes. Refusing a chat
         * for carrying two `order` tags would be this library making a conformant peer look broken.
         */
        private fun refuseDuplicates(kind: RumorKind, tags: List<List<String>>) {
            if (kind == RumorKind.CHAT) return
            for (name in ChannelVocabulary.SINGLE_OCCURRENCE) {
                val count = tags.count { it[0] == name }
                if (count > 1) {
                    throw ChannelException(
                        ChannelRejection.DUPLICATE_TAG,
                        name,
                        "§7.4 fixes what `$name` carries on a rumor and this event carries $count " +
                            "of them; §4.3's rationale applies unchanged — resolving it by first, " +
                            "last or smallest would let two implementations disagree about what " +
                            "this message binds to",
                    )
                }
            }
        }

        /**
         * §7.4's `type`, read in §4.4's strict canonical decimal form.
         *
         * Strict rather than permissive, because a `type` is a discriminator: `"01"`, `"+1"` and
         * `"1.0"` are refused rather than read as `1`, exactly as §4.2's coordinate kind is, so two
         * implementations cannot end up disagreeing about what a signed message *was*.
         */
        private fun readTypeNumber(tags: List<List<String>>): Int {
            val tag = tags.firstOrNull { it[0] == ChannelVocabulary.TYPE }
                ?: throw missing(RumorKind.ORDER_MESSAGE, ChannelVocabulary.TYPE)
            if (tag.size < VALUE_ELEMENTS) throw missing(RumorKind.ORDER_MESSAGE, ChannelVocabulary.TYPE)
            val number = try {
                readStrictDecimal(
                    tag[1],
                    "a `${ChannelVocabulary.TYPE}` value",
                    TagRejection.MALFORMED_NUMBER,
                )
            } catch (refused: TagException) {
                throw ChannelException(
                    ChannelRejection.MALFORMED_TYPE,
                    ChannelVocabulary.TYPE,
                    "§4.3 and §4.4 fix a `${ChannelVocabulary.TYPE}` value as canonical decimal — " +
                        "no sign, no leading zeros, no decimal point — and §4.3 requires rejecting " +
                        "a malformed value rather than repairing it into the nearest legal one",
                    refused,
                )
            }
            if (number > Int.MAX_VALUE) {
                throw ChannelException(
                    ChannelRejection.MALFORMED_TYPE,
                    ChannelVocabulary.TYPE,
                    "§7.4's `${ChannelVocabulary.TYPE}` is a small assigned number and this one " +
                        "does not fit in 32 bits",
                )
            }
            return number.toInt()
        }

        /**
         * §7.4's `order` requirement and its single exception, together, because reading them apart
         * is how an implementer inverts one of them.
         */
        private fun readOrder(
            kind: RumorKind,
            message: OrderMessageKind?,
            tags: List<List<String>>,
        ): OrderId? {
            // §7.4 puts kind:14 outside the `order` requirement and forbids deriving anything from
            // the tag's presence or absence — so it is not looked for here at all, let alone read.
            // Returning before the search is what makes the code say what AttributedRumor.Chat does.
            if (kind == RumorKind.CHAT) return null
            val tag = tags.firstOrNull { it[0] == ChannelVocabulary.ORDER }
            if (message != null && !message.carriesOrderId) {
                if (tag != null) {
                    throw ChannelException(
                        ChannelRejection.FORBIDDEN_ORDER_TAG,
                        ChannelVocabulary.ORDER,
                        "§7.4: a kind:${kind.kind} `${ChannelVocabulary.TYPE}=${message.type}` is " +
                            "the one message that MUST NOT carry `${ChannelVocabulary.ORDER}`, " +
                            "because no order id exists until the buyer proposes",
                    )
                }
                return null
            }
            val value = tag?.getOrNull(VALUE_INDEX) ?: throw missing(kind, ChannelVocabulary.ORDER)
            // Read through T5's order-id codec rather than a second 64-hex reader written here:
            // §4.3's accept-and-normalise rule is implemented there once, and §12 item 11 requires
            // the value be held in the type that redacts it from every string representation.
            return try {
                OrderId.ofHex(value)
            } catch (refused: SeamException) {
                throw ChannelException(
                    when (refused.reason) {
                        SeamRejection.WRONG_LENGTH -> ChannelRejection.ORDER_ID_WRONG_LENGTH
                        SeamRejection.NOT_HEX -> ChannelRejection.ORDER_ID_NOT_HEX
                        // Neither can be raised by a hex read; mapped rather than dropped so a
                        // constant added to SeamRejection later cannot fall through to a wrong one.
                        SeamRejection.RANDOMNESS_UNAVAILABLE,
                        SeamRejection.RANDOMNESS_WRONG_LENGTH,
                        -> ChannelRejection.MALFORMED_TAG
                    },
                    ChannelVocabulary.ORDER,
                    "§7.4 fixes an order id as 32 bytes, 64 hex characters, and §4.3 requires " +
                        "rejecting a value of the wrong length or the wrong alphabet rather than " +
                        "padding or truncating it",
                    refused,
                )
            }
        }

        /**
         * §7.4's collision rule for the two `type` values Nenya assigned in somebody else's
         * vocabulary.
         *
         * Only those two: §4.5's general "ignore an event whose version I do not implement" is the
         * caller's decision and the tag codec deliberately leaves it there. This one is §7.4's own
         * MUST, and it exists because a future GammaMarkets `type=5` would otherwise be read here
         * as a delivery commitment.
         */
        private fun checkVersionCollision(message: OrderMessageKind?, version: Int?) {
            if (message == null || !message.versionDisambiguated) return
            if (version != NenyaProtocol.VERSION) {
                throw ChannelException(
                    ChannelRejection.UNSUPPORTED_VERSION,
                    ChannelVocabulary.VERSION,
                    "§7.4: `${ChannelVocabulary.TYPE}=${message.type}` is Nenya's own assignment in " +
                        "a vocabulary Nenya does not own, so an implementation MUST ignore one that " +
                        "does not carry a `${ChannelVocabulary.VERSION}` version it implements and " +
                        "MUST NOT interpret a foreign one as a delivery commitment or a bid",
                )
            }
        }

        private fun missing(kind: RumorKind, tag: String): ChannelException = ChannelException(
            ChannelRejection.MISSING_REQUIRED_TAG,
            tag,
            "§7.4 marks `$tag` MUST on a kind:${kind.kind} rumor and this event carries no " +
                "readable one",
        )

        /** A tag carries a name and a value; anything shorter carries no value at all. */
        private const val VALUE_ELEMENTS: Int = 2

        /** The value sits second, after the name. */
        private const val VALUE_INDEX: Int = 1

        /**
         * Everything the two implementations share, so the fields a rumor carries are declared
         * once. Private, and abstract, so it is not a door either.
         */
        private abstract class Decoded(
            final override val author: String,
            final override val id: EventId,
            private val event: WireEvent,
            final override val kind: RumorKind,
            final override val tags: TagSet,
            final override val attribution: Attribution,
        ) : AttributedRumor {

            final override val createdAt: Long get() = event.createdAt

            final override val content: String get() = event.content

            final override val notPerformedHere: Set<SeamCapability> get() = NOT_PERFORMED_HERE

            final override val nenyaVersion: Int? get() = tags.nenyaVersion

            final override val counterparties: List<PubkeyRef> get() = tags.pubkeyRefs

            final override val unknownTags: List<List<String>> get() = tags.unknownTags

            final override fun encode(): WireEvent =
                WireEvent(event.pubkey, event.createdAt, kind.kind, tags.republish(), event.content)

            /**
             * Names the kind and the attribution mode, and no identifier of any kind.
             *
             * §12 item 11 and STOP RULE 14 name the order id alongside key material and preimages
             * as values that MUST NOT appear in the string representation of anything this library
             * exposes, and §12 item 2 adds the counterparty pubkey. This type transitively holds
             * both. `TagSet.toString` redacts for the same reason, so delegating to it is safe.
             */
            override fun toString(): String =
                "AttributedRumor(kind=${kind.name}, attribution=${attribution.name}, tags=$tags)"
        }

        /** §7.4's `kind:14`. Declares no order id and no `type`; see [Chat]. */
        private class ChatRumor(
            author: String,
            id: EventId,
            event: WireEvent,
            kind: RumorKind,
            tags: TagSet,
            attribution: Attribution,
        ) : Decoded(author, id, event, kind, tags, attribution), Chat

        /** §7.4's `kind:15`, `kind:16` and `kind:17`. */
        private class BoundRumor(
            author: String,
            id: EventId,
            event: WireEvent,
            kind: RumorKind,
            tags: TagSet,
            attribution: Attribution,
            override val type: OrderMessageKind?,
            override val order: OrderId?,
        ) : Decoded(author, id, event, kind, tags, attribution), Bound {

            /** The `type` is a kind of message and carries no identifier; the order id is not named. */
            override fun toString(): String =
                "AttributedRumor(kind=${kind.name}, type=${type?.name}, " +
                    "attribution=${attribution.name}, tags=$tags)"
        }
    }
}
