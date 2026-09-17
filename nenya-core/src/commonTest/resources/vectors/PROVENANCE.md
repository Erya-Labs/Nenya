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
| `bolt11/11-payment-encoding.md` | [lightning/bolts](https://github.com/lightning/bolts), BOLT #11 at commit `14901bdcacee53d95b46dc276b0f09c85d7d71fd` | CC-BY-4.0 (attribution below) | The whole BOLT #11 document, whose Examples sections publish 16 valid and 10 invalid example invoices, 11 of them with a signature breakdown |

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

`bolt11/11-payment-encoding.md` is BOLT #11, "Invoice Protocol for Lightning Payments",
downloaded on 2026-09-17 from the pinned URL
<https://raw.githubusercontent.com/lightning/bolts/14901bdcacee53d95b46dc276b0f09c85d7d71fd/11-payment-encoding.md>
and vendored byte for byte (52,334 bytes, 863 lines, LF line endings, UTF-8). Commit
`14901bdcacee53d95b46dc276b0f09c85d7d71fd` (2026-03-09, "bolt11: add new test vector for
signature (high-S) with 'n' field defined (#1298)") is the last commit to touch the file; the
file at master `152897261850d93c4f4597f39cf22d7d22d6ede6` (2026-08-26) has the same bytes.
The document is vendored whole, not excerpted, so its example invoices are never retyped: the
test-side extractor `Bolt11Examples` locates each invoice and the values the document states
for it. The document's `# Examples` section holds **16** valid examples and its
`# Examples of Invalid Invoices` section **10** invalid ones. Upstream publishes no checksum, so
this is the checksum as downloaded. `Bolt11ExamplesTest` parses the line below and asserts the
file against it with Nenya's NIST-proven SHA-256, and asserts both example counts, so a
substituted, truncated or misparsed file fails:

```
d2ede88b25ca3017e8cce7cc313a88d3b6a780d34ded2d403b996b2a8c7e9810  bolt11/11-payment-encoding.md
```

Attribution: BOLT #11 is by the Lightning Network specification authors, lightning/bolts
contributors, licensed under the Creative Commons Attribution 4.0 International License
(<https://creativecommons.org/licenses/by/4.0/>), as the document itself and the repository's
README state. It is redistributed here unmodified.

## A caveat that matters

The BIP-340 vectors exercise **signature** operations, which Nenya cannot perform today:
BIP-340 Schnorr needs a cryptography dependency this project has not taken. They are staged
here ahead of that decision so the vectors are already in place, externally anchored, when
the dependency lands. Until then, no test may claim to have verified a signature.
