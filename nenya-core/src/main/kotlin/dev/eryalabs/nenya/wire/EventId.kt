package dev.eryalabs.nenya.wire

import java.security.MessageDigest

/**
 * A nostr event id — 32 bytes, read from or written as 64 hex characters (§4.1, §4.3).
 *
 * §4.1: the id is the SHA-256 of the **UTF-8 bytes** of the canonical serialisation. The charset
 * is load-bearing rather than incidental: a serialisation carrying one non-BMP character hashes
 * to a different value under ISO-8859-1, and an implementation that got it wrong would compute
 * ids nobody else computes for exactly the events that carry an emoji.
 *
 * ### Case
 *
 * Uppercase and mixed-case input is **accepted and normalised**, as for a pubkey and for `x` and
 * `ox`. §4.3's no-normalisation exception list has exactly two entries — the BOLT-11 invoice
 * string and the Lightning preimage — and states that it is exhaustive; an event id is named
 * explicitly among the values that follow the general accept-and-normalise rule.
 *
 * ### The length is checked in `init`, not only in the factory
 *
 * A Kotlin class with a `private constructor` and a factory on its companion still emits a
 * JVM-public **synthetic** constructor carrying a trailing `DefaultConstructorMarker`, which a
 * Java client reaches as `new EventId(bytes, null)`. So "the factory is the only way in" is a
 * property of today's Kotlin call sites and not of this type. The invariant lives in `init`,
 * which every construction path runs, and the array is copied on the way in as well as on the
 * way out. Same shape and same reason as `DeliverableHash`.
 *
 * Pure computation: no clock, no randomness, no I/O.
 */
public class EventId private constructor(value: ByteArray) {

    private val value: ByteArray

    init {
        if (value.size != BYTE_LENGTH) {
            throw WireException(
                WireRejection.WRONG_LENGTH,
                "an event id is exactly $BYTE_LENGTH bytes; this one is ${value.size}",
            )
        }
        this.value = value.copyOf()
    }

    /** A fresh copy of the 32 bytes. Copied, so a caller cannot mutate this id. */
    public fun bytes(): ByteArray = value.copyOf()

    /** The canonical lowercase hex form (§4.3), whatever case it was read in. */
    public fun toHex(): String = encodeLowerHex(value)

    override fun equals(other: Any?): Boolean =
        other is EventId && MessageDigest.isEqual(value, other.value)

    override fun hashCode(): Int = value.contentHashCode()

    /**
     * Deliberately carries no hex.
     *
     * A public event's id is public, and for those this override costs a little convenience. A
     * **rumor's** id is not: §7.2's `kind:16` order messages are unsigned rumors sealed inside a
     * gift wrap, they never reach a relay, and their ids are correlation handles for a private
     * order between two parties — the same argument §10.5 makes for `ox` and §12 item 11 makes
     * for an order id. One type serves both, so it redacts, and [toHex] is there for the caller
     * who genuinely needs the value. A `toString` is never that caller.
     */
    override fun toString(): String = "EventId(redacted)"

    public companion object {

        /** 32 bytes — a SHA-256 digest. */
        public const val BYTE_LENGTH: Int = 32

        /** 64 characters — §4.3 fixes an event id at exactly that. */
        public const val HEX_LENGTH: Int = BYTE_LENGTH * 2

        /**
         * An event id read from [HEX_LENGTH] hex characters, in either case (§4.3), normalised
         * to lowercase.
         *
         * @throws WireException [WireRejection.WRONG_LENGTH] if the character count is not
         *   [HEX_LENGTH] — §4.3 requires rejection rather than padding or truncation — or
         *   [WireRejection.NOT_HEX] for a non-hex character.
         */
        public fun ofHex(hex: String): EventId = EventId(decodeThirtyTwoBytes(readEventIdHex(hex)))

        /**
         * §4.1: `SHA-256(UTF-8 bytes of the canonical serialisation)`, computed here with
         * `java.security.MessageDigest`.
         *
         * This is the write half of §17 item 1 and the recomputation half of its read rule. It
         * enforces §4.3's four bounds on the way past, because it serialises to get the bytes.
         *
         * @throws WireException naming whichever bound [WireEvent.canonicalSerialisation]
         *   refused.
         */
        public fun of(event: WireEvent, limits: WireLimits = WireLimits.DEFAULT): EventId {
            val serialisation = event.canonicalSerialisation(limits)
            return EventId(
                MessageDigest.getInstance("SHA-256").digest(serialisation.toByteArray(Charsets.UTF_8)),
            )
        }

        private fun decodeThirtyTwoBytes(lowercaseHex: String): ByteArray {
            val bytes = ByteArray(BYTE_LENGTH)
            for (index in 0 until BYTE_LENGTH) {
                bytes[index] =
                    ((nibble(lowercaseHex[2 * index]) shl 4) or nibble(lowercaseHex[2 * index + 1]))
                        .toByte()
            }
            return bytes
        }

        /** Only ever reached with a value [readEventIdHex] has already normalised and checked. */
        private fun nibble(character: Char): Int = when (character) {
            in '0'..'9' -> character - '0'
            else -> character - 'a' + 10
        }
    }
}

/**
 * An event whose claimed `id` this library recomputed for itself and found to match (§4.1).
 *
 * ### This type is §4.1's ordering, enforced by the compiler
 *
 * §4.1 says an implementation MUST reject a received event whose `id` does not match its
 * recomputed value **before any other processing**. An ordering rule is only as strong as the
 * narrowest door: so the later steps take a type only the earlier step can produce, and the tag
 * codec, the listing codec and the bid codec decode from a [CheckedEvent] rather than from a
 * bare [WireEvent]. There is no path to "process this event" that does not pass the id check,
 * and no comment anybody has to remember to read. Same construction as `ServedBytesVerified` in
 * the delivery package, for the same reason and one layer earlier.
 *
 * ### What it does and does not claim
 *
 * It claims exactly one thing: these five fields serialise, per §4.1, to bytes whose SHA-256 is
 * this id — an integrity check, and the cheapest complete one available. It claims **nothing**
 * about the signature. No signature is checked, produced or claimed anywhere in this library:
 * `Secp256k1Ops` answers `Unavailable`, and §17 permits an implementation to omit BIP-340
 * verification provided it does not report unverified things as verified.
 *
 * ### Unforgeable through the published API
 *
 * Same shape and same reason as `VerifiedPayment` and `DeliveryEvidence`: a public `sealed
 * interface` whose single implementation is a `private` nested class. An interface has no
 * constructor to synthesise an accessor for, so `Class.getConstructors()` on it is empty by
 * construction and stays empty; a `private constructor` plus a companion factory would emit a
 * public synthetic constructor a Java client can call with a `null` marker, and a `data class`
 * with a private primary constructor is still reachable through the generated `copy()`.
 *
 * The claim is "unforgeable **through the published API**", not unforgeable full stop: a
 * `private` nested class compiles to a package-private JVM class with a public constructor, so a
 * client that declares itself into `dev.eryalabs.nenya.wire` on the same classloader can still
 * reach it. Nenya protects its user against counterparties and relays, not against the client
 * embedding it.
 */
public sealed interface CheckedEvent {

    /** The recomputed id, equal to the claimed one by construction. */
    public val id: EventId

    /** The event those five fields describe, unchanged. */
    public val event: WireEvent

    /**
     * The §4.3 bounds this check ran under — the value the caller passed, or [WireLimits.DEFAULT]
     * if it passed none.
     *
     * Published for the reason `ServedBytesVerified.maxServedBytes` is: a caller that raised
     * every bound to `Int.MAX_VALUE` has satisfied §4.3's letter while enforcing nothing, and a
     * consumer that must know which of the two happened reads the numbers rather than inferring
     * them from the existence of this value.
     */
    public val limits: WireLimits

    public companion object {

        /**
         * §4.1's rule: recompute the id from the event and compare it to what the relay claimed.
         *
         * Three refusals, and nothing is returned for any of them — which is the enforcement.
         * §4.1 says the event MUST be rejected before any other processing, and a caller holding
         * no [CheckedEvent] has nothing this library will decode:
         *
         * - [claimedIdHex] is not 64 hex characters — [WireRejection.WRONG_LENGTH], never padded
         *   (§4.3) — or is not hex at all ([WireRejection.NOT_HEX]);
         * - a §4.3 bound is exceeded — see [WireEvent.canonicalSerialisation];
         * - the recomputation disagrees with the claim — [WireRejection.ID_MISMATCH].
         *
         * An uppercase claim is **accepted and normalised** before comparison, because §4.3's
         * two-item no-normalisation list is exhaustive and an event id is not on it. Refusing a
         * conformant-but-permissive peer here would be a bug, not strictness.
         */
        public fun checkEventId(
            claimedIdHex: String,
            event: WireEvent,
            limits: WireLimits = WireLimits.DEFAULT,
        ): CheckedEvent {
            val claimed = EventId.ofHex(claimedIdHex)
            val recomputed = EventId.of(event, limits)
            if (claimed != recomputed) {
                throw WireException(
                    WireRejection.ID_MISMATCH,
                    "the claimed id is not the SHA-256 of this event's canonical serialisation " +
                        "(§4.1); the event MUST be rejected before any other processing",
                )
            }
            return IdChecked(recomputed, event, limits)
        }

        /** The single implementation. Private, so the only door in is [checkEventId]. */
        private class IdChecked(
            override val id: EventId,
            override val event: WireEvent,
            override val limits: WireLimits,
        ) : CheckedEvent {

            /** Names neither the id nor anything [WireEvent.toString] declines to name. */
            override fun toString(): String = "CheckedEvent(event=$event, limits=$limits)"
        }
    }
}
