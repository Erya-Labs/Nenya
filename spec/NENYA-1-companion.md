# NENYA-1 companion — rationale, decisions and a worked example

**This document is non-normative.** The contract is [`spec/NENYA-1.md`](NENYA-1.md), and nothing
here adds to it, qualifies it or takes anything away from it. Where this document and NENYA-1
disagree, **NENYA-1 is right and this document is a defect**; report it as one. An implementation
is conformant on the strength of NENYA-1 alone — §17 of that document is the only conformance
criterion — and no sentence here is a requirement, whatever words it happens to use.

What it holds is the material a reader needs once and an implementer does not need at the
keyboard: why a rule exists, what the alternatives were, what was decided and what it cost. It
exists because NENYA-1 had grown too long to read, and the requirements were buried in the
reasoning that produced them.

**Section numbers mirror NENYA-1's.** `§16.2` here is NENYA-1's `§16.2`, and Appendix A here is
NENYA-1's Appendix A. Nothing is renumbered in either document, in either direction: a citation of
`§11.4` or of `OPEN-5` resolves in both, and the identifiers `OPEN-1`…`OPEN-9` keep the numbers
they were given. Sections appear here only where they had non-normative material to give; the gaps
in the numbering are sections that had none.

**Every requirement sentence stayed behind**, whether or not code enforces it: not one of the
RFC-2119 key words NENYA-1 §2 defines appears anywhere in this file **outside the
revision-history table in §0**, which is a property a grep can check and is meant to be checked.
That table is the one exception because a changelog row describing a past change is not the rule it
describes — the rule itself lives in its own section of NENYA-1 and stayed there — and a published
history is not rewritten to suit a grep, so the rows are reproduced word for word. So a reader who
wants the rules reads NENYA-1 and can ignore this file entirely; a reader who wants the reasons
reads this one.

---

## 0. Status of this document

NENYA-1 §0 says which revision is current. This is the history of how it got there, one row per
revision, reproduced unchanged.

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
| `1.8` | 2026-10-06 | Non-normative material moved to the companion document `spec/NENYA-1-companion.md` — §11.4's residual-risk discussion, §13's trust-model elaboration, three of §15's interoperability notes, §16's decision narratives and Appendix A's worked order — leaving a pointer in each place. **No requirement changed and nothing was renumbered:** every sentence carrying an RFC-2119 key word stayed in this document, the `OPEN-1`…`OPEN-9` identifiers and every `§x.y` number mean what they meant, and no wire value, tag, cardinality, requirement level, evidence rule, state or fee rule is touched. |
| `1.9` | 2026-10-07 | The rest of the extraction begun in `1.8`: this revision-history table itself, §1's motivation, §3's rationale for distrusting the seams, and the rationale paragraphs of §4.2, §4.5, §6.1, §8.2, §8.6, §9.3, §10.5 and §10.6 moved to the companion, leaving a pointer in each place. The per-revision notes for `1.1`, `1.2`, `1.3` and `1.5` moved with the table; the notes for `1.4`, `1.6` and `1.7` stayed, because each carries a requirement sentence. **No requirement changed and nothing was renumbered:** every sentence carrying an RFC-2119 key word stayed in NENYA-1, the sole exception being the key words inside rows `1.3`, `1.4`, `1.5` and `1.7` of this table, which describe past changes and state no rule; every `§x.y` number and every `OPEN-n` identifier means what it meant; and no wire value, tag, cardinality, requirement level, evidence rule, state or fee rule is touched. `nenya-core/src/commonTest/resources/spec/nenya-1-11.2-transitions.psv` was regenerated in the same commit, its `(from, to)` pair set unchanged. |

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

NENYA-1 §1 lists the six things Nenya adds to close that gap, and what is out of scope.

The first target audience is people without access to generative media tooling paying
people who have it. Nothing in the wire format is specific to that audience; "digital
media" is an `m` (MIME type) tag, not a hardcoded assumption.

---

## 3. Roles and the seams between them

**Why the five seams are untrusted.** This is not defensive style. It is the reason the
payment-evidence rule and the signed fee term exist: both are cases where the natural,
convenient design is to believe an injected component, and both are cases where believing it
loses the user money.

---

## 4. Preliminaries

### 4.2 Addressable coordinates

**Why a coordinate and not an event id.** This is not a stylistic preference. NIP-15 auctions
bind bids to an event id, and NIP-15 itself has to warn that an auction cannot be edited once
bid on, because every edit detaches every bid. Coordinate-scoping makes that failure
unrepresentable.

### 4.5 Version and discovery tags

`t` is used because relays index single-letter tags only. This is the single hardest
constraint on the whole design: **anything that must be filtered server-side has to ride on
a single-letter tag.** A discriminator on `price`, `status`, `fee` or any other multi-letter
name is invisible to a relay and forces every client to download the whole board.

---

## 6. Bids — NIP-22 `kind:1111`

### 6.1 Private bidding

Revision `1.1` specified the private bid's *contents* but pinned no rumor kind for it, which
left two implementations free to encode it differently and not interoperate. `type=6` closes
that hole. `kind:14` was rejected because §5.4 puts terms in tags and `kind:14` is defined as
free text; `kind:15` is a file message; `type=1` is excluded by §6.1 itself.

---

## 8. The fee term

### 8.2 Price semantics

**Why the fee is an added line item and not a deduction.** The alternative — fee deducted
from price — means the same listing shows a different effective price in every client
depending on that client's fee configuration, which destroys price comparison across the
board and makes the fee invisible to the provider.

### 8.6 Two invoices, never one — the non-custodial rule

A single combined invoice means somebody receives money that is not theirs and forwards part
of it. That is custody, and in most jurisdictions it is money transmission. There is no
version of that design in NENYA-1.

**The provider invoice's sealing key.** A provider invoice from another key is somebody
else's bill under the provider's name, and §9.2 check 1 would then anchor the whole order's
payment evidence to it.

**One accepted `type=2` per `(order, payee)`.** The first of §8.6's two uniqueness rules
refuses a re-pointing after the fact: check 1 is a comparison against a stored string, so
replacing that string retroactively changes which payment settles the order, and any party
that can get a second `type=2` accepted can aim the check wherever it likes. It refuses one
flow that looks conformant and is worth naming rather than smuggling — a provider re-sending
its invoice, for instance because the first went unanswered. That is refused; an invoice that
expires unpaid is not re-issued inside the order, and the correct response to an order whose
invoice has died is §11.2's, not a second invoice.

**One invoice per order.** The second rule is the non-custodial rule above, enforced at the
moment of storage instead of at settlement. One invoice presented as both payees' bills is a
combined invoice with extra steps: whoever is paid holds `price_msat + fee_msat` and owes
somebody the difference, which is the custody §8.6 exists to rule out. Caught at storage, it
is refused before the buyer pays; caught at settlement, it is refused after — and the money
has already moved.

**And the same reasoning for time.** §8.6's refusal of a dead invoice and §9.2 check 5's name
the same fact, and only the earlier one is useful: a buyer shown a dead invoice has been shown
a bill nothing will settle, and an implementation that stores it and refuses the receipt has
waited until after the payment to say so. Nothing is weakened by this — an invoice accepted
under the rule is one check 5 will accept, and check 5 stays where it is for evidence arriving
against a store the implementation did not itself write.

---

## 9. Payment evidence

### 9.3 Invoice binding and privacy

The binding is transport-level precisely because the alternative — writing the order id
into the invoice description — would publish that identifier to the payer's wallet, the
payee's node, and every routing hop. See §12.

---

## 10. Deliverables and hash commitments

### 10.5 What the commitment does and does not prove

Because `ox` is published **before** the buyer pays and **before** the key exists in the
buyer's hands, a provider cannot substitute a different file after payment: the only file
whose plaintext hashes to `ox` is the one they committed to.

Publishing `ox` in a public bid additionally lets a third party later adjudicate a "you
delivered something other than what you committed to" claim without either party's
cooperation. NENYA-1 §10.5 states the correlation cost that buys, and what an implementation
owes the user about it.

### 10.6 Metadata

A blob host that strips metadata server-side has already seen the original. Because the
commitment hashes are computed after stripping, stripping is not merely advisory — a
deliverable stripped after commitment fails the `ox` check.

---

## 11. Order lifecycle

### 11.4 Where escrow would go

v1 is **pay-on-delivery plus reputation**. The residual risk is stated plainly rather than
engineered away: the buyer pays after the commitment but before the key, so a provider who takes
payment and withholds the key steals the price. What limits that is that the provider is publicly
identified by their bid, the commitment is a signed artefact, and the loss is bounded by the price.

The `paid → disputed` transition is the only mechanism v1 has that acts on that risk, and it is
why §11.2 binds it to `deliver_by` rather than to an unnamed timeout: an order that a provider
abandons after payment must reach a terminal, visible, comparable state at a moment both parties
agreed to in advance, not at whichever timeout each client happened to choose. `disputed` resolves
nothing (§14 item 3); what it does is stop the order pretending to be live. §11.2's verification
deadline is the companion rule for the state after that one: it acts on no risk a provider
controls — the key is already released by then — and exists so that an order whose blob never
verified, because it was served at the wrong length, because the download never completed or
because the buyer never looked, stops pretending to be live too.

---

## 13. Security considerations

**The trust model, in more detail.** The injected signer sees every event before it is signed; the
injected wallet sees every invoice; the injected transport sees whatever the client hands it. A
malicious embedding client can do anything the user can do. NENYA-1 §13 states the line itself and
states the duty not to claim otherwise.

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

---

## 15. Interoperability notes

The two notes that carry a requirement — `alt` on a request, and the bid's human-readable
terms — stayed in NENYA-1 §15. These are the rest.

- A NIP-99 client that knows nothing about Nenya renders a Nenya **offer** correctly: it is
  an unmodified `kind:30402`, and every Nenya-specific tag is additive. The `SAT` currency
  token is the one place such a client may render oddly.
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

This is the reasoning behind the decisions NENYA-1 §16 records. The decisions themselves, their
numbers, and every duty they impose are in NENYA-1; what is here is what was considered, why it
went the way it did, and what it cost — so a future revision does not relitigate them blind.

A note on the numbering, since it looks gappy. The three items closed in revision `1.1` keep
their original numbers, and the five that were open then keep theirs: `OPEN-4` through `OPEN-8`
are numbered exactly as they were in revision `1.0`. Revision `1.2` added `OPEN-9` at the end —
a question that was already live and merely unnumbered — and broadened `OPEN-8`'s subject without
changing its number. NENYA-1 §16 states the rule that nothing is ever renumbered, and why.

### 16.1 Closed decisions

#### `OPEN-1` — the request kind number — **CLOSED: `30404`**

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
here unnumbered, in no index, which meant nothing in NENYA-1 would surface it before
publication, the one moment at which it stops being reversible. It is a publication decision,
not a wire-format one, and the number is usable either way.

Consequence for implementers: the number is now fixed, so **fixtures and test vectors may be
written against it**. The revision `1.0` prohibition on writing a fixture before this
decision no longer applies.

#### `OPEN-2` — a hard ceiling on fee basis points — **CLOSED: no ceiling below `10000`**

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

The escape valve is local policy, not protocol policy, and NENYA-1 §16.1 states the client's
latitude and §8.1 the visibility it owes. That keeps the choice with the operator who bears it,
and keeps it visible.

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
number `500` is a UI threshold, not a wire value: it appears in no event. Recorded in
revision `1.2` (§0).

#### `OPEN-3` — public versus private bids as the default — **CLOSED: public is the default**

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

Items are of two sorts — implementation-affecting (`OPEN-4` through `OPEN-8`) and a publication
decision (`OPEN-9`) — and NENYA-1 §16.2 states the duty each sort carries and indexes all six.
These are the candidates, for the four items that have candidates of their own; `OPEN-4` and
`OPEN-7` state their subject by pointing at §9.4 and §8.7 and had nothing further to move.

#### `OPEN-5` — the `license` tag vocabulary

Candidates: SPDX identifiers (precise, wrong shape for commissioned work); a small Nenya enum
such as `exclusive` / `non-exclusive` / `cc0` / `personal-use`; free text with no machine
meaning. NENYA-1 §16.2 states what an implementation does with the token until this resolves.

#### `OPEN-6` — reputation

Whether Nenya adopts GammaMarkets `kind:31555` reviews as its reputation primitive, defines
its own, or defines none in v1 and leaves reputation entirely to the embedding client.
Reputation is load-bearing for a marketplace with no escrow, so "none" is a real cost.

#### `OPEN-8` — coordinating the `type=5` and `type=6` assignments

`type=5` (delivery commitment, §10.1) and `type=6` (private bid, §6.1) on `kind:16` are
Nenya's extensions to a vocabulary GammaMarkets defines and Nenya does not own. Both are
**assigned and normative** — a spec with an unimplementable section is worse than one with a
coordination risk — and both are wire-usable today; what is open is only whether to
coordinate the numbers upstream. Candidates: propose both upstream to GammaMarkets before
publishing; move the two Nenya-specific messages to a Nenya-private rumor kind and give up
`kind:16` interop for them; accept the `nenya`-tag disambiguation in §7.4 as sufficient.
Why the assignment is safe to use before the coordination question is answered — §7.4's standing
rule about a foreign `type` — is stated in NENYA-1 §16.2 itself, and is why this item blocks
nothing.

#### `OPEN-9` — registering `30404` in the kind registry

Whether to file a `registry-of-kinds` pull request staking `30404` (§5.2, `OPEN-1` in §16.1).
Candidates: **file it before publishing** — good citizenship, and it is the only thing that
actually prevents a collision with a future official NIP-99 extension, but it announces the
project publicly, possibly earlier than intended; **publish first, file later** — keeps the
announcement under the maintainer's control, at the cost of a window in which someone else can
take the number; **never file** — relies on the Appendix B migration path if a collision ever
happens, which is the outcome `OPEN-1` accepted as bounded.

This was a live question in revision `1.1` but carried no number, so it appeared in no index
and nothing in NENYA-1 would have raised it before publication — the one moment at
which it becomes irreversible. Numbering it is the whole fix. If it is instead moved to a
repo-level release checklist, delete this entry rather than leaving it here answered.

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
