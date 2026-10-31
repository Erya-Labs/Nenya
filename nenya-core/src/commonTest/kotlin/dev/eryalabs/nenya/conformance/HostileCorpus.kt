package dev.eryalabs.nenya.conformance

import dev.eryalabs.nenya.JdkRandom
import dev.eryalabs.nenya.bid.BidFixtures
import dev.eryalabs.nenya.channel.RumorWriterFixtures
import dev.eryalabs.nenya.settlement.Bolt11Composer
import dev.eryalabs.nenya.settlement.Bolt11Examples
import dev.eryalabs.nenya.settlement.Bolt11Parts
import dev.eryalabs.nenya.settlement.Bolt11TaggedField
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.tag.TagFixtures
import dev.eryalabs.nenya.tag.TagLimits
import dev.eryalabs.nenya.wire.WireLimits

/**
 * The generator behind the hostile-input sweep: well-formed fixtures, broken every way §4.3
 * anticipates.
 *
 * This is not a scratch file. The queue's Definition of done forbids an encoded value appearing in
 * a test as something somebody typed out, so nothing here is: every pubkey is a SHA-256 digest of a
 * per-index label (`TagFixtures.pubkeyFor`), every base fixture is `TagFixtures`', and every string
 * is assembled by `java.util.Random`'s algorithm ([JdkRandom]) pinned to [SEED]. A reviewer can
 * change [SEED], re-run the suite, and every property must still hold.
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
     * Pinned so a failure is reproducible. `java.util.Random`'s algorithm rather than `kotlin.random`
     * for the reason `WireFixtures` gives: it is specified by the JDK, so this file produces the
     * same runs on any JVM a reviewer re-runs it on — and, as [JdkRandom], the same runs on JavaScript.
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
     * The same, for a §7.4 rumor: §7.4's own `order` and `type`, the §5.3 rows the order messages
     * carry, §7.5's `amount_msat`, §8.6's `payee` and `payment`, and §10's `x`. A name the base does
     * not carry is appended once rather than duplicated — still hostile, as an unexpected row.
     */
    private val RUMOR_SINGLE_OCCURRENCE: List<String> =
        listOf("nenya", "order", "type", "item", "price", "fee", "amount_msat", "payee", "payment", "x")

    /** §7.4's two discriminators, which the rumor corpus's decimal mutations leave alone ([numbers]). */
    private val RUMOR_DISCRIMINATORS: Set<String> = setOf("nenya", "type")

    /**
     * One rumor corpus entry: the `RumorWriterFixtures` case its base event was built from, and that
     * event with one [Mutation] applied.
     *
     * The case is carried so a [Mutation.WELL_FORMED] entry can be held field for field against the
     * values it was written from, and so a floor can be counted per message shape.
     */
    internal class RumorInput(val case: RumorWriterFixtures.Case, val input: Input) {

        /** Names the shape, the mutation and the index, and no value at all — as [Input] does. */
        override fun toString(): String = "RumorInput(${case.shape}, ${input.mutation}, #${input.index})"
    }

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
        val random = JdkRandom(SEED)
        val mutations = Mutation.entries
        return List(count) { index -> input(random, index, mutations[index % mutations.size]) }
    }

    /**
     * [count] §7.4 rumors from one seeded run: the event `RumorWriterFixtures` builds for each of its
     * cases — every [RumorWriterFixtures.Shape], cycled — with one [Mutation] applied, the mutations
     * cycled as in [corpus].
     *
     * The same [SEED] drives the mutations, from a [JdkRandom] of its own, so [corpus]'s output does
     * not move. Wrong-length hex and the three decimal mutations break **one** value here rather
     * than every one — see [numbers] for what breaking them all did to a rumor. Eight shapes and thirteen mutations are coprime, so a count that is a multiple of
     * their product draws every (shape, mutation) pair equally; and five, [breachesTheWire]'s
     * modulus, is coprime with both, for the reason that function gives.
     */
    fun rumorCorpus(count: Int): List<RumorInput> {
        val random = JdkRandom(SEED)
        val mutations = Mutation.entries
        return RumorWriterFixtures.cases(count).mapIndexed { index, case ->
            val event = RumorWriterFixtures.build(case)
            val draft = Input(
                mutation = mutations[index % mutations.size],
                index = index,
                kind = event.kind,
                pubkey = event.pubkey,
                createdAt = event.createdAt,
                tags = event.tags,
                content = event.content,
                text = "nenya-rumor-$index",
                hex = event.pubkey,
                coordinate = "${event.kind}:${event.pubkey}:listing-$index",
                decimal = index.toString(),
            )
            RumorInput(case, mutate(random, draft, RUMOR_SINGLE_OCCURRENCE, oneAtATime = true))
        }
    }

    /**
     * The [Mutation]s that have no meaning on an invoice string, each with the reason — skipped by
     * name rather than applied to something they were not written for.
     *
     * Every other mutation is applied to the string: the case, empty, truncated, oversized and
     * control-character ones to the whole string as it arrives, and the three decimal ones to
     * Appendix C's amount in the human-readable part, the one decimal an invoice carries in the clear.
     */
    val INVOICE_SKIPPED: Map<Mutation, String> = mapOf(
        Mutation.WRONG_ARITY to "a tag's element count; an invoice is one string, and its tagged fields' " +
            "own lengths are the FieldMutation constants",
        Mutation.DUPLICATED_TAG to "§4.3's duplicate rule is about an event's tag list; a repeated tagged " +
            "field is FieldMutation.DUPLICATED",
        Mutation.HEX_WRONG_LENGTH to "a 64-character hex value; an invoice carries none in the clear, and a " +
            "wrong-length payment hash is FieldMutation.TRUNCATED or LENGTHENED on a `p` field",
        Mutation.JUST_OVER_BOUND to "§4.3's event bounds — tags, tag bytes, content, images — none of which " +
            "an invoice string has",
    )

    /** One tagged field of a vendored invoice changed, and the invoice recomposed with its checksum recomputed. */
    internal enum class FieldMutation {

        /** The field removed — a `p`, `s` or `d` gone is what Appendix C requires be refused. */
        DROPPED,

        /** A second copy straight after the first — two `p` fields, two `x`, a `d` beside a `d`. */
        DUPLICATED,

        /** The field's groups cut to half, so its `data_length` and its value are both short. */
        TRUNCATED,

        /** One to eight drawn groups appended, so a fixed-length field is no longer that length. */
        LENGTHENED,

        /** The field's type character replaced by another drawn from the bech32 alphabet. */
        RETYPED,
    }

    /**
     * One invoice corpus entry: which vendored example it came from, the one change made to it, and
     * the string.
     *
     * [composed] is whether `Bolt11Composer` put it back together with the bech32 checksum recomputed —
     * every [FieldMutation] entry and the three decimal ones. Those are the entries that reach past
     * `Bolt11Invoice.parse`'s checksum; a raw string edit almost never does.
     */
    internal data class InvoiceInput(
        val index: Int,
        val example: String,
        val mutation: Mutation?,
        val fieldMutation: FieldMutation?,
        val composed: Boolean,
        val invoice: String,
    ) {

        /**
         * Names the index, the example and the change, and no character of the invoice: §12 item 2
         * bars an invoice string from every public event, this library redacts one from anything it
         * prints for the reason `Bolt11Reference.toString` gives, and a failure is reproducible from
         * [SEED] anyway.
         */
        override fun toString(): String = "InvoiceInput(#$index, ${fieldMutation ?: mutation}, $example)"
    }

    /** Draws per (example, [FieldMutation]) pair: each draw picks its own field and, where it needs one, its own groups. */
    const val FIELD_DRAWS: Int = 3

    /**
     * Every all-lowercase valid example in the vendored BOLT-11 document, under every [Mutation] not in
     * [INVOICE_SKIPPED] and [FIELD_DRAWS] times under every [FieldMutation], from one seeded run.
     *
     * Only the lowercase valid examples, because they are the ones `Bolt11Composer` is held to
     * (`compose(decompose(x)) == x`); the uppercase one and the invalid ones are already parser
     * vectors of their own in `Bolt11InvoiceTest`. Nothing here is typed: every string is a vendored
     * one, a raw edit of one, or one recomposed by the composer (decision **D**).
     */
    fun invoiceCorpus(): List<InvoiceInput> {
        val random = JdkRandom(SEED)
        val examples = Bolt11Examples.extract().filter {
            it.group == Bolt11Examples.Group.VALID && it.invoice == it.invoice.lowercase()
        }
        val out = ArrayList<InvoiceInput>()
        for (example in examples) {
            val name = "line ${example.headingLine}"
            for (mutation in Mutation.entries) {
                if (mutation in INVOICE_SKIPPED) continue
                val (invoice, composed) = mutatedInvoice(random, example.invoice, mutation)
                out += InvoiceInput(out.size, name, mutation, null, composed, invoice)
            }
            for (fieldMutation in FieldMutation.entries) {
                repeat(FIELD_DRAWS) {
                    val invoice = Bolt11Composer.compose(mutatedField(random, Bolt11Composer.decompose(example.invoice), fieldMutation))
                    out += InvoiceInput(out.size, name, null, fieldMutation, true, invoice)
                }
            }
        }
        return out
    }

    /** [invoice] under [mutation], and whether it was recomposed with its checksum recomputed. */
    private fun mutatedInvoice(random: JdkRandom, invoice: String, mutation: Mutation): Pair<String, Boolean> =
        when (mutation) {
            Mutation.WELL_FORMED -> invoice to false
            Mutation.TRUNCATED -> invoice.take(invoice.length / 2) to false
            Mutation.OVERSIZED -> invoice + "q".repeat(4_096 + random.nextInt(1_024)) to false
            Mutation.EMPTY -> "" to false
            Mutation.CONTROL_CHARACTERS -> {
                val at = random.nextInt(invoice.length + 1)
                invoice.substring(0, at) + CONTROL_PIECES[random.nextInt(CONTROL_PIECES.size)] + invoice.substring(at) to false
            }
            Mutation.HEX_MIXED_CASE ->
                invoice.mapIndexed { at, ch -> if (at % 2 == 0) ch.uppercaseChar() else ch }.joinToString("") to false
            Mutation.NON_CANONICAL_DECIMAL, Mutation.SIGNED_DECIMAL, Mutation.EXPONENTIAL_DECIMAL -> {
                val parts = Bolt11Composer.decompose(invoice)
                val written = parts.amount.ifEmpty { "1" }
                val digits = written.takeWhile { it in '0'..'9' }
                val letter = written.substring(digits.length)
                val amount = when (mutation) {
                    Mutation.NON_CANONICAL_DECIMAL -> when (random.nextInt(4)) {
                        0 -> "00$digits$letter"
                        1 -> "0x$digits$letter"
                        2 -> "1_000$letter"
                        else -> " $digits$letter "
                    }
                    Mutation.SIGNED_DECIMAL -> (if (random.nextBoolean()) "-" else "+") + digits + letter
                    else -> (if (random.nextBoolean()) "1e${1 + random.nextInt(9)}" else "1.5E2") + letter
                }
                // Built rather than `withAmount`, which refuses exactly these spellings: the composer
                // puts any human-readable part into the checksum, so the refusal has to come from Nenya.
                Bolt11Composer.compose(
                    Bolt11Parts(parts.networkPrefix + amount, parts.timestamp, parts.fields, parts.signature, parts.checksum),
                ) to true
            }
            Mutation.WRONG_ARITY, Mutation.DUPLICATED_TAG, Mutation.HEX_WRONG_LENGTH, Mutation.JUST_OVER_BOUND ->
                error("$mutation is skipped on an invoice: ${INVOICE_SKIPPED[mutation]}")
        }

    /** [parts] with one drawn tagged field changed by [mutation]. */
    private fun mutatedField(random: JdkRandom, parts: Bolt11Parts, mutation: FieldMutation): Bolt11Parts {
        val fields = parts.fields
        val at = random.nextInt(fields.size)
        val field = fields[at]
        val changed: List<Bolt11TaggedField> = when (mutation) {
            FieldMutation.DROPPED -> emptyList()
            FieldMutation.DUPLICATED -> listOf(field, field)
            FieldMutation.TRUNCATED -> listOf(Bolt11TaggedField(field.type, field.groups.take(field.groups.size / 2)))
            FieldMutation.LENGTHENED ->
                listOf(Bolt11TaggedField(field.type, field.groups + List(1 + random.nextInt(8)) { random.nextInt(32) }))
            FieldMutation.RETYPED -> {
                val others = Bolt11Composer.ALPHABET.filter { it != field.type }
                listOf(Bolt11TaggedField(others[random.nextInt(others.length)], field.groups))
            }
        }
        return parts.withFields(fields.subList(0, at) + changed + fields.subList(at + 1, fields.size))
    }

    private fun input(random: JdkRandom, index: Int, mutation: Mutation): Input {
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

    private fun mutate(
        random: JdkRandom,
        draft: Input,
        singleOccurrence: List<String> = SINGLE_OCCURRENCE,
        oneAtATime: Boolean = false,
    ): Input = when (draft.mutation) {
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
            val name = singleOccurrence[random.nextInt(singleOccurrence.size)]
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
                tags = if (oneAtATime) {
                    replaceOne(random, draft.tags, { _, value -> isHex64(value) || isCoordinate(value) }) { value ->
                        val cut = wrong.length < draft.pubkey.length
                        if (isCoordinate(value)) {
                            val fields = value.split(":", limit = 3)
                            "${fields[0]}:${if (cut) fields[1].dropLast(1) else fields[1] + "a"}:${fields[2]}"
                        } else if (cut) value.dropLast(1) else value + "a"
                    }
                } else replaceHex(draft.tags, wrong),
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
            draft.copy(tags = numbers(random, draft.tags, value, oneAtATime), decimal = value, coordinate = "0$value:${draft.pubkey}:d")
        }

        Mutation.SIGNED_DECIMAL -> {
            val value = if (random.nextBoolean()) "-${1 + draft.index}" else "+${1 + draft.index}"
            draft.copy(tags = numbers(random, draft.tags, value, oneAtATime), decimal = value, coordinate = "$value:${draft.pubkey}:d")
        }

        Mutation.EXPONENTIAL_DECIMAL -> {
            val value = if (random.nextBoolean()) "1e${1 + random.nextInt(9)}" else "1.5E2"
            draft.copy(tags = numbers(random, draft.tags, value, oneAtATime), decimal = value, coordinate = "$value:${draft.pubkey}:d")
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
        random: JdkRandom,
        tags: List<List<String>>,
        edit: (List<String>) -> List<String>,
    ): List<List<String>> {
        if (tags.isEmpty()) return tags
        val target = random.nextInt(tags.size)
        return tags.mapIndexed { at, tag -> if (at == target) edit(tag) else tag }
    }

    /** [tags] with one randomly chosen tag **value** — never a tag name — rewritten by [edit]. */
    private fun mapOneValue(
        random: JdkRandom,
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

    /**
     * [replaceNumbers], or — for the rumor corpus — the same replacement in **one** all-digit value
     * chosen at random, never §7.4's `nenya` or `type`.
     *
     * Measured before this existed: replacing every number on a rumor rewrote §7.4's `nenya` and
     * `type` too, `attribute` refused all 240 decimal-mutated rumors as `MALFORMED_TAG` or
     * `MALFORMED_TYPE`, and no decoder above it ever saw a non-canonical `amount_msat` or `size` —
     * which is where an unhardened `toInt()` would sit. Those two discriminators' strictness is
     * `attribute`'s own and is swept by the wrong-length, empty and truncated mutations here.
     */
    private fun numbers(
        random: JdkRandom,
        tags: List<List<String>>,
        replacement: String,
        oneAtATime: Boolean,
    ): List<List<String>> = if (!oneAtATime) replaceNumbers(tags, replacement) else {
        replaceOne(random, tags, { name, value ->
            name !in RUMOR_DISCRIMINATORS && value.isNotEmpty() && value.all { it.isDigit() }
        }) { replacement }
    }

    /**
     * [tags] with **one** value, chosen at random among those [eligible] accepts by tag name and
     * value — never a tag name — rewritten by [edit]. Unchanged when no value is eligible.
     */
    private fun replaceOne(
        random: JdkRandom,
        tags: List<List<String>>,
        eligible: (name: String, value: String) -> Boolean,
        edit: (String) -> String,
    ): List<List<String>> {
        val spots = tags.flatMapIndexed { at, tag ->
            (1 until tag.size).filter { eligible(tag[0], tag[it]) }.map { at to it }
        }
        if (spots.isEmpty()) return tags
        val (target, position) = spots[random.nextInt(spots.size)]
        return tags.mapIndexed { at, tag ->
            if (at != target) tag else tag.mapIndexed { place, value -> if (place == position) edit(value) else value }
        }
    }

    private fun isHex64(value: String): Boolean = value.length == 64 && value.all { it in "0123456789abcdef" }

    private fun isCoordinate(value: String): Boolean = value.count { it == ':' } >= 2

    /** Every all-digit value in [tags] replaced by [replacement] — timestamps, amounts, kinds. */
    private fun replaceNumbers(tags: List<List<String>>, replacement: String): List<List<String>> =
        tags.map { tag ->
            tag.mapIndexed { at, value ->
                if (at > 0 && value.isNotEmpty() && value.all { it.isDigit() }) replacement else value
            }
        }
}
