# Nenya

![How a Nenya trade works: six animated steps between buyer and seller, each naming the cheat it stops](docs/trade-flow.svg)

**A marketplace layer for nostr clients.** Nenya is a library you import, not an app you
run and not a service you sign up for. It gives a nostr client the wire format and the
verification logic for a two-sided, non-custodial marketplace: one side posts a **request**
("I want a 30-second animation of a lighthouse in a storm, budget 120k sats"), the other
side posts an **offer** or bids on the request, and the two settle directly in bitcoin over
Lightning. Nenya holds no funds, runs no server, and takes no cut.

**Want to see it running?** The [Coracle marketplace fork](https://github.com/Erya-Labs/coracle/tree/marketplace)
trades end to end against the JavaScript build of this library — listings, bids and verified
Lightning-settled deliveries inside a real nostr client, with a
[step-by-step trade guide](https://github.com/Erya-Labs/coracle/blob/marketplace/MARKETPLACE.md).

**Adding Nenya to your own web client?** [`docs/INTEGRATING.md`](docs/INTEGRATING.md) covers loading
the JavaScript build, the seams your client supplies, and the order of calls in one trade. A JVM
test holds its entry-point index equal to the library's exported functions.

The contract is the microstandard in [`spec/NENYA-1.md`](spec/NENYA-1.md); `nenya-core` is
its first reference implementation. Anything in the spec can be implemented in TypeScript,
Rust or Swift against any nostr stack, and it will interoperate with `nenya-core` without
sharing a line of code.

> **Status: early.** The wire format is settled enough to implement against, and the proof
> of concept above actually trades. But nothing is published to a package registry, the
> request kind is not yet registered upstream, and six decisions are still open (`OPEN-4`
> through `OPEN-9`, indexed in §16.2). Do not build a product on this yet. Do read the spec
> and tell us where it is wrong.

---

## Why

NIP-99 gave nostr a decent classified listing — and nothing else a market needs. There is no
want-to-buy convention in the NIPs, the kind registry, or any shipping client, so every
existing marketplace is seller-side only: a catalogue, not a market. Past the ad there is no
vocabulary at all for a bid, an order, a delivery, a payment, a fee or a dispute — only
NIP-15, which upstream marks `unrecommended` and whose checkout rides the deprecated NIP-04.

Nenya fills that gap and nothing else:

- a **request** kind (`30404`) mirroring NIP-99's tag vocabulary, so one parser and one
  renderer serve both sides of the board;
- **bids** as NIP-22 comments on an addressable *coordinate*, so a bid survives its listing
  being edited (NIP-15 auctions bind bids to an event id, and break on every edit);
- a **private order channel** over NIP-17 gift wrap, so terms, invoices, delivery and
  decryption keys never reach a public relay;
- a **signed fee term**, so a client that takes a handling fee cannot take it silently;
- **payment evidence** — a preimage whose SHA-256 matches the invoice's payment hash,
  verified locally, or nothing;
- a **hash commitment** on the deliverable, so a provider cannot swap the file after being
  paid.

**Offers ride NIP-99 `kind:30402` unmodified.** Every Nenya-specific tag is additive, so
clients that already render classified listings render a Nenya offer today without knowing
Nenya exists.

---

## How it fits together

![Two clients, two channels: the public board carries requests, offers and bids between the two clients; the sealed private channel carries terms, bill, payment proof and unlock key. nenya-core verifies everything itself; the signer, wallet and relay transport are injected by the embedding client.](docs/how-it-fits.svg)

![The life of an order: proposed, accepted, committed, awaiting_payment, paid, released, settled along the happy path; cancelled and expired reachable until the payment proof verifies; disputed from committed onward. Drawn from the machine-checked transcription of section 11.2.](docs/order-lifecycle.svg)

---

## Trust model — read this before you integrate

**Nenya protects your user against their counterparties and against relays. It does not
protect them against your client.** The signer, the wallet and the relay transport are
injected by the embedding client — that is what makes the library portable, and it is also
the boundary of what it can defend. A malicious embedding client can do anything the user
can do, and no amount of care inside this library changes that. If you ship Nenya, do not
tell your users otherwise.

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

## The spec

[`spec/NENYA-1.md`](spec/NENYA-1.md) is the normative document: event shapes, the tag
vocabulary, money and fee arithmetic, the private-channel envelope, payment evidence,
deliverable commitments, the order state machine, privacy requirements and a conformance
checklist, in RFC 2119 language. [`spec/NENYA-1-companion.md`](spec/NENYA-1-companion.md)
is its non-normative companion: the revision history, the rationale, the candidates and
reasoning behind every decision in §16, and a complete worked order. Every requirement
sentence lives in the normative document alone.

If you are implementing NENYA-1 in another language, §17 (conformance) and Appendix C (the
four BOLT-11 fields the evidence rule needs) are the two sections to read closely.

---

## Modules

| Module | What it is |
|---|---|
| `:nenya-core` | **Kotlin Multiplatform: one `commonMain` implementation compiled for the JVM and for JavaScript** — the Coracle PoC runs the JS build. The wire format, the money type, fee arithmetic, the order state machine, event id computation, tag codecs, BOLT-11 parsing and preimage verification. No Android, no network, no I/O. |

`nenya-core` depends on **nothing but the Kotlin standard library** (JUnit for tests).
Everything that touches the outside world — signing, relay sockets, wallets, clocks,
randomness — is an interface you implement. That is what makes the whole library testable
offline with fakes, and it is also the trust boundary described above. Where the secret key
lives is not Nenya's business: an in-process key, a NIP-55 signer app or a remote signer
are equally valid behind the signer seam.

```
nenya-core/src/commonMain/kotlin/dev/eryalabs/nenya/ implementation shared by JVM and JS
nenya-core/src/jvmMain/kotlin/dev/eryalabs/nenya/    implementation still JVM-bound
nenya-core/src/commonTest/kotlin/dev/eryalabs/nenya/ tests run on every target
nenya-core/src/jvmTest/kotlin/dev/eryalabs/nenya/    JVM-only tests (reflection, JDK oracles, files on disk)
nenya-core/src/jsTest/kotlin/dev/eryalabs/nenya/     JS test oracles and the JS classes of split tests
nenya-core/src/commonTest/resources/                 test vectors and fixtures
spec/                                               the microstandard
```

Package root: `dev.eryalabs.nenya`, with Kotlin's `explicitApi()` on — for a library whose
whole purpose is to be a contract, an accidentally public symbol is a breaking change
waiting to happen.

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
examples. A test that only encodes the implementer's own understanding of a spec proves very
little. And checks whose failure would cost a user money carry a negative control: a test
proving the check *rejects* what it must reject, not only that it accepts what it should.

---

## Contributing

The most useful contribution right now is a careful read of `spec/NENYA-1.md` — especially
if you maintain a nostr client, a NIP-99 marketplace, or anything that would have to
interoperate with this. Concrete disagreements about the wire format are more valuable than
code at this stage, and the open decisions indexed in §16.2 are genuinely open — their
candidates are in §16 of the companion.

Please open an issue rather than a large pull request against the spec; a wire format is
cheap to change today and expensive to change after anyone ships it.

---

## License

MIT. See [LICENSE](LICENSE).

Built by [Erya Labs](https://eryalabs.dev). Nenya is named after the ring of adamant — the
one that preserves rather than rules.
