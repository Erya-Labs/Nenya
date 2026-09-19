package dev.eryalabs.nenya.settlement

import dev.eryalabs.nenya.channel.Acceptance
import dev.eryalabs.nenya.payment.Payee
import dev.eryalabs.nenya.seam.NenyaClock
import dev.eryalabs.nenya.seam.OrderId
import dev.eryalabs.nenya.seam.SeamAnswer

/**
 * An accepted §8.6 payment request, together with **the value the injected clock held at the moment
 * it was accepted** — the two halves §17 item 6 requires be persisted.
 *
 * ### Why the clock reading is not optional
 *
 * §9.2 check 1: "This implies that an implementation MUST persist every payment request it accepts,
 * **together with the value its injected clock held at the moment it accepted it** — check 5 is
 * unperformable without that second value, and §17 item 6 requires both." Check 5 then says the
 * expiry "MUST NOT be in the past relative to the injected clock **as it read at the moment the
 * invoice was accepted**", and is deliberately *not* re-evaluated later: "an invoice that was live
 * when the buyer paid it does not become unpaid because it has since expired."
 *
 * So there is no shape here that holds an invoice and no time. With `NenyaClock.FAIL_CLOSED` — the
 * default, and what a client that injected nothing gets — [accept] refuses the request outright
 * with [SettlementRejection.CLOCK_UNAVAILABLE] rather than storing it against a fabricated `now` or
 * a `null`. That is T5's fail-closed rule reaching a **store** for the first time, and it is the
 * shape a later check 5 depends on: a request that is present is a request whose expiry can be
 * judged.
 *
 * ### Unforgeable through the published API
 *
 * Same shape and same reason as `VerifiedPayment`, `DeliveryEvidence`, `CheckedEvent` and
 * `AttributedRumor`: a public `sealed interface` whose single implementation is a `private` class
 * nested inside its companion. An interface has no constructor to synthesise an accessor for, so
 * `Class.getConstructors()` on it is empty by construction and stays empty; a `private constructor`
 * plus a companion factory emits a public synthetic constructor a Java client can call with a
 * `null` marker, and a `data class` with a private primary constructor is still reachable through
 * the generated `copy()`.
 *
 * What that buys is narrow and worth stating: the only records an injected [PaymentRequestStore]
 * can ever be handed are ones [accept] minted, from a request this library decoded and recognised
 * and a clock reading it took itself. It does **not** make an injected store trustworthy — a store
 * is the embedding client's own persistence, not a counterparty's, and §13 says Nenya does not
 * defend its user against the client embedding it. A client whose store forgets records will find
 * receipts rejected as [SettlementRejection.NO_STORED_REQUEST], which is the fail-closed direction.
 *
 * The claim is "unforgeable **through the published API**", not unforgeable full stop: a `private`
 * nested class compiles to a package-private JVM class with a public constructor, so a client that
 * declares itself into `dev.eryalabs.nenya.settlement` on the same classloader can still reach it.
 */
public sealed interface AcceptedPaymentRequest {

    /** §7.4's `order` id this request is for. Half of the store's key. */
    public val order: OrderId

    /** §8.6's payee role. The other half of the key — and never a collection (§8.6). */
    public val payee: Payee

    /**
     * The BOLT-11 string, **verbatim**, as it arrived in the `type=2`.
     *
     * §9.2 check 1: "No normalisation, no case folding, no re-encoding, no bech32 round-trip."
     * Nothing between the wire and this field touches the characters.
     */
    public val invoice: Bolt11Reference

    /**
     * The injected clock's reading, in unix seconds, at the moment this request was accepted
     * (§9.2 check 1, §9.2 check 5, §17 item 6).
     *
     * A `Long` and not a nullable one: the absence is refused at [accept] rather than represented
     * here, so a stored record always carries the value check 5 needs.
     *
     * This is §9.2 check 5's **only** operand, and [Settlement.verify] reads it: the check is
     * `expiry >= acceptedAt − timestamp` over the invoice this record holds, parsed through
     * `Bolt11Invoice.parse`. The clock is deliberately not re-read at verification — §9.2 says an
     * invoice that was live when the buyer paid it does not become unpaid because it has since
     * expired — so a record that kept the invoice and threw this value away could never make the
     * comparison at all, which is why [accept] refuses rather than storing a `null`.
     *
     * `PaymentCheck.INVOICE_EXPIRY` stays in `Capabilities.PAYMENT_CHECKS_NOT_PERFORMED` all the
     * same, for the reason `PaymentCheck.INVOICE_IDENTITY` does: that set is a claim about a bare
     * `VerifiedPayment.verify`, which holds no store and parses no invoice. What the store path
     * did is on its own result's `checksPerformed`.
     */
    public val acceptedAt: Long

    /**
     * §7.2's attribution of the `type=2` this record was minted from: the **seal's** pubkey, in
     * §4.3's canonical lowercase.
     *
     * §8.7 binds a fee invoice to its recipient through the transport and nothing else — "a
     * `type=2` payment request with `["payee", "fee", ...]` MUST arrive in a gift wrap whose seal
     * (`kind:13`) `pubkey` equals the fee-recipient pubkey named in the signed fee term" — so the
     * operand of that comparison is a property of the message the invoice arrived in, and a store
     * that kept the invoice and threw the sender away could never make it again.
     *
     * That is exactly the position §9.2 check 5 is in with the clock reading, and the same
     * conclusion: [Settlement.verifyFeeReceipt] performs §8.7 against **this** field, because a
     * fee *receipt* is authored by the buyer (§9.2's own worked example says so) and its own seal
     * is therefore never the fee recipient's. See that function's note.
     *
     * That makes this field a **security operand** and not only a record, and what it does not
     * change is worth stating: an injected store is still the embedding client's own persistence,
     * so a store that hands back a fabricated `sealedBy` defeats §8.7 exactly as a store that
     * hands back a fabricated invoice defeats check 1. Both are the client lying to itself, which
     * §13 puts outside what this library defends against. What is guaranteed is narrower and is
     * the part that matters against a counterparty: the only records [accept] mints carry the key
     * **this library** read off the attributed rumor.
     */
    public val sealedBy: String

    public companion object {

        /**
         * Accept [request] against [acceptance], recording the injected clock's reading, and store
         * it in [into].
         *
         * One call rather than two, so "mint the record" and "persist it" cannot come apart: §17
         * item 6's obligation is about what an implementation *has kept*, and a factory a caller
         * could forget to follow with a store call would satisfy the type system and not the
         * clause. Revision `1.5` makes the same argument about the checks: every one below is a
         * rule about what may be **stored**, so none of them is a step a caller can take or skip.
         *
         * ### The seven refusals, in the order they are made
         *
         * The clock is read first and is unchanged: §9.2 check 5 is unperformable without that
         * value and there is no shape here that holds an invoice and no time, so a store that
         * cannot record *when* accepts nothing at all. Then, before anything reaches [into]:
         *
         * 1. [SettlementRejection.REQUEST_FOR_ANOTHER_ORDER] — [acceptance] is the source of every
         *    operand below, so one for another order would check this request against a provider, a
         *    price and a fee term nobody signed for it, and report each check performed.
         * 2. **Who sealed it**, by payee. A `payee=provider` request is held to §8.6's revision
         *    `1.5` rule — the seal's pubkey is [Acceptance.Accepted.provider], the key §7.6 checked
         *    the acceptance against — and a `payee=fee` one to §8.4 and §8.7 through
         *    [Settlement.checkFeePaymentRequest], so an unchecked fee request can no longer be
         *    stored at all.
         * 3. **Appendix C**, then 4. **§9.2 check 4** and 5. **check 5**, by way of
         *    `Settlement.checkAmountAndExpiry` — the same helper the receipt path uses, not a
         *    second reading of the same two sentences. Decision I as amended: an invoice this
         *    library would refuse a receipt against is an invoice it must not have stored.
         * 6. [SettlementRejection.REQUEST_ALREADY_STORED] and
         *    7. [SettlementRejection.INVOICE_STORED_FOR_OTHER_PAYEE], §8.6's two revision `1.5`
         *    uniqueness rules. Both leave every existing record exactly as it was.
         *
         * **The order is a decision and not an accident.** Who sealed the message is settled before
         * anything about its contents, because a request that is both from the wrong key and
         * over-priced is first of all not the provider's message — a caller told "wrong amount"
         * would go and negotiate a price with a stranger.
         *
         * @param acceptance §7.6's checked acceptance **for this order**, from
         *   [dev.eryalabs.nenya.channel.OrderProposal.accepts]. It carries the three things this
         *   function cannot obtain honestly any other way: the order it is about, the provider key
         *   the seal is compared against, and the accepted terms §8.3's split — and therefore §9.2
         *   check 4's expected amount — is computed from. Taking them as separate parameters would
         *   let a caller assemble three halves of three different orders (decision J), and taking
         *   the provider key alone would let the amount be checked against terms nobody accepted.
         * @param into the client's persistence. No default value, deliberately:
         *   [PaymentRequestStore.inMemory] exists and a default would construct a **fresh** one per
         *   call, silently discarding every record and turning every later receipt into
         *   [SettlementRejection.NO_STORED_REQUEST]. A store is a thing a client has to hold.
         * @param clock §4.6's injected clock, defaulting to the fail-closed one — which accepts
         *   nothing, and says so. A default that read the system clock would record a time the
         *   client never chose against an invoice it will later be asked to judge.
         * @param feeTermPoints §8.4's points observed **before** this request, for a `payee=fee`
         *   one; ignored for a provider request, which §8.4 makes the `fee` tag OPTIONAL on and
         *   forbids requiring one there. Empty by default, and that default is a refusal rather
         *   than a convenience: [Settlement.checkFeePaymentRequest] requires the proposal and the
         *   acceptance to be among them, so a caller that names none has its fee request rejected
         *   [SettlementRejection.FEE_TERM_POINT_MISSING] rather than stored unchecked.
         * @return the record this function minted and handed to [into] — never the value [into]
         *   returned, which a client store chooses and which §13 does not make trustworthy.
         * @throws SettlementException [SettlementRejection.CLOCK_UNAVAILABLE] when the clock
         *   declined, or [SettlementRejection.CLOCK_BEFORE_EPOCH] when it reported a time before
         *   1970 — never defaulted, never stored as absent; then whichever of the seven above
         *   refused it, or any [SettlementRejection.APPENDIX_C_PARSER] reason the invoice does not
         *   decode under.
         */
        public fun accept(
            request: PaymentRequest,
            acceptance: Acceptance.Accepted,
            into: PaymentRequestStore,
            clock: NenyaClock = NenyaClock.FAIL_CLOSED,
            feeTermPoints: List<FeeTermSighting> = emptyList(),
        ): AcceptedPaymentRequest {
            val reading = clock.now()
            val seconds = when (reading) {
                is SeamAnswer.Provided -> reading.value
                is SeamAnswer.Unavailable -> throw SettlementException(
                    SettlementRejection.CLOCK_UNAVAILABLE,
                    null,
                    "the injected clock answered unavailable (${reading.capability.name}), so this " +
                        "implementation cannot say when it accepted this request. §9.2 check 1 and " +
                        "§17 item 6 require that reading be persisted alongside the invoice and " +
                        "§9.2 check 5 is unperformable without it, so the request is not accepted " +
                        "at all — never stored against a fabricated time and never against none",
                )
            }
            if (seconds < 0) {
                throw SettlementException(
                    SettlementRejection.CLOCK_BEFORE_EPOCH,
                    null,
                    "the injected clock reported a time before 1970. §4.3 fixes every timestamp as " +
                        "a non-negative integer, so this clock is not reporting an unusual time — " +
                        "it is broken, and no acceptance moment can honestly be recorded from it",
                )
            }
            if (request.order != acceptance.order) {
                throw SettlementException(
                    SettlementRejection.REQUEST_FOR_ANOTHER_ORDER,
                    SettlementVocabulary.ORDER,
                    "this `type=2` names one order and the §7.6 acceptance it was offered against " +
                        "names another. Every check below is derived from that acceptance — the " +
                        "provider's key, §8.3's expected amount, the signed fee term — so judging " +
                        "the request against it would compare it to terms nobody signed for it, " +
                        "and would report each of those checks as performed",
                )
            }
            checkSender(request, acceptance, feeTermPoints)
            Settlement.checkAmountAndExpiry(
                request.invoice,
                seconds,
                Settlement.expectedAmount(request.payee, acceptance.terms.split),
            )
            checkNotAlreadyStored(request, into)
            val record = Record(request.order, request.payee, request.invoice, seconds, request.sender)
            into.store(record)
            // The record this function minted, and **not** whatever [into] handed back. A store is
            // the embedding client's own persistence (§13) and may return another record entirely;
            // a caller that took the store's value would be told "this is what you just accepted"
            // about a value this library did not build for this request.
            return record
        }

        /**
         * §8.6's and §8.7's two sender rules, one per payee — which is why this is a `when` over
         * [Payee] and not an `if`.
         *
         * A constant added to that enum later must not be able to fall through to "no sender check
         * performed", which is the one outcome here that is silent and wrong. The same reasoning
         * `MoneyException.asChannelRejection` gives for its own exhaustive `when`.
         *
         * The fee side is delegated whole to [Settlement.checkFeePaymentRequest] rather than
         * reimplemented: §8.7's operand is the recipient in the **signed fee term**, which is a
         * sequence of raw tags across §8.4's points and not anything a `FeeSplit` holds, and that
         * function is where both halves already meet. What is new is that it is now unskippable —
         * before revision `1.5` a client could store a fee request without ever calling it.
         */
        private fun checkSender(
            request: PaymentRequest,
            acceptance: Acceptance.Accepted,
            feeTermPoints: List<FeeTermSighting>,
        ) {
            when (request.payee) {
                Payee.PROVIDER -> if (request.sender != acceptance.provider) {
                    throw SettlementException(
                        SettlementRejection.PROVIDER_REQUEST_NOT_FROM_PROVIDER,
                        SettlementVocabulary.PAYEE,
                        "§8.6: a `type=2` with `[\"${SettlementVocabulary.PAYEE}\", " +
                            "\"${Payee.PROVIDER.token}\"]` MUST arrive in a gift wrap whose seal " +
                            "(kind:13) `pubkey` is the provider's key — the same key the " +
                            "acceptance for this order was checked against (§7.6, §11.2) — and " +
                            "any other MUST be rejected. This is not a missing or a malformed " +
                            "`${SettlementVocabulary.PAYEE}` tag: the tag is present and well " +
                            "formed, and what is wrong is who sealed the message. A provider " +
                            "invoice from another key is somebody else's bill under the " +
                            "provider's name, and §9.2 check 1 would anchor this order's whole " +
                            "payment evidence to it",
                    )
                }

                Payee.FEE -> Settlement.checkFeePaymentRequest(request, feeTermPoints)
            }
        }

        /**
         * §8.6's two revision `1.5` uniqueness rules, in the order the document states them.
         *
         * Neither writes anything: both are asked of [into] before [accept] mints a record, so a
         * refusal leaves every existing record exactly as it was — which is the whole content of
         * [SettlementRejection.REQUEST_ALREADY_STORED], whose point is that the **first** record
         * survives.
         *
         * The cross-payee sweep walks [Payee] rather than asking the store for a collection, and
         * that is §8.6's non-custodial rule showing through the shape: no method on
         * [PaymentRequestStore] takes or returns a collection of payees, because there is to be no
         * value anywhere in this package that names two payees of one order at once.
         */
        private fun checkNotAlreadyStored(request: PaymentRequest, into: PaymentRequestStore) {
            if (into.find(request.order, request.payee) != null) {
                throw SettlementException(
                    SettlementRejection.REQUEST_ALREADY_STORED,
                    SettlementVocabulary.PAYMENT,
                    "§8.6: once a `type=2` has been accepted for an order and payee, a second MUST " +
                        "be rejected rather than replacing it — §9.2 check 1 compares a receipt " +
                        "against *the* stored request, so a replacement re-points this order's " +
                        "payment evidence after the fact, including after the buyer has paid the " +
                        "first. The record already held is untouched. A provider re-sending its " +
                        "own invoice is refused too: an invoice that expires unpaid is not " +
                        "re-issued inside the order",
                )
            }
            for (other in Payee.entries) {
                if (other == request.payee) continue
                val held = into.find(request.order, other) ?: continue
                if (held.invoice.text != request.invoice.text) continue
                throw SettlementException(
                    SettlementRejection.INVOICE_STORED_FOR_OTHER_PAYEE,
                    SettlementVocabulary.PAYMENT,
                    "§8.6: an invoice already accepted for one payee on an order MUST be rejected " +
                        "for any other payee on that order. One payment cannot settle two payees, " +
                        "and a combined bill is custody — somebody would hold " +
                        "`price_msat + fee_msat` and owe another party the difference, which §1 " +
                        "rules out. Both records are untouched. The comparison is over the " +
                        "verbatim BOLT-11 string, which is the byte-identity §9.2 check 1 makes",
                )
            }
        }

        /**
         * The single implementation. Private, so the only door in is [accept].
         *
         * Nested in the companion rather than at package level so that "the only door in is
         * [accept]" is enforced by the compiler rather than by convention.
         */
        private class Record(
            override val order: OrderId,
            override val payee: Payee,
            override val invoice: Bolt11Reference,
            override val acceptedAt: Long,
            override val sealedBy: String,
        ) : AcceptedPaymentRequest {

            /**
             * Names the payee role and nothing else — not the order id, not the invoice, not the
             * sealing key and not even the acceptance time.
             *
             * §12 item 11 and STOP RULE 14 name the order id alongside key material and preimages,
             * §12 item 1 keeps an invoice out of anything public and §12 item 2 the counterparty
             * pubkey. The clock reading is left out too: it is a timestamp of this user's activity
             * on one order, which is the correlation handle §7.1 randomises gift-wrap timestamps
             * to deny.
             */
            override fun toString(): String = "AcceptedPaymentRequest(payee=${payee.token})"
        }
    }
}

/**
 * The persisted store of accepted `type=2` payment requests that §9.2 check 1 is checked against
 * and §17 item 6 requires — keyed by `(order, payee)`, which is exactly the pair check 1 names.
 *
 * ### Injected, because persistence is the client's
 *
 * §17 item 6 requires an implementation to have "persisted the payment requests and their
 * acceptance timestamps that evidence is checked against". *Where* is not NENYA-1's business and is
 * certainly not this library's: nothing here opens a file or a socket (STOP RULE 13), and a client
 * with a database has one. So this is an interface with [inMemory] as the implementation this
 * library ships, and a client that needs records to outlive a process implements it.
 *
 * ### The key is the pair, and §8.6's non-custodial rule rides on that
 *
 * §8.6: "The buyer MUST pay the provider and the fee recipient with two separate payments to two
 * separate invoices. An implementation MUST NOT construct, accept, or honour a single invoice
 * covering `total_msat` that some party then splits." Keying by `(order, payee)` makes the two
 * invoices two records with no shape that could join them, and no method here takes or returns a
 * collection of payees — `SettlementStructureTest` asserts that over the **generic** signatures,
 * because `List<Payee>` erases to `List` and an erasure-blind sweep would pass over exactly the
 * shape §8.6 forbids.
 *
 * ### Nothing here is evidence
 *
 * A record this store hands back is a thing the client persisted, and §9.2 check 1 is a byte
 * comparison against it — not a claim that a payment happened. The only payment evidence in this
 * library is still a preimage `VerifiedPayment` hashed itself.
 */
public interface PaymentRequestStore {

    /**
     * Persist [accepted]. **A record already held under the same `(order, payee)` key is not
     * replaced**, and an implementation MUST refuse rather than overwrite it.
     *
     * §8.6 (revision `1.5`): "Once a `type=2` has been accepted for an `(order, payee)` pair, a
     * second MUST be rejected rather than replacing it." Before that revision this method
     * documented the opposite, and the hole it left is gap G1d: §9.2 check 1 compares a receipt
     * against *the* stored request, so a party who can get a second one accepted re-points this
     * order's payment evidence at an invoice of its choosing, including after the buyer has paid
     * the first.
     *
     * [AcceptedPaymentRequest.accept] asks [find] and refuses before ever calling this, so a store
     * reached through the only door that mints a record never sees a duplicate key. The obligation
     * is stated here as well, and [inMemory] keeps it, for the reason §13 draws its line where it
     * does: a store is the embedding client's own persistence, and a client and this library
     * disagreeing about what a second `store` means is precisely how a record gets silently
     * replaced behind a check that refused the replacement.
     *
     * @return the record now held under that key, which is [accepted]. A value rather than `Unit`
     *   for the reason every seam method returns one: an implementation that fails can only do so
     *   by throwing, and a caller holding the record it was given back can assert on it.
     * @throws SettlementException [SettlementRejection.REQUEST_ALREADY_STORED] when a record is
     *   already held under that key, leaving it untouched.
     */
    public fun store(accepted: AcceptedPaymentRequest): AcceptedPaymentRequest

    /**
     * The request stored for [order] and [payee], or `null` if none was.
     *
     * `null` and not a throw, and not a `Boolean` beside a value: §9.2 check 1 gives "no such
     * payment request was received and stored" its own outcome, distinct from a mismatch, and
     * [Settlement] turns this `null` into [SettlementRejection.NO_STORED_REQUEST] so a caller is
     * told which of the two happened.
     */
    public fun find(order: OrderId, payee: Payee): AcceptedPaymentRequest?

    public companion object {

        /**
         * A store held in memory for the lifetime of the object.
         *
         * Enough for a client that verifies receipts inside one session and for the whole of this
         * library's test suite; **not** enough for §17 item 6 on its own, which is about what
         * survives. A client that restarts and cannot find a request will reject the receipt, which
         * is check 1's own fail-closed direction and not a silent acceptance — but it is still a
         * receipt the buyer legitimately sent, so a real client persists.
         */
        public fun inMemory(): PaymentRequestStore = InMemoryPaymentRequestStore()
    }
}

/**
 * [PaymentRequestStore.inMemory]'s implementation: a map keyed by the pair §9.2 check 1 names.
 *
 * File-private rather than published: a client that wants this one calls the factory, and a client
 * that wants another writes its own. Not thread-safe, and that is stated rather than implied —
 * common Kotlin has no shared-memory concurrency primitive on the dependency budget, and a client
 * that verifies receipts from two threads owns that problem along with the persistence.
 */
private class InMemoryPaymentRequestStore : PaymentRequestStore {

    private val records = mutableMapOf<Key, AcceptedPaymentRequest>()

    /**
     * §8.6's no-replacement rule, kept by the store this library ships as well as by the door that
     * reaches it — see [PaymentRequestStore.store] for why both.
     *
     * The refusal is made **before** the write and by `put`-free lookup, so the record already held
     * survives byte for byte. `records[key] = accepted` guarded by an `if` afterwards would be the
     * same rule written the one way that can still lose the first record to an early return.
     */
    override fun store(accepted: AcceptedPaymentRequest): AcceptedPaymentRequest {
        val key = Key(accepted.order, accepted.payee)
        if (key in records) {
            throw SettlementException(
                SettlementRejection.REQUEST_ALREADY_STORED,
                SettlementVocabulary.PAYMENT,
                "§8.6: a `type=2` has already been accepted for this order and payee, and a second " +
                    "MUST be rejected rather than replacing it. The record already held is " +
                    "untouched. This is the library's own store keeping the rule its own " +
                    "`AcceptedPaymentRequest.accept` enforces one step earlier, so that a client " +
                    "store and this one cannot come to mean different things by a second `store`",
            )
        }
        records[key] = accepted
        return accepted
    }

    override fun find(order: OrderId, payee: Payee): AcceptedPaymentRequest? = records[Key(order, payee)]

    /**
     * Names how many records are held and nothing about any of them.
     *
     * §12 item 11 and STOP RULE 14: this object transitively holds every order id and every invoice
     * string the client has accepted, which is the largest single collection of them anywhere in
     * this library. A count is not one of those values.
     */
    override fun toString(): String = "PaymentRequestStore.inMemory(records=${records.size})"

    /**
     * §9.2 check 1's `(order, payee)` pair, as one value.
     *
     * A declared key class rather than a `Pair`, so the store's key is a thing with a name that a
     * reader can check against check 1's wording — and so that equality is `OrderId`'s own
     * constant-time comparison rather than whatever a tuple happened to inherit.
     */
    private class Key(private val order: OrderId, private val payee: Payee) {

        override fun equals(other: Any?): Boolean =
            other is Key && other.order == order && other.payee == payee

        override fun hashCode(): Int = order.hashCode() * KEY_MULTIPLIER + payee.ordinal

        /** Holds an order id (§12 item 11), so it names the role and a redaction. */
        override fun toString(): String = "Key(order=redacted, payee=${payee.token})"

        private companion object {

            /** The odd multiplier Kotlin's own generated `hashCode` uses. */
            const val KEY_MULTIPLIER: Int = 31
        }
    }
}
