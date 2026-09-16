package dev.eryalabs.nenya.settlement

/**
 * The `<medium>` of a `["payment", "<medium>", …]` tag — Lightning, §9.4's two other rails, and the
 * sink §9.4's "MAY be parsed" requires for anything else.
 *
 * ### The sink is the only member of the vocabulary this package invented
 *
 * §9.4 recognises `bitcoin` and `ecash` as GammaMarkets vocabulary and says NENYA-1 v1 "defines no
 * verification rule for either, so neither constitutes evidence and neither may advance state. An
 * implementation encountering one MUST treat the payment as unverified." A medium token this
 * library has never heard of is in exactly that position and must get exactly that answer, so
 * [UNKNOWN] exists for the same reason `OrderMessageKind.UNKNOWN`, `ListingStatus.UNKNOWN` and
 * `OrderState.UNKNOWN` do — and is modelled the same way. It carries a `null` [token], so nothing
 * in this package can emit one: a sentinel string would put an invented medium within reach of
 * anything that writes a `payment` tag.
 *
 * Pure values: no clock, no randomness, no I/O.
 */
public enum class PaymentMedium(

    /** The literal `<medium>` token, or `null` for [UNKNOWN], which has none and is not a wire value. */
    public val token: String?,
) {

    /** §8.6 and §9.2 — the only rail NENYA-1 v1 defines an evidence rule for. */
    LIGHTNING("lightning"),

    /** §9.4's `["payment", "bitcoin", "<address>", "<txid>"]`. No v1 verification rule. */
    BITCOIN("bitcoin"),

    /** §9.4's `["payment", "ecash", "<mint>", "<proof>"]`. No v1 verification rule; see `OPEN-4`. */
    ECASH("ecash"),

    /**
     * A medium token this implementation does not implement. §9.4's treatment applies unchanged:
     * parsed, and evidencing nothing.
     */
    UNKNOWN(token = null);

    /** Whether §9.2's evidence rule is defined for this rail at all. True for [LIGHTNING] alone. */
    public val hasVerificationRule: Boolean get() = this == LIGHTNING

    public companion object {

        /**
         * The medium [token] names, or [UNKNOWN] for one §9.4 does not list.
         *
         * Never the nearest known one. Matched byte for byte and not case-insensitively: §4.3's
         * accept-and-normalise rule is about *hex*, and a vocabulary token is not hex — §11.1 and
         * §5.2's status vocabularies are read the same way, and a `"Lightning"` read as
         * [LIGHTNING] would be this codec deciding what a token a stranger chose meant.
         */
        public fun of(token: String): PaymentMedium = entries.firstOrNull { it.token == token } ?: UNKNOWN
    }
}

/**
 * A BOLT-11 invoice string, held **verbatim**, that has passed this library's structural
 * recogniser.
 *
 * ### This is a recogniser and not a parser, and the gap is named rather than papered over
 *
 * §8.6 requires an implementation to "reject a `payment` tag whose reference is not a BOLT-11
 * invoice string", and §9.3 forbids a static Lightning address outright. [recognise] discharges
 * exactly that much and no more. It checks:
 *
 * - **case** — Appendix C: an invoice is bech32 and "all of it is lowercase; a mixed-case invoice
 *   is invalid". §4.3 names the BOLT-11 string as one of exactly **two** values where no
 *   normalisation of any kind is permitted, so an uppercase character is
 *   [SettlementRejection.INVOICE_UPPERCASE_NOT_PERMITTED] and is never folded;
 * - **not an address** — no `@` (an `lud16` such as `name@example.com`) and no `lnurl` prefix, both
 *   [SettlementRejection.INVOICE_STATIC_ADDRESS] by §9.3;
 * - **shape** — an `ln` + network-prefix human-readable part with Appendix C's OPTIONAL amount, the
 *   `1` separator, and a data part drawn from the bech32 character set and long enough to hold
 *   Appendix C's 7-character timestamp and 104-character signature.
 *
 * It does **not** verify the bech32 checksum. It slices no tagged field, and reads no amount, no
 * timestamp, no expiry and **no payment hash** — which is why `PaymentCheck.INVOICE_AMOUNT` and
 * `PaymentCheck.INVOICE_EXPIRY` stay exactly where they are, in the not-performed set, and why
 * `PaymentCheck.INVOICE_IDENTITY` stays there too: that constant carries the provenance of check
 * 3's payment hash as well as check 1's comparison, and nothing here supplies the first.
 *
 * Appendix C states the obligation this leaves open in its own words: an implementation that skips
 * the signature "MUST still parse past it correctly and MUST verify the bech32 checksum". This
 * recogniser verifies no checksum, so it accepts strings a full parser would reject. A caller may
 * conclude from a value of this type only that the reference **is not an address, is not an LNURL
 * and is not obviously something else** — never that it is a valid invoice. Closing that gap needs
 * a BOLT-11 parser with its own externally-authored vectors, which is a human decision rather than
 * a queued task; `loop/queue.md`'s "Blocked on human" section carries the drafted proposal.
 *
 * ### Verbatim, and that is the whole point
 *
 * §9.2 check 1 compares this string byte-identically and forbids "no normalisation, no case
 * folding, no re-encoding, no bech32 round-trip" by name. So [text] is the string that arrived, and
 * there is no normalising accessor beside it for a later caller to reach for by mistake.
 *
 * ### The check runs in `init`
 *
 * A Kotlin class with a `private constructor` and a factory on its companion still emits a
 * **JVM-public synthetic** constructor carrying a trailing `DefaultConstructorMarker`, which a Java
 * client reaches as `new Bolt11Reference(text, null)`. So "the factory is the only way in" is a
 * property of today's Kotlin call sites and not of this type, exactly as `PaymentHash` and
 * `Preimage` record. The recogniser therefore runs in `init`, which every construction path runs.
 *
 * Pure computation: no clock, no randomness, no I/O.
 */
public class Bolt11Reference private constructor(

    /**
     * The invoice string exactly as it arrived — the operand of §9.2 check 1's byte comparison, and
     * the value an embedding client hands to `Wallet.payInvoice`.
     *
     * The one way the string gets out, and therefore the one place a leak can start. That is
     * deliberate, in the shape `OrderId.toHex` uses: §12 items 1 and 2 keep an invoice out of a
     * public event and out of anything a host application logs, and a caller that writes one
     * somewhere had to ask for it by name. [toString] carries none of it.
     */
    public val text: String,
) {

    init {
        recogniseShape(text)
    }

    override fun equals(other: Any?): Boolean = other is Bolt11Reference && other.text == text

    override fun hashCode(): Int = text.hashCode()

    /**
     * Deliberately carries no invoice.
     *
     * §12 item 1 keeps an invoice out of any public event and §12 item 11 and STOP RULE 14 forbid
     * an order-scoped identifier in "the string representation of anything the implementation
     * exposes". A BOLT-11 string is a per-order correlator visible to the payer's node, the payee's
     * node and every routing hop, so printing it into a host application's log links an order to a
     * Lightning payment for free. [text] is there for the caller that genuinely needs the value; a
     * `toString` is never that caller.
     */
    override fun toString(): String = "Bolt11Reference(redacted)"

    public companion object {

        /**
         * The bech32 character set, in BIP-173's own order.
         *
         * It carries no `1`, `b`, `i` or `o`, and the missing `1` is load-bearing rather than
         * trivia: it is what makes "split at the **last** `1`" correct, because the separator is
         * then the only `1` that can appear after the human-readable part — while the HRP itself
         * may legitimately contain one, inside Appendix C's optional amount (`lnbc1500n1…`).
         */
        public const val BECH32_ALPHABET: String = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

        /** Appendix C: the first 35 bits of the data part, which is seven bech32 characters. */
        private const val TIMESTAMP_CHARACTERS: Int = 7

        /** Appendix C: "the final 104 characters (512-bit signature + 1 recovery byte)". */
        private const val SIGNATURE_CHARACTERS: Int = 104

        /**
         * 111 — Appendix C's 7-character timestamp plus its 104-character signature.
         *
         * A floor on the data part and not a length: a real invoice carries tagged fields between
         * the two, so this is the shortest data part that could hold the two values Appendix C
         * says are always present. Below it there is nothing to parse past, whatever a checksum
         * might say.
         */
        public const val MIN_DATA_CHARACTERS: Int = TIMESTAMP_CHARACTERS + SIGNATURE_CHARACTERS

        /**
         * A reference recognised as BOLT-11-shaped, held verbatim.
         *
         * @throws SettlementException [SettlementRejection.INVOICE_UPPERCASE_NOT_PERMITTED],
         *   [SettlementRejection.INVOICE_STATIC_ADDRESS] or
         *   [SettlementRejection.INVOICE_MALFORMED], each naming which rule refused it. See this
         *   class's note on what "recognised" does and does not mean.
         */
        public fun recognise(reference: String): Bolt11Reference = Bolt11Reference(reference)

        /** Appendix C's human-readable part: `ln`, a network prefix, and an OPTIONAL amount. */
        private val HUMAN_READABLE_PART = Regex("""ln[a-z]+([0-9]+[munp]?)?""")

        /** Appendix C's separator, and BIP-173's: the last `1` in the string. */
        private const val SEPARATOR: Char = '1'

        /** §9.3's `lud16` form, which is an email-shaped address and never an invoice. */
        private const val ADDRESS_MARKER: Char = '@'

        /** §9.3's LNURL form, whose own human-readable part would otherwise satisfy `ln[a-z]+`. */
        private const val LNURL_PREFIX: String = "lnurl"

        /**
         * §8.6's and §9.3's rules over one string, in the order that makes each refusal name the
         * rule a reader has to act on.
         *
         * Case first, because Appendix C makes it a property of the whole string and because §4.3
         * requires it be **rejected** rather than normalised: an implementation that lowercased
         * here and then recognised the result would report a different, later reason for a defect
         * whose fix is in the sender's encoder.
         *
         * Then the two address forms, because [LNURL_PREFIX] would otherwise satisfy the
         * human-readable part below — `lnurl` is `ln` followed by letters — and be accepted as an
         * invoice, which is precisely the substitution §9.3 forbids.
         */
        private fun recogniseShape(reference: String) {
            for (character in reference) {
                if (character in 'A'..'Z') {
                    throw SettlementException(
                        SettlementRejection.INVOICE_UPPERCASE_NOT_PERMITTED,
                        SettlementVocabulary.PAYMENT,
                        "Appendix C fixes a BOLT-11 invoice as all-lowercase bech32 and §4.3 names " +
                            "it as one of exactly two values where an uppercase or mixed-case form " +
                            "is rejected outright rather than normalised",
                    )
                }
            }
            if (ADDRESS_MARKER in reference || reference.startsWith(LNURL_PREFIX)) {
                throw SettlementException(
                    SettlementRejection.INVOICE_STATIC_ADDRESS,
                    SettlementVocabulary.PAYMENT,
                    "§9.3: static Lightning addresses (LNURL / `lud16`) MUST NOT be used for order " +
                        "settlement — a reused address links every order the user has ever settled, " +
                        "and an LNURL fetch discloses the payer's IP to the recipient's server; " +
                        "§8.6 requires this reference be a BOLT-11 invoice string",
                )
            }
            val separator = reference.lastIndexOf(SEPARATOR)
            if (separator < 0) {
                throw malformed(
                    "Appendix C encodes an invoice as bech32 — a human-readable part, the `$SEPARATOR` " +
                        "separator, then a data part — and this reference carries no separator at all",
                )
            }
            val hrp = reference.substring(0, separator)
            if (!HUMAN_READABLE_PART.matches(hrp)) {
                throw malformed(
                    "Appendix C fixes the human-readable part as `ln` + a network prefix + an " +
                        "OPTIONAL decimal amount and multiplier; this reference's is not one",
                )
            }
            val data = reference.substring(separator + 1)
            if (data.length < MIN_DATA_CHARACTERS) {
                throw malformed(
                    "Appendix C puts a 7-character timestamp and a 104-character signature in every " +
                        "invoice's data part, so a data part shorter than $MIN_DATA_CHARACTERS " +
                        "characters cannot be parsed past; this one is ${data.length}",
                )
            }
            for (character in data) {
                if (character !in BECH32_ALPHABET) {
                    throw malformed(
                        "a bech32 data part is drawn from a 32-character alphabet that carries no " +
                            "`1`, `b`, `i` or `o`; this reference's carries something outside it",
                    )
                }
            }
        }

        /** The message never names a byte of the reference (§12 items 1 and 11). */
        private fun malformed(detail: String): SettlementException = SettlementException(
            SettlementRejection.INVOICE_MALFORMED,
            SettlementVocabulary.PAYMENT,
            "$detail. This library recognises the shape of a BOLT-11 string and does not parse " +
                "one: it verifies no bech32 checksum, so it accepts references a full parser would " +
                "reject, and refusing one here is therefore a strong answer and accepting one is a " +
                "weak one",
        )
    }
}
