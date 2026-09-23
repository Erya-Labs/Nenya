package dev.eryalabs.nenya.delivery

import dev.eryalabs.nenya.collections.readOnlySetOf
import dev.eryalabs.nenya.crypto.constantTimeEquals
import dev.eryalabs.nenya.crypto.sha256

/**
 * One of §10's obligations, named so that what this library did and did not do is
 * machine-readable rather than a paragraph in a README.
 *
 * §17 requires an implementation that omits a check to expose a capability surface — a
 * machine-readable statement of what it verifies — and MUST NOT report unverified things as
 * verified. §17 item 7 asks an implementation to "commit and verify `x` and `ox` **with the
 * encryption parameters in §10.2**", and this library verifies the two hashes while performing
 * no encryption at all. An evidence type that said only "verified" would be claiming item 7
 * outright, which is the over-claim this enum exists to prevent.
 *
 * Mirrors `PaymentCheck` in the payment package deliberately: the two capability records are
 * read by the same caller, at two ends of the same order, and one of them being a set of
 * booleans would make the pair unreadable.
 */
public enum class DeliveryCheck {

    /**
     * §10.4's bound and length rule: the commitment's declared `size` is within the bound the
     * caller injected (§4.3), and the served bytes are exactly that many. Performed here.
     *
     * "Within the bound the caller injected" is doing real work in that sentence, and the bound
     * is therefore published alongside this constant as
     * [ServedBytesVerified.maxServedBytes]. §4.3 requires a bound; it does not require anyone
     * else's bound, so a caller that passed `Long.MAX_VALUE` has satisfied this constant while
     * enforcing nothing. A consumer that needs to know which of the two happened — and the
     * order state machine is exactly that consumer — reads the number rather than the flag.
     */
    SERVED_BYTES_SIZE,

    /**
     * §10.4 step 1 — `SHA-256(served bytes)` equals `x`, computed here with
     * this library's own FIPS 180-4 SHA-256. Performed here.
     */
    SERVED_BYTES_HASH,

    /**
     * §10.4 step 3 — `SHA-256(plaintext bytes)` equals `ox`, computed here with
     * this library's own FIPS 180-4 SHA-256. Performed here, and it is the one §11.3
     * invariant 4 turns on: `settled` is reachable only after the buyer's *own* hash computation.
     */
    PLAINTEXT_BYTES_HASH,

    /**
     * §10.4 step 2 — AES-256-GCM decryption with the released key and nonce, whose
     * authentication MUST pass. **Not performed here.** This library does not decrypt: the
     * plaintext arrives as a parameter, already decrypted and already GCM-authenticated by the
     * embedding client. A caller that skipped the authentication and handed over whatever the
     * cipher emitted has a `DeliveryEvidence` proving only that those bytes hash to `ox` — which
     * is a real and useful fact, and is not §10.4.
     */
    GCM_AUTHENTICATION,

    /**
     * That the served bytes are the bytes at the commitment's `url`. **Not performed here.**
     * This library opens no socket (§12 item 10 forbids auto-fetching a counterparty's URL at
     * all, and it has no I/O by construction), so it cannot attest where a byte array came
     * from — only that it hashes to `x`. In practice this check is subsumed by the hash: a blob
     * from anywhere that hashes to `x` is the committed blob. It is listed because "the buyer
     * downloaded the bytes at the URL" is step 1's first clause and something the caller, not
     * this library, did.
     */
    SERVED_BYTES_PROVENANCE,

    /**
     * §10.3's four-operand identity check between the commitment and the release.
     * **Not performed in this chain**, though this package implements it: it is
     * [DeliverableCommitment.checkReleaseIdentity], a separate call, because §10.3's failure
     * moves the order to `disputed` while §10.4's failures are settlement preconditions, and
     * because a buyer verifying a blob against a commitment need not have a release in hand at
     * all. Evidence produced without it MUST NOT be read as having performed it.
     */
    RELEASE_IDENTITY,

    /**
     * §10.3's binding: the release's `["order", ...]` tag names an open order of this
     * implementation's own, in state `paid`. §10.3 is explicit that hash equality is a *check*
     * and not the binding, so this one cannot be inferred from the two that are performed.
     *
     * **Not performed in this chain**, and it cannot be: a [DeliveryEvidence] is produced from a
     * commitment and two byte arrays, and holds no `order` tag and no order to compare one
     * against. It **is** performed by `DeliverableReleaseMessage.asOrderEvent` in the channel
     * package, which takes the decoded `kind:15` and the open `Order` and makes both halves of
     * the comparison — and which is the only way to build an `OrderEvent.DeliverableReleased`
     * from a decoded release, so the event cannot exist without it. What that produces is
     * recorded on the **order**, in `Order.deliveryChecksPerformed`; evidence produced here
     * still MUST NOT be read as having performed it.
     */
    RELEASE_ORDER_BINDING,

    /**
     * §10.1: the commitment message MUST NOT contain `decryption-key` or `decryption-nonce`,
     * and an implementation MUST reject one that does — releasing the key at commitment time
     * collapses the whole construction.
     *
     * **Not performed in this chain**, for the reason [RELEASE_ORDER_BINDING] is not:
     * [DeliverableCommitment] holds four typed values rather than a tag list, so there is no tag
     * here to refuse. It **is** performed by `DeliveryCommitmentMessage.decode`, which refuses
     * either tag by name before it reads a value, and travels onto the order through
     * `asOrderEvent`.
     */
    COMMITMENT_CARRIES_NO_KEY,

    /**
     * §10.2's parameters — `encryption-algorithm` is `aes-gcm`, meaning AES-256-GCM with a
     * per-deliverable 256-bit key, a 96-bit nonce never reused with that key, a 128-bit tag
     * appended to the ciphertext and no AAD. **Not performed here.** This is the other half of
     * §17 item 7 and the reason a bare "verified" would be a lie.
     */
    ENCRYPTION_PARAMETERS,
}

/**
 * The served blob hashed to the commitment's `x` — §10.4 step 1, and nothing beyond it.
 *
 * ### This type is §10.4's ordering, enforced by the compiler
 *
 * §10.4 says the buyer MUST verify in this order: hash the served bytes against `x`, decrypt,
 * then hash the plaintext against `ox`. The ordering is not decoration — a buyer who checks
 * `ox` first has decrypted a blob it has no reason to trust, which is exactly what step 1's
 * "MUST NOT decrypt it" forbids. So the ordering is a type: the only way to obtain a
 * [DeliveryEvidence] is [DeliveryEvidence.verifyPlaintextBytes], the only way to call that is
 * with one of these, and the only way to obtain one of these is [verifyServedBytes]. There is
 * no path to the `ox` check that does not pass the `x` check, and no comment anybody has to
 * remember to read.
 *
 * ### Unforgeable through the published API
 *
 * Same shape and same reason as `VerifiedPayment` in the payment package: an interface has no
 * constructor to synthesise an accessor for, so `Class.getConstructors()` on it is empty by
 * construction and stays empty. A class with a `private constructor` and a companion factory
 * would emit a **public synthetic** constructor carrying a trailing `DefaultConstructorMarker`
 * that a Java client can call with a `null` marker, and a `data class` with a private primary
 * constructor is still reachable through the generated `copy()`. Neither is good enough.
 *
 * The claim is "unforgeable **through the published API**", not unforgeable full stop: a
 * `private` nested class compiles to a package-private JVM class with a public constructor, so
 * a client that declares itself into `dev.eryalabs.nenya.delivery` on the same classloader can
 * still reach it. Nenya protects its user against counterparties and relays, not against the
 * client embedding it.
 */
public sealed interface ServedBytesVerified {

    /** The commitment these bytes were checked against. Carries the `ox` step 3 will need. */
    public val commitment: DeliverableCommitment

    /** How many bytes were hashed. Equal to [DeliverableCommitment.sizeBytes] by construction. */
    public val servedBytesLength: Long

    /**
     * The §4.3 bound this verification was performed under — the value the caller passed to
     * [verifyServedBytes], or [DEFAULT_MAX_SERVED_BYTES] if it passed none.
     *
     * Published because [DeliveryCheck.SERVED_BYTES_SIZE] alone is coarser than the check
     * behind it: evidence produced under a 64 MiB bound and evidence produced under
     * `Long.MAX_VALUE` would otherwise be indistinguishable, and a consumer reading the flag
     * would conclude that §4.3's resource rule had been enforced when the caller may have
     * disabled it. §17 forbids reporting an unverified thing as verified, and "bounded, by a
     * bound that bounds nothing" is that.
     */
    public val maxServedBytes: Long

    public companion object {

        /**
         * The default bound on a declared deliverable size (§4.3, §10.4).
         *
         * §4.3 requires an implementation to bound anything it parses and publishes defaults
         * for events, tags and content; it publishes none for a deliverable blob, and §10.4
         * only says the download MUST be bounded. So this number is **local policy, not a wire
         * value**, and a client with large deliverables is expected to raise it deliberately
         * rather than discover it. 64 MiB comfortably clears the 18 MiB `video/mp4` of §10.1's
         * own worked example while still refusing the blob host that announces 100 MiB.
         *
         * It is a parameter with a default rather than a constant so a test can pin it, which
         * is the same reason every other dependency in this library is injected.
         */
        public const val DEFAULT_MAX_SERVED_BYTES: Long = 64L * 1024L * 1024L

        /**
         * §10.4 step 1: the SHA-256 of the served bytes, computed here, MUST equal `x`.
         *
         * Three refusals, and §10.4 fixes no order between the size rules and the hash, so none
         * is asserted here or in the tests:
         *
         * - the commitment's declared [DeliverableCommitment.sizeBytes] is above
         *   [maxServedBytes] — [DeliveryRejection.SIZE_LIMIT_EXCEEDED]. §4.3 requires rejecting
         *   rather than truncating when a bound is exceeded, so nothing is hashed and nothing
         *   is trimmed to fit;
         * - [servedBytes] is not [DeliverableCommitment.sizeBytes] long —
         *   [DeliveryRejection.SIZE_DISAGREEMENT]. §10.4: an implementation MUST refuse a blob
         *   whose length disagrees with `size`;
         * - the digest is not `x` — [DeliveryRejection.SERVED_BYTES_MISMATCH]. The buyer has
         *   the wrong or a tampered blob, and §10.4 says it MUST NOT decrypt and MUST NOT
         *   settle. Returning nothing is how that is enforced: without this value there is no
         *   way to call step 3.
         *
         * [servedBytes] is not copied and not retained — only its digest and its length are —
         * so a caller streaming a large blob is free to reuse the array afterwards.
         *
         * @throws DeliveryException with one of the three reasons above.
         */
        public fun verifyServedBytes(
            commitment: DeliverableCommitment,
            servedBytes: ByteArray,
            maxServedBytes: Long = DEFAULT_MAX_SERVED_BYTES,
        ): ServedBytesVerified {
            if (commitment.sizeBytes > maxServedBytes) {
                throw DeliveryException(
                    DeliveryRejection.SIZE_LIMIT_EXCEEDED,
                    "the commitment declares ${commitment.sizeBytes} bytes and this caller's bound " +
                        "is $maxServedBytes; §4.3 requires refusing rather than truncating when a " +
                        "bound is exceeded",
                )
            }
            if (servedBytes.size.toLong() != commitment.sizeBytes) {
                throw DeliveryException(
                    DeliveryRejection.SIZE_DISAGREEMENT,
                    "the commitment declares ${commitment.sizeBytes} bytes and ${servedBytes.size} " +
                        "were supplied; §10.4 requires refusing a blob whose length disagrees with size",
                )
            }
            val digest = sha256(servedBytes)
            if (!constantTimeEquals(digest, commitment.x.bytes())) {
                throw DeliveryException(
                    DeliveryRejection.SERVED_BYTES_MISMATCH,
                    "the SHA-256 of the served bytes is not the commitment's x (§10.4 step 1); this " +
                        "is the wrong or a tampered blob, and it MUST NOT be decrypted",
                )
            }
            return ServedBytes(commitment, servedBytes.size.toLong(), maxServedBytes)
        }

        /** The single implementation. Private, so the only door in is [verifyServedBytes]. */
        private class ServedBytes(
            override val commitment: DeliverableCommitment,
            override val servedBytesLength: Long,
            override val maxServedBytes: Long,
        ) : ServedBytesVerified {

            override fun toString(): String =
                "ServedBytesVerified(servedBytesLength=$servedBytesLength, " +
                    "maxServedBytes=$maxServedBytes)"
        }
    }
}

/**
 * The buyer's own proof that the committed deliverable was delivered — §10.4 steps 1 and 3,
 * both computed here.
 *
 * §11.3 invariant 4 says `settled` is reachable only after the buyer's **own** hash
 * computation, never on the provider's assertion. This is that computation's result, and the
 * only value the order state machine may accept for `released → settled`. It cannot be
 * constructed any other way — see [ServedBytesVerified]'s note on the shape and on what
 * "unforgeable through the published API" does and does not claim.
 *
 * ### It says what it did not check
 *
 * §10 asks for more than two hashes, and §17 item 7 asks for the hashes *plus* §10.2's
 * encryption parameters. This library performs three of §10's obligations and records the rest
 * in [checksNotPerformedHere] — the GCM authentication of step 2 above all, which the caller
 * performed and this library did not. An instance that recorded nothing would silently claim
 * §17 item 7, one layer below where the order state machine would repeat the claim.
 */
public sealed interface DeliveryEvidence {

    /** The commitment both hashes were checked against. */
    public val commitment: DeliverableCommitment

    /** How many served bytes were hashed against `x`. */
    public val servedBytesLength: Long

    /** How many plaintext bytes were hashed against `ox`. */
    public val plaintextBytesLength: Long

    /**
     * The §4.3 bound the served-bytes step ran under. Carried forward from
     * [ServedBytesVerified.maxServedBytes] for the reason given there: the flag is coarser than
     * the check, and the consumer that has to act on the difference is a layer above this one.
     */
    public val maxServedBytes: Long

    /** The §10 obligations this library performed for itself. Never empty. */
    public val checksPerformed: Set<DeliveryCheck>

    /**
     * The §10 obligations this library did **not** perform, which the caller must perform or
     * account for before treating the deliverable as verified per §17 item 7. Never empty in v1.
     */
    public val checksNotPerformedHere: Set<DeliveryCheck>

    public companion object {

        /** §10.4 steps 1 and 3, and §10.4's size rule — everything this library computes itself. */
        public val CHECKS_PERFORMED_HERE: Set<DeliveryCheck> =
            readOnlySetOf(
                linkedSetOf(
                    DeliveryCheck.SERVED_BYTES_SIZE,
                    DeliveryCheck.SERVED_BYTES_HASH,
                    DeliveryCheck.PLAINTEXT_BYTES_HASH,
                ),
            )

        /**
         * Everything §10 asks for that this library does not do: step 2's decryption, the
         * provenance of the bytes, §10.3's identity check and order binding, §10.1's
         * key-absence rule and §10.2's parameters.
         */
        public val CHECKS_NOT_PERFORMED_HERE: Set<DeliveryCheck> =
            readOnlySetOf(
                linkedSetOf(
                    DeliveryCheck.GCM_AUTHENTICATION,
                    DeliveryCheck.SERVED_BYTES_PROVENANCE,
                    DeliveryCheck.RELEASE_IDENTITY,
                    DeliveryCheck.RELEASE_ORDER_BINDING,
                    DeliveryCheck.COMMITMENT_CARRIES_NO_KEY,
                    DeliveryCheck.ENCRYPTION_PARAMETERS,
                ),
            )

        /**
         * §10.4 step 3: the SHA-256 of the plaintext, computed here, MUST equal `ox`.
         *
         * Reachable only with a [ServedBytesVerified], which is §10.4's ordering made
         * structural rather than documentary.
         *
         * There is deliberately no length check on [plaintextBytes]. §10.1's `size` is the
         * length of the **encrypted** blob, and no field of the commitment states the
         * plaintext's length, so there is nothing to compare it against — asserting one would
         * be inventing a rule §10 does not have. The `ox` hash is the whole of step 3.
         *
         * [plaintextBytes] is not copied and not retained — only its digest and its length are.
         * That is a privacy property as much as a performance one: the plaintext is the thing
         * the buyer paid for, and a verifier that kept a reference to it would be a place for
         * it to survive.
         *
         * @throws DeliveryException [DeliveryRejection.PLAINTEXT_BYTES_MISMATCH] when the
         *   digest is not `ox`. §10.4: the order MUST then move to `disputed` — which is the
         *   caller's transition to make, since this package has no opinion about state.
         */
        public fun verifyPlaintextBytes(
            servedBytes: ServedBytesVerified,
            plaintextBytes: ByteArray,
        ): DeliveryEvidence {
            val commitment = servedBytes.commitment
            val digest = sha256(plaintextBytes)
            if (!constantTimeEquals(digest, commitment.ox.bytes())) {
                throw DeliveryException(
                    DeliveryRejection.PLAINTEXT_BYTES_MISMATCH,
                    "the SHA-256 of the plaintext is not the commitment's ox (§10.4 step 3); the " +
                        "order MUST move to disputed",
                )
            }
            return HashedDelivery(
                commitment = commitment,
                servedBytesLength = servedBytes.servedBytesLength,
                plaintextBytesLength = plaintextBytes.size.toLong(),
                maxServedBytes = servedBytes.maxServedBytes,
            )
        }

        /**
         * The single implementation. Private, so the only door in is [verifyPlaintextBytes],
         * whose own only door in is [ServedBytesVerified.verifyServedBytes].
         */
        private class HashedDelivery(
            override val commitment: DeliverableCommitment,
            override val servedBytesLength: Long,
            override val plaintextBytesLength: Long,
            override val maxServedBytes: Long,
        ) : DeliveryEvidence {

            override val checksPerformed: Set<DeliveryCheck> get() = CHECKS_PERFORMED_HERE

            override val checksNotPerformedHere: Set<DeliveryCheck> get() = CHECKS_NOT_PERFORMED_HERE

            /** Names the two lengths and the two check sets, and no hash and no byte. */
            override fun toString(): String =
                "DeliveryEvidence(servedBytesLength=$servedBytesLength, " +
                    "plaintextBytesLength=$plaintextBytesLength, maxServedBytes=$maxServedBytes, " +
                    "performed=$checksPerformed, notPerformedHere=$checksNotPerformedHere)"
        }
    }
}
