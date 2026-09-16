package dev.eryalabs.nenya.delivery

import dev.eryalabs.nenya.SpecAnchor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * §10.3's four-operand identity check and §10.4's ordered buyer verification.
 *
 * The positive path is anchored outside this repository. §18 publishes the SHA-256 of the
 * vendored `nip44.vectors.json`, so that file is treated here as a served blob whose `x` and
 * whose `size` are read from the specification and from the filesystem respectively — neither
 * is typed into this file, and a reviewer can re-derive both with `sha256sum` and `wc -c`.
 * Every other fixture comes from [DeliveryFixtures], the seeded generator committed beside
 * this file.
 *
 * Each negative control asserts the *reason* rather than merely that something failed. That is
 * the whole point of §10.3's check being four operands rather than one: a `file-type`
 * divergence and a `size` divergence have different causes, and a check that compares only `x`
 * passes a careless test while catching neither.
 */
class DeliveryVerificationTest {

    // ---------------------------------------------------------------- the external anchor

    @Test
    fun `the vendored NIP-44 vectors hash to the digest the specification publishes`() {
        val vectors = SpecAnchor.nip44VectorsFile()
        val computed = DeliveryFixtures.lowerHex(DeliveryFixtures.sha256(vectors.readBytes()))

        assertEquals(
            SpecAnchor.publishedNip44Digest(),
            computed,
            "the vendored ${vectors.location} does not hash to the digest §18 publishes; either " +
                "the file was swapped or SHA-256 on this JVM is not SHA-256",
        )
        assertTrue(vectors.length() > 0L, "the vendored vector file must not be empty")
    }

    @Test
    fun `the vendored vectors verify as the served blob the specification's digest commits to`() {
        val vectors = SpecAnchor.nip44VectorsFile()
        val plaintext = DeliveryFixtures.blob().plaintext
        val commitment = DeliverableCommitment(
            // Parsed out of §18 at test time, never transcribed.
            x = DeliverableHash.ofHex(SpecAnchor.publishedNip44Digest()),
            ox = DeliveryFixtures.hashOf(plaintext),
            mimeType = "application/json",
            // Measured, never typed.
            sizeBytes = vectors.length(),
        )

        val served = ServedBytesVerified.verifyServedBytes(commitment, vectors.readBytes())
        assertEquals(vectors.length(), served.servedBytesLength)

        val evidence = DeliveryEvidence.verifyPlaintextBytes(served, plaintext)
        assertEquals(vectors.length(), evidence.servedBytesLength)
        assertEquals(plaintext.size.toLong(), evidence.plaintextBytesLength)
    }

    // ------------------------------------------------- §10.3, the four operands, one by one

    @Test
    fun `a release matching on all four operands is accepted`() {
        val blob = DeliveryFixtures.blob()

        blob.commitment.checkReleaseIdentity(blob.matchingRelease())
    }

    @Test
    fun `a release whose file-type diverges is rejected naming file-type`() {
        val blob = DeliveryFixtures.blob()
        val release = DeliverableRelease(
            x = blob.commitment.x,
            ox = blob.commitment.ox,
            fileType = blob.commitment.mimeType + "x",
            sizeBytes = blob.commitment.sizeBytes,
        )

        val refused = assertFailsWith<DeliveryException> { blob.commitment.checkReleaseIdentity(release) }
        assertEquals(DeliveryRejection.FILE_TYPE_MISMATCH, refused.reason)
    }

    @Test
    fun `a release whose file-type differs only in case is rejected, because §10_3 says byte-identical`() {
        val blob = DeliveryFixtures.blob()
        val release = DeliverableRelease(
            x = blob.commitment.x,
            ox = blob.commitment.ox,
            fileType = blob.commitment.mimeType.uppercase(),
            sizeBytes = blob.commitment.sizeBytes,
        )
        assertNotEquals(blob.commitment.mimeType, release.fileType, "the fixture must actually differ")

        // The `+ "x"` control above diverges under a case-insensitive comparison too, so it
        // cannot see the "helpful" normalisation somebody adds later. §10.3 says byte-identical
        // and §4.3's normalisation licence covers hex, not MIME types: `VIDEO/MP4` against a
        // committed `video/mp4` is a divergence and MUST move the order to disputed.
        val refused = assertFailsWith<DeliveryException> { blob.commitment.checkReleaseIdentity(release) }
        assertEquals(DeliveryRejection.FILE_TYPE_MISMATCH, refused.reason)
    }

    @Test
    fun `a release whose x diverges is rejected naming the served hash`() {
        val (blob, other) = DeliveryFixtures.blobs(2)
        val release = DeliverableRelease(
            x = other.commitment.x,
            ox = blob.commitment.ox,
            fileType = blob.commitment.mimeType,
            sizeBytes = blob.commitment.sizeBytes,
        )

        val refused = assertFailsWith<DeliveryException> { blob.commitment.checkReleaseIdentity(release) }
        assertEquals(DeliveryRejection.SERVED_HASH_MISMATCH, refused.reason)
    }

    @Test
    fun `a release whose ox diverges is rejected naming the plaintext hash`() {
        val (blob, other) = DeliveryFixtures.blobs(2)
        val release = DeliverableRelease(
            x = blob.commitment.x,
            ox = other.commitment.ox,
            fileType = blob.commitment.mimeType,
            sizeBytes = blob.commitment.sizeBytes,
        )

        val refused = assertFailsWith<DeliveryException> { blob.commitment.checkReleaseIdentity(release) }
        assertEquals(DeliveryRejection.PLAINTEXT_HASH_MISMATCH, refused.reason)
    }

    @Test
    fun `a release whose size diverges is rejected naming size`() {
        val blob = DeliveryFixtures.blob()
        val release = DeliverableRelease(
            x = blob.commitment.x,
            ox = blob.commitment.ox,
            fileType = blob.commitment.mimeType,
            sizeBytes = blob.commitment.sizeBytes + 1L,
        )

        val refused = assertFailsWith<DeliveryException> { blob.commitment.checkReleaseIdentity(release) }
        assertEquals(
            DeliveryRejection.SIZE_MISMATCH,
            refused.reason,
            "a size divergence is a disagreement between two signed statements, and §10.3 makes it " +
                "one of the four operands; an implementation comparing only x never sees it",
        )
    }

    @Test
    fun `a release with no file-type tag is refused rather than skipped`() {
        val blob = DeliveryFixtures.blob()
        val release = DeliverableRelease(
            x = blob.commitment.x,
            ox = blob.commitment.ox,
            fileType = null,
            sizeBytes = blob.commitment.sizeBytes,
        )

        val refused = assertFailsWith<DeliveryException> { blob.commitment.checkReleaseIdentity(release) }
        assertEquals(
            DeliveryRejection.FILE_TYPE_ABSENT,
            refused.reason,
            "§10.3: an implementation MUST NOT skip the check because the tag it looked for was absent",
        )
        assertNotEquals(
            DeliveryRejection.FILE_TYPE_MISMATCH,
            refused.reason,
            "absence and divergence are different bugs on the provider's side",
        )
    }

    @Test
    fun `a commitment with no MIME type is refused where it is built`() {
        val blob = DeliveryFixtures.blob()

        val refused = assertFailsWith<DeliveryException> {
            DeliverableCommitment(blob.commitment.x, blob.commitment.ox, "", blob.commitment.sizeBytes)
        }
        assertEquals(DeliveryRejection.MIME_TYPE_ABSENT, refused.reason)
    }

    // ------------------------------------------------------------ §4.3, reading x and ox

    @Test
    fun `an x of sixty-three hex characters is rejected as the wrong length, never padded`() {
        val blob = DeliveryFixtures.blob()
        val short = blob.commitment.x.toHex().dropLast(1)
        assertEquals(DeliverableHash.HEX_LENGTH - 1, short.length)

        val refused = assertFailsWith<DeliveryException> { DeliverableHash.ofHex(short) }
        assertEquals(
            DeliveryRejection.WRONG_LENGTH,
            refused.reason,
            "§4.3: reject a value of the wrong length rather than padding or truncating it",
        )
    }

    @Test
    fun `an x of sixty-five hex characters is rejected as the wrong length, never truncated`() {
        val blob = DeliveryFixtures.blob()

        val refused = assertFailsWith<DeliveryException> { DeliverableHash.ofHex(blob.commitment.x.toHex() + "a") }
        assertEquals(DeliveryRejection.WRONG_LENGTH, refused.reason)
    }

    @Test
    fun `a non-hex x is rejected as not hex`() {
        val blob = DeliveryFixtures.blob()
        val notHex = blob.commitment.x.toHex().dropLast(1) + "g"

        val refused = assertFailsWith<DeliveryException> { DeliverableHash.ofHex(notHex) }
        assertEquals(DeliveryRejection.NOT_HEX, refused.reason)
    }

    @Test
    fun `an uppercase x is accepted and normalised`() {
        val blob = DeliveryFixtures.blob()
        val lower = blob.commitment.x.toHex()

        val upper = DeliverableHash.ofHex(lower.uppercase())

        assertEquals(
            blob.commitment.x,
            upper,
            "§4.3's no-normalisation exception list is exhaustive and names only the BOLT-11 string " +
                "and the preimage; x and ox are named among the values that ARE normalised",
        )
        assertEquals(lower, upper.toHex(), "the canonical form is lowercase whatever it was read in")
    }

    @Test
    fun `toHex emits lowercase, checked against a digest this test hexed itself`() {
        val blob = DeliveryFixtures.blob()

        // Independently computed rather than compared against another toHex call: the uppercase
        // control above is self-consistent and would survive a wholesale case flip in the
        // encoder, while §4.3 and §10.1 both say an implementation MUST *emit* lowercase — which
        // is what a tag codec will publish into ["x", ...] the moment one exists.
        val expected = DeliveryFixtures.lowerHex(DeliveryFixtures.sha256(blob.served))

        assertEquals(expected, blob.commitment.x.toHex())
        assertTrue(
            blob.commitment.x.toHex().none(Char::isUpperCase),
            "§4.3: implementations MUST emit lowercase hex",
        )
    }

    @Test
    fun `an uppercase x in a release still matches a lowercase commitment`() {
        val blob = DeliveryFixtures.blob()
        val release = DeliverableRelease(
            x = DeliverableHash.ofHex(blob.commitment.x.toHex().uppercase()),
            ox = DeliverableHash.ofHex(blob.commitment.ox.toHex().uppercase()),
            fileType = blob.commitment.mimeType,
            sizeBytes = blob.commitment.sizeBytes,
        )

        blob.commitment.checkReleaseIdentity(release)
    }

    @Test
    fun `a negative size is refused on both the commitment and the release`() {
        val blob = DeliveryFixtures.blob()

        val commitment = assertFailsWith<DeliveryException> {
            DeliverableCommitment(blob.commitment.x, blob.commitment.ox, DeliveryFixtures.MIME_TYPE, -1L)
        }
        assertEquals(DeliveryRejection.NEGATIVE_SIZE, commitment.reason)

        val release = assertFailsWith<DeliveryException> {
            DeliverableRelease(blob.commitment.x, blob.commitment.ox, DeliveryFixtures.MIME_TYPE, -1L)
        }
        assertEquals(DeliveryRejection.NEGATIVE_SIZE, release.reason)
    }

    // --------------------------------------------------------- §10.4, the two hash checks

    @Test
    fun `the committed blob verifies through both steps and reports what it checked`() {
        val blob = DeliveryFixtures.blob()

        val served = ServedBytesVerified.verifyServedBytes(blob.commitment, blob.served)
        val evidence = DeliveryEvidence.verifyPlaintextBytes(served, blob.plaintext)

        assertEquals(blob.commitment, evidence.commitment)
        assertEquals(blob.served.size.toLong(), evidence.servedBytesLength)
        assertEquals(blob.plaintext.size.toLong(), evidence.plaintextBytesLength)
        assertEquals(
            setOf(
                DeliveryCheck.SERVED_BYTES_SIZE,
                DeliveryCheck.SERVED_BYTES_HASH,
                DeliveryCheck.PLAINTEXT_BYTES_HASH,
            ),
            evidence.checksPerformed,
        )
    }

    @Test
    fun `served bytes that are not the committed blob are refused at the first step`() {
        val blob = DeliveryFixtures.blob()
        // Same length, so the size rules cannot be what refuses it — one byte apart, so the
        // digest is the only thing that can.
        val impostor = blob.served.copyOf().also { it[0] = (it[0] + 1).toByte() }

        val refused = assertFailsWith<DeliveryException> {
            ServedBytesVerified.verifyServedBytes(blob.commitment, impostor)
        }
        assertEquals(DeliveryRejection.SERVED_BYTES_MISMATCH, refused.reason)
    }

    @Test
    fun `plaintext that hashes to x rather than ox is refused at the second step`() {
        val blob = DeliveryFixtures.blob()
        val served = ServedBytesVerified.verifyServedBytes(blob.commitment, blob.served)

        val refused = assertFailsWith<DeliveryException> {
            // The served bytes hash to `x`, which step 1 has just accepted. Handing them to
            // step 3 must still fail: §10.4 step 3 compares against `ox` and nothing else, and
            // a verifier that reused step 1's operand would pass this and settle every order on
            // the ciphertext it had already seen.
            DeliveryEvidence.verifyPlaintextBytes(served, blob.served)
        }
        assertEquals(DeliveryRejection.PLAINTEXT_BYTES_MISMATCH, refused.reason)
    }

    @Test
    fun `plaintext from another blob is refused at the second step`() {
        val (blob, other) = DeliveryFixtures.blobs(2)
        val served = ServedBytesVerified.verifyServedBytes(blob.commitment, blob.served)

        val refused = assertFailsWith<DeliveryException> {
            DeliveryEvidence.verifyPlaintextBytes(served, other.plaintext)
        }
        assertEquals(DeliveryRejection.PLAINTEXT_BYTES_MISMATCH, refused.reason)
    }

    @Test
    fun `bytes whose length disagrees with the declared size are refused naming size`() {
        val blob = DeliveryFixtures.blob()

        // Both directions. A partial download is the likelier of the two by a wide margin, and a
        // check written as `>` rather than `!=` catches only the other one — reporting a truncated
        // blob as "the wrong or a tampered blob" when what actually happened is that it is short.
        for (disagreeing in listOf(blob.served + byteArrayOf(0), blob.served.copyOf(blob.served.size - 1))) {
            val refused = assertFailsWith<DeliveryException> {
                ServedBytesVerified.verifyServedBytes(blob.commitment, disagreeing)
            }
            assertEquals(
                DeliveryRejection.SIZE_DISAGREEMENT,
                refused.reason,
                "§10.4: an implementation MUST refuse a blob whose length disagrees with size, and " +
                    "${disagreeing.size} is not ${blob.commitment.sizeBytes}",
            )
        }
    }

    @Test
    fun `a declared size above the injected bound is refused as limit exceeded, never truncated`() {
        val blob = DeliveryFixtures.blob()
        val bound = blob.commitment.sizeBytes - 1L

        val refused = assertFailsWith<DeliveryException> {
            ServedBytesVerified.verifyServedBytes(blob.commitment, blob.served, maxServedBytes = bound)
        }
        assertEquals(DeliveryRejection.SIZE_LIMIT_EXCEEDED, refused.reason)

        // The same bytes and the same commitment at a bound one larger: so the refusal above
        // was the bound and not the blob, and nothing was trimmed to fit under it.
        val served = ServedBytesVerified.verifyServedBytes(
            blob.commitment,
            blob.served,
            maxServedBytes = blob.commitment.sizeBytes,
        )
        assertEquals(blob.commitment.sizeBytes, served.servedBytesLength)
    }

    @Test
    fun `the default bound is the one used when none is injected`() {
        val blob = DeliveryFixtures.blob()
        val oversized = DeliverableCommitment(
            x = blob.commitment.x,
            ox = blob.commitment.ox,
            mimeType = blob.commitment.mimeType,
            sizeBytes = ServedBytesVerified.DEFAULT_MAX_SERVED_BYTES + 1L,
        )

        val refused = assertFailsWith<DeliveryException> {
            ServedBytesVerified.verifyServedBytes(oversized, blob.served)
        }
        assertEquals(DeliveryRejection.SIZE_LIMIT_EXCEEDED, refused.reason)
        // Bounded on both sides, because a one-sided assertion is satisfied by a bound of
        // Long.MAX_VALUE — which is §4.3's "MUST enforce a bound" honoured in name only, and
        // which the refusal above cannot see because it is written relative to the constant.
        assertTrue(
            ServedBytesVerified.DEFAULT_MAX_SERVED_BYTES > 18_342_912L,
            "§10.1's own worked example commits to an 18 MiB video, so a default that refused it " +
                "would refuse the specification's own example",
        )
        assertTrue(
            ServedBytesVerified.DEFAULT_MAX_SERVED_BYTES < 100L * 1024L * 1024L,
            "§4.3 requires a bound that actually bounds; the blob host that announces 100 MiB is " +
                "the case the default exists to refuse",
        )
    }

    @Test
    fun `the bound the verification ran under is carried on the evidence, not just a flag`() {
        val blob = DeliveryFixtures.blob()

        val bounded = ServedBytesVerified.verifyServedBytes(blob.commitment, blob.served)
        val unbounded = ServedBytesVerified.verifyServedBytes(
            blob.commitment,
            blob.served,
            maxServedBytes = Long.MAX_VALUE,
        )

        assertEquals(ServedBytesVerified.DEFAULT_MAX_SERVED_BYTES, bounded.maxServedBytes)
        assertEquals(Long.MAX_VALUE, unbounded.maxServedBytes)
        assertEquals(
            Long.MAX_VALUE,
            DeliveryEvidence.verifyPlaintextBytes(unbounded, blob.plaintext).maxServedBytes,
            "§17: SERVED_BYTES_SIZE alone cannot tell a 64 MiB bound from a disabled one, and the " +
                "consumer that has to act on the difference is the order state machine one layer up",
        )
        assertEquals(
            DeliveryEvidence.CHECKS_PERFORMED_HERE,
            DeliveryEvidence.verifyPlaintextBytes(unbounded, blob.plaintext).checksPerformed,
            "the flag is deliberately the same either way; the number is what distinguishes them",
        )
    }

    @Test
    fun `a zero-size deliverable is representable and this library does not refuse it`() {
        val empty = ByteArray(0)
        val commitment = DeliverableCommitment(
            x = DeliveryFixtures.hashOf(empty),
            ox = DeliveryFixtures.hashOf(empty),
            mimeType = DeliveryFixtures.MIME_TYPE,
            sizeBytes = 0L,
        )

        // Pinned for the same reason the x == ox case below is: §10 nowhere forbids it, and it
        // cannot come from a conformant provider — §10.2 appends a 128-bit tag to the ciphertext,
        // so a conformant served blob is never shorter than 16 bytes. The provider who commits to
        // zero bytes and delivers zero bytes has delivered what it committed to, which is the only
        // thing §10.5 says `ox` proves. Refusing it here would be this library inventing a rule;
        // the sibling degenerate shape got pinned and this one should not have been left implicit.
        val served = ServedBytesVerified.verifyServedBytes(commitment, empty)
        val evidence = DeliveryEvidence.verifyPlaintextBytes(served, empty)

        assertEquals(0L, evidence.servedBytesLength)
        assertEquals(0L, evidence.plaintextBytesLength)
        assertTrue(
            DeliveryCheck.ENCRYPTION_PARAMETERS in evidence.checksNotPerformedHere,
            "and §17 is satisfied because the evidence says on its face that §10.2's parameters — " +
                "the very rule that makes a zero-length served blob impossible — went unchecked here",
        )
    }

    @Test
    fun `a commitment whose x equals its ox is representable and this library does not refuse it`() {
        val blob = DeliveryFixtures.blob()
        val degenerate = DeliverableCommitment(
            x = blob.commitment.x,
            ox = blob.commitment.x,
            mimeType = blob.commitment.mimeType,
            sizeBytes = blob.commitment.sizeBytes,
        )

        // The served bytes then satisfy step 3 as well, so evidence is reachable for a blob that
        // was never decrypted. That is deliberate: §10 nowhere forbids the shape, and it cannot
        // arise from a conformant provider, because §10.2 appends a 128-bit tag to the ciphertext
        // and a plaintext therefore never equals its own served blob. Refusing it here would be
        // this library inventing a rule; pinning it here is so that a later change to the shape
        // is a decision somebody took rather than a behaviour that drifted.
        val served = ServedBytesVerified.verifyServedBytes(degenerate, blob.served)
        val evidence = DeliveryEvidence.verifyPlaintextBytes(served, blob.served)

        assertTrue(
            DeliveryCheck.GCM_AUTHENTICATION in evidence.checksNotPerformedHere,
            "and the honesty record is exactly why it is safe: the evidence says on its face that " +
                "no decryption was verified by this library",
        )
    }

    // ------------------------------------------------------------- §17, the honesty record

    @Test
    fun `the evidence names the GCM authentication it did not perform`() {
        val blob = DeliveryFixtures.blob()
        val served = ServedBytesVerified.verifyServedBytes(blob.commitment, blob.served)

        val evidence = DeliveryEvidence.verifyPlaintextBytes(served, blob.plaintext)

        assertTrue(
            DeliveryCheck.GCM_AUTHENTICATION in evidence.checksNotPerformedHere,
            "§10.4 step 2 sits between the two checks this library performs, and this library does " +
                "not decrypt; §17 forbids reporting an unverified thing as verified",
        )
        assertEquals(
            setOf(
                DeliveryCheck.GCM_AUTHENTICATION,
                DeliveryCheck.SERVED_BYTES_PROVENANCE,
                DeliveryCheck.RELEASE_IDENTITY,
                DeliveryCheck.RELEASE_ORDER_BINDING,
                DeliveryCheck.COMMITMENT_CARRIES_NO_KEY,
                DeliveryCheck.ENCRYPTION_PARAMETERS,
            ),
            evidence.checksNotPerformedHere,
        )
    }

    @Test
    fun `the two check sets are disjoint and together cover the vocabulary`() {
        val performed = DeliveryEvidence.CHECKS_PERFORMED_HERE
        val notPerformed = DeliveryEvidence.CHECKS_NOT_PERFORMED_HERE

        assertTrue(
            (performed intersect notPerformed).isEmpty(),
            "a check cannot be both performed and not performed",
        )
        assertEquals(
            DeliveryCheck.entries.toSet(),
            performed + notPerformed,
            "every constant in the vocabulary must be accounted for in one set or the other, or a " +
                "check added later is silently claimed by neither and noticed by nobody",
        )
    }

    @Test
    fun `the published check sets cannot be mutated by a caller`() {
        @Suppress("UNCHECKED_CAST")
        val performed = DeliveryEvidence.CHECKS_PERFORMED_HERE as MutableSet<DeliveryCheck>
        @Suppress("UNCHECKED_CAST")
        val notPerformed = DeliveryEvidence.CHECKS_NOT_PERFORMED_HERE as MutableSet<DeliveryCheck>

        assertFailsWith<UnsupportedOperationException> { performed.add(DeliveryCheck.GCM_AUTHENTICATION) }
        assertFailsWith<UnsupportedOperationException> { notPerformed.clear() }
    }

    // ------------------------------------------------------ §12 / STOP RULE 14, the leak controls

    @Test
    fun `no string representation carries a commitment hash`() {
        val blob = DeliveryFixtures.blob()
        val served = ServedBytesVerified.verifyServedBytes(blob.commitment, blob.served)
        val evidence = DeliveryEvidence.verifyPlaintextBytes(served, blob.plaintext)
        val release = blob.matchingRelease()
        val xHex = blob.commitment.x.toHex()
        val oxHex = blob.commitment.ox.toHex()

        for (rendered in listOf(
            blob.commitment.x.toString(),
            blob.commitment.ox.toString(),
            blob.commitment.toString(),
            release.toString(),
            served.toString(),
            evidence.toString(),
        )) {
            assertFalse(xHex in rendered, "an x reached a string representation: $rendered")
            assertFalse(oxHex in rendered, "an ox reached a string representation: $rendered")
        }
    }

    @Test
    fun `a rejection message never echoes the hash it refused`() {
        val blob = DeliveryFixtures.blob()

        val refused = assertFailsWith<DeliveryException> {
            ServedBytesVerified.verifyServedBytes(
                blob.commitment,
                blob.served.copyOf().also { it[0] = (it[0] + 1).toByte() },
            )
        }
        val message = refused.message.orEmpty()
        assertTrue(message.isNotEmpty(), "a rejection must still say something useful")
        assertFalse(blob.commitment.x.toHex() in message)
        assertFalse(blob.commitment.ox.toHex() in message)
    }

    // ------------------------------------------------------------------- the arrays are safe

    @Test
    fun `mutating the caller's array afterwards does not change a hash already read`() {
        val blob = DeliveryFixtures.blob()
        val hex = blob.commitment.x.toHex()
        val borrowed = blob.commitment.x.bytes()

        borrowed.fill(0)

        assertEquals(hex, blob.commitment.x.toHex(), "bytes() must hand out a copy, not the field")
    }
}
