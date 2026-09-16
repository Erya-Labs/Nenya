# Test vector provenance

These files are **externally authored**. That is the entire point of them: a test that
checks an implementation against vectors someone else produced cannot pass by encoding
the implementer's own misunderstanding, which is the failure mode a self-authored fixture
invites.

Vendored into the repository rather than fetched at test time, so the suite runs with no
network and cannot go red because an upstream host moved.

| File | Source | Licence | Contents |
|---|---|---|---|
| `nip44.vectors.json` | [nostr-protocol/nips](https://github.com/nostr-protocol/nips), NIP-44 | CC0 / public domain | 128 cases covering NIP-44 v2 conversation keys, message keys, padding, encryption and the invalid-input set |
| `bip340-vectors.csv` | [bitcoin/bips](https://github.com/bitcoin/bips), BIP-340 | BSD-2-Clause | 19 cases covering BIP-340 Schnorr signing and verification, including the out-of-range and non-canonical rejections |

## Integrity

`nip44.vectors.json` has a checksum published in the NIP-44 specification itself. Any test
that reads it MUST assert the checksum first, so that a corrupted or substituted file fails
loudly as a tampering signal rather than quietly as a wrong expectation:

```
269ed0f69e4c192512cc779e78c555090cebc7c785b609e338a62afc3ce25040  nip44.vectors.json
```

Verified matching at the time this file was written.

## A caveat that matters

The BIP-340 vectors exercise **signature** operations, which Nenya cannot perform today:
BIP-340 Schnorr needs a cryptography dependency this project has not taken. They are staged
here ahead of that decision so the vectors are already in place, externally anchored, when
the dependency lands. Until then, no test may claim to have verified a signature.
