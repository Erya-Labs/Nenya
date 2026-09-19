package dev.eryalabs.nenya.settlement

import dev.eryalabs.nenya.JdkRandom
import dev.eryalabs.nenya.channel.Acceptance
import dev.eryalabs.nenya.channel.AttributedRumor
import dev.eryalabs.nenya.channel.ChannelFixtures
import dev.eryalabs.nenya.channel.ChannelTags
import dev.eryalabs.nenya.channel.ChannelVocabulary
import dev.eryalabs.nenya.channel.OrderProposal
import dev.eryalabs.nenya.channel.OrderStatusMessage
import dev.eryalabs.nenya.channel.ProposalFixtures
import dev.eryalabs.nenya.money.FeeSplit
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.payment.PaymentFixtures
import dev.eryalabs.nenya.payment.PaymentHash
import dev.eryalabs.nenya.payment.Preimage
import dev.eryalabs.nenya.seam.NenyaClock
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.tag.TagFixtures
import kotlin.test.fail

/**
 * The generator behind every §8.6 and §9.2 fixture in this package.
 *
 * ### Every invoice here is a **real** one, derived from a vendored example
 *
 * [invoice] takes the vendored BOLT-11 example [BASE] — read through [Bolt11Examples], which is
 * externally authored and whose SHA-256 `PROVENANCE.md` records — and changes exactly three things:
 * the human-readable part's amount, the 35-bit timestamp, and the 256 bits of the `p` field. The
 * bech32 checksum is then **recomputed** by [Bolt11Composer], which `Bolt11ComposerTest` proves
 * rebuilds every vendored example character for character. So the result parses, its amount is the
 * one the fixture asked for, its payment hash is the SHA-256 of the fixture's own preimage, and its
 * expiry is measured against [ACCEPTED_AT].
 *
 * That is decision D, and it replaced something weaker. T15's generator produced strings that were
 * BOLT-11-*shaped* and nothing more — wrong checksums, noise for tagged fields, no readable amount
 * or expiry — which was sufficient while §9.2 check 1 was a byte comparison over an opaque string
 * and became insufficient the moment checks 4 and 5 read the stored invoice. It is gone rather than
 * kept for the tests that did not need more: an invoice-shaped string is exactly how somebody talks
 * themselves into parsing one, and `InvoiceLiteralSweepTest` now fails the build if any string
 * literal anywhere under either test source root looks like a BOLT-11 invoice.
 *
 * ### Nothing else here is typed either
 *
 * The queue's Definition of done forbids an encoded value appearing in a test as something somebody
 * wrote out. The order ids are SHA-256 digests of a per-index label computed by the platform's own
 * SHA-256 (`ChannelFixtures`), the pubkeys are real BIP-340 keys read out of the vendored,
 * externally-authored `bip340-vectors.csv` (`TagFixtures.uppercasePubkeys`), the preimages come
 * from `PaymentFixtures`' pinned generator and their payment hashes are computed, and every event
 * id under test is computed inside T8. A reviewer can change [SEED], re-run the suite, and every
 * property must still hold.
 *
 * ### The one fixture nobody here authored
 *
 * `TagFixtures.uppercasePubkeys` doubles as this package's free negative fixture: the `public key`
 * column of the BIP-340 vectors is 64-character **uppercase** hex, which is refused as a `payment`
 * reference for four independent reasons — it carries uppercase, it does not begin `ln`, its data
 * part carries characters outside the bech32 alphabet, and it is far shorter than Appendix C's
 * timestamp and signature together. `PaymentRequestCodecTest` asserts the first and then feeds the
 * lowercased form back in to reach the others, so the four are shown to be independent rather than
 * claimed to be.
 */
internal object SettlementFixtures {

    /**
     * Pinned so a failure is reproducible. `java.util.Random`'s algorithm ([JdkRandom], identical on
     * the JVM and JavaScript) rather than `kotlin.random`, for the reason `WireFixtures` gives.
     */
    const val SEED: Long = 20260923L

    /** T9's fixed, non-round `created_at`, shared with every other rumor fixture. */
    const val CREATED_AT: Long = ChannelFixtures.CREATED_AT

    /**
     * The clock reading a request is accepted at, in unix seconds — §17 item 6's second value.
     *
     * A fixed, non-round number a codec that recorded a constant could not hide inside, and
     * deliberately *not* [CREATED_AT]: §4.6 says a counterparty's `created_at` orders nothing, and a
     * fixture where the two were equal could not tell a store that recorded the clock from one that
     * recorded the event's claim.
     */
    const val ACCEPTED_AT: Long = 1_767_311_117L

    /** §4.5's version token this implementation implements, as it appears in a tag. */
    val VERSION: String = ChannelFixtures.VERSION

    /** §7.4's `type` number for a payment request, read off T9's constants and never written here. */
    val PAYMENT_REQUEST_TYPE: String = NenyaKind.OrderMessageType.PAYMENT_REQUEST.toString()

    /** A price well above the point at which a 250 bps fee rounds to a non-zero amount (§8.3). */
    const val PRICE_MSAT: Long = 90_000_000L

    /** §7.5's example fee: `250` basis points, which is 2.5%. */
    const val BASIS_POINTS: Int = 250

    /**
     * How long before [ACCEPTED_AT] a derived invoice is stamped.
     *
     * Fixed, non-round and small, so that §9.2 check 5 passes on every default fixture with room to
     * spare: `timestamp + expiry` lands [INVOICE_EXPIRY_SECONDS] − this many seconds *after* the
     * acceptance reading. A check-5 control that wants the boundary states its own clock.
     */
    const val INVOICE_AGE_SECONDS: Long = 137L

    /** The `x` every derived invoice carries. Appendix C's own default, stated rather than omitted. */
    const val INVOICE_EXPIRY_SECONDS: Long = 3_600L

    /** Keeps the generated provider, fee recipient and buyer distinct at every index. */
    private const val PROVIDER_OFFSET: Int = 1
    private const val FEE_RECIPIENT_OFFSET: Int = 2

    /** Clear of all three, so [stranger] is nobody's key on the order it is used against. */
    private const val STRANGER_OFFSET: Int = 7

    /** Tag names §5.3 does not name and §8.6 does not read, so §4.3's unknown-tag rule applies. */
    private val UNKNOWN_NAMES: List<String> = listOf("client", "zap", "relays", "x-nenya-experiment")

    /** Appendix C's `x` tagged field, whose absence means its default of 3600. */
    private const val EXPIRY_FIELD: Char = 'x'

    /** Appendix C's `p`, the field every derivation here replaces. */
    private const val PAYMENT_HASH_FIELD: Char = 'p'

    /** Appendix C's `s`, `d` and `h` — required to be there, and never touched. */
    private const val PAYMENT_SECRET_FIELD: Char = 's'
    private const val DESCRIPTION_FIELD: Char = 'd'
    private const val DESCRIPTION_HASH_FIELD: Char = 'h'

    /** A real BIP-340 public key, in §4.3's canonical lowercase. */
    fun pubkey(index: Int): String = uppercasePubkey(index).lowercase()

    /**
     * The same key in the vendored file's own **uppercase** spelling — §4.3's read rule, live.
     *
     * Read out of [TagFixtures.distinctPubkeys] and not out of the raw column, for the reason
     * `ProposalFixtures.raw` gives: the file reuses one key across eight rows, and since revision
     * `1.5` a fixture whose buyer and provider are the same key is refused by §7.6 itself. The two
     * generators must index the same list, because an order's buyer and provider are drawn there
     * and its `payee` tags and seals here.
     */
    fun uppercasePubkey(index: Int): String {
        val keys = TagFixtures.distinctPubkeys
        return keys[((index % keys.size) + keys.size) % keys.size]
    }

    /** §7.4's order id for [index], a digest and never a typed string. */
    fun orderHex(index: Int): String = ChannelFixtures.orderHexFor(index)

    /**
     * The vendored example every invoice in this package is derived from.
     *
     * Chosen by **what it carries** rather than by position, and then held to it: the first
     * all-lowercase valid example whose tagged fields are exactly one correct-length `p`, exactly
     * one `s`, and exactly one of `d` and `h`. Those three conditions are what the derivations
     * below need — [Bolt11Parts.withPaymentHash] replaces the *first* `p` whatever its length, so a
     * base like the vendored "fields which must be ignored" example would have it write into a
     * wrong-length field and leave the real payment hash alone. Picking by index would have made
     * that a silent property of which example happened to come first.
     *
     * Failing loudly rather than falling back is deliberate: a base that stopped satisfying these
     * conditions would produce invoices that still parse and no longer say what the fixture meant.
     */
    private val BASE: Bolt11Parts by lazy {
        Bolt11Examples.extract()
            .filter { it.group == Bolt11Examples.Group.VALID && it.invoice == it.invoice.lowercase() }
            .map { Bolt11Composer.decompose(it.invoice) }
            .firstOrNull { parts ->
                parts.fields(PAYMENT_HASH_FIELD).singleOrNull()?.dataLength == Bolt11Composer.HASH_GROUPS &&
                    parts.fields(PAYMENT_SECRET_FIELD).size == 1 &&
                    parts.fields(DESCRIPTION_FIELD).size + parts.fields(DESCRIPTION_HASH_FIELD).size == 1
            }
            ?: fail(
                "no all-lowercase valid example in ${Bolt11Examples.PATH} carries exactly one " +
                    "correct-length `$PAYMENT_HASH_FIELD`, one `$PAYMENT_SECRET_FIELD` and one of " +
                    "`$DESCRIPTION_FIELD`/`$DESCRIPTION_HASH_FIELD`, so there is no base a " +
                    "derivation here can safely replace a payment hash in",
            )
    }

    /**
     * §8.6's `<bolt11>` for one fixture: a real invoice, derived from [BASE] (decision D).
     *
     * @param preimageHex the preimage the matching receipt will carry. Its SHA-256 becomes the
     *   invoice's `p` field, so the stored invoice and the receipt agree about which payment
     *   settles it. Nothing in the library reads that field yet — §9.2 check 3's operand is still
     *   a parameter — and setting it anyway is what keeps these fixtures honest ahead of the task
     *   that closes the provenance.
     * @param amount what this payee is owed, or `null` for BOLT-11's "any amount" form, which §9.2
     *   check 4 refuses. There is no third case: an amount of zero is not expressible in BOLT-11.
     * @param timestamp Appendix C's 35-bit timestamp. Defaults to [INVOICE_AGE_SECONDS] before
     *   [ACCEPTED_AT], so check 5 passes with room to spare on every fixture that does not state
     *   otherwise.
     * @param expirySeconds the `x` field, or `null` to carry none at all — which is a real case
     *   and not an omission: Appendix C then reads the expiry as its default of 3600.
     */
    fun invoice(
        preimageHex: String,
        amount: Msat?,
        timestamp: Long = ACCEPTED_AT - INVOICE_AGE_SECONDS,
        expirySeconds: Long? = INVOICE_EXPIRY_SECONDS,
    ): String = invoiceWithExpiryGroups(
        preimageHex,
        amount,
        timestamp,
        expirySeconds?.let { expiryGroups(it) },
    )

    /**
     * The same, with the `x` field's five-bit groups given directly — or `null` for no `x` at all.
     *
     * For the two controls that live above what [invoice] can write: an expiry of `2^63`, the first
     * value a signed `Long` cannot hold, and one of `2^64 − 1`, the largest Appendix C admits. Both
     * need a thirteen-group field, and both are about what T22's **saturation** does with one, so
     * the fixture has to be able to say "these groups" rather than "this number".
     */
    fun invoiceWithExpiryGroups(
        preimageHex: String,
        amount: Msat?,
        timestamp: Long = ACCEPTED_AT - INVOICE_AGE_SECONDS,
        groups: List<Int>?,
    ): String {
        val written = if (amount == null) "" else Bolt11Composer.writtenAmount(amount.millisatoshis)
        val hashed = PaymentFixtures.paymentHashOf(Preimage.ofHex(preimageHex)).bytes()
        val base = BASE
            .withAmount(written)
            .withTimestamp(timestamp)
            .withPaymentHash(hashed)
            .withoutField(EXPIRY_FIELD)
        return Bolt11Composer.compose(
            if (groups == null) base else base.plusField(EXPIRY_FIELD, groups),
        )
    }

    /**
     * [seconds] as the fewest five-bit groups that hold it.
     *
     * Appendix C fixes no width for an `x`, and the vendored examples write each one in the
     * narrowest field that fits, so this does too — a fixture padded to a constant width would be a
     * shape no real issuer emits and would quietly stop exercising the parser's leading-zero skip.
     *
     * Bounded at twelve groups, which is 60 bits: past that the value needs a thirteenth group and
     * [Bolt11Composer.groupsOfNumber] is not the tool. The two controls that live up there — an `x`
     * of 2^63 and one of 2^64 − 1 — are built group by group in the test that needs them, beside
     * the assertion about what they mean.
     */
    private fun expiryGroups(seconds: Long): List<Int> {
        var size = 1
        while (size < MAX_EXPIRY_GROUPS && seconds >= (1L shl (BITS_PER_GROUP * size))) size++
        if (seconds >= (1L shl (BITS_PER_GROUP * MAX_EXPIRY_GROUPS))) {
            fail("an `$EXPIRY_FIELD` of $seconds needs more than $MAX_EXPIRY_GROUPS groups; build it directly")
        }
        return Bolt11Composer.groupsOfNumber(seconds, size)
    }

    /** A bech32 group is five bits. */
    private const val BITS_PER_GROUP: Int = 5

    /** The widest field [Bolt11Composer.groupsOfNumber] writes, which is 60 bits. */
    private const val MAX_EXPIRY_GROUPS: Int = 12

    /**
     * §9.2 check 4's expected amount for [payee] under [split] — `price_msat` or §8.3's `fee_msat`.
     *
     * The fixtures' own copy of the rule `Settlement` applies, and it is a duplicate on purpose: a
     * fixture that read the expected amount through the library's own helper would build an invoice
     * for whatever that helper said, and check 4 would then compare a value against itself. Two
     * independent readings of §9.2's one sentence is what makes the comparison mean anything.
     */
    fun amountFor(payee: Payee, split: FeeSplit): Msat = when (payee) {
        Payee.PROVIDER -> split.price
        Payee.FEE -> split.fee
    }

    /** §8.3's split for an order at [priceMsat] under [basisPoints], or an absent fee term. */
    fun split(priceMsat: Long = PRICE_MSAT, basisPoints: Int? = BASIS_POINTS): FeeSplit {
        val term = if (basisPoints == null) FeeTerm.Absent else FeeTerm.of(basisPoints)
        return term.splitOn(Msat.ofMsat(priceMsat))
    }

    /** §8.6's `["payee", …]` tag for [role], naming a real key on the fee side. */
    fun payeeTag(role: Payee, index: Int = 0): List<String> = when (role) {
        Payee.PROVIDER -> listOf(SettlementVocabulary.PAYEE, role.token)
        Payee.FEE -> listOf(SettlementVocabulary.PAYEE, role.token, pubkey(index + FEE_RECIPIENT_OFFSET))
    }

    /**
     * §8.6's worked `type=2`, with every element defaulted to the shape the document prints.
     *
     * Mutable, so a control can break exactly one thing about it.
     */
    fun requestTags(
        index: Int = 0,
        order: String? = orderHex(index),
        payment: List<String>? = requestPaymentTag(defaultInvoice()),
        payeeTag: List<String>? = SettlementFixtures.payeeTag(Payee.PROVIDER, index),
        version: String? = VERSION,
        type: String? = PAYMENT_REQUEST_TYPE,
        fee: List<String>? = null,
    ): MutableList<List<String>> {
        val tags = mutableListOf<List<String>>()
        if (order != null) tags += listOf(ChannelTags.ORDER, order)
        if (type != null) tags += listOf(ChannelTags.TYPE, type)
        tags += listOf(ChannelVocabulary.COUNTERPARTY, pubkey(index + PROVIDER_OFFSET))
        if (payment != null) tags += payment
        if (payeeTag != null) tags += payeeTag
        // §8.4 makes it REQUIRED on a `payee=fee` request and OPTIONAL on a `payee=provider` one,
        // so it is defaulted absent here and supplied by whichever test is about §8.4.
        if (fee != null) tags += fee
        if (version != null) tags += listOf(ChannelVocabulary.VERSION, version)
        return tags
    }

    /** §9.2's worked `kind:17`, in the tag order the example prints. Mutable, for the same reason. */
    fun receiptTags(
        index: Int = 0,
        order: String? = orderHex(index),
        payment: List<String>? = null,
        payeeTag: List<String>? = SettlementFixtures.payeeTag(Payee.PROVIDER, index),
        version: String? = VERSION,
        fee: List<String>? = null,
    ): MutableList<List<String>> {
        val tags = mutableListOf<List<String>>()
        if (order != null) tags += listOf(ChannelTags.ORDER, order)
        tags += listOf(ChannelVocabulary.COUNTERPARTY, pubkey(index + PROVIDER_OFFSET))
        if (payment != null) tags += payment
        if (payeeTag != null) tags += payeeTag
        if (fee != null) tags += fee
        if (version != null) tags += listOf(ChannelVocabulary.VERSION, version)
        return tags
    }

    /** §9.2's `["payment", "<medium>", "<reference>", "<proof>"]`, assembled from its parts. */
    fun paymentTag(reference: String, proof: String, medium: String = PaymentMedium.LIGHTNING.token!!):
        List<String> = listOf(SettlementVocabulary.PAYMENT, medium, reference, proof)

    /** §8.6's `["payment", "<medium>", "<reference>"]` — a request carries no proof. */
    fun requestPaymentTag(reference: String, medium: String = PaymentMedium.LIGHTNING.token!!):
        List<String> = listOf(SettlementVocabulary.PAYMENT, medium, reference)

    /**
     * The invoice [requestTags] and [receiptTags] use when a caller names none: a provider invoice
     * for [split]'s price, against the first preimage [PaymentFixtures] draws.
     *
     * A single value rather than a fresh derivation per call, because a great many controls build
     * two requests and compare them, and a default that differed between two calls would make
     * "these name the same invoice" accidentally false.
     */
    fun defaultInvoice(): String = DEFAULT_INVOICE

    private val DEFAULT_INVOICE: String by lazy {
        invoice(PaymentFixtures.preimageHex(1).single(), amountFor(Payee.PROVIDER, split()))
    }

    /** The buyer at [index] — the key `bound` attributes a rumor to unless told otherwise. */
    fun buyer(index: Int = 0): String = pubkey(index)

    /** The provider at [index], the `p` counterparty of every fixture here. */
    fun provider(index: Int = 0): String = pubkey(index + PROVIDER_OFFSET)

    /** The fee recipient at [index] — §8.1's third `fee` element and §8.7's required seal. */
    fun feeRecipient(index: Int = 0): String = pubkey(index + FEE_RECIPIENT_OFFSET)

    /**
     * A key that is none of the three parties to the order at [index].
     *
     * Every revision `1.5` sender rule has two attackers and they are different people: the
     * counterparty overstepping its role — a buyer accepting its own order, a provider forwarding
     * the fee recipient's invoice — and a stranger who simply saw the order id. A fixture that only
     * ever tried the first would pass over a check that compared against "not the buyer".
     */
    fun stranger(index: Int = 0): String = pubkey(index + STRANGER_OFFSET)

    /** [tags] as a `kind:16` rumor attributed to the key that authored it — §7.2's positive case. */
    fun bound(tags: List<List<String>>, kind: Int = NenyaKind.ORDER_MESSAGE, index: Int = 0):
        AttributedRumor.Bound = boundSealedBy(buyer(index), tags, kind)

    /**
     * [tags] as a rumor whose **seal** is [author], which §7.2 makes the key every term is
     * attributed to.
     *
     * The rumor's own `pubkey` is the same key, so §7.2's equality holds and every control built
     * on this is about §8.7 rather than about §7.2 — which `ChannelCodecTest` covers. §8.7's rule
     * is that the *fee recipient's* key is the one a fee invoice may arrive under, so a fixture
     * that could not choose the sealing key could not state the rule at all.
     */
    fun boundSealedBy(author: String, tags: List<List<String>>, kind: Int = NenyaKind.ORDER_MESSAGE):
        AttributedRumor.Bound {
        val rumor = AttributedRumor.attribute(author, ChannelFixtures.checked(author, kind, tags))
        return rumor as? AttributedRumor.Bound
            ?: fail("a kind:$kind must decode to a bound rumor, not to $rumor")
    }

    /** [tags] decoded as §8.6's payment request, through T8's and T13's doors. */
    fun request(tags: List<List<String>>, split: FeeSplit = split(), index: Int = 0): PaymentRequest =
        PaymentRequest.decode(bound(tags, NenyaKind.ORDER_MESSAGE, index), split)

    /** The same, sealed by [author] — §8.7's operand. */
    fun requestSealedBy(author: String, tags: List<List<String>>, split: FeeSplit = split()): PaymentRequest =
        PaymentRequest.decode(boundSealedBy(author, tags, NenyaKind.ORDER_MESSAGE), split)

    /**
     * The same, sealed by the key §8.6 requires for [payee] on the order at [index] — the provider
     * for a `payee=provider` request (revision `1.5`), the fee recipient for a `payee=fee` one
     * (§8.7).
     *
     * A `when` over the enum and never a default, so a third role added to [Payee] later cannot
     * fall through to "sealed by whoever": the point of these two rules is that each message has
     * exactly one key it may arrive under.
     */
    fun requestFrom(
        payee: Payee,
        tags: List<List<String>>,
        split: FeeSplit = split(),
        index: Int = 0,
    ): PaymentRequest = requestSealedBy(
        when (payee) {
            Payee.PROVIDER -> provider(index)
            Payee.FEE -> feeRecipient(index)
        },
        tags,
        split,
    )

    /** §8.1's `fee` tag for [index], read through the proposal fixtures so the two cannot diverge. */
    fun feeTag(index: Int = 0, basisPoints: Int = BASIS_POINTS): List<String> =
        ProposalFixtures.feeTag(index, basisPoints)

    /**
     * §7.6's **checked** acceptance for the order at [index], together with §8.4's two points that
     * precede any `type=2` for it.
     *
     * Every value `AcceptedPaymentRequest.accept` now derives its checks from arrives through this
     * one object, and each half comes out of a real codec rather than out of a constructor: the
     * proposal is sealed by the buyer, the `type=3` by the provider, and
     * [OrderProposal.accepts] is what mints the [acceptance]. `Acceptance.Accepted`'s constructor is
     * `internal` and therefore reachable from this source set — building one directly would make
     * every store test pass with §7.6's own sender check deleted, which is half of what T24 closes.
     */
    class Accepted internal constructor(

        /** §8.4 point 1, and the message whose terms the acceptance repeats. */
        val proposal: OrderProposal,

        /** §8.4 point 2 — the `type=3`, sealed by the provider. */
        val update: OrderStatusMessage,

        /** What [OrderProposal.accepts] answered, checked against the resolved provider key. */
        val acceptance: Acceptance.Accepted,
    ) {

        /** §8.4's two REQUIRED points that precede every `type=2`, in message order. */
        val feeTermPoints: List<FeeTermSighting> = listOf(
            FeeTermSighting.onProposal(proposal),
            FeeTermSighting.onAcceptance(update),
        )

        /** Names neither key nor order (§12 items 2 and 11). */
        override fun toString(): String = "SettlementFixtures.Accepted(redacted)"
    }

    /**
     * [Accepted] for the order at [index], priced at [priceMsat] under [basisPoints] — or under no
     * `fee` tag at all when that is `null`, which §8.1's read rule makes a signed zero.
     *
     * The order id is [orderHex]'s, so a request built by [requestTags] at the same index names the
     * same order; the provider is [provider]'s, so a request built by [requestFrom] at the same
     * index is sealed by the key this acceptance was checked against; and the fee recipient is
     * [feeRecipient]'s, because [feeTag] and [payeeTag] read the same offset.
     */
    fun accepted(
        index: Int = 0,
        priceMsat: Long = PRICE_MSAT,
        basisPoints: Int? = BASIS_POINTS,
    ): Accepted {
        val proposalTags = ProposalFixtures.proposalTags(
            index = index,
            amountMsat = priceMsat.toString(),
            fee = basisPoints?.let { feeTag(index, it) },
        )
        val proposal = ProposalFixtures.proposal(proposalTags, index)
        val update = ProposalFixtures.statusMessage(
            ProposalFixtures.acceptanceTags(proposalTags, index),
            index + PROVIDER_OFFSET,
        )
        val answer = proposal.accepts(update, provider(index))
        return Accepted(
            proposal,
            update,
            answer as? Acceptance.Accepted
                ?: fail("the fixture proposal and its byte-identical type=3 must accept, not $answer"),
        )
    }

    /**
     * [Accepted] for the order at [index] whose accepted terms produce exactly [split].
     *
     * Derived from the split rather than restated beside it, because `AcceptedPaymentRequest.accept`
     * now reads §9.2 check 4's expected amount off `acceptance.terms.split`: a fixture that named
     * the price twice could have a request checked against one figure and a receipt against
     * another, which is a disagreement no control in this suite would attribute correctly.
     *
     * §8.1's two zero shapes are kept apart — `FeeTerm.Absent` is a proposal carrying no `fee` tag
     * and `FeeTerm.Stated(0)` is one carrying `["fee", "0"]` — because §8.4 compares the tag's
     * presence and the two are different signed statements.
     */
    fun acceptedFor(split: FeeSplit, index: Int = 0): Accepted = accepted(
        index,
        split.price.millisatoshis,
        if (split.term == FeeTerm.Absent) null else split.term.basisPoints,
    )

    /**
     * [request] accepted into [into] against [order]'s own §7.6 acceptance and §8.4 points.
     *
     * The one-line shorthand every call site here would otherwise repeat. It supplies the fee-term
     * points for **both** payees rather than only the fee one, deliberately: `accept` ignores them
     * for a provider request (§8.4 makes the `fee` tag OPTIONAL there and forbids requiring one),
     * and a fixture that decided which payee needed them would be a second copy of the rule under
     * test.
     */
    fun accept(
        request: PaymentRequest,
        into: PaymentRequestStore,
        clock: NenyaClock,
        order: Accepted,
    ): AcceptedPaymentRequest =
        AcceptedPaymentRequest.accept(request, order.acceptance, into, clock, order.feeTermPoints)

    /** [tags] decoded as §9.2's receipt. */
    fun receipt(tags: List<List<String>>, index: Int = 0): PaymentReceipt =
        PaymentReceipt.decode(bound(tags, NenyaKind.RECEIPT, index))

    /** The same, sealed by [author]. */
    fun receiptSealedBy(author: String, tags: List<List<String>>): PaymentReceipt =
        PaymentReceipt.decode(boundSealedBy(author, tags, NenyaKind.RECEIPT))

    /** [tags] with every occurrence of [name] removed. */
    fun without(tags: List<List<String>>, name: String): List<List<String>> =
        tags.filter { it[0] != name }

    /** [tags] with [tag] appended. */
    fun with(tags: List<List<String>>, tag: List<String>): List<List<String>> = tags + listOf(tag)

    /** [tags] with every occurrence of [tag]'s name replaced by [tag]. */
    fun replacing(tags: List<List<String>>, tag: List<String>): List<List<String>> =
        tags.map { if (it[0] == tag[0]) tag else it }

    /**
     * One generated `(request, receipt)` pair and every value drawn for it.
     *
     * The preimage and its payment hash travel together so a property can assert both directions:
     * each receipt verifies against **its own** stored request and hash, and is refused against
     * another's.
     */
    class Fixture(
        val index: Int,
        val payee: Payee,
        val orderHex: String,
        val invoice: String,
        val preimageHex: String,
        val paymentHash: PaymentHash,
        val basisPoints: Int,
        val requestTags: List<List<String>>,
        val receiptTags: List<List<String>>,
    )

    /**
     * [count] **legal** request/receipt pairs from one seeded run, cycling §8.6's two payee roles.
     *
     * Every fixture decodes, because the property being proved is about matching and mismatching
     * rather than about rejection: a corpus that was refused would prove nothing, which is why
     * `SettlementPropertyTest` asserts the whole corpus decodes before it asserts anything else.
     */
    fun pairs(count: Int): List<Fixture> {
        val random = JdkRandom(SEED)
        val preimages = PaymentFixtures.preimageHex(count)
        return List(count) { index -> pair(random, index, preimages[index]) }
    }

    private fun pair(random: JdkRandom, index: Int, preimageHex: String): Fixture {
        // Both roles, alternating rather than drawn, so the corpus cannot end up with none of one.
        val payee = if (index % 2 == 0) Payee.PROVIDER else Payee.FEE
        // §9.2 check 4 now reads this amount, so it is the one this payee is owed under the default
        // split — and the invoices stay distinct across the corpus because each carries the
        // SHA-256 of its own preimage in its `p` field.
        val invoice = invoice(preimageHex, amountFor(payee, split()))
        val order = orderHex(index)
        val payeeTag = payeeTag(payee, index)
        val request = requestTags(
            index = index,
            order = order,
            payment = requestPaymentTag(invoice),
            payeeTag = payeeTag,
            // §8.4 marks the `fee` tag REQUIRED on a fee `type=2` and OPTIONAL on a provider one,
            // and revision `1.5` makes `checkFeePaymentRequest` unskippable for the fee side — so
            // a corpus whose fee requests carried none would be a corpus none of which can be
            // stored. The provider's carries none, because §8.4 forbids requiring one there and a
            // fixture that supplied one anyway would never exercise the branch that drops it.
            fee = if (payee == Payee.FEE) feeTag(index) else null,
        )
        val receipt = receiptTags(
            index = index,
            order = order,
            payment = paymentTag(invoice, preimageHex),
            payeeTag = payeeTag,
        )
        repeat(1 + random.nextInt(2)) {
            val name = UNKNOWN_NAMES[random.nextInt(UNKNOWN_NAMES.size)]
            request += listOf(name, "$index-$it")
            receipt += listOf(name, "$index-$it")
        }
        request.shuffleAll(random)
        receipt.shuffleAll(random)
        return Fixture(
            index = index,
            payee = payee,
            orderHex = order,
            invoice = invoice,
            preimageHex = preimageHex,
            paymentHash = PaymentFixtures.paymentHashOf(Preimage.ofHex(preimageHex)),
            basisPoints = BASIS_POINTS,
            requestTags = request.toList(),
            receiptTags = receipt.toList(),
        )
    }

    /**
     * Reorders the whole list, so the corpus does not test one tag order ten thousand times.
     *
     * §8.6 fixes no tag order and §4.1 forbids *changing* one.
     */
    private fun MutableList<List<String>>.shuffleAll(random: JdkRandom) {
        for (position in size - 1 downTo 1) {
            val other = random.nextInt(position + 1)
            val swap = this[position]
            this[position] = this[other]
            this[other] = swap
        }
    }
}
