package dev.eryalabs.nenya.conformance

import dev.eryalabs.nenya.bid.Bid
import dev.eryalabs.nenya.channel.AttributedRumor
import dev.eryalabs.nenya.channel.DeliverableReleaseMessage
import dev.eryalabs.nenya.channel.DeliveryCommitmentMessage
import dev.eryalabs.nenya.channel.OrderProposal
import dev.eryalabs.nenya.channel.OrderStatusMessage
import dev.eryalabs.nenya.channel.PrivateBid
import dev.eryalabs.nenya.channel.RumorWriterFixtures
import dev.eryalabs.nenya.channel.RumorWriterFixtures.Shape
import dev.eryalabs.nenya.listing.Listing
import dev.eryalabs.nenya.money.FeeSplit
import dev.eryalabs.nenya.money.Msat
import dev.eryalabs.nenya.order.OrderTerms
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.payment.Preimage
import dev.eryalabs.nenya.settlement.Bolt11Examples
import dev.eryalabs.nenya.settlement.Bolt11Invoice
import dev.eryalabs.nenya.settlement.Bolt11Reference
import dev.eryalabs.nenya.settlement.PaymentMedium
import dev.eryalabs.nenya.settlement.PaymentReceipt
import dev.eryalabs.nenya.settlement.PaymentRequest
import dev.eryalabs.nenya.settlement.SettlementException
import dev.eryalabs.nenya.settlement.SettlementRejection
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.tag.TagContext
import dev.eryalabs.nenya.tag.TagSet
import dev.eryalabs.nenya.wire.CheckedEvent
import dev.eryalabs.nenya.wire.EventId
import dev.eryalabs.nenya.wire.WireEvent
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The half of `HostileInputSweepTest` that is common Kotlin: the corpus floors. [HostileCorpus]
 * carries every mutation kind, reproducibly; its inputs reach the tag, listing and bid codecs and
 * round-trip through them; and every mutation reaches a decoder on every listing kind.
 *
 * Abstract, and run as `HostileInputSweepTest` on every target, so every test keeps its name. The
 * sweep itself, its harness control and its completeness check recognise a named rejection and
 * enumerate entry points through `java.lang.reflect`, and stay in the JVM `HostileInputSweepTest`,
 * whose KDoc gives the rule.
 */
abstract class PortableHostileInputSweepTest {

    protected companion object {

        /** Thirteen mutation kinds, so each is drawn a hundred times. */
        const val CORPUS: Int = 1_300

        /**
         * Thirteen mutation kinds by eight `RumorWriterFixtures` shapes, each pair drawn ten times —
         * and no larger than [CORPUS], which is sized to JavaScript's 30-second per-test budget.
         */
        const val RUMOR_CORPUS: Int = 1_040

        /**
         * Mutated inputs of its own shape each decoder must see past `attribute`. Measured at
         * [HostileCorpus.SEED]: 28 for `PrivateBid` (the fewest) to 79 for the commitment and the
         * release, of 120 mutated per shape — so this sits well below the smallest, for a reviewer
         * who changes the seed.
         */
        const val RUMOR_REACH_FLOOR: Int = 20

        /**
         * Mutated invoice corpus entries `Bolt11Invoice.parse` must accept, or refuse for a reason other
         * than its checksum. Measured at [HostileCorpus.SEED]: 227 of the 345 mutated entries, every one of
         * them composed (the 75 raw string edits reach none) — so this sits well below it, for a
         * reviewer who changes the seed.
         */
        const val INVOICE_REACH_FLOOR: Int = 100
    }

    /**
     * One rumor corpus entry, plus whatever of it survived the layers below the seven decoders:
     * the wire event, T8's id check, and §7.2/§7.4's [AttributedRumor.attribute] under the rumor's
     * own claimed pubkey as the seal's — §7.2's positive case, so what refuses is the mutation.
     */
    internal class RumorContext(
        val rumor: HostileCorpus.RumorInput,
        val event: WireEvent?,
        val checked: CheckedEvent?,
        val attributed: AttributedRumor?,
    ) {

        val input: HostileCorpus.Input get() = rumor.input

        val case: RumorWriterFixtures.Case get() = rumor.case

        /** The four hostile strings, as [Context.strings]. */
        val strings: List<String>
            get() = listOf(input.text, input.hex, input.coordinate, input.decimal)
    }

    internal fun rumorContextOf(rumor: HostileCorpus.RumorInput): RumorContext {
        val event = runCatching { build(rumor.input) }.getOrNull()
        val checked = event?.let {
            runCatching { CheckedEvent.checkEventId(EventId.of(it).toHex(), it) }.getOrNull()
        }
        val attributed = checked?.let {
            runCatching { AttributedRumor.attribute(rumor.input.pubkey, it) }.getOrNull()
        }
        return RumorContext(rumor, event, checked, attributed)
    }

    /**
     * One of §7's seven decoders over an [AttributedRumor.Bound]: the label the JVM reflection check
     * names it by, the fixture shape whose rumors are its own, and the call.
     */
    internal class RumorDecoder(
        val label: String,
        val shape: Shape,
        val decode: (AttributedRumor.Bound, RumorWriterFixtures.Case) -> Any,
    )

    /**
     * The seven, in one list both halves of the sweep read: the reach floor here, and the JVM
     * sweep and its reflection equality in `HostileInputSweepTest`. `kind:14` has none — §7.4 puts
     * chat outside the required set, and `attribute` answers it as [AttributedRumor.Chat].
     */
    internal val rumorDecoders: List<RumorDecoder> = listOf(
        RumorDecoder("PrivateBid\$Companion.decode", Shape.PRIVATE_BID) { rumor, _ -> PrivateBid.decode(rumor) },
        RumorDecoder("OrderProposal\$Companion.decode", Shape.PROPOSAL) { rumor, _ -> OrderProposal.decode(rumor) },
        RumorDecoder("OrderStatusMessage\$Companion.decode", Shape.ORDER_UPDATE) { rumor, _ ->
            OrderStatusMessage.decode(rumor)
        },
        RumorDecoder("DeliveryCommitmentMessage\$Companion.decode", Shape.COMMITMENT) { rumor, _ ->
            DeliveryCommitmentMessage.decode(rumor)
        },
        RumorDecoder("DeliverableReleaseMessage\$Companion.decode", Shape.RELEASE) { rumor, _ ->
            DeliverableReleaseMessage.decode(rumor)
        },
        RumorDecoder("PaymentRequest\$Companion.decode", Shape.PAYMENT_REQUEST) { rumor, case ->
            PaymentRequest.decode(rumor, splitFor(case))
        },
        RumorDecoder("PaymentReceipt\$Companion.decode", Shape.RECEIPT) { rumor, _ -> PaymentReceipt.decode(rumor) },
    )

    /**
     * §8.3's split `PaymentRequest.decode` takes, which no message carries: the case's own where it
     * has terms, otherwise a fixed fee-less one, so the decoder is reached on every bound rumor.
     */
    internal fun splitFor(case: RumorWriterFixtures.Case): FeeSplit =
        (case.terms ?: OrderTerms.of(Msat.ofSat(90_000L))).split

    /**
     * The decoders reached by fewer than [floor] **mutated** inputs of their own shape past
     * `attribute`, with the count each was reached by. Empty when every decoder was swept.
     *
     * Counted over mutated inputs only, for the reason the listing floor gives: the well-formed
     * thirteenth of each shape would meet a small floor on its own. And only over inputs whose event
     * the mutation actually changed — a one-value-at-a-time mutation that found no value to break
     * leaves the fixture's event as it was, and is the writers' own output under another name.
     */
    internal fun rumorReachShortfalls(corpus: List<HostileCorpus.RumorInput>, floor: Int): Map<String, Int> {
        val reached = rumorDecoders.associate { it.label to 0 }.toMutableMap()
        for (rumor in corpus) {
            if (rumor.input.mutation == HostileCorpus.Mutation.WELL_FORMED) continue
            val base = RumorWriterFixtures.build(rumor.case)
            val input = rumor.input
            if (input.tags == base.tags && input.content == base.content && input.pubkey == base.pubkey) continue
            if (rumorContextOf(rumor).attributed !is AttributedRumor.Bound) continue
            for (decoder in rumorDecoders) {
                if (decoder.shape == rumor.case.shape) reached[decoder.label] = reached.getValue(decoder.label) + 1
            }
        }
        return reached.filterValues { it < floor }
    }

    /** One corpus entry, plus whatever of it survived the layers below the entry point. */
    internal class Context(
        val input: HostileCorpus.Input,
        val event: WireEvent?,
        val checked: CheckedEvent?,
        val tagSet: TagSet?,
    ) {

        /** The four hostile strings, so a String-taking door sees shapes it was not written for. */
        val strings: List<String>
            get() = listOf(input.text, input.hex, input.coordinate, input.decimal)
    }

    /** As much of the layer stack as this input survives — the rest of the targets skip it. */
    internal fun contextOf(input: HostileCorpus.Input): Context {
        val event = runCatching { build(input) }.getOrNull()
        val checked = event?.let {
            runCatching { CheckedEvent.checkEventId(EventId.of(it).toHex(), it) }.getOrNull()
        }
        val tagSet = checked?.let {
            runCatching { TagSet.read(it, contextFor(input.kind)) }.getOrNull()
        }
        return Context(input, event, checked, tagSet)
    }

    internal fun build(input: HostileCorpus.Input): WireEvent = WireEvent(
        pubkey = input.pubkey,
        createdAt = input.createdAt,
        kind = input.kind,
        tags = input.tags,
        content = input.content,
    )

    /** §5.3's "a shared tag validator MUST take the kind" — the context the event's kind names. */
    internal fun contextFor(kind: Int): TagContext = when (kind) {
        NenyaKind.PUBLIC_BID -> TagContext.publicBid()
        NenyaKind.OFFER, NenyaKind.OFFER_DRAFT, NenyaKind.REQUEST -> TagContext.listing(kind)
        else -> TagContext.rumor(kind)
    }

    /** The corpus floor: non-empty, and every mutation kind actually drawn. */
    @JsName("the_corpus_carries_every_mutation_kind")
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
    @JsName("the_corpus_reaches_the_tag_listing_and_bid_codecs_in_both_directions")
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
    @JsName("every_mutation_reaches_a_decoder_on_every_listing_kind")
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

    // ---------------------------------------------------------------------------------------------
    // §7's rumor decoders.
    // ---------------------------------------------------------------------------------------------

    /** The rumor corpus floor: every (shape, mutation) pair drawn, reproducibly, within budget. */
    @JsName("the_rumor_corpus_carries_every_shape_and_mutation_kind")
    @Test
    fun `the rumor corpus carries every shape and mutation kind`() {
        val corpus = HostileCorpus.rumorCorpus(RUMOR_CORPUS)

        assertTrue(RUMOR_CORPUS <= CORPUS, "the rumor corpus must stay within the listing corpus's budget")
        assertEquals(RUMOR_CORPUS, corpus.size)
        for (shape in Shape.entries) {
            for (mutation in HostileCorpus.Mutation.entries) {
                assertTrue(
                    corpus.any { it.case.shape == shape && it.input.mutation == mutation },
                    "the rumor corpus never drew $mutation on a $shape, so nothing swept it there",
                )
            }
        }
        // Reproducible from the seed, compared as values for the reason the listing floor gives.
        assertEquals(corpus.map { it.input }, HostileCorpus.rumorCorpus(RUMOR_CORPUS).map { it.input })
        // The well-formed entries are exactly the fixtures' events, so the floor below is about
        // mutated input and the field-for-field test about the writers' own output.
        for (rumor in corpus.filter { it.input.mutation == HostileCorpus.Mutation.WELL_FORMED }) {
            val event = RumorWriterFixtures.build(rumor.case)
            assertEquals(event.tags, rumor.input.tags, "$rumor is not its fixture's event")
            assertEquals(event.content, rumor.input.content, "$rumor is not its fixture's event")
        }
    }

    /**
     * Every one of the seven decoders is reached, past §7.2/§7.4's attribution, by mutated input of
     * its own message shape.
     *
     * Without it the sweep in `HostileInputSweepTest` could be green because every mutated rumor died
     * in `attribute` and no decoder ever saw one.
     */
    @JsName("every_rumor_decoder_is_reached_past_attribution_by_mutated_input")
    @Test
    fun `every rumor decoder is reached past attribution by mutated input`() {
        assertEquals(
            emptyMap(),
            rumorReachShortfalls(HostileCorpus.rumorCorpus(RUMOR_CORPUS), RUMOR_REACH_FLOOR),
            "these decoders were reached by fewer than $RUMOR_REACH_FLOOR mutated inputs past `attribute`",
        )
    }

    /** The floor's control: a corpus with one shape's inputs cut to zero fails that decoder's floor. */
    @JsName("a_rumor_corpus_missing_one_shape_fails_that_decoder_s_floor")
    @Test
    fun `a rumor corpus missing one shape fails that decoder's floor`() {
        val corpus = HostileCorpus.rumorCorpus(RUMOR_CORPUS)
        for (decoder in rumorDecoders) {
            val cut = corpus.filter { it.case.shape != decoder.shape }
            assertEquals(
                mapOf(decoder.label to 0),
                rumorReachShortfalls(cut, RUMOR_REACH_FLOOR),
                "cutting every ${decoder.shape} input must fail ${decoder.label}'s floor and no other",
            )
        }
    }

    /**
     * Every well-formed rumor attributes, and decodes **field for field** to the values its fixture
     * case was written from — so the sweep's decoders are the working ones, not decoders refusing
     * everything by name.
     */
    @JsName("well_formed_rumors_decode_field_for_field_to_their_fixture_s_inputs")
    @Test
    fun `well-formed rumors decode field for field to their fixture's inputs`() {
        val wellFormed = HostileCorpus.rumorCorpus(RUMOR_CORPUS)
            .filter { it.input.mutation == HostileCorpus.Mutation.WELL_FORMED }
        assertEquals(Shape.entries.toSet(), wellFormed.map { it.case.shape }.toSet())

        for (rumor in wellFormed) {
            val case = rumor.case
            val attributed = assertNotNull(rumorContextOf(rumor).attributed, "$rumor did not attribute")
            assertEquals(case.envelope.authorPubkey, attributed.author, "$rumor: author")
            assertEquals(case.envelope.createdAt, attributed.createdAt, "$rumor: created_at")
            assertEquals(case.envelope.content, attributed.content, "$rumor: content")
            assertEquals(case.envelope.counterparties, attributed.counterparties, "$rumor: counterparties")
            assertEquals(case.envelope.nenyaVersion, attributed.nenyaVersion, "$rumor: version")
            if (case.shape == Shape.CHAT) {
                assertTrue(attributed is AttributedRumor.Chat, "$rumor: a kind:14 attributes as chat")
                continue
            }
            val bound = assertIs<AttributedRumor.Bound>(attributed, "$rumor")
            when (case.shape) {
                Shape.CHAT -> Unit
                Shape.PRIVATE_BID -> {
                    val bid = PrivateBid.decode(bound)
                    val terms = assertNotNull(case.terms)
                    assertEquals(case.item, bid.item, "$rumor: item")
                    assertEquals(terms.split.price, bid.price, "$rumor: price")
                    assertEquals(terms.split.term, bid.fee, "$rumor: fee")
                    assertEquals(case.feeRecipient, bid.feeRecipient, "$rumor: fee recipient")
                    assertEquals(terms.expiration, bid.expiration, "$rumor: expiration")
                    assertEquals(terms.deliverBy, bid.deliverBy, "$rumor: deliver_by")
                }
                Shape.PROPOSAL -> {
                    val proposal = OrderProposal.decode(bound)
                    assertEquals(case.order, proposal.order, "$rumor: order")
                    assertEquals(case.item, proposal.item, "$rumor: item")
                    assertEquals(case.terms, proposal.terms, "$rumor: terms")
                    assertEquals(case.feeRecipient, proposal.feeRecipient, "$rumor: fee recipient")
                }
                Shape.ORDER_UPDATE -> {
                    val update = OrderStatusMessage.decode(bound)
                    assertEquals(case.order, update.order, "$rumor: order")
                    assertEquals(case.status, update.status, "$rumor: status")
                }
                Shape.COMMITMENT -> {
                    val message = DeliveryCommitmentMessage.decode(bound)
                    val commitment = assertNotNull(case.commitment)
                    assertEquals(case.order, message.order, "$rumor: order")
                    assertEquals(commitment.x, message.commitment.x, "$rumor: x")
                    assertEquals(commitment.ox, message.commitment.ox, "$rumor: ox")
                    assertEquals(commitment.mimeType, message.commitment.mimeType, "$rumor: m")
                    assertEquals(commitment.sizeBytes, message.commitment.sizeBytes, "$rumor: size")
                    assertEquals(case.url, message.url, "$rumor: url")
                }
                Shape.RELEASE -> {
                    val message = DeliverableReleaseMessage.decode(bound)
                    val release = assertNotNull(case.release)
                    assertEquals(case.order, message.order, "$rumor: order")
                    assertEquals(release.x, message.release.x, "$rumor: x")
                    assertEquals(release.ox, message.release.ox, "$rumor: ox")
                    assertEquals(release.fileType, message.release.fileType, "$rumor: file-type")
                    assertEquals(release.sizeBytes, message.release.sizeBytes, "$rumor: size")
                }
                Shape.PAYMENT_REQUEST -> {
                    val request = PaymentRequest.decode(bound, splitFor(case))
                    assertEquals(case.order, request.order, "$rumor: order")
                    assertEquals(case.payee, request.payee, "$rumor: payee")
                    assertEquals(case.invoice, request.invoice, "$rumor: invoice")
                    // §8.6's third `payee` element: the order's fee recipient on a `payee=fee`
                    // request, and none on a provider request.
                    assertEquals(
                        if (case.payee == Payee.FEE) case.feeRecipient else null,
                        request.feeRecipient,
                        "$rumor: payee's fee recipient",
                    )
                }
                Shape.RECEIPT -> {
                    val receipt = PaymentReceipt.decode(bound)
                    assertEquals(case.order, receipt.order, "$rumor: order")
                    assertEquals(case.payee, receipt.payee, "$rumor: payee")
                    assertEquals(case.medium, receipt.medium, "$rumor: medium")
                    assertEquals(case.reference, receipt.reference, "$rumor: reference")
                    // §9.2 check 2's preimage on the `lightning` rail; the other two rails' proofs
                    // are not preimages and decode to none.
                    assertEquals(
                        if (case.medium == PaymentMedium.LIGHTNING) Preimage.ofHex(assertNotNull(case.proof)) else null,
                        receipt.preimage,
                        "$rumor: preimage",
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The money path's string decoders: the BOLT-11 reader.
    // ---------------------------------------------------------------------------------------------

    /**
     * Whether [invoice] gets past `Bolt11Invoice.parse`'s checksum: recognised, and then either
     * accepted or refused for a reason other than [SettlementRejection.CHECKSUM_INVALID].
     *
     * Only a [SettlementException] is caught. Anything else thrown is the defect the JVM sweep names,
     * and here it fails the floor test outright — on JavaScript as well, which that sweep cannot reach.
     */
    internal fun reachesPastTheChecksum(invoice: String): Boolean {
        val reference = try {
            Bolt11Reference.recognise(invoice)
        } catch (refused: SettlementException) {
            return false
        }
        return try {
            Bolt11Invoice.parse(reference)
            true
        } catch (refused: SettlementException) {
            refused.reason != SettlementRejection.CHECKSUM_INVALID
        }
    }

    /** How many **mutated** entries of [corpus] reach past the checksum. */
    internal fun invoiceReach(corpus: List<HostileCorpus.InvoiceInput>): Int = corpus.count {
        it.mutation != HostileCorpus.Mutation.WELL_FORMED && reachesPastTheChecksum(it.invoice)
    }

    /** The invoice corpus floor: every lowercase valid example, every applicable change, reproducibly, within budget. */
    @JsName("the_invoice_corpus_carries_every_applicable_change_on_every_lowercase_valid_example")
    @Test
    fun `the invoice corpus carries every applicable change on every lowercase valid example`() {
        val corpus = HostileCorpus.invoiceCorpus()
        val examples = Bolt11Examples.extract().filter {
            it.group == Bolt11Examples.Group.VALID && it.invoice == it.invoice.lowercase()
        }

        assertTrue(examples.size > 10, "only ${examples.size} lowercase valid BOLT-11 examples were found")
        assertTrue(corpus.size <= CORPUS / 2, "the invoice corpus must stay well under the listing corpus's budget: ${corpus.size}")
        val applicable = HostileCorpus.Mutation.entries.filter { it !in HostileCorpus.INVOICE_SKIPPED }
        assertEquals(
            examples.size * (applicable.size + HostileCorpus.FieldMutation.entries.size * HostileCorpus.FIELD_DRAWS),
            corpus.size,
        )
        for (example in examples) {
            val own = corpus.filter { it.example == "line ${example.headingLine}" }
            assertEquals(applicable.toSet(), own.mapNotNull { it.mutation }.toSet(), "the mutations on $example")
            for (fieldMutation in HostileCorpus.FieldMutation.entries) {
                assertEquals(
                    HostileCorpus.FIELD_DRAWS,
                    own.count { it.fieldMutation == fieldMutation },
                    "$fieldMutation draws on $example",
                )
            }
            // The well-formed entry is the vendored string itself, and every other entry differs from it.
            val wellFormed = own.single { it.mutation == HostileCorpus.Mutation.WELL_FORMED }
            assertEquals(example.invoice, wellFormed.invoice, "$wellFormed is not its vendored example")
            for (entry in own - wellFormed) assertTrue(entry.invoice != example.invoice, "$entry changed nothing")
        }
        assertTrue(corpus.none { it.mutation in HostileCorpus.INVOICE_SKIPPED }, "a skipped mutation was applied")
        // Composed exactly where the checksum was recomputed: every tagged-field change and the three
        // decimal ones, and no raw edit.
        val decimals = setOf(
            HostileCorpus.Mutation.NON_CANONICAL_DECIMAL,
            HostileCorpus.Mutation.SIGNED_DECIMAL,
            HostileCorpus.Mutation.EXPONENTIAL_DECIMAL,
        )
        for (entry in corpus) {
            assertEquals(entry.fieldMutation != null || entry.mutation in decimals, entry.composed, "$entry: composed")
        }
        // Reproducible from the seed, compared as values for the reason the listing floor gives.
        assertEquals(corpus, HostileCorpus.invoiceCorpus())
    }

    /**
     * The BOLT-11 reader is reached past its checksum by mutated input — so the JVM sweep's `parse`
     * target sees field-level damage, not only strings its first check refuses.
     */
    @JsName("the_invoice_corpus_reaches_past_the_bolt11_checksum")
    @Test
    fun `the invoice corpus reaches past the BOLT-11 checksum`() {
        val reached = invoiceReach(HostileCorpus.invoiceCorpus())
        assertTrue(
            reached >= INVOICE_REACH_FLOOR,
            "only $reached mutated invoices got past the checksum; the floor is $INVOICE_REACH_FLOOR",
        )
    }

    /** The floor's control: without the composed entries, raw string edits alone do not meet it. */
    @JsName("without_the_composed_invoices_the_invoice_floor_fails")
    @Test
    fun `without the composed invoices the invoice floor fails`() {
        val raw = HostileCorpus.invoiceCorpus().filter { !it.composed }
        assertTrue(raw.isNotEmpty(), "the raw string edits must be in the corpus")
        val reached = invoiceReach(raw)
        assertTrue(
            reached < INVOICE_REACH_FLOOR,
            "$reached raw string edits reached past the checksum, so the floor does not need the composer",
        )
    }
}
