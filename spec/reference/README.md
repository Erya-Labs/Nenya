# Referenced NIPs, vendored

Nenya builds on existing nostr protocol documents rather than replacing them. Copies of
the ones NENYA-1 normatively references are kept here so that the specification can be
read, and the implementation checked against it, without network access — and so that a
future reader can see exactly which revision Nenya was written against, rather than
whatever upstream says years from now.

All are from [nostr-protocol/nips](https://github.com/nostr-protocol/nips), which is
public domain (CC0). They are unmodified.

| File | NIP | Why Nenya references it |
|---|---|---|
| `nip01.md` | NIP-01 | The base protocol: event structure, canonical serialisation and the id computation, kind ranges, filters, and the relay wire messages |
| `nip19.md` | NIP-19 | bech32-encoded entities (`npub`, `nsec`, `naddr`, `nevent`, `nprofile`) used to reference listings and identities |
| `nip44.md` | NIP-44 | Versioned encryption (v2) — the payload encryption under the gift wrap |
| `nip59.md` | NIP-59 | Gift wrap: the seal and wrap layering that hides metadata |
| `nip17.md` | NIP-17 | Private direct messages built on NIP-59, carrying private terms, delivery and settlement |
| `nip99.md` | NIP-99 | Classified listings (`kind:30402`), which Nenya offers ride unmodified so existing marketplace clients render them |
| `nip55.md` | NIP-55 | Android signer application IPC — the shape a vault-backed signer follows, relevant to the planned Endeavor integration |

## `nip44.md` and the extended-length prefix

`nip44.md` is kept **unmodified**, like every other copy here, and it is worth saying what
that means in one specific case rather than leaving a reader to find the disagreement.

The vendored `nip44.md` describes a six-byte extended-length prefix for long plaintexts. That
text **postdates the official test vectors**, which are vendored separately at
`nenya-core/src/commonTest/resources/vectors/nip44.vectors.json` and list `65536` among
`v2.invalid.encrypt_msg_lengths` — that is, they require encryption to refuse a plaintext of
65 536 bytes, which the extended form would permit. No deployed application emits the
extended form.

**NENYA-1 follows the vectors**: §4.3 fixes a NIP-44 plaintext at 1 to 65 535 bytes, and the
seal and wrap bounds are derived from that. Where the letter of a document and every working
implementation disagree, interoperability wins — the same precedent NENYA-1 §4.1 sets for
canonical JSON escaping. The file below is not edited to match, because this README promises
these copies are unmodified and a quietly corrected copy is worse than a documented
disagreement.

Two documents deliberately **not** vendored, because Nenya does not build on them:

- **NIP-90** (data vending machines) — marked `unrecommended` upstream, and its kind
  registry was archived. Read for vocabulary only.
- **NIP-15** (nostr marketplace) — marked `unrecommended` upstream in favour of NIP-99,
  and its checkout rests on the deprecated NIP-04.
