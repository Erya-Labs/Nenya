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
  - `partyOf` — `party`. An `ok` answer with no `party` (`kind` `"not_a_party"`) means the key is
    none of the three, or holds two roles at once; never fill in a party yourself;
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
needs, and it holds the stored payment requests the receipt checks compare against. Every order
state named in this section is the state of the buyer's own order. The provider's
client builds and sends its own messages at the matching steps; [The provider's side](#the-providers-side)
walks them in its own order.

Every step ends with a `Calls:` line naming the entry points the step uses, in the order it uses
them. Where a step names two calls as alternatives, both are listed. `seal` and `open` stand for
`sealAsync` and `openAsync` too.

1. **Post to the board** (§5). The provider builds an offer with `buildListingOffer`, or the buyer
   builds a request with `buildListingRequest`. Sign it and publish it.

   Calls: `buildListingOffer`, `buildListingRequest`.
2. **Read the board** (§5.5, §5.6). Decode each event with `decodeListing`. Relays OR a multi-value
   filter, so re-check each result with `matchesBoard`. Before acting on a listing, ask
   `listingActivity` or `checkDeadlines`.

   Calls: `decodeListing`, `matchesBoard`, `listingActivity`, `checkDeadlines`.
3. **Bid** (§6), where the listing invites it: `buildBid` for a public bid, or `buildPrivateBid`
   sealed to the listing's author. Read public bids with `decodeBid`, and open private bids and
   read them with `decodePrivateBid`. A bid binds nobody and does not move an order.

   Calls: `buildBid`, `buildPrivateBid`, `seal`, `decodeBid`, `open`, `decodePrivateBid`.
4. **Propose** (§7.5). The buyer draws a new order id — 32 bytes from a secure random source, as
   64 hex characters (§7.4) — builds the proposal with `buildProposalAgainst`, then seals and sends
   it. If the buyer chose a public bid, build it with `buildProposalForBid` instead: it copies the
   bid's price, `fee`, fee recipient and `deliver_by`, so do not type them again. It is refused unless
   the buyer sends it to the provider — on a request, the request's author to the bidder; on an
   offer, the bidder to the offer's author. Its `acceptBy` is the proposal's `expiration`, and must
   be before the bid's `deliver_by`; once the clock has reached either, nothing is built. Either call
   also builds nothing when the fee recipient is the buyer's own key, the envelope's author
   (`FEE_RECIPIENT_IS_BUYER`): §8.1 says the buyer is never its own fee recipient. For a bid, that is
   the fee recipient the bid names.

   Before publishing the proposal, open the `toSelf` copy of it that `seal` answered and read it with
   `decodeProposal`. When its `feeConfirmationRequired` is true, the fee is above §8.1's line: show the
   user `feeBasisPoints`, `feeMsat` and `totalMsat`, and publish nothing until they confirm. That
   value is an answer, not a refusal: nothing in this library checks that you asked. The listing and
   the bid carry `feeConfirmationRequired` too, so you can warn the user before building.

   Ask the same decoded proposal `feeRecipientIsProvider`, with the provider's key as you know it
   from the listing or the bid. When it is true, the provider is also the fee recipient. §8.1 allows
   that only where the provider really acts as an agent, so tell the user before you publish the
   proposal. This too is a warning, not a refusal: the proposal still builds, and nothing in this
   library checks that you told them. Once the order is accepted, the order answers
   `feeRecipientIsProvider` as well, for the provider key it accepted.

   The buyer keeps the `toSelf` copy of its proposal. It starts its order from that copy with
   `openOrderFromProposal`, which takes the order id and terms from the proposal itself, so do not type
   them again. That call *is* the proposal step: the order starts in `proposed`, and stepping it again
   with a `proposal` event is refused. It checks nothing about the provider; only step 5 moves the order
   on. Read the proposal with `decodeProposal` and keep its `terms`: step 8 checks the provider's
   receipt against them. Keep the opened proposal itself too: steps 5, 6 and 9 hand it back. They
   refuse, with `NOT_THIS_ORDERS_PROPOSAL`, a proposal written by another key, one with other terms, or
   one naming another fee recipient, even under the same order id. Always hand back the copy you kept.

   Calls: `buildProposalAgainst`, `buildProposalForBid`, `seal`, `open`, `openOrderFromProposal`, `decodeProposal`.
5. **Accept** (§7.6). The provider opens the proposal, reads it with `decodeProposal`, and, when its
   `feeConfirmationRequired` is true, gets the user's confirmation of the fee first, as
   [The provider's side](#the-providers-side) says. It then builds the acceptance with
   `buildAgreementAgainst`, then seals and sends it. The buyer opens it, can ask
   `checkAcceptance` whether it is an acceptance or a counter-proposal, and steps the order with
   `stepOrderWithStatusUpdate`. Give it the proposal you kept in step 4, the acceptance as `open`
   answered it, and the provider's key as you know it from the listing or the bid, not from either
   message. It checks that both messages are for this order, and makes the same acceptance check as
   `checkAcceptance`, so a counter-proposal or an acceptance from another key does not move the order.
   Once the order is accepted, it records the provider key the acceptance was checked against. From then
   on, steps 6 and 9 and every later status update must be given that same key: another key is refused
   with `PROVIDER_NOT_THIS_ORDERS`, and the order does not move.

   Calls: `open`, `decodeProposal`, `buildAgreementAgainst`, `seal`, `checkAcceptance`, `stepOrderWithStatusUpdate`.
6. **Commit to the deliverable** (§10.1). The provider builds `buildCommitment`, seals and sends it. The
   buyer opens it and steps the order with `stepOrderWithCommitment`, giving it the proposal, the
   commitment and the provider's key as in step 5. Like step 5's call, it refuses a proposal from
   another key or with other terms, and a provider key that is not the accepted one. It refuses a commitment for another
   order, one that carries the decryption key (§10.1), and one sealed by a key that is not a party to the
   order. A commitment the fee recipient sealed is refused too, with `WRONG_SENDER`: only the provider
   commits. Otherwise the order records that the key check was made. Read the commitment with `decodeCommitment` for
   its `url`, which step 10 fetches from.

   Calls: `buildCommitment`, `seal`, `open`, `stepOrderWithCommitment`, `decodeCommitment`.
7. **Request payment** (§8.6, §8.7). Where the terms give a non-zero fee, the buyer first tells the fee
   recipient about the order: once the order is `committed`, call `proposalForFeeRecipient` with the
   order, the proposal you kept in step 4 and the provider's key as in step 5. It answers the proposal,
   unchanged, as an `event`, and the fee recipient's key, read from the fee term, as `recipientPubkey`.
   Seal and send that `event` to `recipientPubkey`. It is refused, and gives nothing to send, for an
   order that is not `committed` (`ORDER_NOT_COMMITTED`), for a proposal another key wrote, with other
   terms or naming another fee recipient (`NOT_THIS_ORDERS_PROPOSAL`), and for an order that owes no fee
   (`NO_FEE_TO_REQUEST`). Without this copy the fee recipient
   knows of no order and sends no request, so the order cannot reach `awaiting_payment`. The provider
   builds `buildPaymentRequest`, seals and sends it; the fee recipient builds its own from the copy, as
   [The fee recipient's side](#the-fee-recipients-side) says. The buyer opens each and accepts it with
   `acceptPaymentRequest`, which writes the record to the store. For a fee request, give
   `acceptPaymentRequest` its `earlierPoints` parameter: a `JsFeeTermSighting` for each earlier point
   where the `fee` tag appeared, or was absent (§8.4). At least the proposal (`ORDER_PROPOSAL`) and
   the acceptance (`ACCEPTANCE`) must be among them, or the request is refused
   `FEE_TERM_POINT_MISSING`. Get them from the library rather than copying a `fee` tag yourself:
   `proposalFeeSighting` on the proposal you kept in step 4, and `acceptanceFeeSighting` on the
   acceptance. Each answers a result: take its `sighting` when `ok`. They only read the message's own
   `fee` tag; comparing the points is `acceptPaymentRequest`'s job. Keep both sightings for step 8. If
   the order came from a bid, add the bid's point too: get it from `bidFeeSighting` for a public bid or
   `privateBidFeeSighting` for a private one. A bid with no `fee` tag gives a sighting with no `feeTag`,
   which the comparison skips (§8.4). Then the buyer steps the order with `payment_requests_received`,
   passing the accepted records.

   Calls: `proposalForFeeRecipient`, `seal`, `buildPaymentRequest`, `open`, `proposalFeeSighting`, `acceptanceFeeSighting`, `bidFeeSighting`, `privateBidFeeSighting`, `acceptPaymentRequest`, `stepOrder`.
8. **Pay and check the receipts** (§9). The buyer pays each invoice with its own wallet, builds
   `buildReceipt` for each payment, then seals and sends it. The buyer opens its own `toSelf` copy of
   each receipt and checks it against the stored request. For the provider's payment, use
   `verifySettlement` with the proposal's `terms` you kept in step 4. For the fee, use
   `verifyFeeReceipt` with the order and `earlierPoints`: step 7's sightings plus the fee payment
   request's (`FEE_PAYMENT_REQUEST`), from `paymentRequestFeeSighting`. Give it the fee request, the
   proposal, the acceptance and the provider's key. It reads the request against that acceptance, as step
   7's acceptance of the request did, and compares its `fee` tag with no other point. Read `settlement.evidenced` on each answer, not `ok`. Then the buyer
   steps the order with `receipts_verified`, passing those settlements. The buyer's order enters `paid` only
   once the provider's receipt and any fee receipt are verified (§11.2), so read `stepOrder`'s answer
   rather than assuming the order moved.

   Calls: `buildReceipt`, `seal`, `open`, `verifySettlement`, `paymentRequestFeeSighting`, `verifyFeeReceipt`, `stepOrder`.
9. **Release** (§10.3). The provider builds `buildReleaseAfterPayment` from its own commitment and
   the settlement its own `verifySettlement` call answered for the buyer's receipt of its invoice. It
   is refused unless that settlement is evidenced, is the provider's payment and is for the
   commitment's order. For a free order, which has no payment to check, the provider builds
   `buildRelease` instead. The provider seals and sends the release. The buyer opens it and
   steps the order with `stepOrderWithRelease`, giving it the proposal, the release and the provider's
   key as in step 5. Like step 5's call, it refuses a proposal from another key or with other terms,
   and a provider key that is not the accepted one. It refuses a release for another order, and one sealed by
   a key that is not a party to the order. Otherwise the order records that the release was checked
   against this order.
   A release whose values differ from the commitment moves the order to `disputed`, so read the
   answer's state rather than assuming `released`.
   A release can arrive while the buyer's order is still `awaiting_payment`, because the provider
   does not wait for the fee; `stepOrderWithRelease` then refuses it `RELEASE_ORDER_NOT_PAID`. Keep
   the release, and step the order with it once the order is `paid`, before any deadline check (§10.3).

   Calls: `verifySettlement`, `buildReleaseAfterPayment`, `buildRelease`, `seal`, `open`, `stepOrderWithRelease`.
10. **Check the delivery** (§10.4). The buyer fetches the file and checks it with `verifyServedBytes`.
    Only if that answers `ok`, decrypt the file. If it decrypts, step the order with
    `delivery_verified`, passing the served and decrypted bytes. If it does not, step it with
    `delivery_refused` and `BLOB_DID_NOT_DECRYPT`. When `verifyServedBytes` or the
    `delivery_verified` step refuses, read `deliveryFailure` on the answer.
    If it is set, step the order with `delivery_refused` and that value. It is set only when the bytes
    do not hash to the commitment's value. For any other refusal, such as a size refusal, it is not set,
    and the library names no `delivery_refused` value. Reading it never moves the order.

    Calls: `verifyServedBytes`, `stepOrder`.

Along the way, either side can send a chat message with `buildChat` and a status update (for
example `cancelled`) with `buildStatusUpdate`, reading a status update with `decodeStatusUpdate`.
Step the order on a status update you receive with `stepOrderWithStatusUpdate`, as in step 5. It
works out from the proposal which party sent the update, so do not say who sent it yourself: an
update from a key that is not exactly one of the buyer, the provider and the fee recipient moves
nothing, and the order decides what each party may do. As in step 5, the proposal must have the
author, the terms and the fee recipient of the one the order was started from (a proposal naming
another fee recipient is refused `NOT_THIS_ORDERS_PROPOSAL`), and
once the order is accepted the provider key must be the accepted one; otherwise nothing moves. The three `stepOrderWith…` calls work only on an order started with
`openOrderFromProposal`. An order started with `openOrder`, from an order id and terms you typed, has
no proposal of its own to compare with, so all three refuse it with `NOT_THIS_ORDERS_PROPOSAL`.
To show who sent a chat message on an order, ask `partyOf` with the proposal, the provider's key and the key `open` gave for the message,
rather than labelling it yourself. It answers no party for a key that is not exactly one of those
three. A private bid comes before any proposal, so `partyOf` cannot label one: its sender is the key
`open` gave. Step the order with `clock_checked` whenever your client wants deadlines applied. An
order only moves when `stepOrder` or one of the `stepOrderWith…` calls says it did, so always read
its answer.

---

## The provider's side

The same trade, followed by **the provider's client**. The provider never pays, so it never builds a
receipt. What it has to get right is that it releases the deliverable only once the library has
checked the buyer's payment of its own invoice (§10.3).

These steps open no order machine. The release rests on the evidenced settlement that step 5 answers
for the provider's own invoice (§9.2). The release does not wait for the fee, and a provider that is
not also the fee recipient does not see the fee paid. An order machine the provider opens on a
fee-bearing order does not pass `committed` without the fee request (§11).

Several later calls take a `JsRumor` for a message the provider handled earlier: the buyer's
proposal, and its own acceptance, commitment and payment request. `open` answers each of them, its
own messages from their `toSelf` copies. Keep each one.

1. **Post or bid** (§5, §6). Post an offer with `buildListingOffer`, sign it and publish it. Or read
   the requests on the board with `decodeListing`, re-check each with `matchesBoard`, ask
   `checkDeadlines` before acting on one, and bid: `buildBid` for a public bid, or `buildPrivateBid`
   sealed to the request's author.

   Calls: `buildListingOffer`, `decodeListing`, `matchesBoard`, `checkDeadlines`, `buildBid`, `buildPrivateBid`, `seal`.
2. **Open and answer a proposal** (§7.5, §7.6). Open the buyer's proposal and read its terms with
   `decodeProposal`, and keep its `terms`: step 5 checks the buyer's receipt against them. When its
   `feeConfirmationRequired` is true, the fee is above §8.1's line: before building the acceptance,
   show the user `feeBasisPoints`, `feeMsat` and `totalMsat` and build nothing until they confirm.
   Nothing in this library checks that you asked. Ask the decoded proposal `feeRecipientIsProvider`
   with your own key, too. When it is true, the proposal names you as the fee recipient: tell the
   user so before building the acceptance, because §8.1 allows that only where the provider really
   acts as an agent. It is a warning, not a refusal, and nothing checks that you told them. To accept, build the acceptance with `buildAgreementAgainst`, giving it the
   listing the proposal is for. It is refused, and builds nothing, when the proposal is for another
   listing, when the listing has expired, or when the clock has reached the proposal's own
   `expiration`, which is the deadline for accepting it. Where either carries a deadline and your
   clock gives no reading, it is refused too. Seal and send the acceptance, then open its `toSelf`
   copy.

   Calls: `open`, `decodeProposal`, `buildAgreementAgainst`, `seal`.
3. **Commit to the deliverable** (§10.1). Encrypt the file (§10.2) and build the commitment with
   `buildCommitment`. It goes before any payment request. Seal and send it, then open its `toSelf`
   copy: step 6 builds the release from that copy.

   Calls: `buildCommitment`, `seal`, `open`.
4. **Request payment and keep the request** (§8.6). Build a payment request for your own invoice with
   `buildPaymentRequest`, payee `provider`, for exactly the price. Seal and send it, then open its
   `toSelf` copy and accept it into your own store with `acceptPaymentRequest`, giving it the
   proposal, your acceptance and your own key as the provider. Without that stored request, step 5
   has nothing to check the buyer's receipt against, and the receipt is refused. `acceptPaymentRequest`
   reads your clock, and refuses with `CLOCK_UNAVAILABLE` if it has none.

   Calls: `buildPaymentRequest`, `seal`, `open`, `acceptPaymentRequest`.
5. **Check the buyer's receipt** (§9.2). When the buyer's receipt for your invoice arrives, open it
   and check it with `verifySettlement`, giving it your store and the proposal's `terms` from step 2.
   Your acceptance copied those terms, so they are the terms you accepted. Read
   `settlement.evidenced` on the answer, not `ok`: a receipt on a rail with no evidence rule, such as
   `bitcoin`, is answered `ok` and evidences nothing (§9.4). Your wallet saying "paid" is not
   evidence either (§9.1).

   Calls: `open`, `verifySettlement`.
6. **Release** (§10.3). Build the release with `buildReleaseAfterPayment`, giving it your commitment
   from step 3, the settlement step 5 answered, and the key and nonce you encrypted the file with. It
   copies the order and the commitment's values, so do not type them again. It is refused, and builds
   nothing, unless the settlement is evidenced, is the provider's payment, and is for the commitment's
   order, and unless the commitment was sealed by the author you are building as. The fee invoice and
   the fee receipt pass only between the buyer and the fee recipient. This call does not ask for them,
   and the release does not wait for the fee. A free order, with a price of `0`, has no payment to check, and `buildReleaseAfterPayment`
   refuses it: build its release with `buildRelease` instead, skipping steps 4 and 5. Seal and send the
   release.

   Calls: `buildReleaseAfterPayment`, `buildRelease`, `seal`.

---

## The fee recipient's side

The same trade, followed by **the fee recipient's client**, where the terms give a non-zero fee. The
fee recipient learns of the order only from the copy of the proposal the buyer sends it (§8.7), and it
acts only on that copy: it types no order id, no terms and no buyer key. Where the fee recipient is the
provider (§8.1), the provider receives the proposal a second time, as this copy.

1. **Open the copy** (§8.7). Open the gift wrap the buyer sealed to you. `open` answers the proposal,
   and its `author` is the buyer's key. Read its terms with `decodeProposal` if you want to show them.
   The copy is not evidence that the provider accepted, committed or was paid (§8.7): it only tells you
   what the buyer says the order is and what fee it would owe you. Keep the copy as `open` answered it.

   Calls: `open`, `decodeProposal`.
2. **Request the fee and seal it to the buyer** (§8.6). Build the request with
   `buildFeeRequestFromProposal`, giving it an envelope with your own key as the author and the copy's
   `author` as the one `p` key, the copy, and your invoice for exactly the fee. It reads the order id,
   the terms and your key as the fee recipient from the copy. It is refused, and builds nothing, when
   your key is not the fee recipient the copy names (`NOT_THE_FEE_RECIPIENT`), when the envelope's `p`
   keys are anything but the copy's `author` alone (`NOT_ADDRESSED_TO_BUYER`), when the copy owes no fee
   (`PAYEE_NOT_REQUIRED`), and when the invoice is not for exactly the fee (`INVOICE_AMOUNT_MISMATCH`, or
   `INVOICE_AMOUNT_MISSING` for an invoice with no amount). The answer's `buyerPubkey` is the copy's
   author: seal and send the `event` to that key.

   Calls: `buildFeeRequestFromProposal`, `seal`.

A fee recipient that is not also the provider holds no acceptance, so `acceptPaymentRequest` cannot
store its invoice, and `verifySettlement` and `verifyFeeReceipt` have no stored request to check its
receipt against. It holds no order in this role. A fee is not enforced against the buyer, so the
provider can release the file and the fee may never be paid. The fee recipient cannot tell an unpaid
fee from an order that never went ahead.

---

## What Nenya does not check

`capabilities()` answers the library's own statement (§17) of each conformance item: whether it is
`PERFORMED_HERE`, `PARTIAL` or `NOT_PERFORMED_HERE`, which sections it covers, and what is left to
your client. `notPerformedHere` lists everything the library leaves undone, and `report` is the same
statement as text. Read it before you ship, and do not tell your users the library checks something
it reports as not performed. The library does not enforce a fee. A provider can build a release with
no fee paid, and a buyer can read a release while its own order is not `paid`; the order state
reports what that party verified, not whether the file was delivered.

Some results also report what was not done on that call. For example, `seal`'s answer carries
`notPerformedHere`, naming any signature check your environment could not perform. Show those, too.

`capabilities()` also answers `openItems`. Each is an open question of NENYA-1 (§16.2, `OPEN-4` to
`OPEN-7`) that the library leaves unsupported: its `status` is `UNSUPPORTED`, and its `note` says
what the library does instead. Show them to your user rather than choosing a value for any of them.

The specification (§13) requires these residual risks to be surfaced. The library has no screen, so
showing them to your user is your client's job. No check in the library removes any of them:

- a paid provider can withhold the key (§11.4)
- a buyer can receive the deliverable without paying the fee (§8)
- a blob host can disappear between commitment and download
- a partitioned relay set can hide an acceptance
- a `kind:5` deletion is advisory

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
- `buildProposalForBid` — the same, from the decoded public bid the buyer chose, with the bid's terms copied (§6, §7.5).
- `buildAgreementAgainst` — builds the provider's acceptance of a proposal against a decoded listing (§7.6).
- `buildStatusUpdate` — builds a status update rumor, `kind:16` `type=3` (§11.1).
- `buildCommitment` — builds a delivery commitment rumor, `kind:16` `type=5` (§10.1).
- `buildRelease` — builds the deliverable release rumor, `kind:15` (§10.3).
- `buildReleaseAfterPayment` — the same, from the provider's own commitment, refused unless the provider's own payment was evidenced (§10.3).
- `buildPrivateBid` — builds a private bid rumor, `kind:16` `type=6` (§6.1).
- `buildPaymentRequest` — builds a payment request rumor, `kind:16` `type=2` (§8.6).
- `buildReceipt` — builds a payment receipt rumor, `kind:17` (§9).
- `seal` — gift-wraps a rumor for the recipient and for yourself (§7.1).
- `open` — unwraps a gift wrap addressed to you and attributes its rumor (§7.1, §7.2).
- `sealAsync` — `seal`, for a signer that answers later (§7.1).
- `openAsync` — `open`, for a signer that answers later (§7.1).
- `decodeProposal` — reads an order proposal from an opened rumor (§7.5).
- `decodeStatusUpdate` — reads a status update from an opened rumor (§7.4).
- `decodePrivateBid` — reads a private bid from an opened rumor (§6.1).
- `checkAcceptance` — compares a status update with the proposal it answers: acceptance, counter-proposal or neither (§7.6).
- `partyOf` — says whether the key a rumor came from is the buyer, the provider or the fee recipient of a proposal, or none of them (§7.2, §11.2).
- `decodeCommitment` — reads a delivery commitment from an opened rumor (§10.1).
- `decodeRelease` — reads a deliverable release from an opened rumor (§10.3).
- `acceptPaymentRequest` — accepts a payment request against the proposal and acceptance, and stores it (§8.6).
- `verifySettlement` — checks a payment receipt against the stored payment request (§9.2).
- `verifyFeeReceipt` — the same, for the buyer's check of its own fee payment; it reads the buyer's order (§8.5, §9.2).
- `bidFeeSighting` — the `fee` tag of a decoded public bid, as a `BID` sighting for `earlierPoints` (§8.4).
- `privateBidFeeSighting` — the same, for a private bid read from an opened rumor (§6.1, §8.4).
- `proposalFeeSighting` — the `fee` tag of an order proposal read from an opened rumor, as an `ORDER_PROPOSAL` sighting (§7.5, §8.4).
- `acceptanceFeeSighting` — the same, for the provider's acceptance, refused if the update is not `status=accepted` (§7.6, §8.4).
- `paymentRequestFeeSighting` — the same, for a payment request read against the proposal and acceptance; the point comes from its `payee` (§8.6, §8.4).
- `verifyServedBytes` — compares the bytes a provider served with the commitment (§10.4).
- `openOrder` — starts an order in the state machine (§11).
- `openOrderFromProposal` — the same, from an order proposal read from an opened rumor, so its order id and terms are the ones it carried (§7.5, §11.2).
- `stepOrder` — applies one event to an order and answers the new order or the refusal (§11.2).
- `stepOrderWithStatusUpdate` — applies a received status update to an order, with the sender's party and the order id read from the messages (§7.6, §11.2).
- `stepOrderWithCommitment` — applies a received delivery commitment to an order, read from the message, so the order records §10.1's check that it carries no key (§10.1, §11.2).
- `stepOrderWithRelease` — applies a received release to an order, read from the message, so the order records §10.3's check that it names this order (§10.3, §11.2).
- `proposalForFeeRecipient` — gives the buyer the order's proposal, unchanged, to seal to the fee recipient once the order is committed, and the fee recipient's key read from the fee term (§8.7).
- `buildFeeRequestFromProposal` — builds the fee recipient's payment request from the proposal the buyer sent it, with the order, the terms and the buyer's key to seal it to read from that proposal (§8.6, §8.7).
- `capabilities` — the library's own statement of what it does and does not check (§17).
