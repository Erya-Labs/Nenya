package dev.eryalabs.nenya.order

/**
 * Why an order-state value was refused.
 *
 * The reason is part of the API and not merely diagnostic text, mirroring
 * `PaymentRejection` and `MoneyRejection`: a caller branches on the constant and tests
 * assert on it rather than on message wording.
 */
public enum class OrderStateRejection {

    /**
     * [OrderState.UNKNOWN] was handed to [OrderStatusCodec.write].
     *
     * §11.1 fixes ten canonical tokens and says an unrecognised status MUST be treated as
     * `unknown`. `unknown` is the *absence* of a recognised state, not an eleventh token on
     * the wire, so there is nothing to emit for it — and emitting one would republish a
     * stranger's unrecognised token as though this implementation had understood it.
     */
    UNKNOWN_IS_NOT_EMITTABLE,

    /**
     * [OrderTerms] were built with an `expiration` at or after their `deliver_by`.
     *
     * §7.5 requires `expiration` to fall strictly before `deliver_by` when both are present, and
     * requires an implementation to **reject** a proposal that violates it rather than prefer one
     * of the two: an order that can still be accepted after its own delivery deadline has passed
     * is incoherent, and leaves the provider in a state where acceptance and `expired` are
     * simultaneously correct. Refused at construction, so no such order exists to transition.
     */
    DEADLINES_INVERTED,

    /**
     * An [OrderMachine] was built with a zero or negative release timeout.
     *
     * §11.2 requires an implementation whose accepted terms carry no `deliver_by` to "apply and
     * display a release timeout of its own" and MUST NOT leave the order in `paid` indefinitely.
     * A non-positive one satisfies neither half: it disputes an order the moment it is paid,
     * which is not a timeout but a refusal to deliver.
     */
    RELEASE_TIMEOUT_NOT_POSITIVE,

    /**
     * A timestamp that §4.3 fixes as non-negative unix seconds was negative: an [OrderTerms]
     * `expiration` or `deliver_by`, or a rumor's `created_at`.
     *
     * These are wire values, and §4.3 has no encoding for a negative one — `WireEvent` and the
     * tag codec already refuse it on read. A negative value reaching the order package was
     * therefore made up rather than decoded, and is refused at construction.
     */
    NEGATIVE_TIMESTAMP,
}

/**
 * An order-state value was refused, carrying the [reason] as data.
 *
 * One exception type for the whole order package, so a caller has one thing to catch and
 * one field to branch on.
 *
 * **The message never echoes the caller's input.** An order `status` token arrives sealed
 * inside a `kind:16` `type=3` and is therefore private-channel content; §12 item 11 and
 * STOP RULE 14 keep it out of logs and crash reports. A rejection message may name a rule;
 * it may never name the bytes that broke it.
 */
public class OrderStateException internal constructor(
    public val reason: OrderStateRejection,
    message: String,
) : IllegalArgumentException(message)

/**
 * §11.1's order-state vocabulary: the ten canonical lowercase tokens, plus the
 * non-transitionable [UNKNOWN] sink §11.1 requires.
 *
 * ### This is the ORDER vocabulary, and it is not the listing vocabulary
 *
 * §11.1 states the trap plainly and this package exists in order to keep the two apart. A
 * public listing (§5.1, §5.2) carries `active | sold` for an offer, or
 * `active | awarded | fulfilled | cancelled` for a request. An order carries the ten tokens
 * below. They ride the *same tag name*, `["status", ...]`, and they share exactly one token
 * — `cancelled` — whose meaning differs: a cancelled **listing** is withdrawn from the
 * board, a cancelled **order** ended before `paid`.
 *
 * So §11.1 forbids one codec serving both, and `cancelled` is precisely the token where the
 * conflation looks correct and passes a careless test. Nothing here may be extended,
 * generalised or reused toward a listing status. When a listing codec is written it decodes
 * to its own, non-interchangeable type, and §11.1 additionally forbids deriving order state
 * from a listing status under any circumstances — a buyer republishing their request as
 * `fulfilled` is an announcement to the board, not a transition.
 *
 * ### The eleventh case is the specification's, not an invention
 *
 * §11.1: "An unrecognised status value MUST be treated as `unknown` and MUST NOT be mapped
 * onto the nearest known state. In particular an unknown status MUST NOT be treated as
 * `paid` or `settled`." A codec that returned `null`, or threw, would push that decision
 * back onto every caller, and the nearest-known-state mapping is exactly what a caller
 * writing its own fallback reaches for. So the sink is a state: [UNKNOWN], which carries no
 * token, appears in no row of §11.2, and therefore neither originates nor receives a
 * transition.
 *
 * ### Reserved tokens
 *
 * §11.1 reserves `held`, `refunded`, `in_review` and `resolved` for a future escrow
 * revision and says a v1 implementation MUST NOT emit them. They are deliberately absent
 * here: they read as [UNKNOWN] like any other unrecognised token, and no constant in this
 * package can produce one.
 *
 * ### Never branch on ordinal
 *
 * §11.4 says escrow would enter as a `held` state between `accepted` and `committed`, and
 * that implementations MUST NOT write transition logic enumerating "everything after
 * `accepted`" in a way a new state would silently join. Declaration order here follows the
 * §11.1 table for readability and carries **no** meaning: every property below is an
 * explicit `when` over named constants, and a caller must do the same.
 */
public enum class OrderState(

    /**
     * The canonical lowercase token §11.1 fixes for this state, or `null` for [UNKNOWN],
     * which has none.
     *
     * Nullable on purpose. The alternative — a sentinel string such as `"unknown"` — would
     * put an eleventh token within reach of anything that writes a `status` tag, and §11.1
     * defines `unknown` as a *treatment* of an unrecognised value rather than as a value.
     * A caller that has to reckon with the null is a caller that has noticed the sink.
     */
    public val token: String?,

    /**
     * Whether §11.1 marks this state **Terminal**.
     *
     * `settled` (terminal, success), `cancelled`, `expired` and `disputed` (terminal in v1
     * — Nenya provides no resolution mechanism). §11.3 invariant 3 says there is no path
     * from any terminal state to any other state; enforcing that is the transition
     * function's job and this flag is the vocabulary half of it.
     *
     * `false` for [UNKNOWN], which is not one of §11.1's terminals. That is not a claim
     * that an order can leave it: [UNKNOWN] is unreachable *as* a state — nothing may
     * transition into it and it appears in no §11.2 row — which is a different fact from
     * being an agreed terminal outcome, and conflating the two would report a garbled
     * status update as a settled or cancelled order.
     */
    public val isTerminal: Boolean,
) {

    /** Buyer has sent `type=1`; nothing is agreed. */
    PROPOSED(token = "proposed", isTerminal = false),

    /** Provider has accepted the terms as sent. */
    ACCEPTED(token = "accepted", isTerminal = false),

    /** Provider has published the deliverable commitment (`type=5`) with `x` and `ox`. */
    COMMITTED(token = "committed", isTerminal = false),

    /**
     * Every **required** payment request (`type=2`) has been received and validated against
     * the terms — required meaning named by the terms **and** carrying a non-zero expected
     * amount (§8.3, §9.2, and `FeeSplit.feePayeeRequired`).
     */
    AWAITING_PAYMENT(token = "awaiting_payment", isTerminal = false),

    /**
     * Every **required** receipt has been **verified** per §9.2, with "required" as in
     * [AWAITING_PAYMENT].
     *
     * Verified means this library hashed a preimage itself (§9.1, STOP RULE 12) — never a
     * wallet's `Boolean` and never a counterparty's status update.
     */
    PAID(token = "paid", isTerminal = false),

    /** Provider has sent the `kind:15` with the decryption key. */
    RELEASED(token = "released", isTerminal = false),

    /**
     * Buyer has verified `x` and `ox` **by their own computation** (§10.4, §11.3 invariant
     * 4). Terminal, success.
     */
    SETTLED(token = "settled", isTerminal = true),

    /**
     * Either party cancelled before `paid`. Terminal.
     *
     * The one token this vocabulary shares with the public listing vocabulary, with a
     * different meaning. See this enum's note on the conflation trap.
     */
    CANCELLED(token = "cancelled", isTerminal = true),

    /** A deadline passed. Terminal. */
    EXPIRED(token = "expired", isTerminal = true),

    /**
     * Something failed after `paid`, or a commitment was violated. Terminal in v1 — Nenya
     * provides no resolution mechanism (§14 item 3).
     */
    DISPUTED(token = "disputed", isTerminal = true),

    /**
     * §11.1's required treatment of an unrecognised status token: a sink that is **not**
     * mapped onto the nearest known state, and in particular never onto [PAID] or
     * [SETTLED].
     *
     * Carries no token, so nothing in this package can emit it (§11.1's reserved-token rule
     * reaches it too, by construction rather than by a list). It appears in neither column
     * of §11.2's table, so it originates no transition and receives none.
     */
    UNKNOWN(token = null, isTerminal = false),
    ;

    /**
     * Whether this is one of §11.1's ten canonical states rather than the [UNKNOWN] sink.
     *
     * Equivalent to `token != null` and named because that is the question callers ask.
     */
    public val isRecognised: Boolean get() = token != null
}

/**
 * The order-state codec, and **only** the order-state codec (§11.1).
 *
 * `read` is total: every string maps to an [OrderState], with anything §11.1 does not name
 * going to [OrderState.UNKNOWN]. `write` is partial in exactly one place, because there is
 * no token to emit for the sink.
 *
 * ### No normalisation
 *
 * §11.1 calls its tokens "canonical lowercase tokens", and §4.3's list of values that get
 * case-folded on the way in is exhaustive and does not include a status token. So `Paid`,
 * `PAID` and ` paid ` all read as [OrderState.UNKNOWN] rather than as [OrderState.PAID].
 * Folding them would be the nearest-known-state mapping §11.1 forbids, arriving through the
 * side door: a counterparty that sends `PAID` has sent something this vocabulary does not
 * define, and guessing what they meant is guessing about money.
 *
 * ### This codec MUST NOT be pointed at a listing status
 *
 * §11.1 forbids parsing a listing `status` with the order-state codec, or an order `status`
 * with the listing codec. The overlap is `cancelled` and it is the token where the mistake
 * looks like a success. There is no listing codec yet; when there is, it is a separate
 * function returning a separate type, and this one does not grow a flag.
 */
public object OrderStatusCodec {

    private val BY_TOKEN: Map<String, OrderState> =
        OrderState.entries.mapNotNull { state -> state.token?.let { it to state } }.toMap()

    /**
     * The state [statusToken] names, or [OrderState.UNKNOWN] if §11.1 does not name it.
     *
     * Total, exact and case-sensitive: see this object's notes. Never throws — an
     * unrecognised status is a fact about the counterparty, not a malformed input, and §11.1
     * prescribes the treatment rather than a refusal.
     */
    public fun read(statusToken: String): OrderState = BY_TOKEN[statusToken] ?: OrderState.UNKNOWN

    /**
     * The canonical token for [state], for a `["status", ...]` tag on a `kind:16` `type=3`.
     *
     * @throws OrderStateException [OrderStateRejection.UNKNOWN_IS_NOT_EMITTABLE] for
     *   [OrderState.UNKNOWN]. §11.1's four reserved tokens need no such guard: they are not
     *   constants of [OrderState] at all, so no call can reach one.
     */
    public fun write(state: OrderState): String =
        state.token ?: throw OrderStateException(
            OrderStateRejection.UNKNOWN_IS_NOT_EMITTABLE,
            "§11.1's `unknown` is the treatment of an unrecognised status, not a token: " +
                "there is nothing to emit for it",
        )
}
