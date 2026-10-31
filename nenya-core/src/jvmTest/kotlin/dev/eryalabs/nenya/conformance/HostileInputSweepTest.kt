package dev.eryalabs.nenya.conformance

import dev.eryalabs.nenya.bid.AuthoredBid
import dev.eryalabs.nenya.bid.Bid
import dev.eryalabs.nenya.bid.BidWriter
import dev.eryalabs.nenya.channel.AttributedRumor
import dev.eryalabs.nenya.channel.RumorWriterFixtures
import dev.eryalabs.nenya.delivery.DeliverableCommitment
import dev.eryalabs.nenya.delivery.DeliverableHash
import dev.eryalabs.nenya.delivery.DeliverableRelease
import dev.eryalabs.nenya.delivery.DeliveryEvidence
import dev.eryalabs.nenya.delivery.ServedBytesVerified
import dev.eryalabs.nenya.listing.AuthoredListing
import dev.eryalabs.nenya.listing.Listing
import dev.eryalabs.nenya.listing.ListingBuild
import dev.eryalabs.nenya.listing.ListingSide
import dev.eryalabs.nenya.listing.ListingStatusCodec
import dev.eryalabs.nenya.listing.ListingWriter
import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.order.OrderFixtures
import dev.eryalabs.nenya.order.OrderStatusCodec
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.payment.PaymentHash
import dev.eryalabs.nenya.payment.Preimage
import dev.eryalabs.nenya.settlement.Bolt11Invoice
import dev.eryalabs.nenya.settlement.Bolt11Reference
import dev.eryalabs.nenya.settlement.FeeTermPoint
import dev.eryalabs.nenya.settlement.FeeTermSighting
import dev.eryalabs.nenya.settlement.PaymentMedium
import dev.eryalabs.nenya.settlement.PaymentWriter
import dev.eryalabs.nenya.settlement.ReleaseFixtures
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
import java.lang.reflect.InvocationTargetException
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
 * §7's private channel is swept the same way over its own corpus, `HostileCorpus.rumorCorpus`:
 * `AttributedRumor.attribute` and the seven `decode(AttributedRumor.Bound…)`, through the same
 * [problemWith], with its own unhardened-probe control, and with the published set of those
 * decoders — in every package, found by reflection — held **equal** to the swept one.
 *
 * The money path is swept the same way again: the BOLT-11 recogniser and parser over
 * `HostileCorpus.invoiceCorpus`, and the hex, token and byte decoders in `settlement`, `payment`,
 * `delivery` and `order` over every tag value of the other two corpora — with its own probe control,
 * and with every published function or constructor there taking a `String`, a `ByteArray` or a
 * `List<String>` held equal to the swept set plus a pinned exemption list, each exemption with its
 * reason.
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
            // T40's three §5.1/§5.2 writers. The filter's four shapes do not name them: each takes an
            // `AuthoredListing` and a `TagLimits`, so no `String`, `List`, `CheckedEvent` or
            // `WireEvent` appears in the signature — and `AuthoredListing.<init>`, which is where the
            // hostile strings and the hostile tag list go in, is a constructor, which the filter does
            // not enumerate either. They are swept anyway, because a writer handed a stranger's values
            // by a client that trusted them is exactly §4.3's sentence: every one of §5.3's rows is
            // fed all four hostile strings, with the corpus's own tag list as the extension tags, and
            // the writer must answer a `ListingBuild` — never one of the six unhardened shapes.
            "ListingWriter.request",
            "ListingWriter.offer",
            "ListingWriter.draft",
            // T47's §6 writer, on the same terms: it takes an `AuthoredBid` and a `TagLimits`, and the
            // hostile strings go in through `AuthoredBid.<init>`.
            "BidWriter.bid",
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
        // T40's writers, each twice per hostile string, for the reason the four `<init>` targets
        // above give: a listing whose author pubkey is hostile is refused before §5.3's Encoding
        // column is reached at all, so the second call hands it a pubkey this library accepts and
        // puts the hostile value in every §5.3 row instead.
        Target("ListingWriter.request", Needs.NOTHING) { c ->
            c.strings.flatMap { text -> writerCalls(c.input, text) { ListingWriter.request(it) } }
        },
        Target("ListingWriter.offer", Needs.NOTHING) { c ->
            c.strings.flatMap { text -> writerCalls(c.input, text) { ListingWriter.offer(it) } }
        },
        Target("ListingWriter.draft", Needs.NOTHING) { c ->
            c.strings.flatMap { text -> writerCalls(c.input, text) { ListingWriter.draft(it) } }
        },
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
        // T47's writer, twice per hostile string for the reason T40's three are: a hostile bidder
        // pubkey is refused before §5.3's Encoding column is reached.
        Target("BidWriter.bid", Needs.NOTHING) { c ->
            c.strings.flatMap { text ->
                listOf<() -> Unit>(
                    { BidWriter.bid(bidWith(c.input, text, text)); Unit },
                    { BidWriter.bid(bidWith(c.input, wellFormedPubkey(c.input), text)); Unit },
                )
            }
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
    // §7's private channel: `attribute` and the seven rumor decoders.
    // -----------------------------------------------------------------------------------------

    /** One swept §7 entry point: its label, and the calls it makes for one rumor corpus entry. */
    private class RumorTarget(val label: String, val calls: (RumorContext) -> List<() -> Unit>)

    /**
     * `AttributedRumor.attribute`, under the rumor's own claimed pubkey and under each hostile string
     * as the seal's; then every one of [rumorDecoders] over **every** bound rumor — a decoder handed
     * another message's rumor must refuse it by name too, so none is fed only its own shape.
     */
    private fun rumorSwept(): List<RumorTarget> = listOf(
        RumorTarget("AttributedRumor\$Companion.attribute") { c ->
            val checked = c.checked ?: return@RumorTarget emptyList()
            listOf<() -> Unit>({ AttributedRumor.attribute(c.input.pubkey, checked); Unit }) +
                c.strings.map { text -> { AttributedRumor.attribute(text, checked); Unit } }
        },
    ) + rumorDecoders.map { decoder ->
        RumorTarget(decoder.label) { c ->
            val bound = c.attributed as? AttributedRumor.Bound ?: return@RumorTarget emptyList()
            listOf { decoder.decode(bound, c.case); Unit }
        }
    }

    /** The rumor sweep's harness: each target over [HostileCorpus.rumorCorpus], every call through [problemWith]. */
    private fun rumorSweep(targets: List<RumorTarget>): Pair<Map<String, Int>, List<String>> {
        val invocations = targets.associate { it.label to 0 }.toMutableMap()
        val problems = mutableListOf<String>()
        for (rumor in HostileCorpus.rumorCorpus(RUMOR_CORPUS)) {
            val context = rumorContextOf(rumor)
            for (target in targets) {
                for (call in target.calls(context)) {
                    invocations[target.label] = invocations.getValue(target.label) + 1
                    problemWith(target.label, rumor.input, call)?.let { problems += "$it ($rumor)" }
                }
            }
        }
        return invocations to problems
    }

    @Test
    fun `every rumor decoder refuses hostile input by name, never by unhandled exception`() {
        val (invocations, problems) = rumorSweep(rumorSwept())

        val never = invocations.filterValues { it == 0 }.keys
        assertTrue(never.isEmpty(), "these §7 entry points were never invoked, so the sweep proves nothing about them: $never")
        assertTrue(
            invocations.values.all { it > 200 },
            "a §7 entry point ran too few times to have seen every mutation kind: " +
                invocations.filterValues { it <= 200 },
        )
        assertEquals(
            emptyList(),
            problems.take(20),
            "`attribute` or a rumor decoder threw something that is not a named rejection (§4.3). " +
                "${problems.size} failure(s) in total.",
        )
    }

    /**
     * The rumor harness's control: an unhardened decoder — `toInt()` on a tag value, no digit or
     * length check — swept beside the real ones through [rumorSweep], must be reported; its hardened
     * twin, over the same corpus, must not.
     */
    @Test
    fun `an unhardened rumor decoder fails the rumor sweep harness`() {
        val probe = "probe.unhardenedAmount"
        val unhardened = RumorTarget(probe) { c ->
            val bound = c.attributed as? AttributedRumor.Bound ?: return@RumorTarget emptyList()
            listOf { amountOf(bound)?.let(::unhardenedProbe); Unit }
        }
        val (_, problems) = rumorSweep(rumorSwept() + unhardened)

        assertTrue(problems.isNotEmpty(), "the unhardened rumor probe passed the rumor harness, so it cannot fail")
        assertTrue(problems.all { it.startsWith("$probe ") }, "only the probe may be reported: ${problems.take(5)}")
        assertTrue(
            problems.any { "NumberFormatException" in it },
            "the harness must name the shape it saw; it reported ${problems.first()}",
        )
        assertTrue(
            problems.none { "WELL_FORMED" in it },
            "the probe must fail on hostile input, not on the writers' own output: ${problems.take(5)}",
        )

        val hardened = RumorTarget("probe.hardenedAmount") { c ->
            val bound = c.attributed as? AttributedRumor.Bound ?: return@RumorTarget emptyList()
            listOf { amountOf(bound)?.let(::hardenedProbe); Unit }
        }
        assertEquals(emptyList(), rumorSweep(listOf(hardened)).second, "the hardened twin must pass the same harness")
    }

    /**
     * Every published `decode` taking an `AttributedRumor$Bound`, in every package, **equals** the
     * swept set — so a §7 decoder added later is not outside the sweep, and the sweep names none that
     * does not exist.
     */
    @Test
    fun `every published rumor decode is swept, and nothing else is`() {
        val reflected = reflectedRumorDecoders()

        assertEquals(7, reflected.size, "§7 has seven decoders over a bound rumor: $reflected")
        assertEquals(emptySet(), rumorDecodeGap(rumorDecoders.map { it.label }.toSet()))
    }

    /** The equality's control: any one decoder dropped from the swept set is named by the gap. */
    @Test
    fun `a rumor decoder dropped from the swept set fails the equality`() {
        val swept = rumorDecoders.map { it.label }.toSet()
        for (label in swept) {
            assertEquals(setOf(label), rumorDecodeGap(swept - label), "dropping $label must fail the equality")
        }
    }

    /** The two sets' symmetric difference: published rumor decoders not swept, and swept labels not published. */
    private fun rumorDecodeGap(swept: Set<String>): Set<String> {
        val reflected = reflectedRumorDecoders()
        return (reflected - swept) + (swept - reflected)
    }

    private fun reflectedRumorDecoders(): Set<String> {
        val found = mutableSetOf<String>()
        for (packageName in MainClasses.allPackages()) {
            for (type in MainClasses.published(packageName)) {
                for (method in MainClasses.methods(type)) {
                    if (method.name != "decode") continue
                    if (method.genericParameterTypes.none { it.typeName == AttributedRumor.Bound::class.java.typeName }) continue
                    found += MainClasses.label(packageName, type, method)
                }
            }
        }
        return found
    }

    /** §7.5's `amount_msat` as the rumor carries it, read the way the probes above need it. */
    private fun amountOf(rumor: AttributedRumor.Bound): String? =
        rumor.encode().tags.firstOrNull { it[0] == "amount_msat" }?.getOrNull(1)

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
    private fun problemWith(label: String, input: HostileCorpus.Input, body: () -> Unit): String? =
        problemWith(label, input.toString(), body)

    /** The same, for an input named by [described] — a redacted description, never the value itself. */
    private fun problemWith(label: String, described: String, body: () -> Unit): String? {
        try {
            body()
        } catch (thrown: Throwable) {
            if (isNamedRejection(thrown)) return null
            return "$label on $described threw ${thrown.javaClass.name}: ${thrown.message}"
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

    /**
     * The two calls each §5.1/§5.2 writer makes per hostile string: the string in the author pubkey,
     * then a pubkey this library accepts with the string in every §5.3 row the writer takes.
     *
     * Both are needed for the reason the `Coordinate.<init>` target gives: §4.1's event fields are
     * checked before §5.3's Encoding column, so a single call with the hostile value everywhere never
     * reaches `TagWriter.d`, `TagWriter.topic` or the `t` rule at all.
     */
    private fun writerCalls(
        input: HostileCorpus.Input,
        text: String,
        write: (AuthoredListing) -> ListingBuild,
    ): List<() -> Unit> = listOf(
        { write(authoredWith(input, text, text)); Unit },
        { write(authoredWith(input, wellFormedPubkey(input), text)); Unit },
    )

    /**
     * A listing authored from [text] in every row that takes a string, with the corpus entry's own
     * tag list as the §4.3 extension tags.
     *
     * The `price` is a fixed legal amount: `Msat` makes a hostile one unrepresentable — a negative or
     * above-cap amount is refused at construction, which is T1's whole point — so the price branch is
     * covered by `ListingWriterTest`'s own controls instead.
     */
    private fun authoredWith(
        input: HostileCorpus.Input,
        pubkey: String,
        text: String,
    ): AuthoredListing = AuthoredListing(
        authorPubkey = pubkey,
        createdAt = input.createdAt,
        dValue = text,
        title = text,
        price = Msat.ofSat(50_000L),
        content = text,
        summary = text,
        mimeType = text,
        topics = listOf(text),
        alt = text,
        license = text,
        extraTags = input.tags,
    )

    /**
     * A bid authored from [text] in every row that takes a string, with the corpus entry's own tag
     * list as the §4.3 extension tags. The coordinate is a well-formed one on a listing kind, so the
     * rows are reached; `Coordinate`'s own hostile-input target is above.
     */
    private fun bidWith(input: HostileCorpus.Input, pubkey: String, text: String): AuthoredBid = AuthoredBid(
        bidderPubkey = pubkey,
        createdAt = input.createdAt,
        listing = Coordinate(NenyaKind.REQUEST, wellFormedPubkey(input), "listing-${input.index}"),
        price = Msat.ofSat(90_000L),
        content = text,
        rootRelayHint = text,
        parentRelayHint = text,
        topics = listOf(text),
        alt = text,
        dValue = text,
        title = text,
        summary = text,
        status = text,
        mimeType = text,
        license = text,
        extraTags = input.tags,
    )

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

    // -----------------------------------------------------------------------------------------
    // The money path: the BOLT-11 reader, and the hex, token and byte decoders a counterparty's
    // values reach in `settlement`, `payment`, `delivery` and `order`.
    // -----------------------------------------------------------------------------------------

    /**
     * One money-path sweep input: a redacted description, the strings it carries, and the tags it
     * carries.
     *
     * Drawn from all three corpora: every tag value and hostile string of [HostileCorpus.corpus] and
     * [HostileCorpus.rumorCorpus] — the values a counterparty's event hands these decoders — and every
     * entry of [HostileCorpus.invoiceCorpus]. A value or a tag already seen is not repeated, so the
     * sweep's cost is the corpora's distinct values rather than their size.
     */
    private class MoneyInput(val described: String, val values: List<String>, val tags: List<List<String>>)

    /** One swept money-path entry point: its label, and the calls it makes for one [MoneyInput]. */
    private class MoneyTarget(val label: String, val calls: (MoneyInput) -> List<() -> Unit>)

    private fun moneyInputs(): List<MoneyInput> {
        val seenValues = HashSet<String>()
        val seenTags = HashSet<List<String>>()
        val out = ArrayList<MoneyInput>()
        fun add(described: String, input: HostileCorpus.Input) {
            val values = (input.tags.flatMap { it.drop(1) } + listOf(input.text, input.hex, input.coordinate, input.decimal))
                .filter { seenValues.add(it) }
            val tags = input.tags.filter { seenTags.add(it) }
            if (values.isNotEmpty() || tags.isNotEmpty()) out += MoneyInput(described, values, tags)
        }
        for (input in HostileCorpus.corpus(CORPUS)) add("$input", input)
        for (rumor in HostileCorpus.rumorCorpus(RUMOR_CORPUS)) add("$rumor", rumor.input)
        for (invoice in HostileCorpus.invoiceCorpus()) out += MoneyInput("$invoice", listOf(invoice.invoice), emptyList())
        return out
    }

    /**
     * Every money-path entry point a counterparty's string, bytes or tag reaches, each over every
     * [MoneyInput] it can take.
     *
     * The three writers are swept on their string parameters with every other argument a working
     * fixture's, for the reason the listing writers are: a hostile value refused before the parameter
     * it sits in is reached sweeps nothing. A receipt's `reference` and `proof` are what the embedding
     * client's wallet answered, and a fee recipient is read off a counterparty's terms, so none is the
     * caller's own trusted value. The `paymentRequest` writer also takes every invoice the recogniser
     * accepted.
     *
     * The three 32-byte types and `Bolt11Reference` are also built through the JVM-public synthetic
     * constructor their KDoc names — the door a Java client reaches without the factory — so the
     * `init` checks those notes say every construction path runs are swept on that path too.
     */
    private fun moneySwept(): List<MoneyTarget> {
        val blob = OrderFixtures.blob
        val served = ServedBytesVerified.verifyServedBytes(blob.commitment, blob.served)
        val cases = RumorWriterFixtures.cases(64)
        val request = cases.first { it.shape == RumorWriterFixtures.Shape.PAYMENT_REQUEST }
        val receipt = cases.first { it.shape == RumorWriterFixtures.Shape.RECEIPT }
        val requestOrder = request.order ?: fail("$request carries no order")
        val receiptOrder = receipt.order ?: fail("$receipt carries no order")
        val reference = receipt.reference ?: fail("$receipt carries no reference")
        val proof = receipt.proof ?: fail("$receipt carries no proof")
        val invoice = request.invoice ?: fail("$request carries no invoice")
        val commitment = ReleaseFixtures.commitment()
        val payment = OrderFixtures.receipt(Payee.PROVIDER)

        fun bytes(m: MoneyInput): List<ByteArray> =
            m.values.flatMap { listOf(it.encodeToByteArray(), it.encodeToByteArray().copyOf(PaymentHash.BYTE_LENGTH)) }

        return listOf(
            MoneyTarget("Bolt11Reference\$Companion.recognise") { m ->
                m.values.map { v -> { Bolt11Reference.recognise(v); Unit } }
            },
            MoneyTarget("Bolt11Reference.<init>") { m ->
                m.values.map { v -> { construct(Bolt11Reference::class.java, v) } }
            },
            MoneyTarget("Bolt11Invoice\$Companion.parse") { m ->
                recognised(m).map { r -> { Bolt11Invoice.parse(r); Unit } }
            },
            MoneyTarget("PaymentHash\$Companion.ofHex") { m -> m.values.map { v -> { PaymentHash.ofHex(v); Unit } } },
            MoneyTarget("PaymentHash.<init>") { m -> bytes(m).map { b -> { construct(PaymentHash::class.java, b) } } },
            MoneyTarget("Preimage\$Companion.ofHex") { m -> m.values.map { v -> { Preimage.ofHex(v); Unit } } },
            MoneyTarget("Preimage.<init>") { m -> bytes(m).map { b -> { construct(Preimage::class.java, b) } } },
            MoneyTarget("DeliverableHash\$Companion.ofHex") { m ->
                m.values.map { v -> { DeliverableHash.ofHex(v); Unit } }
            },
            MoneyTarget("DeliverableHash.<init>") { m -> bytes(m).map { b -> { construct(DeliverableHash::class.java, b) } } },
            // Every tag as it arrived, and every value inside a `fee` tag at both of §8.1's arities, at
            // every point §8.4 names.
            MoneyTarget("FeeTermSighting\$Companion.of") { m ->
                (m.tags + m.values.flatMap { v -> listOf(listOf("fee", v), listOf("fee", v, v)) }).flatMap { tag ->
                    FeeTermPoint.entries.map { point -> { FeeTermSighting.of(point, tag); Unit } }
                }
            },
            MoneyTarget("PaymentMedium\$Companion.of") { m -> m.values.map { v -> { PaymentMedium.of(v); Unit } } },
            MoneyTarget("OrderStatusCodec.read") { m -> m.values.map { v -> { OrderStatusCodec.read(v); Unit } } },
            MoneyTarget("DeliverableCommitment.<init>") { m ->
                m.values.map { v ->
                    { DeliverableCommitment(blob.commitment.x, blob.commitment.ox, v, blob.commitment.sizeBytes); Unit }
                }
            },
            MoneyTarget("DeliverableRelease.<init>") { m ->
                m.values.map { v ->
                    { DeliverableRelease(blob.commitment.x, blob.commitment.ox, v, blob.commitment.sizeBytes); Unit }
                }
            },
            // Each value's bytes as they are, and cut or padded to the committed size so the digest
            // comparison is reached rather than only the length check before it.
            MoneyTarget("ServedBytesVerified\$Companion.verifyServedBytes") { m ->
                m.values.flatMap { v ->
                    listOf(v.encodeToByteArray(), v.encodeToByteArray().copyOf(blob.commitment.sizeBytes.toInt()))
                }.map { b -> { ServedBytesVerified.verifyServedBytes(blob.commitment, b); Unit } }
            },
            MoneyTarget("DeliveryEvidence\$Companion.verifyPlaintextBytes") { m ->
                bytes(m).map { b -> { DeliveryEvidence.verifyPlaintextBytes(served, b); Unit } }
            },
            MoneyTarget("PaymentWriter.paymentRequest") { m ->
                m.values.map<String, () -> Unit> { v ->
                    { PaymentWriter.paymentRequest(request.envelope, requestOrder, Payee.FEE, invoice, v, request.medium, request.terms); Unit }
                } + recognised(m).map { r ->
                    { PaymentWriter.paymentRequest(request.envelope, requestOrder, Payee.PROVIDER, r); Unit }
                }
            },
            MoneyTarget("PaymentWriter.receipt") { m ->
                m.values.flatMap { v ->
                    listOf<() -> Unit>(
                        { PaymentWriter.receipt(receipt.envelope, receiptOrder, Payee.PROVIDER, v, proof); Unit },
                        { PaymentWriter.receipt(receipt.envelope, receiptOrder, Payee.PROVIDER, reference, v); Unit },
                        { PaymentWriter.receipt(receipt.envelope, receiptOrder, Payee.FEE, reference, proof, v); Unit },
                    )
                }
            },
            MoneyTarget("PaymentWriter.release") { m ->
                m.values.flatMap { v ->
                    listOf<() -> Unit>(
                        {
                            PaymentWriter.release(ReleaseFixtures.providerEnvelope(), commitment, payment, v, ReleaseFixtures.NONCE_HEX)
                            Unit
                        },
                        {
                            PaymentWriter.release(ReleaseFixtures.providerEnvelope(), commitment, payment, ReleaseFixtures.KEY_HEX, v)
                            Unit
                        },
                    )
                }
            },
        )
    }

    /** The money-path sweep's harness: each target over [moneyInputs], every call through [problemWith]. */
    private fun moneySweep(targets: List<MoneyTarget>): Pair<Map<String, Int>, List<String>> {
        val invocations = targets.associate { it.label to 0 }.toMutableMap()
        val problems = mutableListOf<String>()
        for (input in moneyInputs()) {
            for (target in targets) {
                for (call in target.calls(input)) {
                    invocations[target.label] = invocations.getValue(target.label) + 1
                    problemWith(target.label, input.described, call)?.let { problems += it }
                }
            }
        }
        return invocations to problems
    }

    @Test
    fun `every money-path decoder refuses hostile input by name, never by unhandled exception`() {
        val (invocations, problems) = moneySweep(moneySwept())

        val never = invocations.filterValues { it == 0 }.keys
        assertTrue(never.isEmpty(), "these money-path entry points were never invoked, so the sweep proves nothing about them: $never")
        assertTrue(
            invocations.values.all { it > 200 },
            "a money-path entry point ran too few times to have seen every mutation kind: " +
                invocations.filterValues { it <= 200 },
        )
        assertEquals(
            emptyList(),
            problems.take(20),
            "a money-path decoder threw something that is not a named rejection (§4.3). " +
                "${problems.size} failure(s) in total.",
        )
    }

    /**
     * The money-path harness's control: the unhardened probe — `toInt()` on a value, no digit or
     * length check — swept beside the real targets through [moneySweep], must be reported, and only it;
     * its hardened twin, over the same inputs, must not.
     */
    @Test
    fun `an unhardened probe fails the money-path sweep harness`() {
        val probe = "probe.unhardenedMoneyValue"
        val unhardened = MoneyTarget(probe) { m -> m.values.map { v -> { unhardenedProbe(v); Unit } } }
        val (_, problems) = moneySweep(moneySwept() + unhardened)

        assertTrue(problems.isNotEmpty(), "the unhardened probe passed the money-path harness, so it cannot fail")
        assertTrue(problems.all { it.startsWith("$probe ") }, "only the probe may be reported: ${problems.take(5)}")
        assertTrue(
            problems.any { "NumberFormatException" in it },
            "the harness must name the shape it saw; it reported ${problems.first()}",
        )

        val hardened = MoneyTarget("probe.hardenedMoneyValue") { m -> m.values.map { v -> { hardenedProbe(v); Unit } } }
        assertEquals(emptyList(), moneySweep(listOf(hardened)).second, "the hardened twin must pass the same harness")
    }

    /**
     * Every published function or constructor in the four money packages taking a `String`, a
     * `ByteArray` or a `List<String>` is swept or pinned exempt with a reason — the swept set plus
     * [MONEY_EXEMPT] **equals** the reflected one, and the two are disjoint.
     */
    @Test
    fun `every published money-path entry point taking a string, bytes or a string list is swept or exempt`() {
        val reflected = reflectedMoneyEntryPoints()

        assertTrue(reflected.size > 20, "the money-path filter found only ${reflected.size} entry point(s): $reflected")
        assertEquals(emptySet(), moneyGap(moneySwept().map { it.label }.toSet()))
    }

    /** The equality's control: any one swept entry point dropped from the swept set is named by the gap. */
    @Test
    fun `a money-path decoder dropped from the swept set fails the equality`() {
        val swept = moneySwept().map { it.label }.toSet()
        for (label in swept) {
            assertEquals(setOf(label), moneyGap(swept - label), "dropping $label must fail the equality")
        }
    }

    /**
     * Everything the reflected set and the claimed one disagree on: a published entry point neither
     * swept nor exempt, a claim nothing publishes, a label both swept and exempt, and a pinned
     * beyond-the-filter target no longer swept.
     */
    private fun moneyGap(swept: Set<String>): Set<String> {
        val reflected = reflectedMoneyEntryPoints()
        val claimed = (swept - MONEY_SWEPT_BEYOND_THE_FILTER) + MONEY_EXEMPT.keys
        return (reflected - claimed) + (claimed - reflected) + (swept intersect MONEY_EXEMPT.keys) +
            (MONEY_SWEPT_BEYOND_THE_FILTER - swept)
    }

    /**
     * Every public method and every JVM-public constructor — the synthetic ones included, since a Java
     * client reaches those — in the four money packages with a parameter of one of [MONEY_SHAPES].
     */
    private fun reflectedMoneyEntryPoints(): Set<String> {
        val found = mutableSetOf<String>()
        for (packageName in MONEY_PACKAGES) {
            for (type in MainClasses.published(packageName)) {
                for (method in MainClasses.methods(type)) {
                    if (MainClasses.isCompilerGeneratedEnumMember(type, method)) continue
                    if (method.genericParameterTypes.none { it.typeName in MONEY_SHAPES }) continue
                    found += MainClasses.label(packageName, type, method)
                }
                for (constructor in type.constructors) {
                    if (constructor.genericParameterTypes.none { it.typeName in MONEY_SHAPES }) continue
                    found += "${type.name.removePrefix("$packageName.")}.<init>"
                }
            }
        }
        if (found.isEmpty()) fail("the money-path filter found no entry point at all in $MONEY_PACKAGES")
        return found
    }

    /** [m]'s values the recogniser accepts, as the references `Bolt11Invoice.parse` and the request writer take. */
    private fun recognised(m: MoneyInput): List<Bolt11Reference> =
        m.values.mapNotNull { runCatching { Bolt11Reference.recognise(it) }.getOrNull() }

    /**
     * [type] built through its one JVM-public synthetic constructor — the private constructor's
     * parameter and Kotlin's trailing `DefaultConstructorMarker` — with the cause of a refusal rethrown
     * as itself, so [problemWith] judges what `init` threw rather than reflection's wrapper.
     */
    private fun construct(type: Class<*>, argument: Any) {
        val synthetic = type.declaredConstructors.single { it.isSynthetic }
        try {
            synthetic.newInstance(argument, null)
        } catch (wrapped: InvocationTargetException) {
            throw wrapped.targetException
        }
    }

    private val MONEY_PACKAGES: List<String> = listOf(
        "dev.eryalabs.nenya.settlement",
        "dev.eryalabs.nenya.payment",
        "dev.eryalabs.nenya.delivery",
        "dev.eryalabs.nenya.order",
    )

    /** The three parameter shapes a counterparty's value reaches these packages in, by generic type name. */
    private val MONEY_SHAPES: Set<String> = setOf("java.lang.String", "byte[]", "java.util.List<java.lang.String>")

    /**
     * Swept although the filter does not name it: `parse` takes a `Bolt11Reference`, not a string, and
     * is the decoder every invoice a counterparty sends reaches once the recogniser has accepted it.
     */
    private val MONEY_SWEPT_BEYOND_THE_FILTER: Set<String> = setOf("Bolt11Invoice\$Companion.parse")

    /** Published entry points the filter names and the sweep does not call, each with the reason. */
    private val MONEY_EXEMPT: Map<String, String> = mapOf(
        "SettlementException.<init>" to "a refusal: a reason and a detail this library wrote; it reads nothing",
        "PaymentException.<init>" to "a refusal: a reason and a detail this library wrote; it reads nothing",
        "DeliveryException.<init>" to "a refusal: a reason and a detail this library wrote; it reads nothing",
        "OrderStateException.<init>" to "a refusal: a reason and a detail this library wrote; it reads nothing",
        "PaymentBuild\$Refused.<init>" to "a writer's refusal value: reasons and a detail this library wrote",
        "PaymentRequest.<init>" to "internal: its one caller is PaymentRequest.decode, which the rumor sweep sweeps",
        "PaymentReceipt.<init>" to "internal: its one caller is PaymentReceipt.decode, which the rumor sweep sweeps",
        "SettlementTags.single" to "internal to the two settlement decoders, reached only through them, which the " +
            "rumor sweep sweeps",
        "SettlementTags.missing" to "internal: builds the refusal a missing tag gets, from a tag name and a clause " +
            "this library wrote",
        "SettlementTags\$DecodedPayee.<init>" to "internal: the payee tag the settlement decoders already read",
        "FeeTermSighting.<init>" to "internal and checks nothing: its callers are `of`, swept here, and the decoded " +
            "doors over messages the rumor sweep sweeps",
    )
}
