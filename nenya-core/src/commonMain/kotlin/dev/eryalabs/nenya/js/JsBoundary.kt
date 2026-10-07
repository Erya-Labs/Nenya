package dev.eryalabs.nenya.js

import dev.eryalabs.nenya.seam.SeamAnswer
import dev.eryalabs.nenya.wire.EventId
import dev.eryalabs.nenya.wire.EventJson
import dev.eryalabs.nenya.wire.JsonException
import dev.eryalabs.nenya.wire.WireEvent
import dev.eryalabs.nenya.wire.WireException
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

/**
 * The decimal-string codec every number that can exceed 2^53 crosses this boundary as.
 *
 * ### Why a string and not a number
 *
 * A JavaScript `number` is an IEEE-754 double and holds integers exactly only up to
 * 2^53 − 1 = 9 007 199 254 740 991. §4.4's supply cap is 2 100 000 000 000 000 000 millisatoshis,
 * which is **233 times** larger, and `Long.MAX_VALUE` — reachable by §8.3's `total_msat` arithmetic
 * and by a `created_at` a stranger chose — is larger still. Crossing either as a `number` silently
 * rounds: 2 100 000 000 000 000 000 survives by luck, because it happens to be a multiple of a
 * large power of two, while the supply cap minus one millisatoshi does not. A money bug that
 * depends on the low bits of the amount is the exact failure `Msat` exists to make impossible one
 * layer down, and a translation layer that reintroduced it at the edge would have undone that work.
 *
 * So every millisatoshi amount, every unix-second timestamp and every byte count crosses as an
 * exact decimal string, and **no `Double` appears anywhere in this package**. `CapabilitySurfaceTest`
 * already sweeps every published signature in this library for floating-point money and this
 * package joins that sweep by discovery, not by being listed; `JsSurfaceTest` adds the stronger
 * rule that the translation types are the only ones here at all.
 *
 * ### Canonical on write, strict on read
 *
 * [of] emits the shortest form — no leading zeros, no `+`, a leading `-` only for a negative — and
 * [toLongOrNull] accepts exactly that form and nothing else. The asymmetry §4.4 has between reading
 * a price and writing one does not apply: both ends of this codec are code in this repository, so a
 * permissive reader would only hide a bug in the writer.
 */
internal object JsDecimal {

    /** [value] as an exact decimal string. No `Double`, no rounding, no formatting. */
    fun of(value: Long): String = value.toString()

    /** The same for a value that may be absent, so an optional field crosses as `null`. */
    fun ofOrNull(value: Long?): String? = value?.toString()

    /**
     * [text] read back as the `Long` [of] wrote, or `null` if it is not that exact form.
     *
     * `null` rather than an exception: a refusal is a value everywhere in this package. The caller
     * turns it into whichever named rejection the field it was reading belongs to, so the
     * diagnostic names a tag rather than this codec.
     */
    fun toLongOrNull(text: String): Long? {
        if (text.isEmpty()) return null
        val negative = text[0] == '-'
        val digits = if (negative) text.substring(1) else text
        if (digits.isEmpty()) return null
        // Deliberately not Char.isDigit(), which accepts Arabic-Indic digits that toLong() parses.
        for (c in digits) if (c !in '0'..'9') return null
        // Canonical only: "007" and "-0" are not what `of` writes, so they are not read either.
        if (digits.length > 1 && digits[0] == '0') return null
        if (negative && digits == "0") return null
        return text.toLongOrNull()
    }
}

/**
 * The hex codec a `ByteArray` crosses this boundary as, for the same reason as [JsDecimal].
 *
 * A `ByteArray` is not an exportable type and a JavaScript `Uint8Array` is not a `ByteArray`, so the
 * two seams that speak in bytes — `Randomness.randomBytes` and `Secp256k1Ops.verifySchnorr` — cross
 * as §4.3's own lowercase hex, which is the form every other 32-byte value in this library already
 * takes on the wire.
 *
 * Writing uses the library's own `encodeLowerHex`. Reading is here, and it is **strict**: an even
 * number of lowercase hex digits and nothing else. Uppercase is refused rather than normalised,
 * which is narrower than §4.3's read rule for an event id on purpose — nothing is being read off a
 * relay here, both ends are code, and a permissive reader would only hide a bug in the page.
 */
internal object JsHex {

    /** [text] as the bytes it spells, or `null` if it is not strict lowercase hex of even length. */
    fun toBytesOrNull(text: String): ByteArray? {
        if (text.length % 2 != 0) return null
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            val high = digit(text[i * 2]) ?: return null
            val low = digit(text[i * 2 + 1]) ?: return null
            out[i] = ((high shl 4) or low).toByte()
        }
        return out
    }

    /** One lowercase hex digit's value, or `null`. Deliberately rejects `A`–`F`. */
    private fun digit(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        else -> null
    }
}

/**
 * What a seam answered, as decision **P** fixes its shape at this boundary: `{ ok, value,
 * capability, detail }`.
 *
 * `SeamAnswer` is a sealed hierarchy of two classes with disjoint fields, and no sealed type
 * crosses. The four fields here are the union, and [ok] is the discriminator: a `Provided` is
 * `ok = true` with [value] set and the other two `null`, an `Unavailable` is `ok = false` with
 * [capability] and [detail] set and [value] `null`.
 *
 * **[ok] is not evidence of anything**, and that is `SeamAnswer`'s own note rather than a new
 * caveat: a `Provided` carries whatever the embedding client's implementation said, and §9.1 says a
 * wallet's claim of settlement is not evidence of payment. `ok = true` means the seam answered, not
 * that the answer is true.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsSeamAnswer internal constructor(

    /** True for a `SeamAnswer.Provided`, false for a `SeamAnswer.Unavailable`. */
    public val ok: Boolean,

    /** What the seam said, for an `ok` answer, already translated to a string. `null` otherwise. */
    public val value: String?,

    /** `SeamCapability.name` for an unavailable answer, `null` for an `ok` one. */
    public val capability: String?,

    /** The `Unavailable.detail` sentence, `null` for an `ok` one. */
    public val detail: String?,
) {

    /** Names no value: `SeamAnswer.Provided.toString` redacts, and so does this (§12 item 11). */
    override fun toString(): String =
        if (ok) "JsSeamAnswer(ok, redacted)" else "JsSeamAnswer(unavailable: $capability)"
}

/**
 * The one `SeamAnswer` translation, so no entry point writes a second.
 *
 * A top-level `internal` function and **not** a factory on [JsSeamAnswer]'s companion, which is the
 * difference between a Kotlin type crossing this boundary and not: the companion object of a
 * `@JsExport` class is itself exported, so an `internal fun of(answer: SeamAnswer<*>)` on it is an
 * exported declaration taking a sealed Kotlin type, which is exactly what decision **P** forbids.
 * The compiler says so — `NON_EXPORTABLE_TYPE` — but only as a warning, so every factory in this
 * package lives out here where it cannot be reached from JavaScript at all. `JsSurfaceTest` is what
 * holds the rule after the warning has been read and forgotten.
 */
internal fun jsSeamAnswer(answer: SeamAnswer<*>, value: (Any?) -> String?): JsSeamAnswer =
    when (answer) {
        is SeamAnswer.Provided -> JsSeamAnswer(true, value(answer.value), null, null)
        is SeamAnswer.Unavailable ->
            JsSeamAnswer(false, null, answer.capability.name, answer.detail)
    }

/**
 * An unsigned event this library built, as a plain object plus the Kotlin value it was built from.
 *
 * ### The Kotlin event is held, not crossed
 *
 * [event] is `internal`, so it is not exported and no JavaScript caller can reach it; what crosses
 * is [kind], [pubkey], [createdAtSeconds], [content] and [tags], all of them strings, ints and
 * arrays of strings. Holding it is what lets a sealing entry point take a built rumor directly,
 * without this package re-reading its own output — the alternative is a second parse, and a second
 * parse is a second chance to disagree with the first. §7.1's sealing crossing is a separate task;
 * this field is the door it will come through.
 *
 * ### Nothing here is signed and nothing here carries an id
 *
 * The same division every builder in this library records: signing is the embedding app's, and
 * §4.1's `id` is recomputed from the five fields rather than carried. [unsignedJson] is where the id
 * is computed, and it is the step that can still refuse — §4.3's four event-level bounds are
 * measured in the canonical serialisation, which is behind the id — so it answers a [JsText] rather
 * than a `String`.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsWireEvent internal constructor(

    /** The event this object describes. Not exported: see this class's note. */
    internal val event: WireEvent,
) {

    /** §4.1's `kind`. */
    public val kind: Int get() = event.kind

    /** §4.1's `pubkey`, 64 lowercase hex characters. */
    public val pubkey: String get() = event.pubkey

    /** §4.1's `created_at`, unix seconds as a decimal string (see [JsDecimal]). */
    public val createdAtSeconds: String get() = JsDecimal.of(event.createdAt)

    /** §4.1's `content`. */
    public val content: String get() = event.content

    /** §4.1's `tags`, in the emission order the builder chose. A fresh array on every read. */
    public val tags: Array<Array<String>>
        get() = Array(event.tags.size) { row -> event.tags[row].toTypedArray() }

    /**
     * §4.1's object form with the recomputed `id` and no `sig`, ready for the app to sign.
     *
     * Refuses rather than throws when §4.3's bounds or §4.5's kind range turn it down — the same
     * `WireException` and `JsonException` the JVM side gets from `EventId.of` and
     * `EventJson.writeUnsigned`, reported as a value.
     */
    public fun unsignedJson(): JsText = jsText {
        EventJson.writeUnsigned(event, EventId.of(event))
    }

    /** Names the kind and a tag count. §12 item 11 keeps the rest out of a log. */
    override fun toString(): String = "JsWireEvent(kind=$kind, tags=${event.tags.size})"
}

/**
 * A string answer that may instead be a refusal, since decision **P** says refusals are returned
 * and never thrown.
 *
 * [kind] is `"ok"` or `"refused"`, and it is the discriminator every result object in this package
 * carries.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsText internal constructor(

    /** `"ok"` or `"refused"`. */
    public val kind: String,

    /** True exactly when [kind] is `"ok"`. */
    public val ok: Boolean,

    /** The answer, when [ok]. */
    public val value: String?,

    /** The rejection constant's `name`, when not [ok]. */
    public val reason: String?,

    /** Which rejection enum [reason] came from, so a caller can tell two vocabularies apart. */
    public val reasonVocabulary: String?,

    /** Why, in words, authored in this library and never echoing an input (§12 item 11). */
    public val detail: String?,
) {

    override fun toString(): String = if (ok) "JsText(ok)" else "JsText(refused: $reason)"
}

/** An `ok` [JsText]. */
internal fun jsTextOk(value: String): JsText = JsText("ok", true, value, null, null, null)

/**
 * [compute]'s answer, or the refusal it threw, translated.
 *
 * Catches exactly the two exception types the event layer raises. Deliberately **not** `Throwable`:
 * swallowing an unexpected failure is how a translation layer comes to report a bug in this library
 * as a conformance refusal, and STOP RULE 1 is about the same instinct one level up.
 */
internal fun jsText(compute: () -> String): JsText = try {
    jsTextOk(compute())
} catch (refused: WireException) {
    JsText("refused", false, null, refused.reason.name, "WireRejection", refused.message)
} catch (refused: JsonException) {
    JsText("refused", false, null, refused.reason.name, "JsonRejection", refused.message)
}

/**
 * What a builder answered: the unsigned event, or the refusal, as one plain object with a [kind].
 *
 * Every builder T40 and T41 added returns a sealed `Built`/`Refused` pair, and no sealed type
 * crosses this boundary. The fields below are the union of all three of those pairs
 * (`ListingBuild`, `RumorBuild`, `PaymentBuild`), and nothing is flattened away: a listing refusal
 * keeps its §5.3 `tag` and its tag-layer [tagReason], and a payment refusal keeps which of the two
 * vocabularies refused it on [reasonVocabulary], because `PaymentBuild.Refused` publishes exactly
 * one of a `SettlementRejection` and a `ChannelRejection` and which one says which layer said no.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsBuildResult internal constructor(

    /** `"built"` or `"refused"`. */
    public val kind: String,

    /** True exactly when [kind] is `"built"`. */
    public val ok: Boolean,

    /** The unsigned event, when [ok]. */
    public val event: JsWireEvent?,

    /** The rejection constant's `name`, when not [ok]. */
    public val reason: String?,

    /** Which rejection enum [reason] came from — `ListingRejection`, `ChannelRejection`, … */
    public val reasonVocabulary: String?,

    /** The tag the refusal is about, or `null` where the rule names no single tag. */
    public val tag: String?,

    /** The tag layer's own `TagRejection.name`, where a tag codec refused the value. */
    public val tagReason: String?,

    /** Why, in words. Never echoes an input (§12 item 11, STOP RULE 14). */
    public val detail: String?,
) {

    override fun toString(): String =
        if (ok) "JsBuildResult(built, kind=${event?.kind})" else "JsBuildResult(refused: $reason)"
}

/** A `built` [JsBuildResult] over the unsigned event a builder produced. */
internal fun jsBuilt(event: WireEvent): JsBuildResult =
    JsBuildResult("built", true, JsWireEvent(event), null, null, null, null, null)

/** A `refused` [JsBuildResult]. */
internal fun jsRefused(
    reason: String?,
    reasonVocabulary: String?,
    tag: String? = null,
    tagReason: String? = null,
    detail: String? = null,
): JsBuildResult =
    JsBuildResult("refused", false, null, reason, reasonVocabulary, tag, tagReason, detail)

/**
 * The one vocabulary this boundary owns, and the reason it is told apart from every other.
 *
 * ### What it is, and what it deliberately is not
 *
 * Decision **P** says the layer "adds no rule and relaxes none", and T42 says the facade performs
 * no check of its own. This exception is not a check on a *value*: it is the crossing reporting that
 * what it was handed cannot be turned into the Kotlin value at all. `"12x"` is not a decimal string,
 * so there is no millisatoshi amount to hand `Msat.ofMsat` and no `created_at` to hand
 * `AuthoredListing`. The only alternatives are to guess a number or to throw, and decision **P**
 * forbids throwing.
 *
 * So it is reported with [reasonVocabulary] `"JsCrossing"` — a name no section of NENYA-1 uses and
 * no other result in this library can produce — precisely so a caller can never read it as a
 * protocol refusal. Every answer about §4, §5, §6, §7, §8, §9 or §11 comes from the library call,
 * and `JsSurfaceTest` pins the set of reasons this vocabulary can ever carry so a later entry point
 * cannot quietly grow it into a second rulebook.
 *
 * The companion rule is that a *well-formed* crossing never produces one. Hand this boundary the
 * decimal string [JsDecimal.of] wrote and the only answers left are the library's.
 */
internal class JsCrossing(

    /**
     * One of [MALFORMED_DECIMAL], [WRONG_ROW_ARITY] or [UNKNOWN_PAYEE_TOKEN] — [REASONS] is the whole
     * vocabulary, and `JsSurfaceTest` holds this field's possible values equal to that set.
     */
    val reason: String,

    /** The field or tag row the crossing was reading, so the diagnostic names something. */
    val field: String,

    /** Why, authored here. Never echoes the input (§12 item 11, STOP RULE 14). */
    val why: String,
) : RuntimeException(why) {

    internal companion object {

        /** The text was not the exact decimal form [JsDecimal.of] writes. */
        const val MALFORMED_DECIMAL: String = "MALFORMED_DECIMAL"

        /** A tag row crossed with a number of elements §5.3 or §7.4 gives that row no form for. */
        const val WRONG_ROW_ARITY: String = "WRONG_ROW_ARITY"

        /** §8.6's `payee` crossed as a token that is neither `provider` nor `fee`. */
        const val UNKNOWN_PAYEE_TOKEN: String = "UNKNOWN_PAYEE_TOKEN"

        /** The vocabulary name every one of these refusals reports. */
        const val VOCABULARY: String = "JsCrossing"

        /**
         * Every reason this vocabulary can ever carry, pinned by exact set equality in
         * `JsSurfaceTest`.
         *
         * A new entry turns that test red and asks a human, which is the point: this boundary owns
         * three refusals about the *form* of a crossing, and a fourth appearing without anybody
         * noticing is how a translation layer grows into a second rulebook — the divergence
         * STOP RULE 5 exists to prevent.
         */
        val REASONS: Set<String> = setOf(MALFORMED_DECIMAL, WRONG_ROW_ARITY, UNKNOWN_PAYEE_TOKEN)
    }
}

/** A required decimal field, read as the `Long` it names. */
internal fun crossedNumber(field: String, text: String): Long =
    JsDecimal.toLongOrNull(text) ?: throw JsCrossing(
        JsCrossing.MALFORMED_DECIMAL,
        field,
        "this boundary carries a number as an exact decimal string — ASCII digits, an optional " +
            "leading `-`, no `+`, no leading zeros, no exponent and no decimal point — because a " +
            "JavaScript number holds neither §4.4's supply cap nor a 64-bit timestamp exactly; " +
            "the value crossed for `$field` is not that form",
    )

/** The same for a field that may be absent, where absent crosses as `null`. */
internal fun crossedNumberOrNull(field: String, text: String?): Long? =
    if (text == null) null else crossedNumber(field, text)

/** A millisatoshi amount. Named apart from [crossedNumber] so the diagnostic says which it was. */
internal fun crossedAmount(field: String, text: String): Long = crossedNumber(field, text)

/** A unix-second timestamp. */
internal fun crossedSeconds(field: String, text: String): Long = crossedNumber(field, text)

/** A unix-second timestamp that may be absent. */
internal fun crossedSecondsOrNull(field: String, text: String?): Long? = crossedNumberOrNull(field, text)

/**
 * A builder call, with every refusal either builder or boundary can produce returned as a value.
 *
 * The catch list is exhaustive over the types the translation and the builders actually raise, and
 * it is a **list of named types rather than `Throwable`** on purpose: a translation layer that
 * caught everything would report a defect in this library as a conformance refusal, and the whole
 * point of the equality proof is that it cannot.
 *
 * **Exhaustiveness is proved rather than reasoned about.** `JsRefusalByValueTest` feeds every entry
 * point a hostile crossing of every shape and asserts a value comes back, because a missing arm is
 * invisible until the input that reaches it arrives: two were missing when T42's proof was first
 * run — `ListingException`, raised by §5.1's closed `status` vocabulary before any writer is called,
 * and `OrderStateException`, raised by §7.5's inverted-deadline rule inside `OrderTerms.of` — and
 * both escaped the boundary as thrown exceptions, which is what decision **P** forbids.
 */
internal inline fun jsBuild(compute: () -> JsBuildResult): JsBuildResult = try {
    compute()
} catch (refused: JsCrossing) {
    jsRefused(refused.reason, JsCrossing.VOCABULARY, refused.field, null, refused.why)
} catch (refused: dev.eryalabs.nenya.listing.ListingException) {
    // §5.1's and §5.2's rules that run during the translation rather than inside the writer:
    // `ListingStatusCodec.read` refuses one of §5.2's request tokens on an offer by name.
    jsRefused(
        refused.reason.name,
        "ListingRejection",
        refused.tag,
        (refused.cause as? dev.eryalabs.nenya.tag.TagException)?.reason?.name,
        refused.message,
    )
} catch (refused: dev.eryalabs.nenya.order.OrderStateException) {
    // §7.5's two deadline rules, enforced by `OrderTerms`'s own `init` rather than by a writer.
    jsRefused(refused.reason.name, "OrderStateRejection", null, null, refused.message)
} catch (refused: dev.eryalabs.nenya.tag.TagException) {
    jsRefused(refused.reason.name, "TagRejection", null, refused.reason.name, refused.message)
} catch (refused: dev.eryalabs.nenya.money.MoneyException) {
    jsRefused(refused.reason.name, "MoneyRejection", null, null, refused.message)
} catch (refused: dev.eryalabs.nenya.seam.SeamException) {
    // `OrderId.ofHex` on §7.4's order id.
    jsRefused(refused.reason.name, "SeamRejection", null, null, refused.message)
} catch (refused: dev.eryalabs.nenya.delivery.DeliveryException) {
    // `DeliverableHash.ofHex` on §10.1's and §10.3's `x` and `ox`.
    jsRefused(refused.reason.name, "DeliveryRejection", null, null, refused.message)
} catch (refused: dev.eryalabs.nenya.channel.ChannelException) {
    jsRefused(refused.reason.name, "ChannelRejection", refused.tag, null, refused.message)
} catch (refused: dev.eryalabs.nenya.settlement.SettlementException) {
    jsRefused(refused.reason.name, "SettlementRejection", refused.tag, null, refused.message)
} catch (refused: WireException) {
    jsRefused(refused.reason.name, "WireRejection", null, null, refused.message)
} catch (refused: JsonException) {
    jsRefused(refused.reason.name, "JsonRejection", null, null, refused.message)
}

/**
 * The tag rows a caller hands in, translated to the `List<List<String>>` this library's builders
 * take.
 *
 * `Array<Array<String>>` crosses and `List<List<String>>` does not, which is the whole of the
 * difference. A row is copied rather than wrapped: a JavaScript array the caller still holds a
 * reference to is mutable, and a builder that read it twice could see two different events.
 */
internal fun Array<out Array<String>>.asTagRows(): List<List<String>> = map { it.toList() }

/** The reverse, for a value this library produced. */
internal fun List<List<String>>.asTagArray(): Array<Array<String>> =
    Array(size) { row -> this[row].toTypedArray() }
