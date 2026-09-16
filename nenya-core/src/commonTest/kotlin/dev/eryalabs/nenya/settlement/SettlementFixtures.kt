package dev.eryalabs.nenya.settlement

import dev.eryalabs.nenya.JdkRandom
import dev.eryalabs.nenya.channel.AttributedRumor
import dev.eryalabs.nenya.channel.ChannelFixtures
import dev.eryalabs.nenya.channel.ChannelTags
import dev.eryalabs.nenya.channel.ChannelVocabulary
import dev.eryalabs.nenya.money.FeeSplit
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.payment.PaymentFixtures
import dev.eryalabs.nenya.payment.PaymentHash
import dev.eryalabs.nenya.payment.Preimage
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.tag.TagFixtures
import kotlin.test.fail

/**
 * The generator behind every §8.6 and §9.2 fixture in this package.
 *
 * ### These strings are **not** invoices, and nothing here may ever treat them as ones
 *
 * [invoices] produces strings that are BOLT-11-*shaped*: an `ln` + network human-readable part, an
 * optional Appendix C amount, the `1` separator, and a data part drawn from the bech32 alphabet and
 * long enough to hold Appendix C's timestamp and signature. **Their bech32 checksums are wrong,
 * their tagged fields are noise, and no payment hash, amount or expiry can be read out of any of
 * them.** That is deliberate and sufficient: §9.2 check 1 is a byte comparison over an opaque
 * string, which is the only thing this package does with one.
 *
 * So: no test in this package may assert anything about the *contents* of a generated invoice, and
 * no later reader may take one of these as a parser fixture. A fixture that looks like an invoice is
 * exactly how somebody talks themselves into parsing one — and the BOLT-11 parser is a human
 * decision precisely because it needs externally-authored vectors this repository does not have.
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

    /** Appendix C's network prefixes, in the order the appendix lists them. */
    private val NETWORKS: List<String> = listOf("bc", "tb", "bcrt")

    /** Appendix C's four amount multipliers, plus the no-multiplier whole-BTC form. */
    private val MULTIPLIERS: List<String> = listOf("m", "u", "n", "p", "")

    /** Keeps the generated provider, fee recipient and buyer distinct at every index. */
    private const val PROVIDER_OFFSET: Int = 1
    private const val FEE_RECIPIENT_OFFSET: Int = 2

    /** Tag names §5.3 does not name and §8.6 does not read, so §4.3's unknown-tag rule applies. */
    private val UNKNOWN_NAMES: List<String> = listOf("client", "zap", "relays", "x-nenya-experiment")

    /** How much longer than Appendix C's floor a generated data part may run. */
    private const val EXTRA_DATA_CHARACTERS: Int = 60

    /** A real BIP-340 public key, in §4.3's canonical lowercase. */
    fun pubkey(index: Int): String {
        val keys = TagFixtures.uppercasePubkeys
        return keys[((index % keys.size) + keys.size) % keys.size].lowercase()
    }

    /** The same key in the vendored file's own **uppercase** spelling — §4.3's read rule, live. */
    fun uppercasePubkey(index: Int): String {
        val keys = TagFixtures.uppercasePubkeys
        return keys[((index % keys.size) + keys.size) % keys.size]
    }

    /** §7.4's order id for [index], a digest and never a typed string. */
    fun orderHex(index: Int): String = ChannelFixtures.orderHexFor(index)

    /**
     * [count] distinct BOLT-11-**shaped** strings from one seeded run.
     *
     * See this object's header: these are not invoices and carry no valid checksum.
     */
    fun invoices(count: Int): List<String> {
        val random = JdkRandom(SEED)
        return List(count) { invoice(random) }
    }

    private fun invoice(random: JdkRandom): String {
        val network = NETWORKS[random.nextInt(NETWORKS.size)]
        val amount = if (random.nextBoolean()) {
            ""
        } else {
            "${1 + random.nextInt(99_999)}${MULTIPLIERS[random.nextInt(MULTIPLIERS.size)]}"
        }
        val length = Bolt11Reference.MIN_DATA_CHARACTERS + random.nextInt(EXTRA_DATA_CHARACTERS)
        val data = StringBuilder(length)
        repeat(length) {
            data.append(Bolt11Reference.BECH32_ALPHABET[random.nextInt(Bolt11Reference.BECH32_ALPHABET.length)])
        }
        return "ln$network${amount}1$data"
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
        payment: List<String>? = listOf(
            SettlementVocabulary.PAYMENT,
            PaymentMedium.LIGHTNING.token!!,
            invoices(1).single(),
        ),
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

    /** The buyer at [index] — the key `bound` attributes a rumor to unless told otherwise. */
    fun buyer(index: Int = 0): String = pubkey(index)

    /** The provider at [index], the `p` counterparty of every fixture here. */
    fun provider(index: Int = 0): String = pubkey(index + PROVIDER_OFFSET)

    /** The fee recipient at [index] — §8.1's third `fee` element and §8.7's required seal. */
    fun feeRecipient(index: Int = 0): String = pubkey(index + FEE_RECIPIENT_OFFSET)

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
        val invoice = invoice(random)
        val order = orderHex(index)
        val payeeTag = payeeTag(payee, index)
        val request = requestTags(
            index = index,
            order = order,
            payment = requestPaymentTag(invoice),
            payeeTag = payeeTag,
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
