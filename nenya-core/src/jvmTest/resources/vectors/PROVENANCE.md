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
| `nist-sha256/SHA256ShortMsg.rsp` | [NIST CAVP, Secure Hashing](https://csrc.nist.gov/projects/cryptographic-algorithm-validation-program/secure-hashing), `shabytetestvectors.zip` | US Government work, public domain | 65 vectors: every message length from 0 to 64 bytes (`Len` 0-512 bits) |
| `nist-sha256/SHA256LongMsg.rsp` | NIST CAVP, as above | US Government work, public domain | 64 vectors: multi-block messages from 163 to 6400 bytes, in steps of 99 bytes |
| `nist-sha256/SHA256Monte.rsp` | NIST CAVP, as above | US Government work, public domain | 1 seed and 100 Monte Carlo checkpoints, each after 1000 chained hashes |

## Integrity

`nip44.vectors.json` has a checksum published in the NIP-44 specification itself. Any test
that reads it MUST assert the checksum first, so that a corrupted or substituted file fails
loudly as a tampering signal rather than quietly as a wrong expectation:

```
269ed0f69e4c192512cc779e78c555090cebc7c785b609e338a62afc3ce25040  nip44.vectors.json
```

Verified matching at the time this file was written.

The three `nist-sha256/` files are the SHA-256 members of NIST's CAVP SHA byte-oriented
test vectors, downloaded on 2026-09-16 from
<https://csrc.nist.gov/CSRC/media/Projects/Cryptographic-Algorithm-Validation-Program/documents/shs/shabytetestvectors.zip>
(linked from the Secure Hashing page above; the zip's own SHA-256 was
`929ef80b7b3418aca026643f6f248815913b60e01741a44bba9e118067f4c9b8`) and vendored byte for
byte, CRLF line endings included. NIST publishes no separate checksum for them, so these are
the checksums as downloaded. `Sha256NistVectorTest` parses the lines below and asserts each
file against its line, and asserts the vector counts (65, 64 and 100 checkpoints), so a
substituted file or a parser that reads nothing fails:

```
75e1cb83994638481808e225b9eb0c1ebd0c232d952ac42b61abce6363be283c  nist-sha256/SHA256ShortMsg.rsp
6fac36f37360bcf74ffcf4465c18e30d6d5a04cc90885b901fc3130c16060974  nist-sha256/SHA256LongMsg.rsp
29ea30c6bb4b84e425fb8c1d731c6bb852dac935825f2bd1143e5d3c4f10bfb9  nist-sha256/SHA256Monte.rsp
```

## A caveat that matters

The BIP-340 vectors exercise **signature** operations, which Nenya cannot perform today:
BIP-340 Schnorr needs a cryptography dependency this project has not taken. They are staged
here ahead of that decision so the vectors are already in place, externally anchored, when
the dependency lands. Until then, no test may claim to have verified a signature.
