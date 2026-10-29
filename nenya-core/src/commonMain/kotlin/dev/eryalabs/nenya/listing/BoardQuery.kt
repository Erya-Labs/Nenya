package dev.eryalabs.nenya.listing

import dev.eryalabs.nenya.collections.readOnlySetOf

/**
 * §5.5's client-side re-filter: the conditions a board view asked a relay for, conjoined again over
 * what came back.
 *
 * ### Why a relay's answer is not the answer
 *
 * §5.5: "Multiple values in one tag filter are OR, not AND. `{"#t": ["nenya", "request"]}` returns
 * events tagged *either* `nenya` *or* `request`. Implementations MUST NOT rely on multi-value tag
 * filters to conjoin conditions; they MUST re-filter client-side after receipt." So a client that
 * asked for requests tagged `nenya` receives every offer tagged `nenya` too, and only a check run
 * here, after receipt, says which of them it asked for. [matches] is that check. It emits no relay
 * filter: building the subscription is the client's, and §5.5's examples already show the shapes
 * that work.
 *
 * ### Every condition holds, or the listing does not match
 *
 * - [kinds]: the listing's kind is one of them. An empty set matches nothing — a query for no kind
 *   is a query for nothing, never a wildcard.
 * - [topics]: **every** one is among the listing's `t` tokens, compared case-insensitively (§5.3:
 *   implementations SHOULD match case-insensitively on read, because relay tag indexes are
 *   byte-exact). An empty set adds no condition.
 * - [mimeType]: when given, equal to the listing's `m` byte for byte. A listing with no `m` never
 *   matches a query that names one.
 * - [directedTo]: when given, among the listing's `p` pubkeys. A pubkey is 64 lowercase hex
 *   characters (§4.3) and `PubkeyRef` lowers `A`–`F` on read, so the query's own `A`–`F` are lowered
 *   the same way and nothing else is: a query naming something that is not a pubkey matches nothing
 *   rather than throwing.
 *
 * ### What it does not consult
 *
 * Neither [Listing.status] nor [Listing.activity]. Whether a listing is still open — its `status`
 * (§5.1, §5.2) or its `expiration` against the injected clock (§5.6) — is §5.6's question, not
 * §5.5's, and `DeadlineGate` answers the deadline half of it. A listing this query matches may be
 * sold or expired.
 *
 * Pure computation: no clock, no I/O, and nothing here throws.
 */
public class BoardQuery(
    kinds: Set<Int>,
    topics: Set<String> = emptySet(),

    /** §5.3's `m`, compared byte for byte, or `null` for no condition. */
    public val mimeType: String? = null,

    /** A pubkey that must be among the listing's `p` tags (§5.3's directed listing), or `null`. */
    public val directedTo: String? = null,
) {

    /** The listing kinds the query asks for. Empty matches nothing. */
    public val kinds: Set<Int> = readOnlySetOf(kinds)

    /** The `t` tokens a listing must **all** carry, as the caller gave them. Empty adds no condition. */
    public val topics: Set<String> = readOnlySetOf(topics)

    /** [topics] in the form `TagSet.normalisedTopics` compares against. */
    private val normalisedTopics: Set<String> = topics.map { it.lowercase() }.toSet()

    /** [directedTo] with `A`–`F` lowered as `PubkeyRef` lowers them, and nothing else touched. */
    private val directedToHex: String? = directedTo?.let { pubkey ->
        buildString(pubkey.length) {
            for (character in pubkey) append(if (character in 'A'..'F') character + ('a' - 'A') else character)
        }
    }

    /** True exactly when [listing] satisfies **every** condition this query names. */
    public fun matches(listing: Listing): Boolean {
        if (listing.kind !in kinds) return false
        if (!listing.tags.normalisedTopics.containsAll(normalisedTopics)) return false
        if (mimeType != null && listing.mimeType != mimeType) return false
        if (directedToHex != null && listing.pubkeyRefs.none { it.pubkey == directedToHex }) return false
        return true
    }

    /** Names the counts only: a pubkey a client is filtering for is §12 item 11 material. */
    override fun toString(): String =
        "BoardQuery(kinds=${kinds.size}, topics=${topics.size}, mimeType=${mimeType != null}, " +
            "directedTo=${directedTo != null})"
}
