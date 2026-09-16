package dev.eryalabs.nenya.settlement

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
     * Nothing in this library reads it yet. Check 5 needs it **and** a BOLT-11 parser for the
     * invoice's own `timestamp` and `x` fields, which this library does not have — so
     * `PaymentCheck.INVOICE_EXPIRY` stays in the not-performed set, and this field is the half of
     * it §17 item 6 could be held to today.
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
         * Accept [request], recording the injected clock's reading, and store it in [into].
         *
         * One call rather than two, so "mint the record" and "persist it" cannot come apart: §17
         * item 6's obligation is about what an implementation *has kept*, and a factory a caller
         * could forget to follow with a store call would satisfy the type system and not the
         * clause.
         *
         * @param into the client's persistence. No default value, deliberately:
         *   [PaymentRequestStore.inMemory] exists and a default would construct a **fresh** one per
         *   call, silently discarding every record and turning every later receipt into
         *   [SettlementRejection.NO_STORED_REQUEST]. A store is a thing a client has to hold.
         * @param clock §4.6's injected clock, defaulting to the fail-closed one — which accepts
         *   nothing, and says so. A default that read the system clock would record a time the
         *   client never chose against an invoice it will later be asked to judge.
         * @return the record now in the store. If a request was already stored under the same
         *   `(order, payee)` key it is **replaced**, and the displaced record is not returned or
         *   merged: NENYA-1 states no rule for a second `type=2` under one key, and this library
         *   neither invents one nor de-duplicates — a caller that must refuse a replacement asks
         *   [PaymentRequestStore.find] first, which is the one shape that cannot get the rule wrong
         *   on its behalf.
         * @throws SettlementException [SettlementRejection.CLOCK_UNAVAILABLE] when the clock
         *   declined, or [SettlementRejection.CLOCK_BEFORE_EPOCH] when it reported a time before
         *   1970 — never defaulted, never stored as absent.
         */
        public fun accept(
            request: PaymentRequest,
            into: PaymentRequestStore,
            clock: NenyaClock = NenyaClock.FAIL_CLOSED,
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
            val record = Record(request.order, request.payee, request.invoice, seconds, request.sender)
            into.store(record)
            return record
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
     * Persist [accepted], replacing any record under the same `(order, payee)` key.
     *
     * @return the record now held under that key, which is [accepted]. A value rather than `Unit`
     *   for the reason every seam method returns one: an implementation that fails can only do so
     *   by throwing, and a caller holding the record it was given back can assert on it.
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

    override fun store(accepted: AcceptedPaymentRequest): AcceptedPaymentRequest {
        records[Key(accepted.order, accepted.payee)] = accepted
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
