# Integrating Nenya into a JavaScript client

This guide is for a developer adding Nenya to a nostr web client. It covers how to load the
build, what the client has to supply, how values cross between JavaScript and the library, and
the order of calls in one trade. The contract is [`spec/NENYA-1.md`](../spec/NENYA-1.md); where
this guide and the specification disagree, the specification is right.

This guide makes no promise about what the library checks. The library states that itself: call
`capabilities()` (see [What Nenya does not check](#what-nenya-does-not-check)) and show your users
what it says.

---

## Loading the build

Nenya's JavaScript target is configured in `nenya-core/build.gradle.kts` (the `js(IR)` block). It
produces a library (`binaries.library()`) as plain ES modules (`useEsModules()`) with TypeScript
declarations (`generateTypeScriptDefinitions()`). The module is named `nenya`. Nothing in it needs
Node at run time, so a page loads it directly.

Compile it with:

```sh
./gradlew :nenya-core:compileProductionLibraryKotlinJs
```

This needs no Node and no network. The packaged library, with its `package.json`, lands in
`nenya-core/build/dist/js/productionLibrary/`:

- `nenya.mjs` — the library, and the module you import;
- `nenya.d.ts` — the TypeScript declarations for every name listed under [Entry points](#entry-points);
- `kotlin-kotlin-stdlib.mjs` and the other `.mjs` files beside it — the Kotlin standard library,
  which `nenya.mjs` imports by relative path. Keep them in the same directory.

Every entry point is a named export:

```js
import { decodeListing, buildProposalAgainst, sealAsync, capabilities } from "./nenya/nenya.mjs";
```

Nothing is published to a package registry yet. Copy the directory into your client, or point
your bundler at it.

---

## What your client supplies

Nenya holds no keys, opens no sockets, has no wallet, and does not read the time on its own.
Your client supplies each of those through a **seam**: an object whose functions the library
calls. A seam is the trust boundary. Nenya protects your user against counterparties and relays,
not against your client, and nothing a seam *says* counts as evidence. A wallet reporting
"paid" proves nothing; only a preimage the library hashes itself does (§9.1, §9.2).

**`null` means unavailable.** Every seam function may return (or complete with) `null`. The library
then reports that capability as *not performed* — never as a negative answer. For example, a
`verifySchnorr` that returns `null` means signatures are not checked, and the answers say so. They
do not say "invalid".

**Which functions you may leave out.** Every function of `JsEnvironment` and `JsPaymentRequestStore`
is optional, and one you leave out is treated as always answering `null`. The four functions of
`JsSigner` and of `JsAsyncSigner` are **required**. Supply all four, and return `null` from any you
cannot serve. A missing one is a JavaScript `TypeError` when the library calls it, and that error is
not turned into a refusal.

### `JsSigner` — the user's key, answering straight away

```js
const signer = new JsSigner(
  () => pubkeyHex,                        // 64 hex characters, or null
  (serialisation) => signHex(serialisation), // BIP-340 signature, 128 hex characters, or null
  (peerHex, plaintext) => nip44Encrypt(peerHex, plaintext), // or null
  (peerHex, payload) => {                 // a JsDecryption, or null
    const text = nip44Decrypt(peerHex, payload);
    return text === undefined ? new JsDecryption(false) : new JsDecryption(true, text);
  },
);
```

`JsDecryption(false)` means the signer *checked* the payload and it did not authenticate. Returning
`null` means the signer did not try. They are different facts, and the library keeps them apart.

### `JsAsyncSigner` — the same four functions, answering later

Browser signer extensions (NIP-07: `window.nostr`) answer with Promises, and `JsSigner` cannot wait for
a Promise. `JsAsyncSigner` takes the same four functions, each with an extra final argument,
`complete`, which you call once the answer arrives:

```js
const asyncSigner = new JsAsyncSigner(
  (complete) => window.nostr.getPublicKey().then(complete, () => complete(null)),
  (serialisation, complete) => signThroughExtension(serialisation).then(complete, () => complete(null)),
  (peerHex, plaintext, complete) =>
    window.nostr.nip44.encrypt(peerHex, plaintext).then(complete, () => complete(null)),
  (peerHex, payload, complete) =>
    window.nostr.nip44.decrypt(peerHex, payload).then(
      (text) => complete(new JsDecryption(true, text)),
      () => complete(null),
    ),
);
```

A rejected Promise completes with `null`, not `JsDecryption(false)`. The rejection may mean the user
declined, which is "not tried", not "checked and refused".

`signThroughExtension` stands for your own few lines that hand the extension the event the
serialisation describes and return its `sig`. Only `sealAsync` and `openAsync` take a
`JsAsyncSigner` (decision **R**). See [Sealing with a browser extension](#sealing-with-a-browser-extension).

### `JsEnvironment` — clock, randomness, signature checks, throwaway keys

```js
const environment = new JsEnvironment(
  () => String(Math.floor(Date.now() / 1000)),       // now, unix seconds, as a decimal string
  (count) => randomHex(count),                       // `count` secure random bytes as lowercase hex
  (pubkeyHex, messageHex, sigHex) => schnorrVerify(pubkeyHex, messageHex, sigHex) ? "VALID" : "INVALID",
  () => newThrowawaySigner(),                        // a fresh JsSigner with a new key, for each gift wrap
);
```

Each of the four is optional and independent. Without a clock, no deadline can be judged:
a listing or bid with an `expiration` answers `CANNOT_SAY`, and an action that has to judge a
deadline is refused. Randomness must return exactly
`count` bytes. A short or long answer counts as unavailable. `verifySchnorr` must return
exactly `"VALID"` or `"INVALID"`. Anything else counts as *not checked*. The environment is
synchronous even for `sealAsync` and `openAsync`.

### `JsPaymentRequestStore` — the payment requests your user accepted

```js
const store = new JsPaymentRequestStore(
  (orderIdHex, payeeToken) => saved.get(orderIdHex + "/" + payeeToken) ?? null, // a JsAcceptedRequest, or null
  (accepted) => saved.set(/* your key for it */, accepted),
);
```

`acceptPaymentRequest` hands `store` the record it made. `verifySettlement` and `verifyFeeReceipt`
read it back through `find`. Keep it out of anything unencrypted: an order id is private (§12).

---

## How values cross

These rules come from decision **P**:

- **Money, timestamps and byte counts are decimal strings.** Millisatoshi amounts reach
  2.1 × 10¹⁸, and a JavaScript number cannot hold them exactly. Write `"120000000"`, never
  `120000000`. Unix seconds are decimal strings too.
- **Answers are plain objects with a `kind`.** Most results name their case in `kind` (`"built"`
  or `"refused"`, `"sealed"` or `"refused"`, …) and carry `ok`. **`ok` only says the call produced
  an answer rather than a refusal. It is never the verdict.** Read the field that carries the verdict:
  - `checkAcceptance` — `accepted` (and `kind`: acceptance, counter-proposal, or neither);
  - `checkDeadlines` — `permitted`;
  - `verifySettlement` and `verifyFeeReceipt` — the answer's `settlement.evidenced`. An `ok`
    answer can hold a receipt that proves nothing, and `settlement.checksNotPerformedHere`
    names what was not checked. Never treat a payment as made on `ok` alone;
  - `stepOrder` — the order it answers, and that order's `state`.
- **Some answers are not objects.** `listingActivity` and `bidActivity` answer a string (`ACTIVE`,
  `EXPIRED` or `CANNOT_SAY`). `matchesBoard` answers a boolean. `capabilities` answers the
  statement described [below](#what-nenya-does-not-check). `sealAsync` and `openAsync` return
  nothing and answer through `onResult`.
- **Refusals are values, never thrown.** A refused call answers with `ok: false`, a `reason`
  (a constant's name), a `reasonVocabulary` (which list that name comes from — `ListingRejection`,
  `ChannelRejection`, `TransitionRejection`, `JsCrossing`, …), and often a `tag` and a `detail`.
  Branch on `reason` and `reasonVocabulary`. Do not wrap calls in `try`/`catch` to find refusals.
  An error thrown by your own seam function is the exception: it is not a refusal, and it is not
  caught for you.
- **Builders sign nothing.** A `build…` call returns an unsigned event in `event`. Your client
  signs a public event and publishes it. A private-channel event goes through `seal` or
  `sealAsync` instead, which ask your signer to sign the seal.
- **Some values are handles.** `JsRumor`, `JsOrder`, `JsSettlement` and `JsAcceptedRequest` come only
  from the library's own answers. Pass them back as they are. Your client cannot construct them.

---

## Sealing with a browser extension

`seal` and `open` need a signer that answers straight away. `sealAsync` and `openAsync` take a
`JsAsyncSigner` instead, and an `onResult` callback that is called **exactly once** with the same
answer `seal` or `open` would give:

```js
sealAsync(built.event, recipientPubkeyHex, asyncSigner, (result) => {
  if (!result.ok) return showRefusal(result.reason, result.reasonVocabulary);
  publish(result.toRecipient.json);
  publish(result.toSelf.json);
}, environment);
```

There is no timeout. If the extension never completes a question, `onResult` is never called, and
nothing is thrown. A completion that arrives twice, or late, changes nothing.

---

## One trade, in order

The names in brackets are sections of `spec/NENYA-1.md`. "Seal and send" means `seal` or
`sealAsync`, then publishing both copies (`toRecipient` and `toSelf`). "Open" means `open` or
`openAsync` on a gift wrap addressed to you, which answers a `JsRumor`. That includes the `toSelf`
copy of a message you sent yourself, which is how your client gets a `JsRumor` for it.

The order steps below follow **the buyer's client**. It receives every message the order machine
needs, and it holds the stored payment requests the receipt checks compare against. The provider's
client builds and sends its own messages at the matching steps.

1. **Post to the board** (§5). The provider builds an offer with `buildListingOffer`, or the buyer
   builds a request with `buildListingRequest`. Sign it and publish it.
2. **Read the board** (§5.5, §5.6). Decode each event with `decodeListing`. Relays OR a multi-value
   filter, so re-check each result with `matchesBoard`. Before acting on a listing, ask
   `listingActivity` or `checkDeadlines`.
3. **Bid** (§6), where the listing invites it: `buildBid` for a public bid, or `buildPrivateBid`
   sealed to the listing's author. Read public bids with `decodeBid`.
4. **Propose** (§7.5). The buyer draws a new order id — 32 bytes from a secure random source, as
   64 hex characters (§7.4) — builds the proposal with `buildProposalAgainst`, then seals and sends
   it. The buyer then starts its order with `openOrder`, giving the same order id and terms. That
   call *is* the proposal step: the order starts in `proposed`, and stepping it again with a
   `proposal` event is refused.
5. **Accept** (§7.6). The provider opens the proposal, reads it with `decodeProposal`, builds the
   acceptance with `buildAgreementAgainst`, then seals and sends it. The buyer opens it, can ask
   `checkAcceptance` whether it is an acceptance or a counter-proposal, and steps the order with
   `acceptance_received`.
6. **Commit to the deliverable** (§10.1). The provider builds `buildCommitment`, seals and sends it. The
   buyer opens it, reads it with `decodeCommitment` and steps the order with `delivery_committed`.
7. **Request payment** (§8.6). The provider — and the fee recipient, where the terms give a non-zero
   fee — each build `buildPaymentRequest`, seal and send it. The buyer opens each and accepts it with
   `acceptPaymentRequest`, which writes the record to the store. For a fee request, give
   `acceptPaymentRequest` its `earlierPoints` parameter: a `JsFeeTermSighting` for each earlier point
   where the `fee` tag appeared, or was absent (§8.4). At least the proposal (`ORDER_PROPOSAL`) and
   the acceptance (`ACCEPTANCE`) must be among them, or the request is refused
   `FEE_TERM_POINT_MISSING`. Then the buyer steps the order with `payment_requests_received`,
   passing the accepted records.
8. **Pay and check the receipts** (§9). The buyer pays each invoice with its own wallet, builds
   `buildReceipt` for each payment, then seals and sends it. The buyer opens its own `toSelf` copy of
   each receipt and checks it against the stored request: with `verifySettlement` and the accepted
   terms for the provider's payment, and with `verifyFeeReceipt`, the order and `earlierPoints` for
   the fee. For the fee, the earlier points now also include the fee payment request
   (`FEE_PAYMENT_REQUEST`). Read `settlement.evidenced` on each answer, not `ok`. Then the buyer
   steps the order with `receipts_verified`, passing those settlements. §11.2 allows `paid` only
   once **all** required receipts are verified, so read `stepOrder`'s answer rather than assuming
   the order moved.
9. **Release** (§10.3). The provider builds `buildRelease`, seals and sends it. The buyer opens it,
   reads it with `decodeRelease` and steps the order with `deliverable_released`.
10. **Check the delivery** (§10.4). The buyer fetches the file, checks it with `verifyServedBytes`,
    decrypts it, and steps the order with `delivery_verified` (passing the served and decrypted
    bytes) or `delivery_refused`.

Along the way, either side can send a chat message with `buildChat` and a status update (for
example `cancelled`) with `buildStatusUpdate`, reading a status update with `decodeStatusUpdate`.
Step the order with `clock_checked` whenever your client wants deadlines applied. An order only
moves when `stepOrder` says it did, so always read its answer.

---

## What Nenya does not check

`capabilities()` answers the library's own statement (§17) of each conformance item: whether it is
`PERFORMED_HERE`, `PARTIAL` or `NOT_PERFORMED_HERE`, which sections it covers, and what is left to
your client. `notPerformedHere` lists everything the library leaves undone, and `report` is the same
statement as text. Read it before you ship, and do not tell your users the library checks something
it reports as not performed.

Some results also report what was not done on that call. For example, `seal`'s answer carries
`notPerformedHere`, naming any signature check your environment could not perform. Show those, too.

---

## Entry points

Every function the JavaScript build exports, each listed once. The parameters and fields are in
`nenya.d.ts`.

- `decodeListing` — reads a listing event's JSON (§5): an offer, a draft or a request.
- `decodeBid` — reads a public bid event's JSON (§6).
- `buildListingRequest` — builds an unsigned request, `kind:30404` (§5.2).
- `buildListingOffer` — builds an unsigned offer, `kind:30402` (§5.1).
- `buildListingDraft` — builds an unsigned offer draft, `kind:30403` (§5.1).
- `buildBid` — builds an unsigned public bid, `kind:1111` (§6).
- `listingActivity` — whether a listing is still active by your clock (§4.6).
- `bidActivity` — whether a bid is still active by your clock (§4.6).
- `checkDeadlines` — whether you may act on a listing, and optionally a bid, by your clock (§5.6).
- `matchesBoard` — whether a decoded listing meets every condition of a board query (§5.5).
- `buildChat` — builds a chat rumor, `kind:14` (§7.4).
- `buildProposal` — builds an order proposal rumor, `kind:16` `type=1`, from a coordinate (§7.5).
- `buildProposalAgainst` — the same, against a decoded listing, refused if the listing has expired (§5.6).
- `buildAgreementAgainst` — builds the provider's acceptance of a proposal against a decoded listing (§7.6).
- `buildStatusUpdate` — builds a status update rumor, `kind:16` `type=3` (§11.1).
- `buildCommitment` — builds a delivery commitment rumor, `kind:16` `type=5` (§10.1).
- `buildRelease` — builds the deliverable release rumor, `kind:15` (§10.3).
- `buildPrivateBid` — builds a private bid rumor, `kind:16` `type=6` (§6.1).
- `buildPaymentRequest` — builds a payment request rumor, `kind:16` `type=2` (§8.6).
- `buildReceipt` — builds a payment receipt rumor, `kind:17` (§9).
- `seal` — gift-wraps a rumor for the recipient and for yourself (§7.1).
- `open` — unwraps a gift wrap addressed to you and attributes its rumor (§7.1, §7.2).
- `sealAsync` — `seal`, for a signer that answers later (§7.1).
- `openAsync` — `open`, for a signer that answers later (§7.1).
- `decodeProposal` — reads an order proposal from an opened rumor (§7.5).
- `decodeStatusUpdate` — reads a status update from an opened rumor (§7.4).
- `checkAcceptance` — compares a status update with the proposal it answers: acceptance, counter-proposal or neither (§7.6).
- `decodeCommitment` — reads a delivery commitment from an opened rumor (§10.1).
- `decodeRelease` — reads a deliverable release from an opened rumor (§10.3).
- `acceptPaymentRequest` — accepts a payment request against the proposal and acceptance, and stores it (§8.6).
- `verifySettlement` — checks a payment receipt against the stored payment request (§9.2).
- `verifyFeeReceipt` — the same, for the fee recipient's payment (§8.5, §9.2).
- `verifyServedBytes` — compares the bytes a provider served with the commitment (§10.4).
- `openOrder` — starts an order in the state machine (§11).
- `stepOrder` — applies one event to an order and answers the new order or the refusal (§11.2).
- `capabilities` — the library's own statement of what it does and does not check (§17).
