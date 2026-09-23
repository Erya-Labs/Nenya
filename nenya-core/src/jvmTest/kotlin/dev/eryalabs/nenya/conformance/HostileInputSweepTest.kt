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
import dev.eryalabs.nenya.tag.TagFixtures
import dev.eryalabs.nenya.tag.TagSet
import dev.eryalabs.nenya.tag.TagWriter
import dev.eryalabs.nenya.wire.CheckedEvent
import dev.eryalabs.nenya.wire.EventId
import dev.eryalabs.nenya.wire.EventJson
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
 *
 * ### Where these tests run
 *
 * The sweep's harness recognises a named rejection through `java.lang.reflect` (a `getReason`
 * accessor returning an enum, on a class in this library's package), and the completeness check
 * enumerates the published entry points the same way, so those three tests run on the JVM only. The
 * three corpus floors need no reflection: they, and the layer stack they build ([contextOf]), are in
 * [PortableHostileInputSweepTest] in `src/commonTest`, which this class extends, so they keep their
 * `HostileInputSweepTest` names and run on JavaScript as well.
 */
class HostileInputSweepTest : PortableHostileInputSweepTest() {

    private companion object {

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
         * The opening of `content`'s value in §7.1's object form, which `EventJson` writes in a
         * fixed key order — so splicing a hostile string in immediately after it puts that string
         * inside a JSON string, unescaped, which is where the scanner's interesting branches are.
         */
        const val CONTENT_OPENS: String = "\"content\":\""

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
        // §7.1's object form. `read` is the widest door in this library — it is the one function a
        // relay's bytes reach before anything has been checked at all — so it gets two calls per
        // hostile string rather than one.
        //
        // The bare string is the first, and on its own it would prove almost nothing: none of
        // `Context.strings` begins with `{`, so all four die on the scanner's first branch as
        // NOT_AN_OBJECT and the interior is never entered. The second call is what reaches it —
        // the JSON this library itself emitted for this corpus entry, with the hostile string
        // spliced in raw where `content`'s value belongs. Every one of those walks the whole
        // well-formed prefix the bare call never touches — the `id` and `pubkey` strings, both
        // number scans, the whole `tags` array — and then enters `readStringBody`.
        //
        // What it reaches there is stated exactly, because the corpus was measured rather than
        // assumed: over `corpus(1300)` the four strings carry 388 raw characters below `0x20`
        // between them, and **zero** backslashes, bare quotes or surrogates. So this target covers
        // `readStringBody`'s control-character refusal and its ordinary-append path, and it does
        // **not** reach `readEscape`, `readUnicodeEscape` or the early string-termination path at
        // all. Those are covered by `EventJsonTest`'s own hostile corpus, which is built for them.
        Target("EventJson.read", Needs.EVENT) { c ->
            val emitted = runCatching {
                EventJson.writeUnsigned(c.event!!, EventId.of(c.event))
            }.getOrNull()
            c.strings.flatMap { text ->
                buildList<() -> Unit> {
                    add { EventJson.read(text); Unit }
                    if (emitted != null) {
                        add { EventJson.read(emitted.replace(CONTENT_OPENS, CONTENT_OPENS + text)); Unit }
                    }
                }
            }
        },
        Target("EventJson.writeUnsigned", Needs.EVENT) { c ->
            listOf { EventJson.writeUnsigned(c.event!!, EventId.of(c.event)); Unit }
        },
        // The hostile string lands in the `sig` slot, which is the one parameter of the three a
        // caller could hand a status-shaped value to.
        Target("EventJson.writeSigned", Needs.EVENT) { c ->
            c.strings.map { text ->
                { EventJson.writeSigned(c.event!!, EventId.of(c.event), text); Unit }
            }
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
