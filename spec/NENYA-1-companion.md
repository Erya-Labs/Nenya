# NENYA-1 companion

This document is non-normative. It gives the reasons for NENYA-1's rules, the alternatives
considered, the revision history and a worked order. The contract is [`spec/NENYA-1.md`](NENYA-1.md):
where the two disagree, NENYA-1 governs and this document is in error. Conformance is defined by
NENYA-1 §17 alone. Section numbers and `OPEN-n` identifiers are NENYA-1's and are never renumbered;
sections with nothing to add are omitted. No RFC 2119 keyword in capitals appears outside the §0
table, whose rows describe past changes rather than state rules.

## 0. Status of this document

NENYA-1 §0 names the current revision. No revision has changed the `["nenya", "1"]` version tag
(Appendix B).

| Revision | Date | Change |
|---|---|---|
| `1.0` | 2026-09-09 | First published revision. Eight items open (`OPEN-1`–`OPEN-8`). |
| `1.1` | 2026-09-09 | `OPEN-1`, `OPEN-2` and `OPEN-3` closed: the request kind is `30404`, replacing a placeholder throughout; fee basis points are legal from `0` to `10000` with mandatory disclosure and no lower protocol ceiling; public bidding is the default. `OPEN-4`–`OPEN-8` remain open under their original numbers. Closing an OPEN item is not a major change. |
| `1.2` | 2026-09-09 | Consistency repair; no new wire semantics. §4.1 escaping aligned with deployed implementations (the seven shortcut escapes; every other character below `0x20` as `\u00XX`), deviating from NIP-01's literal wording; only ids of events containing such other characters change, and those events had no valid serialisation under `1.1`. Twenty internal contradictions resolved, among them the fee-invoice deadlock (§8.5, §11.2), the zero-`fee_msat` deadlock (§8.3, §11.1), the `fee` tag stated as both mandatory and optional (§7.5, §8.1), the undefined release deadline (§11.2), the unbound `kind:15` release (§7.4, §10.3) and the `m`/`file-type` identity (§10.1, §10.3). `kind:16` `type=6` assigned to the private bid, a minor change; `item` given a row in §5.3. The confirmation floor above `500` bps (§8.1) made a standing duty as part of the `OPEN-2` closure. `OPEN-8` broadened to `type=6`; `OPEN-9` (registering `30404`) added. Two example timestamps corrected (§6, §7.5); all worked numbers recomputed, usable as fixtures apart from `<…>` stand-ins. |
| `1.3` | 2026-09-16 | §4.1: text with no UTF-8 encoding, including an unpaired UTF-16 surrogate, is rejected rather than serialised with a substitute character. Cross-referenced from §4.3; added to §18's event-id vectors. No computed id changed: a newly rejected event had no id two implementations agreed on. |
| `1.4` | 2026-09-17 | Appendix C corrected against BOLT-11 and completed as a reader specification. The lowercase-invoice rule restated as Nenya's, not BOLT-11's (§4.3, Appendix C); a tagged field of the wrong length is skipped as unknown instead of rejecting the invoice; the payment secret `s` is required. The reader rules Nenya enforces, and the BOLT-11 checks it does not perform, are stated. No tag meaning, fee arithmetic or state changed; the skip and `s` rules change which invoices §9.2 can read. |
| `1.5` | 2026-09-19 | Four refusals stated. §7.6: the provider's key is known independently of the proposal, and a `type=3` acceptance sealed by any other key, the buyer's included, is rejected. §8.6: a `payee=provider` `type=2` sealed by a key other than the provider's is rejected; a second `type=2` for an accepted `(order, payee)` is rejected; an invoice accepted for one payee is rejected for another payee on the same order; an invoice already expired when it would be accepted is rejected then. No tag meaning, fee arithmetic or state changed. A tightening: §7.4 and §7.6 already named each sender, so a `1.4`-conformant sender emits nothing `1.5` rejects; the one flow refused is a provider re-sending its invoice. |
| `1.6` | 2026-09-23 | A verification deadline out of `released`, as a second `released → disputed` row in §11.2. The deadline is local: the clock reading at release plus a window the implementation applies and displays, falling back to the reading at `paid`, then to `deliver_by`; with none of the three, the order reports that it has no deadline rather than that it is pending. §10.4, §11.4 and §18 updated. No tag meaning, fee arithmetic, evidence rule or state changed; the set of `(from, to)` pairs is unchanged, and nothing new is sent. |
| `1.7` | 2026-09-23 | The gift-wrap envelope written as a procedure, with bounds that make it representable. §4.3's defaults refused wraps whose rumor JSON exceeded 7 168 bytes, so `kind:13` and `kind:1059` are bounded by NIP-44 instead: plaintext at most 65 535 bytes, wrap `content` at most 87 472 characters, rumor JSON at most 40 960 bytes on write. §7.1 restated as numbered write and read procedures with six previously unstated refusals; §3 gained the one-time signer; §4.1 states what a signature covers; §7.2 states when a message can be reported as signature-verified; §18 records the offline gift-wrap checks. No tag meaning, fee arithmetic, evidence rule or state changed. A tightening: NIP-59 already required an unsigned rumor and an empty-tagged seal, and the raised bounds refuse nothing previously accepted. |
| `1.8` | 2026-10-06 | Non-normative material moved to this companion: §11.4's residual-risk discussion, §13's trust model, three §15 notes, §16's decision narratives and Appendix A's worked order. No requirement changed; nothing renumbered. |
| `1.9` | 2026-10-07 | The rest of the extraction: this table, §1's motivation, §3's rationale and the rationale paragraphs of §4.2, §4.5, §6.1, §8.2, §8.6, §9.3, §10.5 and §10.6 moved here. The notes for `1.1`, `1.2`, `1.3` and `1.5` moved with the table; those for `1.4`, `1.6` and `1.7` stayed in NENYA-1. No requirement changed; nothing renumbered. `nenya-1-11.2-transitions.psv` regenerated, its `(from, to)` pairs unchanged. |
| `1.10` | 2026-10-23 | A plain-language pass over the prose of §0, §2, §4.1, §4.3, §6, §7.1, §7.4, §7.5, §8.1, §8.5, §9, §9.2, §9.4, §10.3, §11.1, §11.2 and Appendix C. No requirement, keyword, identifier, wire value, example, vector, bound or section number changed; sentences that could not be simplified safely were left as they were and listed for the maintainer. `nenya-1-11.2-transitions.psv` regenerated (row block moved from lines 1766–1780 to 1767–1781), its `(from, to)` pairs unchanged. |
| `1.11` | 2026-10-24 | The request-side `t` token renamed from `wtb` to `request` (§5.2, §5.3), after feedback on nips issue #2467; nothing was deployed, so nothing migrates. The offer-side `wts` token, the `["nenya", "1"]` tag and every other value unchanged. The five places quoting the token updated: §5.2's example, §5.3's prose, §5.5's filter example, §6's note and companion Appendix A. `nenya-1-11.2-transitions.psv` not regenerated: NENYA-1 still had 2 307 lines and the row block had not moved. |
| `1.12` | 2026-10-31 | Prose condensed from 2 307 to 1 435 lines (45% fewer words). Each rule is stated once, in its home section; restatements removed; rationale and history moved here, including the `1.4`, `1.6` and `1.7` notes formerly in NENYA-1 §0; explanatory passages replaced by figures in `docs/`. No requirement changed: every keyword sentence of `1.11` was checked against `1.12`. Section numbers, `OPEN-n` identifiers, tables, worked examples, vectors and bounds unchanged. `spec/NENYA-1-overview.md` added (non-normative). `nenya-1-11.2-transitions.psv` regenerated (row block moved from lines 1767–1781 to 1079–1093), its `(from, to)` pairs unchanged. |
| `1.13` | 2026-10-31 | Citation typo fixed: §5.3's `image` row and §5.4 cited "§12.10", but §12 is a numbered list with no subsections; both now cite §12 item 10. No requirement, keyword, identifier, wire value or section number changed; no line of NENYA-1 moved, so `nenya-1-11.2-transitions.psv` is unchanged. |

## 1. Motivation and scope

NIP-99 defines a listing that offers something for sale. No NIP, registered kind or surveyed client
defines the buying side, and NIP-99 has no vocabulary for a bid, order, delivery, payment, fee or
dispute. Clients invent these or borrow them from NIP-15, which is deprecated and whose checkout uses
deprecated encryption. NENYA-1 §1 lists what Nenya adds and what is out of scope. The first intended
users are people without generative media tools commissioning people who have them. The wire format
does not depend on that audience: the deliverable's type is an `m` (MIME type) tag.

## 3. Roles and client-supplied interfaces

In the payment-evidence rule (§9) and the signed fee term (§8), the convenient design believes a
client-supplied interface, and believing it loses the user money. That is why all five interfaces
are untrusted. The one-time signer is its own interface: one returning only a signature cannot
produce the NIP-44 conversation key a gift wrap needs, and one returning the secret key would pass a
secret key across the interface.

## 4. Preliminaries

### 4.1 Event ids and signatures

Read literally, NIP-01 puts raw control bytes such as `0x01` inside a JSON string, which RFC 8259
forbids. The output is not JSON, and its id differs from the one deployed implementations compute,
so each side would see the other's events as forged. §4.1 states what `nostr-tools` and `go-nostr`
do: `JSON.stringify` and the Go escaper both emit lowercase `\u00XX` for control characters outside
the seven, and agree byte for byte. For an event with no such character, which includes every
NENYA-1 example, both readings give the same bytes.

An unpaired surrogate can arrive as a JSON escape such as `\uD800`, which common parsers accept.
Encoders then substitute differently (`?` on the JVM, U+FFFD in JavaScript), so substitution gives
one event several ids. Rejection keeps ids equal everywhere.

### 4.2 Addressable coordinates

NIP-15 binds auction bids to an event id and warns that an auction cannot be edited once bid on,
because each edit detaches every bid. A coordinate is unchanged by edits.

### 4.3 Encodings, tags and hostile input

Duplicate single-cardinality tags are rejected because "first wins" and "last wins" are both
defensible, so two conformant implementations could read different prices from one signed event.
Resource bounds are practical: relays returning 100 MiB "events" exist.

A wrap's `content` is base64 over a NIP-44 payload holding the seal's JSON, whose `content` is
base64 over a payload holding the rumor's JSON. Each layer adds a version byte, a 32-byte nonce, a
2-byte length and a 32-byte MAC and pads to a power-of-two-derived size; base64 adds a third. Under
the 16 KiB default, a 7 169-byte rumor already produced a wrap the reader had to refuse. The rumor
keeps the ordinary defaults. A rumor of 40 961 bytes or more pads to a seal too large to wrap, so
that bound applies on write, where the sender sees the error. The 65 535-byte plaintext ceiling
follows NIP-44's published vectors, which list 65 536 among the lengths encryption refuses, and
every deployed application, not a later revision's extended-length prefix that nothing emits. As in
§4.1 and Appendix C, where a document's text and every working implementation disagree, NENYA-1
follows the implementations.

### 4.4 Money

NIP-99 suggests ISO 4217 codes and uses `btc` in its own example. `SAT` keeps decimals out of the
money path and matches nostr's settlement vocabularies, which count in satoshis or millisatoshis.
Some NIP-99 clients render `SAT` poorly, hence the display in both units. The supply in
millisatoshis exceeds 2^53, so a double, the default number type in JavaScript, TypeScript and Lua,
cannot hold every amount exactly.

### 4.5 Version and discovery tags

Relays index single-letter tags only. A discriminator in `price`, `status`, `fee` or any other
multi-letter tag is invisible to relays and makes each client download the whole board.

## 5. The board

An offer (§5.1) is an unmodified NIP-99 `kind:30402`, so existing NIP-99 clients render it without
knowing Nenya. NIP-99 allows only `active` and `sold` as an offer's `status`, so order states stay in
the private thread (§11).

`30404` (§5.2) is a single named constant because duplicated wire constants diverge, not because the
value can change (Appendix B). Generic clients render an unregistered kind only through `alt`, which
is why requests carry it. The extended request `status` vocabulary is possible because the kind is
Nenya's own.

Applying the listing rules of §5.3 to every event would reject NENYA-1's own bid example, which has
one `["t", "nenya"]` and no `request` or `wts`; the validator therefore takes kind and `type` as
input. Several values in one tag filter widen it (§5.5): `{"#t": ["nenya", "request"]}` matches
either tag. A relay that silently drops an unregistered kind looks like a publisher bug; the
read-back check and the distinct "relay did not accept" condition tell the two apart.

## 6. Bids

A bid scoped by event id would detach when the listing is edited (§4.2). Public bids give price
discovery and a record of who bid what. Users accustomed to private quotes do not expect that, so
they are told before their first bid. Text generated from the tags cannot drift from them, and
generic NIP-22 clients show only the text and `alt` (§15).

### 6.1 Private bidding

Revision `1.1` specified a private bid's contents but no rumor kind, so implementations could encode
it incompatibly. Revision `1.2` assigned `kind:16` `type=6`. `kind:14` was rejected because it is
free text and terms are carried in tags (§5.4); `kind:15` is a file message; `type=1` is a proposal.
A `1.1` implementation ignores `type=6` (§7.4), so a private bid sent to it is not received, the
same failure as a missing `kind:10050` (§7.3). This is why §17 requires public bidding.

An order id exists only once the buyer proposes, and a bid shaped like a proposal could be processed
as one and advance state. A `type=6` with an `order` tag is therefore rejected, not accepted with the
tag ignored. With no public thread to scope to, `item` carries the binding. A bid
silently made private leaves a bidder without the reputation trail and price signal it expected.

## 7. The private channel

The envelope's numbered procedures (§7.1) state refusals that NIP-59 implied but did not state as
checkable rules. Every layer carries the object form, so one escaping routine serves all layers and a
rumor's `content` round-trips byte for byte; §4.1's array form exists only to be hashed. Kind
`21059` is defined elsewhere for another purpose. A wrap key that either party uses elsewhere links
the two copies, and a seal carrying the true time restores the timing correlation the random
timestamps remove.

The read steps that need no cryptography come first, so a wrap addressed to someone else is
discarded before any decryption. NIP-59 requires an unsigned rumor: a `sig` key on one comes from a
sender that misunderstands the construction or from an attempt to pass the rumor off as signed. An
unknown top-level key indicates another protocol or a value smuggled past a lenient parser. Seal and
wrap timestamps are never grounds for rejection (§4.6), because the write procedure backdates them
by up to two days. The signature steps are conditional because a reader without a verifier would
otherwise receive nothing; verifying and then ignoring the result is what is ruled out.

The fee term is signed by the seal that carries it (§7.2), not by a signature on the rumor.
Reporting a message as signature-verified is permitted, not required, since "authenticated by
decryption" is always truthful; one label for both kinds of message claims the stronger property
for the weaker case. The fee recipient has no delivery role (§2) but counts as a counterparty for
§7.3, because its invoice reaches the buyer sealed by its own key.

Shipping NIP-99 clients already implement the GammaMarkets vocabulary (§7.4), which references items
by coordinate and counts in satoshis. `kind:15` carries `order` because revision `1.1` bound a
release to its order only by hash equality, which gave no way to route releases between two
concurrent orders with the same provider (§10.3).

A proposal (§7.5) still acceptable after its own `deliver_by` would make acceptance and `expired`
both correct, so `expiration` falls strictly earlier; in NENYA-1's example the window is four hours
and `deliver_by` is copied from the bid. Revision `1.1`'s "exactly one `fee` tag" contradicted
§8.1's reading of an omitted tag as zero fee; the read rule governs. Without the sealing-key check of
§7.6, a buyer could seal a `type=3` carrying its own terms byte for byte, reach `accepted`, and then
send itself the provider's invoice (§8.6). The term comparison establishes what was agreed and the
key check who agreed; neither replaces the other.

## 8. The fee term

A fee held in client configuration is indistinguishable on the wire from skimming. The buyer pays P,
the provider expects P, and the client settles the provider at P·(1−f). Nothing on the wire records
f, so no party can detect or audit it. A signed term puts f on the wire.

The `OPEN-2` closure chose disclosure over a cap (§16.1). A local limit (§8.1) is reported as its
own condition so that local policy is not presented as a peer's fault. Without §8.1's reachability
check, an order naming an unreachable fee recipient is wire-valid but can only expire, because §8.7
needs the fee invoice sealed by that recipient and §11.2 waits for it: the dead end §7.3 prevents,
one party further out. A fee deducted from the price instead of added (§8.2) would make one listing
show a different price in each client, defeating comparison and hiding the fee from the provider.

§8.5 governs payment, not the arrival of the fee invoice. §11.2 makes that invoice part of entering
`awaiting_payment`, so rejecting it in `committed`, as revision `1.1`'s wording did, leaves every
fee-bearing order unable to reach `awaiting_payment`, Appendix A step 6 included. A test of §11.3
invariant 2 that forbids the request rather than the payment tests the wrong thing. A fee paid
earlier could not be recovered on a refund, because the fee recipient has no refund obligation.

A combined invoice (§8.6) makes one party receive and forward money that is not its own. That is
custody, and in most jurisdictions money transmission. One invoice offered as both payees' bills
amounts to the same, and refusing it at storage stops it before the buyer pays. A provider invoice
sealed by another key is someone else's bill, and §9.2 check 1 would anchor the order's evidence to
it. Check 1 compares against a stored string, so a second accepted `type=2` would change which
payment settles the order. This refuses a provider re-sending an unanswered invoice: an invoice that
expires unpaid is not re-issued, and the order follows §11.2 instead. An expired invoice is refused
at acceptance because the buyer would otherwise be shown a bill nothing will settle; §9.2 check 5
remains for evidence checked against a store the implementation did not write. The fee-invoice
binding of §8.7 uses only the checks §7.2 already requires, and makes the fee recipient a
participant in the conversation rather than a line in a configuration file.

## 9. Payment evidence

Zap receipts (§9.1) are also public events linking payer, payee and amount. The payee releases the
preimage only when the payment settles (§9.2), so a buyer obtains it only by paying, and a payee
gains nothing by fabricating a receipt against its own invoice. The check needs only SHA-256. Each
accepted request is stored with its acceptance time (§17 item 6) because a receipt cannot be checked
against a discarded invoice, and an invoice live when paid does not become unpaid by expiring later.
A fee that computes to zero has no invoice; waiting for one would leave the order to expire. Ecash
(§9.4) offers the most privacy and a likely basis for future escrow; on-chain settlement is too
slow, too costly and too public for these amounts.

### 9.3 Invoice binding and privacy

An order id in the invoice description would reach the payer's wallet, the payee's node and every
routing hop (§12). A reused static address links every order its owner settles, and an LNURL fetch
discloses the payer's IP address to the recipient's server.

## 10. Deliverables and hash commitments

The commitment (§10.1) precedes payment; a decryption key in it would let the buyer decrypt without
paying. As an ordinary NIP-17 file message, the release (§10.3) renders in generic clients.
The two name the MIME type by their own kinds' conventions (`m`, `file-type`), so the mapping is
stated explicitly. A wrong length, a download bound or an incomplete download (§10.4) says nothing
about the committed bytes, and the blob can later be served correctly; such an order leaves
`released` at the verification deadline (§11.2), as does one whose buyer never verifies. A host that
strips metadata server-side (§10.6) has already seen the original, and hashes are computed after
stripping, so a file stripped after commitment fails the `ox` check.

### 10.5 What the commitment does and does not prove

`ox` is published before the buyer pays or holds the key, so no other file can match it afterwards.
It says nothing about quality, and Nenya defines no quality check. In a public bid, `ox` lets a third
party judge a claim that the delivered file differs from the committed one without either party's
help. If the plaintext ever becomes public, the hash links it to the bid, so publication is opt-in.

## 11. Order lifecycle

`expiration` and `deliver_by` bound acts the counterparty owes, so both parties fix them in advance
as wire terms. At `released` the only outstanding act is the buyer's own hash check, which the
provider cannot affect. A wire term for it would add to the terms §7.6 compares, for a risk §11.4
does not describe, so the §11.2 verification window is local and can differ between
implementations. With no anchor the order stays in `released`, because §4.6 rules out taking a time
from elsewhere. Revision `1.1` named a "release deadline" defined nowhere; each of §11.2's three
deadlines now states what it runs from and what follows once it has passed.

### 11.4 Where escrow would go

v1 is pay-on-delivery plus reputation. The buyer pays before receiving the key, so a provider can
take the payment and withhold the key. The provider is publicly identified by its bid, the
commitment is signed, and the loss is bounded by the price; NENYA-1 §11.4 also recommends a visible
cap on value transacted without escrow. `paid → disputed` is the only v1 transition acting on that
risk. It is bound to `deliver_by` so that an abandoned order reaches a terminal, visible state at a
time both parties agreed in advance. `disputed` resolves nothing (§14 item 3); it marks the order as
no longer live. The verification deadline does the same for `released`, where the key is already
out and the cause is not the provider's: a wrong-length blob, an incomplete download, or a buyer who
never checked.

## 13. Security considerations

The embedding client supplies the signer, which sees every event before signing; the wallet, which
sees every invoice; and the transport. A malicious client can do anything the user can do. Within
that model:

- a dishonest counterparty cannot advance state by assertion (§9), substitute the deliverable after
  commitment (§10) or impersonate another party in the private channel (§7.2);
- a dishonest client can refuse to transact, misrepresent in its UI or exfiltrate through its
  signer, but cannot produce a wire-valid order that underpays the provider against the signed
  terms, because the fee is signed and a disagreeing split can be refused (§8);
- a dishonest relay can withhold, delay and reorder events and sees every public event. It cannot
  forge an event or read a gift wrap, and it sees each wrap's recipient but not its sender.

## 15. Interoperability notes

- NIP-99 clients render a Nenya offer as an ordinary `kind:30402`, since Nenya's tags are additive;
  `SAT` can render oddly. They cannot render a `kind:30404` request, so generic clients show `alt`.
- NIP-22 clients render a bid as a comment on an address they may not resolve; the text and `alt`
  carry the terms. This is a convention, not a guarantee, and the text is display only (§5.4).
- NIP-17 clients show order traffic as a direct-message thread: `kind:14` and `kind:15` normally,
  `kind:16` and `kind:17` as unknown kinds that are usually hidden.
- GammaMarkets shares the `kind:16`/`kind:17` vocabulary and the `item`, `amount` and `payment` tag
  shapes. Nenya adds `type=5`, `type=6`, `amount_msat`, `fee`, `payee` and order-scoped hash
  commitments, and is stricter about evidence and static Lightning addresses. Each side parses the
  other's messages up to unknown tags; one without `["nenya", "1"]` is not a Nenya order message.

## 16. Decisions, closed and open

`OPEN-n` numbers are cited across NENYA-1, so none is ever changed. `OPEN-4`–`OPEN-8` keep their
`1.0` numbers; `1.2` added `OPEN-9` and widened `OPEN-8` in place. The client-local policies that
closed decisions permit (§8.1, §6.1) each carry their own visibility rule. Of the decisions in
§16.3, §4.4 gives the reason for `SAT`, §4.3 for rejecting duplicate tags, and §11.4 the cost of
pay-on-delivery.

### 16.1 Closed decisions

All three were decided on 2026-09-09, in revision `1.1`.

`OPEN-1`, the request kind number (closed: `30404`). The kind is addressable because a request is
edited and retracted by `(kind, pubkey, d)`.

| Candidate | For | Against | Outcome |
|---|---|---|---|
| `30404` | unclaimed in the NIPs table and the kind registry; reads as a neighbour of NIP-99's `30402`/`30403` | the obvious number for a future NIP-99 extension, so unregistered use is squatting, as GammaMarkets did with `30405`/`30406` | chosen |
| `33402` | empty band, no collision risk, echoes `402` | conveys nothing to a reader | rejected |
| `32767` / `32768` | already used by SatShoot | inherits another project's semantics and its changes | rejected |

Legibility outweighed the squatting risk. A later official claim on `30404` would be a major
version and a migration (Appendix B), not a silent reinterpretation. Registration is `OPEN-9`.
Fixtures and test vectors can use the fixed number; `1.0` barred fixtures until this decision.

`OPEN-2`, a hard ceiling on fee basis points (closed: no ceiling below `10000`).

| Candidate | For | Against | Outcome |
|---|---|---|---|
| No cap, mandatory disclosure | keeps the protocol neutral; disclosure protects the provider at every rate; no number to revisit | a 9000 bps term is legal | chosen |
| Cap at 1000 bps (10%) | matches the usual marketplace fee | sets fee policy for every deployment without evidence; conformant peers reject each other's legal events | rejected |
| Cap at 2000 bps (20%) | more headroom | the same objection; the number is arbitrary | rejected |

The fee §8 guards against is a hidden one, not a large one. A cap does nothing about a fee absent
from the tag, and a capped implementation refuses legal, signed, disclosed events. Disclosure is the
protection: a fee both parties see in bps and msat, in a term neither can alter without aborting the
order. Lower limits are client-local policy (§8.1), which leaves the choice with the operator who
bears it. The `500` bps confirmation floor was interim in `1.0`, tied to this item; `1.2` kept it as
a standing part of the decision, because disclosure without active acknowledgement lets a 9000 bps
term be clicked past. The floor refuses no legal term and rejects no peer. `500` is a UI threshold
that appears in no event, and a client can confirm more often.

`OPEN-3`, public versus private bids as the default (closed: public is the default). Public bids
give price discovery and a reputation trail. Private bids give the bidder anonymity but make the
board look empty, and a visitor who sees no bids cannot tell an unserved market from an inactive
one. With no escrow (§11.4), a record of public bids and deliveries is v1's only reputation
material; a primitive over it is `OPEN-6`. Anonymity is why private bidding remains as a per-bid
choice (§6.1).

### 16.2 Still open

Each item is reserved for a human maintainer. `OPEN-4` and `OPEN-7` are described in NENYA-1 §9.4
and §8.7.

- `OPEN-5`, the `license` vocabulary. Candidates: SPDX identifiers, which are precise but shaped for
  software rather than commissioned work; a small enumeration such as `exclusive`, `non-exclusive`,
  `cc0` and `personal-use`; free text with no machine meaning.
- `OPEN-6`, reputation. Candidates: GammaMarkets `kind:31555` reviews; a Nenya primitive; none in
  v1, leaving reputation to the client, which has a real cost without escrow.
- `OPEN-8`, the `type=5` and `type=6` assignments, which extend a vocabulary GammaMarkets defines.
  Both are normative and usable now, since an unimplementable section is worse than a coordination
  risk, and §7.4's rule for a foreign `type` keeps them safe meanwhile. Candidates: propose both
  upstream before publication; move both to a Nenya-specific rumor kind, giving up `kind:16`
  interoperability for them; treat the `nenya`-tag disambiguation as sufficient.
- `OPEN-9`, registering `30404`. Candidates: file before publishing, which prevents a collision but
  announces the project early; publish first and file later, which leaves a window in which the
  number can be taken; never file, relying on Appendix B's migration, which `OPEN-1` accepted as
  bounded. In `1.1` the question had no number and appeared in no index, so nothing would have
  raised it before publication, after which it cannot be reversed.

## Appendix A. A complete worked order

Buyer `B` posts a request, provider `P` bids, and client operator `F` takes 2.5%. The same trade is
drawn in [`../docs/trade-sequence.svg`](../docs/trade-sequence.svg).

```
1.  B  → relays      kind:30404  d=lighthouse-loop-2026-09
                     price 120000 SAT, t=nenya, t=request, m=video/mp4, expiration set
2.  P  → relays      kind:1111  A/a = 30404:B:lighthouse-loop-2026-09, K/k = 30404, P/p = B
                     price 90000 SAT, fee 250 bps → F, deliver_by set
                     (public, the default; a private bid, §6.1, is a sealed kind:16
                      type=6 to B with the same terms, item=<coordinate>, no order tag)
3.  B  → P  (sealed) kind:16 type=1  order=9f2c…  item=<coordinate>
                     amount_msat=90000000  fee=250,F                   state: proposed
4.  P  → B  (sealed) kind:16 type=3  status=accepted                   state: accepted
5.  P  → B  (sealed) kind:16 type=5  url, x, ox, m, size               state: committed
6.  P  → B  (sealed) kind:16 type=2  payee=provider
                     bolt11 for exactly 90 000 000 msat
    F  → B  (sealed) kind:16 type=2  payee=fee,F
                     bolt11 for exactly  2 250 000 msat
                     (sealed by F's own key, §8.7; F publishes a kind:10050, §8.1.
                      Both invoices are accepted in this transition, the only point
                      at which a fee invoice is legal, §8.5. A fee of 0 msat would
                      mean no fee invoice and none awaited, §8.3.)
                                                                       state: awaiting_payment
7.  B pays both.
    B  → P  (sealed) kind:17  payee=provider  payment=lightning,<bolt11>,<preimage>
    B  → F  (sealed) kind:17  payee=fee,F     payment=lightning,<bolt11>,<preimage>
    P and F each verify SHA-256(preimage) == payment_hash of the invoice
    it issued, byte-identical.                                         state: paid
8.  P  → B  (sealed) kind:15  order=9f2c…  url, decryption-key,
                     decryption-nonce, x, ox, size, file-type
                     (routed by the order tag, §7.4; file-type equals the commitment's m, §10.3)
                                                                       state: released
9.  B downloads, checks SHA-256(ciphertext) == x, decrypts,
    checks SHA-256(plaintext) == ox.                                   state: settled
10. B  → relays      kind:30404  same d, status=fulfilled
                     (and a kind:5 deletion request if the request is withdrawn)
                     Listing status, not order state, which is never read from it (§11.1).
```

The money, with no combined 92 250 SAT invoice anywhere (§8.6):

```
provider receives   90 000 SAT   ( 90 000 000 msat )
fee recipient gets   2 250 SAT   (  2 250 000 msat, = floor(90 000 000 × 250 / 10 000) )
buyer pays          92 250 SAT
```

## Appendix C. The BOLT-11 fields Nenya requires

The lowercase-only rule is Nenya's. BOLT-11 prescribes uppercase for QR codes and lists an
all-uppercase invoice among its valid examples; revision `1.4` corrected an attribution of the rule
to BOLT-11, not the rule itself. Skipping a wrong-length tagged field is BOLT-11's own reader rule.
Deployed wallets implement it, and BOLT-11's valid example 14 carries eight such fields, so
rejecting them refused invoices wallets pay. §9.2 is unaffected: exactly one 52-group `p` is still
required, and the evidence is the preimage. `s` is required because BOLT-11 and every deployed
wallet require it; BOLT-11's invalid examples include one whose only defect is its absence. Nenya
reads nothing from it.

An invoice carrying both `d` and `h` gives two descriptions, with no way to tell which the issuer
meant. An `x` value from 2^63 to 2^64 − 1 is an expiry that never runs out in practice; saturating
keeps that meaning, where a negative reading would invert it. Signature, node-key, low-S and feature-bit checks
belong to the paying wallet.
