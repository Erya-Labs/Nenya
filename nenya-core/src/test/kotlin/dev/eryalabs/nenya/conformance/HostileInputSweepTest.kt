package dev.eryalabs.nenya.conformance

import dev.eryalabs.nenya.bid.Bid
import dev.eryalabs.nenya.listing.Listing
import dev.eryalabs.nenya.listing.ListingSide
import dev.eryalabs.nenya.listing.ListingStatusCodec
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.tag.Coordinate
import dev.eryalabs.nenya.tag.ImageRef
import dev.eryalabs.nenya.tag.ItemRef
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.tag.NenyaTags
import dev.eryalabs.nenya.tag.PubkeyRef
import dev.eryalabs.nenya.tag.TagContext
import dev.eryalabs.nenya.tag.TagFixtures
import dev.eryalabs.nenya.tag.TagSet
import dev.eryalabs.nenya.tag.TagWriter
import dev.eryalabs.nenya.wire.CheckedEvent
import dev.eryalabs.nenya.wire.EventId
import dev.eryalabs.nenya.wire.WireEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * §4.3's opening sentence, run against every decoder this library has: "Every event reaching an
 * implementation is hostile input, including events it believes it authored."
 *
 * ### What is being asserted, and what is not
 *
 * **Not** "is this input rejected". A good half of [HostileCorpus]'s entries are legal — §4.3's
 * unknown tags, an uppercase pubkey, a bid with no `expiration` — and a sweep asserting rejection
 * would be asserting the opposite of §5.1's and §6's accepting-direction rules. What is asserted is
 * *how* each entry point declines: every input must produce either a value or one of this library's
 * own exception types **carrying a named reason**, and never one of the six shapes an unhardened
 * decoder actually produces:
 *
 * `NullPointerException`, `IndexOutOfBoundsException`, `StringIndexOutOfBoundsException`,
 * `NumberFormatException`, `ClassCastException`, `ArithmeticException`.
 *
 * Each of those is a denial of service in the buyer's client rather than a rejection: the caller
 * cannot tell "this event is malformed" from "this library is broken", and a relay that can produce
 * one on demand can crash every client on the board. That is the whole point of the sweep, and the
 * harness self-control below — a deliberately unhardened probe calling `String.toInt()`, run
 * through the same harness and required to **fail** it — is what proves it can fail at all.
 *
 * ### Completeness is checked rather than claimed
 *
 * The entry points are named explicitly in [swept] — a list a human reads — and every public
 * function in `wire`, `tag`, `listing` and `bid` that takes a `CheckedEvent`, a tag list, a
 * `String` or a `WireEvent` is asserted to be in it. So a decoder added by a later task is not
 * silently outside the sweep; it turns this red until somebody sweeps it.
 *
 * Following T5's rule, the sweep **fails hard** on an entry point it never managed to invoke and
 * never skips one: a sweep that quietly passed over what it could not build arguments for would
 * satisfy the letter of "enumerated by reflection" while defeating the entire point of it.
 */
class HostileInputSweepTest {

    private companion object {

        /** Thirteen mutation kinds, so each is drawn a hundred times. */
        const val CORPUS: Int = 1_300

        /** The four packages this round built a decoder in. */
        val DECODER_PACKAGES: List<String> = listOf(
            "dev.eryalabs.nenya.wire",
            "dev.eryalabs.nenya.tag",
            "dev.eryalabs.nenya.listing",
            "dev.eryalabs.nenya.bid",
        )

        /** Anything in here is this library's own refusal. Anything outside it is a bug. */
        const val LIBRARY_PACKAGE: String = "dev.eryalabs.nenya."

        /** An `https:` URL §5.3 accepts, so a hostile `dimensions` is actually reached. */
        const val WELL_FORMED_URL: String = "https://example.invalid/nenya.png"

        /**
         * The shapes an unhardened decoder produces, named so the failure message says which one
         * was seen rather than only that something was thrown.
         */
        val UNHARDENED: List<String> = listOf(
            "java.lang.NullPointerException",
            "java.lang.IndexOutOfBoundsException",
            "java.lang.StringIndexOutOfBoundsException",
            "java.lang.NumberFormatException",
            "java.lang.ClassCastException",
            "java.lang.ArithmeticException",
        )

        /**
         * Entry points swept although the reflection filter does not name them — pinned, so the
         * two lists stay honest in both directions.
         *
         * Each is a door hostile data genuinely walks through whose signature happens to mention
         * none of the four parameter shapes: a constructor (Kotlin publishes `internal` ones to the
         * JVM, so a constructor-completeness assertion would pin this library's plumbing rather
         * than its surface), or a function whose only parameter is an injected limit.
         */
        val SWEPT_BEYOND_THE_FILTER: Set<String> = setOf(
            "WireEvent.<init>",
            "WireEvent.canonicalSerialisation",
            "Coordinate.<init>",
            "ImageRef.<init>",
            "PubkeyRef.<init>",
            "ItemRef.<init>",
            "Listing.encode",
            "Bid.encode",
            "TagSet.republish",
        )
    }

    /** What an entry point needs before it can be invoked at all. */
    private enum class Needs { NOTHING, EVENT, CHECKED, TAG_SET }

    /**
     * One swept entry point: the label the reflection filter also produces, and the **list** of
     * calls it makes for one corpus entry.
     *
     * A list rather than one lambda, because the first shape it is worth reading this file for:
     * an entry point that takes a `String` is fed all four of [Context.strings], and if the four
     * calls sat inside one lambda the first refusal would abort the other three. `EventId.ofHex`
     * would then never see the wrong-length hex, because `input.text` is not hex and throws
     * first. Each call is run and counted separately.
     */
    private class Target(
        val label: String,
        val needs: Needs,
        val calls: (Context) -> List<() -> Unit>,
    )

    /** One corpus entry, plus whatever of it survived the layers below the entry point. */
    private class Context(
        val input: HostileCorpus.Input,
        val event: WireEvent?,
        val checked: CheckedEvent?,
        val tagSet: TagSet?,
    ) {

        /** The four hostile strings, so a String-taking door sees shapes it was not written for. */
        val strings: List<String>
            get() = listOf(input.text, input.hex, input.coordinate, input.decimal)
    }

    // -----------------------------------------------------------------------------------------
    // The swept entry points.
    // -----------------------------------------------------------------------------------------

    private fun swept(): List<Target> = listOf(
        // ---- wire -----------------------------------------------------------------------------
        Target("WireEvent.<init>", Needs.NOTHING) { c -> listOf { build(c.input) } },
        Target("WireEvent.canonicalSerialisation", Needs.EVENT) { c ->
            listOf { c.event!!.canonicalSerialisation() }
        },
        Target("EventId\$Companion.of", Needs.EVENT) { c -> listOf { EventId.of(c.event!!) } },
        Target("EventId\$Companion.ofHex", Needs.NOTHING) { c ->
            c.strings.map { text -> { EventId.ofHex(text); Unit } }
        },
        Target("CheckedEvent\$Companion.checkEventId", Needs.EVENT) { c ->
            c.strings.map { text -> { CheckedEvent.checkEventId(text, c.event!!); Unit } }
        },

        // ---- tag ------------------------------------------------------------------------------
        Target("TagSet\$Companion.read", Needs.CHECKED) { c ->
            listOf { TagSet.read(c.checked!!, contextFor(c.input.kind)); Unit }
        },
        Target("TagSet.occurrences", Needs.TAG_SET) { c ->
            c.strings.map { text -> { c.tagSet!!.occurrences(text); Unit } }
        },
        Target("TagSet.republish", Needs.TAG_SET) { c -> listOf { c.tagSet!!.republish(); Unit } },
        Target("Coordinate\$Companion.parse", Needs.NOTHING) { c ->
            c.strings.map { text -> { Coordinate.parse(text); Unit } }
        },
        // Each of the next four is called twice per string: once with the hostile value in the
        // field that is checked first, and once with it in a field a well-formed prefix would
        // otherwise shield. A `PubkeyRef(text, text)` alone never reaches the relay-hint branch,
        // because the pubkey is refused before the hint is looked at.
        Target("Coordinate.<init>", Needs.NOTHING) { c ->
            c.strings.flatMap { text ->
                listOf<() -> Unit>(
                    { Coordinate(c.input.kind, text, text); Unit },
                    { Coordinate(c.input.kind, wellFormedPubkey(c.input), text); Unit },
                )
            }
        },
        Target("ImageRef.<init>", Needs.NOTHING) { c ->
            c.strings.flatMap { text ->
                listOf<() -> Unit>(
                    { ImageRef(text, text); Unit },
                    { ImageRef(WELL_FORMED_URL, text); Unit },
                )
            }
        },
        Target("PubkeyRef.<init>", Needs.NOTHING) { c ->
            c.strings.flatMap { text ->
                listOf<() -> Unit>(
                    { PubkeyRef(text, text); Unit },
                    { PubkeyRef(wellFormedPubkey(c.input), text); Unit },
                )
            }
        },
        Target("ItemRef.<init>", Needs.NOTHING) { c ->
            // The coordinate is built rather than parsed: an ItemRef target whose first statement
            // is `Coordinate.parse(hostile)` never constructs an ItemRef at all, and would be
            // sweeping `Coordinate.parse` a second time under another name.
            c.strings.map { text -> { ItemRef(wellFormedCoordinate(c.input), text); Unit } }
        },
        Target("TagWriter.topic", Needs.NOTHING) { c ->
            c.strings.map { text -> { TagWriter.topic(text); Unit } }
        },
        Target("TagWriter.d", Needs.NOTHING) { c -> c.strings.map { text -> { TagWriter.d(text); Unit } } },
        Target("TagWriter.timestamp", Needs.NOTHING) { c ->
            c.strings.map { text -> { TagWriter.timestamp(text, c.input.createdAt); Unit } }
        },
        Target("TagWriter.fee", Needs.NOTHING) { c ->
            c.strings.map { text -> { TagWriter.fee(FeeTerm.of(250), text); Unit } }
        },
        Target("NenyaTags.byName", Needs.NOTHING) { c ->
            c.strings.map { text -> { NenyaTags.byName(text); Unit } }
        },

        // ---- listing --------------------------------------------------------------------------
        Target("ListingSide\$Companion.byToken", Needs.NOTHING) { c ->
            c.strings.map { text -> { ListingSide.byToken(text); Unit } }
        },
        Target("ListingStatusCodec.read", Needs.NOTHING) { c ->
            // Both a listing kind and the corpus entry's own, which is `kind:1111` a third of the
            // time: `vocabulary` refuses that before the token is read, so a sweep using only the
            // entry's kind would never read a token at all on those inputs.
            c.strings.flatMap { text ->
                listOf<() -> Unit>(
                    { ListingStatusCodec.read(text, NenyaKind.REQUEST); Unit },
                    { ListingStatusCodec.read(text, c.input.kind); Unit },
                )
            }
        },
        Target("Listing\$Companion.decode", Needs.CHECKED) { c -> listOf { Listing.decode(c.checked!!); Unit } },
        // The two encoders run only on input their decoder accepted, so the invocation counter
        // below counts attempts rather than calls for these two. That the calls really happen —
        // and that they round-trip to the same event id — is asserted separately, by
        // `the corpus reaches the tag, listing and bid codecs in both directions`.
        Target("Listing.encode", Needs.CHECKED) { c ->
            listOf { runCatching { Listing.decode(c.checked!!) }.getOrNull()?.encode(); Unit }
        },

        // ---- bid ------------------------------------------------------------------------------
        Target("Bid\$Companion.decode", Needs.CHECKED) { c -> listOf { Bid.decode(c.checked!!); Unit } },
        Target("Bid.encode", Needs.CHECKED) { c ->
            listOf { runCatching { Bid.decode(c.checked!!) }.getOrNull()?.encode(); Unit }
        },
    )

    // -----------------------------------------------------------------------------------------
    // The sweep.
    // -----------------------------------------------------------------------------------------

    @Test
    fun `every decoder refuses hostile input by name, never by unhandled exception`() {
        val corpus = HostileCorpus.corpus(CORPUS)
        val targets = swept()
        val invocations = targets.associate { it.label to 0 }.toMutableMap()
        val problems = mutableListOf<String>()

        for (input in corpus) {
            val context = contextOf(input)
            for (target in targets) {
                if (!context.satisfies(target.needs)) continue
                for (call in target.calls(context)) {
                    invocations[target.label] = invocations.getValue(target.label) + 1
                    problemWith(target.label, input, call)?.let { problems += it }
                }
            }
        }

        // T5's rule: fail hard on an entry point never invoked rather than skipping it quietly. A
        // target that never ran is a target whose hardening this sweep says nothing about.
        val never = invocations.filterValues { it == 0 }.keys
        assertTrue(
            never.isEmpty(),
            "these entry points were never invoked over a corpus of ${corpus.size}, so the sweep " +
                "proves nothing about them: $never",
        )
        // Raised alongside the per-call counting above: the cheapest target now runs once per
        // input that reached a TagSet, so a threshold of 20 no longer discriminates. The real
        // guard is `never.isEmpty()`; this one catches a target that ran on a handful of inputs.
        assertTrue(
            invocations.values.all { it > 200 },
            "an entry point ran too few times to have seen every mutation kind: " +
                invocations.filterValues { it <= 200 },
        )

        assertEquals(
            emptyList(),
            problems.take(20),
            "a decoder threw something that is not a named rejection. Each of ${UNHARDENED} is a " +
                "denial of service in the buyer's client rather than a refusal (§4.3), and the " +
                "caller cannot tell a malformed event from a broken library. " +
                "${problems.size} failure(s) in total.",
        )
    }

    /**
     * The control that proves the harness can fail: a deliberately unhardened function, local to
     * this test, run through the same [problemWith] the sweep uses.
     *
     * Without it every assertion above is satisfiable by a harness that swallows everything.
     */
    @Test
    fun `an unhardened probe fails the sweep harness`() {
        val corpus = HostileCorpus.corpus(CORPUS)
        val problems = mutableListOf<String>()

        for (input in corpus) {
            // The one-line decoder an implementer writes before reading §4.3: no length check, no
            // digit check, and a NumberFormatException for the caller.
            problemWith("probe.unhardened", input) { unhardenedProbe(input.decimal) }?.let { problems += it }
        }

        assertTrue(
            problems.isNotEmpty(),
            "the unhardened probe passed the sweep harness, so the harness cannot fail and every " +
                "other assertion in this file is vacuous",
        )
        assertTrue(
            problems.any { "NumberFormatException" in it },
            "the harness must name the shape it saw; it reported ${problems.first()}",
        )
        // And the same probe, hardened the way this library's decoders are, must pass — so the
        // control is about the hardening rather than about the input.
        for (input in HostileCorpus.corpus(CORPUS)) {
            assertTrue(
                problemWith("probe.hardened", input) { hardenedProbe(input.decimal) } == null,
                "the hardened probe must pass the same harness on the same corpus",
            )
        }
    }

    // -----------------------------------------------------------------------------------------
    // Completeness, and the corpus floor.
    // -----------------------------------------------------------------------------------------

    /**
     * Every public function in the four decoder packages that takes hostile data is in [swept].
     *
     * The four parameter shapes hostile data arrives in: a `String` (a tag value, a claimed id, a
     * coordinate), a `List` (a tag list), a `CheckedEvent` (T8's narrowest door) and a `WireEvent`.
     * Compiler-generated enum members are excluded — see [MainClasses.isCompilerGeneratedEnumMember].
     */
    @Test
    fun `every published decode entry point in the four packages is swept`() {
        val reflected = reflectedEntryPoints()
        val sweptLabels = swept().map { it.label }.toSet()

        assertTrue(
            reflected.size > 10,
            "the reflection filter found only ${reflected.size} entry point(s) across " +
                "$DECODER_PACKAGES, which is not this library: $reflected",
        )
        assertEquals(
            emptySet(),
            reflected - sweptLabels,
            "these published functions take hostile input and no test feeds them any. A decoder " +
                "added later is not silently outside this sweep: either sweep it, or say in its " +
                "KDoc why it is not a decoder.",
        )
        assertEquals(
            SWEPT_BEYOND_THE_FILTER,
            sweptLabels - reflected,
            "the swept list carries entries the reflection filter does not name. That is allowed " +
                "and pinned — see SWEPT_BEYOND_THE_FILTER — but a new one must be justified " +
                "rather than appearing.",
        )
    }

    /** The corpus floor: non-empty, and every mutation kind actually drawn. */
    @Test
    fun `the corpus carries every mutation kind`() {
        val corpus = HostileCorpus.corpus(CORPUS)

        assertTrue(corpus.isNotEmpty(), "an empty corpus would make the sweep vacuously green")
        val counts = HostileCorpus.Mutation.entries.associateWith { mutation ->
            corpus.count { it.mutation == mutation }
        }
        for ((mutation, count) in counts) {
            assertTrue(
                count > 0,
                "the corpus drew $mutation zero times, so nothing was swept for it: $counts",
            )
        }
        // A generator that silently produced only well-formed input cannot pass: it is one kind of
        // thirteen, and the twelve that break something have to be there too.
        assertTrue(
            counts.getValue(HostileCorpus.Mutation.WELL_FORMED) < corpus.size / 2,
            "most of the corpus is well-formed, which is a corpus of fixtures rather than of " +
                "hostile input: $counts",
        )
        // Reproducible from the seed: two runs must be identical, or a failure cannot be re-run.
        // Compared as values rather than as strings — `Input.toString` redacts down to the
        // mutation and the index, both of which are pure functions of the list position, so a
        // string comparison here would pass just as happily over an unseeded `Random()`.
        assertEquals(corpus, HostileCorpus.corpus(CORPUS))
        assertTrue(
            corpus.map { it.tags }.toSet().size > corpus.size / 2,
            "the corpus repeats itself: only ${corpus.map { it.tags }.toSet().size} distinct tag " +
                "lists across ${corpus.size} inputs",
        )
    }

    /**
     * The corpus reaches past the wire layer, and the encoders really run.
     *
     * Without this the sweep could be green because every input died in `WireEvent`'s constructor
     * and no tag, listing or bid decoder ever ran on anything but well-formed input. And since
     * every listing and bid this reaches was **derived from a mutated fixture**, the re-encoded
     * event id is §4.3's round-trip guarantee proved over hostile input rather than over the
     * well-formed corpora `ListingPropertyTest` and `BidPropertyTest` use: an event this library
     * did not author, carrying tags it does not model, must come back out byte-identical or a
     * stranger's signature stops verifying.
     */
    @Test
    fun `the corpus reaches the tag, listing and bid codecs in both directions`() {
        var checked = 0
        var tagSets = 0
        var listings = 0
        var bids = 0
        // Per mutation kind, because that is the floor that discriminates. An aggregate count is
        // met by the well-formed tenth of the corpus on its own, so a regression in which every
        // *mutated* input died at the wire layer would leave an aggregate assertion green while
        // this test's whole claim — that the decoders see broken input — became false.
        val reached = HostileCorpus.Mutation.entries.associateWith { 0 }.toMutableMap()
        for (input in HostileCorpus.corpus(CORPUS)) {
            val context = contextOf(input)
            val event = context.checked ?: continue
            checked++
            reached[input.mutation] = reached.getValue(input.mutation) + 1
            if (context.tagSet != null) tagSets++
            runCatching { Listing.decode(event) }.getOrNull()?.let { listing ->
                if (input.mutation != HostileCorpus.Mutation.WELL_FORMED) listings++
                assertEquals(
                    event.id.toHex(),
                    EventId.of(listing.encode()).toHex(),
                    "a listing decoded from $input did not re-encode to the same event id",
                )
            }
            runCatching { Bid.decode(event) }.getOrNull()?.let { bid ->
                if (input.mutation != HostileCorpus.Mutation.WELL_FORMED) bids++
                assertEquals(
                    event.id.toHex(),
                    EventId.of(bid.encode()).toHex(),
                    "a bid decoded from $input did not re-encode to the same event id",
                )
            }
        }

        val perMutation = CORPUS / HostileCorpus.Mutation.entries.size
        for ((mutation, count) in reached) {
            // The smallest of these is JUST_OVER_BOUND, whose four variants are chosen by index
            // rather than drawn, so its count is a fixed 25 rather than a binomial draw around it.
            // A drawn variant would put this floor inside one standard deviation, and a reviewer
            // changing SEED — which HostileCorpus invites — would see it fail for no reason.
            assertTrue(
                count > perMutation / 5,
                "only $count of about $perMutation $mutation inputs got past the wire layer, so " +
                    "the tag, listing and bid decoders were barely swept for it: $reached",
            )
        }
        assertTrue(checked > CORPUS / 2, "only $checked of $CORPUS inputs became a CheckedEvent")
        assertTrue(tagSets > 300, "only $tagSets inputs reached a TagSet, so §5.3's codec was barely swept")
        // Counted over **mutated** inputs only: the well-formed tenth alone yields more listings
        // than a naive floor here would ask for.
        assertTrue(listings > 100, "only $listings mutated inputs decoded as a listing")
        assertTrue(bids > 50, "only $bids mutated inputs decoded as a bid")
    }

    /**
     * Every mutation reaches a decoder on **every** kind, not only on the first of the three.
     *
     * The three mutations that can break either the wire layer or a decoder split on
     * `index % 5`, and `KINDS` has three entries selected by `index % 3`. An `index % 3` split
     * would send every wire-breaking input to `KINDS[0]` and none to the other two — so §5.2's
     * request path would never see a control character at a decoder, while the wire bound for
     * those mutations would only ever be exercised on a request. Both halves would look fully
     * covered in the aggregate counts. This is the assertion that says otherwise.
     */
    @Test
    fun `every mutation reaches a decoder on every listing kind`() {
        val reached = mutableMapOf<Pair<HostileCorpus.Mutation, Int>, Int>()
        for (input in HostileCorpus.corpus(CORPUS)) {
            if (contextOf(input).checked == null) continue
            val key = input.mutation to input.kind
            reached[key] = (reached[key] ?: 0) + 1
        }

        val kinds = listOf(NenyaKind.REQUEST, NenyaKind.OFFER, NenyaKind.PUBLIC_BID)
        for (mutation in HostileCorpus.Mutation.entries) {
            for (kind in kinds) {
                assertTrue(
                    (reached[mutation to kind] ?: 0) > 0,
                    "no $mutation input of kind:$kind got past the wire layer, so that mutation " +
                        "is swept on two kinds out of three and nobody would notice: $reached",
                )
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Helpers.
    // -----------------------------------------------------------------------------------------

    /**
     * `null` when [body] either returned or refused the way this library refuses, and a sentence
     * naming the shape otherwise.
     *
     * "The way this library refuses" is deliberately narrow: one of this library's own exception
     * types **carrying a named reason**. A bare `IllegalArgumentException` from somewhere in the
     * JDK is not a rejection a caller can branch on, and neither is a `RuntimeException` this
     * library threw without saying why.
     */
    private fun problemWith(label: String, input: HostileCorpus.Input, body: () -> Unit): String? {
        try {
            body()
        } catch (thrown: Throwable) {
            if (isNamedRejection(thrown)) return null
            return "$label on $input threw ${thrown.javaClass.name}: ${thrown.message}"
        }
        return null
    }

    private fun isNamedRejection(thrown: Throwable): Boolean {
        val type = thrown.javaClass
        if (!type.name.startsWith(LIBRARY_PACKAGE)) return false
        val reason = try {
            type.getMethod("getReason")
        } catch (absent: NoSuchMethodException) {
            return false
        }
        return Enum::class.java.isAssignableFrom(reason.returnType)
    }

    /** The unhardened decoder this test exists to prove the harness would catch. */
    private fun unhardenedProbe(value: String): Int = value.toInt()

    /** The same thing written the way §4.3 requires: refuse by name rather than by exception. */
    private fun hardenedProbe(value: String): Int? =
        if (value.isNotEmpty() && value.length < 10 && value.all { it.isDigit() }) value.toInt() else null

    private fun Context.satisfies(needs: Needs): Boolean = when (needs) {
        Needs.NOTHING -> true
        Needs.EVENT -> event != null
        Needs.CHECKED -> checked != null
        Needs.TAG_SET -> tagSet != null
    }

    /** As much of the layer stack as this input survives — the rest of the targets skip it. */
    private fun contextOf(input: HostileCorpus.Input): Context {
        val event = runCatching { build(input) }.getOrNull()
        val checked = event?.let {
            runCatching { CheckedEvent.checkEventId(EventId.of(it).toHex(), it) }.getOrNull()
        }
        val tagSet = checked?.let {
            runCatching { TagSet.read(it, contextFor(input.kind)) }.getOrNull()
        }
        return Context(input, event, checked, tagSet)
    }

    /**
     * A pubkey this library accepts, so a hostile value in a *later* parameter is actually
     * reached.
     *
     * `TagFixtures.pubkeyFor` is a SHA-256 digest of a per-index label: nothing typed, and
     * unaffected by whatever the mutation did to `input.pubkey`.
     */
    private fun wellFormedPubkey(input: HostileCorpus.Input): String = TagFixtures.pubkeyFor(input.index)

    /** The same, as a §4.2 coordinate. */
    private fun wellFormedCoordinate(input: HostileCorpus.Input): Coordinate =
        Coordinate(input.kind, wellFormedPubkey(input), "listing-${input.index}")

    private fun build(input: HostileCorpus.Input): WireEvent = WireEvent(
        pubkey = input.pubkey,
        createdAt = input.createdAt,
        kind = input.kind,
        tags = input.tags,
        content = input.content,
    )

    /** §5.3's "a shared tag validator MUST take the kind" — the context the event's kind names. */
    private fun contextFor(kind: Int): TagContext = when (kind) {
        NenyaKind.PUBLIC_BID -> TagContext.publicBid()
        NenyaKind.OFFER, NenyaKind.OFFER_DRAFT, NenyaKind.REQUEST -> TagContext.listing(kind)
        else -> TagContext.rumor(kind)
    }

    /** Every published function in the four packages taking one of the four hostile shapes. */
    private fun reflectedEntryPoints(): Set<String> {
        val found = mutableSetOf<String>()
        for (packageName in DECODER_PACKAGES) {
            for (type in MainClasses.published(packageName)) {
                for (method in MainClasses.methods(type)) {
                    if (MainClasses.isCompilerGeneratedEnumMember(type, method)) continue
                    if (method.parameterTypes.none { it in hostileShapes() }) continue
                    found += MainClasses.label(packageName, type, method)
                }
            }
        }
        if (found.isEmpty()) fail("the reflection filter found no entry point at all in $DECODER_PACKAGES")
        return found
    }

    /**
     * The four parameter shapes hostile data arrives in.
     *
     * `List` erased rather than `List<List<String>>`: the JVM shows the erased type on a parameter
     * and a filter written against the parameterised one would see nothing at all. Over-inclusive
     * on purpose — a published function taking any list in these four packages is taking tags.
     */
    private fun hostileShapes(): Set<Class<*>> = setOf(
        String::class.java,
        List::class.java,
        CheckedEvent::class.java,
        WireEvent::class.java,
    )
}
