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
RFC-2119 key words NENYA-1 §2 defines appears anywhere in this file, which is a property a grep can
check and is meant to be checked. So a reader who wants the rules reads NENYA-1 and can ignore this
file entirely; a reader who wants the reasons reads this one.

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
