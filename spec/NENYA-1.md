NENYA-1
=======

Nenya Marketplace Microstandard
-------------------------------

`draft` `optional`

A microstandard for a non-custodial marketplace on nostr in which one party posts a REQUEST for
digital media or an OFFER to produce it, and the parties settle directly in bitcoin. Nenya is a
library, not an application or a service; `nenya-core` is one reference implementation of it.

---

## 0. Status of this document

This is NENYA-1, version `1`, **revision `1.12`** of the Nenya wire format. It is a draft:
nothing in it is deployed, and its reserved identifiers are not registered.

A short non-normative summary is [the overview](NENYA-1-overview.md). The revision history is in
§0 of [the companion](NENYA-1-companion.md). Undecided items are marked `OPEN-n`; §16.2 indexes
them and states an implementation's duty toward them, and §16.1 lists closed ones.

Where this document and a referenced NIP disagree, the NIP governs the parts it defines, except
§4.1's canonical JSON escaping, which overrides NIP-01. Nenya never redefines an existing tag.

Normative references: [NIP-01][nip01], [NIP-09][nip09], [NIP-11][nip11], [NIP-17][nip17],
[NIP-19][nip19], [NIP-22][nip22], [NIP-31][nip31], [NIP-40][nip40], [NIP-44][nip44], [NIP-59][nip59],
[NIP-94][nip94], [NIP-99][nip99], [BIP-340][bip340], [BOLT-11][bolt11], [RFC 2119][rfc2119],
[RFC 8259][rfc8259] (§4.1 string escaping). Informative: [GammaMarkets market-spec][gamma] (order
and receipt vocabulary), [NIP-69][nip69]. §14 lists prior art not built on.

---

## 1. Motivation and scope

The motivation is in §1 of [the companion](NENYA-1-companion.md).

Nenya specifies only a public board of listings and bids (§5, §6), a private NIP-17 order channel
(§7), a signed fee term (§8), a payment-evidence rule (§9) and a deliverable hash commitment (§10).

![One trade, message by message](../docs/trade-sequence.svg)

*Figure (non-normative): one order, from public listing to settled, lane by lane.*

Out of scope, permanently or for v1 (§14): custody, escrow, arbitration, indexing, hosting,
identity verification, content moderation policy, and any protocol role for this document's author.

---

## 2. Terminology and conventions

The key words "MUST", "MUST NOT", "REQUIRED", "SHALL", "SHALL NOT", "SHOULD", "SHOULD NOT",
"RECOMMENDED", "MAY", and "OPTIONAL" in this document are to be interpreted as described in
RFC 2119.

- **Board** — the public Nenya events (requests, offers, bids) on a set of relays.
- **Request** — a public, addressable `kind:30404` event stating what a buyer wants and will pay.
- **Offer** — a public NIP-99 `kind:30402` listing stating what a provider will make and its price.
- **Listing** — a request or an offer.
- **Bid** — a public NIP-22 `kind:1111` comment proposing terms on a listing (§6); a **private
  bid** carries the same terms as a §7 message.
- **Buyer** — the paying party: the author of a request or the responder to an offer.
- **Provider** — the delivering party: the author of an offer or the responder to a request.
- **Fee recipient** — an optional third party named in the terms and paid a handling fee; not a
  delivery party; for a non-zero fee, always in the order's sealed conversation (§7.3, §8.1, §8.7).
- **Order** — a private negotiation and settlement thread between exactly one buyer and one
  provider, identified by an *order id*.
- **Terms** — the mutually accepted tuple (item coordinate, price, fee bps, fee recipient, deadlines).
- **Evidence** — a value an implementation verifies **itself** by computation, trusting no supplier;
  a boolean, status string, or wallet, relay, signer or counterparty assertion is **not** evidence.
- **Coordinate** — an addressable event address, NIP-01 form `<kind>:<pubkey-hex>:<d-value>`.
- **Implementation** — the software implementing this document. **Client** — the application
  embedding it. §13 relies on the distinction.

---

## 3. Roles and the seams between them

An implementation MUST treat these five client-supplied interfaces as untrusted with respect to state:

| Seam | Supplies | What Nenya MUST NOT accept from it |
|---|---|---|
| Signer | public key, event signatures, NIP-44 encrypt/decrypt | any claim about *what* was signed, beyond the bytes returned |
| One-time signer | a signer over a **freshly generated** keypair, used for exactly one gift wrap | any claim that the keypair is fresh. Freshness cannot be observed from outside the seam — it is the client's contract to keep, and §17 forbids reporting it as checked. What an implementation does verify itself is narrower, and is exactly what §7.1 states: that the key equals neither party's key, and that it differs between the two copies |
| Relay transport | events in, events out | any claim that an event is valid, current, or complete |
| Wallet | "pay this invoice", invoices for the user's own funds | any claim that a payment succeeded (see §9) |
| Clock and randomness | current time, random bytes | nothing structural; but every deadline MUST be evaluated against this clock, never against a counterparty's `created_at` (§4.6) |

No secret key crosses the one-time signer in either direction; the implementation requests only the
throwaway public key, a signature and an encryption (§7.1, §12, §14).

An in-process key, a [NIP-55][nip55] Android signer app or a remote signer is a conformant signer.

The rationale is in §3 of [the companion](NENYA-1-companion.md).

---

## 4. Preliminaries

### 4.1 Event ids and signatures

The `id` is the SHA-256 of the UTF-8 bytes of the NIP-01 canonical serialisation

```
[0,<pubkey hex>,<created_at number>,<kind number>,<tags>,<content>]
```

with no insignificant whitespace. In `content` and every tag value, an implementation MUST emit:

1. the seven shortcut escapes NIP-01 names, and only these seven, for the characters they
   name: `\n` (0x0A), `\"` (0x22), `\\` (0x5C), `\r` (0x0D), `\t` (0x09), `\b` (0x08),
   `\f` (0x0C);
2. every **other** character below `0x20` as `\u00XX`, where `XX` is that byte's value in two
   **lowercase** hexadecimal digits — so `0x00` is `\u0000`, `0x01` is `\u0001`, and `0x1f`
   is `\u001f`;
3. every character at or above `0x20` verbatim, as UTF-8 bytes.

An implementation MUST NOT `\u`-escape any character at or above `0x20` (including non-ASCII
characters and `0x7f`), MUST NOT escape `/`, MUST NOT emit uppercase hex digits in a `\u` escape,
MUST NOT use the `\u00XX` form for the seven characters of rule 1, and MUST NOT reorder or normalise
tags. Rule 1 takes precedence over rule 2.

Rules 1–3 follow `nostr-tools` and `go-nostr` rather than NIP-01's literal wording, which yields raw
control bytes RFC 8259 forbids (§4.1 of [the companion](NENYA-1-companion.md)). Implementations SHOULD carry a
test vector for each shortcut escape and at least one for a `\u00XX` control character (§18).

Text with no UTF-8 encoding MUST be rejected, including an unpaired UTF-16 surrogate (a lone high
`U+D800`–`U+DBFF` or low `U+DC00`–`U+DFFF` unit, or a wrong-order pair). An implementation MUST
reject an event whose `content` or a tag value contains one, when computing or checking an id, and
MUST NOT substitute a replacement character, drop the unit, or hash its three-byte encoding. A
correctly paired character above `U+FFFF` is emitted as four UTF-8 bytes (rule 3).

An implementation MUST compute ids itself and MUST reject, before any other processing, a received
event whose `id` differs from the recomputed value. Signatures are BIP-340 Schnorr over secp256k1 on
exactly the 32 bytes the `id` encodes in hex. Signature verification MAY be delegated to the
embedding client; an implementation that cannot verify signatures MUST state this in its
capability surface and MUST NOT report unverified events as verified (§17).

### 4.2 Addressable coordinates

Requests and offers are addressable events (kinds 30000–39999), edited by republishing with a newer
`created_at`. A reference to a listing MUST use its coordinate, never its event id:

```
["a", "<kind>:<pubkey-hex>:<d-value>", "<relay-url>"]
```

A listing MAY be shared out of band as a NIP-19 `naddr`; its relay hints reveal the sharer's
relays (§12).

### 4.3 Encodings, tags and hostile input

The rules below apply to every event, including events the implementation believes it authored.

**Hex.** Lowercase and unpadded. Implementations MUST emit lowercase hex and SHOULD accept and
normalise uppercase on read, except that an uppercase or mixed-case BOLT-11 invoice string or
`kind:17` Lightning preimage MUST be rejected without normalisation (§9.2 checks 1 and 2; §18).
BOLT-11 does not impose this rule. A sender lowercases an invoice before placing it in a `payment`
tag (Appendix C). A pubkey (32-byte x-only), event id, order id, `x` and `ox` are each exactly 64
hex characters; an implementation MUST reject one of another length rather than pad or truncate it.
The invoice and preimage are the only exceptions: every other hex value, including `x`, `ox`,
`decryption-key` and `decryption-nonce`, follows the read rule above.

**Timestamps.** Unix seconds: a decimal string in a tag value, a JSON number in `created_at`.
Implementations MUST reject a timestamp tag value that is not a non-negative decimal integer and
MUST NOT accept floating-point, exponential or signed forms.

**Tag shape.** A tag is a non-empty array of strings. Implementations MUST reject an event
containing an empty tag array or a non-string or `null` tag element.

**Text.** Byte bounds are measured in UTF-8 bytes; §4.1 governs text with no UTF-8 encoding.

**Duplicate tags.** An event with more than one occurrence of a tag of cardinality `1` or `0–1` MUST
be rejected, not resolved by taking the first, last or smallest.

**Unknown tags.** Unknown tags MUST be ignored on read and MUST be preserved verbatim when an
implementation round-trips an event it did not author.

**Resource limits.** An implementation MUST bound the size of anything it parses and MUST
reject, not truncate, input that exceeds a bound. These bounds SHOULD
be configurable, with defaults no larger than: 64 KiB serialised event, 512 tags per event,
1024 bytes per tag value, 16 KiB `content`, 64 `image` tags. An unbounded parser is a
denial of service in the buyer's client.

The defaults above apply to ordinary events and the rumor; a `kind:13` seal and a
`kind:1059` gift wrap are bounded by NIP-44 instead. NIP-44 pads each nested plaintext
to a power-of-two-derived size, so under the 16 KiB default a wrap carrying a rumor of
more than 7 168 bytes of JSON had to be refused — a conformant implementation could not
open a 10 KB chat message. For these two kinds only:

- a **decrypted NIP-44 plaintext** is at least 1 and at most 65 535 bytes, which is what
  NIP-44's published vectors require and what every deployed application implements. A
  later NIP-44 revision's six-byte extended-length prefix is not part of NENYA-1;
- a `kind:1059` wrap's `content` is at most 87 472 base64 characters, exactly the encoding
  of the largest NIP-44 payload the bound above permits;
- a **rumor's JSON is at most 40 960 bytes on write** — a bound on emitting, not reading:
  anything larger pads to a seal whose own JSON exceeds the plaintext bound above and so
  cannot be wrapped at all, a message nobody can open. An implementation MUST refuse to
  emit one rather than wrap it.

Every other default above still applies to a seal and a wrap: the tag count, the tag value length,
and the whole-event bound an implementation sets for them.

### 4.4 Money

In public listing and bid tags, prices are integer satoshis with the unit token `SAT`:

```
["price", "<integer satoshis>", "SAT"]
```

Internally and in every private order message, amounts are integer millisatoshis:
`1 SAT = 1000 msat`; `1 BTC = 100 000 000 SAT = 100 000 000 000 msat`.

- **Strict on write.** Exactly the token `SAT` and a decimal integer matching `0|[1-9][0-9]*`.
- **Permissive on read.** Implementations MUST accept `SAT`, `sat`, `sats`, `SATS`, `msat`, `MSAT`,
  `BTC` and `btc`, and normalise to millisatoshis. A `BTC` value MAY carry a decimal point.
- **Never lossy.** A read that would lose precision (a `BTC` value with more than 11 decimal places,
  or an `msat` value not a multiple of 1000 where a satoshi-denominated field is REQUIRED) MUST fail
  with an error and MUST NOT round.
- **Never floating point.** Amounts MUST NOT be held in binary floating point, including while
  parsing a decimal `BTC` string (the supply cap exceeds 2^53). Where the default number type is a
  double, amounts MUST use an arbitrary-precision integer (`BigInt`) or decimal-string arithmetic.
- **Bounded.** Implementations MUST reject a `price` whose millisatoshi value exceeds
  2 100 000 000 000 000 000 (the total bitcoin supply in msat).

The NIP-99 `<frequency>` fourth element of `price` MUST NOT be emitted by Nenya v1; a listing
carrying one MUST be treated as unsupported. Implementations SHOULD display prices in both units.

### 4.5 Version and discovery tags

Every public Nenya event MUST carry both of:

```
["nenya", "1"]          # protocol version, not relay-indexed
["t", "nenya"]          # board discovery, relay-indexed
```

The `nenya` value is the decimal major version of this document. An implementation MUST ignore a
public event whose `nenya` version it does not implement.

### 4.6 Clocks and deadlines

![Clocks, windows and deadlines](../docs/clocks-and-deadlines.svg)
*Figure (non-normative): the backdated gift-wrap timestamp window, the future tolerance for public events, and where each deadline anchors against the injected clock.*

Every deadline (`expiration` on a listing, bid or order proposal; `deliver_by`; a BOLT-11 invoice
expiry) MUST be evaluated against the implementation's own injected clock.

- An implementation MUST NOT order an order thread's events by `created_at`; it MUST order them by
  its own receipt sequence and MUST derive state only from the §11.2 transition table.
- An implementation MAY reject a public event whose `created_at` is implausibly far in the future
  (RECOMMENDED tolerance: 15 minutes) and MUST NOT reject a gift wrap, seal or rumor on
  timestamp grounds.
- An implementation SHOULD test every deadline rule offline with a fake clock.

---

## 5. The board — public events

### 5.1 Offers — NIP-99 `kind:30402`

An offer is a NIP-99 classified listing with added tags and no other changes. Drafts and parked
offers use `kind:30403`, which has the same structure. `["t", "wts"]` (want to sell) is REQUIRED on
an offer, alongside `["t", "nenya"]`. An offer's `price` is what the provider receives (§8.2).
An offer's `status` MUST be `active` or `sold`.

```json
{
  "kind": 30402,
  "created_at": 1757000000,
  "content": "Stable Diffusion XL, 1024x1024, three revisions included.\n\nTurnaround under 24h.",
  "tags": [
    ["d", "sdxl-portrait-commission"],
    ["title", "Custom SDXL portrait, 1024x1024"],
    ["summary", "One portrait, three revisions, 24h turnaround"],
    ["published_at", "1756900000"],
    ["price", "50000", "SAT"],
    ["status", "active"],
    ["m", "image/png"],
    ["t", "nenya"],
    ["t", "wts"],
    ["t", "ai-image"],
    ["image", "https://example.invalid/sample.png", "1024x1024"],
    ["expiration", "1759592000"],
    ["nenya", "1"],
    ["alt", "Nenya marketplace offer: custom SDXL portrait for 50000 sats"]
  ],
  "pubkey": "<provider-pubkey-hex>",
  "id": "<computed>",
  "sig": "<bip340>"
}
```

### 5.2 Requests — `kind:30404`

**The Nenya request kind is `30404`.** The kind is addressable: a request is edited and retracted
by `(kind, pubkey, d)`. Appendix B freezes the value. An implementation MUST expose `30404` as a
single named constant and MUST NOT hardcode the literal in more than one place. `["t", "request"]`
is REQUIRED on a request, alongside `["t", "nenya"]`. A request's `price` is the buyer's budget:
the amount the provider would receive, as a negotiation ceiling rather than a commitment. A bid
MAY name a different price (§6). An implementation MUST treat an unrecognised request `status`
value as `unknown` and MUST NOT treat it as `active`. Fixtures MAY be built against the example
below as written.

```json
{
  "kind": 30404,
  "created_at": 1757000000,
  "content": "Looking for a 30-second looping animation of a lighthouse in a storm.\n\nStyle reference attached. Must be my own commission, not a stock clip.",
  "tags": [
    ["d", "lighthouse-loop-2026-09"],
    ["title", "30s looping animation, lighthouse in a storm"],
    ["summary", "Seamless loop, 1080p, no watermark"],
    ["published_at", "1757000000"],
    ["price", "120000", "SAT"],
    ["status", "active"],
    ["m", "video/mp4"],
    ["t", "nenya"],
    ["t", "request"],
    ["t", "ai-video"],
    ["image", "https://example.invalid/reference.jpg", "1280x720"],
    ["expiration", "1759592000"],
    ["nenya", "1"],
    ["alt", "Nenya marketplace request: 30s lighthouse animation, budget 120000 sats"]
  ],
  "pubkey": "<buyer-pubkey-hex>",
  "id": "<computed>",
  "sig": "<bip340>"
}
```

### 5.3 Tag vocabulary

A tag in this table has the same meaning on requests and offers. Wherever it appears, including on
a `kind:1111` bid (§6), a private bid (§6.1) or an order rumor (§7), it MUST use the encoding given
here. A codec SHOULD be shared across these events. The **Card.** and **Requirement** columns apply
only to `kind:30402`, `kind:30403` and `kind:30404` listings and MUST NOT be applied to bids or
order rumors (see §6, §6.1, §7.4, §7.5). A shared tag validator MUST take the event kind, and for
`kind:16` the `type`, as input to its cardinality and requirement rules.

| Tag | Side | Card. | Requirement | Encoding | Notes |
|---|---|---|---|---|---|
| `d` | both | 1 | MUST | `["d", "<opaque>"]` | Addressability key. MUST be non-empty. SHOULD be unguessable if the author does not want their listings enumerable by `d`. MUST be stable across edits. |
| `title` | both | 1 | MUST | `["title", "<text>"]` | Plain text, no markup. Implementations SHOULD cap display length; a title is not a description. |
| `summary` | both | 0–1 | SHOULD | `["summary", "<text>"]` | One-line tagline, plain text. |
| `published_at` | both | 0–1 | SHOULD | `["published_at", "<unix seconds>"]` | Time of **first** publication. MUST NOT change on edit. A later `created_at` with an unchanged `published_at` is how an edit is recognised. |
| `price` | both | 1 | MUST | `["price", "<sats>", "SAT"]` | See §4.4 and §8.2. |
| `status` | both | 0–1 | SHOULD | `["status", "<value>"]` | Offers: `active` \| `sold` only. Requests: `active` \| `awarded` \| `fulfilled` \| `cancelled`. Absent means `active`. **This is the listing vocabulary, not the order-state vocabulary of §11.1**, which rides the same tag name on a sealed `kind:16` `type=3` and shares only the token `cancelled`, with a different meaning. One codec MUST NOT serve both (§11.1). |
| `t` | both | ≥2 | MUST | `["t", "<token>"]` | MUST include `nenya` and exactly one of `request` / `wts`. Further `t` tags are free-form categories. Tokens MUST be lowercase with no whitespace; implementations MUST lowercase on write and SHOULD match case-insensitively on read, because relay tag indexes are byte-exact. |
| `m` | both | 0–1 | SHOULD | `["m", "<mime type>"]` | Desired/produced MIME type of the deliverable, NIP-94 semantics. Single-letter, therefore relay-filterable — this is how a client offers "show me only video jobs" without downloading the board. |
| `image` | both | 0–n | MAY | `["image", "<url>", "<width>x<height>"]` | Reference material (requests) or samples (offers). MUST be an `https:` URL, never inline bytes and never a `data:` URI. See §12.10 on auto-fetch. |
| `expiration` | both | 0–1 | SHOULD | `["expiration", "<unix seconds>"]` | NIP-40. See §5.6. |
| `alt` | both | 0–1 | MUST on requests, SHOULD on offers | `["alt", "<text>"]` | NIP-31 fallback rendering. |
| `nenya` | both | 1 | MUST | `["nenya", "1"]` | Protocol version. |
| `p` | both | 0–n | MAY | `["p", "<pubkey-hex>", "<relay-url>"]` | A directed listing — addressed to specific counterparties. Relay-indexed, so "requests directed at me" is a server-side query. A directed listing is still public. |
| `fee` | both | 0–1 | MAY | `["fee", "<bps>"]` when bps = `0`; `["fee", "<bps>", "<recipient-pubkey-hex>"]` when bps > `0` | An **advertised** fee term. Advisory only on a listing; only the order copy binds. The recipient element is REQUIRED when bps > `0` and MUST be OMITTED when bps = `0`, so the canonical no-fee form is the two-element `["fee", "0"]` (§8.1). A parser MUST accept both arities and MUST NOT treat `["fee", "0"]` as malformed. See §8. |
| `license` | both | 0–1 | MAY | `["license", "<token>"]` | Usage rights granted with the deliverable. Vocabulary is `OPEN-5`. |
| `deliver_by` | both | 0–1 | MAY | `["deliver_by", "<unix seconds>"]` | Requested/offered delivery deadline. Advisory on a listing; binding only when copied into accepted terms. |
| `item` | neither | 0 | MUST NOT | `["item", "<kind>:<pubkey-hex>:<d-value>", "<quantity>"]` | **Not a listing tag** — a listing cannot reference itself, and an implementation MUST reject an `item` tag on a `kind:30402`/`30403`/`30404` event. It is defined here because it is the one tag §6.1 and §7.5 need that the listing vocabulary does not otherwise supply. First element is the listing **coordinate** (§4.2); second is the quantity, which MUST be the string `"1"` in Nenya v1 and MUST be rejected otherwise. Cardinality by context: exactly `1` on an order proposal (`kind:16` `type=1`, §7.5) and on a private bid (`kind:16` `type=6`, §6.1); `0` on a public `kind:1111` bid, where `A`/`a` already carry the binding and a second binding that could disagree with the first is precisely what §4.2 exists to prevent; more than one is a rejection everywhere (§4.3, duplicate tags). |
| `g` | both | 0 | MUST NOT | — | Geohash. Nenya deliverables are digital; a geohash on a Nenya listing is a location leak with no compensating function. Implementations MUST NOT emit `g` and SHOULD warn if they parse one. |
| `location` | both | 0 | MUST NOT | — | Same reasoning as `g`. |

### 5.4 Content

A listing's `content` is a human-readable description and, per NIP-99, SHOULD be Markdown. It
MUST NOT carry structured terms: a price, deadline, fee or URL in `content` has no effect. An
implementation rendering `content` MUST NOT execute embedded scripts, MUST NOT auto-fetch remote
resources it references (§12.10), and SHOULD render it as plain text if it cannot do both.

### 5.5 Discovery

Example server-side filters:

```json
{"kinds": [30402], "#t": ["nenya"], "limit": 200}
{"kinds": [30404], "#t": ["nenya"], "limit": 200}
{"kinds": [30404], "#m": ["image/png"]}
{"kinds": [30402, 30404], "#p": ["<my-pubkey>"]}
```

Implementations MUST account for these relay behaviours:

- Multiple values in one tag filter match as OR, not AND. Implementations MUST NOT rely on such
  filters to conjoin conditions and MUST re-filter results client-side.
- Relays index only a tag's first value (`50000`, not `SAT`, in `["price", "50000", "SAT"]`).
- Relays can silently drop unregistered kinds. Implementations SHOULD check the relay's NIP-11
  document, SHOULD read a published request back, and MUST report "this relay did not accept the
  request" distinctly from "publish failed".
- Relay results are unordered, incomplete and possibly stale. An implementation MUST NOT treat the
  absence of an event as evidence of anything, including order cancellation or listing withdrawal.

### 5.6 Expiration and takedown

Per NIP-40, relays *SHOULD* honour `expiration`. An implementation MUST treat an event whose
`expiration` has passed as inactive regardless of the relay serving it, and MUST NOT accept a bid,
propose an order, or advance an order against an expired listing.

Un-listing requires both: (1) republishing the listing with `["status", "sold"]` (offers) or a
terminal status (requests), or as `kind:30403` (offers only); and (2) publishing a NIP-09 `kind:5`
deletion request with an `a` tag naming the listing's coordinate. Publishing a `kind:30403` does
not remove the `kind:30402`; implementations MUST NOT present "save as draft" as a takedown. A
NIP-09 deletion request is advisory: relays SHOULD delete versions up to its `created_at`, and a
republish of the same `d` with a newer `created_at` is valid and visible again.
Implementations MUST NOT tell a user that a deletion is permanent.

---

## 6. Bids — NIP-22 `kind:1111`

A bid is a NIP-22 comment scoped to the coordinate of the listing it bids on. A conformant client
MUST implement public bidding and MUST make it the default. An implementation MUST tell the user,
before their first bid, that a bid's price, terms and author pubkey are public. Uppercase (root) and
lowercase (parent) NIP-22 scope tags MUST both be present, with equal values on a top-level bid.

```json
{
  "kind": 1111,
  "created_at": 1757001234,
  "content": "I can do this for 90k sats, delivered within 18 hours. Portfolio in my profile.",
  "tags": [
    ["A", "30404:<buyer-pubkey-hex>:lighthouse-loop-2026-09", "wss://relay.example.invalid"],
    ["K", "30404"],
    ["P", "<buyer-pubkey-hex>", "wss://relay.example.invalid"],
    ["a", "30404:<buyer-pubkey-hex>:lighthouse-loop-2026-09", "wss://relay.example.invalid"],
    ["k", "30404"],
    ["p", "<buyer-pubkey-hex>", "wss://relay.example.invalid"],
    ["price", "90000", "SAT"],
    ["fee", "250", "<fee-recipient-pubkey-hex>"],
    ["deliver_by", "1757066034"],
    ["expiration", "1757260000"],
    ["t", "nenya"],
    ["nenya", "1"],
    ["alt", "Nenya bid: 90000 sats, delivery within 18 hours"]
  ],
  "pubkey": "<provider-pubkey-hex>",
  "id": "<computed>",
  "sig": "<bip340>"
}
```

Rules:

- A bid MUST scope to a coordinate (`A`/`a`), never to an event id (`E`/`e`).
- `K` and `k` MUST both be present and MUST both equal the listing's kind as a decimal string. `P`
  and `p` MUST both be present and MUST both equal the listing author's pubkey.
- A bid MUST carry `price`, `t=nenya` and `nenya`. §5.3's cardinality rules do not apply to bids.
- A bid SHOULD carry `expiration`; implementations SHOULD render a bid without it as open-ended.
- A bid SHOULD carry `["alt", "<text>"]` (NIP-31) and SHOULD restate its price and delivery terms in
  `content`, which implementations SHOULD generate from the tags. An implementation MUST derive
  every term from the tags (§5.4).
- A bid's `deliver_by` is evaluated against the injected clock (§4.6).
- A bid MAY carry `fee`. Any later divergence from it MUST abort the order (§8.4).
- `content` MUST be plain text.
- Bids target requests and MAY target offers as counter-offers (`A`/`K` set to `30402:...` /
  `30402`). An implementation MUST parse both.
- A bid creates no obligation (§7.4); an implementation MUST NOT advance order state on any bid.
- Implementations SHOULD apply the user's mute and filter lists to bids and MUST NOT auto-accept.

### 6.1 Private bidding

A private bid is a `kind:16` rumor with `["type", "6"]`, sent to the listing author over the §7
channel instead of as a public `kind:1111`. It is the only `kind:16` `type` value that MUST NOT
carry an `order` tag, and it MUST NOT advance any state (§7.4, §11.2).

Rationale for `type=6`: §6.1 of [the companion](NENYA-1-companion.md).

- It carries `price`, optionally `fee`, `deliver_by` and `expiration`, exactly one
  `["item", "<coordinate>", "1"]` naming the listing (§5.3), `["nenya", "1"]`, and
  `["p", "<listing-author-pubkey-hex>"]` (§7.4).
- A private bid MUST NOT be sent as a `kind:16` `type=1`. A `type=6` carrying an `order` tag MUST be
  rejected, not accepted with the tag ignored.
- An implementation that does not support private bidding MUST ignore a `type=6` rumor (§7.4) and
  MUST NOT report it as an order, a proposal, or a sender error.
- Every §6 rule about terms applies to a private bid.
- An implementation that offers private bidding MUST make the choice explicit per bid and MUST NOT
  silently convert a bid between public and private.

An implementation MAY support only public or only private bidding; public-only is conformant,
private-only is not (§17, item 3).

---

## 7. The private channel — NIP-17 / NIP-59

Every message after the bid is a NIP-17 private direct message, never plaintext on a relay.

![One trade, message by message](../docs/trade-sequence.svg)

*Figure (non-normative): one complete trade, message by message, across buyer, provider,
fee recipient, relays and Lightning.*

### 7.1 Envelope

The envelope follows NIP-17 and NIP-59. Step order is significant in both procedures.

![The envelope: rumor, seal, wrap — twice](../docs/envelope-nesting.svg)

*Figure (non-normative): the three layers, their keys, timestamps and size bounds (§4.3),
and the read order.*

Each layer's payload is the event's JSON object form, whose strings MUST be escaped by §4.1's rules.
A duplicate key MUST be rejected at every layer (§4.3).

**The write procedure.**

1. Build an unsigned rumor: an event object with `id`, `created_at` set to the true time, and no
   `sig` key, within §4.3's size bounds.
2. Seal it: NIP-44-encrypt the rumor's JSON into the `content` of a `kind:13` with empty `tags`,
   signed by the sender's key.
3. Gift wrap it: NIP-44-encrypt the seal's JSON into the `content` of a `kind:1059` carrying exactly
   one `["p", "<recipient-pubkey>", "<relay-url>"]` tag, signed by a keypair freshly generated for
   that wrap. Kind `21059` MUST NOT be used.
4. Produce one copy for the recipient and one for the sender, each sealed to its addressee. The
   throwaway keypair MUST differ between the copies and MUST equal neither party's key.
5. `created_at` on each seal and each wrap (four values) MUST be drawn independently and uniformly
   from `[now − 172 800, now]` using the injected randomness source (§3).

**The read procedure.** The steps MUST be applied in order; a rejection at any step ends the read
and no part of the message is reported.

1. **Id.** Recompute the wrap's `id` and reject on mismatch (§4.1).
2. **Kind.** Reject any kind other than `1059`.
3. **Recipient.** The wrap MUST carry exactly one `p` tag, whose value MUST be the reader's public
   key; reject otherwise, before any decryption.
4. **Wrap signature.** An implementation that verifies BIP-340 signatures MUST verify the wrap's
   signature against its `pubkey` and MUST reject the wrap on failure.
5. **Decrypt** the wrap's `content` to the seal's JSON.
6. **Seal.** Parse it, recompute its `id`, and reject a kind other than `13` or non-empty `tags`. An
   implementation that verifies BIP-340 signatures MUST verify the seal's signature against its
   `pubkey` and MUST reject the seal on failure.
7. **Decrypt** the seal's `content` to the rumor's JSON.
8. **Rumor.** Parse it; reject a `sig` key with any value, including an empty string or `null`;
   recompute its `id` and reject on mismatch.
9. **§7.2.** Apply the attribution rule.

An implementation that does not verify signatures (§17) MUST continue past steps 4 and 6, MUST still
perform §7.2's pubkey-equality check, and MUST report such messages as authenticated-by-decryption
only, never as signature-verified. One that verifies MUST reject a bad signature and MUST NOT report
a message as signature-verified on a verdict it did not obtain (§7.2, §17).

An implementation MAY reject event JSON carrying an unknown top-level key, and MAY reject a message
whose recipient equals its sender other than as the sender's own copy from step 4.

### 7.2 Attribution — the rule that makes a sealed term binding

Implementations MUST verify that the `kind:13` seal's `pubkey` equals that of the rumor inside it,
and MUST discard the message otherwise. A rumor's terms are signed (§8) by its seal's signature.

- An order term is attributed to the seal's `pubkey`, never to the rumor's or the gift wrap's.
- Implementations MUST NOT display, index by, or compare the gift wrap's `pubkey`.
- An implementation that verified the seal's signature over its recomputed id (§4.1) MAY report the
  message as signature-verified; one that verifies only some messages MUST distinguish them.
- The gift wrap's signature does not affect attribution, and an implementation MUST NOT report a
  message as signature-verified on its strength.

### 7.3 DM relay lists — `kind:10050`

NIP-17 clients publish DMs only to the relays in the recipient's `kind:10050`; a missing list means
the user cannot receive messages.

- An implementation MUST publish and maintain its user's `kind:10050` before the user can transact.
- An implementation MUST treat a counterparty's missing `kind:10050`, including the named fee
  recipient's (§8.1), as a distinct, user-visible condition (this user cannot receive orders),
  MUST NOT report it as a generic send failure, and
  MUST NOT fall back to publishing the gift wrap elsewhere.

### 7.4 Order message types

Nenya uses the GammaMarkets order vocabulary.

Rumor kinds:

| Rumor kind | Meaning |
|---|---|
| `14` | free-text chat inside the order thread |
| `15` | file message — the **released** deliverable (§10) |
| `16` | structured order message, discriminated by a `type` tag |
| `17` | payment receipt (§9) |

`kind:16` `type` values:

| `type` | Name | Sender | Nenya use |
|---|---|---|---|
| `1` | order proposal | buyer | REQUIRED to open an order |
| `2` | payment request | provider, or fee recipient | carries exactly one BOLT-11 invoice |
| `3` | status update | either | carries a `status` tag from §11.1 |
| `4` | shipping update | — | **Reserved.** GammaMarkets uses this for physical shipping. Nenya v1 MUST NOT emit `type=4` and MUST ignore it on read. |
| `5` | delivery commitment | provider | Nenya extension — §10 |
| `6` | private bid | bidder | Nenya extension — §6.1. The **only** `type` that carries no `order` tag, because no order exists yet. Like `type=4`, it advances no state (§11.2). |

An implementation MUST ignore a `type=5` or `type=6` without a `nenya` version it implements and
MUST NOT interpret it as a delivery commitment or a bid (`OPEN-8`). It MUST ignore a `kind:16` of an
unimplemented `type` and MUST NOT map an unknown `type` to the nearest known one (§4.3, §11.1).

Required tags, by rumor kind:

```
["nenya", "1"]                   # every kind:15, kind:16 and kind:17 rumor
["p", "<counterparty-pubkey-hex>"] # likewise
["order", "<order-id-hex>"]      # likewise, EXCEPT kind:16 type=6, which MUST NOT carry it
                                 # 32 bytes, 64 lowercase hex chars
```

Every `kind:15`, `kind:16` and `kind:17` rumor MUST carry `["nenya", "1"]` and `["p", ...]`, and
MUST carry `["order", ...]` except `kind:16` `type=6`, which MUST NOT carry it. A `kind:14` MAY
carry `order`; an implementation MUST NOT derive anything from its presence or absence.

The **order id** MUST be 32 bytes from a cryptographically secure random source, generated by
the buyer when proposing, and
MUST NOT be derived from the coordinate, either pubkey, the price, or the time. A `subject` tag
(NIP-17) MAY appear on any rumor; an implementation MUST NOT derive any term or state from it.

#### 7.5 `type=1` — order proposal

```json
{
  "kind": 16,
  "created_at": 1757002000,
  "content": "",
  "tags": [
    ["order", "9f2c00000000000000000000000000000000000000000000000000000000abcd"],
    ["type", "1"],
    ["p", "<provider-pubkey-hex>"],
    ["item", "30404:<buyer-pubkey-hex>:lighthouse-loop-2026-09", "1"],
    ["amount_msat", "90000000"],
    ["fee", "250", "<fee-recipient-pubkey-hex>"],
    ["deliver_by", "1757066034"],
    ["expiration", "1757016400"],
    ["nenya", "1"]
  ],
  "pubkey": "<buyer-pubkey-hex>"
}
```

- `item` (REQUIRED): the listing coordinate and a quantity. Exactly one `item` tag MUST be present,
  its quantity MUST be `"1"` in v1, and the listing MUST NOT be expired when proposed (§5.6).
- `amount_msat` is the price in millisatoshis (§8.2). An `["amount", "<sats>"]` tag MAY also be
  present; if `amount × 1000 ≠ amount_msat`, an implementation MUST reject the message and MUST NOT
  prefer one and continue.
- `expiration` is the acceptance deadline. When both are present, `expiration` MUST be strictly
  earlier than `deliver_by`; an implementation MUST reject a proposal that violates this.
- `fee` (write rule; the §8.1 read rule governs): a proposal MUST NOT carry more than one `fee` tag,
  and SHOULD carry exactly one, as `["fee", "0"]` when no fee applies.

#### 7.6 Acceptance

Acceptance is a `type=3` from the provider carrying `["status", "accepted"]`, the same `order` id,
and `item`, `amount_msat`, `fee` and `deliver_by` byte-identical to the proposal's. An
implementation MUST NOT treat silence, a `kind:14`, a public event, or a status update with altered
terms as acceptance. Altered terms make a **counter-proposal**, answered by a new `type=1` with a
new order id.

An implementation MUST establish the provider's pubkey independently of the proposal (the author of
the offer the `item` coordinate names, or the bidder the buyer chose; §5, §6) and MUST NOT read it
from the proposal's `p` tag. It MUST reject a `type=3` acceptance whose seal (`kind:13`) `pubkey` is
not that key, and MUST reject one sealed by the buyer even with byte-identical terms.

---

## 8. The fee term

![Where the fee term is checked](../docs/fee-term-points.svg)
*Figure (non-normative): the signed fee term's path through the order, and the checks at each point.*

A client embedding Nenya MAY take a percentage handling fee. A fee MUST be a signed term of the
deal, and an invoice claiming an unsigned fee MUST be refused.

Rationale and history: §8 of [the companion](NENYA-1-companion.md).

### 8.1 Shape

```
["fee", "<basis-points>", "<recipient-pubkey-hex>"]     # basis points > 0
["fee", "<basis-points>"]                               # basis points == 0
```

- `<basis-points>`: a non-negative decimal integer (§4.4 strict form); 1 bps = 0.01%, `250` = 2.5%.
- `<recipient-pubkey-hex>` is a 64-character lowercase hex x-only public key, REQUIRED when basis
  points is above `0` and OMITTED at `0`. A parser MUST accept both arities and MUST reject
  `["fee", "0", "<pubkey>"]` and `["fee", "250"]` as malformed.
- The legal range is `0..10000` inclusive (`OPEN-2`, closed in revision `1.1`); an implementation
  MUST reject basis points above `10000` (100%) and MUST NOT reject one in range as too high.
- A client MAY apply a lower **local policy limit**. A refusal under it MUST surface as the
  distinct, named condition *above this client's local policy limit* and MUST NOT be reported as a
  malformed fee tag, an unsupported term, a protocol violation, or other peer fault. The limit MUST
  be visible to the user, MUST NOT be a silent default, and MUST NOT be applied on read: an event
  carrying `9000` bps MUST parse, be displayed, and be attributed correctly.
- Above `500` bps, an implementation MUST require explicit user confirmation of the fee, showing
  basis points, absolute amount and total (§8.2), before proposing or accepting the order. A
  client MAY also require it at or below `500` bps.
- A missing `fee` tag on an order proposal MUST be interpreted as zero fee; an implementation MUST
  then refuse every fee invoice for that order and MUST NOT refuse the `type=1` as incomplete terms.
  Per §7.5, a proposal carries at most one `fee` tag, SHOULD carry exactly one, and SHOULD carry
  `["fee", "0"]` when no fee applies.
- The recipient MUST NOT be the buyer's pubkey. It MAY be the provider's pubkey only in a genuine
  agent relationship, and implementations SHOULD warn when it is.
- The named fee recipient MUST be reachable before the fee term is proposed or accepted: for bps
  above `0`, an implementation MUST check that it publishes a `kind:10050` (§7.3) and MUST surface
  its absence as the distinct, named condition *the named fee recipient cannot receive order
  messages*, never as a generic failure or a malformed fee tag. A fee term naming an unreachable
  recipient MUST NOT be proposed (§8.7).

### 8.2 Price semantics

A listing's or bid's `price` and an order's `amount_msat` are the amount the provider receives. The
fee is added on top:

```
provider receives  = price
buyer pays         = price + fee
```

An implementation MUST display the fee as a separate, labelled line item with its basis points and
absolute value, and MUST display the buyer's total.

### 8.3 Fee arithmetic

All arithmetic is integer arithmetic in millisatoshis.

```
price_msat = <amount_msat from the accepted terms>
fee_msat   = floor(price_msat × bps / 10000)
total_msat = price_msat + fee_msat
```

An implementation MUST NOT compute `price_msat × bps` (up to 2.1 × 10^22, above 2^63 and 2^53) in a
64-bit or double-precision intermediate. Arbitrary-precision integers or this exact split, whose
largest intermediate (2.1 × 10^18) fits a signed 64-bit integer, are used instead:

```
fee_msat = (price_msat / 10000) * bps + ((price_msat % 10000) * bps) / 10000
```

`/` is integer division. The result equals `floor(price_msat × bps / 10000)` for every
`price_msat ≤ 2.1 × 10^18` and `0 ≤ bps ≤ 10000`.

- If the fee recipient's rail cannot express millisatoshis, the fee MUST be rounded down to a whole
  satoshi; the rounded value is the expected fee for every later check (§8.6, §9.2 check 4), and a
  rounded `0` triggers the zero-fee rule below. No v1 rail requires it (Appendix C, §9.4).
- When the computed `fee_msat` is zero, no fee invoice may be sent or accepted, even with a non-zero
  `bps` (at `bps = 1` and `price_msat = 3000`, `fee_msat = 0`). "Required payee" MUST be computed
  from the amount, not from the term (§9.2, §11.1).

Worked example (`price = 50 000 SAT`, `bps = 250`):

```
price_msat = 50 000 000
fee_msat   = floor(50 000 000 × 250 / 10 000) = 1 250 000   # 1 250 SAT
total_msat = 51 250 000                                     # 51 250 SAT
```

Rounding example (`price = 1 001 SAT`, `bps = 137`):

```
price_msat = 1 001 000
fee_msat   = floor(1 001 000 × 137 / 10 000) = 13 713 msat
                                             = 13 SAT if the rail is satoshi-denominated
```

Boundary example (`price_msat = 2 100 000 000 000 000 000`, `bps = 10000`):

```
fee_msat   = 2 100 000 000 000 000 000        # exact; naive 64-bit multiply overflows here
total_msat = 4 200 000 000 000 000 000        # still < 2^63
```

### 8.4 The fee term MUST be consistent everywhere

The same `(bps, recipient)` pair MUST appear, byte-identically, wherever a `fee` tag appears
for an order. It is **REQUIRED** on:

1. the order proposal (`type=1`) — subject to §8.1's read rule: a proposal with no `fee` tag
   states a zero fee, and the pair for that order is then `(0, —)` for every later check;
2. the acceptance (`type=3`, `status=accepted`), which is byte-identical to the proposal's
   terms (§7.6) and therefore omits `fee` exactly when the proposal omitted it;
3. the fee payment request (`type=2`, `payee=fee`);
4. the fee receipt (`kind:17`, `payee=fee`) — §9.2 check 6.

It is **OPTIONAL** on the bid (§6), on a `payee=provider` payment request, and on a
`payee=provider` receipt: those messages concern an amount the fee does not enter, and the
`kind:17` example in §9.2 correctly carries no `fee` tag. An implementation MUST NOT require
a `fee` tag on any of the three — but where one **is** present it MUST match, and a bid that
carried a `fee` tag binds the bidder to that term (§6, §6.1).

Any divergence at any point MUST abort the order and MUST be surfaced to the user as a terms
mismatch, not as a transient error. An implementation MUST NOT "take the newest", "take the
smaller", or renegotiate silently. "Byte-identical" here means what it says: `["fee","250",X]`
and `["fee","0250",X]` diverge, and §4.4's strict decimal form is what keeps that from
happening on write.

### 8.5 Fees settle only at settlement

No fee may be paid, and no fee receipt (`kind:17`, `payee=fee`) accepted, before the order reaches
`awaiting_payment`. A fee payment request (`kind:16` `type=2`, `payee=fee`) is accepted only in the
transition into `awaiting_payment` (§11.2). §11.3 invariant 2 tests this.

### 8.6 Two invoices, never one — the non-custodial rule

The buyer MUST pay the provider and the fee recipient with two separate payments to two separate
invoices. An implementation MUST NOT construct, accept, or honour a single invoice covering
`total_msat` that some party then splits.

- A provider payment request MUST be for exactly `price_msat`; any other amount MUST be rejected.
- The fee recipient's payment request MUST be for exactly `fee_msat` (or, where §8.3's rounding
  applies, the rounded amount); when that amount is `0`, no fee payment request exists and any
  that arrives MUST be rejected.
- Payment requests MUST be tagged with their payee role:

```
["payee", "provider"]
["payee", "fee", "<fee-recipient-pubkey-hex>"]
```

- A `type=2` payment request MUST carry exactly one `["payment", "lightning", "<bolt11>"]` tag; an
  implementation MUST reject a `payment` tag whose reference is not a BOLT-11 invoice string,
  including a `lud16` static Lightning address (§9.3).

A `type=2` with `["payee", "provider"]` MUST arrive in a gift wrap whose seal (`kind:13`) `pubkey`
is the key the order's acceptance was checked against (§7.6, §11.2); any other MUST be rejected.

Once a `type=2` is accepted for an `(order, payee)` pair, a second MUST be rejected rather than
replacing it, and a `type=2` whose BOLT-11 string is byte-identical to one accepted for another
payee on the same order MUST be rejected. An implementation MUST reject a `type=2` whose invoice has
expired at the clock reading it would record for that acceptance (§9.2 check 5).

### 8.7 Verifying that a fee invoice is the fee recipient's

A `type=2` with `["payee", "fee", ...]` MUST arrive in a gift wrap whose seal (`kind:13`) `pubkey`
equals the fee-recipient pubkey named in the signed fee term; a fee invoice forwarded by the
provider or from any other key MUST be rejected.

`OPEN-7`: whether the transport binding above suffices for v1, or a stronger one is specified (a
fee-recipient nostr signature over the invoice's `payment_hash`, or BOLT-11 node-key recovery
against a pre-registered node key).

---

## 9. Payment evidence

### 9.1 What is not evidence

None of the following MUST advance an order's state:

- a boolean returned by a wallet (`isPaid`, `success`, `settled`);
- a status string in any message;
- a `kind:17` receipt without verifiable proof;
- a [NIP-57][nip57] zap receipt;
- a relay's acceptance of an event;
- any assertion by a signer or transport.

An implementation MAY display any of these, labelled as unverified, and MUST NOT let any of them
change an order's state.

### 9.2 Lightning payment evidence

The only payment evidence NENYA-1 v1 defines is a Lightning preimage, carried in a `kind:17`
receipt whose `payment` tag has the GammaMarkets shape
`["payment", "<medium>", "<medium-reference>", "<proof>"]`:

```json
{
  "kind": 17,
  "created_at": 1757003000,
  "content": "",
  "tags": [
    ["order", "9f2c00000000000000000000000000000000000000000000000000000000abcd"],
    ["payee", "provider"],
    ["payment", "lightning", "lnbc900u1p...", "<preimage-hex-64-chars>"],
    ["amount_msat", "90000000"],
    ["nenya", "1"],
    ["p", "<counterparty-pubkey-hex>"]
  ],
  "pubkey": "<buyer-pubkey-hex>"
}
```

An implementation MUST itself perform all six checks before treating a payment as made:

1. **Invoice identity.** The receipt's BOLT-11 string MUST be byte-identical to the one in the
   stored `type=2` payment request for the same `order` and `payee`; with no stored request, the
   receipt MUST be rejected. An implementation MUST persist each accepted payment request with
   the injected clock's value at acceptance.
2. **Preimage shape and case.** The proof MUST be lowercase hex, never normalised (§4.3), and
   MUST decode to exactly 32 bytes.
3. **Preimage check.** `SHA-256(preimage) == payment_hash`, where `payment_hash` is the BOLT-11
   invoice's 256-bit `p` tagged field (Appendix C).
4. **Amount check.** The amount in the invoice's human-readable part MUST equal the payee's
   expected amount (§8.3): `price_msat` for `provider`, the fee amount for `fee`. A zero-amount
   invoice, or any invoice for a payee whose expected amount is `0`, MUST be rejected.
5. **Expiry check.** The invoice's `timestamp + expiry` MUST NOT be earlier than the injected
   clock's value persisted at acceptance (check 1, §4.6); it is not re-evaluated at verification.
6. **Fee-term check.** For a `payee=fee` receipt, the fee term MUST match (§8.4), the sealing key
   MUST be the fee recipient's (§8.7), and the state MUST already be `awaiting_payment` (§8.5).

An order becomes `paid` only when each payee named in the terms with a non-zero expected amount
(§8.3) has a verified receipt.

Verification of the invoice's destination node is OPTIONAL in v1; an implementation that omits it
MUST NOT claim to have verified the destination.

### 9.3 Invoice binding and privacy

An invoice is bound to an order by arriving in that order's sealed thread from the correct key
(§7.2, §8.7). An invoice obtained any other way MUST NOT be paid for a Nenya order, and static
Lightning addresses (LNURL / `lud16`) MUST NOT be used for order settlement.

Rationale: §9.3 of [the companion](NENYA-1-companion.md).

### 9.4 Other rails

`["payment", "bitcoin", "<address>", "<txid>"]` and `["payment", "ecash", "<mint>", "<proof>"]`
MAY be parsed. NENYA-1 v1 defines no verification rule for either; an implementation MUST treat
such a payment as unverified.

`OPEN-4`: an offline rule establishing that an ecash token (Cashu, NIP-60/61 nutzaps) was received
and is spendable. Candidates: defer to NENYA-2; DLEQ proof verification (requires secp256k1); a
mint-attested state check (requires a trusted third party).

---

## 10. Deliverables and hash commitments

A deliverable is an encrypted blob at a URL, never bytes inside a nostr event, committed to by
hash before payment and unlocked after.

![From file to verified delivery](../docs/deliverable-pipeline.svg)
*Figure (non-normative): the deliverable pipeline, from metadata stripping to buyer verification.*

### 10.1 The commitment — `kind:16`, `type=5`

Sent by the provider before any payment request.

```json
{
  "kind": 16,
  "created_at": 1757002500,
  "content": "",
  "tags": [
    ["order", "9f2c00000000000000000000000000000000000000000000000000000000abcd"],
    ["type", "5"],
    ["url", "https://blob.example.invalid/9a7f"],
    ["x", "<sha256 hex of the ENCRYPTED bytes exactly as served>"],
    ["ox", "<sha256 hex of the PLAINTEXT bytes before encryption>"],
    ["m", "video/mp4"],
    ["size", "18342912"],
    ["encryption-algorithm", "aes-gcm"],
    ["nenya", "1"],
    ["p", "<buyer-pubkey-hex>"]
  ],
  "pubkey": "<provider-pubkey-hex>"
}
```

- `x` is the SHA-256 of the encrypted file as served, `ox` of the file before encryption (NIP-94 /
  NIP-17); both MUST be present as lowercase hex of exactly 64 characters.
- `size` is the encrypted blob's length in bytes, as a decimal string.
- The commitment MUST NOT contain `decryption-key` or `decryption-nonce`; an implementation MUST
  reject one that does.

### 10.2 Encryption parameters

`encryption-algorithm` MUST be `aes-gcm` in v1: AES-256-GCM with a 256-bit key generated per
deliverable from the injected randomness source and never reused across orders, a 96-bit (12-byte)
nonce never reused with the same key, a 128-bit authentication tag appended to the ciphertext, and
no additional authenticated data. The served blob is exactly `ciphertext || tag`; the nonce
travels in the `decryption-nonce` tag.

`decryption-key` and `decryption-nonce` MUST be emitted as lowercase hex (64 and 24 characters
respectively). Implementations SHOULD also accept standard base64 on read, and MUST reject a value
that decodes to the wrong length under both encodings.

### 10.3 The release — `kind:15` file message

Sent by the provider, as a NIP-17 file message, after payment evidence verifies.

```json
{
  "kind": 15,
  "created_at": 1757003100,
  "content": "https://blob.example.invalid/9a7f",
  "tags": [
    ["p", "<buyer-pubkey-hex>"],
    ["order", "9f2c00000000000000000000000000000000000000000000000000000000abcd"],
    ["file-type", "video/mp4"],
    ["encryption-algorithm", "aes-gcm"],
    ["decryption-key", "<64 hex chars>"],
    ["decryption-nonce", "<24 hex chars>"],
    ["x", "<same value as the commitment>"],
    ["ox", "<same value as the commitment>"],
    ["size", "18342912"],
    ["nenya", "1"]
  ],
  "pubkey": "<provider-pubkey-hex>"
}
```

A `kind:15` release is bound to its order by its `["order", "<order-id-hex>"]` tag (§7.4), not by
hash equality. A release whose `order` tag names no open order held by this implementation, or an
order not in state `paid`, MUST NOT advance any state.

The release's `file-type` value MUST be byte-identical to the commitment's `m` value, and its `x`,
`ox` and `size` values MUST each be byte-identical to the commitment's. An implementation MUST NOT
read `file-type` from the commitment or `m` from the release, and MUST NOT skip the check because a
tag is absent. Any mismatch MUST move the order to `disputed`.

### 10.4 Buyer verification

The buyer MUST, in this order:

1. Download the blob at the URL and compute its SHA-256; it MUST equal `x`, otherwise the buyer
   MUST NOT decrypt it and MUST NOT settle.
2. Decrypt with the released key and nonce; GCM authentication MUST pass.
3. Compute the plaintext's SHA-256; it MUST equal `ox`, otherwise the order MUST move to `disputed`.

The order reaches `settled` only after all three succeed. An implementation MUST bound the
download (§4.3) and MUST refuse a blob whose length differs from `size`.

A blob refused for its length or by the download bound, or a download that does not complete, is
not a hash mismatch: the order remains `released` until the verification deadline (§11.2), and an
implementation MUST NOT immediately move it to `disputed`.

### 10.5 What the commitment does and does not prove

`ox` proves only delivery of the committed bytes. Implementations MUST NOT present hash
verification to users as a quality guarantee.

A provider MAY publish `ox` in a public bid. Implementations MUST make this opt-in, MUST explain
the trade-off, including that it creates a correlation handle, and MUST NOT make it the default.

Rationale: §10.5 of [the companion](NENYA-1-companion.md).

### 10.6 Metadata

Providers MUST strip identifying metadata (EXIF, XMP, C2PA/JUMBF) before encryption and upload.

---

## 11. Order lifecycle

Each party derives its own order state only from evidence it verified itself. An implementation
MUST NOT assume its counterparty is in the same state.

### 11.1 States

Canonical lowercase tokens, carried in `["status", "<value>"]` on `kind:16` `type=3`:

| State | Meaning |
|---|---|
| `proposed` | buyer has sent `type=1`; nothing is agreed |
| `accepted` | provider has accepted the terms as sent |
| `committed` | provider has published the deliverable commitment (`type=5`) with `x` and `ox` |
| `awaiting_payment` | every **required** payment request (`type=2`) has been received and validated against the terms — required meaning named by the terms **and** carrying a non-zero expected amount (§8.3, §9.2) |
| `paid` | every **required** receipt has been **verified** per §9.2, with "required" as above |
| `released` | provider has sent the `kind:15` with the decryption key |
| `settled` | buyer has verified `x` and `ox`. **Terminal, success.** |
| `cancelled` | either party cancelled before `paid`. **Terminal.** |
| `expired` | a deadline passed. **Terminal.** |
| `disputed` | something failed after `paid`, or a commitment was violated. **Terminal in v1** — Nenya provides no resolution mechanism. |

Reserved, and MUST NOT be emitted by a v1 implementation: `held`, `refunded`, `in_review`,
`resolved`. They are named here so a future escrow revision can use them without colliding
with an extension somebody invented in the meantime.

An unrecognised status value MUST be treated as `unknown`, MUST NOT be mapped onto the nearest
known state, and MUST NOT be treated as `paid` or `settled`. An implementation MUST NOT map
GammaMarkets `pending`, `confirmed`, `processing` or `completed` onto Nenya states. `cancelled`
has the same meaning in both vocabularies.

Order states are separate from listing `status` values (§5.1, §5.2). An implementation MUST NOT
parse a listing `status` with the order-state codec or an order `status` with the listing codec,
and MUST NOT derive order state from a listing `status`.

### 11.2 Transitions

| From | To | Trigger — and what MUST be verified first |
|---|---|---|
| — | `proposed` | buyer sends `type=1` with complete terms, carrying **at most one** `fee` tag; a proposal with no `fee` tag is complete and means zero fee (§8.1), and a second `fee` tag is a rejection (§4.3) |
| `proposed` | `accepted` | sealed `type=3` `status=accepted` from the **provider's** key, terms byte-identical to the proposal |
| `proposed` | `cancelled` | sealed `type=3` `status=cancelled` from either party |
| `proposed` | `expired` | injected clock passes the proposal's `expiration` |
| `accepted` | `committed` | sealed `type=5` from the provider with well-formed `x` and `ox` |
| `accepted` | `cancelled` / `expired` | as above |
| `committed` | `awaiting_payment` | one valid `type=2` per **required** payee — a payee named by the terms whose expected amount is non-zero; provider invoice amount `== price_msat`; fee invoice, when `fee_msat > 0`, amount `== fee_msat`, sealed by the fee recipient (§8.7), fee term matching (§8.4). A fee payee whose computed `fee_msat` is `0` requires **no** invoice and **no** receipt (§8.3). Accepting the fee `type=2` is part of *this* transition and is legal here and nowhere earlier (§8.5) |
| `committed` | `cancelled` / `expired` / `disputed` | as above |
| `awaiting_payment` | `paid` | **all** required receipts verified per §9.2, with "required" as in the row above |
| `awaiting_payment` | `cancelled` / `expired` / `disputed` | as above |
| `paid` | `released` | `kind:15` from the provider whose `order` tag names **this** order (§7.4), carrying key and nonce, with `x`, `ox`, `size` and `file-type` byte-identical to the commitment (§10.3) |
| `paid` | `disputed` | the injected clock passes `deliver_by` from the accepted terms and no `kind:15` carrying a decrypting key has been received. If the accepted terms carry no `deliver_by`, an implementation MUST apply and display a release timeout of its own and MUST NOT leave the order in `paid` indefinitely |
| `released` | `settled` | buyer's own computation of `x` **and** `ox` both match |
| `released` | `disputed` | either hash mismatches, or the blob does not decrypt |
| `released` | `disputed` | the injected clock passes the **verification deadline** with the buyer's own §10.4 verification neither succeeded nor failed. That deadline is the clock reading taken when the order entered `released`, plus a verification window the implementation applies and displays; where no reading was taken at release, the reading taken when the order entered `paid` plus that window; where neither reading was taken, `deliver_by` from the accepted terms plus that window. An implementation MUST move the order to `disputed` once that deadline has passed and MUST NOT leave it in `released` past it. Where no reading was taken at `paid` and none at release **and** the accepted terms carry no `deliver_by`, there is no deadline to check — as for a `proposed` order whose terms carry no `expiration` — and an implementation MUST surface that to the user as having no deadline rather than as pending |

![The order lifecycle](../docs/order-lifecycle.svg)

*Figure (non-normative): the states of §11.1 and the transitions of this table, drawn.*

Every unlisted transition MUST be refused. An implementation SHOULD model the transition function
as total: every (state, event) pair yields a new state or an explicit rejection.

A `kind:16` `type=6` private bid (§6.1) or `kind:14` chat message MUST advance no state, from any
key, and MUST NOT be treated as acceptance, cancellation or evidence (§7.6, §9.1).

The verification window in the second `released → disputed` row is local to each implementation,
not a wire term like `expiration` or `deliver_by`; different windows are equally conformant.

### 11.3 Invariants worth testing directly

1. Every path to `paid` passes through verified evidence; `proposed → paid` is unreachable.
2. No `kind:17` `payee=fee` receipt is accepted before `awaiting_payment` (§8.5); the fee payment
   request is accepted exactly once, in `committed → awaiting_payment`.
3. No transition leaves a terminal state.
4. `settled` is reachable only through the buyer's own hash computation.
5. A `type=3` status update claiming `paid` or `settled`, from any key, MUST NOT change state;
   only `cancelled` is accepted on a counterparty's assertion alone, and only before `paid`.

### 11.4 Where escrow would go

Escrow, if added, enters as a `held` state between `accepted` and `committed`, with a terminal
`refunded`. Transition logic MUST NOT enumerate "everything after `accepted`" in a way a new
state would silently join.

In v1 the buyer pays before receiving the key. Implementations SHOULD cap the value they transact
without escrow and SHOULD make that cap visible. Rationale: §11.4 of
[the companion](NENYA-1-companion.md).

---

## 12. Privacy requirements

These requirements are normative.

1. **No identifiers in the payment layer.** An order id, listing coordinate, `d` value, counterparty pubkey, title or value from a personal-data vault MUST NOT appear in a BOLT-11 description, description-hash preimage, LNURL metadata, wallet memo or blob-store filename.
2. **No identifiers in unencrypted events.** The values in item 1, and any invoice string, MUST NOT appear in a public event; implementations SHOULD check this automatically over every event they can construct.
3. **No static payment identifiers.** Each order uses its own invoices (§9.3).
4. **Separate keys.** The key that signs listings SHOULD NOT receive ecash or zaps; a P2PK receiving key MUST differ from the marketplace identity key.
5. **No location.** `g` and `location` MUST NOT be emitted (§5.3).
6. **No profile leakage.** Values from a local identity vault MUST NOT be written into a `kind:0` profile or any Nenya event; an implementation SHOULD enforce this at the type level.
7. **Randomised gift-wrap timing** (§7.1).
8. **Ephemeral wrapper keys are never identity** (§7.2).
9. **Minimal relay hints.** Implementations SHOULD keep relay hints in `a`/`p` tags minimal and MUST NOT include one the user marked private.
10. **No auto-fetch.** An implementation MUST NOT automatically download or render a URL from an unknown counterparty, including image tags, blob URLs and links in `content`.
11. **No secrets in diagnostics.** Key material, decryption keys, preimages and order ids MUST NOT appear in logs, crash reports, analytics or the string representation of any exposed value.

---

## 13. Security considerations

Nenya protects a user against counterparties and relays, not against the client that embeds it
and supplies the signer, wallet and transport; implementations MUST NOT claim otherwise in their
documentation. Threat model: §13 of [the companion](NENYA-1-companion.md).

Implementations MUST surface these residual risks: a paid provider can withhold the key (§11.4);
a blob host can disappear between commitment and download; a partitioned relay set can hide an
acceptance; a `kind:5` deletion is advisory.

---

## 14. What Nenya deliberately does not do

This list is normative: a system that does any of these does not implement NENYA-1.

1. **No custody.** No party holds another's funds; each payee is paid directly on its own invoice, never through a split invoice (§8.6).
2. **No escrow in v1.** No hold, multisig, 2-of-3 or locktime refund; §11.4 only reserves states for it.
3. **No arbitration.** No dispute resolution, arbiter role or appeal.
4. **No index server.** Discovery uses relay filters (§5.5); there is no Nenya search API, aggregator or canonical board.
5. **No relay, blob server, mint or push server** is operated or named by the protocol.
6. **No fee to the specification's author.** The only fee is an optional signed term between the transacting parties, naming its recipient; there is no protocol-level cut, default recipient or hardcoded pubkey in this document or any conformant implementation.
7. **No identity verification.** No KYC, real name, address, phone or email; the order payload has no field for such values, and implementations SHOULD enforce their absence by a structural check.
8. **No content scanning** or protocol-level moderation; reporting and muting ([NIP-56][nip56], [NIP-51][nip51]) are left to the client.
9. **No dependency on NIP-90 or NIP-15**; an optional, interface-isolated bridge letting a NIP-90 provider answer a Nenya request is a permitted extension.
10. **No NIP-04.**
11. **No zap receipts as evidence** (§9.1).
12. **No floating-point money** (§4.4).

---

## 15. Interoperability notes

`alt` on requests (§5.3) and human-readable bid text (§6) give NIP-99 and NIP-22 clients a
readable fallback; that text is display only (§5.4). A bid's `alt` SHOULD also state its price
and delivery terms (§6). Rendering details and the GammaMarkets
comparison: §15 of [the companion](NENYA-1-companion.md).

---

## 16. Decisions — closed and open

The rationale for each entry is in §16 of [the companion](NENYA-1-companion.md).

### 16.1 Closed decisions

An implementation MUST implement each decision below as written and MUST NOT expose it as
configuration, except client-local policy an entry permits (§8.1 fee limit, §6.1 private bidding).

#### `OPEN-1` — the request kind number — **CLOSED: `30404`**

The request kind is **`30404`** (§5.2). Registration is `OPEN-9`.

#### `OPEN-2` — a hard ceiling on fee basis points — **CLOSED: no ceiling below `10000`**

The fee range, rejection above `10000`, the client-local lower limit and the `500` bps
confirmation floor are in §8.1; signing is in §8.4, display in §8.2.

#### `OPEN-3` — public versus private bids as the default — **CLOSED: public is the default**

Public bidding (§6) is the default; private bidding (§6.1) is permitted (§17, item 3).

### 16.2 Still open

Each item below is reserved for a human maintainer. Implementations MUST surface `OPEN-4` through
`OPEN-7` as configuration or as an explicit "unsupported" state, never as a silent default, and
MUST document the value used.
`OPEN-8` is covered by §7.4's rule for unknown types; `OPEN-9` carries no implementation duty.

#### `OPEN-4` — ecash settlement evidence

Open; see §9.4.

#### `OPEN-5` — the `license` tag vocabulary

Open. Until resolved, `license` is free text and implementations MUST NOT act on its value.

#### `OPEN-6` — reputation

Open: whether v1 adopts a reputation primitive, and whose.

#### `OPEN-7` — binding the fee invoice to the fee recipient

Open; see §8.7.

#### `OPEN-8` — coordinating the `type=5` and `type=6` assignments

Open: upstream coordination only. Both assignments are normative and usable now (§10.1, §6.1).

#### `OPEN-9` — registering `30404` in the kind registry

Open: whether to file a `registry-of-kinds` pull request for `30404`.

### 16.3 Other decisions with known costs

These decisions are closed: `SAT`, not `btc`, as the price unit (§4.4); two kinds, not one kind
with a buy/sell discriminator (§5); rejecting, not resolving, duplicate single-cardinality tags
(§4.3); pay-on-delivery, not a hold-invoice pay-for-key exchange (§11.4).

---

## 17. Conformance

An implementation is **NENYA-1 conformant** if it:

1. computes and checks event ids per §4.1, and enforces the parsing and resource rules in
   §4.3;
2. emits and parses the tag vocabulary in §5.3 with the encodings given — including both
   arities of `fee` — applying that section's cardinality and requirement rules to listings
   only and taking the event kind as an input elsewhere (§5.3), strict on write and
   permissive on read for money (§4.4), with no floating-point amount anywhere;
3. implements **public** bidding — the default (§6) — and scopes those bids by **coordinate**,
   with `A`/`a` (never `E`/`e`), `K`/`k` and `P`/`p` all present and all matching the listing.
   Private bidding (§6.1) is OPTIONAL; an implementation that offers only private bidding is
   not conformant;
4. enforces the seal/rumor pubkey equality check (§7.2) and publishes a `kind:10050` (§7.3);
5. requires a signed fee term, computes the fee exactly as specified without overflow,
   refuses an inconsistent term (§8.4), refuses a fee **payment** before `awaiting_payment`
   while accepting the fee **payment request** as part of the `committed → awaiting_payment`
   transition and never earlier (§8.5), requires no invoice for a payee whose computed amount
   is `0` (§8.3), checks that a named fee recipient is reachable before proposing (§8.1), and
   refuses a combined invoice (§8.6);
6. advances to `paid` only on evidence it verified itself, per §9.2, having persisted the
   payment requests — **and their acceptance timestamps** — that evidence is checked against;
7. commits and verifies `x` and `ox` with the encryption parameters in §10.2 (§10);
8. implements the state machine as a total function and refuses every unlisted transition
   (§11);
9. emits none of the identifiers listed in §12 into a payment layer, a public event, or a log;
10. does none of the things in §14.

An implementation MAY omit BIP-340 signature verification and BOLT-11 node-key recovery and is
still conformant, provided it does not report unverified things as verified. Such an
implementation MUST expose a capability surface — a machine-readable statement of what it
verifies — and its UI MUST distinguish "signature verified" from "decrypted and structurally
valid".

---

## 18. Test vectors

A conformant implementation SHOULD be provable offline. The checks below are worth pinning to
externally-authored vectors rather than to the implementer's own understanding.

| Area | Vector source |
|---|---|
| Event id serialisation | NIP-01 examples plus hand-built cases covering all seven shortcut escapes, at least one `\u00XX` control character (§4.1), a `0x7f` DEL emitted verbatim, non-BMP characters, empty tags, and empty content; plus negative controls for text with no UTF-8 encoding — a lone high surrogate, a lone low surrogate and a reversed pair, each rejected (§4.1) rather than hashed. Ids for the hand-built cases SHOULD be cross-checked against `nostr-tools` and `go-nostr` rather than against the implementer's own reading of NIP-01 |
| Schnorr signatures | the BIP-340 CSV (19 cases), including the invalid ones |
| NIP-44 encryption | `nip44.vectors.json` (128 cases), SHA-256 `269ed0f69e4c192512cc779e78c555090cebc7c785b609e338a62afc3ce25040` |
| Gift wrap round trip | the two worked gift wraps in NIP-17's Examples section, plus NIP-59's worked seal and rumor. **What can be checked with no cryptography at all** — and therefore offline, by an implementation that delegates secp256k1 and NIP-44 to its embedding client — is that all four parse as event JSON under §7.1's object rules and that each one's `id` recomputes from §4.1's serialisation. That is the half of the envelope this document defines; decrypting them needs keys the examples do not publish. Note that NIP-59's worked **wrap** carries a trailing comma and is not strict JSON: it is a documentation example, not a vector, and a parser that accepts it is a parser that accepts malformed input from a relay. Use NIP-17's two for the wrap layer. Bound checks — 65 535-byte plaintext, 87 472-character wrap `content`, 40 960-byte rumor JSON (§4.3) — are derivable from NIP-44's own `calc_padded_len` vectors and need no keys either |
| BOLT-11 parsing | the BOLT-11 Examples appendix |
| Preimage verification | invoices with known preimages, **plus** negative controls: wrong preimage, 31-byte preimage, 33-byte preimage, uppercase hex, preimage of the wrong invoice |
| Fee arithmetic | boundary cases at `bps = 0`, `bps = 10000`, the msat rounding example in §8.3, an overflow probe at the supply cap, and the non-zero-`bps`-but-zero-`fee_msat` case (`bps = 1`, `price_msat = 3000`) |
| State machine | every legal transition, **and** an enumeration proving every unlisted pair is refused. Include as named cases: a fee-bearing order reaching `awaiting_payment` and then `paid` (the §8.5 deadlock probe); an order whose `fee_msat` computes to `0` reaching `paid` with no fee invoice (the §8.3 deadlock probe); a `type=1` with no `fee` tag opening at zero fee; a `type=6` and a `kind:14` each advancing nothing from every state; an order in `released` whose served blob was refused for its length — which is no hash mismatch (§10.4) — reaching `disputed` at the verification deadline, probed one second before the deadline, at it exactly, and one second after; the same order under each fallback anchor in turn, with the candidate deadlines chosen so that anchoring on the wrong one would be visible; and an order with no clock reading at `paid`, none at release and no `deliver_by`, reporting that it has no deadline to check rather than that it is pending |

Vectors that only prove the happy path prove very little. Every check above whose failure
would cost a user money SHOULD have a negative control demonstrating that the check rejects
what it must reject.

---

## Appendix A — a complete worked order

![One trade, message by message](../docs/trade-sequence.svg)
*Figure (non-normative): one priced trade carried from request to `settled`. The step-by-step
worked order, with the money, is Appendix A of [the companion](NENYA-1-companion.md) — an
example, not a rule.*

---

## Appendix B — versioning

- The `["nenya", "<n>"]` tag carries the major version. This document is `1`. Revisions
  within a major version (`1.0`, `1.1`, …) are recorded in §0 and do **not** appear on the
  wire: closing an OPEN item, recording a rationale, or tightening prose that was already
  normative is a revision, not a version bump.
- A change that alters the meaning of an existing tag, removes a tag, changes the fee
  arithmetic, changes the evidence rule, or adds a state to the machine is a **major** change
  and increments the version.
- Adding an optional tag, adding a `kind:16` `type` value, or specifying a new payment rail's
  evidence rule is **minor** and does not increment the version; implementations MUST ignore
  what they do not recognise.
- The request kind is `30404` (§5.2) and MUST NOT change after the first public release. If
  it must, that is a new major version and a migration, not an edit.

---

## Appendix C — the BOLT-11 fields Nenya requires

[BOLT-11][bolt11] is normative for everything here.

![A BOLT-11 invoice, field by field](../docs/bolt11-anatomy.svg)
*Figure (non-normative): the anatomy of a BOLT-11 invoice and the fields Nenya reads.*

An invoice is bech32 without the 90-character limit: a human-readable part (HRP), separator `1`,
data part of 5-bit groups, 6-character checksum. The separator is the last `1` (`lnbc1500n1…`).

Nenya refuses an uppercase invoice and never normalises it (§4.3, §9.2 check 1); a sender
lowercases it before putting it in a `payment` tag.

**1. Amount.** The HRP is `ln` + a network prefix (`bc`, `tb`, `bcrt`, …) + an OPTIONAL amount, a
decimal integer followed by a multiplier:

| Multiplier | Meaning | msat per unit |
|---|---|---|
| `m` | milli (10⁻³ BTC) | 100 000 000 |
| `u` | micro (10⁻⁶ BTC) | 100 000 |
| `n` | nano (10⁻⁹ BTC) | 100 |
| `p` | pico (10⁻¹² BTC) | 0.1 |
| *(none)* | whole BTC | 100 000 000 000 |

A present amount MUST be a positive decimal integer with no leading zeroes; `lnbc0…` and
`lnbc0500u…` MUST be rejected. A `p` amount MUST be a multiple of 10. An invoice with no amount
MUST be rejected (§9.2 check 4). §4.4's arbitrary-precision rule applies; the supply cap,
21 000 000 000 000 000 000 pico-BTC, exceeds signed 64-bit range, so a 64-bit implementation
divides a `p` amount by 10 before multiplying.

**2. Timestamp.** First 35 bits (7 bech32 characters) of the data part, big-endian, Unix seconds.

**3. Tagged fields.** The rest of the data part, up to the 104-character signature, is a sequence
of `(type: 5 bits, length: 10 bits in two 5-bit groups, data: length × 5 bits)`. A field of the
wrong length for its type MUST be skipped as an unknown type and MUST NOT reject the invoice.

Nenya reads six fields and requires three:

- `p` (type 1): **payment hash**, `length == 52`; the hash is the first 256 of its 260 bits.
  Required exactly once (§4.3); a `p` of any other length is skipped and does not count.
- `s` (type 16): **payment secret**, `length == 52`. Required; its value is not read or published.
- `d` (type 13, variable length) and `h` (type 23, `length == 52`): **description** and
  **description hash**. Exactly one is required.
- `x` (type 6): **expiry** in seconds, big-endian over its data groups. OPTIONAL; default **3600**.
  A second `x` MUST be rejected. The invoice is expired when `timestamp + expiry` is earlier than
  the clock value persisted at invoice acceptance (§8.6), never the clock at receipt verification
  (§9.2 check 5).
- `c` (type 24): `min_final_cltv_expiry_delta`. OPTIONAL; default **18**. Nenya does not act on it;
  a reader MAY publish it. If repeated, the first wins.
- `9` (type 5): **feature bits**, big-endian over the field, numbered from its least significant
  bit. OPTIONAL; absent means no bit is set; if repeated, the first wins. Nenya applies no
  feature-bit rule; a reader MAY publish which bits are set.

An `x` value, and every `c` value including a repeat, MUST be rejected when it is 2^64 or more.
The bound applies to the value, not the field width: a 13-group field holding 2^64 − 1 is legal.
An implementation with signed 64-bit integers MUST NOT let such a value read as negative;
saturating at the largest representable value is the fail-closed reading. Every other tagged
field MUST be skipped by its length.

**4. Signature.** The final 104 characters (512-bit signature + 1 recovery byte) over the HRP and
data part. Verifying it and recovering the node key are OPTIONAL in v1 (§9.2). An implementation
that skips them MUST still parse past the signature and MUST verify the bech32 checksum.

**What a reader MUST do, and what Nenya does not do.** An implementation reading an invoice for
Nenya MUST: verify the bech32 checksum; split at the last `1`; apply the HRP and amount rules above;
require `s`; require exactly one of `d` and `h`; require exactly one correct-length `p`; refuse an
`x` or `c` value of 2^64 or more; and refuse a data part shorter than 117 characters (7-character
timestamp, 104-character signature, 6-character checksum).

It MUST NOT report as performed signature verification, node-key recovery, the low-S rule, or any
judgement of the feature bits; Nenya performs none of them (§17). An implementation MAY publish the
signature bytes, the recovery id, the SHA-256 of the signed data and the feature bits.

Everything Nenya's evidence rule needs — the payment hash, the amount, the expiry — is
therefore reachable with bech32 decoding, bit-slicing and SHA-256 alone.

---

[nip01]: https://github.com/nostr-protocol/nips/blob/master/01.md
[nip09]: https://github.com/nostr-protocol/nips/blob/master/09.md
[nip11]: https://github.com/nostr-protocol/nips/blob/master/11.md
[nip15]: https://github.com/nostr-protocol/nips/blob/master/15.md
[nip17]: https://github.com/nostr-protocol/nips/blob/master/17.md
[nip19]: https://github.com/nostr-protocol/nips/blob/master/19.md
[nip22]: https://github.com/nostr-protocol/nips/blob/master/22.md
[nip31]: https://github.com/nostr-protocol/nips/blob/master/31.md
[nip40]: https://github.com/nostr-protocol/nips/blob/master/40.md
[nip44]: https://github.com/nostr-protocol/nips/blob/master/44.md
[nip51]: https://github.com/nostr-protocol/nips/blob/master/51.md
[nip55]: https://github.com/nostr-protocol/nips/blob/master/55.md
[nip56]: https://github.com/nostr-protocol/nips/blob/master/56.md
[nip57]: https://github.com/nostr-protocol/nips/blob/master/57.md
[nip59]: https://github.com/nostr-protocol/nips/blob/master/59.md
[nip69]: https://github.com/nostr-protocol/nips/blob/master/69.md
[nip90]: https://github.com/nostr-protocol/nips/blob/master/90.md
[nip94]: https://github.com/nostr-protocol/nips/blob/master/94.md
[nip99]: https://github.com/nostr-protocol/nips/blob/master/99.md
[bip340]: https://bips.xyz/340
[bolt11]: https://github.com/lightning/bolts/blob/master/11-payment-encoding.md
[gamma]: https://github.com/GammaMarkets/market-spec/blob/main/spec.md
[rfc2119]: https://www.rfc-editor.org/rfc/rfc2119
[rfc8259]: https://www.rfc-editor.org/rfc/rfc8259
