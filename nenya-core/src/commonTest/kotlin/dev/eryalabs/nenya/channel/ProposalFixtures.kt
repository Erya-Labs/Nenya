package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.JdkRandom
import dev.eryalabs.nenya.NenyaProtocol
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.order.OrderState
import dev.eryalabs.nenya.order.OrderStatusCodec
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.tag.TagFixtures
import kotlin.test.fail

/**
 * The generator behind every §7.5 and §7.6 fixture.
 *
 * This is not a scratch file. The queue's Definition of done forbids an encoded value appearing in a
 * test as something somebody typed out, and nothing here is: the public keys are real BIP-340 keys
 * read out of the vendored, externally-authored `bip340-vectors.csv` through
 * [TagFixtures.uppercasePubkeys] — which is also why they arrive **uppercase**, making every one of
 * them §4.3's accept-and-normalise control as well as a fixture — the order ids are SHA-256 digests
 * of a per-index label computed by the platform's own SHA-256, and every event id under test is
 * computed inside T8. A reviewer can change [SEED], re-run the suite, and every property must still
 * hold.
 *
 * ### Why §7.5's own example values are not used
 *
 * §7.5 prints `<provider-pubkey-hex>`, `<buyer-pubkey-hex>` and `<fee-recipient-pubkey-hex>`. They
 * are placeholders: none is 64 hex characters, so none survives §4.3 and none can be fed to this
 * codec. `Section75` therefore asserts over the example's tag **names**, and the values come from
 * here.
 *
 * ### What the corpus carries and why
 *
 * The non-vacuity floor is §4.3's round-trip rule proved through the **event id**, plus the
 * delegated values T10's review found a codec could quietly replace with a constant. So the corpus
 * draws the price, the fee arity, both deadlines and the optional `amount` tag independently and
 * `ProposalPropertyTest` asserts each decoded term is the one drawn — not merely that something
 * decoded. It also carries unknown tags and `subject` tags, because §4.3 requires those be preserved
 * verbatim and §7.4 requires no term be derived from a `subject`.
 */
internal object ProposalFixtures {

    /**
     * Pinned so a failure is reproducible. `java.util.Random`'s algorithm ([JdkRandom], identical on
     * the JVM and JavaScript), for the reason `WireFixtures` gives.
     */
    const val SEED: Long = 20260921L

    /** T9's fixed, non-round `created_at`. A codec that emitted a constant would still be visible. */
    const val CREATED_AT: Long = ChannelFixtures.CREATED_AT

    /** §4.5's version token this implementation implements, as it appears in a tag. */
    val VERSION: String = NenyaProtocol.VERSION.toString()

    /** §7.4's `type` number for a proposal, read off T9's constants and never written here. */
    val PROPOSAL_TYPE: String = NenyaKind.OrderMessageType.PROPOSAL.toString()

    /** §7.4's `type` number for a status update. */
    val STATUS_TYPE: String = NenyaKind.OrderMessageType.STATUS.toString()

    /** §11.1's token for the one status §7.6 makes an acceptance out of. */
    val ACCEPTED: String = OrderStatusCodec.write(OrderState.ACCEPTED)

    /** §7.6's four byte-identical terms, in the order §7.6 lists them. */
    val TERM_NAMES: List<String> = listOf(
        ChannelVocabulary.ITEM,
        ChannelVocabulary.AMOUNT_MSAT,
        ChannelVocabulary.FEE,
        ChannelVocabulary.DELIVER_BY,
    )

    /** A price that is a whole number of satoshis, so the optional `amount` tag can agree with it. */
    const val PRICE_SAT: Long = 90_000L

    /** The acceptance window §7.5's example uses: `created_at + 4 h`. */
    const val ACCEPTANCE_WINDOW: Long = 4L * 60L * 60L

    /** §7.5's example `deliver_by`, which is the bid's own `created_at + 18 h`. */
    const val DELIVERY_WINDOW: Long = 18L * 60L * 60L

    /** Tag names §5.3 does not name and §7.5 does not read, so §4.3's unknown-tag rule applies. */
    private val UNKNOWN_NAMES: List<String> = listOf("client", "zap", "relays", "x-nenya-experiment")

    /** Keeps the generated buyer, provider and fee recipient distinct at every index. */
    private const val PROVIDER_OFFSET: Int = 1
    private const val FEE_RECIPIENT_OFFSET: Int = 2
    private const val LISTING_OFFSET: Int = 3

    /**
     * A real BIP-340 public key, in §4.3's canonical lowercase.
     *
     * The file's own spelling is uppercase; [recased] is where that spelling is used deliberately.
     */
    fun pubkey(index: Int): String = raw(index).lowercase()

    /** The same key in the vendored file's own **uppercase** spelling — §4.3's read rule, live. */
    fun uppercasePubkey(index: Int): String = raw(index)

    /**
     * [TagFixtures.distinctPubkeys] and **not** `uppercasePubkeys`, and the difference is
     * load-bearing since revision `1.5`.
     *
     * The vendored file reuses one key across eight of its rows, so indexing the raw column would
     * hand this generator the same key for a buyer at one index and a provider at the next — and
     * §7.6 now refuses an acceptance whose resolved provider is the proposal's own author. That
     * would be a fixture failure wearing a library failure's clothes.
     */
    private fun raw(index: Int): String {
        val keys = TagFixtures.distinctPubkeys
        return keys[((index % keys.size) + keys.size) % keys.size]
    }

    /** §4.2's coordinate for the listing a proposal at [index] derives from. */
    fun coordinate(index: Int): String =
        "${NenyaKind.REQUEST}:${pubkey(index + LISTING_OFFSET)}:lighthouse-loop-$index"

    /** §7.4's order id for [index], a digest and never a typed string. */
    fun orderHex(index: Int): String = ChannelFixtures.orderHexFor(index)

    /** §8.1's three-element fee term at [basisPoints] above zero, naming a real recipient key. */
    fun feeTag(index: Int, basisPoints: Int): List<String> =
        if (basisPoints == 0) listOf(ChannelVocabulary.FEE, "0")
        else listOf(ChannelVocabulary.FEE, basisPoints.toString(), pubkey(index + FEE_RECIPIENT_OFFSET))

    /**
     * §7.5's worked proposal, in the tag order the example prints, with every optional term
     * defaulted to the example's own shape.
     *
     * Mutable, so a control can break exactly one thing about it.
     */
    fun proposalTags(
        index: Int = 0,
        order: String? = orderHex(index),
        item: List<String>? = listOf(ChannelVocabulary.ITEM, coordinate(index), "1"),
        amountMsat: String? = (PRICE_SAT * Msat.MSAT_PER_SAT).toString(),
        amount: String? = null,
        fee: List<String>? = feeTag(index, DEFAULT_BASIS_POINTS),
        deliverBy: Long? = CREATED_AT + DELIVERY_WINDOW,
        expiration: Long? = CREATED_AT + ACCEPTANCE_WINDOW,
        version: String? = VERSION,
        type: String? = PROPOSAL_TYPE,
    ): MutableList<List<String>> {
        val tags = mutableListOf<List<String>>()
        if (order != null) tags += listOf(ChannelTags.ORDER, order)
        if (type != null) tags += listOf(ChannelTags.TYPE, type)
        tags += listOf(ChannelVocabulary.COUNTERPARTY, pubkey(index + PROVIDER_OFFSET))
        if (item != null) tags += item
        if (amountMsat != null) tags += listOf(ChannelTags.AMOUNT_MSAT, amountMsat)
        if (amount != null) tags += listOf(ChannelTags.AMOUNT, amount)
        if (fee != null) tags += fee
        if (deliverBy != null) tags += listOf(ChannelVocabulary.DELIVER_BY, deliverBy.toString())
        if (expiration != null) tags += listOf(ChannelVocabulary.EXPIRATION, expiration.toString())
        if (version != null) tags += listOf(ChannelVocabulary.VERSION, version)
        return tags
    }

    /**
     * §7.6's acceptance for [proposal]: a `type=3` carrying `["status", "accepted"]`, the same
     * `order` id, and §7.6's four terms copied **byte for byte** off the proposal.
     *
     * `expiration` is deliberately not carried across: §7.6's list of terms an acceptance must
     * repeat is `item`, `amount_msat`, `fee` and `deliver_by`, and §7.5 makes `expiration` the
     * deadline for acceptance — which a conformant provider has no reason to echo, and which
     * `OrderTerms.namesTheSameDealAs` excludes for the same reason.
     */
    fun acceptanceTags(
        proposal: List<List<String>>,
        index: Int = 0,
        status: String? = ACCEPTED,
        order: String = orderOf(proposal),
        terms: List<List<String>> = proposal.filter { it[0] in TERM_NAMES },
    ): MutableList<List<String>> {
        val tags = mutableListOf<List<String>>()
        tags += listOf(ChannelTags.ORDER, order)
        tags += listOf(ChannelTags.TYPE, STATUS_TYPE)
        tags += listOf(ChannelVocabulary.COUNTERPARTY, pubkey(index))
        if (status != null) tags += listOf(ChannelVocabulary.STATUS, status)
        tags += terms
        tags += listOf(ChannelVocabulary.VERSION, VERSION)
        return tags
    }

    /** The `order` id [tags] carries, for an acceptance built to name the same one. */
    fun orderOf(tags: List<List<String>>): String =
        tags.firstOrNull { it[0] == ChannelTags.ORDER }?.getOrNull(1)
            ?: fail("this fixture carries no `${ChannelTags.ORDER}` tag to name")

    /**
     * [tags] as a `kind:16` rumor, attributed to the key that authored it — §7.2's positive case.
     *
     * The seal pubkey and the rumor pubkey are the same key, so every control below is about §7.5
     * or §7.6 rather than about §7.2, which `ChannelCodecTest` covers.
     */
    fun bound(tags: List<List<String>>, index: Int = 0): AttributedRumor.Bound {
        val author = pubkey(index)
        val rumor = AttributedRumor.attribute(
            author,
            ChannelFixtures.checked(author, NenyaKind.ORDER_MESSAGE, tags),
        )
        return rumor as? AttributedRumor.Bound
            ?: fail("a kind:${NenyaKind.ORDER_MESSAGE} must decode to a bound rumor, not to $rumor")
    }

    /** [tags] decoded as §7.5's proposal, through T8's and T13's doors. */
    fun proposal(tags: List<List<String>>, index: Int = 0): OrderProposal =
        OrderProposal.decode(bound(tags, index))

    /** [tags] decoded as §7.4's `type=3` status update. */
    fun statusMessage(tags: List<List<String>>, index: Int = 0): OrderStatusMessage =
        OrderStatusMessage.decode(bound(tags, index))

    /** [tags] with every occurrence of [name] removed. */
    fun without(tags: List<List<String>>, name: String): List<List<String>> =
        tags.filter { it[0] != name }

    /** [tags] with every occurrence of [tag]'s name replaced by [tag]. */
    fun replacing(tags: List<List<String>>, tag: List<String>): List<List<String>> =
        tags.map { if (it[0] == tag[0]) tag else it }

    /** [tags] with [tag] appended. */
    fun with(tags: List<List<String>>, tag: List<String>): List<List<String>> = tags + listOf(tag)

    /** One generated proposal and the acceptance that matches it, plus every value drawn for it. */
    data class Fixture(
        val index: Int,
        val proposalTags: List<List<String>>,
        val acceptanceTags: List<List<String>>,
        val priceMsat: Long,
        val basisPoints: Int?,
        val expiration: Long?,
        val deliverBy: Long?,
        val carriesAmount: Boolean,
    ) {

        /** §8.1's read rule: no `fee` tag at all is [FeeTerm.Absent] and not a stated zero. */
        val feeAbsent: Boolean get() = basisPoints == null
    }

    /**
     * [count] **legal** proposal/acceptance pairs from one seeded run.
     *
     * Every fixture decodes and every acceptance is byte-identical, because the property being
     * proved is about round-tripping and about the delegated values rather than about rejection: a
     * corpus that was refused would prove nothing, which is why `ProposalPropertyTest` asserts the
     * whole corpus decodes before it asserts anything else.
     */
    fun pairs(count: Int): List<Fixture> {
        val random = JdkRandom(SEED)
        return List(count) { index -> pair(random, index) }
    }

    private fun pair(random: JdkRandom, index: Int): Fixture {
        // Drawn in satoshis so the optional `amount` tag can agree with `amount_msat` exactly; the
        // `+ 1` keeps the price above zero, which is what makes a non-zero fee reachable.
        val satoshis = 1L + random.nextInt(MAX_DRAWN_SAT)
        val priceMsat = satoshis * Msat.MSAT_PER_SAT
        val basisPoints = when (random.nextInt(FEE_SHAPES)) {
            0 -> null
            1 -> 0
            else -> 1 + random.nextInt(FeeTerm.MAX_BASIS_POINTS)
        }
        // All four presence combinations, drawn deliberately: §7.5 makes both deadlines optional and
        // §11.2 has a clause written precisely for terms carrying no `deliver_by`.
        val deadlines = random.nextInt(DEADLINE_SHAPES)
        val hasExpiration = deadlines == 1 || deadlines == 3
        val hasDeliverBy = deadlines == 2 || deadlines == 3
        val deliverBy = if (hasDeliverBy) CREATED_AT + DELIVERY_WINDOW + random.nextInt(9999) else null
        // §7.5: strictly earlier when both are present.
        val expiration = if (!hasExpiration) null
        else if (deliverBy != null) deliverBy - 1L - random.nextInt(ACCEPTANCE_WINDOW.toInt())
        else CREATED_AT + random.nextInt(9999)
        val carriesAmount = random.nextBoolean()

        val tags = proposalTags(
            index = index,
            amountMsat = priceMsat.toString(),
            amount = if (carriesAmount) satoshis.toString() else null,
            fee = basisPoints?.let { feeTag(index, it) },
            deliverBy = deliverBy,
            expiration = expiration,
        )
        if (random.nextBoolean()) tags += listOf(ChannelVocabulary.SUBJECT, "order $index")
        repeat(1 + random.nextInt(2)) {
            val name = UNKNOWN_NAMES[random.nextInt(UNKNOWN_NAMES.size)]
            tags += listOf(name, "$index-$it")
        }
        tags.shuffleAll(random)

        val acceptance = acceptanceTags(tags, index)
        acceptance.shuffleAll(random)
        return Fixture(
            index = index,
            proposalTags = tags.toList(),
            acceptanceTags = acceptance.toList(),
            priceMsat = priceMsat,
            basisPoints = basisPoints,
            expiration = expiration,
            deliverBy = deliverBy,
            carriesAmount = carriesAmount,
        )
    }

    /**
     * Reorders the whole list, so the corpus does not test one tag order ten thousand times.
     *
     * §7.5 fixes no tag order and §4.1 forbids *changing* one, and the id property is what would
     * catch a codec that sorted or normalised on the way out.
     */
    private fun MutableList<List<String>>.shuffleAll(random: JdkRandom) {
        for (position in size - 1 downTo 1) {
            val other = random.nextInt(position + 1)
            val swap = this[position]
            this[position] = this[other]
            this[other] = swap
        }
    }

    /** §7.5's example fee: `250` basis points, which is 2.5%. */
    private const val DEFAULT_BASIS_POINTS: Int = 250

    /** Absent, stated zero, stated non-zero — §8.1's three distinguishable shapes. */
    private const val FEE_SHAPES: Int = 3

    /** Neither deadline, one, the other, both. */
    private const val DEADLINE_SHAPES: Int = 4

    /** Well inside §4.4's supply cap, and large enough that a non-zero fee rounds to a non-zero. */
    private const val MAX_DRAWN_SAT: Int = 10_000_000
}
