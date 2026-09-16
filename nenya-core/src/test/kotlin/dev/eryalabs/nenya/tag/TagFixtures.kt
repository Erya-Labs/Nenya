package dev.eryalabs.nenya.tag

import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.wire.CheckedEvent
import dev.eryalabs.nenya.wire.EventId
import dev.eryalabs.nenya.wire.WireEvent
import java.io.File
import java.security.MessageDigest
import java.util.Random
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The generator behind every tag fixture in this package.
 *
 * This is not a scratch file. The queue's Definition of done forbids an encoded value appearing in
 * a test as something somebody typed out, so no pubkey, coordinate or event id here is typed: the
 * generated pubkeys are SHA-256 digests of a per-index label, the uppercase ones come from the
 * vendored, externally-authored BIP-340 vector file, and every event id under test is computed by
 * `MessageDigest` inside T8. A reviewer can change [SEED], re-run the suite, and every property
 * must still hold.
 *
 * ### Why the corpus carries hostile strings and unknown tags
 *
 * The non-vacuity floor for this task is §4.3's round-trip rule, and §4.3 states its own
 * consequence: dropping a tag an implementation does not understand "silently strips extensions
 * **and changes the event id**". So the corpus has to carry unknown tags for that to be provable,
 * and it carries control characters, quotes and non-BMP characters inside them so the id it is
 * compared against is one T8's escaping rules actually had to work for.
 */
internal object TagFixtures {

    /**
     * Pinned so a failure is reproducible. `java.util.Random` rather than `kotlin.random`, for the
     * reason `WireFixtures` gives: its algorithm is specified by the JDK, so this file produces
     * the same runs on any JVM a reviewer re-runs it on.
     */
    const val SEED: Long = 20260916L

    /** A fixed, non-round timestamp, so a codec that emitted a constant would be visible. */
    const val CREATED_AT: Long = 1_767_225_600L

    private const val BIP340_VECTORS: String = "src/test/resources/vectors/bip340-vectors.csv"

    /** The BIP-340 reference vector file has nineteen data rows. */
    private const val BIP340_ROWS: Int = 19

    /** Tag names that are **not** in §5.3's vocabulary, so §4.3's "unknown tag" rule applies. */
    private val UNKNOWN_NAMES: List<String> = listOf(
        "client", "subject", "zap", "relays", "A", "K", "P", "e", "E", "k", "x-nenya-experiment",
    )

    /** Values chosen to exercise §4.1's escaping rules, since the id is what the property asserts. */
    private val HOSTILE_PIECES: List<String> = buildList {
        add("")
        for (code in listOf(0x00, 0x08, 0x09, 0x0A, 0x0C, 0x0D, 0x1F, 0x22, 0x5C, 0x2F, 0x7F)) {
            add(code.toChar().toString())
        }
        add("é")
        add("中")
        add(String(Character.toChars(0x1F600)))
        add("ordinary")
        add("wss://relay.example.invalid")
    }

    /**
     * The `public key` column of the vendored BIP-340 vectors, parsed **by name** rather than
     * indexed blind, and **uppercase in the file** — which is what makes it both a fixture and
     * §4.3's accept-and-normalise control in one value.
     */
    val uppercasePubkeys: List<String> by lazy {
        val file = File(BIP340_VECTORS)
        if (!file.isFile) {
            fail(
                "the vendored BIP-340 vectors were expected at ${file.absolutePath} but are not " +
                    "there. The tests run with the module directory as the working directory; " +
                    "this one is ${File(".").absoluteFile.normalize()}.",
            )
        }
        val lines = file.readLines().filter { it.isNotBlank() }
        val headers = lines.first().split(",").map { it.trim() }
        val column = headers.indexOf("public key")
        assertTrue(column >= 0, "the vectors must carry a 'public key' column; found $headers")
        val keys = lines.drop(1).map { it.split(",", limit = headers.size)[column].trim() }
        assertTrue(
            keys.size == BIP340_ROWS,
            "expected $BIP340_ROWS BIP-340 data rows and read ${keys.size}; a broken reader must " +
                "not pass with zero",
        )
        assertTrue(
            keys.all { it.length == 64 && it.any { character -> character in 'A'..'F' } },
            "these fixtures are 64-character hex and are relied on to be uppercase in the file",
        )
        keys
    }

    /** A generated 64-character lowercase-hex pubkey, unique per [index] and never typed. */
    fun pubkeyFor(index: Int): String =
        lowerHex(MessageDigest.getInstance("SHA-256").digest("nenya-tag-fixture-$index".toByteArray()))

    /** §4.3's canonical form: lowercase, unpadded. Written here so no test transcribes hex. */
    fun lowerHex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            out.append(digits[value ushr 4]).append(digits[value and 0x0f])
        }
        return out.toString()
    }

    /** A §4.2 coordinate over a generated pubkey. */
    fun coordinate(kind: Int = NenyaKind.REQUEST, index: Int = 0, dValue: String = "d-value"): String =
        "$kind:${pubkeyFor(index)}:$dValue"

    /**
     * [tags] as a T8 [CheckedEvent] — the only door into [TagSet.read], because §4.1 requires the
     * id check happen before any other processing.
     */
    fun checked(
        tags: List<List<String>>,
        kind: Int,
        content: String = "",
        index: Int = 0,
    ): CheckedEvent {
        val event = WireEvent(pubkeyFor(index), CREATED_AT, kind, tags, content)
        return CheckedEvent.checkEventId(EventId.of(event).toHex(), event)
    }

    /** [tags] read under [context], through the checked-event door. */
    fun read(
        tags: List<List<String>>,
        context: TagContext,
        limits: TagLimits = TagLimits.DEFAULT,
        content: String = "",
        index: Int = 0,
    ): TagSet = TagSet.read(checked(tags, context.kind, content, index), context, limits)

    /** §5.2's worked request, reduced to the tags §5.3 marks MUST. Mutable, so a test can break one. */
    fun minimalRequest(): MutableList<List<String>> = mutableListOf(
        listOf("d", "lighthouse-loop-2026-09"),
        listOf("title", "30s looping animation, lighthouse in a storm"),
        listOf("price", "120000", "SAT"),
        listOf("t", "nenya"),
        listOf("t", "wtb"),
        listOf("nenya", "1"),
        // MUST on a request (§5.2, §5.3), SHOULD on an offer — the one conditional row.
        listOf("alt", "Nenya marketplace request: 30s lighthouse animation"),
    )

    /** §5.1's worked offer, reduced to the tags §5.3 marks MUST on an offer. */
    fun minimalOffer(): MutableList<List<String>> = mutableListOf(
        listOf("d", "sdxl-portrait-commission"),
        listOf("title", "Custom SDXL portrait, 1024x1024"),
        listOf("price", "50000", "SAT"),
        listOf("t", "nenya"),
        listOf("t", "wts"),
        listOf("nenya", "1"),
    )

    /**
     * §6's worked public bid, which carries a **single** `["t", "nenya"]` and no `wtb`/`wts`.
     *
     * §5.3 names this shape itself as what a codec applying the listing rules everywhere would
     * reject — "this document's own bid example".
     */
    fun minimalBid(): MutableList<List<String>> = mutableListOf(
        listOf("price", "90000", "SAT"),
        listOf("t", "nenya"),
        listOf("nenya", "1"),
    )

    /** §7.5's worked order proposal, reduced to the tags this codec reads. */
    fun minimalProposal(): MutableList<List<String>> = mutableListOf(
        listOf("item", coordinate(), "1"),
        listOf("p", pubkeyFor(1)),
        listOf("fee", "250", pubkeyFor(2)),
        listOf("nenya", "1"),
    )

    /** One generated fixture: a context, the tags read under it, and the event body they ride in. */
    data class Fixture(
        val context: TagContext,
        val tags: List<List<String>>,
        val content: String,
        val index: Int,
    )

    /**
     * [count] valid fixtures from one seeded run, spread across the three contexts §5.3's split
     * distinguishes: a request, an offer and a public bid.
     *
     * Every fixture is *legal*, because the property being proved is about round-tripping rather
     * than about rejection — a corpus that was rejected would prove nothing at all, which is why
     * `TagPropertyTest` asserts the whole corpus reads before it asserts anything about ids.
     */
    fun fixtures(count: Int): List<Fixture> {
        val random = Random(SEED)
        return List(count) { index ->
            when (index % 3) {
                0 -> listingFixture(random, index, NenyaKind.REQUEST)
                1 -> listingFixture(random, index, if (index % 6 == 1) NenyaKind.OFFER else NenyaKind.OFFER_DRAFT)
                else -> bidFixture(random, index)
            }
        }
    }

    private fun listingFixture(random: Random, index: Int, kind: Int): Fixture {
        val tags = mutableListOf<List<String>>()
        tags += listOf("d", "listing-$index-${hostile(random, 1)}")
        tags += listOf("title", hostile(random, 2))
        tags += priceTag(random)
        tags += listOf("t", "nenya")
        tags += listOf("t", if (kind == NenyaKind.REQUEST) "wtb" else "wts")
        repeat(random.nextInt(3)) { tags += listOf("t", "category-$index-$it") }
        tags += listOf("nenya", "1")
        tags += listOf("alt", hostile(random, 2))
        addOptionalListingTags(random, index, tags)
        addUnknownTags(random, index, tags)
        tags.shuffleTail(random)
        return Fixture(TagContext.listing(kind), tags.toList(), hostile(random, 4), index)
    }

    private fun bidFixture(random: Random, index: Int): Fixture {
        val tags = mutableListOf<List<String>>()
        tags += priceTag(random)
        tags += listOf("t", "nenya")
        tags += listOf("nenya", "1")
        if (random.nextBoolean()) tags += listOf("expiration", (CREATED_AT + random.nextInt(9999)).toString())
        if (random.nextBoolean()) tags += listOf("deliver_by", (CREATED_AT + random.nextInt(99999)).toString())
        if (random.nextBoolean()) tags += feeTag(random, index)
        if (random.nextBoolean()) tags += listOf("p", pubkeyFor(index + 1), "wss://relay.example.invalid")
        addUnknownTags(random, index, tags)
        return Fixture(TagContext.publicBid(), tags.toList(), hostile(random, 4), index)
    }

    private fun addOptionalListingTags(random: Random, index: Int, tags: MutableList<List<String>>) {
        if (random.nextBoolean()) tags += listOf("summary", hostile(random, 2))
        if (random.nextBoolean()) tags += listOf("published_at", (CREATED_AT - random.nextInt(99999)).toString())
        if (random.nextBoolean()) tags += listOf("status", if (random.nextBoolean()) "active" else "sold")
        if (random.nextBoolean()) tags += listOf("m", "image/png")
        if (random.nextBoolean()) tags += listOf("expiration", (CREATED_AT + random.nextInt(99999)).toString())
        if (random.nextBoolean()) tags += listOf("deliver_by", (CREATED_AT + random.nextInt(99999)).toString())
        if (random.nextBoolean()) tags += listOf("license", "cc-by-4.0")
        if (random.nextBoolean()) tags += feeTag(random, index)
        repeat(random.nextInt(4)) {
            // Both arities. §5.3's Encoding cell prints three elements and NIP-99 makes the
            // dimensions optional, and §5.1 says a Nenya offer is an unmodified NIP-99 listing —
            // so the two-element form is legal and has to appear in the corpus, or the branch
            // `ImageRef` argues hardest for is the one nothing ever executes.
            val url = "https://example.invalid/$index-$it.png"
            tags += if (random.nextBoolean()) listOf("image", url, "${64 + it}x${64 + it}")
            else listOf("image", url)
        }
        repeat(random.nextInt(3)) { tags += listOf("p", pubkeyFor(index + it + 1)) }
    }

    /** §8.1's two arities, both generated, so neither is only ever exercised by a named control. */
    private fun feeTag(random: Random, index: Int): List<String> {
        // Zero is drawn deliberately rather than left to a uniform 1-in-10001 chance. §8.1 says a
        // proposal SHOULD carry an explicit `["fee", "0"]` so that the absence of a fee is itself a
        // signed statement, which makes the two-element arity a common real case rather than an
        // edge — and a uniform draw produced a corpus of 10 000 that carried none of them.
        val bps = if (random.nextInt(4) == 0) 0 else 1 + random.nextInt(FeeTerm.MAX_BASIS_POINTS)
        return if (bps == 0) listOf("fee", "0") else listOf("fee", bps.toString(), pubkeyFor(index + 7))
    }

    /** §4.4's eight permissive-read unit tokens, all of them, on satoshi-exact values. */
    private fun priceTag(random: Random): List<String> {
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

    private fun addUnknownTags(random: Random, index: Int, tags: MutableList<List<String>>) {
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
     * Reorders everything after the required prefix, so the corpus does not test one tag order ten
     * thousand times. §4.1 forbids reordering tags on the way out, and the id property is what
     * would catch a codec that did.
     */
    private fun MutableList<List<String>>.shuffleTail(random: Random) {
        for (position in size - 1 downTo 1) {
            val other = random.nextInt(position + 1)
            val swap = this[position]
            this[position] = this[other]
            this[other] = swap
        }
    }

    private fun hostile(random: Random, maxPieces: Int): String {
        val out = StringBuilder()
        repeat(random.nextInt(maxPieces + 1)) {
            out.append(HOSTILE_PIECES[random.nextInt(HOSTILE_PIECES.size)])
        }
        return out.toString()
    }
}
