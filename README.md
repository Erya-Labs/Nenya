# Nenya

**A marketplace layer for nostr clients.** Nenya is a library you import, not an app you
run and not a service you sign up for. It gives a nostr client the wire format and the
verification logic for a two-sided, non-custodial marketplace: one side posts a **request**
("I want a 30-second animation of a lighthouse in a storm, budget 120k sats"), the other
side posts an **offer** or bids on the request, and the two settle directly in bitcoin over
Lightning. Nenya holds no funds, runs no server, and takes no cut.

The contract is the written microstandard in [`spec/NENYA-1.md`](spec/NENYA-1.md).
`nenya-core` is the first reference implementation of it. Anything in the spec can be
implemented in TypeScript, Rust or Swift against any nostr stack, and it will interoperate
with `nenya-core` without sharing a line of code.

> **Status: early.** The protocol core is under construction. Nothing is published, nothing
> is on a relay, and no artifact is released. The wire format is settled enough to implement
> against — the request kind, the fee rules and the bidding default are all decided, and
> fixtures may be written against them — but six decisions remain open, marked `OPEN-4`
> through `OPEN-9` inline and indexed in §16.2, and only one of those (`OPEN-9`) is a
> publication question rather than an implementation one. Do not build a product on this
> yet. Do read the spec and tell us where it is wrong.

---

## Why

NIP-99 gave nostr a decent classified listing: an addressable event saying *this thing is
for sale*. It gave nostr nothing for the other half of a marketplace. There is no
want-to-buy convention anywhere in the NIPs, the kind registry, or any shipping client —
every implementation we surveyed is seller-side only. A board with only one side is a
catalogue, not a market.

NIP-99 also stops at the ad. There is no vocabulary for a bid, an order, a delivery, a
payment, a fee, or a dispute. Clients either invent one each or borrow from NIP-15, which
upstream marks `unrecommended` and whose checkout still rides the deprecated NIP-04.

Nenya fills that gap and nothing else:

- a **request** kind mirroring NIP-99's tag vocabulary, so one parser and one renderer serve
  both sides of the board;
- **bids** as NIP-22 comments scoped to an addressable *coordinate*, so a bid survives its
  listing being edited (NIP-15 auctions bind bids to an event id, and therefore break on
  every edit);
- a **private order channel** over NIP-17 gift wrap, so terms, invoices, delivery and
  decryption keys never reach a public relay;
- a **signed fee term**, so a client that takes a handling fee cannot take it silently;
- a **payment-evidence rule** — a preimage whose SHA-256 matches the invoice's payment hash,
  verified locally, or nothing;
- a **hash commitment** on the deliverable, so a provider cannot swap the file after being
  paid.

**Offers ride NIP-99 `kind:30402` unmodified.** Every Nenya-specific tag is additive, so
clients that already render classified listings render a Nenya offer today without knowing
Nenya exists.

The first audience we have in mind is people without access to generative media tooling
paying people who have it. Nothing in the wire format assumes that: the deliverable is
described by a MIME type, not by a hardcoded category.

---

## Trust model — read this before you integrate

**Nenya protects your user against their counterparties and against relays. It does not
protect them against your client.**

The wallet, the signer and the relay transport are all *injected by the embedding client*.
That is what makes the library portable, and it is also the boundary of what it can defend.
Your signer sees every event before it is signed. Your wallet sees every invoice. A
malicious embedding client can do anything the user can do, and no amount of care inside
this library changes that. If you ship Nenya, do not tell your users otherwise.

Inside that boundary, the design does buy real things:

| Against | What Nenya guarantees |
|---|---|
| A dishonest **counterparty** | Cannot advance an order by claiming to have paid — the only accepted evidence is a Lightning preimage the library hashes itself. Cannot substitute the deliverable after committing to its hash. Cannot impersonate another party in the private channel (the seal's pubkey must equal the rumor's, per NIP-17). |
| A dishonest **relay** | Can withhold, delay and reorder events, and can read every public listing. Cannot forge one (ids are recomputed, signatures checked). Cannot read a gift wrap or learn an order's participants from it. |
| A dishonest **client** (partial) | Cannot skim silently. The fee is a signed term of the deal — basis points plus recipient, sealed and signed — and the other side refuses any payment split that disagrees with it. It can still lie in its own UI; it cannot produce a wire-valid order that under-pays the provider. |

Two rules follow from this and are enforced throughout the library:

1. **Never advance state on an injected interface's say-so.** A `Boolean isPaid` from a
   wallet is not evidence. Neither is a status string from a counterparty, a relay's
   acceptance of an event, or a NIP-57 zap receipt (NIP-57 itself says a zap receipt is not
   proof of payment).
2. **Price means what the provider receives.** A fee is an added line item on top, never a
   silent subtraction — otherwise the same listing shows a different price in every client
   and the provider cannot see what was taken.

---

## What Nenya deliberately does not do

This list is part of the spec, not marketing. A system doing any of these is not
implementing NENYA-1.

- **No custody.** No party ever holds funds belonging to another. The buyer pays the
  provider and (if there is one) the fee recipient with two separate payments to two
  separate invoices. There is no combined invoice that somebody splits, because that is
  custody and in most jurisdictions it is money transmission.
- **No escrow in v1.** No hold, no multisig, no locktime refund. The order state machine
  reserves a seam where escrow could later be inserted, and nothing more. v1 is
  pay-on-delivery plus reputation, and the residual risk is written down rather than
  papered over.
- **No arbitration.** No arbiter role, no appeal, no dispute resolution. `disputed` is a
  terminal label meaning "this ended badly".
- **No index server.** Discovery is relay filters. There is no Nenya search API, no
  aggregator, no canonical board.
- **No relay, blob server, mint or push server** operated as part of the protocol. Bring
  your own.
- **No fee to Erya Labs.** There is exactly one fee mechanism, it is optional, it is a
  signed term between the two transacting parties, and its recipient is named in that term.
  There is no protocol-level cut, no default recipient, and no hardcoded pubkey anywhere in
  the spec or the code.
- **No identity verification, no content scanning, no moderation policy.** Reporting and
  muting are your client's business.
- **No NIP-04, and no NIP-90 or NIP-15 foundation.** All three are deprecated or
  `unrecommended` upstream.

---

## How it fits together

```
  buyer's client                                       provider's client
 ┌───────────────┐                                    ┌───────────────┐
 │  your UI      │                                    │  your UI      │
 ├───────────────┤   public board (relays)            ├───────────────┤
 │  nenya-core   │  request  kind:<request>  ───────► │  nenya-core   │
 │               │  offer    kind:30402      ◄─────── │               │
 │               │  bid      kind:1111       ◄─────── │               │
 │               │                                    │               │
 │               │   private order thread             │               │
 │               │  NIP-17 gift wrap (kind:1059)      │               │
 │               │  terms · invoice · receipt · key   │               │
 ├───────────────┤  ◄──────────────────────────────►  ├───────────────┤
 │ signer wallet │                                    │ signer wallet │
 │   transport   │   (you inject these; see above)    │   transport   │
 └───────────────┘                                    └───────────────┘
```

An order runs `proposed → accepted → committed → awaiting_payment → paid → released →
settled`, with `cancelled`, `expired` and `disputed` as terminals. Every transition is
gated on something the library verified itself: an id it recomputed, a hash it computed, a
seal whose pubkey it checked. The state machine is a total function — every (state, event)
pair either produces a new state or is explicitly refused, so a forgotten case cannot fall
through to the happy path.

---

## The spec

[`spec/NENYA-1.md`](spec/NENYA-1.md) is the normative document: event shapes, the full tag
vocabulary, money encoding and fee arithmetic, the private-channel envelope, the payment
evidence rule, deliverable commitments, the order state machine, privacy requirements, and
a conformance checklist. It uses RFC 2119 language and cites the NIPs it builds on.

Decisions are recorded in §16 with their candidates and trade-offs, rather than silently
defaulted — closed ones in §16.1 with the reasoning that settled them, open ones in §16.2.
Requests are `kind:30404`, which is unregistered: implementations must still expose it as a
single named constant, not because it may change but because a duplicated wire constant is
how call sites drift apart.

If you are implementing NENYA-1 in another language, §17 (conformance) and Appendix C (the
four BOLT-11 fields the evidence rule needs) are the two sections to read closely.

---

## Modules

| Module | What it is |
|---|---|
| `:nenya-core` | Plain Kotlin/JVM. The wire format, the money type, the fee arithmetic, the order state machine, event id computation, tag codecs, BOLT-11 parsing and preimage verification. No Android, no network, no I/O. |

`nenya-core` is deliberately platform-free and depends on **nothing but the Kotlin standard
library** (JUnit for tests). Everything that touches the outside world — signing, relay
sockets, wallets, clocks, randomness — is an interface you implement. That is what makes
the whole library testable offline with fakes, and it is also the trust boundary described
above.

Signing is abstract by design: an in-process key, a NIP-55 Android signer app, or a remote
signer are all equally valid behind the `NostrSigner` seam. Nenya does not know or care
where the secret lives.

```
nenya-core/src/commonMain/kotlin/dev/eryalabs/nenya/ implementation shared by JVM and JS
nenya-core/src/jvmMain/kotlin/dev/eryalabs/nenya/    implementation still JVM-bound
nenya-core/src/commonTest/kotlin/dev/eryalabs/nenya/ tests run on every target
nenya-core/src/jvmTest/kotlin/dev/eryalabs/nenya/    JVM-only tests
nenya-core/src/commonTest/resources/                 test vectors and fixtures (compiled into common tests as constants)
spec/                                               the microstandard
```

Package root: `dev.eryalabs.nenya`. The published API runs with Kotlin's `explicitApi()` on,
because for a library whose whole purpose is to be a contract, an accidentally public symbol
is a breaking change waiting to happen.

---

## Build and test

Requires a JDK 17 toolchain. For the JVM tests and the JS compile, nothing else — no Android
SDK, no emulator, no Node, no network access, no relay.

```sh
./gradlew :nenya-core:jvmTest :nenya-core:compileTestKotlinJs  # JVM tests + proof common code compiles for JS
./gradlew :nenya-core:jsNodeTest  # JS tests under Node (downloads Node, Yarn and npm packages)
./gradlew :nenya-core:build       # everything, JS included (same downloads)
```

Where an externally-authored test vector exists, we use it rather than a fixture we wrote
ourselves — the BIP-340 CSV, the NIP-44 vector file, NIP-17's worked gift wraps, the BOLT-11
examples. A test that only encodes the implementer's own understanding of a spec proves
very little; a test against someone else's vectors catches the misunderstanding.

Checks whose failure would cost a user money carry a negative control: a test proving the
check *rejects* what it must reject, not only that it accepts what it should.

---

## Contributing

The most useful contribution right now is a careful read of `spec/NENYA-1.md` — especially
if you maintain a nostr client, a NIP-99 marketplace, or anything that would have to
interoperate with this. Concrete disagreements about the wire format are more valuable than
code at this stage, and the open decisions in §16 are genuinely open.

Please open an issue rather than a large pull request against the spec; a wire format is
cheap to change today and expensive to change after anyone ships it.

---

## License

MIT. See [LICENSE](LICENSE).

Built by [Erya Labs](https://eryalabs.dev). Nenya is named after the ring of adamant — the
one that preserves rather than rules.
