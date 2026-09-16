package dev.eryalabs.nenya.listing

import dev.eryalabs.nenya.JdkRandom
import dev.eryalabs.nenya.TestText
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.tag.TagFixtures
import dev.eryalabs.nenya.wire.CheckedEvent
import dev.eryalabs.nenya.wire.EventId
import dev.eryalabs.nenya.wire.WireEvent

/**
 * The generator behind every listing fixture in this package.
 *
 * This is not a scratch file. The queue's Definition of done forbids an encoded value appearing in
 * a test as something somebody typed out, so nothing here is: the pubkeys are SHA-256 digests of a
 * per-index label (`TagFixtures.pubkeyFor`), the uppercase ones come from the vendored,
 * externally-authored BIP-340 vector file, and every event id under test is computed by
 * the platform's own SHA-256 inside T8. A reviewer can change [SEED], re-run the suite, and every property
 * must still hold.
 *
 * ### Why it is a second generator rather than T9's
 *
 * `TagFixtures` generates tag sets for the three contexts §5.3's split distinguishes, including
 * `kind:1111` bids, and its listings carry `["status", "sold"]` on requests — legal at the tag
 * layer, which §5.3 says reads `status` as an opaque value, and a §5.2 violation here. The
 * vocabulary being a function of the kind is precisely what this package exists for, so its corpus
 * has to be built by something that knows the kinds. Everything below the vocabulary — pubkeys,
 * the checked-event door, the two worked listings §5.1 and §5.2 print — is T9's and is reused.
 *
 * ### What the corpus carries and why
 *
 * The non-vacuity floor for this task is §4.3's round-trip rule proved through the **event id**, so
 * the corpus carries what makes that assertion mean something: unknown tags (§4.3 requires they be
 * preserved verbatim, and states that dropping one changes the id), control characters and non-BMP
 * characters inside values (so T8's escaping rules had to work for the id being compared), both
 * `fee` arities, both `image` arities, absent and unrecognised `status` tokens, and `expiration`
 * tags so §5.6's rule has something to evaluate.
 */
internal object ListingFixtures {

    /**
     * Pinned so a failure is reproducible. `java.util.Random`'s algorithm ([JdkRandom], identical on
     * the JVM and JavaScript) rather than `kotlin.random`, for the
     * reason `WireFixtures` gives: its algorithm is specified by the JDK, so this file produces the
     * same runs on any JVM a reviewer re-runs it on.
     */
    const val SEED: Long = 20260917L

    /** T9's fixed, non-round `created_at`. A codec that emitted a constant would still be visible. */
    const val CREATED_AT: Long = TagFixtures.CREATED_AT

    /** A `status` token neither §5.1's nor §5.2's vocabulary names — §5.2's `unknown` treatment. */
    const val UNRECOGNISED_STATUS: String = "part-exchanged"

    /** §5.1's and §5.2's three kinds, in the order the corpus cycles them. */
    val KINDS: List<Int> = listOf(NenyaKind.REQUEST, NenyaKind.OFFER, NenyaKind.OFFER_DRAFT)

    /** Tag names §5.3 does not name, so §4.3's "unknown tag" rule applies to them. */
    private val UNKNOWN_NAMES: List<String> = listOf(
        "client", "subject", "zap", "relays", "A", "K", "P", "e", "E", "k", "x-nenya-experiment",
    )

    /** Values chosen to exercise §4.1's escaping rules, since the event id is what the property asserts. */
    private val HOSTILE_PIECES: List<String> = buildList {
        add("")
        for (code in listOf(0x00, 0x08, 0x09, 0x0A, 0x0C, 0x0D, 0x1F, 0x22, 0x5C, 0x2F, 0x7F)) {
            add(code.toChar().toString())
        }
        add("é")
        add("中")
        add(TestText.codePoint(0x1F600))
        add("ordinary")
        add("wss://relay.example.invalid")
    }

    /** One generated listing: its kind, its tags, its `content` and the index its pubkey comes from. */
    data class Fixture(
        val kind: Int,
        val tags: List<List<String>>,
        val content: String,
        val index: Int,
    )

    /** [tags] as a T8 [CheckedEvent] — the only door into [Listing.decode] (§4.1). */
    fun checked(
        tags: List<List<String>>,
        kind: Int,
        content: String = "",
        index: Int = 0,
    ): CheckedEvent = TagFixtures.checked(tags, kind, content, index)

    /**
     * [tags] as a [CheckedEvent] whose `created_at` is [createdAt] rather than the fixed one.
     *
     * The parameter exists for §4.6's controls and for nothing else: they need an event whose own
     * `created_at` **disagrees** with the injected clock, in both directions, or an implementation
     * evaluating `expiration` against the event's timestamp would survive them.
     */
    fun checkedAt(
        tags: List<List<String>>,
        kind: Int,
        createdAt: Long,
        content: String = "",
        index: Int = 0,
    ): CheckedEvent {
        val event = WireEvent(TagFixtures.pubkeyFor(index), createdAt, kind, tags, content)
        return CheckedEvent.checkEventId(EventId.of(event).toHex(), event)
    }

    /** [tags] decoded as a listing of [kind], through the checked-event door. */
    fun decode(
        tags: List<List<String>>,
        kind: Int,
        content: String = "",
        index: Int = 0,
    ): Listing = Listing.decode(checked(tags, kind, content, index))

    /** The fixture, decoded. */
    fun decode(fixture: Fixture): Listing =
        Listing.decode(checked(fixture.tags, fixture.kind, fixture.content, fixture.index))

    /**
     * §5.2's worked request, reduced to the tags §5.3 marks MUST on a request — `alt` included,
     * because that is the row where the two listing kinds differ. Mutable, so a test can break one.
     */
    fun minimalRequest(): MutableList<List<String>> = TagFixtures.minimalRequest()

    /** §5.1's worked offer, reduced to the tags §5.3 marks MUST on an offer — no `alt`. */
    fun minimalOffer(): MutableList<List<String>> = TagFixtures.minimalOffer()

    /** The minimal listing for [kind]: §5.2's request or §5.1's offer, with the right side token. */
    fun minimal(kind: Int): MutableList<List<String>> =
        if (kind == NenyaKind.REQUEST) minimalRequest() else minimalOffer()

    /** [tags] with every occurrence of [name] removed — the per-required-tag removal loop's edit. */
    fun without(tags: List<List<String>>, name: String): List<List<String>> =
        tags.filter { it[0] != name }

    /** [tags] with [tag] appended. */
    fun with(tags: List<List<String>>, tag: List<String>): List<List<String>> = tags + listOf(tag)

    /**
     * [count] **legal** listings from one seeded run, cycling §5.1's and §5.2's three kinds.
     *
     * Every fixture decodes, because the property being proved is about round-tripping rather than
     * about rejection: a corpus that was refused would prove nothing at all, which is why
     * `ListingPropertyTest` asserts the whole corpus decodes before it asserts anything about ids.
     */
    fun listings(count: Int): List<Fixture> {
        val random = JdkRandom(SEED)
        return List(count) { index -> listing(random, index, KINDS[index % KINDS.size]) }
    }

    private fun listing(random: JdkRandom, index: Int, kind: Int): Fixture {
        val request = kind == NenyaKind.REQUEST
        val tags = mutableListOf<List<String>>()
        tags += listOf("d", "listing-$index-${hostile(random, 1)}")
        tags += listOf("title", hostile(random, 2))
        tags += priceTag(random)
        tags += listOf("t", "nenya")
        tags += listOf("t", if (request) ListingSide.WANT_TO_BUY.token else ListingSide.WANT_TO_SELL.token)
        repeat(random.nextInt(3)) { tags += listOf("t", "category-$index-$it") }
        tags += listOf("nenya", "1")
        // MUST on a request (§5.2), SHOULD on an offer (§5.1) — so the offer side draws it.
        if (request || random.nextBoolean()) tags += listOf("alt", hostile(random, 2))
        statusTag(random, kind)?.let { tags += it }
        addOptionalTags(random, index, tags)
        addUnknownTags(random, index, tags)
        tags.shuffleTail(random)
        return Fixture(kind, tags.toList(), hostile(random, 4), index)
    }

    /**
     * §5.1's and §5.2's vocabularies, plus the two cases §5.2 names that are not tokens at all: an
     * **absent** `status`, which means `active`, and an **unrecognised** one, which means `unknown`
     * and specifically not `active`. Both have to be in the corpus or the round-trip property
     * never reaches the branch that reads them.
     */
    private fun statusTag(random: JdkRandom, kind: Int): List<String>? {
        val vocabulary = ListingStatusCodec.vocabulary(kind).mapNotNull { it.token }.sorted()
        return when (random.nextInt(vocabulary.size + 2)) {
            0 -> null
            1 -> listOf("status", UNRECOGNISED_STATUS)
            else -> listOf("status", vocabulary[random.nextInt(vocabulary.size)])
        }
    }

    private fun addOptionalTags(random: JdkRandom, index: Int, tags: MutableList<List<String>>) {
        if (random.nextBoolean()) tags += listOf("summary", hostile(random, 2))
        if (random.nextBoolean()) tags += listOf("published_at", (CREATED_AT - random.nextInt(99999)).toString())
        if (random.nextBoolean()) tags += listOf("m", "image/png")
        // Drawn deliberately rather than left to chance: §5.6's rule has nothing to evaluate on a
        // listing carrying no `expiration`, and a corpus without one never exercises `activity`.
        if (random.nextInt(3) != 0) tags += listOf("expiration", (CREATED_AT + random.nextInt(99999)).toString())
        if (random.nextBoolean()) tags += listOf("deliver_by", (CREATED_AT + random.nextInt(99999)).toString())
        if (random.nextBoolean()) tags += listOf("license", "cc-by-4.0")
        if (random.nextBoolean()) tags += feeTag(random, index)
        repeat(random.nextInt(4)) {
            // Both arities: §5.3's Encoding cell prints three elements and NIP-99 makes the
            // dimensions optional, which §5.1's "unmodified NIP-99" makes legal on an offer.
            val url = "https://example.invalid/$index-$it.png"
            tags += if (random.nextBoolean()) listOf("image", url, "${64 + it}x${64 + it}")
            else listOf("image", url)
        }
        repeat(random.nextInt(3)) { tags += listOf("p", TagFixtures.pubkeyFor(index + it + 1)) }
    }

    /** §8.1's two arities, both generated, with zero drawn deliberately rather than by chance. */
    private fun feeTag(random: JdkRandom, index: Int): List<String> {
        val bps = if (random.nextInt(4) == 0) 0 else 1 + random.nextInt(FeeTerm.MAX_BASIS_POINTS)
        return if (bps == 0) listOf("fee", "0")
        else listOf("fee", bps.toString(), TagFixtures.pubkeyFor(index + 7))
    }

    /** §4.4's eight permissive-on-read unit tokens, all of them, on satoshi-exact values. */
    private fun priceTag(random: JdkRandom): List<String> {
        val satoshis = random.nextInt(1_000_000).toLong()
        return when (random.nextInt(8)) {
            0 -> listOf("price", satoshis.toString(), "SAT")
            1 -> listOf("price", satoshis.toString(), "sat")
            2 -> listOf("price", satoshis.toString(), "sats")
            3 -> listOf("price", satoshis.toString(), "SATS")
            4 -> listOf("price", (satoshis * 1000).toString(), "msat")
            5 -> listOf("price", (satoshis * 1000).toString(), "MSAT")
            // 1 SAT = 0.00000001 BTC, so eight fractional digits is satoshi-exact.
            6 -> listOf("price", btcDecimal(satoshis), "BTC")
            else -> listOf("price", btcDecimal(satoshis), "btc")
        }
    }

    private fun btcDecimal(satoshis: Long): String {
        val whole = satoshis / 100_000_000L
        val fraction = satoshis % 100_000_000L
        return "$whole.${fraction.toString().padStart(8, '0')}"
    }

    private fun addUnknownTags(random: JdkRandom, index: Int, tags: MutableList<List<String>>) {
        repeat(1 + random.nextInt(3)) {
            val name = UNKNOWN_NAMES[random.nextInt(UNKNOWN_NAMES.size)]
            val elements = 1 + random.nextInt(3)
            tags += buildList<String> {
                add(name)
                repeat(elements) { add("$index-${hostile(random, 2)}") }
            }
        }
    }

    /**
     * Reorders everything after the first tag, so the corpus does not test one tag order ten
     * thousand times. §4.1 forbids reordering tags on the way out and the id property is what would
     * catch a codec that did.
     */
    private fun MutableList<List<String>>.shuffleTail(random: JdkRandom) {
        for (position in size - 1 downTo 1) {
            val other = random.nextInt(position + 1)
            val swap = this[position]
            this[position] = this[other]
            this[other] = swap
        }
    }

    private fun hostile(random: JdkRandom, maxPieces: Int): String {
        val out = StringBuilder()
        repeat(random.nextInt(maxPieces + 1)) {
            out.append(HOSTILE_PIECES[random.nextInt(HOSTILE_PIECES.size)])
        }
        return out.toString()
    }
}
