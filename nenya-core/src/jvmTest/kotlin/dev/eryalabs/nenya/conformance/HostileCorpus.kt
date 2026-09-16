package dev.eryalabs.nenya.conformance

import dev.eryalabs.nenya.bid.BidFixtures
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.tag.TagFixtures
import dev.eryalabs.nenya.tag.TagLimits
import dev.eryalabs.nenya.wire.WireLimits
import java.util.Random

/**
 * The generator behind the hostile-input sweep: well-formed fixtures, broken every way §4.3
 * anticipates.
 *
 * This is not a scratch file. The queue's Definition of done forbids an encoded value appearing in
 * a test as something somebody typed out, so nothing here is: every pubkey is a SHA-256 digest of a
 * per-index label (`TagFixtures.pubkeyFor`), every base fixture is `TagFixtures`', and every string
 * is assembled by a `java.util.Random` pinned to [SEED]. A reviewer can change [SEED], re-run the
 * suite, and every property must still hold.
 *
 * ### Why the bases are well-formed and the mutations are one at a time
 *
 * §4.3 opens by saying every event reaching an implementation is hostile input "including events it
 * believes it authored", and the corpus that finds bugs in a decoder is not random bytes — a
 * decoder rejects those on the first field and never reaches the code under test. It is a
 * conformant event with **one** thing wrong, which gets past the outer checks and into the branch
 * nobody exercised. So each input is a real §5.1, §5.2 or §6 fixture with exactly one [Mutation]
 * applied, and [Mutation.WELL_FORMED] is in the list so the sweep also proves the entry points
 * still work on input they should accept.
 *
 * ### What the sweep is looking for
 *
 * Not "did it reject this" — plenty of these are legal — but *how* it refused. A
 * `StringIndexOutOfBoundsException` out of a decoder is a denial of service in the buyer's client
 * rather than a rejection, and it is what an unhardened `substring`, `first()` or `toInt()`
 * produces. `HostileInputSweepTest` is where that rule lives; this file only supplies the input.
 */
internal object HostileCorpus {

    /**
     * Pinned so a failure is reproducible. `java.util.Random` rather than `kotlin.random` for the
     * reason `WireFixtures` gives: its algorithm is specified by the JDK, so this file produces the
     * same runs on any JVM a reviewer re-runs it on.
     */
    const val SEED: Long = 20260919L

    /** Every way §4.3 says an event can arrive wrong, one constant per way. */
    internal enum class Mutation {

        /** Nothing broken. The control that proves the entry points still accept conformant input. */
        WELL_FORMED,

        /** A value cut short — half a pubkey, half a coordinate, half a digest. */
        TRUNCATED,

        /** A value far longer than anything §4.3 bounds, without reaching a bound exactly. */
        OVERSIZED,

        /** Empty strings and the empty tag array §4.3 names by itself. */
        EMPTY,

        /** A tag with the wrong number of elements — a name and nothing else, or one element too many. */
        WRONG_ARITY,

        /** A second occurrence of a tag §5.3 gives cardinality `1` or `0–1` (§4.3's duplicate rule). */
        DUPLICATED_TAG,

        /** Control characters in every string position: tag name, tag value, `content`, pubkey. */
        CONTROL_CHARACTERS,

        /** 63 or 65 hex characters where §4.3 fixes exactly 64 — never padded, never truncated. */
        HEX_WRONG_LENGTH,

        /** Uppercase and mixed-case hex, which §4.3 accepts in some places and refuses in two. */
        HEX_MIXED_CASE,

        /** `007`, `0x40`, `1_000` — a decimal §4.4's strict form does not admit. */
        NON_CANONICAL_DECIMAL,

        /** `-1`, `+1` — §4.3 forbids a signed timestamp and §4.4 a negative amount. */
        SIGNED_DECIMAL,

        /** `1e3`, `1.5E2` — §4.3 forbids an exponential form by name. */
        EXPONENTIAL_DECIMAL,

        /** One unit past a §4.3 resource bound: 513 tags, 1025 bytes, 16 KiB + 1, 65 images. */
        JUST_OVER_BOUND,
    }

    /**
     * One corpus entry: an event's five id-bearing fields, and four hostile strings for the entry
     * points that take one.
     *
     * The four strings are separate because a decoder shaped like `Coordinate.parse` never reaches
     * its interesting branches on arbitrary bytes — it needs something coordinate-shaped with one
     * field wrong. The sweep feeds **all four** to every String-taking entry point anyway, so each
     * also sees input of a shape it was not written for.
     */
    internal data class Input(
        val mutation: Mutation,
        val index: Int,
        val kind: Int,
        val pubkey: String,
        val createdAt: Long,
        val tags: List<List<String>>,
        val content: String,

        /** Arbitrary hostile text: control characters, empties, oversized runs. */
        val text: String,

        /** Something hex-shaped with one thing wrong, for the 64-character values of §4.3. */
        val hex: String,

        /** Something coordinate-shaped with one thing wrong (§4.2). */
        val coordinate: String,

        /** Something number-shaped with one thing wrong (§4.3's timestamps, §4.4's amounts). */
        val decimal: String,
    ) {

        /**
         * Names the mutation and the index, and no value at all.
         *
         * A corpus entry carries a coordinate, a `d` value and a pubkey — three of the values §12
         * item 2 and STOP RULE 14 keep out of the string representation of anything. A failure
         * message naming the mutation and the index is reproducible from [SEED] anyway.
         */
        override fun toString(): String = "Input($mutation, #$index)"
    }

    /** The three kinds the bases are drawn from: §5.2's request, §5.1's offer, §6's public bid. */
    private val KINDS: List<Int> = listOf(NenyaKind.REQUEST, NenyaKind.OFFER, NenyaKind.PUBLIC_BID)

    /** The 32 control characters plus DEL, which is what "every string position" means below. */
    private val CONTROL_PIECES: List<String> =
        ((0x00..0x1F) + 0x7F).map { it.toChar().toString() }

    /** Tags §5.3 gives cardinality `1` or `0–1`, so a second occurrence is §4.3's rejection. */
    private val SINGLE_OCCURRENCE: List<String> = listOf("d", "title", "price", "status", "nenya")

    /**
     * [count] inputs from one seeded run, cycling [Mutation] so every constant is drawn roughly
     * equally.
     *
     * The cycle is deliberate rather than a draw: a uniform random choice over thirteen constants
     * leaves the rarest of them with a count nobody asserted, and `HostileInputSweepTest` asserts
     * each is non-zero precisely so a generator that quietly emitted only well-formed input cannot
     * pass.
     */
    fun corpus(count: Int): List<Input> {
        val random = Random(SEED)
        val mutations = Mutation.entries
        return List(count) { index -> input(random, index, mutations[index % mutations.size]) }
    }

    private fun input(random: Random, index: Int, mutation: Mutation): Input {
        val kind = KINDS[index % KINDS.size]
        val base = base(kind, index)
        val pubkey = TagFixtures.pubkeyFor(index)
        val draft = Input(
            mutation = mutation,
            index = index,
            kind = kind,
            pubkey = pubkey,
            createdAt = TagFixtures.CREATED_AT + index,
            tags = base,
            content = "a public listing's content is plain text (§5.4) #$index",
            text = "nenya-$index",
            hex = pubkey,
            coordinate = "$kind:$pubkey:listing-$index",
            decimal = index.toString(),
        )
        return mutate(random, draft)
    }

    /**
     * §5.2's worked request, §5.1's worked offer, or §6's worked bid, reduced to its MUSTs.
     *
     * The bid comes from `BidFixtures` rather than `TagFixtures`: the latter's is the tag layer's
     * bid, which carries no scope tags at all because §5.3 does not require them, and `Bid.decode`
     * would refuse every one of those for the same reason. A base a decoder refuses on its first
     * check never reaches the branch the mutation is aimed at.
     *
     * An unknown tag is appended to every base so §4.3's preserve-verbatim rule is in the corpus.
     */
    private fun base(kind: Int, index: Int): List<List<String>> = when (kind) {
        NenyaKind.REQUEST -> TagFixtures.minimalRequest()
        NenyaKind.PUBLIC_BID -> BidFixtures.minimalBid()
        else -> TagFixtures.minimalOffer()
    }.also { tags -> tags += listOf("x-nenya-unknown-$index", "preserved verbatim (§4.3)") }

    private fun mutate(random: Random, draft: Input): Input = when (draft.mutation) {
        Mutation.WELL_FORMED -> draft

        Mutation.TRUNCATED -> draft.copy(
            tags = mapOneValue(random, draft.tags) { it.take(it.length / 2) },
            text = draft.text.take(draft.text.length / 2),
            hex = draft.hex.take(draft.hex.length / 2),
            coordinate = draft.coordinate.take(draft.coordinate.length / 2),
            decimal = draft.decimal.take(draft.decimal.length / 2),
        )

        Mutation.OVERSIZED -> {
            val long = "n".repeat(4_096 + random.nextInt(1_024))
            // Most of these stay **inside** §4.3's 1024-byte tag-value bound, because a value that
            // breaches it is refused by the wire layer and the tag, listing and bid codecs then
            // never see a long value at all. A fifth breach it on purpose, so the bound is swept
            // as well as the decoders behind it.
            val inTag = if (breachesTheWire(draft)) long else "n".repeat(900)
            draft.copy(
                tags = mapOneValue(random, draft.tags) { inTag },
                text = long,
                hex = long,
                coordinate = "${draft.kind}:${draft.pubkey}:$long",
                decimal = "9".repeat(64),
            )
        }

        Mutation.EMPTY -> draft.copy(
            // §4.3 names the empty tag array by itself: "Implementations MUST reject ... an empty
            // tag array." It is unrepresentable nowhere — a List<String> can be empty — so it is
            // here rather than argued away.
            tags = if (random.nextBoolean()) draft.tags + listOf(emptyList())
            else mapOneValue(random, draft.tags) { "" },
            text = "",
            hex = "",
            coordinate = "",
            decimal = "",
        )

        Mutation.WRONG_ARITY -> draft.copy(
            tags = mapOneTag(random, draft.tags) { tag ->
                if (random.nextBoolean()) listOf(tag[0]) else tag + listOf("a fourth element", "a fifth")
            },
            coordinate = when (random.nextInt(3)) {
                0 -> "${draft.kind}:${draft.pubkey}"
                1 -> draft.kind.toString()
                else -> "${draft.kind}:${draft.pubkey}:d:with:colons"
            },
        )

        Mutation.DUPLICATED_TAG -> {
            val name = SINGLE_OCCURRENCE[random.nextInt(SINGLE_OCCURRENCE.size)]
            val existing = draft.tags.firstOrNull { it[0] == name }
            draft.copy(tags = draft.tags + listOf(existing ?: listOf(name, "a second occurrence")))
        }

        Mutation.CONTROL_CHARACTERS -> {
            val control = CONTROL_PIECES[random.nextInt(CONTROL_PIECES.size)]
            draft.copy(
                // Every string position: the tag name as well as the values, on a tag of its own so
                // the base fixture stays readable in the other positions.
                tags = mapOneTag(random, draft.tags) { tag ->
                    tag.mapIndexed { position, value -> if (position == 0) "$value$control" else "$control$value$control" }
                } + listOf(listOf("${control}name", "${control}value")),
                content = "${draft.content}$control",
                // The event's **own** pubkey only on a fifth of these, for the reason the OVERSIZED
                // branch gives: a control character in the pubkey is refused by the wire layer, and
                // the tag, listing and bid codecs would then never see a control character in a tag
                // — which is the single most obvious hostile shape for a tag decoder.
                pubkey = if (breachesTheWire(draft)) draft.pubkey.dropLast(1) + control else draft.pubkey,
                text = "$control${draft.text}$control",
                hex = "$control${draft.hex.drop(1)}",
                coordinate = "$control${draft.coordinate}",
                decimal = "$control${draft.decimal}",
            )
        }

        Mutation.HEX_WRONG_LENGTH -> {
            val wrong = if (random.nextBoolean()) draft.pubkey.dropLast(1) else draft.pubkey + "a"
            draft.copy(
                tags = replaceHex(draft.tags, wrong),
                // Same split, same reason: a wrong-length event pubkey dies at the wire layer, so
                // only a fifth of these carry one and the rest reach the tag codec with
                // wrong-length hex inside a `p`, an `a` and a `P` tag.
                pubkey = if (breachesTheWire(draft)) wrong else draft.pubkey,
                hex = wrong,
                coordinate = "${draft.kind}:$wrong:listing-${draft.index}",
            )
        }

        Mutation.HEX_MIXED_CASE -> {
            val mixed = draft.pubkey.mapIndexed { at, ch -> if (at % 2 == 0) ch.uppercaseChar() else ch }
                .joinToString("")
            draft.copy(
                tags = replaceHex(draft.tags, mixed),
                pubkey = mixed,
                hex = mixed,
                coordinate = "${draft.kind}:$mixed:listing-${draft.index}",
            )
        }

        Mutation.NON_CANONICAL_DECIMAL -> {
            val value = when (random.nextInt(4)) {
                0 -> "00${draft.index}"
                1 -> "0x${draft.index}"
                2 -> "1_000"
                else -> " ${draft.index} "
            }
            draft.copy(tags = replaceNumbers(draft.tags, value), decimal = value, coordinate = "0$value:${draft.pubkey}:d")
        }

        Mutation.SIGNED_DECIMAL -> {
            val value = if (random.nextBoolean()) "-${1 + draft.index}" else "+${1 + draft.index}"
            draft.copy(tags = replaceNumbers(draft.tags, value), decimal = value, coordinate = "$value:${draft.pubkey}:d")
        }

        Mutation.EXPONENTIAL_DECIMAL -> {
            val value = if (random.nextBoolean()) "1e${1 + random.nextInt(9)}" else "1.5E2"
            draft.copy(tags = replaceNumbers(draft.tags, value), decimal = value, coordinate = "$value:${draft.pubkey}:d")
        }

        // Chosen by index rather than drawn, so the count of each variant is **fixed**. Three of
        // the four breach a wire bound and only the 65-image-tags one reaches a decoder, so a draw
        // makes "how many JUST_OVER_BOUND inputs got past the wire layer" a binomial with a
        // standard deviation of about four — and the per-mutation reach floor in the sweep would
        // then turn red on roughly one seed in seven, which is exactly the thing a reviewer is
        // invited to try.
        Mutation.JUST_OVER_BOUND -> when (draft.index % 4) {
            0 -> draft.copy(tags = draft.tags + List(WireLimits.DEFAULT_MAX_TAGS_PER_EVENT) { listOf("t", "over-$it") })
            1 -> draft.copy(
                tags = mapOneValue(random, draft.tags) { "v".repeat(WireLimits.DEFAULT_MAX_TAG_VALUE_BYTES + 1) },
            )
            2 -> draft.copy(content = "c".repeat(WireLimits.DEFAULT_MAX_CONTENT_BYTES + 1))
            else -> draft.copy(
                tags = draft.tags + List(TagLimits.DEFAULT_MAX_IMAGE_TAGS + 1) {
                    listOf("image", "https://example.invalid/$it.png", "64x64")
                },
            )
        }
    }

    /**
     * Whether this input breaks something the **wire** layer checks, rather than something a
     * decoder above it checks.
     *
     * Three mutations can do either — a control character, a wrong-length hex value or an
     * oversized string can sit in the event's own `pubkey`, where `WireEvent` refuses it, or in a
     * tag value, where the tag, listing and bid codecs get to see it. Both halves have to be in
     * the corpus, so a share of each goes to the wire layer and the rest goes past it.
     *
     * **Five, not three**, and the number is load-bearing: [KINDS] has three entries and the kind
     * is `index % 3`, so an `index % 3` split here would send every wire-breaking input to
     * `KINDS[0]` and none to the other two. §5.2's request path would then never see a control
     * character at a decoder, and the wire bound would only ever be exercised on a request. Five
     * is coprime with both 3 and the thirteen-long mutation cycle, so every
     * (mutation, kind, breach) combination occurs.
     */
    private fun breachesTheWire(draft: Input): Boolean = draft.index % 5 == 0

    /** [tags] with one randomly chosen tag rewritten by [edit]. */
    private fun mapOneTag(
        random: Random,
        tags: List<List<String>>,
        edit: (List<String>) -> List<String>,
    ): List<List<String>> {
        if (tags.isEmpty()) return tags
        val target = random.nextInt(tags.size)
        return tags.mapIndexed { at, tag -> if (at == target) edit(tag) else tag }
    }

    /** [tags] with one randomly chosen tag **value** — never a tag name — rewritten by [edit]. */
    private fun mapOneValue(
        random: Random,
        tags: List<List<String>>,
        edit: (String) -> String,
    ): List<List<String>> = mapOneTag(random, tags) { tag ->
        if (tag.size < 2) tag else tag.mapIndexed { at, value -> if (at == 1) edit(value) else value }
    }

    /** Every 64-character hex value in [tags] replaced by [replacement], coordinates included. */
    private fun replaceHex(tags: List<List<String>>, replacement: String): List<List<String>> =
        tags.map { tag ->
            tag.map { value ->
                when {
                    value.length == 64 && value.all { it in "0123456789abcdef" } -> replacement
                    value.count { it == ':' } >= 2 -> {
                        val fields = value.split(":", limit = 3)
                        "${fields[0]}:$replacement:${fields[2]}"
                    }
                    else -> value
                }
            }
        }

    /** Every all-digit value in [tags] replaced by [replacement] — timestamps, amounts, kinds. */
    private fun replaceNumbers(tags: List<List<String>>, replacement: String): List<List<String>> =
        tags.map { tag ->
            tag.mapIndexed { at, value ->
                if (at > 0 && value.isNotEmpty() && value.all { it.isDigit() }) replacement else value
            }
        }
}
