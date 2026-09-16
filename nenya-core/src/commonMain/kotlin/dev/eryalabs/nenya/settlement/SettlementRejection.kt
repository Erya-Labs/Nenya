package dev.eryalabs.nenya.settlement

import dev.eryalabs.nenya.channel.ChannelTags
import dev.eryalabs.nenya.payment.PaymentRejection
import dev.eryalabs.nenya.tag.TagRejection

/**
 * Why a §8.6 payment request, or a §9.2 receipt checked against the store, was refused.
 *
 * The reason is part of the API and not merely diagnostic text, following the convention every
 * other package in this library already uses — `MoneyRejection`, `PaymentRejection`,
 * `DeliveryRejection`, `SeamRejection`, `OrderStateRejection`, `WireRejection`, `TagRejection`,
 * `ListingRejection`, `BidRejection`, `ChannelRejection`. Tests assert on these constants rather
 * than on wording.
 *
 * Several exist because §9.2 check 1 and §9.3 give a *reason* and not merely a verdict, and an
 * implementer who collapses them tells the user something false. The three sharpest are
 * [NO_STORED_REQUEST], [INVOICE_NOT_IDENTICAL] and [INVOICE_UPPERCASE_NOT_PERMITTED]: check 1
 * names all three situations separately, and a caller told "mismatch" when nothing was ever
 * stored goes looking for a wrong invoice that does not exist.
 */
public enum class SettlementRejection {

    /**
     * §8.6's decoder was handed a rumor that is not a `kind:16` `type=2`.
     *
     * The caller's mistake rather than the peer's, and its own constant for the reason
     * `ChannelRejection.NOT_A_PROPOSAL` has one: a `type=3` is a perfectly well-formed message
     * this entry point has nothing to say about, and reporting it as malformed would send a
     * reader looking for a bug in the sender's implementation.
     */
    NOT_A_PAYMENT_REQUEST,

    /** §9.2's decoder was handed a rumor that is not a `kind:17`. See [NOT_A_PAYMENT_REQUEST]. */
    NOT_A_RECEIPT,

    /**
     * §8.6 or §9.2 marks a tag MUST on this message and the event carries no readable one.
     * See [SettlementException.tag].
     */
    MISSING_REQUIRED_TAG,

    /**
     * A second `payment` or `payee` tag.
     *
     * §5.3's table contains neither — they are §8.6's, as `order` and `type` are §7.4's — so
     * §4.3's duplicate rule, stated over "any tag **this document** marks with cardinality `1` or
     * `0–1`", does not literally reach them and neither the tag codec nor the channel codec checks
     * them. §8.6 states the cardinality itself, in its own words: a `type=2` "MUST carry exactly
     * one `["payment", "lightning", "<bolt11>"]` tag", and it names exactly one payee role. §4.3's
     * rationale reaches them unchanged and is at its sharpest here — two implementations resolving
     * two `payment` tags by first and by last disagree about which invoice an order was settled
     * against, which is the one comparison §9.2 check 1 exists to make.
     */
    DUPLICATE_TAG,

    /**
     * A `payment` or `payee` tag with the wrong number of elements for the message it is on.
     *
     * §8.6 prints the request's `payment` tag with three elements and §9.2 prints the receipt's
     * with four — `["payment", "<medium>", "<medium-reference>", "<proof>"]` — because a request
     * carries no proof and a receipt is nothing without one. The payee tag has two legal arities
     * and §8.6 pins each to its role: two elements for `provider`, three for `fee`, the third
     * being the fee recipient's pubkey.
     */
    WRONG_ARITY,

    /**
     * §8.6: a `type=2` payment request MUST carry exactly one
     * `["payment", "lightning", "<bolt11>"]` tag.
     *
     * Distinct from [UNKNOWN_PAYEE_ROLE] and from the §9.4 treatment a *receipt* gets, and that
     * asymmetry is the point: §9.4 says a `bitcoin` or `ecash` receipt MAY be parsed and simply
     * evidences nothing, while §8.6 makes the medium of a *request* a MUST. Conflating the two is
     * how a codec either refuses a conformant receipt or stores a request it can never verify.
     */
    MEDIUM_NOT_LIGHTNING,

    /**
     * A `payee` token that is neither of §8.6's two roles.
     *
     * Not mapped onto the nearest known one: §8.6 gives two roles and there is no third, so a
     * request naming one is a message this implementation does not understand rather than a
     * provider invoice with a typo. Same reading §7.4 requires for an unknown `type` and §11.1 for
     * an unknown `status`.
     */
    UNKNOWN_PAYEE_ROLE,

    /** A `["payee", "fee", …]` third element that is not 64 hex characters (§4.3, §8.6). */
    MALFORMED_PAYEE_RECIPIENT,

    /**
     * A payment request for a payee whose expected amount is `0`, which §8.3 and §8.6 say may not
     * exist at all.
     *
     * §8.6: "When that amount is `0`, **no fee payment request exists at all** and any that
     * arrives MUST be rejected (§8.3)." §18's zero-fee probe is the mirror of this on the *receipt*
     * side; this is the request side, where an implementation that accepts one strands the order —
     * it now holds an invoice for a payee no receipt may ever be required from.
     *
     * The same clause reaches a zero **price**, by §9.2's generic "expected amount is non-zero"
     * wording rather than as a case of its own, and the answer is read out of
     * `Payee.requiredPayees` rather than re-derived here.
     */
    PAYEE_NOT_REQUIRED,

    /**
     * A BOLT-11 reference carrying an uppercase character (§4.3, Appendix C).
     *
     * Appendix C: an invoice is "all of it lowercase; a mixed-case invoice is invalid", and §4.3
     * names the BOLT-11 string as one of exactly two values where no normalisation of any kind is
     * permitted. Its own constant rather than [INVOICE_MALFORMED] for the reason
     * `PaymentRejection.UPPERCASE_NOT_PERMITTED` has one: the string *is* hex-ish and well formed,
     * and a caller told "malformed" would go looking for the wrong bug in its own encoder.
     */
    INVOICE_UPPERCASE_NOT_PERMITTED,

    /**
     * §9.3: a static Lightning address — an `lud16` `name@example.com` or an `lnurl1…` string —
     * offered where §8.6 requires a BOLT-11 invoice.
     *
     * Its own constant and never [INVOICE_MALFORMED], because §9.3 refuses these for a reason that
     * is not about their syntax: "A reused address links every order the user has ever settled,
     * and an LNURL fetch discloses the payer's IP to the recipient's server." A peer sending one is
     * conformant with GammaMarkets and not with Nenya, and telling it its invoice is malformed
     * sends it to fix the wrong thing.
     */
    INVOICE_STATIC_ADDRESS,

    /**
     * A BOLT-11 reference that does not have the shape Appendix C describes: no `ln` prefix and
     * network, no `1` separator, a data part carrying a character outside the bech32 set, or a data
     * part too short to hold Appendix C's 7-character timestamp and 104-character signature.
     *
     * **This is a recogniser's answer and not a parser's.** See [Bolt11Reference] for what is and
     * is not checked, and in particular for the bech32 checksum that is not.
     */
    INVOICE_MALFORMED,

    /**
     * The `<proof>` element of a `lightning` receipt is not §9.2 check 2's lowercase 32-byte hex.
     *
     * The precise reason is **not** lost: it is the [PaymentRejection] on the `cause`, which is the
     * `PaymentException` T3's `Preimage.ofHex` threw — [PaymentRejection.UPPERCASE_NOT_PERMITTED]
     * for an `A`–`F`, [PaymentRejection.WRONG_LENGTH] for §18's 31- and 33-byte controls,
     * [PaymentRejection.NOT_HEX] for anything else.
     */
    PREIMAGE_MALFORMED,

    /**
     * The injected [dev.eryalabs.nenya.seam.NenyaClock] answered `Unavailable`, so the moment of
     * acceptance §9.2 check 5 and §17 item 6 require cannot be recorded — and the request is
     * therefore **not accepted at all**.
     *
     * Not a default of "now", which this library has no way to read, and not a stored `null`,
     * which would leave check 5 permanently unperformable against a request that looks stored. §4.6
     * makes the injected clock authoritative for every deadline; a library that invented one here
     * would be recording a time the client never chose against an invoice it will later be asked to
     * judge.
     */
    CLOCK_UNAVAILABLE,

    /**
     * The injected clock reported a time before 1970.
     *
     * §4.3 fixes every timestamp as a non-negative integer, so a clock answering before the epoch
     * is not reporting an unusual time — it is broken, and no acceptance moment can honestly be
     * recorded from it. The same reading, and the same fail-closed answer, as
     * `TransitionRejection.CLOCK_READING_BEFORE_EPOCH` and `ListingActivity.CANNOT_SAY`.
     */
    CLOCK_BEFORE_EPOCH,

    /**
     * §9.2 check 1, second half: "If no such payment request was received and stored, the receipt
     * MUST be rejected."
     *
     * Its own constant, and check 1 names it separately from a mismatch for a reason a caller acts
     * on: told "mismatch" it looks for a wrong invoice; told this, it looks for a `type=2` its
     * store never saw — a receipt for an order nobody asked it to pay.
     */
    NO_STORED_REQUEST,

    /**
     * §9.2 check 1, first half: the BOLT-11 string in the receipt is not **byte-identical** to the
     * stored one. "No normalisation, no case folding, no re-encoding, no bech32 round-trip."
     */
    INVOICE_NOT_IDENTICAL,

    /**
     * An `order` value this codec could not read as §7.4's 32-byte id.
     *
     * Cannot fire on a rumor T13 already decoded — it reads the `order` tag through T5's codec —
     * and is mapped rather than dropped, so a loosening of §7.4's rule there cannot fall through
     * to a wrong reason here.
     */
    ORDER_ID_MALFORMED,

    /**
     * §5.3's Encoding column refused a value this package read through the tag layer.
     *
     * The precise §5.3 reason is the [TagRejection] on the `cause`, which is the `TagException` the
     * tag codec threw.
     */
    MALFORMED_TAG,
}

/**
 * A payment request or a receipt was refused, carrying the [reason] and the [tag] it is about as
 * data.
 *
 * One exception type for the whole settlement surface, as every other package in this library
 * does: a caller has one thing to catch and one field to branch on.
 *
 * **The message never echoes the caller's input**, and here that is §12 rather than convention.
 * §12 item 11 and STOP RULE 14 name the order id alongside key material and preimages, and §12
 * items 1 and 2 put the invoice string and the counterparty pubkey in the same company — every one
 * of which travels through this package. A message may name a tag *name*, a *kind*, a *count*, a
 * *length*, a *role* and a *reason*; it may never name a pubkey, an order id, an invoice or a
 * preimage.
 */
public class SettlementException internal constructor(
    public val reason: SettlementRejection,

    /** The §8.6, §9.2 or §5.3 tag this refusal is about, or `null` where the rule names no tag. */
    public val tag: String?,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/**
 * §8.6's and §9.2's tag names and tokens, kept **out** of `NenyaTags` on purpose.
 *
 * `NenyaTags` is held *equal* to §5.3's table parsed at test time, and neither `payment` nor
 * `payee` is a row in it — they are §8.6's, exactly as `order` and `type` are §7.4's and live in
 * `ChannelTags`. Adding one to `NenyaTags` would turn `TagVocabularyTest` red for a reason that has
 * nothing to do with the tag: the table would no longer equal the document.
 *
 * The two **role** tokens are not written here either. They are `Payee.PROVIDER.token` and
 * `Payee.FEE.token`, which T3 declared from §8.6 and which this package MUST NOT re-declare — a
 * wire constant duplicated across a codebase is how two call sites end up disagreeing (§5.2).
 *
 * Pure values: no clock, no randomness, no I/O.
 */
internal object SettlementVocabulary {

    /** §8.6's and §9.2's `["payment", "<medium>", "<medium-reference>", "<proof>"]`. */
    const val PAYMENT: String = "payment"

    /** §8.6's `["payee", "provider"]` / `["payee", "fee", "<pubkey>"]`. */
    const val PAYEE: String = "payee"

    /** §7.4's `order` row, read through `ChannelTags` rather than spelled a second time. */
    const val ORDER: String = ChannelTags.ORDER

    /** The tags whose second occurrence is a rejection here, because no layer below reaches them. */
    val SINGLE_OCCURRENCE: List<String> = listOf(PAYMENT, PAYEE)

    /** A tag's name sits first. */
    const val NAME_INDEX: Int = 0

    /** `["payment", "<medium>", …]` — the medium is the second element. */
    const val MEDIUM_INDEX: Int = 1

    /** `["payment", "<medium>", "<medium-reference>", …]` — the reference is the third. */
    const val REFERENCE_INDEX: Int = 2

    /** `["payment", …, "<proof>"]` on a `kind:17` — the proof is the fourth (§9.2). */
    const val PROOF_INDEX: Int = 3

    /** `["payee", "<role>"]` — the role token is the second element. */
    const val ROLE_INDEX: Int = 1

    /** `["payee", "fee", "<fee-recipient-pubkey-hex>"]` — the recipient is the third. */
    const val RECIPIENT_INDEX: Int = 2

    /** §8.6's request form: name, medium, reference. No proof — nothing has been paid yet. */
    const val PAYMENT_ELEMENTS_REQUEST: Int = 3

    /** §9.2's receipt form: name, medium, reference, proof. */
    const val PAYMENT_ELEMENTS_RECEIPT: Int = 4

    /** §8.6's `["payee", "provider"]`. */
    const val PAYEE_ELEMENTS_PROVIDER: Int = 2

    /** §8.6's `["payee", "fee", "<fee-recipient-pubkey-hex>"]`. */
    const val PAYEE_ELEMENTS_FEE: Int = 3
}
