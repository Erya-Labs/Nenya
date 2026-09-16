package dev.eryalabs.nenya.bid

import dev.eryalabs.nenya.JdkRandom
import dev.eryalabs.nenya.TestText
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.tag.TagFixtures
import dev.eryalabs.nenya.wire.CheckedEvent

/**
 * The generator behind every bid fixture in this package.
 *
 * This is not a scratch file. The queue's Definition of done forbids an encoded value appearing in
 * a test as something somebody typed out, so nothing here is: the pubkeys are SHA-256 digests of a
 * per-index label (`TagFixtures.pubkeyFor`), the uppercase ones come from the vendored,
 * externally-authored BIP-340 vector file, and every event id under test is computed by
 * the platform's own SHA-256 inside T8. A reviewer can change [SEED], re-run the suite, and every
 * property
 * must still hold.
 *
 * ### Why it is a third generator rather than T9's or T10's
 *
 * `TagFixtures.minimalBid` produces §6's worked **term** tags — `price`, `t`, `nenya` — and nothing
 * else, because the tag layer knows no scope tags: `A`, `a`, `K`, `k` and `P` are NIP-22's and are
 * not rows in §5.3's table, so T9 generates them as *unknown* tags carrying arbitrary values. A
 * corpus built that way would carry scope tags that disagree with each other by construction and
 * would be refused by every assertion in this package. Everything below the scope vocabulary —
 * pubkeys, the checked-event door, §6's term tags — is T9's and is reused.
 *
 * ### What the corpus carries and why
 *
 * The non-vacuity floor for this task is §4.3's round-trip rule proved through the **event id**, so
 * the corpus carries what makes that assertion mean something: unknown tags (§4.3 requires they be
 * preserved verbatim, and states that dropping one changes the id), control characters and non-BMP
 * characters inside values, `d` values containing **colons** (§4.2's coordinate splits into exactly
 * three fields, and an unbounded split truncates those), both `fee` arities, relay hints present and
 * absent, uppercase and lowercase spellings of the same pubkey (§4.3's accept-and-normalise rule),
 * bids against all three listing kinds, and — deliberately, because §6 makes `expiration` a SHOULD
 * rather than a MUST — bids that carry no `expiration` at all.
 */
internal object BidFixtures {

    /**
     * Pinned so a failure is reproducible. `java.util.Random`'s algorithm ([JdkRandom], identical on
     * the JVM and JavaScript) rather than `kotlin.random`, for the
     * reason `WireFixtures` gives: its algorithm is specified by the JDK, so this file produces the
     * same runs on any JVM a reviewer re-runs it on.
     */
    const val SEED: Long = 20260918L

    /** T9's fixed, non-round `created_at`. A codec that emitted a constant would still be visible. */
    const val CREATED_AT: Long = TagFixtures.CREATED_AT

    /** The listing kinds a bid may scope to: §6's request, §6's counter-offer, and §5.1's draft. */
    val LISTING_KINDS: List<Int> = listOf(NenyaKind.REQUEST, NenyaKind.OFFER, NenyaKind.OFFER_DRAFT)

    /**
     * Tag names §5.3 does not name **and** §6 does not use.
     *
     * Deliberately not T9's list, which carries `A`, `K`, `P`, `e`, `E` and `k` precisely because
     * the tag layer treats them as unknown. Generating one of those here would produce a bid whose
     * scope tags contradict each other, or one scoped by event id — legitimate corpora for T9's
     * property and refusals for every assertion in this package.
     */
    private val UNKNOWN_NAMES: List<String> = listOf(
        "client", "subject", "zap", "relays", "r", "emoji", "x-nenya-experiment",
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

    /** One generated bid: its tags, its `content` and the index its bidder pubkey comes from. */
    data class Fixture(
        val tags: List<List<String>>,
        val content: String,
        val index: Int,
        val listingKind: Int,
    )

    /** [tags] as a T8 [CheckedEvent] on §6's kind — the only door into [Bid.decode] (§4.1). */
    fun checked(tags: List<List<String>>, content: String = "", index: Int = 0): CheckedEvent =
        TagFixtures.checked(tags, NenyaKind.PUBLIC_BID, content, index)

    /** [tags] as a [CheckedEvent] on some other kind, for the not-a-bid control. */
    fun checkedOnKind(tags: List<List<String>>, kind: Int, index: Int = 0): CheckedEvent =
        TagFixtures.checked(tags, kind, "", index)

    /** [tags] decoded as a bid, through the checked-event door. */
    fun decode(tags: List<List<String>>, content: String = "", index: Int = 0): Bid =
        Bid.decode(checked(tags, content, index))

    /** The fixture, decoded. */
    fun decode(fixture: Fixture): Bid = Bid.decode(checked(fixture.tags, fixture.content, fixture.index))

    /**
     * §6's worked bid, reduced to the tags §6 marks MUST, scoped to [listingKind].
     *
     * It carries a **single** `["t", "nenya"]` and no `wtb`/`wts` — §5.3 names this shape itself as
     * what a codec applying the listing rules everywhere would reject, calling it "this document's
     * own bid example". Mutable, so a test can break one tag.
     */
    fun minimalBid(
        listingKind: Int = NenyaKind.REQUEST,
        authorIndex: Int = LISTING_AUTHOR_INDEX,
        dValue: String = D_VALUE,
    ): MutableList<List<String>> {
        val author = TagFixtures.pubkeyFor(authorIndex)
        val coordinate = "$listingKind:$author:$dValue"
        return mutableListOf(
            listOf("A", coordinate, RELAY_HINT),
            listOf("K", listingKind.toString()),
            listOf("P", author, RELAY_HINT),
            listOf("a", coordinate, RELAY_HINT),
            listOf("k", listingKind.toString()),
            listOf("p", author, RELAY_HINT),
            listOf("price", "90000", "SAT"),
            listOf("t", "nenya"),
            listOf("nenya", "1"),
        )
    }

    /** The coordinate [minimalBid] scopes to, for a control that needs to name it. */
    fun coordinateOf(
        listingKind: Int = NenyaKind.REQUEST,
        authorIndex: Int = LISTING_AUTHOR_INDEX,
        dValue: String = D_VALUE,
    ): String = "$listingKind:${TagFixtures.pubkeyFor(authorIndex)}:$dValue"

    /** The listing author [minimalBid] names, for a control that needs to disagree with it. */
    fun listingAuthor(authorIndex: Int = LISTING_AUTHOR_INDEX): String = TagFixtures.pubkeyFor(authorIndex)

    /** [tags] with every occurrence of [name] removed — the per-required-tag removal loop's edit. */
    fun without(tags: List<List<String>>, name: String): List<List<String>> = tags.filter { it[0] != name }

    /** [tags] with [tag] appended. */
    fun with(tags: List<List<String>>, tag: List<String>): List<List<String>> = tags + listOf(tag)

    /** [tags] with every occurrence of [name] replaced by [tag]. */
    fun replacing(tags: List<List<String>>, name: String, tag: List<String>): List<List<String>> =
        tags.map { if (it[0] == name) tag else it }

    /**
     * [count] **legal** bids from one seeded run, cycling the three listing kinds a bid may scope to.
     *
     * Every fixture decodes, because the property being proved is about round-tripping rather than
     * about rejection: a corpus that was refused would prove nothing at all, which is why
     * `BidPropertyTest` asserts the whole corpus decodes before it asserts anything about ids.
     */
    fun bids(count: Int): List<Fixture> {
        val random = JdkRandom(SEED)
        return List(count) { index -> bid(random, index, LISTING_KINDS[index % LISTING_KINDS.size]) }
    }

    private fun bid(random: JdkRandom, index: Int, listingKind: Int): Fixture {
        val author = TagFixtures.pubkeyFor(index + AUTHOR_OFFSET)
        // §5.3 makes `d` opaque and only requires it be non-empty, so a colon inside one is legal —
        // and is the case §4.2's three-field split with a limit exists for. Drawn deliberately
        // rather than left to chance, because a corpus without one never reaches that branch.
        val dValue = "listing-$index${if (random.nextInt(3) == 0) ":$index" else ""}-${hostile(random, 1)}"
        val coordinate = "$listingKind:$author:$dValue"
        // §4.3: uppercase hex is accepted and normalised everywhere but the two values on its
        // exhaustive exception list, neither of which is a pubkey. So the same key spelled two ways
        // across `A`, `a`, `P` and `p` must still agree, and the corpus says so ten thousand times.
        // Only the **pubkey** field is re-cased: `d` is opaque (§5.3) and carries non-ASCII here, so
        // uppercasing the whole coordinate string would change the address rather than its spelling.
        val coordinateSpelling =
            if (random.nextBoolean()) coordinate else "$listingKind:${author.uppercase()}:$dValue"

        val tags = mutableListOf<List<String>>()
        tags += scopeTag(random, "A", coordinate)
        tags += scopeTag(random, "a", coordinateSpelling)
        tags += listOf("K", listingKind.toString())
        tags += listOf("k", listingKind.toString())
        tags += scopeTag(random, "P", author)
        tags += scopeTag(random, "p", if (random.nextBoolean()) author else author.uppercase())
        tags += priceTag(random)
        tags += listOf("t", "nenya")
        repeat(random.nextInt(3)) { tags += listOf("t", "category-$index-$it") }
        tags += listOf("nenya", "1")
        addOptionalTags(random, index, tags)
        addUnknownTags(random, index, tags)
        tags.shuffleAll(random)
        return Fixture(tags.toList(), hostile(random, 4), index, listingKind)
    }

    /** A scope tag with NIP-22's optional relay hint present or absent — both arities are legal. */
    private fun scopeTag(random: JdkRandom, name: String, value: String): List<String> =
        if (random.nextBoolean()) listOf(name, value, RELAY_HINT) else listOf(name, value)

    private fun addOptionalTags(random: JdkRandom, index: Int, tags: MutableList<List<String>>) {
        // §6 makes `expiration` a SHOULD, so roughly a third of the corpus carries none: the
        // open-ended bid is a conformant shape and has to be in the corpus, or the property below
        // proves nothing about the one control an over-strict codec fails.
        if (random.nextInt(3) != 0) {
            tags += listOf("expiration", (CREATED_AT + random.nextInt(99999)).toString())
        }
        if (random.nextBoolean()) tags += listOf("deliver_by", (CREATED_AT + random.nextInt(99999)).toString())
        if (random.nextBoolean()) tags += feeTag(random, index)
        if (random.nextBoolean()) tags += listOf("alt", hostile(random, 2))
        if (random.nextBoolean()) tags += listOf("summary", hostile(random, 2))
        if (random.nextBoolean()) tags += listOf("m", "image/png")
        if (random.nextBoolean()) tags += listOf("license", "cc-by-4.0")
        repeat(random.nextInt(3)) {
            val url = "https://example.invalid/$index-$it.png"
            tags += if (random.nextBoolean()) listOf("image", url, "${64 + it}x${64 + it}")
            else listOf("image", url)
        }
    }

    /** §8.1's two arities, both generated, with zero drawn deliberately rather than by chance. */
    private fun feeTag(random: JdkRandom, index: Int): List<String> {
        val bps = if (random.nextInt(4) == 0) 0 else 1 + random.nextInt(FeeTerm.MAX_BASIS_POINTS)
        return if (bps == 0) listOf("fee", "0")
        else listOf("fee", bps.toString(), TagFixtures.pubkeyFor(index + FEE_RECIPIENT_OFFSET))
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
     * Reorders the whole list, so the corpus does not test one tag order ten thousand times.
     *
     * Every tag here may sit anywhere — §6 fixes no order and §4.1 forbids *changing* one — and the
     * id property is what would catch a codec that sorted or normalised on the way out.
     */
    private fun MutableList<List<String>>.shuffleAll(random: JdkRandom) {
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

    /** §6's example relay hint, from the document's own reserved-for-documentation domain. */
    private const val RELAY_HINT: String = "wss://relay.example.invalid"

    /** The `d` value §6's worked example uses, which is plain text and carries no colon. */
    private const val D_VALUE: String = "lighthouse-loop-2026-09"

    /** The index [minimalBid]'s listing author is generated from; the bidder is index 0. */
    private const val LISTING_AUTHOR_INDEX: Int = 1

    /** Keeps a generated listing author distinct from the generated bidder at the same index. */
    private const val AUTHOR_OFFSET: Int = 1

    /** Keeps a generated fee recipient distinct from both parties. */
    private const val FEE_RECIPIENT_OFFSET: Int = 7
}
