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

Two documents deliberately **not** vendored, because Nenya does not build on them:

- **NIP-90** (data vending machines) — marked `unrecommended` upstream, and its kind
  registry was archived. Read for vocabulary only.
- **NIP-15** (nostr marketplace) — marked `unrecommended` upstream in favour of NIP-99,
  and its checkout rests on the deprecated NIP-04.
