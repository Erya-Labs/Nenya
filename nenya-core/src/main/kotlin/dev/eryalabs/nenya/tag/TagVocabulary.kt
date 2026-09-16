package dev.eryalabs.nenya.tag

/**
 * §5.3's **Card.** column, as its own tokens.
 *
 * The tokens are the specification's, character for character, including the two non-ASCII ones:
 * `0–1` carries U+2013 EN DASH and `≥2` carries U+2265 GREATER-THAN OR EQUAL TO. That is not
 * decoration — `TagVocabularyTest` asserts this vocabulary equals the table parsed out of §5.3 at
 * test time, so an ASCII `0-1` here would turn the suite red rather than quietly disagree with
 * the document.
 */
public enum class TagCardinality(

    /** The cell §5.3 prints, verbatim. */
    public val token: String,
) {

    /** `1` — exactly one, on a listing. */
    EXACTLY_ONE("1"),

    /** `0–1` — at most one, on a listing. */
    ZERO_OR_ONE("0–1"),

    /** `≥2` — at least two, on a listing. Only `t` carries it. */
    TWO_OR_MORE("≥2"),

    /** `0–n` — any number, on a listing. */
    ZERO_OR_MORE("0–n"),

    /** `0` — none. `item`, `g` and `location`; see [TagSpec.requirement] for the matching MUST NOT. */
    ZERO("0");

    /**
     * Whether §4.3's duplicate rule binds this cardinality: "For any tag this document marks with
     * cardinality `1` or `0–1`, an event carrying more than one occurrence MUST be **rejected**."
     */
    public val singleOccurrence: Boolean get() = this == EXACTLY_ONE || this == ZERO_OR_ONE
}

/** §5.3's **Requirement** column, as RFC-2119 tokens. */
public enum class TagRequirement(

    /** The cell §5.3 prints, verbatim. */
    public val token: String,
) {
    MUST("MUST"),
    SHOULD("SHOULD"),
    MAY("MAY"),
    MUST_NOT("MUST NOT"),
}

/** §5.3's **Side** column. `both` for every row but `item`, which is `neither`. */
public enum class TagSide(

    /** The cell §5.3 prints, verbatim. */
    public val token: String,
) {
    BOTH("both"),
    NEITHER("neither"),
}

/**
 * §5.3's Requirement for one tag, which is **not** a single token for every row.
 *
 * The `alt` row's cell is conditional prose — `MUST on requests, SHOULD on offers` — because
 * §5.2 makes `alt` REQUIRED on a request (`30404` is an unregistered kind, and every generic
 * client that meets one renders whatever `alt` says, or nothing at all) while §5.1 leaves it a
 * SHOULD on a NIP-99 offer. Modelling Requirement as a flat enum would force that row to be
 * rounded to one side or the other, and rounding it down loses a MUST.
 *
 * [cell] reproduces §5.3's text for either shape, which is what makes the set-equality proof in
 * `TagVocabularyTest` possible without special-casing the row.
 */
public class ListingRequirement internal constructor(

    /** What §5.3 requires on a `kind:30404` request (§5.2). */
    public val onRequests: TagRequirement,

    /** What §5.3 requires on a `kind:30402` or `kind:30403` offer (§5.1). */
    public val onOffers: TagRequirement,
) {

    /** §5.3's Requirement cell for this row, verbatim. */
    public val cell: String
        get() =
            if (onRequests == onOffers) onRequests.token
            else "${onRequests.token} on requests, ${onOffers.token} on offers"

    /** What this row requires on [context]'s listing kind. */
    public fun on(context: TagContext): TagRequirement =
        if (context.isRequest) onRequests else onOffers

    override fun equals(other: Any?): Boolean =
        other is ListingRequirement && other.onRequests == onRequests && other.onOffers == onOffers

    override fun hashCode(): Int = onRequests.hashCode() * 31 + onOffers.hashCode()

    override fun toString(): String = cell

    internal companion object {
        fun uniform(requirement: TagRequirement): ListingRequirement =
            ListingRequirement(requirement, requirement)
    }
}

/**
 * Which codec §5.3's **Encoding** column fixes for a tag.
 *
 * [NONE] is a case rather than a parse failure: the `g` and `location` rows carry an em dash in
 * that column, because §5.3 forbids emitting either tag at all and there is therefore nothing to
 * encode. A parser that treated `—` as a malformed cell would fail on the document it is reading.
 */
public enum class TagEncoding {

    /** `["<name>", "<text>"]` — plain text, no further structure. `title`, `summary`, `alt`… */
    TEXT,

    /** `["d", "<opaque>"]` — opaque to this codec and MUST be non-empty (§5.3). */
    OPAQUE,

    /** `["<name>", "<unix seconds>"]` — §4.3's timestamp rule. */
    TIMESTAMP,

    /** `["price", "<sats>", "SAT"]` — §4.4, strict on write and permissive on read. */
    PRICE,

    /** `["fee", "<bps>"]` or `["fee", "<bps>", "<recipient-pubkey-hex>"]` — §8.1's two arities. */
    FEE,

    /** `["t", "<token>"]` — lowercase on write, matched case-insensitively on read (§5.3). */
    TOPIC,

    /** `["image", "<url>", "<width>x<height>"]` — `https:` only (§5.3). */
    IMAGE,

    /** `["p", "<pubkey-hex>", "<relay-url>"]` — §4.3 hex, 64 characters. */
    PUBKEY_REF,

    /** `["item", "<coordinate>", "<quantity>"]` — §4.2's coordinate, quantity `"1"` (§5.3). */
    ITEM,

    /** `["nenya", "1"]` — the decimal major version of the document (§4.5). */
    VERSION,

    /** The em dash of the `g` and `location` rows: nothing to encode, because nothing may be emitted. */
    NONE,
}

/**
 * One row of §5.3's table.
 *
 * The four columns this library acts on are held as data rather than as code: the reader consults
 * them, and `TagVocabularyTest` asserts the whole set equals the table parsed out of the
 * specification at test time. A row added to §5.3 and not here — or here and not there — turns
 * the suite red, which is the only way a transcription stays honest.
 */
public class TagSpec internal constructor(

    /** §5.3's Tag column: the tag name as it appears in position 0 of the array. */
    public val name: String,

    /** §5.3's Side column. */
    public val side: TagSide,

    /** §5.3's Card. column. A **listing** rule, except for §4.3's duplicate clause. */
    public val cardinality: TagCardinality,

    /** §5.3's Requirement column. A **listing** rule. */
    public val requirement: ListingRequirement,

    /** §5.3's Encoding column, which is **universal** across every kind (§5.3). */
    public val encoding: TagEncoding,
) {

    override fun toString(): String = "$name | ${side.token} | ${cardinality.token} | ${requirement.cell}"
}

/**
 * §5.3's tag vocabulary, whole.
 *
 * ### What is universal and what is not
 *
 * §5.3 draws the line itself and this object does nothing but hold it: [TagSpec.encoding] applies
 * wherever a tag appears, and [TagSpec.cardinality] and [TagSpec.requirement] are listing rules
 * that MUST NOT be applied to a bid or an order rumor. [TagSet.read] is where the split is acted
 * on; see its documentation for the one clause that crosses the line — §4.3's duplicate rule,
 * whose rationale is about signed events rather than about listings.
 *
 * ### Unknown tags
 *
 * A name not in this table is **not** an error. §4.3 requires unknown tags be ignored on read and
 * preserved verbatim on the way back out, because dropping one silently strips an extension and
 * changes the event id. [TagSet.republish] is that guarantee.
 *
 * Pure values: no clock, no randomness, no I/O.
 */
public object NenyaTags {

    /** §5.3's nineteen rows, in table order. */
    public val ALL: List<TagSpec> = listOf(
        spec("d", TagCardinality.EXACTLY_ONE, TagRequirement.MUST, TagEncoding.OPAQUE),
        spec("title", TagCardinality.EXACTLY_ONE, TagRequirement.MUST, TagEncoding.TEXT),
        spec("summary", TagCardinality.ZERO_OR_ONE, TagRequirement.SHOULD, TagEncoding.TEXT),
        spec("published_at", TagCardinality.ZERO_OR_ONE, TagRequirement.SHOULD, TagEncoding.TIMESTAMP),
        spec("price", TagCardinality.EXACTLY_ONE, TagRequirement.MUST, TagEncoding.PRICE),
        spec("status", TagCardinality.ZERO_OR_ONE, TagRequirement.SHOULD, TagEncoding.TEXT),
        spec("t", TagCardinality.TWO_OR_MORE, TagRequirement.MUST, TagEncoding.TOPIC),
        spec("m", TagCardinality.ZERO_OR_ONE, TagRequirement.SHOULD, TagEncoding.TEXT),
        spec("image", TagCardinality.ZERO_OR_MORE, TagRequirement.MAY, TagEncoding.IMAGE),
        spec("expiration", TagCardinality.ZERO_OR_ONE, TagRequirement.SHOULD, TagEncoding.TIMESTAMP),
        TagSpec(
            name = "alt",
            side = TagSide.BOTH,
            cardinality = TagCardinality.ZERO_OR_ONE,
            // The one conditional row. §5.2 makes `alt` REQUIRED on a request; §5.1 leaves it a
            // SHOULD on a NIP-99 offer. See ListingRequirement.
            requirement = ListingRequirement(TagRequirement.MUST, TagRequirement.SHOULD),
            encoding = TagEncoding.TEXT,
        ),
        spec("nenya", TagCardinality.EXACTLY_ONE, TagRequirement.MUST, TagEncoding.VERSION),
        spec("p", TagCardinality.ZERO_OR_MORE, TagRequirement.MAY, TagEncoding.PUBKEY_REF),
        spec("fee", TagCardinality.ZERO_OR_ONE, TagRequirement.MAY, TagEncoding.FEE),
        spec("license", TagCardinality.ZERO_OR_ONE, TagRequirement.MAY, TagEncoding.TEXT),
        spec("deliver_by", TagCardinality.ZERO_OR_ONE, TagRequirement.MAY, TagEncoding.TIMESTAMP),
        TagSpec(
            name = "item",
            // The only `neither` row: a listing cannot reference itself. §5.3 defines it here
            // anyway because it is the one tag §6.1 and §7.5 need that the listing vocabulary
            // does not otherwise supply.
            side = TagSide.NEITHER,
            cardinality = TagCardinality.ZERO,
            requirement = ListingRequirement.uniform(TagRequirement.MUST_NOT),
            encoding = TagEncoding.ITEM,
        ),
        forbidden("g"),
        forbidden("location"),
    )

    /** The nineteen names, for a quick membership test against §4.3's "unknown tag" rule. */
    public val NAMES: Set<String> = ALL.map { it.name }.toSet()

    private val byName: Map<String, TagSpec> = ALL.associateBy { it.name }

    init {
        // A duplicated row would make `byName` silently shorter than `ALL` and every by-name
        // lookup answer for the wrong row. Cheap, and it fires at class-load rather than in a test.
        check(byName.size == ALL.size) { "§5.3's vocabulary carries a duplicated tag name" }
    }

    /** The §5.3 row for [name], or `null` — which §4.3 calls an unknown tag, not an error. */
    public fun byName(name: String): TagSpec? = byName[name]

    private fun spec(
        name: String,
        cardinality: TagCardinality,
        requirement: TagRequirement,
        encoding: TagEncoding,
    ): TagSpec = TagSpec(name, TagSide.BOTH, cardinality, ListingRequirement.uniform(requirement), encoding)

    /** `g` and `location`: Card. `0`, MUST NOT, and an em dash where an encoding would be. */
    private fun forbidden(name: String): TagSpec =
        TagSpec(
            name = name,
            side = TagSide.BOTH,
            cardinality = TagCardinality.ZERO,
            requirement = ListingRequirement.uniform(TagRequirement.MUST_NOT),
            encoding = TagEncoding.NONE,
        )
}
