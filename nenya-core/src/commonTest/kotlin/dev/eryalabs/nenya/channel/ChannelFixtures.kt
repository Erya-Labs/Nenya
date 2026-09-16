package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.JdkRandom
import dev.eryalabs.nenya.NenyaProtocol
import dev.eryalabs.nenya.TestText
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.oracleSha256
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.tag.TagFixtures
import dev.eryalabs.nenya.utf8Bytes
import dev.eryalabs.nenya.wire.CheckedEvent
import dev.eryalabs.nenya.wire.EventId
import dev.eryalabs.nenya.wire.WireEvent

/**
 * The generator behind every rumor fixture in this package.
 *
 * This is not a scratch file. The queue's Definition of done forbids an encoded value appearing in
 * a test as something somebody typed out, so nothing here is: every pubkey and every order id is a
 * SHA-256 digest of a per-index label computed by the platform's own SHA-256 ([oracleSha256]), the
 * uppercase spellings are `uppercase()` of those, and every event id under test is computed inside
 * T8. A reviewer can change [SEED], re-run the suite, and every property must still hold.
 *
 * ### Why it is its own generator rather than T9's or T11's
 *
 * `TagFixtures` generates `A`, `K`, `P`, `e`, `E` and `k` as *unknown* tags carrying arbitrary
 * values, which is right for the tag layer and produces §6 scope tags that contradict each other.
 * More to the point, it has no §7.4 vocabulary at all: `order` and `type` are not §5.3 rows, so a
 * corpus built there would carry no rumor envelope to round-trip. Everything below that vocabulary
 * — the generated pubkeys, the hostile string pieces, the checked-event door — is T9's and is
 * reused rather than copied.
 *
 * ### What the corpus carries and why
 *
 * The non-vacuity floor for this task is §4.3's round-trip rule proved through the **event id**, so
 * the corpus carries what makes that assertion mean something: unknown tags (§4.3 requires they be
 * preserved verbatim, and states that dropping one changes the id), `subject` tags (§7.4 makes them
 * display metadata that must round-trip and must yield no term), control characters and non-BMP
 * characters inside values, both `fee` arities, uppercase and lowercase spellings of the same
 * pubkey **and of the same order id** (§4.3's accept-and-normalise rule, which §7.2's comparison and
 * §7.4's `order` read both depend on), and all four rumor kinds with all six `type` values.
 */
internal object ChannelFixtures {

    /**
     * Pinned so a failure is reproducible. `java.util.Random`'s algorithm ([JdkRandom], identical on
     * the JVM and JavaScript) rather than `kotlin.random`, for the reason `WireFixtures` gives: its
     * algorithm is specified by the JDK, so this file produces the same runs on any JVM a reviewer
     * re-runs it on.
     */
    const val SEED: Long = 20260919L

    /** T9's fixed, non-round `created_at`. A codec that emitted a constant would still be visible. */
    const val CREATED_AT: Long = TagFixtures.CREATED_AT

    /** §4.5's version token this implementation implements, as it appears in a tag. */
    val VERSION: String = NenyaProtocol.VERSION.toString()

    /** §7.4's six `type` values, as the numbers T9 declares. Never written as literals here. */
    val TYPES: List<Int> = listOf(
        NenyaKind.OrderMessageType.PROPOSAL,
        NenyaKind.OrderMessageType.PAYMENT_REQUEST,
        NenyaKind.OrderMessageType.STATUS,
        NenyaKind.OrderMessageType.SHIPPING,
        NenyaKind.OrderMessageType.DELIVERY_COMMITMENT,
        NenyaKind.OrderMessageType.PRIVATE_BID,
    )

    /**
     * The nine shapes the corpus cycles: the three non-chat kinds that carry no `type`, chat, and a
     * `kind:16` for each of §7.4's six `type` values.
     */
    val SHAPES: List<Shape> = buildList {
        add(Shape(NenyaKind.CHAT, null))
        add(Shape(NenyaKind.FILE_MESSAGE, null))
        add(Shape(NenyaKind.RECEIPT, null))
        for (type in TYPES) add(Shape(NenyaKind.ORDER_MESSAGE, type))
    }

    /** Tag names §5.3 does not name and §7.4 does not use, so §4.3's unknown-tag rule applies. */
    private val UNKNOWN_NAMES: List<String> = listOf(
        "client", "zap", "relays", "r", "emoji", "x-nenya-experiment",
    )

    /** Values chosen to exercise §4.1's escaping rules, since the event id is what is asserted. */
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

    /** One rumor kind, with §7.4's `type` when it is a `kind:16` and `null` otherwise. */
    data class Shape(val kind: Int, val type: Int?)

    /**
     * One generated rumor: the seal pubkey §7.2 compares against, the rumor's own spelling of that
     * same key, its tags, its `content`, and the shape it was drawn for.
     */
    data class Fixture(
        val sealPubkey: String,
        val rumorPubkey: String,
        val tags: List<List<String>>,
        val content: String,
        val index: Int,
        val shape: Shape,
    )

    /** A generated 64-character lowercase-hex order id, unique per [index] and never typed. */
    fun orderHexFor(index: Int): String =
        TagFixtures.lowerHex(oracleSha256("nenya-order-fixture-$index".utf8Bytes()))

    /** A generated 64-character lowercase-hex pubkey, unique per [index] and never typed. */
    fun pubkeyFor(index: Int): String = TagFixtures.pubkeyFor(index)

    /**
     * [tags] as a T8 [CheckedEvent] on [kind], authored by [pubkey] **in the spelling given**.
     *
     * Its own door rather than `TagFixtures.checked`, because §4.3's accept-and-normalise rule is
     * half of what this package is about: the pubkey has to be choosable, in either case, so that
     * §7.2's comparison and §4.1's id can be asserted against the same event.
     */
    fun checked(
        pubkey: String,
        kind: Int,
        tags: List<List<String>>,
        content: String = "",
    ): CheckedEvent {
        val event = WireEvent(pubkey, CREATED_AT, kind, tags, content)
        return CheckedEvent.checkEventId(EventId.of(event).toHex(), event)
    }

    /** [tags] attributed to the same key that authored them — §7.2's positive case. */
    fun attribute(
        kind: Int,
        tags: List<List<String>>,
        content: String = "",
        index: Int = 0,
    ): AttributedRumor =
        AttributedRumor.attribute(pubkeyFor(index), checked(pubkeyFor(index), kind, tags, content))

    /** The fixture, attributed through the checked-event door. */
    fun attribute(fixture: Fixture): AttributedRumor = AttributedRumor.attribute(
        fixture.sealPubkey,
        checked(fixture.rumorPubkey, fixture.shape.kind, fixture.tags, fixture.content),
    )

    /** §7.4's required set for a `kind:15` or `kind:17`. Mutable, so a test can break one tag. */
    fun minimalBound(index: Int = 0): MutableList<List<String>> = mutableListOf(
        listOf(ChannelVocabulary.VERSION, VERSION),
        listOf(ChannelVocabulary.COUNTERPARTY, pubkeyFor(index + COUNTERPARTY_OFFSET)),
        listOf(ChannelTags.ORDER, orderHexFor(index)),
    )

    /**
     * §7.4's required set for a `kind:16` of [type], with §5.3's `item` where that row makes it
     * mandatory — exactly one on a `type=1` proposal (§7.5) and on a `type=6` private bid (§6.1) —
     * and without the `order` tag §7.4 forbids on a `type=6`.
     */
    fun minimalOrderMessage(type: Int, index: Int = 0): MutableList<List<String>> {
        val tags = mutableListOf<List<String>>(
            listOf(ChannelVocabulary.VERSION, VERSION),
            listOf(ChannelVocabulary.COUNTERPARTY, pubkeyFor(index + COUNTERPARTY_OFFSET)),
            listOf(ChannelTags.TYPE, type.toString()),
        )
        if (type != NenyaKind.OrderMessageType.PRIVATE_BID) {
            tags += listOf(ChannelTags.ORDER, orderHexFor(index))
        }
        if (type == NenyaKind.OrderMessageType.PROPOSAL || type == NenyaKind.OrderMessageType.PRIVATE_BID) {
            tags += listOf("item", TagFixtures.coordinate(index = index + ITEM_OFFSET), "1")
        }
        return tags
    }

    /** §7.4's `kind:14`, which is outside the required-tag rule entirely. */
    fun minimalChat(): MutableList<List<String>> = mutableListOf()

    /** The required set for [shape], whichever of the three forms it takes. */
    fun minimal(shape: Shape, index: Int = 0): MutableList<List<String>> = when {
        shape.kind == NenyaKind.CHAT -> minimalChat()
        shape.type != null -> minimalOrderMessage(shape.type, index)
        else -> minimalBound(index)
    }

    /** [tags] with every occurrence of [name] removed — the per-required-tag removal loop's edit. */
    fun without(tags: List<List<String>>, name: String): List<List<String>> = tags.filter { it[0] != name }

    /** [tags] with [tag] appended. */
    fun with(tags: List<List<String>>, tag: List<String>): List<List<String>> = tags + listOf(tag)

    /** [tags] with every occurrence of [name] replaced by [tag]. */
    fun replacing(tags: List<List<String>>, name: String, tag: List<String>): List<List<String>> =
        tags.map { if (it[0] == name) tag else it }

    /**
     * [count] **legal** rumors from one seeded run, cycling [SHAPES].
     *
     * Every fixture attributes, because the property being proved is about round-tripping rather
     * than about rejection: a corpus that was refused would prove nothing at all, which is why
     * `ChannelPropertyTest` asserts the whole corpus decodes before it asserts anything about ids.
     */
    fun rumors(count: Int): List<Fixture> {
        val random = JdkRandom(SEED)
        return List(count) { index -> rumor(random, index, SHAPES[index % SHAPES.size]) }
    }

    private fun rumor(random: JdkRandom, index: Int, shape: Shape): Fixture {
        val author = pubkeyFor(index)
        // §4.3: uppercase hex is accepted and normalised everywhere but the two values on its
        // exhaustive exception list, neither of which is a pubkey. So §7.2's comparison must hold
        // across the two spellings of one key, and the corpus says so thousands of times — while
        // §4.1's id must still be computed over the spelling the rumor actually carried.
        val rumorPubkey = if (random.nextBoolean()) author else author.uppercase()
        val sealPubkey = if (random.nextBoolean()) author else author.uppercase()

        val tags = mutableListOf<List<String>>()
        tags += listOf(ChannelVocabulary.COUNTERPARTY, counterpartySpelling(random, index))
        if (shape.kind != NenyaKind.CHAT) {
            tags += listOf(ChannelVocabulary.VERSION, VERSION)
        } else if (random.nextBoolean()) {
            // §7.4 puts kind:14 outside the required set, and a chat carrying the tags anyway is
            // legal — so both halves have to be in the corpus, or "outside the rule" is untested.
            tags += listOf(ChannelVocabulary.VERSION, VERSION)
        }
        addOrderTag(random, index, shape, tags)
        if (shape.type != null) tags += listOf(ChannelTags.TYPE, shape.type.toString())
        if (shape.type == NenyaKind.OrderMessageType.PROPOSAL ||
            shape.type == NenyaKind.OrderMessageType.PRIVATE_BID
        ) {
            tags += listOf("item", TagFixtures.coordinate(index = index + ITEM_OFFSET), "1")
        }
        addOptionalTags(random, index, tags)
        addUnknownTags(random, index, tags)
        tags.shuffleAll(random)
        return Fixture(sealPubkey, rumorPubkey, tags.toList(), hostile(random, 4), index, shape)
    }

    /**
     * §7.4's `order` rule and its one exception, generated rather than assumed.
     *
     * A `kind:16` `type=6` never gets one — §7.4 forbids it — and a `kind:14` gets one about half
     * the time, because §7.4 says an implementation MAY carry it there and MUST NOT derive anything
     * from its presence *or its absence*, so both halves must be in the corpus.
     */
    private fun addOrderTag(
        random: JdkRandom,
        index: Int,
        shape: Shape,
        tags: MutableList<List<String>>,
    ) {
        val forbidden = shape.type == NenyaKind.OrderMessageType.PRIVATE_BID
        if (forbidden) return
        if (shape.kind == NenyaKind.CHAT && random.nextBoolean()) return
        val hex = orderHexFor(index)
        tags += listOf(ChannelTags.ORDER, if (random.nextBoolean()) hex else hex.uppercase())
    }

    private fun addOptionalTags(random: JdkRandom, index: Int, tags: MutableList<List<String>>) {
        // §7.4: "A `subject` tag (NIP-17) MAY be present on any rumor. It is display metadata only."
        // Not a §5.3 row, so it rides through as an unknown tag and must come back out verbatim.
        if (random.nextBoolean()) tags += listOf("subject", hostile(random, 2))
        if (random.nextBoolean()) tags += listOf("expiration", (CREATED_AT + random.nextInt(99999)).toString())
        if (random.nextBoolean()) tags += listOf("deliver_by", (CREATED_AT + random.nextInt(99999)).toString())
        if (random.nextBoolean()) tags += feeTag(random, index)
        if (random.nextBoolean()) tags += listOf("alt", hostile(random, 2))
        if (random.nextBoolean()) tags += listOf("summary", hostile(random, 2))
        if (random.nextBoolean()) tags += listOf("m", "image/png")
        if (random.nextBoolean()) {
            tags += listOf(ChannelVocabulary.COUNTERPARTY, pubkeyFor(index + SECOND_PARTY_OFFSET))
        }
        if (random.nextBoolean()) {
            tags += listOf("price", random.nextInt(1_000_000).toString(), "SAT")
        }
    }

    /** §8.1's two arities, both generated, with zero drawn deliberately rather than by chance. */
    private fun feeTag(random: JdkRandom, index: Int): List<String> {
        val bps = if (random.nextInt(4) == 0) 0 else 1 + random.nextInt(FeeTerm.MAX_BASIS_POINTS)
        return if (bps == 0) listOf("fee", "0")
        else listOf("fee", bps.toString(), pubkeyFor(index + FEE_RECIPIENT_OFFSET))
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

    /** §4.3's accept-and-normalise rule again, on the `p` tag the tag codec decodes. */
    private fun counterpartySpelling(random: JdkRandom, index: Int): String {
        val pubkey = pubkeyFor(index + COUNTERPARTY_OFFSET)
        return if (random.nextBoolean()) pubkey else pubkey.uppercase()
    }

    /**
     * Reorders the whole list, so the corpus does not test one tag order ten thousand times.
     *
     * §7.4 fixes no tag order and §4.1 forbids *changing* one, and the id property is what would
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

    private fun hostile(random: JdkRandom, maxPieces: Int): String {
        val out = StringBuilder()
        repeat(random.nextInt(maxPieces + 1)) {
            out.append(HOSTILE_PIECES[random.nextInt(HOSTILE_PIECES.size)])
        }
        return out.toString()
    }

    /** Keeps the generated counterparty distinct from the generated author at the same index. */
    private const val COUNTERPARTY_OFFSET: Int = 1

    /** A second `p` tag names somebody else again: §5.3 gives `p` cardinality `0–n`. */
    private const val SECOND_PARTY_OFFSET: Int = 3

    /** Keeps a generated fee recipient distinct from both parties. */
    private const val FEE_RECIPIENT_OFFSET: Int = 7

    /** Keeps the `item` coordinate's author distinct from both parties. */
    private const val ITEM_OFFSET: Int = 11
}
