NENYA-1
=======

Nenya Marketplace Microstandard
-------------------------------

`draft` `optional`

A microstandard for a two-sided, non-custodial marketplace on nostr, in which one party
posts a REQUEST for digital media ("I want an image / song / video of X") or an OFFER to
produce it ("I will make X"), and the two settle directly in bitcoin.

Nenya is a library, not an application and not a service. This document is the contract;
`nenya-core` is one reference implementation of it. Everything here is defined in terms of
JSON events, tags, byte strings and integer arithmetic. An implementation in any language,
on any nostr stack, that follows this document interoperates with `nenya-core` without
sharing a line of code, and no part of this document requires reading the reference
implementation to understand.

---

## 0. Status of this document

This is NENYA-1, version `1`, **revision `1.7`** of the Nenya wire format. It is a draft:
nothing in it is deployed, and the identifiers it reserves are not registered.

**Revision history**

| Revision | Date | Change |
|---|---|---|
| `1.0` | 2026-09-09 | First published revision. Eight items left OPEN. |
| `1.1` | 2026-09-09 | `OPEN-1`, `OPEN-2` and `OPEN-3` **closed** by the maintainer. The request kind is fixed at `30404`; the fee basis-point rule is fixed at "legal 0–10000, mandatory disclosure, no protocol ceiling below 10000"; public bidding is the default. Five items remain open (`OPEN-4`–`OPEN-8`), keeping their original numbers. |
| `1.2` | 2026-09-09 | Consistency repair, no new wire semantics. Canonical JSON escaping (§4.1) aligned with the deployed ecosystem — the seven shortcut escapes, every other character below `0x20` as `\u00XX` — recorded as a deliberate, reasoned deviation from NIP-01's literal wording. Twenty internal contradictions resolved, chiefly: the fee-invoice deadlock (§8.5 vs §11.2), the zero-`fee_msat` deadlock (§8.3 vs §11.1), the simultaneously mandatory and optional `fee` tag (§7.5 vs §8.1), the undefined "release deadline" (§11.2), the unbound `kind:15` release (§7.4, §10.3), and the `m`/`file-type` identity (§10.1 vs §10.3). `kind:16` `type=6` assigned to the private bid, closing the §6.1 interop hole; `item` given a normative row in §5.3. The confirmation floor above `500` bps (§8.1) is recorded as a **standing disclosure duty, promoted from interim as part of the `OPEN-2` closure**. `OPEN-8` broadened to cover `type=6`; new `OPEN-9` (registering `30404`) gives the previously unnumbered publication question a number. Two example timestamps corrected (§6, §7.5). |
| `1.3` | 2026-09-16 | Text with no UTF-8 encoding (§4.1). A `content` or tag value containing an unpaired UTF-16 surrogate MUST now be **rejected**, never serialised with a substituted replacement character: platforms substitute differently (`?` on the JVM, U+FFFD in JavaScript), so substitution gave one event two ids. Stated in §4.1, cross-referenced from §4.3, and added to §18's event-id vectors. |
| `1.4` | 2026-09-17 | Appendix C corrected against BOLT-11 itself, and completed as a reader specification. Three errors of fact repaired: BOLT-11 does **not** define invoices as all-lowercase (it prescribes uppercase for QR codes and its own example 13 is all uppercase), so refusing an uppercase invoice is stated as **Nenya's** rule and not as BOLT-11's (§4.3, Appendix C); a tagged field of the wrong length is **skipped** as unknown rather than rejecting the invoice, which is BOLT-11's own reader rule; and the payment secret (`s`) is now REQUIRED. Appendix C additionally states, rather than leaves to the reference implementation, the reader rules Nenya enforces, and states which BOLT-11 rules Nenya does **not** perform and MUST NOT report as performed. No tag meaning, fee arithmetic or state changes. |
| `1.5` | 2026-09-19 | Four refusals stated where the document previously named a sender, or presumed a uniqueness, without saying what to do about anything else. §7.6: an implementation MUST know the provider's key independently of the proposal and MUST reject a `type=3` acceptance sealed by any other key, the buyer's included. §8.6: a `payee=provider` `type=2` MUST arrive under a seal whose pubkey is that same provider key, the counterpart to §8.7's rule for the fee side; a second `type=2` for an `(order, payee)` already accepted MUST be rejected rather than replacing the first; an invoice already accepted for one payee on an order MUST be rejected for any other payee on that order; and a `type=2` whose invoice has already expired at the moment it would be accepted MUST be rejected then rather than at settlement. No tag meaning, fee arithmetic or state changes. |
| `1.6` | 2026-09-23 | A **verification deadline** out of `released`, stated as a second `released → disputed` row in §11.2. An order whose buyer never completes §10.4 previously sat in `released` for ever: a blob refused for its length or for the download bound is not a hash mismatch, so no trigger existed for it, and neither did one for a dead URL or a buyer who simply never looks. The deadline is **local** rather than a new wire term — the clock reading taken at release plus a window the implementation applies and displays, falling back to the reading taken at `paid` and then to `deliver_by` — and where none of the three was ever recorded the order reports that it has no deadline rather than that it is pending. §11.2's "there is no third deadline" paragraph is replaced accordingly; §10.4, §11.4 and §18 record the consequence. No tag meaning, fee arithmetic, evidence rule or state changes: a trigger is added to a `(from, to)` pair the table already carried. |
| `1.7` | 2026-09-23 | The gift-wrap envelope, written down as a procedure an implementer can follow, and the bounds that make it representable. §4.3's default bounds **refused real gift wraps**: NIP-44 pads a plaintext to a power-of-two-derived size, so a rumor's JSON grows twice on the way out, and a wrap's `content` passed the 16 KiB default once the rumor's JSON exceeded 7 168 bytes — a conformant implementation had to refuse to open a 10 KB chat message. A `kind:13` seal and a `kind:1059` wrap are therefore bounded by NIP-44 instead: decrypted plaintext at most 65 535 bytes, wrap `content` at most 87 472 characters, rumor JSON at most 40 960 bytes on write. The plaintext limit follows the official NIP-44 vectors and every deployed application rather than the extended-length text a later NIP-44 revision added (§4.3). §7.1 is restated as a numbered write procedure and a numbered read procedure, with six refusals it previously left unstated; §3 gains the one-time signer seam the wrap needs; §4.1 states what a nostr signature is over; §7.2 says when a message MAY be reported as signature-verified; §18 records what the gift wrap can be checked against offline. No tag meaning, fee arithmetic, evidence rule or state changes. |

Revision `1.1` changes no tag meaning, no fee arithmetic, no evidence rule and no state, so
the `["nenya", "1"]` version tag is unchanged and revision `1.0` and revision `1.1` are wire
compatible in every respect except that a `1.0` implementation had no request kind to emit.
Per Appendix B, closing an OPEN item is not by itself a major change.

Revision `1.2` likewise changes no tag meaning, no fee arithmetic, no evidence rule and no
state, and adds one `kind:16` `type` value — which Appendix B classifies as **minor**. The
`["nenya", "1"]` version tag is therefore unchanged. Two consequences are worth naming
precisely rather than burying:

- The §4.1 escaping correction changes the computed `id` of an event whose `content` or tag
  values contain a character below `0x20` other than the seven NIP-01 names. It changes no
  other event's id. Such an event had **no** valid serialisation under revision `1.1`'s
  literal reading — the output was not JSON — so no interoperating implementation can have
  produced one. §4.1 states the reasoning in full.
- A revision `1.1` implementation does not know `type=6` and, per §7.4, ignores it. A
  private bid therefore degrades to *not received*, never to a misparsed order message. That
  is the same failure a missing `kind:10050` produces (§7.3), and it is why §17 requires the
  public bidding path.

Revision `1.3` changes no tag meaning, no fee arithmetic, no evidence rule and no state; it
makes a rule of §4.1's that was already implied — the id is over UTF-8 bytes, and some strings
have none — explicit. The `["nenya", "1"]` version tag is unchanged. It changes no computed id:
an event it newly rejects is one whose id no two implementations could agree on, because its
serialisation has no UTF-8 encoding and every substitute byte sequence was an implementation
accident rather than NIP-01.

Revision `1.4` changes no tag meaning, no fee arithmetic and no state, and the
`["nenya", "1"]` version tag is unchanged. It does **not** claim to change no evidence rule:
two of its edits change which invoices §9.2's checks can read, and both are named here rather
than left to be discovered.

- **A tagged field of the wrong length is skipped as unknown, not rejected.** Appendix C
  previously said an implementation "MUST reject" an invoice whose `p` field has
  `length != 52`. That is not BOLT-11's rule: BOLT-11 requires a reader to skip any field
  whose length is not the one its type calls for, every deployed Lightning wallet does so, and
  BOLT-11's own valid example 14 is an invoice carrying eight such fields *on purpose*. An
  implementation following the old text refused an invoice real wallets pay. **Evidence is not
  weakened by the correction.** §9.2 check 3 still reads exactly one 52-group `p`: an invoice
  with no correct-length `p`, and an invoice with a second one, are both still rejected — so
  the payment hash a receipt is checked against is still the single unambiguous one the paying
  wallet used.
- **The payment secret (`s`) is now REQUIRED**, which rejects an invoice the old text
  accepted. BOLT-11 requires the field, every deployed wallet refuses an invoice without one,
  and BOLT-11's own list of *invalid* examples contains one whose only defect is its absence.
  An implementation that stored such an invoice would be holding a bill no wallet will pay.

Both corrections follow the precedent §4.1 set for canonical JSON escaping: where the letter
of a document and every working implementation disagree, interoperability wins, and the
reasoning is written down once in the place it applies.

Everything else Appendix C gains in revision `1.4` is a rule Nenya's implementation already
had to apply in order to read an invoice at all — the bech32 checksum, the last-`1` split, the
amount rules, the data-part floor — written down so another implementer need not re-derive it,
plus an explicit statement of what Nenya does **not** check. Neither adds an obligation on a
sender, so no conformant invoice becomes non-conformant because of them.

Revision `1.5` changes no tag meaning, no fee arithmetic and no state, and the
`["nenya", "1"]` version tag is unchanged. It adds no field, no tag and no message; each of
its four edits states, as a refusal, something an earlier revision had already named as a
fact about the sender or presumed about uniqueness. Per Appendix B that is a **tightening**
and not a version change: an implementation conformant with revision `1.4` emits nothing
revision `1.5` rejects, because §7.4's Sender column and §7.6's "from the provider" already
said who sends each of these messages. What changes is that a *receiver* now has a stated
obligation to check.

- **§7.6 — the acceptance's sealing key.** §7.6 already called acceptance "a `type=3` status
  update **from the provider**" and §11.2's `proposed → accepted` row already read "from the
  provider's key". Neither said what to do with one sealed by another key, and an
  implementation that compared only the four terms would accept a buyer's own `type=3`
  carrying byte-identical terms — the buyer accepting its own order.
- **§8.6 — the provider invoice's sealing key.** §7.4's Sender column gives a `type=2` as
  "provider, or fee recipient", and §8.7 turned that into a refusal for the fee side alone.
  The provider side is now stated in the same shape.
- **§8.6 — one accepted `type=2` per `(order, payee)`, and one invoice per order.** §9.2
  check 1 compares a receipt against *the* stored payment request, which presumes there is
  exactly one; nothing said so. The two refusals that follow from it — a replacement, and one
  invoice offered as two payees' — are now written down.
- **§8.6 — an expired invoice is refused when it arrives.** §9.2 check 5 already required an
  invoice to be live at the moment it was accepted, but stated the consequence at settlement,
  where the buyer may already have paid. It is now also a rule about accepting the `type=2`.

The third is the only one that refuses a flow somebody might have built: a provider that
re-sends its invoice, for instance because the first went unanswered, now has the second
rejected rather than silently replacing the first. That is deliberate and §8.6 states the
reasoning in place. An invoice that expires unpaid is not re-issued within the order.

Revision `1.6` changes no tag meaning, no fee arithmetic, no evidence rule and no state, and
the `["nenya", "1"]` version tag is unchanged. It adds no field, no tag, no message and no
status token. The row it adds to §11.2 is a second **trigger** for the `released → disputed`
pair that table already carried, so the set of legal `(from, to)` transitions is exactly
revision `1.5`'s; what changes is that an order sitting in `released` now has a stated moment
at which it leaves. The deadline itself is **not** a wire value: it is computed from readings
of the implementation's own clock and a window the implementation chooses, so no sender emits
it, nothing about it can be misparsed by a revision `1.5` reader, and two conformant
implementations may pick different windows exactly as §11.2 already permits them to for an
order with no `deliver_by`. A revision `1.5` implementation reading a `1.6` implementation's
events sees the same events.

The row deliberately claims less than "an order never stays in `released` indefinitely". Where
no clock reading was taken when the order became `paid`, none was taken at release, and the
accepted terms carry no `deliver_by`, there is nothing the deadline can run from and §4.6
forbids substituting a time from anywhere else. That order does stay in `released` — and an
implementation MUST tell its user that it has no deadline to check rather than showing it as
pending, which is the same treatment §11.2 already gives a `proposed` order whose terms carry
no `expiration`.

Revision `1.7` changes no tag meaning, no fee arithmetic, no evidence rule and no state, and
the `["nenya", "1"]` version tag is unchanged. It adds no field, no tag, no message and no
status token. Everything it does is confined to the envelope every private message already
travelled in, and three of its consequences are worth naming rather than leaving to be
discovered.

- **A bound is raised, and it is raised because the old one was unusable.** §4.3's 16 KiB
  default for `content` is right for an ordinary event and wrong for a `kind:1059`, whose `content`
  is a base64 NIP-44 payload over a *second* NIP-44 payload. Each layer prefixes a version
  byte, a 32-byte nonce and a 2-byte length, pads the plaintext to a power-of-two-derived size
  and appends a 32-byte MAC, and base64 then costs a further third — so a rumor of 7 169 bytes
  of JSON produced a wrap an implementation obeying §4.3 had to refuse. Raising the bound for
  these two kinds refuses nothing that was accepted before; it accepts wraps that every
  deployed NIP-17 client already emits. The **rumor** keeps §4.3's ordinary defaults, so the
  content a user actually writes is bounded exactly as it was.
- **The rumor bound is a write-side rule.** An implementation MUST NOT *emit* a rumor whose
  JSON exceeds 40 960 bytes, because anything from 40 961 upwards pads to a seal whose JSON
  exceeds NIP-44's plaintext limit and therefore cannot be wrapped at all. Stating it as a
  refusal on write turns an unrepresentable message into an error at the sender rather than a
  wrap the recipient cannot open.
- **Six refusals §7.1 previously left unstated are now MUSTs**: a duplicate key in event JSON,
  a rumor carrying a `sig` key, a seal with non-empty `tags`, a wrap of any kind other than
  `1059`, a wrap whose single `p` tag is not the reader's own key, and the rumor size bound
  above. Each refuses input the old text never permitted — NIP-59 already required an unsigned
  inner event and an empty-tagged seal — so an implementation conformant with revision `1.6`
  emits nothing revision `1.7` rejects. Per Appendix B that is a **tightening** and not a
  version change.

Revision `1.7` follows the same interoperability precedent §4.1 set for canonical JSON
escaping and revision `1.4` set for Appendix C. NIP-44's vendored text now describes an
extended six-byte length prefix; its own published vectors do not, listing 65 536 among the
plaintext lengths encryption MUST refuse, and no deployed application emits the extended form.
Where the letter of a document and every working implementation disagree, interoperability
wins: §4.3 follows the vectors.

Some decisions are still **not yet made**. They are marked `OPEN-n` inline and indexed in
§16.2; the ones already closed are recorded with their rationale in §16.1. An OPEN item is a
decision reserved for a human maintainer. An implementation MUST NOT
silently pick a value for an OPEN item and present the result as NENYA-1 conformant; it
MUST surface the choice as configuration, or as an explicit unsupported state, and document
the value it used. `OPEN-9` is the one exception to that duty and says so in its own entry:
it is a publication decision that constrains nothing an implementation does.

The request kind is **no longer** such an item. As of revision `1.1` the Nenya request kind
is the literal `30404` (`OPEN-1`, closed; rationale recorded in §16.1). Revision `1.0`
carried a placeholder token in that position; revision `1.1` substituted `30404` for it
throughout, and no placeholder for the request kind survives anywhere in this document.
**Every example here is now copy-pasteable**, with the sole exception of the deliberately
truncated order ids, pubkeys and invoice strings written as `<…>`, which remain stand-ins
for readability. Every worked number and every timestamp relationship in this document has
been recomputed for revision `1.2`; a fixture author may take any of them at face value.

Where this document and a referenced NIP disagree, the NIP wins for the parts of the event
that NIP defines, and this document wins for the parts Nenya adds. Nenya adds tags; it
never redefines an existing tag's meaning.

**There is exactly one exception, and it is stated rather than left implicit: §4.1's
canonical JSON escaping.** There this document overrides the literal wording of NIP-01,
because that wording emits invalid JSON and computes an event id no relay agrees with, while
every deployed implementation does what §4.1 specifies. §4.1 records the reasoning and names
the interoperability authority. An exception that has to be argued for in full, once, in the
section it applies to is the only kind this document permits.

Normative references: [NIP-01][nip01] (events, ids, tags, kinds, filters), [NIP-09][nip09]
(deletion requests), [NIP-11][nip11] (relay information), [NIP-17][nip17] (private direct
messages), [NIP-19][nip19] (bech32 entities), [NIP-22][nip22] (comments), [NIP-31][nip31]
(`alt`), [NIP-40][nip40] (expiration), [NIP-44][nip44] (encryption), [NIP-59][nip59] (gift
wrap), [NIP-94][nip94] (`x`, `ox`, `m`, `size`), [NIP-99][nip99] (classified listings),
[BIP-340][bip340] (Schnorr signatures), [BOLT-11][bolt11] (Lightning invoices),
[RFC 2119][rfc2119], [RFC 8259][rfc8259] (JSON — normative for §4.1's string escaping).

Informative references: the [GammaMarkets market-spec][gamma] (order and receipt
vocabulary), [NIP-69][nip69] (prior art for a two-sided board on one addressable kind),
[NIP-90][nip90] (prior art for paid job requests; **do not build on it** — it is marked
`unrecommended` upstream and its kind registry is archived), [NIP-15][nip15] (**do not
build on it** — marked `unrecommended` upstream, and its checkout rests on the deprecated
NIP-04).

---

## 1. Motivation and scope

NIP-99 gives nostr a good-enough classified listing: an addressable event that says *this
thing is for sale*. It gives nostr nothing at all for the other half of a marketplace.
There is no want-to-buy convention anywhere in the NIPs, the kind registry, or any shipping
client; every implementation surveyed is seller-side only. A board with only one side is a
catalogue, not a market.

NIP-99 also stops at the ad. It has no vocabulary for a bid, an order, a delivery, a
payment, a fee, or a dispute. Everything past "here is a thing, here is a price" is
currently either invented per-client or borrowed from NIP-15, which is deprecated and whose
checkout rides deprecated encryption.

Nenya fills exactly that gap, and no more:

1. A **request** kind that mirrors NIP-99's vocabulary, so both sides of the board share
   one parser, one renderer, and one set of tests.
2. A **bid** carried as a NIP-22 comment scoped to an addressable coordinate, so a bid
   survives its listing being edited. Bidding is **public by default** (§6); the same terms
   MAY instead travel privately over the order channel (§7).
3. A **private order channel** over NIP-17 gift wrap for terms, delivery and settlement, so
   no order metadata reaches a public relay.
4. A **signed fee term**, so a client that takes a handling fee cannot take it silently.
5. A **payment-evidence rule**, so no party and no injected component can advance an order
   by asserting that money moved.
6. A **hash commitment** on the deliverable, so a provider cannot swap the file after being
   paid.

Out of scope, permanently or for v1: custody, escrow, arbitration, indexing, hosting,
identity verification, content moderation policy, and any protocol role for the author of
this document. See §14.

The first target audience is people without access to generative media tooling paying
people who have it. Nothing in the wire format is specific to that audience; "digital
media" is an `m` (MIME type) tag, not a hardcoded assumption.

---

## 2. Terminology and conventions

The key words "MUST", "MUST NOT", "REQUIRED", "SHALL", "SHALL NOT", "SHOULD", "SHOULD NOT",
"RECOMMENDED", "MAY", and "OPTIONAL" in this document are to be interpreted as described in
RFC 2119.

- **Board** — the set of public Nenya events (requests, offers, bids) on some set of relays.
- **Request** — a public, addressable event of `kind:30404` saying what a buyer wants and
  what they will pay.
- **Offer** — a public NIP-99 `kind:30402` classified listing saying what a provider will
  produce and for how much.
- **Listing** — a request or an offer, where the distinction does not matter.
- **Bid** — a NIP-22 `kind:1111` comment proposing terms against a listing. Public by
  default (§6); a **private bid** carries the same terms as a §7 message instead.
- **Buyer** — the party who pays. The author of a request; the responder to an offer.
- **Provider** — the party who delivers. The author of an offer; the responder to a request.
- **Fee recipient** — an optional third party named in the order terms who receives a
  handling fee. Typically the operator of one of the two clients. Never a party to the
  **delivery** — but, when the fee is non-zero, necessarily a participant in the order's
  sealed conversation, because they must issue their own invoice from their own key (§8.7)
  and must therefore be reachable over NIP-17 (§7.3, §8.1).
- **Order** — a private negotiation and settlement thread between exactly one buyer and one
  provider, identified by an *order id*.
- **Terms** — the tuple (item coordinate, price, fee bps, fee recipient, deadlines) that
  both parties have accepted.
- **Evidence** — a value an implementation verifies **itself**, by computation, without
  trusting the party or component that supplied it. A boolean, a status string, or an
  assertion by a wallet, a relay, a signer or a counterparty is **not** evidence.
- **Coordinate** — an addressable event address in the NIP-01 form
  `<kind>:<pubkey-hex>:<d-value>`.
- **Implementation** — the software implementing this document. **Client** — the
  application that embeds it. They are distinct: §13 turns on the difference.

---

## 3. Roles and the seams between them

A Nenya implementation is a library embedded in a client. It obtains five things from that
client and MUST treat all five as untrusted with respect to state:

| Seam | Supplies | What Nenya MUST NOT accept from it |
|---|---|---|
| Signer | public key, event signatures, NIP-44 encrypt/decrypt | any claim about *what* was signed, beyond the bytes returned |
| One-time signer | a signer over a **freshly generated** keypair, used for exactly one gift wrap | any claim that the keypair is fresh. Freshness cannot be observed from outside the seam — it is the client's contract to keep, and §17 forbids reporting it as checked. What an implementation does verify itself is narrower, and is exactly what §7.1 states: that the key equals neither party's key, and that it differs between the two copies |
| Relay transport | events in, events out | any claim that an event is valid, current, or complete |
| Wallet | "pay this invoice", invoices for the user's own funds | any claim that a payment succeeded (see §9) |
| Clock and randomness | current time, random bytes | nothing structural; but every deadline MUST be evaluated against this clock, never against a counterparty's `created_at` (§4.6) |

The one-time signer is a seam of its own and not a mode of the signer above, because a
`kind:1059` gift wrap is both **signed** and **NIP-44-encrypted** with the throwaway key
(§7.1): the same key must produce a BIP-340 signature and a NIP-44 conversation key with the
addressee. A seam that returned only a signature could not do the second, and one that
returned the secret key could do both at the cost of the property this table exists to
protect. **No secret key crosses this boundary in either direction.** The implementation
asks the seam for the throwaway public key, for a signature, and for an encryption; it never
sees, stores or logs the secret behind them (§12, §14).

This is not defensive style. It is the reason the payment-evidence rule and the signed fee
term exist: both are cases where the natural, convenient design is to believe an injected
component, and both are cases where believing it loses the user money.

Signing is deliberately abstract. An in-process key, a [NIP-55][nip55] Android signer app,
or a remote signer are all conformant. Nothing in this document depends on where the secret
lives.

---

## 4. Preliminaries

### 4.1 Event ids and signatures

Nenya events are ordinary nostr events. The `id` is the SHA-256 of the UTF-8 bytes of the
NIP-01 canonical serialisation

```
[0,<pubkey hex>,<created_at number>,<kind number>,<tags>,<content>]
```

with no insignificant whitespace anywhere. Inside a string value — `content` and every tag
value alike — an implementation MUST emit exactly this and nothing else:

1. the seven shortcut escapes NIP-01 names, and only these seven, for the characters they
   name: `\n` (0x0A), `\"` (0x22), `\\` (0x5C), `\r` (0x0D), `\t` (0x09), `\b` (0x08),
   `\f` (0x0C);
2. every **other** character below `0x20` as `\u00XX`, where `XX` is that byte's value in two
   **lowercase** hexadecimal digits — so `0x00` is `\u0000`, `0x01` is `\u0001`, and `0x1f`
   is `\u001f`;
3. every character at or above `0x20` verbatim, as UTF-8 bytes.

In particular, an implementation MUST NOT `\u`-escape any character at or above `0x20`
— not non-ASCII characters, and not `0x7f` (DEL, which is above `0x20`) — MUST NOT
escape `/`, MUST NOT emit uppercase hex digits in a `\u` escape, MUST NOT use the
six-character `\u000a`-style form for any of the seven characters rule 1 covers, and
MUST NOT reorder or normalise tags. Rule 1 takes precedence over rule 2 wherever both
could apply.

> **This is a deliberate, reasoned deviation from NIP-01's literal wording, and it is the
> only place in this document where Nenya does not follow a NIP as written.**

NIP-01 lists the seven shortcut escapes and then says every other character is emitted
verbatim. Read literally, that instructs an implementation to place a raw `0x01` — or a raw
`0x1f` — inside a JSON string. RFC 8259 forbids exactly that: characters `0x00`–`0x1f` MUST
be escaped. So the literal reading has two fatal properties. It emits a byte sequence that
is **not JSON**, which a conformant JSON parser at the receiving relay rejects; and, because
every deployed implementation escapes those characters anyway, the literal reading computes
a **different event id** from the rest of the network for the same event — meaning a Nenya
implementation following it would produce events every relay considers forged, and would
consider every relay's events forged in turn.

The interoperability authority here is the deployed code, not the prose: **`nostr-tools`**
(the reference TypeScript stack, whose serialiser is `JSON.stringify` over the array, and
`JSON.stringify` emits `\u00XX` in lowercase for exactly the control characters not covered
by the seven shortcuts) and **`go-nostr`** (the reference Go stack, whose hand-written
escaper does the same thing explicitly). They agree with each other byte for byte, they are
what the network actually computes ids against, and rules 1–3 above are a precise statement
of what they do. An implementation that follows this section interoperates; one that follows
NIP-01's sentence literally does not.

The deviation is narrow by construction. For any event containing no character below `0x20`
other than the seven — which is every event in this document, and every event any normal
listing, bid or order produces — the two readings are byte-identical and the deviation is
unobservable. Implementations SHOULD carry a test vector for each of the seven shortcuts and
at least one for a `\u00XX` control character, per §18.

**Text with no UTF-8 encoding MUST be rejected.** The id is over UTF-8 bytes, so every string
value must be a sequence of Unicode scalar values. A `content` or tag value containing an
**unpaired UTF-16 surrogate** — a high surrogate (`U+D800`–`U+DBFF`) not immediately followed by
a low one, a low surrogate (`U+DC00`–`U+DFFF`) not immediately preceded by a high one, or a
pair in the wrong order — has no UTF-8 encoding. Such a value arrives easily: a JSON string
may spell it as a `\uD800`-style escape, and common JSON parsers expand that without complaint.
An implementation MUST reject an event carrying one, whether computing its id or checking a
claimed one, and MUST NOT substitute a replacement character (`?`, `U+FFFD`) for the
unpaired unit, drop it, or hash the unit's own three-byte encoding. The reason is
interoperability: platforms substitute differently — the JVM's encoder writes `?`,
JavaScript's writes `U+FFFD` — so any substitution gives the same event different ids in
different implementations. A correctly paired astral character (for example `U+1F3A8`, the
pair `U+D83C U+DFA8`) is not affected: it is emitted verbatim under rule 3 as its four UTF-8
bytes.

Implementations MUST compute the id themselves and MUST reject any received event whose
`id` does not match its recomputed value, **before any other processing**. Signatures are
BIP-340 Schnorr over secp256k1.

**A signature on a nostr event is over the 32-byte event id and nothing else.** The `sig`
field is a BIP-340 signature whose message is the raw 32 bytes the `id` field spells in hex,
not the canonical serialisation, not a hash of the serialisation taken a second time, and not
any other digest. This is NIP-01's rule and it is restated here because it is the step at
which the id check above becomes load-bearing: an implementation that verifies a signature
against an id it has not recomputed has verified that somebody signed *a* 32-byte string, and
learned nothing about the event carrying it. Recompute the id, reject on mismatch, and only
then verify.

Event-id computation is a pure function of the event and is the cheapest complete integrity
check available. Signature verification requires secp256k1 and MAY be delegated to the
embedding client; an implementation that cannot verify signatures MUST say so in its
capability surface and MUST NOT report unverified events as verified (§17).

### 4.2 Addressable coordinates

Requests and offers are **addressable** events (kind in 30000–39999): a relay stores only
the latest event per `(kind, pubkey, d)` triple. Editing a listing means republishing the
same triple with a newer `created_at`.

Everything in Nenya that refers to a listing MUST refer to it by **coordinate**, never by
event id:

```
["a", "<kind>:<pubkey-hex>:<d-value>", "<relay-url>"]
```

This is not a stylistic preference. NIP-15 auctions bind bids to an event id, and NIP-15
itself has to warn that an auction cannot be edited once bid on, because every edit detaches
every bid. Coordinate-scoping makes that failure unrepresentable.

A listing MAY be shared out of band as a NIP-19 `naddr`. Relay hints inside an `naddr` leak
the sharer's relay set; see §12.

### 4.3 Encodings, tags and hostile input

Every event reaching an implementation is hostile input, including events it believes it
authored. The following rules are normative and apply to every event in this document.

**Hex.** Hex encoding in this document always means lowercase, unpadded hexadecimal.
Implementations MUST emit lowercase hex, and SHOULD accept uppercase hex on read and
normalise it — **except** for the two values named below, where no normalisation of any kind
is permitted and an uppercase or mixed-case value MUST be **rejected**:

- the BOLT-11 invoice string, which is compared byte-identically (§9.2 check 1). **This is
  Nenya's rule and not BOLT-11's**, and revision `1.4` corrected the claim that it was
  BOLT-11's: BOLT-11 prescribes an all-uppercase form for QR codes and publishes an
  all-uppercase invoice among its valid examples. Nenya refuses an uppercase or mixed-case
  invoice anyway, because check 1 compares the string byte for byte against the one that
  arrived in the `type=2` and a case fold is exactly the re-encoding that comparison forbids.
  A sender that has an uppercase invoice lowercases it before putting it in a `payment` tag
  (Appendix C);
- the Lightning **preimage** in a `kind:17` receipt (§9.2 check 2), which travels in the same
  tag as that invoice, is verified in the same step, and for which §18 accordingly lists
  uppercase hex as a negative control.

This exception list is exhaustive. Every other hex value in this document — pubkeys, event
ids, order ids, `x`, `ox`, `decryption-key`, `decryption-nonce` — follows the general
accept-and-normalise rule. A pubkey is exactly 64 hex characters (a 32-byte x-only key); an
event id is exactly 64; an order id is exactly 64; `x` and `ox` are exactly 64 each. An
implementation MUST reject a value of the wrong length rather than padding or truncating it.

**Timestamps.** Unix seconds. Encoded as a decimal string when they appear in a tag value,
and as a JSON number in `created_at`. Implementations MUST reject a timestamp tag value
that is not a non-negative decimal integer, and MUST NOT accept a floating-point,
exponential, or signed form.

**Tag shape.** A tag is an array of one or more strings. Implementations MUST reject an
event containing a non-string or `null` tag element, or an empty tag array.

**Text.** Every string value in an event — `content` and every tag value — MUST have a UTF-8
encoding. An implementation MUST reject an event whose `content` or tag value contains an
unpaired UTF-16 surrogate, and MUST NOT substitute a replacement character for it (§4.1).
Byte bounds below are measured in UTF-8 bytes, so such a value has no size to check either.

**Duplicate tags.** For any tag this document marks with cardinality `1` or `0–1`, an event
carrying more than one occurrence MUST be **rejected**, not resolved by taking the first,
the last, or the smallest. "First wins" and "last wins" are both defensible, which is
exactly the problem: two conformant implementations would then disagree about the price of
the same signed event.

**Unknown tags.** Unknown tags MUST be ignored on read and MUST be preserved verbatim when
an implementation round-trips an event it did not author. An implementation MUST NOT drop
tags it does not understand when re-publishing another party's event, because doing so
silently strips extensions and changes the event id.

**Resource limits.** An implementation MUST enforce a bound on the size of anything it
parses, and MUST reject rather than truncate when a bound is exceeded. These bounds SHOULD
be configurable, with defaults no larger than: 64 KiB serialised event, 512 tags per event,
1024 bytes per tag value, 16 KiB `content`, 64 `image` tags. A relay that returns a
100 MiB "event" is not a hypothetical, and an unbounded parser is a denial of service in
the buyer's client.

**The defaults above govern ordinary events and the rumor. A `kind:13` seal and a
`kind:1059` gift wrap are bounded by NIP-44 instead.** The defaults are not merely
conservative for these two kinds, they are unusable: a wrap's `content` is base64 over a
NIP-44 payload whose plaintext is the seal's JSON, which itself carries base64 over a NIP-44
payload whose plaintext is the rumor's JSON, and NIP-44 pads each plaintext to a
power-of-two-derived size. Under that 16 KiB default a wrap carrying a rumor of more than
7 168 bytes of JSON had to be refused — a conformant implementation could not open a 10 KB
chat message. That is what the bounds below replace, and the refusal they replace is no longer
required of anybody. So, for these two kinds and only these two:

- a **decrypted NIP-44 plaintext** is at least 1 and at most 65 535 bytes, which is what
  NIP-44's published vectors require (they list 65 536 among the plaintext lengths encryption
  MUST refuse) and what every deployed application implements. A later NIP-44 revision
  describes a six-byte extended-length prefix; the vendored vectors predate it, nothing emits
  it, and NENYA-1 follows the vectors — the same interoperability precedent §4.1 sets for
  canonical JSON escaping and revision `1.4` sets for Appendix C;
- a `kind:1059` wrap's `content` is at most 87 472 base64 characters, which is exactly the
  encoding of the largest NIP-44 payload the bound above permits;
- a **rumor's JSON is at most 40 960 bytes on write**. This is a bound on emitting, not on
  reading: anything from 40 961 bytes upwards pads to a seal whose own JSON exceeds 65 535
  bytes and therefore cannot be wrapped at all, so a sender that exceeds it produces a message
  nobody can open. An implementation MUST refuse to emit one rather than wrap it.

Every other default above still applies to a seal and a wrap — the tag count, the tag value
length, and the whole-event bound an implementation sets for them. Nothing here relaxes a
bound on a rumor, which is an ordinary event and keeps the ordinary defaults.

### 4.4 Money

On the wire, in public listing and bid tags, prices are **integer satoshis** with the unit
token `SAT`:

```
["price", "<integer satoshis>", "SAT"]
```

Internally, and in every private order message, amounts are **integer millisatoshis**.
`1 SAT = 1000 msat`. `1 BTC = 100 000 000 SAT = 100 000 000 000 msat`.

- **Strict on write.** Exactly the token `SAT`; exactly a non-negative decimal integer with
  no sign, no separators, no decimal point, no leading `+`, and no leading zeros other than
  the single digit `0`. Formally: `0|[1-9][0-9]*`.
- **Permissive on read.** Implementations MUST accept the unit tokens `SAT`, `sat`, `sats`,
  `SATS`, `msat`, `MSAT`, `BTC`, `btc`, and normalise to millisatoshis. A `BTC` value MAY
  carry a decimal point.
- **Never lossy.** A read that would lose precision — a `BTC` value with more than 11
  decimal places, or an `msat` value used where a satoshi-denominated field is REQUIRED and
  the value is not a multiple of 1000 — MUST fail loudly. It MUST NOT round.
- **Never floating point.** Amounts MUST NOT be represented in binary floating point at any
  point in the pipeline, including during parsing of a decimal `BTC` string. In languages
  whose default number type is a double (JavaScript, TypeScript, Lua), amounts MUST be held
  in an arbitrary-precision integer type (`BigInt`) or in a decimal-string arithmetic
  routine — the supply cap below exceeds 2^53 and is therefore not exactly representable
  as a double.
- **Bounded.** Implementations MUST reject a `price` whose millisatoshi value exceeds
  2 100 000 000 000 000 000 (the total bitcoin supply in msat). This bound is what makes
  the fee arithmetic in §8.3 expressible without overflow.

The optional NIP-99 `<frequency>` fourth element of `price` (recurring pricing) MUST NOT be
emitted by Nenya v1, and a listing carrying one MUST be treated as unsupported on read.
Nenya orders are one-off.

NIP-99 describes the currency field as "3-character ISO 4217 format or ISO 4217-like" and
uses `"btc"` in its own example. `SAT` is chosen anyway, because integer satoshis remove
decimals from a money path entirely, and because every settlement vocabulary on nostr
already denominates in satoshis or millisatoshis. The cost is that a non-Nenya NIP-99
client may render `SAT` poorly; implementations SHOULD render the value in both units in
their own UI.

### 4.5 Version and discovery tags

Every public Nenya event MUST carry both of:

```
["nenya", "1"]          # protocol version, not relay-indexed
["t", "nenya"]          # board discovery, relay-indexed
```

`nenya` is the version tag. Its value is the decimal major version of this document. An
implementation MUST ignore a public event whose `nenya` version it does not implement,
rather than parsing it optimistically.

`t` is used because relays index single-letter tags only. This is the single hardest
constraint on the whole design: **anything that must be filtered server-side has to ride on
a single-letter tag.** A discriminator on `price`, `status`, `fee` or any other multi-letter
name is invisible to a relay and forces every client to download the whole board.

### 4.6 Clocks and deadlines

Every deadline in this document — `expiration` on a listing, on a bid, on an order proposal;
`deliver_by`; a BOLT-11 invoice expiry — MUST be evaluated against the implementation's own
injected clock. A counterparty's `created_at` is a claim, and gift-wrap timestamps are
deliberately randomised into the past (§7.1), so neither can order events.

- An implementation MUST NOT order the events of an order thread by `created_at`. It MUST
  order them by its own receipt sequence, and MUST derive state only from the transition
  table in §11.2.
- An implementation MAY reject a public event whose `created_at` is implausibly far in the
  future (a RECOMMENDED tolerance is 15 minutes), and MUST NOT reject a gift wrap or seal
  on timestamp grounds.
- Because the clock is injected, every deadline rule in this document is testable offline
  with a fake clock, and an implementation SHOULD have such tests.

---

## 5. The board — public events

### 5.1 Offers — NIP-99 `kind:30402`

An offer is an **unmodified** NIP-99 classified listing. Nenya adds tags; it changes
nothing. This is deliberate and it is the cheapest distribution the project will ever get:
clients that already render `kind:30402` render a Nenya offer without knowing Nenya exists.

Drafts and parked offers use `kind:30403`, which has identical structure.

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

Constraints specific to offers:

- The `status` tag MUST be either `active` or `sold`, and nothing else. NIP-99's schema
  constrains it to that pair, and a third value would make the event non-conformant in
  every existing NIP-99 client. **The richer Nenya lifecycle never appears on a public
  offer.** It lives in the private order thread (§11).
- `["t", "wts"]` (want to sell) is REQUIRED on a Nenya offer, alongside `["t", "nenya"]`.
- The `price` is what the provider **receives**. See §8.2.

### 5.2 Requests — `kind:30404`

> **The Nenya request kind is `30404`.** This is decided (`OPEN-1`, closed in revision `1.1`;
> rationale in §16.1) and is normative. It is an addressable kind, so a request is editable
> and retractable by `(kind, pubkey, d)`.
>
> An implementation MUST still expose `30404` as a **single named constant**, set once, and
> MUST NOT hardcode the literal in more than one place — not because the value may change
> (Appendix B forbids that), but because a wire constant duplicated across a codebase is how
> two call sites end up disagreeing. Per Appendix B, the value MUST NOT change after the
> first public release; a change would be a new major version and a migration, not an edit.

A request is an addressable event that mirrors NIP-99's vocabulary as closely as the
semantics allow, so that one parser and one renderer serve both sides of the board.

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
    ["t", "wtb"],
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

> The literal `30404` above is the **decided** request kind, not a placeholder. This example
> is copy-pasteable as written, and fixtures MAY be built against it.

Constraints specific to requests:

- `["t", "wtb"]` (want to buy) is REQUIRED, alongside `["t", "nenya"]`.
- `["alt", ...]` (NIP-31) is REQUIRED. `30404` is an unregistered kind; every generic client
  that encounters one will render whatever `alt` says, or nothing at all.
- The `price` is the buyer's **budget**, and it is the amount the provider would receive.
  It is a ceiling for negotiation, not a commitment. A bid MAY name a different price (§6).
- `status` on a request MAY take the extended values `active`, `awarded`, `fulfilled`,
  `cancelled`. This extension is legitimate only because the kind is Nenya's own; the same
  extension on a `kind:30402` offer is forbidden. Implementations MUST treat an unrecognised
  `status` value as `unknown` and MUST NOT treat it as `active`. This vocabulary is **not**
  the order-state vocabulary of §11.1, which is carried on a sealed `kind:16` `type=3` and
  shares only the token `cancelled` with a different meaning. §11.1 states the rule; the
  short version is that one `status` codec MUST NOT serve both.

### 5.3 Tag vocabulary

The full vocabulary for both sides. "Both" means the tag has identical meaning and encoding
on a request and on an offer, which is the point.

**How far this table reaches.** The **Encoding** column is universal: wherever a tag named
here appears anywhere in this document — on a `kind:1111` bid (§6), on a private bid (§6.1),
or on an order rumor (§7) — it MUST use exactly the encoding given here, and a codec SHOULD
be shared across all of them. The **Card.** and **Requirement** columns are **not**
universal: they state what a `kind:30402`, `kind:30403` or `kind:30404` **listing** requires,
and they MUST NOT be applied to a bid or to an order rumor, which carry their own required
sets (§6, §6.1, §7.4, §7.5). A shared tag validator MUST therefore take the event kind — and,
for `kind:16`, the `type` — as an input to its cardinality and requirement rules. Applying
the listing rules everywhere rejects this document's own bid example, which legitimately
carries a single `["t", "nenya"]` and no `wtb`/`wts`.

| Tag | Side | Card. | Requirement | Encoding | Notes |
|---|---|---|---|---|---|
| `d` | both | 1 | MUST | `["d", "<opaque>"]` | Addressability key. MUST be non-empty. SHOULD be unguessable if the author does not want their listings enumerable by `d`. MUST be stable across edits. |
| `title` | both | 1 | MUST | `["title", "<text>"]` | Plain text, no markup. Implementations SHOULD cap display length; a title is not a description. |
| `summary` | both | 0–1 | SHOULD | `["summary", "<text>"]` | One-line tagline, plain text. |
| `published_at` | both | 0–1 | SHOULD | `["published_at", "<unix seconds>"]` | Time of **first** publication. MUST NOT change on edit. A later `created_at` with an unchanged `published_at` is how an edit is recognised. |
| `price` | both | 1 | MUST | `["price", "<sats>", "SAT"]` | See §4.4 and §8.2. |
| `status` | both | 0–1 | SHOULD | `["status", "<value>"]` | Offers: `active` \| `sold` only. Requests: `active` \| `awarded` \| `fulfilled` \| `cancelled`. Absent means `active`. **This is the listing vocabulary, not the order-state vocabulary of §11.1**, which rides the same tag name on a sealed `kind:16` `type=3` and shares only the token `cancelled`, with a different meaning. One codec MUST NOT serve both (§11.1). |
| `t` | both | ≥2 | MUST | `["t", "<token>"]` | MUST include `nenya` and exactly one of `wtb` / `wts`. Further `t` tags are free-form categories. Tokens MUST be lowercase with no whitespace; implementations MUST lowercase on write and SHOULD match case-insensitively on read, because relay tag indexes are byte-exact. |
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

`content` on a listing is a human-readable description. Per NIP-99 it SHOULD be Markdown,
and Nenya keeps that unchanged so existing NIP-99 renderers behave.

- `content` MUST NOT carry structured terms. Anything a counterparty or an implementation
  acts on lives in tags. A price, a deadline, a fee or a URL that appears only in prose has
  no effect under this document.
- An implementation rendering `content` MUST NOT execute embedded scripting, MUST NOT
  auto-fetch remote resources referenced from it (§12.10), and SHOULD render it as plain
  text if it cannot do both.

### 5.5 Discovery

Server-side filters that work:

```json
{"kinds": [30402], "#t": ["nenya"], "limit": 200}
{"kinds": [30404], "#t": ["nenya"], "limit": 200}
{"kinds": [30404], "#m": ["image/png"]}
{"kinds": [30402, 30404], "#p": ["<my-pubkey>"]}
```

Three relay behaviours that implementations MUST account for:

- **Multiple values in one tag filter are OR, not AND.** `{"#t": ["nenya", "wtb"]}` returns
  events tagged *either* `nenya` *or* `wtb`. Implementations MUST NOT rely on multi-value
  tag filters to conjoin conditions; they MUST re-filter client-side after receipt.
- **Only the first value of a tag is indexed.** A relay indexes `nenya` from
  `["t", "nenya"]`, and nothing from the second position of `["price", "50000", "SAT"]`.
- **A relay may silently drop an unregistered kind.** `30404` is unregistered, and a relay
  that refuses it is indistinguishable from a bug in the publisher.
  Implementations SHOULD check the relay's NIP-11 document, SHOULD verify that a published
  request is readable back, and MUST surface "this relay did not accept the request" as a
  distinct condition from "publish failed".

Results from a relay are unordered, incomplete and possibly stale by construction. An
implementation MUST NOT treat the absence of an event as evidence of anything — in
particular, not as evidence that an order was cancelled or that a listing was withdrawn.

### 5.6 Expiration and takedown

- `["expiration", "<unix seconds>"]` is NIP-40. Relays *SHOULD* honour it, and many do not.
  Therefore: **an implementation MUST treat an event whose `expiration` has passed as
  inactive regardless of the relay serving it**, and MUST NOT accept a bid, propose an
  order, or advance an order against an expired listing. Expiration is enforced by the
  consumer, not by the network.
- Un-listing requires two actions, and an implementation that performs only one leaves the
  listing live:
  1. republish the listing with `["status", "sold"]` (offers) or the appropriate terminal
     status (requests), or park it as `kind:30403` (offers only); **and**
  2. publish a NIP-09 `kind:5` deletion request carrying the `a` tag of the coordinate.
- Publishing a `kind:30403` does **not** remove the `kind:30402`. Addressability is per
  kind. Implementations MUST NOT present "save as draft" as a takedown.
- A NIP-09 deletion request is advisory. Relays SHOULD delete versions up to the deletion
  request's `created_at`, which means republishing the same `d` later with a newer timestamp
  is legal and will resurface. Implementations MUST NOT tell a user that a deletion is
  permanent.

---

## 6. Bids — NIP-22 `kind:1111`

A bid is a NIP-22 comment scoped to the **coordinate** of the listing it bids on.

> **Public bidding — this section — is the default.** (`OPEN-3`, closed in revision `1.1`;
> rationale in §16.1.) A conformant client MUST implement public bidding, and MUST make it
> the behaviour a user gets without changing a setting. Private bidding (§6.1) remains fully
> specified and permitted, but it is the alternative, never the default.

A bid is therefore a **public** event by default: its price, its terms and its author's
pubkey are visible to every relay it reaches and to everyone reading the board. This is
intentional — it is what produces price discovery and a durable, checkable record of who
bid what — and an implementation MUST tell the user so before their first bid, because it
is not what a user accustomed to private quoting will expect.

NIP-22 splits scope tags into uppercase (the root of the thread) and lowercase (the
immediate parent). Both MUST be present. A top-level bid has the listing as both root and
parent, so the uppercase and lowercase tags carry the same values.

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

- A bid MUST scope to a coordinate (`A`/`a`), never to an event id (`E`/`e`). A bid scoped
  by event id detaches the moment the listing is edited.
- `K` and `k` MUST both be present and MUST both equal the listing's kind as a decimal
  string. `P` and `p` MUST both be present and MUST both equal the listing author's pubkey.
- A bid MUST carry `price`, `t=nenya` and `nenya`. The listing table's cardinality rules do
  **not** apply to a bid (§5.3): a single `["t", "nenya"]`, with no `wtb`/`wts`, is correct
  on a bid, as the example above shows.
- A bid SHOULD carry `expiration`. A bid with no expiration is an open-ended commitment and
  implementations SHOULD render it as such.
- A bid **SHOULD** carry `["alt", "<text>"]` (NIP-31), and its `content` **SHOULD** restate
  the price and the delivery terms in prose. This is the requirement §15's interoperability
  claim rests on: a generic NIP-22 client cannot resolve the `a` coordinate and so renders
  the comment text and `alt` and nothing else. Per §5.4 that prose carries **no** machine
  meaning — an implementation MUST derive every term from the tags — so a bid whose prose
  and tags disagree is a display defect, never a terms dispute. Implementations SHOULD
  generate the prose from the tags rather than let a user type it independently, which is
  how the two come to disagree.
- A bid's `deliver_by`, when present, is a deadline in the same sense as every other deadline
  in this document (§4.6), and MUST be evaluated against the injected clock. In the example
  above, `deliver_by` is exactly `created_at + 18 h`, which is what the prose says.
- A bid MAY carry `fee`. If it does, that fee term is the one the bidder is willing to
  transact under, and any later divergence MUST abort the order (§8.4).
- `content` MUST be plain text.
- Bids are made **against requests** in the normal flow, and MAY also be made against offers
  as a counter-offer (with `A`/`K` set to `30402:...` / `30402`). An implementation MUST
  support parsing both.
- **A bid is not an acceptance and creates no obligation on anyone.** Only the sealed order
  proposal and its sealed acceptance bind (§7.4). An implementation MUST NOT advance any
  order state on the basis of a bid, public or private.
- Anyone may bid; there is no gate. Implementations SHOULD apply the user's own mute and
  filter lists to the bid list and MUST NOT auto-accept.

### 6.1 Private bidding

A **private bid** carries the same terms as the public bid above, in a §7 sealed message to
the listing author instead of a public `kind:1111`. It is specified, permitted, and
interoperable; it is not the default.

> **A private bid is a `kind:16` rumor with `["type", "6"]`.** It is the only `kind:16`
> `type` value that MUST NOT carry an `order` tag, and it MUST NOT advance any state (§7.4,
> §11.2). Assigned in revision `1.2`; the coordination question it inherits from `type=5` is
> `OPEN-8`.

Revision `1.1` specified the private bid's *contents* but pinned no rumor kind for it, which
left two implementations free to encode it differently and not interoperate. `type=6` closes
that hole. `kind:14` was rejected because §5.4 puts terms in tags and `kind:14` is defined as
free text; `kind:15` is a file message; `type=1` is excluded below.

- A private bid travels as a §7 gift-wrapped `kind:16` rumor with `["type", "6"]` to the
  listing author, carrying the same term tags a public bid would carry — `price`, and
  optionally `fee`, `deliver_by`, `expiration` — plus exactly one
  `["item", "<coordinate>", "1"]` naming the listing it bids on (§5.3), plus `["nenya", "1"]`
  and the `["p", "<listing-author-pubkey-hex>"]` every `kind:16` rumor carries (§7.4). The
  `item` coordinate carries the binding that `A`/`a`, `K`/`k` and `P`/`p` carry on a public
  bid; those scoping tags do not apply, because there is no public thread to scope to.
- A private bid is **not** an order message. It MUST NOT carry an `order` tag and MUST NOT
  be sent as a `kind:16` `type=1`: an order id exists only once the buyer proposes (§7.4),
  and a bid that looks like a proposal invites an implementation to advance state on it. A
  `type=6` carrying an `order` tag MUST be rejected, not silently accepted with the tag
  ignored, because the two shapes differ in exactly what an attacker would want to blur.
- An implementation that does not support private bidding MUST ignore a `type=6` rumor
  (§7.4) and MUST NOT report it to the user as an order, a proposal, or an error in the
  sender's implementation.
- Every rule in §6 that is about the *terms* applies unchanged: it MUST carry
  `price`; it SHOULD carry `expiration`; a `fee` tag on it is the term the bidder is willing
  to transact under, and any later divergence MUST abort the order (§8.4).
- **A private bid is not an acceptance either**, and creates no obligation on anyone. Only
  the sealed order proposal and its sealed acceptance bind (§7.4).
- An implementation that offers private bidding MUST make the choice explicit per bid and
  MUST NOT silently downgrade a public bid to a private one, or vice versa. A user who
  believes they bid publicly and did not has no reputation trail and no price signal.

An implementation MAY support private bidding only, or public bidding only. One that
supports public bidding only is conformant; one that supports private bidding only is
**not**, because §17, item 3 requires the public path.

---

## 7. The private channel — NIP-17 / NIP-59

Everything after the bid — terms, delivery, invoices, receipts, decryption keys — travels
as NIP-17 private direct messages. Nothing in this section ever appears in plaintext on a
relay.

### 7.1 Envelope

Per NIP-17 and NIP-59. The envelope is stated here as two numbered procedures, because the
order of the steps is load-bearing in both directions and because several of the refusals
below were previously implied by NIP-59 without being written down as rules a reader can
check.

**The payload at every layer is the JSON *object* form of the event** — `{"id":…,"pubkey":…,
"created_at":…,"kind":…,"tags":…,"content":…,"sig":…}` — not §4.1's array serialisation, which
exists only to be hashed. Strings inside it MUST be escaped by §4.1's rules, so that an
implementation has exactly one escaping routine and a rumor's `content` survives the round
trip byte for byte. An object carrying the **same key twice** MUST be rejected, at every
layer: "first wins" and "last wins" are both defensible, which is exactly the problem (§4.3).

**The write procedure.**

1. Build an **unsigned rumor**: a normal event object with `id` and `created_at` present and
   **no** `sig` key at all. The rumor's `created_at` is the **true** time — it is the only
   timestamp in the envelope that is not randomised, and it is what orders the thread for the
   two parties who can read it. The rumor's JSON MUST NOT exceed 40 960 bytes (§4.3); an
   implementation MUST refuse to emit a larger one rather than produce a message nobody can
   open.
2. **Seal** it: NIP-44-encrypt the rumor's JSON into the `content` of a `kind:13`, with
   `tags` **empty**, signed by the sender's real key.
3. **Gift wrap** it: NIP-44-encrypt the seal's JSON into the `content` of a `kind:1059`,
   signed by a **freshly generated keypair, new for every single wrap**, carrying exactly one
   `["p", "<recipient-pubkey>", "<relay-url>"]` tag. The kind is `1059` and only `1059`;
   `21059` is defined elsewhere for another purpose and MUST NOT be used here.
4. Produce **two** copies: one addressed to the recipient and one addressed to the sender's
   own key, so the sender can reconstruct their own thread. Each copy gets **its own seal**,
   NIP-44-encrypted to that copy's addressee — a seal is encrypted to exactly one reader, so
   the two copies cannot share one. The throwaway keypair MUST differ between the two copies,
   and MUST equal neither party's key; a wrap signed by a key either party uses elsewhere
   links the two.
5. `created_at` on **each seal and each wrap** — four values in total, drawn independently —
   MUST be randomised into the past, uniformly in `[now − 172 800, now]`, from the injected
   randomness source (§3). No such value may lie in the future, and no implementation may
   substitute the true time for one: a seal carrying the true time reintroduces exactly the
   timing correlation the construction exists to remove.

**The read procedure.** The steps MUST be applied **in this order**, and a rejection at any step
stops the read: nothing below a failed step is attempted, and no part of the message is reported.
Steps 1 to 3 cost no cryptography, which is why they come first — a wrap addressed to somebody
else is discarded before a single decryption is attempted.

1. **Id.** Recompute the wrap's `id` and reject on mismatch (§4.1).
2. **Kind.** Reject any kind other than `1059`.
3. **Recipient.** The wrap MUST carry exactly one `p` tag, and its value MUST be the reader's
   own public key. Reject otherwise — **before** any decryption.
4. **Wrap signature.** An implementation that verifies BIP-340 signatures MUST verify this one
   against the wrap's `pubkey`, which is the throwaway key, and MUST reject the wrap if it does
   not verify. Per §7.2 the verdict establishes no identity whatsoever; it establishes only that
   the wrap reached the reader unaltered.
5. **Decrypt** the wrap's `content` to the seal's JSON.
6. **Seal.** Parse it, recompute its `id`, reject any kind other than `13`, and reject a seal
   whose `tags` are **not empty**. An implementation that verifies BIP-340 signatures MUST verify
   the seal's signature against the seal's `pubkey` and MUST reject the seal if it does not
   verify.
7. **Decrypt** the seal's `content` to the rumor's JSON.
8. **Rumor.** Parse it and reject a rumor carrying a `sig` key **with any value at all**,
   including an empty string or `null`: NIP-59 requires the inner event to be unsigned, and a
   `sig` present is either a sender that does not understand the construction or an attempt to
   have the rumor treated as independently signed. Recompute the rumor's `id` and reject on
   mismatch.
9. **§7.2.** Apply the attribution rule, which is what makes anything in the rumor binding.

**Steps 4 and 6 are conditional on secp256k1, and deliberately so.** §17 permits an
implementation to delegate or omit BIP-340 verification, and §7.2 requires such an
implementation to perform the pubkey-equality check and report the message as
authenticated-by-decryption only. So an implementation that cannot verify signatures MUST
continue past steps 4 and 6 rather than discarding the message — NIP-44 decryption already
authenticates the ciphertext, and a reader that dropped every message for want of a verifier it
never claimed to have would be unable to receive anything at all. What is forbidden is verifying
and ignoring the answer: an implementation that *does* verify MUST reject on a bad signature, and
MUST NOT report a message as signature-verified on a verdict it did not obtain (§7.2, §17).

**No timestamp anywhere in this envelope is grounds for rejection.** §4.6 already says an
implementation MUST NOT reject a gift wrap or seal on timestamp grounds, and it is restated
here because step 5 of the write procedure deliberately produces timestamps up to two days
old: a reader that applied an ordinary freshness tolerance to them would discard conformant
messages, and one that ordered a thread by them would order it by noise.

**Two refusals are Nenya's own, and are stated as permissions rather than imposed.** An
implementation MAY reject event JSON carrying an unknown **top-level** key — the seven NIP-01
fields are the whole object, and an eighth is either a different protocol or an attempt to
smuggle a value past a parser that ignores it — and MAY reject a message whose recipient
equals its sender other than as the sender's own copy from step 4. Both are refusals Nenya
applies to itself; neither is a MUST on another implementation, and neither affects what any
conformant sender emits.

### 7.2 Attribution — the rule that makes a sealed term binding

The rumor is **unsigned**. Its `pubkey` field is therefore a claim, not a proof.

> **Implementations MUST verify that the `pubkey` of the `kind:13` seal equals the `pubkey`
> of the rumor inside it, and MUST discard the message otherwise.**

Without this check any sender can impersonate any other by writing someone else's pubkey
into the rumor. With it, the seal's BIP-340 signature is what binds every term in the rumor
— including the fee term — to a specific key. This is the sense in which the fee term in §8
is *signed*: not by a signature on the rumor, but by the signature on the seal that carries
it.

Consequently:

- An order term is attributed to the **seal's** `pubkey`, never to the rumor's claimed
  `pubkey`, never to the gift wrap's ephemeral `pubkey`.
- The gift wrap's `pubkey` is random and carries **no** identity. Implementations MUST NOT
  display it, index by it, or use it in any comparison.
- An implementation that cannot verify the seal's signature (no secp256k1; see §17) still
  MUST perform the pubkey-equality check, and MUST report messages in that mode as
  authenticated-by-decryption only, never as signature-verified.
- An implementation that **did** verify the seal's signature, and found it valid over the
  seal's recomputed id (§4.1), MAY report the message as signature-verified. That is a
  permission and not an obligation: authenticated-by-decryption is always a truthful report,
  and §17's rule against reporting an unverified thing as verified is the only one that binds.
  An implementation that verifies the signature for some messages and not others MUST
  distinguish them, because a single label covering both claims the stronger property for the
  weaker case.
- **The gift wrap's signature never affects attribution, whatever its verdict.** It is a
  signature by the throwaway key over the wrap, so a valid one says only that the wrap reached
  the reader unaltered. It attributes nothing, it does not corroborate the seal, and an
  implementation MUST NOT report a message as signature-verified on the strength of it.

### 7.3 DM relay lists — `kind:10050`

NIP-17 requires that clients publish DMs **only** to the relays listed in the recipient's
`kind:10050`, and states that a missing list means the user is not ready to receive
messages.

- An implementation MUST publish and maintain a `kind:10050` for its user before that user
  can transact.
- An implementation MUST treat "counterparty has no `kind:10050`" as a first-class,
  user-visible condition — *this user cannot receive orders* — and MUST NOT fall back to
  publishing the gift wrap elsewhere.
- **For this rule, "counterparty" includes the named fee recipient**, even though §2 defines
  a fee recipient as not a party to the delivery. §8.7 requires the fee `type=2` to arrive
  sealed by the fee recipient's own key, so a fee recipient who cannot receive order messages
  cannot issue the invoice the order blocks on, and a fee-bearing order naming them is
  wire-valid and silently unfulfillable. §8.1 states the check that follows from this.
- A missing `kind:10050` is the single most likely silent dead end in the whole flow. It
  MUST NOT be reported as a generic send failure.

### 7.4 Order message types

Nenya reuses the GammaMarkets order vocabulary rather than inventing one, because it is
already implemented by shipping NIP-99 clients, it already references items by coordinate,
and it already denominates in satoshis.

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

`type=5` and `type=6` are Nenya's own assignments in a vocabulary Nenya does not own, so a
future GammaMarkets `type=5` or `type=6` could collide. The `["nenya", "1"]` tag is what
disambiguates: an implementation MUST ignore a `type=5` or `type=6` message that does not
carry a `nenya` version it implements, and MUST NOT interpret a foreign one as a delivery
commitment or a bid. See `OPEN-8`.

An implementation MUST ignore a `kind:16` whose `type` it does not implement, and MUST NOT
map an unknown `type` onto the nearest known one — the same rule §11.1 applies to an unknown
`status`.

Required tags, by rumor kind:

```
["nenya", "1"]                   # every kind:15, kind:16 and kind:17 rumor
["p", "<counterparty-pubkey-hex>"] # likewise
["order", "<order-id-hex>"]      # likewise, EXCEPT kind:16 type=6, which MUST NOT carry it
                                 # 32 bytes, 64 lowercase hex chars
```

- Every `kind:15`, `kind:16` and `kind:17` rumor MUST carry `["nenya", "1"]` and
  `["p", ...]`.
- Every one of them MUST **additionally** carry `["order", ...]`, with the single exception
  of `kind:16` `type=6`, which MUST NOT carry it — an order id does not exist until the buyer
  proposes (§6.1, §7.5).
- `kind:15` is included in the `order` requirement deliberately. It is the release (§10.3),
  and revision `1.1` left it bound to its order only by hash equality, which is not a stated
  binding rule and gives an implementation handling two concurrent orders with the same
  provider no specified way to route a release. §10.3 states the consequences.
- `kind:14` free-text chat is outside this rule. It carries no terms and moves no state, so
  binding it is a display convenience; an implementation MAY carry `order` on a `kind:14` and
  MUST NOT derive anything from its presence or absence.

The **order id** MUST be 32 bytes from a cryptographically secure random source, generated
by the buyer when proposing. It MUST NOT be derived from the coordinate, either pubkey, the
price, or the time — a derived order id is a correlation handle for anyone who later learns
the inputs.

A `subject` tag (NIP-17) MAY be present on any rumor. It is display metadata only; an
implementation MUST NOT derive any term or state from it.

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

- `item` references the listing **coordinate** with a quantity, per GammaMarkets. Quantity
  MUST be `"1"` in Nenya v1, and exactly one `item` tag MUST be present.
- `amount_msat` is the price in millisatoshis — what the provider receives.
- A GammaMarkets `["amount", "<sats>"]` tag MAY additionally be present for compatibility.
  If both are present and `amount × 1000 ≠ amount_msat`, the implementation MUST reject the
  message. It MUST NOT prefer one and continue.
- `expiration` on the proposal is the deadline for **acceptance**, and it is a different
  deadline from `deliver_by`. When both are present, `expiration` MUST be strictly earlier
  than `deliver_by`, and an implementation MUST reject a proposal that violates this: an
  order that can still be accepted after its own delivery deadline has passed is incoherent,
  and it puts the provider in a state where acceptance and `expired` are simultaneously
  correct. In the example above the acceptance window is four hours (`created_at + 4 h`) and
  `deliver_by` is the bid's own `created_at + 18 h`, carried across byte-identically from
  §6.
- **The `fee` tag is a write rule here and a read rule in §8.1, and the two must not be
  confused.** On write: a proposal MUST NOT carry more than one `fee` tag, and SHOULD carry
  exactly one — including an explicit `["fee", "0"]` when no fee applies, so that the absence
  of a fee is itself a signed statement. On read: a proposal with **no** `fee` tag MUST be
  read as zero fee per §8.1, MUST NOT be refused as incomplete terms, and every fee invoice
  for that order MUST subsequently be refused. Revision `1.1` stated the write rule as
  "MUST carry exactly one", which contradicted §8.1's safe-omission closure and let two
  conformant implementations reach opposite conclusions about the same signed event; the read
  rule is the one that governs. See §8.
- The `item` coordinate is how the proposal names the listing it derives from — that is why
  it is REQUIRED and not optional — and the referenced listing MUST NOT be expired at the
  time of proposal (§5.6).

#### 7.6 Acceptance

Acceptance is a `type=3` status update from the provider carrying `["status", "accepted"]`
and the same `order` id, whose terms — `item`, `amount_msat`, `fee`, `deliver_by` — are
byte-identical to the proposal's. An implementation MUST NOT treat silence, a `kind:14`
chat message, a public event, or a status update with altered terms as acceptance. An
acceptance carrying different terms is a **counter-proposal**, and the correct response is
a new `type=1` from the buyer, with a new order id.

**The key that sealed the acceptance is a term of the check, not context around it.**
"From the provider" is stated above and in §11.2's `proposed → accepted` row, and it is a
requirement on the *receiver* as much as on the sender:

> **An implementation MUST establish the provider's pubkey independently of the proposal —
> it is the author of the offer the proposal's `item` coordinate names, or the bidder the
> buyer chose in response to a request (§5, §6) — and MUST NOT read it out of the
> proposal's `p` tag, which the buyer wrote and which §5.3 gives cardinality `0–n`. It MUST
> reject a `type=3` acceptance whose seal (`kind:13`) `pubkey` is not that key. The buyer's
> own key is not an exception: an acceptance is the counterparty's act, and an
> implementation MUST reject an acceptance sealed by the buyer even where every term is
> byte-identical.**

Without that, the four-term comparison is the only thing standing between a buyer and its
own order: the buyer knows its own terms exactly, so it can seal a `type=3` that is
byte-identical by construction, reach `accepted`, and then — §8.6 — send itself the
provider's invoice. The comparison is about *what* was agreed and this rule is about *who*
agreed; an implementation needs both, and neither substitutes for the other.

Resolving the key is the implementation's own work and NENYA-1 states no procedure for it.
What NENYA-1 requires is that the answer not come from the message being judged.

---

## 8. The fee term

A client embedding Nenya MAY take a percentage handling fee. This section exists because the
obvious way to implement that — a percentage in the client's own configuration — is
indistinguishable on the wire from skimming.

If the fee lives in client config, the buyer is told to pay P, the provider is told they
will receive P, and the client quietly settles the provider at P·(1−f). Nothing on the wire
records f. Neither counterparty can detect it, and no third party can audit it. So:

> **A fee MUST be a signed term of the deal. A fee that is not a signed term of the deal
> does not exist, and any invoice claiming it MUST be refused.**

### 8.1 Shape

```
["fee", "<basis-points>", "<recipient-pubkey-hex>"]     # basis points > 0
["fee", "<basis-points>"]                               # basis points == 0
```

- `<basis-points>` is a non-negative decimal integer in the same strict form as a price
  (§4.4). 1 bps = 0.01%. `250` means 2.5%.
- `<recipient-pubkey-hex>` is a 64-character lowercase hex x-only public key, REQUIRED when
  basis points is greater than zero, and OMITTED when it is `0`. The tag therefore has **two
  legal arities**, and a parser MUST accept both: three elements at bps > `0`, two at bps =
  `0`. §5.3 gives the same two encodings. A three-element `["fee", "0", "<pubkey>"]` and a
  two-element `["fee", "250"]` are both malformed and MUST be rejected.
- **The legal range is `0` to `10000` inclusive, and NENYA-1 imposes no ceiling below
  `10000`.** (`OPEN-2`, closed in revision `1.1`; rationale in §16.1.) An implementation
  MUST reject a `fee` tag with basis points greater than `10000` (100%), and MUST NOT reject
  a value in `0..10000` on the ground that it considers it too high. Any value in that range
  is a legal term provided it is signed (§8.4) and displayed (§8.2). Nenya is a neutral
  protocol and does not set fee policy; **mandatory disclosure is the anti-skim mechanism**,
  not a numeric cap.
- A client MAY apply its own, lower **local policy limit** — refusing to transact above,
  say, 1000 bps. If it does:
  - the refusal MUST surface as a distinct, named condition — *above this client's local
    policy limit* — and MUST NOT be reported as a malformed fee tag, an unsupported term, a
    protocol violation, or any other reason that would make a legal peer look broken;
  - the limit MUST be visible to the user and MUST NOT be a silent default. (This duty is
    stated here in full and depends on no other section. §16.1's preamble records the same
    carve-out for closed decisions that explicitly permit a client-local policy; §16.2's
    preamble is about OPEN items and does not reach this one, because `OPEN-2` is closed.)
  - the limit MUST NOT be applied on **read**: an event carrying `9000` bps is a valid
    NENYA-1 event and MUST parse, be displayed, and be attributed correctly. A local policy
    limit governs whether this client will *transact*, never whether the wire form is valid.
- Above `500` bps an implementation MUST require explicit user confirmation of the fee
  before proposing or accepting an order carrying it, showing basis points, absolute amount
  and total (§8.2). This is a floor on **disclosure**, not a cap on the fee: it never refuses
  a legal term, it only refuses to send one the user has not seen. It was an interim rule in
  revision `1.0`, scoped to the life of `OPEN-2`; revision `1.2` records it as a **standing**
  duty, promoted as part of the `OPEN-2` closure, because mandatory disclosure is the
  mechanism that closure chose and a disclosure mechanism with no floor is not one (§16.1).
- **A missing `fee` tag on an order proposal MUST be interpreted as zero fee.** This is the
  read rule, it governs, and it is the closure that makes omission safe rather than
  exploitable: an implementation that reads a fee-less proposal as zero fee MUST subsequently
  refuse **every** fee invoice for that order. An implementation MUST NOT refuse a fee-less
  `type=1` as incomplete terms. §7.5 carries the matching *write* rule — at most one `fee`
  tag, and SHOULD be exactly one.
- An order proposal SHOULD carry an explicit `["fee", "0"]` when no fee applies, so that the
  absence of a fee is itself a signed statement. Note the arity: `["fee", "0"]` has two
  elements, because the recipient is OMITTED at zero bps (§5.3).
- The recipient MUST NOT be the buyer's own pubkey. It MAY be the provider's pubkey only if
  the parties intend a genuine agent relationship; implementations SHOULD warn, because the
  same effect is achieved more honestly by raising the price.
- **The named fee recipient MUST be reachable before the fee term is proposed or accepted.**
  Before proposing or accepting an order with bps > `0`, an implementation MUST check that
  the named fee recipient publishes a `kind:10050` (§7.3), and MUST surface its absence as
  the distinct, named condition *the named fee recipient cannot receive order messages* —
  never as a generic failure and never as a malformed fee tag. A fee term naming an
  unreachable recipient MUST NOT be proposed. Without this check the order is wire-valid and
  cannot complete: §8.7 requires the fee invoice to arrive sealed by the fee recipient's own
  key, and §11.2 blocks `awaiting_payment` on that invoice, so the order can only expire.
  This is the same silent dead end §7.3 exists to prevent, one party further out.

> **Decided** (`OPEN-2`, closed in revision `1.1`): **no protocol ceiling below `10000`.**
> Above `10000` is rejected; `0..10000` is legal when signed and displayed. A client MAY hold
> a lower local policy limit, which must surface as its own reason. See §16.1 for why.

### 8.2 Price semantics

> **The `price` on a listing, the `price` on a bid, and `amount_msat` on an order are all
> the amount the PROVIDER RECEIVES.**

The fee is an **added line item on top**, never a subtraction from the price.

```
provider receives  = price
buyer pays         = price + fee
```

The alternative — fee deducted from price — means the same listing shows a different
effective price in every client depending on that client's fee configuration, which destroys
price comparison across the board and makes the fee invisible to the provider.

An implementation MUST display the fee as a separate, labelled line item with both its basis
points and its absolute value, and MUST display the total the buyer will pay.

### 8.3 Fee arithmetic

All arithmetic is integer arithmetic in millisatoshis.

```
price_msat = <amount_msat from the accepted terms>
fee_msat   = floor(price_msat × bps / 10000)
total_msat = price_msat + fee_msat
```

The product `price_msat × bps` can reach 2.1 × 10^22, which exceeds both 2^63 and 2^53. An
implementation MUST NOT compute it in a 64-bit or double-precision intermediate. Two
conformant routes:

1. Arbitrary-precision integers (`BigInteger`, `BigInt`, `int` in Python).
2. The exact split below, whose largest intermediate is 2.1 × 10^18 and which therefore fits
   in a signed 64-bit integer:

```
fee_msat = (price_msat / 10000) * bps + ((price_msat % 10000) * bps) / 10000
```

   with `/` integer division. Both terms are exact; the result equals
   `floor(price_msat × bps / 10000)` for every `price_msat ≤ 2.1 × 10^18` and every
   `0 ≤ bps ≤ 10000`.

Further rules:

- The division truncates toward zero. Both operands are non-negative, so truncation is
  floor.
- If the fee recipient's payment rail cannot express millisatoshi precision, the fee MUST be
  rounded **down** to the nearest whole satoshi. Never up. The fee recipient is the least
  privileged party in the transaction and absorbs rounding; the buyer and the provider never
  do. Where this rounding applies, **the rounded value is the expected fee amount** for every
  check that follows: §8.6's "exactly `fee_msat`" and §9.2 check 4 compare the invoice
  against it, not against the unrounded quotient, and if the rounded value is `0` the
  zero-fee rule below applies in full. In NENYA-1 v1 the rounding is **dormant**: BOLT-11
  expresses millisatoshis (Appendix C), and Lightning is the only rail for which v1 defines
  an evidence rule (§9.4), so no v1 payment request is ever rounded. It is specified now so
  that adding a satoshi-only rail later cannot introduce it as a silent behaviour change.
- A computed `fee_msat` of zero means **no fee invoice may be sent or accepted**, even
  though the term named a recipient. This is reachable with a non-zero `bps`: at
  `bps = 1` and `price_msat = 3000`, `fee_msat = floor(3000 × 1 / 10000) = 0`. The order
  then has a fee payee named by its terms and no fee invoice that may legally exist, so
  "required payee" MUST be computed from the **amount**, not from the term — see §9.2 and
  §11.1, which state that clause normatively. An implementation that requires an invoice per
  named payee deadlocks every such order into `expired`.

Worked example — `price = 50 000 SAT`, `bps = 250`:

```
price_msat = 50 000 000
fee_msat   = floor(50 000 000 × 250 / 10 000) = 1 250 000   # 1 250 SAT
total_msat = 51 250 000                                     # 51 250 SAT
```

Rounding example — `price = 1 001 SAT`, `bps = 137`:

```
price_msat = 1 001 000
fee_msat   = floor(1 001 000 × 137 / 10 000) = 13 713 msat
                                             = 13 SAT if the rail is satoshi-denominated
```

Boundary example — `price_msat = 2 100 000 000 000 000 000`, `bps = 10000`:

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

> **No fee may be *paid*, and no fee **receipt** (`kind:17`, `payee=fee`) may be accepted,
> before the order reaches `awaiting_payment`. A fee **payment request** (`kind:16` `type=2`,
> `payee=fee`) is accepted only as part of the transition into `awaiting_payment` (§11.2) —
> never earlier, in particular never at `proposed`, `accepted`, or a future `held`.**

The rule bites on **settlement**, not on the arrival of a request for payment, and the
distinction is load-bearing. §11.1 defines `awaiting_payment` as the state in which every
required payment request has been received and validated, and §11.2 makes the fee invoice
part of the trigger for entering it. A rule that rejected the fee `type=2` while the order
was still `committed` would therefore make `awaiting_payment` unreachable for every
fee-bearing order, and would make this document's own worked order (Appendix A, step 6)
illegal. Revision `1.1` was worded that way; revision `1.2` is not.

What the rule prevents is unchanged: a fee taken earlier — at proposal, at acceptance, at a
hold — leaves the buyer short on a refund with no clawback path, because the fee recipient is
not a party to the delivery and has no refund obligation. The state machine in §11 makes an
early fee **payment** unrepresentable rather than merely discouraged, and §11.3 invariant 2
is the direct test of it.

### 8.6 Two invoices, never one — the non-custodial rule

> **The buyer MUST pay the provider and the fee recipient with two separate payments to two
> separate invoices. An implementation MUST NOT construct, accept, or honour a single
> invoice covering `total_msat` that some party then splits.**

A single combined invoice means somebody receives money that is not theirs and forwards part
of it. That is custody, and in most jurisdictions it is money transmission. There is no
version of that design in NENYA-1.

Concretely:

- The provider's payment request MUST be for exactly `price_msat`. An implementation MUST
  reject a provider invoice whose amount is not exactly `price_msat`, in either direction.
- The fee recipient's payment request MUST be for exactly `fee_msat` — or, on a rail where
  §8.3's satoshi rounding applies, for exactly the rounded expected amount. When that amount
  is `0`, **no fee payment request exists at all** and any that arrives MUST be rejected
  (§8.3); this is the only case in which a fee-bearing order settles with one invoice, and it
  is not a combined invoice but an absent one.
- Payment requests MUST be tagged with their payee role:

```
["payee", "provider"]
["payee", "fee", "<fee-recipient-pubkey-hex>"]
```

- A `type=2` payment request MUST carry exactly one `["payment", "lightning", "<bolt11>"]`
  tag. GammaMarkets permits a static Lightning address (`lud16`) in that position; Nenya
  forbids it (§9.3), and an implementation MUST reject a `payment` tag whose reference is
  not a BOLT-11 invoice string.

**The provider invoice's sealing key, which is §8.7's counterpart on this side.** §7.4's
Sender column gives a `type=2` as "provider, or fee recipient", and §8.7 makes that a
refusal for the fee side. It is a refusal on this side too:

> **A `type=2` payment request with `["payee", "provider"]` MUST arrive in a gift wrap whose
> seal (`kind:13`) `pubkey` is the provider's key — the same key the acceptance for that
> order was checked against (§7.6, §11.2) — and any other MUST be rejected.**

A provider invoice from another key is somebody else's bill under the provider's name, and
§9.2 check 1 would then anchor the whole order's payment evidence to it.

**One accepted `type=2` per `(order, payee)`, and one invoice per order.** §9.2 check 1
compares a receipt against *the* payment request stored for that order and payee, which
presumes there is exactly one. Two rules make that true:

> **Once a `type=2` has been accepted for an `(order, payee)` pair, a second MUST be
> rejected rather than replacing it. And a `type=2` whose BOLT-11 string is byte-identical
> to one already accepted for a **different** payee on the same order MUST be rejected.**

The first rule refuses a re-pointing after the fact: check 1 is a comparison against a
stored string, so replacing that string retroactively changes which payment settles the
order, and any party that can get a second `type=2` accepted can aim the check wherever it
likes. It refuses one flow that looks conformant and is worth naming rather than smuggling
— a provider re-sending its invoice, for instance because the first went unanswered. That
is refused; an invoice that expires unpaid is not re-issued inside the order, and the
correct response to an order whose invoice has died is §11.2's, not a second invoice.

The second rule is the non-custodial rule above, enforced at the moment of storage instead
of at settlement. One invoice presented as both payees' bills is a combined invoice with
extra steps: whoever is paid holds `price_msat + fee_msat` and owes somebody the difference,
which is the custody §8.6 exists to rule out. Caught here, it is refused before the buyer
pays; caught at settlement, it is refused after — and the money has already moved.

**And the same reasoning for time.** §9.2 check 5 measures an invoice's
`timestamp + expiry` against the implementation's clock *as it read when the invoice was
accepted*, so the answer is already fixed at that moment:

> **An implementation MUST reject a `type=2` whose invoice has already expired at the clock
> reading it would record for that acceptance, rather than accepting it and refusing the
> receipt later.**

The two refusals name the same fact, and only the earlier one is useful: a buyer shown a
dead invoice has been shown a bill nothing will settle, and an implementation that stores it
and refuses the receipt has waited until after the payment to say so. Nothing is weakened by
this — an invoice accepted under the rule is one check 5 will accept, and check 5 stays
where it is for evidence arriving against a store the implementation did not itself write.

### 8.7 Verifying that a fee invoice is the fee recipient's

A BOLT-11 invoice is signed by a Lightning node key, which has no relationship to a nostr
pubkey. So a fee invoice cannot be cryptographically bound to the named fee recipient by
inspecting the invoice.

The binding NENYA-1 uses instead is the transport:

> **A `type=2` payment request with `["payee", "fee", ...]` MUST arrive in a gift wrap whose
> seal (`kind:13`) `pubkey` equals the fee-recipient pubkey named in the signed fee term. A
> fee invoice forwarded by the provider, or arriving from any other key, MUST be rejected.**

This is verifiable with the machinery already required by §7.2, and it means the fee
recipient must be a participant in the nostr conversation rather than a silent line item in
someone's config — which is the intended effect.

> **`OPEN-7`** — whether a stronger binding between the fee term and the fee invoice's
> destination is worth specifying. Candidates: require the fee recipient to sign a statement
> containing the invoice's `payment_hash` with their nostr key (adds a message, fully
> verifiable); require BOLT-11 node-key recovery plus a pre-registered node key (needs
> secp256k1 recovery, and doubles as a deanonymisation vector); accept the transport binding
> above as sufficient for v1.

---

## 9. Payment evidence

This is the load-bearing rule of the entire document.

### 9.1 What is not evidence

None of the following MUST ever advance an order's state:

- a boolean returned by a wallet (`isPaid`, `success`, `settled`);
- a status string in any message, from either counterparty;
- a `kind:17` receipt with no verifiable proof attached;
- a NIP-57 zap receipt — [NIP-57][nip57] itself states that a zap receipt is not proof of
  payment, and zap receipts are public events linking payer, payee and amount, which is
  independently disqualifying on privacy grounds;
- a relay's acceptance of an event;
- a signer's or a transport's assertion of anything.

An implementation MAY display any of these as an unverified hint, clearly labelled. It MUST
NOT let any of them move the state machine.

### 9.2 Lightning payment evidence

The only payment evidence NENYA-1 v1 defines is a Lightning **preimage**.

A `kind:17` receipt:

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

The `payment` tag follows the GammaMarkets shape
`["payment", "<medium>", "<medium-reference>", "<proof>"]`.

Before an implementation may treat a payment as made, it MUST perform **all** of the
following itself:

1. **Invoice identity.** The BOLT-11 string in the receipt MUST be **byte-identical** to the
   BOLT-11 string in the corresponding `type=2` payment request stored for the same `order`
   and `payee`. No normalisation, no case folding, no re-encoding, no bech32 round-trip. If
   no such payment request was received and stored, the receipt MUST be rejected. This
   implies that an implementation MUST persist every payment request it accepts, **together
   with the value its injected clock held at the moment it accepted it** — check 5 is
   unperformable without that second value, and §17 item 6 requires both. A receipt cannot be
   verified against an invoice the implementation has thrown away.
2. **Preimage shape and case.** The proof MUST be **lowercase** hex and MUST decode to
   exactly 32 bytes. Uppercase and mixed case are rejected rather than normalised: the
   preimage rides in the same tag as the byte-identically-compared invoice of check 1, it is
   verified in the same step, and §4.3 lists it as one of the two exceptions to the general
   accept-and-normalise rule. §18 accordingly keeps uppercase hex as a negative control.
3. **Preimage check.** `SHA-256(preimage) == payment_hash`, where `payment_hash` is the
   256-bit `p` tagged field parsed out of the BOLT-11 invoice (Appendix C). This is the
   whole rule, and it needs nothing beyond SHA-256 — no secp256k1, no external library, no
   network.
4. **Amount check.** The amount encoded in the invoice's human-readable part MUST equal the
   expected amount for that payee — `price_msat` for `provider`, and for `fee` the expected
   fee amount as §8.3 defines it (`fee_msat`, or its satoshi-rounded value on a rail where
   that rounding applies; in v1 the two are always the same). A zero-amount ("any amount")
   invoice MUST be rejected, and so is any invoice for a payee whose expected amount is `0`,
   because no such invoice may exist (§8.3).
5. **Expiry check.** The invoice's `timestamp + expiry` MUST NOT be in the past relative to
   the injected clock **as it read at the moment the invoice was accepted** (§4.6) — the
   value check 1 requires be persisted alongside the invoice. It is deliberately *not*
   re-evaluated against the clock at receipt-verification time: an invoice that was live when
   the buyer paid it does not become unpaid because it has since expired.
6. **Fee-term check.** For a `payee=fee` receipt, the fee term MUST match (§8.4), the sealing
   key MUST be the fee recipient's (§8.7), and the state MUST already be `awaiting_payment`
   (§8.5).

**Which payees are required.** An order becomes `paid` only when every **required** payee has
a verified receipt, and a payee is required when the terms name it **and** its expected
amount is non-zero. A fee payee whose computed `fee_msat` is `0` is therefore not required:
no fee invoice may exist for it (§8.3), so none may be awaited, and an implementation that
waits for one deadlocks the order into `expired`. §11.1 and §11.2 carry the same clause.

Note the asymmetry that makes this work: the preimage is a secret held by the **payee** and
released only on settlement. A buyer who claims to have paid can obtain the preimage only by
actually paying. A provider who fabricates a receipt gains nothing, because the check is
against the invoice the provider themselves issued and the preimage is theirs already — the
receipt only ever tells the payee something the payee can already confirm, which is why it
is the *payer* who sends it and the *payee's* verification that matters.

Verifying the invoice's *destination node* would require secp256k1 signature recovery and is
therefore OPTIONAL in v1. An implementation that cannot do it MUST NOT claim to have
verified that the money went to the right node: it has verified that *someone* paid *that
invoice*, and that the invoice arrived in the correctly sealed thread.

### 9.3 Invoice binding and privacy

- An invoice is bound to an order by **arriving in that order's sealed thread from the
  correct key** (§7.2, §8.7). An invoice obtained from any other channel — a public event, a
  profile field, a static Lightning address, a link — MUST NOT be paid for a Nenya order.
- **Static Lightning addresses (LNURL / `lud16`) MUST NOT be used for order settlement.** A
  reused address links every order the user has ever settled, and an LNURL fetch discloses
  the payer's IP to the recipient's server.
- The binding is transport-level precisely because the alternative — writing the order id
  into the invoice description — would publish that identifier to the payer's wallet, the
  payee's node, and every routing hop. See §12.

### 9.4 Other rails

`["payment", "bitcoin", "<address>", "<txid>"]` and
`["payment", "ecash", "<mint>", "<proof>"]` are recognised as GammaMarkets vocabulary and
MAY be parsed, but **NENYA-1 v1 defines no verification rule for either**, so neither
constitutes evidence and neither may advance state. An implementation encountering one MUST
treat the payment as unverified.

> **`OPEN-4`** — the verification rule for ecash settlement (Cashu, NIP-60/61 nutzaps).
> Ecash is the strongest privacy option and the natural home for a future escrow primitive,
> but "what does this implementation compute, offline, to be certain a token was received
> and is spendable" is unanswered, and a wrong answer here is a money-loss bug. Candidates:
> defer to NENYA-2; specify DLEQ proof verification (needs secp256k1); specify a
> mint-attested state check (introduces a trusted third party and so weakens the evidence
> rule). On-chain settlement is deliberately not pursued — too slow and too expensive for the
> amounts this board is aimed at, and permanently public.

---

## 10. Deliverables and hash commitments

The deliverable never travels as bytes inside a nostr event. It travels as an **encrypted
blob at a URL**, committed to by hash before payment and unlocked after.

### 10.1 The commitment — `kind:16`, `type=5`

Sent by the provider **before** any payment request.

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

- `x` and `ox` follow NIP-94 / NIP-17 semantics: `x` is the SHA-256 of the encrypted file as
  served, `ox` the SHA-256 of the file before encryption.
- Both MUST be present, lowercase hex, exactly 64 characters.
- `size` is the length in bytes of the **encrypted** blob, as a decimal string.
- The commitment message MUST NOT contain `decryption-key` or `decryption-nonce`. An
  implementation MUST reject a commitment that does — releasing the key at commitment time
  collapses the whole construction.

### 10.2 Encryption parameters

`encryption-algorithm` MUST be `aes-gcm` in v1, and means exactly:

- AES-256-GCM;
- a 256-bit key, generated per deliverable from the injected randomness source, never reused
  across orders;
- a 96-bit (12-byte) nonce, never reused with the same key;
- a 128-bit authentication tag, appended to the ciphertext;
- the served blob is exactly `ciphertext || tag`. The nonce is **not** prefixed to the blob;
  it travels in the `decryption-nonce` tag.
- no additional authenticated data.

`decryption-key` and `decryption-nonce` MUST be emitted as lowercase hex (64 and 24
characters respectively). Implementations SHOULD additionally accept standard base64 on read,
because NIP-17 does not specify the encoding and other clients may emit it; an
implementation MUST reject a value that decodes to the wrong length under both encodings.

### 10.3 The release — `kind:15` file message

Sent by the provider after payment evidence verifies. It is an ordinary NIP-17 file message,
so a generic NIP-17 client can render it.

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

**Binding.** A `kind:15` release is bound to its order by its `["order", "<order-id-hex>"]`
tag, which §7.4 requires. Hash equality is a *check*, not the binding: an implementation
handling two concurrent orders with the same provider routes on the `order` tag and then
verifies the hashes. A release whose `order` tag names no open order of this implementation's
own, or names an order not in state `paid`, MUST NOT advance any state.

**The identity to check, stated as an operand pair.** The commitment (§10.1) carries the MIME
type as `["m", "<mime>"]` and the release carries it as `["file-type", "<mime>"]`; they are
differently named on purpose, because each follows its own host kind's convention. The
mapping is therefore normative rather than implied:

- the release's `["file-type", "<mime>"]` **value** MUST be byte-identical to the
  commitment's `["m", "<mime>"]` **value**;
- the release's `x`, `ox` and `size` values MUST each be byte-identical to the commitment's
  values of the same name.

An implementation MUST NOT look for `file-type` on the commitment or `m` on the release, and
MUST NOT skip the check because the tag it looked for was absent. Any mismatch in any of the
four MUST move the order to `disputed`.

### 10.4 Buyer verification

The buyer MUST, in this order:

1. Download the bytes at the URL and compute their SHA-256. It MUST equal `x`. If not, the
   buyer has the wrong or a tampered blob; the buyer MUST NOT decrypt it and MUST NOT settle.
2. Decrypt with the released key and nonce. GCM authentication MUST pass.
3. Compute the SHA-256 of the plaintext. It MUST equal `ox`. If not, the order MUST move to
   `disputed`.

Only after all three succeed may the order reach `settled`. An implementation MUST also
bound the download (§4.3) and MUST refuse a blob whose length disagrees with `size`.

A blob refused for its length, a blob refused by that bound, and a download that never
completes are **not** hash mismatches. None of them says anything about whether the provider
committed to the bytes they served, any of them may be followed by the same blob served
correctly, and an implementation MUST NOT move the order to `disputed` for one on the spot.
What such an order does instead is leave `released` at the **verification deadline** of
§11.2's `released → disputed` row, along with the order whose buyer never looks at all.

### 10.5 What the commitment does and does not prove

Because `ox` is published **before** the buyer pays and **before** the key exists in the
buyer's hands, a provider cannot substitute a different file after payment: the only file
whose plaintext hashes to `ox` is the one they committed to.

It does **not** prove the file is good, on-brief, original, or non-infringing. Nenya has no
quality oracle and does not want one. `ox` proves *delivery of the committed bytes*, and
nothing more. Implementations MUST NOT present hash verification to users as a quality
guarantee.

A provider MAY additionally publish `ox` in a public bid, which lets a third party later
adjudicate a "you delivered something other than what you committed to" claim without either
party's cooperation. The cost is a correlation handle: if the plaintext ever becomes public,
the hash links it to that bid. Implementations MUST make this opt-in and MUST explain the
trade-off; it MUST NOT be the default.

### 10.6 Metadata

Providers MUST strip identifying metadata (EXIF, XMP, C2PA/JUMBF) from a deliverable
**before** encryption and **before** upload. A blob host that strips metadata server-side has
already seen the original. Because the commitment hashes are computed after stripping,
stripping is not merely advisory — a deliverable stripped after commitment fails the `ox`
check.

---

## 11. Order lifecycle

There is no shared authoritative order state. Each party maintains its **own** view, derived
only from evidence it verified itself. The two views can legitimately differ during transit,
and an implementation MUST NOT assume its counterparty is in the same state.

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

An unrecognised status value MUST be treated as `unknown` and MUST NOT be mapped onto the
nearest known state. In particular an unknown status MUST NOT be treated as `paid` or
`settled`. GammaMarkets' own status vocabulary (`pending`, `confirmed`, `processing`,
`completed`, `cancelled`) is disjoint from this one except for `cancelled`, which carries the
same meaning in both; an implementation MUST NOT map the other four onto Nenya states.

**This vocabulary is also disjoint from Nenya's own public listing `status` (§5.1, §5.2),
and the overlap is the trap.** A listing carries `active | sold` (offers) or
`active | awarded | fulfilled | cancelled` (requests); an order carries the ten tokens above.
They share exactly one token, `cancelled`, and its meaning differs: a cancelled **listing**
is withdrawn from the board, a cancelled **order** ended before `paid`. A single shared
`status` codec — the sort §5.3 otherwise encourages — conflates them, and `cancelled` is
precisely the token where the conflation looks correct and passes a test. Therefore:

- an implementation MUST NOT parse a listing `status` with the order-state codec, or an order
  `status` with the listing codec;
- an implementation MUST NOT derive order state from a public listing `status` under any
  circumstances. A buyer republishing their request as `fulfilled` (Appendix A, step 10) is
  an announcement to the board, not a state transition, and §5.5's rule that absence proves
  nothing applies to presence here too.

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

Every transition not listed is **illegal** and MUST be refused. An implementation SHOULD
model the transition function as total — every (state, event) pair produces either a new
state or an explicit rejection — so that "we forgot a case" cannot become "we fell through
to the happy path".

Two message classes appear in no row of this table and MUST advance no state, in any state,
from any key: a `kind:16` `type=6` **private bid** (§6.1) — it precedes the existence of an
order id, so there is no order for it to advance — and a `kind:14` chat message. An
implementation MUST NOT treat either as an acceptance, a cancellation, or evidence of
anything (§7.6, §9.1).

Three deadlines drive rows of this table. The first two are **wire terms and distinct ones**:
the proposal's `expiration` drives `→ expired`, and `deliver_by` from the accepted terms drives
`paid → disputed`. Both are evaluated against the injected clock and never against a
counterparty's `created_at` (§4.6). §7.5 requires `expiration` to fall strictly before
`deliver_by`, so the two never invert — an order can never still be awaiting acceptance after
the delivery deadline it would be judged against has already passed.

The third is the **verification deadline** of the second `released → disputed` row, and it is
**local rather than a wire term**. The asymmetry is deliberate. `expiration` and `deliver_by`
bound an act the *counterparty* owes, so both parties have to agree in advance on the moment
that act is judged at, and a term on the wire is the only way to agree. At `released` the only
outstanding act is the buyer's **own** computation of `x` and `ox` (§10.4): nothing the provider
does can hasten it or delay it, the provider's view of the order needs no opinion about when it
happened, and a fourth term would reopen §7.5's and §7.6's byte-identical terms for a risk §11.4
does not describe. So an implementation applies and displays a window of its own here, exactly
as it already must where the accepted terms carry no `deliver_by`, and two implementations
picking different windows are both conformant.

Revision `1.1` referred to a "release deadline" that was defined nowhere, which left the one
transition then protecting the buyer against the residual risk of §11.4 untestable and invented
per implementation. Naming a deadline is therefore not sufficient: each of the three above says
what it runs from, what an implementation MUST do once it has passed, and — for the third, which
runs from readings that may never have been taken — what an implementation MUST report when
there is nothing for it to run from at all.

### 11.3 Invariants worth testing directly

1. There is **no** path to `paid` that does not pass through verified evidence.
   `proposed → paid` is unreachable.
2. There is **no** path on which a fee is *paid* — no `kind:17` `payee=fee` receipt accepted
   — before `awaiting_payment` (§8.5, made structural). The fee **payment request** is a
   different object and is accepted exactly once, as part of the `committed →
   awaiting_payment` transition; a test that forbids the request rather than the payment
   makes every fee-bearing order unreachable and is testing the wrong thing.
3. There is no path from any terminal state to any other state.
4. `settled` is reachable only after the buyer's own hash computation, never on the
   provider's assertion.
5. A `type=3` status update purporting to set `paid` or `settled`, arriving from any key,
   MUST NOT change state. Status updates announce; they do not decide. Only `cancelled` is
   accepted from a counterparty's assertion alone, and only before `paid`.

### 11.4 Where escrow would go

If and when escrow is added, it enters as a `held` state between `accepted` and `committed`,
with `refunded` as an additional terminal. Nothing in v1 may assume that edge does not exist:
implementations MUST NOT write transition logic that enumerates "everything after `accepted`"
in a way a new state would silently join.

v1 is **pay-on-delivery plus reputation**. The residual risk is stated plainly rather than
engineered away: the buyer pays after the commitment but before the key, so a provider who
takes payment and withholds the key steals the price. What limits that is that the provider
is publicly identified by their bid, the commitment is a signed artefact, and the loss is
bounded by the price. Implementations SHOULD cap the value they will transact without escrow
and SHOULD make that cap visible.

The `paid → disputed` transition is the only mechanism v1 has that acts on that risk, and it
is why §11.2 binds it to `deliver_by` rather than to an unnamed timeout: an order that a
provider abandons after payment must reach a terminal, visible, comparable state at a moment
both parties agreed to in advance, not at whichever timeout each client happened to choose.
`disputed` resolves nothing (§14 item 3); what it does is stop the order pretending to be
live. §11.2's verification deadline is the companion rule for the state after that one: it
acts on no risk a provider controls — the key is already released by then — and exists so that
an order whose blob never verified, because it was served at the wrong length, because the
download never completed or because the buyer never looked, stops pretending to be live too.

---

## 12. Privacy requirements

These are protocol requirements, not implementation advice. Each one closes a channel that
would otherwise deanonymise a party.

1. **No identifiers in the payment layer.** An order id, listing coordinate, `d` value,
   counterparty pubkey, title, or any value disclosed by a personal-data vault MUST NOT
   appear in a BOLT-11 description, a description-hash preimage, an LNURL metadata blob, a
   wallet memo, or a blob-store filename.
2. **No identifiers in unencrypted events.** The same values, plus any invoice string, MUST
   NOT appear in a public event. Implementations SHOULD have an automated check over the
   corpus of events they can construct that asserts this.
3. **No static payment identifiers.** Per-order invoices only (§9.3).
4. **Separate keys for separate jobs.** The key that signs listings SHOULD NOT be the key
   that receives ecash or zaps. Where a P2PK receiving key is used it MUST differ from the
   marketplace identity key.
5. **No location.** `g` and `location` MUST NOT be emitted (§5.3).
6. **No profile leakage.** Values obtained from a local identity vault MUST NOT be written
   into a `kind:0` profile or any Nenya event. An implementation SHOULD make this a
   type-level impossibility rather than a review item.
7. **Randomised gift-wrap timing** is REQUIRED, not optional (§7.1).
8. **Ephemeral wrapper keys are never identity** (§7.2).
9. **Relay hints leak.** Implementations SHOULD keep relay hints in `a`/`p` tags minimal and
   MUST NOT include a relay hint the user has marked private.
10. **No auto-fetch.** An implementation MUST NOT automatically download or render a URL from
    an unknown counterparty — an image tag, a blob URL, a link in `content`. A fetch is an IP
    disclosure to whoever controls the host, and it is a read receipt.
11. **No secrets in diagnostics.** Key material, decryption keys, preimages and order ids
    MUST NOT appear in logs, crash reports, analytics, or the string representation of any
    value the implementation exposes.

---

## 13. Security considerations

**The trust model, stated honestly.** Nenya protects a user against their *counterparties*
and against *relays*. It does **not** protect a user against the client that embeds it. The
injected signer sees every event before it is signed; the injected wallet sees every invoice;
the injected transport sees whatever the client hands it. A malicious embedding client can do
anything the user can do. Nothing in this document changes that, and implementations MUST NOT
claim otherwise in their documentation.

What the design does achieve within that model:

- A dishonest **counterparty** cannot advance state by assertion (§9), cannot substitute the
  deliverable after commitment (§10), and cannot impersonate another party in the private
  channel (§7.2).
- A dishonest **client** cannot skim silently, because the fee is a signed term and a
  disagreeing split is refusable by the other side (§8). It can still refuse to transact, lie
  in its own UI, or exfiltrate through its own signer — but it cannot produce a wire-valid
  order that under-pays the provider relative to the signed terms.
- A dishonest **relay** can withhold, delay and reorder events, and can see every public
  listing and bid. It cannot forge an event (id + signature), cannot read a gift wrap, and
  cannot learn the participants of an order from the wrap alone.

Residual risks that implementations MUST surface rather than hide: the provider can be paid
and never release the key (§11.4); a blob host can go away between commitment and download; a
relay set can be partitioned so a party never sees an acceptance; and a `kind:5` deletion is
advisory, so nothing published is ever certainly gone.

---

## 14. What Nenya deliberately does not do

This list is normative in the sense that a system doing any of these is not implementing
NENYA-1, whatever else it does.

1. **No custody.** No party to this protocol ever holds funds belonging to another. Two
   invoices, paid directly, never one invoice that someone splits (§8.6).
2. **No escrow in v1.** No hold, no multisig, no 2-of-3, no locktime refund. The state
   machine reserves the seam (§11.4) and nothing more.
3. **No arbitration.** There is no dispute resolution mechanism, no arbiter role, no appeal.
   `disputed` is a terminal label meaning "this ended badly", and the parties are on their
   own. An arbiter with a signing key is a custody-adjacent position and NENYA-1 does not
   create one.
4. **No index server.** Discovery is relay filters (§5.5). There is no Nenya search API, no
   aggregator, no canonical board.
5. **No relay, no blob server, no mint, no push server** operated as part of the protocol.
   Implementations bring their own; the protocol names none.
6. **No fee to the author of this specification.** There is exactly one fee mechanism in
   NENYA-1, it is optional, it is a signed term between the transacting parties, and its
   recipient is named in that term. There is no protocol-level cut, no default recipient, and
   no hardcoded pubkey anywhere in this document or in any conformant implementation.
7. **No identity verification.** No KYC, no real-name field, no address, no phone, no email.
   The order payload has no shape into which such a value could be written, and
   implementations SHOULD enforce their absence by a structural check rather than a
   convention.
8. **No content scanning** and no protocol-level moderation. Client-side reporting and muting
   ([NIP-56][nip56], [NIP-51][nip51]) are the client's business and are deliberately outside
   this document.
9. **No dependency on NIP-90 or NIP-15.** Both are marked `unrecommended` upstream. Their
   vocabulary was read; neither is a foundation. An optional, interface-isolated bridge
   letting an automated NIP-90 provider answer a Nenya request is a permitted extension, not
   part of the core.
10. **No NIP-04.** Ever.
11. **No zap receipts as evidence** (§9.1).
12. **No floating-point money** (§4.4).

---

## 15. Interoperability notes

- A NIP-99 client that knows nothing about Nenya renders a Nenya **offer** correctly: it is
  an unmodified `kind:30402`, and every Nenya-specific tag is additive. The `SAT` currency
  token is the one place such a client may render oddly.
- A NIP-99 client cannot render a Nenya **request**, because `kind:30404` is unregistered.
  This is why `alt` is REQUIRED on requests.
- A NIP-22 client renders a Nenya **bid** as a comment on an address it may not be able to
  resolve. The comment text and `alt` **SHOULD** carry the human-readable terms for exactly
  this reason (§6), so the fallback rendering is a readable quote rather than a bare price
  tag the client cannot place. This is a SHOULD, so the fallback is a strong convention and
  not a guarantee; and per §5.4 that prose is display only — no implementation, Nenya or
  otherwise, may act on it.
- A NIP-17 client renders Nenya order traffic as a direct-message thread: `kind:14` chat
  appears normally, `kind:15` file messages appear normally, and `kind:16`/`kind:17` appear as
  unknown-kind rumors that the client will likely hide. Nothing breaks.
- GammaMarkets clients share the `kind:16`/`kind:17` order and receipt vocabulary and the
  `item` / `amount` / `payment` tag shapes. Nenya adds `type=5`, `type=6`, `amount_msat`,
  `fee`, `payee` and `order`-scoped hash commitments, and is stricter about evidence and about
  static Lightning addresses. A GammaMarkets order is parseable by a Nenya implementation; a Nenya
  order is parseable by a GammaMarkets implementation up to the tags it does not know.

---

## 16. Decisions — closed and open

§16.1 records decisions that **have been made**, with what was considered and why it went the
way it did, so a future revision does not relitigate them blind. §16.2 lists what is **still
open**. §16.3 notes choices that were never OPEN but carry a known cost.

The three items closed in revision `1.1` keep their original numbers, and the five that were
open then keep theirs: `OPEN-4` through `OPEN-8` are numbered exactly as they were in
revision `1.0`. **Nothing has ever been renumbered.** The numbers are referenced from §5.3,
§6.1, §7.4, §8.7 and §9.4, and renumbering would silently retarget those references. Revision
`1.2` added `OPEN-9` at the end — a question that was already live and merely unnumbered —
and broadened `OPEN-8`'s subject without changing its number, so six items are open.

### 16.1 Closed decisions

Each entry below is settled. An implementation MUST implement the decision as written, and
MUST NOT expose the decision itself as configuration — the wire value is not a setting. This
does **not** forbid the client-local policy an entry explicitly permits (§8.1's local fee
limit, §6.1's per-bid choice of private bidding); those are policy on top of a decided wire
format, and each carries its own visibility requirement.

#### `OPEN-1` — the request kind number — **CLOSED: `30404`**

The Nenya request kind is **`30404`** (§5.2). Decided 2026-09-09, revision `1.1`.

It had to be an addressable kind (30000–39999), because a request must be editable and
retractable by `(kind, pubkey, d)`. What was considered:

| Candidate | For | Against | Outcome |
|---|---|---|---|
| `30404` | unclaimed in both the NIPs table and the machine-readable registry; legible sitting next to NIP-99's `30402`/`30403`, which is exactly the adjacency a reader needs to guess what it is | it is the obvious number for a future official NIP-99 extension; taking it is squatting, and GammaMarkets already took `30405`/`30406` without registering | **chosen** |
| `33402` | empty band, no collision risk, echoes `402` | opaque; no one reading it learns anything | rejected |
| `32767` / `32768` | already in the wild via SatShoot | inherits someone else's semantics and any future change they make | rejected |

Why `30404` won: legibility next to `30402`/`30403` is worth more than the squatting risk,
and the squatting objection is weakened by the fact that the neighbouring slots were already
taken the same way without registration. The collision risk it does carry is bounded — if an
official NIP-99 extension later claims `30404`, that is a major version and a migration
(Appendix B), not a silent reinterpretation.

Still to do, and deliberately **not** blocking: whether to file a `registry-of-kinds` pull
request to stake the number. That question is now `OPEN-9` (§16.2) — in revision `1.1` it sat
here unnumbered, in no index, which meant nothing in this document would surface it before
publication, the one moment at which it stops being reversible. It is a publication decision,
not a wire-format one, and the number is usable either way.

Consequence for implementers: the number is now fixed, so **fixtures and test vectors may be
written against it**. The revision `1.0` prohibition on writing a fixture before this
decision no longer applies.

#### `OPEN-2` — a hard ceiling on fee basis points — **CLOSED: no ceiling below `10000`**

Any value in `0..10000` is a legal fee term provided it is a **signed** term (§8.4) and a
**displayed** term (§8.2); above `10000` is rejected (§8.1). Decided 2026-09-09,
revision `1.1`.

What was considered:

| Candidate | For | Against | Outcome |
|---|---|---|---|
| No protocol cap, mandatory disclosure | keeps Nenya a neutral protocol; disclosure is what actually protects the provider, and it works at every rate; no number to relitigate as the market moves | a 9000 bps term is legal and looks shocking in a conformance test | **chosen** |
| Cap at 1000 bps (10%) | matches the intuition that a marketplace fee is "about 10%" | sets fee policy from the protocol layer for every future deployment, on no evidence; makes conformant peers reject each other's legal events | rejected |
| Cap at 2000 bps (20%) | same intuition, more headroom | same objection; the number is arbitrary and would be argued about forever | rejected |

Why no cap won: the skim this whole section exists to prevent is a **hidden** fee, not a
large one. A cap does nothing about a hidden fee — a hidden fee is not in the tag at all —
and it does real damage to a neutral protocol, because a capped implementation refuses a
legal, signed, disclosed event and looks broken to a conformant peer. Mandatory disclosure
(§8.2, and the confirmation floor above 500 bps in §8.1) is the mechanism that actually
bites: a fee the buyer and provider can both see, in bps and in absolute msat, in a term
neither party can alter without aborting the order, is a fee the market can price.

The escape valve is local policy, not protocol policy: a client MAY refuse to transact above
its own lower limit, and §8.1 requires that refusal to surface as its own distinct reason —
*above this client's local policy limit* — never as a malformed tag and never as a silent
default. That keeps the choice with the operator who bears it, and keeps it visible.

**One rule was promoted as part of this closure, and is recorded here so the closure does not
rest on an unratified premise.** In revision `1.0`, §8.1's requirement of explicit user
confirmation above `500` bps was interim, scoped to the life of this open item, and closing
the item would have deleted it. It is instead **retained as a standing duty**, and it is part
of this decision rather than an independent rule that the rationale above happens to lean on.
The reasoning is that this closure chose *disclosure* over a *cap* as the anti-skim mechanism,
and a disclosure mechanism with no point at which the user must actively acknowledge is not
one: without a floor, a 9000 bps term is legal, displayed in a line item, and clicked past.
The floor refuses no legal term and rejects no peer — it only refuses to *send* a term the
user has not seen — so it takes nothing back from "no protocol ceiling below `10000`". The
number `500` is a UI threshold, not a wire value: it appears in no event, and a client MAY
confirm more often than this floor requires. Recorded in revision `1.2` (§0).

#### `OPEN-3` — public versus private bids as the default — **CLOSED: public is the default**

Public bidding (§6) is the default a conformant client implements first and the behaviour a
user gets without changing a setting. Private bidding (§6.1) remains specified and permitted.
Decided 2026-09-09, revision `1.1`.

What was considered: public bids give **price discovery** and a **visible reputation trail**;
private bids give the bidder anonymity but leave the board looking empty.

Why public won: an empty-looking board is the documented killer of marketplaces of this
shape. A visitor who sees a request with no visible bids cannot tell an unserved market from
a dead one, and leaves — which is self-fulfilling, because the next visitor sees the same
nothing. Public bids also give v1 the only *reputation material* it has: there is no escrow
(§11.4), so "this provider bid publicly and delivered" is what limits the residual risk, and
that trail only exists if bidding is public by default. Note that this closes the default,
not `OPEN-6` — whether Nenya defines a reputation *primitive* over that material is still
open. Bidder anonymity is a real cost, and it is the reason private bidding stays specified
rather than being deleted — but it is the exception a user opts into per bid (§6.1), not the
default that starves the board.

Consequence for implementers: an implementation supporting **only** public bidding is
conformant; one supporting **only** private bidding is not (§17, item 3).

### 16.2 Still open

Each item below is reserved for a human maintainer. Items are of two sorts, and the duty
differs:

- **Implementation-affecting** (`OPEN-4` through `OPEN-8`) — the item constrains what an
  implementation does. Implementations MUST surface these as configuration or as an explicit
  "unsupported" state, never as a silent default — except where the item's own entry states
  that the duty is already discharged by a rule elsewhere in this document, which is the case
  for `OPEN-8` alone.
- **Publication decision** (`OPEN-9`) — the item constrains nothing an implementation does
  and blocks no code. It is indexed here so that it is surfaced to a maintainer before the
  moment it stops being reversible, and it carries no implementation duty at all.

Nothing outside this section derives a requirement from this preamble; §8.1's visibility duty
for a client-local fee limit is stated in §8.1 itself and belongs to a **closed** decision
(§16.1), not to an open one.

#### `OPEN-4` — ecash settlement evidence

See §9.4.

#### `OPEN-5` — the `license` tag vocabulary

Candidates: SPDX identifiers (precise, wrong shape for commissioned work); a small Nenya enum
such as `exclusive` / `non-exclusive` / `cc0` / `personal-use`; free text with no machine
meaning. Until resolved, `license` is free text and implementations MUST NOT act on its
value.

#### `OPEN-6` — reputation

Whether Nenya adopts GammaMarkets `kind:31555` reviews as its reputation primitive, defines
its own, or defines none in v1 and leaves reputation entirely to the embedding client.
Reputation is load-bearing for a marketplace with no escrow, so "none" is a real cost.

#### `OPEN-7` — binding the fee invoice to the fee recipient

See §8.7.

#### `OPEN-8` — coordinating the `type=5` and `type=6` assignments

`type=5` (delivery commitment, §10.1) and `type=6` (private bid, §6.1) on `kind:16` are
Nenya's extensions to a vocabulary GammaMarkets defines and Nenya does not own. Both are
**assigned and normative** — a spec with an unimplementable section is worse than one with a
coordination risk — and both are wire-usable today; what is open is only whether to
coordinate the numbers upstream. Candidates: propose both upstream to GammaMarkets before
publishing; move the two Nenya-specific messages to a Nenya-private rumor kind and give up
`kind:16` interop for them; accept the `nenya`-tag disambiguation in §7.4 as sufficient.
`type=6` joined this item in revision `1.2`; the number `8` is unchanged so the references in
§7.4 and §6.1 stay valid.

There is nothing here for an implementation to configure or to declare unsupported, so this
section's preamble duty is discharged for this item by §7.4's standing rule: ignore a `type=5`
or `type=6` that does not carry a `nenya` version the implementation implements, and never
interpret a foreign one. That rule is what makes the assignment safe to use before the
coordination question is answered, and it is why this item blocks nothing.

#### `OPEN-9` — registering `30404` in the kind registry

**Publication decision. Does not block implementation, and imposes no duty on an
implementation.** Whether to file a `registry-of-kinds` pull request staking `30404` (§5.2,
`OPEN-1` in §16.1). Candidates: **file it before publishing** — good citizenship, and it is
the only thing that actually prevents a collision with a future official NIP-99 extension,
but it announces the project publicly, possibly earlier than intended; **publish first, file
later** — keeps the announcement under the maintainer's control, at the cost of a window in
which someone else can take the number; **never file** — relies on the Appendix B migration
path if a collision ever happens, which is the outcome `OPEN-1` accepted as bounded.

This was a live question in revision `1.1` but carried no number, so it appeared in no index
and nothing in this document would have raised it before publication — the one moment at
which it becomes irreversible. Numbering it is the whole fix. If it is instead moved to a
repo-level release checklist, delete this entry rather than leaving it here answered.

### 16.3 Other decisions with known costs

Noted as **not open**, but flagged so a future revision knows they were made deliberately:
`SAT` rather than `btc` as the price unit (§4.4); two kinds rather than one kind with a
buy/sell discriminator (§5); rejecting rather than resolving duplicate single-cardinality
tags (§4.3); and pay-on-delivery ordering rather than a hold-invoice pay-for-key exchange
(§11.4).

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

Buyer `B` posts a request; provider `P` bids; a client operator `F` takes 2.5%.

```
1.  B  → relays      kind:30404  d=lighthouse-loop-2026-09
                     price 120000 SAT, t=nenya, t=wtb, m=video/mp4, expiration set

2.  P  → relays      kind:1111  A/a = 30404:B:lighthouse-loop-2026-09
                     K/k = 30404, P/p = B
                     price 90000 SAT, fee 250 bps → F, deliver_by set
                     (public — the default; a private bid per §6.1 would
                      carry the same terms as a sealed kind:16 type=6
                      to B instead, with item=<coordinate> and no order tag)

3.  B  → P  (sealed) kind:16 type=1  order=9f2c…  item=<coordinate>
                     amount_msat=90000000  fee=250,F
                                                              state: proposed

4.  P  → B  (sealed) kind:16 type=3  status=accepted
                                                              state: accepted

5.  P  → B  (sealed) kind:16 type=5  url, x, ox, m, size
                                                              state: committed

6.  P  → B  (sealed) kind:16 type=2  payee=provider
                     bolt11 for exactly 90 000 000 msat
    F  → B  (sealed) kind:16 type=2  payee=fee,F
                     bolt11 for exactly  2 250 000 msat
                     (sealed by F's own key — a fee invoice
                      forwarded by P would be rejected; and F
                      must publish a kind:10050 or B may not
                      propose this term at all, §8.1)
                     Both invoices are accepted as part of THIS
                     transition, which is the only point at which
                     a fee invoice is legal (§8.5). Had the fee
                     computed to 0 msat, no fee invoice would
                     exist and none would be awaited (§8.3).
                                                              state: awaiting_payment

7.  B pays both.
    B  → P  (sealed) kind:17  payee=provider  payment=lightning,<bolt11>,<preimage>
    B  → F  (sealed) kind:17  payee=fee,F     payment=lightning,<bolt11>,<preimage>
    P and F each verify SHA-256(preimage) == payment_hash of the invoice
    they themselves issued, byte-identical.
                                                              state: paid

8.  P  → B  (sealed) kind:15  order=9f2c…  url, decryption-key,
                     decryption-nonce, x, ox, size, file-type
                     (the order tag is what routes it, §7.4;
                      file-type must equal the commitment's m, §10.3)
                                                              state: released

9.  B downloads, checks SHA-256(ciphertext) == x, decrypts,
    checks SHA-256(plaintext) == ox.
                                                              state: settled

10. B  → relays      kind:30404  same d, status=fulfilled
                     (and a kind:5 deletion request if the request is to disappear)
                     This is the LISTING status vocabulary, and it is an
                     announcement to the board — it is not the order's
                     state and no party may read order state from it (§11.1).
```

Money, for the record:

```
provider receives   90 000 SAT   ( 90 000 000 msat )
fee recipient gets   2 250 SAT   (  2 250 000 msat, = floor(90 000 000 × 250 / 10 000) )
buyer pays          92 250 SAT
```

A combined `92 250` invoice never exists, because no party is permitted to receive money that
is not theirs.

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

Nenya needs four values out of an invoice, and nothing else. This appendix states exactly
which, so an implementation need not read the reference implementation to know what to parse.
[BOLT-11][bolt11] is normative for everything here.

An invoice is bech32 (with the 90-character limit lifted): a human-readable part, the
separator `1`, a data part of 5-bit groups, and a 6-character checksum. The separator is the
**last** `1` in the string, never the first: the bech32 alphabet contains no `1`, while the
human-readable part legitimately may — inside the optional amount, as in `lnbc1500n1…`.

**Case is Nenya's rule, not BOLT-11's.** BOLT-11 prescribes an all-uppercase encoding for QR
codes and publishes an all-uppercase invoice among its own valid examples, so an uppercase
invoice is a valid BOLT-11 invoice. Nenya nevertheless **MUST reject** an invoice containing
any uppercase character, and MUST NOT normalise one: §9.2 check 1 compares the string in a
receipt byte for byte against the string in the stored `type=2`, and a case fold is precisely
the re-encoding that comparison forbids. A sender holding an uppercase invoice lowercases it
before putting it in a `payment` tag. Revision `1.3` and earlier attributed this rule to
BOLT-11, which was an error of fact; the rule itself is unchanged.

**1. Amount — from the human-readable part.** The HRP is `ln` + a network prefix (`bc`,
`tb`, `bcrt`, …) + an OPTIONAL amount, which is a decimal integer followed by a multiplier:

| Multiplier | Meaning | msat per unit |
|---|---|---|
| `m` | milli (10⁻³ BTC) | 100 000 000 |
| `u` | micro (10⁻⁶ BTC) | 100 000 |
| `n` | nano (10⁻⁹ BTC) | 100 |
| `p` | pico (10⁻¹² BTC) | 0.1 |
| *(none)* | whole BTC | 100 000 000 000 |

If an amount is present it MUST be a positive decimal integer with no leading zeroes; an
implementation MUST reject `lnbc0…` and `lnbc0500u…` alike. A `p` amount MUST be a multiple of
10, because msat is the smallest representable unit; an invoice violating that is invalid. An
invoice with **no** amount is an "any amount" invoice and MUST be rejected by Nenya (§9.2 step
4). The multiplication overflows a double for large amounts, so the same arbitrary-precision
rule as §4.4 applies — note that the largest legal `p` figure, the supply cap at
21 000 000 000 000 000 000 pico-BTC, is itself above a signed 64-bit range, so a 64-bit
implementation divides by 10 **before** it multiplies.

**2. Timestamp.** The first 35 bits (7 bech32 characters) of the data part, big-endian, Unix
seconds.

**3. Tagged fields.** The rest of the data part, until the 104-character signature, is a
sequence of `(type: 5 bits, length: 10 bits in two 5-bit groups, data: length × 5 bits)`.

**A tagged field whose length is not the one its type calls for MUST be skipped as though its
type were unknown, and MUST NOT reject the invoice.** This is BOLT-11's own reader rule; every
deployed Lightning wallet implements it, and BOLT-11's valid example 14 is an invoice carrying
eight such fields deliberately, so an implementation that rejected them would refuse invoices
real wallets pay. Revision `1.3` and earlier said the opposite of this for `p`; see §0 for why
the correction does not weaken §9.2's evidence rule.

Nenya reads six fields and requires three of them:

- `p` (type 1) — the **payment hash**, `length == 52`, 260 bits of which the first 256 are the
  hash. REQUIRED. An implementation MUST reject an invoice with no correct-length `p` field,
  and MUST reject one carrying a **second** correct-length `p` field: "first wins" and "last
  wins" are both defensible, which is exactly why neither may be chosen silently (§4.3), and
  §9.2 check 3 compares a preimage against *the* payment hash of the stored invoice. A `p`
  field of any other length is skipped by the rule above and does not satisfy this one.
- `s` (type 16) — the **payment secret**, `length == 52`. REQUIRED. Nenya reads no value out
  of it and publishes none; what it requires is that the field be there, because BOLT-11
  requires it and every deployed wallet refuses an invoice without one.
- `d` (type 13, variable length) and `h` (type 23, `length == 52`) — the **description** and
  the **description hash**. Exactly one of the two is REQUIRED. With both, the two say
  different things about what is being paid for and no reader can tell which the issuer meant.
- `x` (type 6) — **expiry** in seconds, big-endian over its data groups. OPTIONAL; if absent,
  the default is **3600**. A second `x` MUST be rejected, for the reason a second `p` is. The
  invoice is expired when `timestamp + expiry` is in the past relative to **the value the
  injected clock held at the moment the invoice was accepted** — the value §9.2 check 1
  requires be persisted alongside the invoice, and never the clock as it reads at
  receipt-verification time. §9.2 check 5 is normative for this; an invoice that was live when
  the buyer paid it does not become unpaid because it has since expired.

- `c` (type 24) — `min_final_cltv_expiry_delta`. OPTIONAL; if absent, the default is **18**.
  Nenya routes no payment and acts on it nowhere; an embedding wallet does, so a reader MAY
  publish it. A repeat takes BOLT-11's reader rule — the first wins.
- `9` (type 5) — the **feature bits**, big-endian over the field, numbered from the least
  significant bit of that field so a bit's position depends on the field's width. OPTIONAL;
  absent means no bit is set. A reader MAY publish the set of bits so that an embedding wallet
  can apply BOLT-11's own even/odd rule. See below: Nenya applies none of it. A repeat takes
  BOLT-11's reader rule.

An `x` value, and **every** `c` value including a repeated one, MUST be rejected when the
value is 2^64 or more. The bound is on the **value** and never on the field's width: a
13-group field holding 2^64 − 1 is legal, and a wide field of leading zeros holding a small
number is that number. An implementation whose integers are signed 64-bit values MUST NOT let
such a value read as negative — a negative expiry inverts §9.2 check 5's comparison and expires
every such invoice instantly; saturating at the largest representable value is the fail-closed
reading, because an expiry that large is one that never runs out.

Every other tagged field MUST be skipped by its length, never guessed at.

**4. Signature.** The final 104 characters (512-bit signature + 1 recovery byte) over the HRP
and data part. Verifying it, and recovering the node key from it, requires secp256k1 and is
OPTIONAL in v1 (§9.2). An implementation that skips it MUST still parse past it correctly and
MUST verify the bech32 checksum.

**What a reader MUST do, and what Nenya does not do.** An implementation reading an invoice
for Nenya MUST: verify the bech32 checksum; split at the last `1`; apply the human-readable
part and amount rules above; require `s`; require exactly one of `d` and `h`; require exactly
one correct-length `p`; refuse an `x` or `c` value of 2^64 or more; and refuse a data part
shorter than 117 characters, which is the 7-character timestamp, the 104-character signature
and the 6-character checksum with no room left for anything else.

It MUST NOT report as performed any of the following, none of which Nenya performs:
**signature verification**, **node-key recovery**, the **low-S** rule, and any judgement of
the **feature bits** an invoice sets. Those are the paying wallet's, and §9.2's evidence rule
is the preimage rather than the invoice's signature (§17's last paragraph makes the duty to
publish that distinction explicit). An implementation MAY publish the signature bytes, the
recovery id, the SHA-256 of the signed data and the feature bits, so that an embedding
application with a secp256k1 library and a routing stack can do those things itself;
publishing a value is not judging it.

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
