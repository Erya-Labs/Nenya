package dev.eryalabs.nenya.seam

import dev.eryalabs.nenya.money.FeeTerm
import dev.eryalabs.nenya.money.Msat
import kotlin.js.JsName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * §7.4's order id: 32 bytes of injected randomness, derived from nothing, redacted everywhere.
 *
 * > The **order id** MUST be 32 bytes from a cryptographically secure random source, generated
 * > by the buyer when proposing. It MUST NOT be derived from the coordinate, either pubkey, the
 * > price, or the time — a derived order id is a correlation handle for anyone who later learns
 * > the inputs.
 *
 * The negative half of that sentence is what these tests are mostly about, and it is awkward to
 * test because it is a rule about an *absence*. Two things make it testable: behaviourally, two
 * orders with identical terms, identical parties and an identical clock reading must still get
 * different ids; structurally, the minting function's parameter list must contain none of those
 * terms at all, which `SeamStructureTest` asserts by reflection.
 */
class OrderIdTest {

    private companion object {

        /**
         * A stand-in for the coordinate of §4.2. It is a `d`-tag-shaped string and nothing about
         * it is parsed — this task builds no listing codec (stop rule 4). It exists so the test
         * below has terms to hold constant.
         */
        const val COORDINATE: String = "30402:aaaa:lighthouse-animation"

        val PRICE: Msat = Msat.ofSat(120_000L)

        /** Long before nostr and long after this specification. §4.6: both are simply used. */
        const val FAR_PAST: Long = 0L

        /** 3000-01-01T00:00:00Z, in unix seconds. */
        const val FAR_FUTURE: Long = 32_503_680_000L

        /**
         * What a buyer does when proposing (§7.5): it has a coordinate, a price, a fee term and a
         * clock reading in hand, and §7.4 says none of them may reach the order id. This helper
         * holds all four so the assertion below is about the id and not about the setup.
         */
        fun propose(
            coordinate: String,
            price: Msat,
            clock: NenyaClock,
            randomness: Randomness,
        ): OrderId {
            val split = FeeTerm.of(250).splitOn(price)
            val proposedAt = clock.now().provided()
            // All four are read here and every one is deliberately discarded: §7.4 forbids the
            // coordinate, either pubkey, the price and the time from reaching the order id.
            check(coordinate.isNotEmpty())
            check(split.total >= price)
            check(proposedAt >= 0L)
            return OrderId.mint(randomness)
        }
    }

    @JsName("two_orders_with_identical_coordinate_price_and_clock_reading_get_different_ids")
    @Test
    fun `two orders with identical coordinate, price and clock reading get different ids`() {
        val clock = FakeClock(FAR_PAST)
        val randomness = RecordingRandomness()

        val first = propose(COORDINATE, PRICE, clock, randomness)
        val second = propose(COORDINATE, PRICE, clock, randomness)

        assertNotEquals(
            first,
            second,
            "§7.4: an order id derived from the coordinate, a pubkey, the price or the time is a " +
                "correlation handle for anyone who later learns those inputs. Two proposals with " +
                "every one of them identical must still differ.",
        )
        assertNotEquals(first.toHex(), second.toHex())
    }

    @JsName("minting_draws_exactly_thirty_two_bytes_in_one_call")
    @Test
    fun `minting draws exactly thirty-two bytes, in one call`() {
        val randomness = RecordingRandomness()

        OrderId.mint(randomness)

        assertEquals(
            listOf(OrderId.BYTE_LENGTH),
            randomness.requested,
            "§7.4 fixes the length at 32 bytes; a source asked for fewer and stretched, or asked " +
                "repeatedly and concatenated, is not what that sentence describes",
        )
    }

    @JsName("the_default_randomness_mints_nothing_at_all")
    @Test
    fun `the default randomness mints nothing at all`() {
        val refused = assertFailsWith<SeamException> { OrderId.mint() }

        assertEquals(
            SeamRejection.RANDOMNESS_UNAVAILABLE,
            refused.reason,
            "the fail-closed default must not silently fall back to an ambient source; a library " +
                "that reached for one would mint ids from something the client never chose",
        )
    }

    @JsName("a_source_returning_thirty_one_bytes_is_refused_never_padded_out_to_length")
    @Test
    fun `a source returning thirty-one bytes is refused, never padded out to length`() {
        val refused = assertFailsWith<SeamException> { OrderId.mint(WrongLengthRandomness(31)) }

        assertEquals(
            SeamRejection.RANDOMNESS_WRONG_LENGTH,
            refused.reason,
            "padding a short draw would mint an order id with a known byte in it, which is exactly " +
                "the correlation handle §7.4 forbids",
        )
    }

    @JsName("a_source_returning_thirty_three_bytes_is_refused_never_truncated_to_length")
    @Test
    fun `a source returning thirty-three bytes is refused, never truncated to length`() {
        val refused = assertFailsWith<SeamException> { OrderId.mint(WrongLengthRandomness(33)) }

        assertEquals(SeamRejection.RANDOMNESS_WRONG_LENGTH, refused.reason)
    }

    @JsName("a_source_returning_nothing_is_refused_and_not_treated_as_an_empty_id")
    @Test
    fun `a source returning nothing is refused, and not treated as an empty id`() {
        val refused = assertFailsWith<SeamException> { OrderId.mint(WrongLengthRandomness(0)) }

        assertEquals(SeamRejection.RANDOMNESS_WRONG_LENGTH, refused.reason)
    }

    @JsName("a_clock_far_in_the_past_and_one_far_in_the_future_are_both_simply_used")
    @Test
    fun `a clock far in the past and one far in the future are both simply used`() {
        for (instant in listOf(FAR_PAST, FAR_FUTURE)) {
            val clock = FakeClock(instant)

            assertEquals(
                instant,
                clock.now().provided(),
                "§4.6 makes the injected clock authoritative for every deadline. This library has " +
                    "no opinion about what it says, and the one tolerance §4.6 grants — MAY reject " +
                    "an implausible `created_at` — is a rule about a public event, not about the " +
                    "clock, and MUST NOT be applied to a gift wrap or a seal.",
            )
        }
    }

    @JsName("an_order_id_never_appears_in_its_own_string_representation")
    @Test
    fun `an order id never appears in its own string representation`() {
        val id = OrderId.mint(RecordingRandomness())
        val hex = id.toHex()

        assertFalse(
            id.toString().contains(hex),
            "§12 item 11 names order ids alongside key material, decryption keys and preimages: none " +
                "may appear in a log, a crash report, analytics, or the string representation of any " +
                "value this library exposes",
        )
        assertFalse(SeamAnswer.Provided(id).toString().contains(hex))
        assertEquals("OrderId(redacted)", id.toString())
        assertEquals(OrderId.HEX_LENGTH, hex.length)
    }

    @JsName("a_minted_id_round_trips_through_its_canonical_lowercase_hex")
    @Test
    fun `a minted id round-trips through its canonical lowercase hex`() {
        val id = OrderId.mint(RecordingRandomness())

        val hex = id.toHex()

        assertEquals(hex, hex.lowercase(), "§4.3: implementations MUST emit lowercase hex")
        assertEquals(id, OrderId.ofHex(hex))
        assertTrue(id.bytes().contentEquals(OrderId.ofHex(hex).bytes()))
    }

    @JsName("an_uppercase_order_id_is_accepted_and_normalised")
    @Test
    fun `an uppercase order id is accepted and normalised`() {
        val id = OrderId.mint(RecordingRandomness())
        val hex = id.toHex()

        val read = OrderId.ofHex(hex.uppercase())

        assertEquals(
            id,
            read,
            "§4.3's no-normalisation exception list is exhaustive — the BOLT-11 string and the " +
                "preimage — and explicitly names the order id as following the general " +
                "accept-and-normalise rule instead",
        )
        assertEquals(hex, read.toHex(), "and the canonical form it emits is lowercase")
    }

    @JsName("a_hex_order_id_of_the_wrong_length_is_refused_rather_than_padded")
    @Test
    fun `a hex order id of the wrong length is refused rather than padded`() {
        val hex = OrderId.mint(RecordingRandomness()).toHex()

        for (candidate in listOf(hex.dropLast(1), hex.dropLast(2), hex + "ab", "")) {
            val refused = assertFailsWith<SeamException> { OrderId.ofHex(candidate) }
            assertEquals(
                SeamRejection.WRONG_LENGTH,
                refused.reason,
                "§4.3: reject a value of the wrong length rather than padding or truncating it",
            )
        }
    }

    @JsName("a_non_hex_order_id_is_refused_as_not_hex_which_is_a_different_fix_from_wrong_length")
    @Test
    fun `a non-hex order id is refused as not hex, which is a different fix from wrong length`() {
        val hex = OrderId.mint(RecordingRandomness()).toHex()
        val corrupted = "z" + hex.drop(1)

        val refused = assertFailsWith<SeamException> { OrderId.ofHex(corrupted) }

        assertEquals(SeamRejection.NOT_HEX, refused.reason)
    }

    @JsName("a_rejection_message_never_carries_the_value_it_rejected")
    @Test
    fun `a rejection message never carries the value it rejected`() {
        val hex = OrderId.mint(RecordingRandomness()).toHex()

        val refused = assertFailsWith<SeamException> { OrderId.ofHex(hex.dropLast(1)) }

        // Scanned in windows rather than by a prefix. §12 item 11 admits no partial form, and a
        // message ending "...this one is 63 characters: ${hex.takeLast(8)}" survives a prefix
        // check — the same asymmetry that let a 48-character preimage prefix through.
        for (window in hex.windowed(8)) {
            assertFalse(
                refused.message.orEmpty().contains(window),
                "§12 item 11: an order id MUST NOT reach a crash report, and an exception message is " +
                    "a crash report waiting to happen. No 8-character window of it may appear.",
            )
        }
    }

    /**
     * The non-vacuity floor for the "different every time" claim. Three samples would pass over an
     * implementation that cycled through a handful of values; ten thousand from the pinned
     * generator would not.
     */
    @JsName("ten_thousand_minted_ids_are_all_distinct_and_all_canonical")
    @Test
    fun `ten thousand minted ids are all distinct and all canonical`() {
        val randomness = RecordingRandomness()
        val seen = HashSet<String>(20_000)

        repeat(10_000) {
            val hex = OrderId.mint(randomness).toHex()
            assertEquals(OrderId.HEX_LENGTH, hex.length)
            assertEquals(hex, hex.lowercase())
            assertTrue(seen.add(hex), "a repeated order id at draw $it")
        }

        assertEquals(10_000, seen.size)
        assertEquals(
            10_000,
            randomness.requested.size,
            "one draw per id, each of ${OrderId.BYTE_LENGTH} bytes",
        )
        assertTrue(randomness.requested.all { it == OrderId.BYTE_LENGTH })
    }

    /** A fixed source proves the id really is the bytes it was handed, and not a digest of them. */
    @JsName("the_minted_id_is_exactly_the_bytes_the_source_provided")
    @Test
    fun `the minted id is exactly the bytes the source provided`() {
        val bytes = SeamFixtures.bytes(OrderId.BYTE_LENGTH, stream = 3L)

        val id = OrderId.mint(FixedRandomness(bytes))

        assertEquals(SeamFixtures.lowerHex(bytes), id.toHex())
    }

    /** And it copies them, so the source cannot rewrite an id it already handed out. */
    @JsName("the_minted_id_does_not_alias_the_source_s_array")
    @Test
    fun `the minted id does not alias the source's array`() {
        val bytes = SeamFixtures.bytes(OrderId.BYTE_LENGTH, stream = 4L)
        val source = FixedRandomness(bytes)
        val id = OrderId.mint(source)
        val before = id.toHex()

        bytes.fill(0)

        assertEquals(before, id.toHex())
        assertFalse(id.bytes().all { it == 0.toByte() })
    }

    /**
     * The **outbound** direction of the same rule, which the test above cannot reach.
     *
     * `bytes()` documents itself as "a fresh copy … so a caller cannot mutate this id". Dropping
     * the `copyOf()` there leaves the whole suite green, because every other test mutates the
     * *source* array and so pins the copy in `init` only. Without this, a caller writing
     * `id.bytes()[0] = 0` silently rewrites an id that has already been put in an `["order", ...]`
     * tag, and two ids that were equal stop being equal.
     */
    @JsName("the_bytes_handed_out_are_a_copy_so_a_caller_cannot_rewrite_an_id_it_was_given")
    @Test
    fun `the bytes handed out are a copy, so a caller cannot rewrite an id it was given`() {
        val id = OrderId.mint(RecordingRandomness())
        val before = id.toHex()

        val handedOut = id.bytes()
        handedOut.fill(0)

        assertEquals(
            before,
            id.toHex(),
            "bytes() must hand out a fresh copy; an order id a caller can rewrite after reading is " +
                "not a value at all",
        )
        assertNotSame(id.bytes(), id.bytes(), "each call must produce its own array")
    }

    /**
     * `equals` must compare all 32 bytes, not a digest of them.
     *
     * Reducing it to a 32-bit `contentHashCode()` comparison survives every other test in this
     * file: the round-trip cases assert only that equal things are equal, and
     * `assertNotEquals(first, second)` holds for two pinned-seed random ids that happen not to
     * collide. Two *different* order ids comparing equal is cross-thread confusion an attacker can
     * grind for, so the control is a near miss rather than a random pair.
     */
    @JsName("two_ids_differing_by_a_single_nibble_are_not_equal_at_either_end_or_in_the_middle")
    @Test
    fun `two ids differing by a single nibble are not equal, at either end or in the middle`() {
        val id = OrderId.mint(RecordingRandomness())
        val hex = id.toHex()

        for (position in listOf(0, 1, OrderId.HEX_LENGTH / 2, OrderId.HEX_LENGTH - 2, OrderId.HEX_LENGTH - 1)) {
            val flipped = hex.substring(0, position) +
                (if (hex[position] == '0') '1' else '0') +
                hex.substring(position + 1)
            assertNotEquals(hex, flipped, "the fixture must actually differ at $position")

            val other = OrderId.ofHex(flipped)

            assertNotEquals(
                id,
                other,
                "ids differing only at nibble $position must not compare equal; a comparison that " +
                    "hashes first collides at 32 bits and confuses two order threads",
            )
            assertNotEquals(other, id, "and the comparison must be symmetric")
        }
    }

    /**
     * `equals` must compare **bytes**, not a hash of them — proved with an actual collision.
     *
     * The nibble-flip control above cannot catch this: flipping a nibble changes the hash too, so a
     * hash-comparing `equals` still answers "not equal" and stays green. What separates the two
     * implementations is a pair of *distinct* arrays sharing a `contentHashCode`, and for
     * `java.util.Arrays.hashCode(byte[])` those are constructible rather than hypothetical. The
     * fold is `result = 31 * result + b[i]`, so `b[i]` contributes `b[i] * 31^(n-1-i)`: adding 1 at
     * position `i` and subtracting 31 at position `i + 1` moves the digest by
     * `31^(n-1-i) - 31 * 31^(n-2-i)`, which is exactly zero.
     *
     * An attacker who can grind two order ids that compare equal can cross two order threads, so
     * this is the §7.4 correlation rule failing from the other end. `MessageDigest.isEqual` also
     * keeps the comparison constant-time, which a `contentHashCode` comparison does not.
     */
    @JsName("two_distinct_ids_that_share_a_32_bit_content_hash_are_still_not_equal")
    @Test
    fun `two distinct ids that share a 32-bit content hash are still not equal`() {
        val plain = ByteArray(OrderId.BYTE_LENGTH)
        val collided = ByteArray(OrderId.BYTE_LENGTH).also { it[10] = 1; it[11] = -31 }

        // The precondition, asserted rather than assumed — if the arithmetic above were wrong this
        // test would otherwise pass for the wrong reason and prove nothing.
        assertFalse(plain.contentEquals(collided), "the two arrays must genuinely differ")
        assertEquals(
            plain.contentHashCode(),
            collided.contentHashCode(),
            "the fixture must actually be a hash collision, or this test does not discriminate",
        )

        val first = OrderId.mint(FixedRandomness(plain))
        val second = OrderId.mint(FixedRandomness(collided))

        assertNotEquals(
            first,
            second,
            "these two order ids collide on a 32-bit hash and differ in their bytes. An equals that " +
                "compares digests calls them the same order, which crosses two order threads.",
        )
        assertNotEquals(second, first, "and symmetrically")
        assertNotEquals(first.toHex(), second.toHex())
    }

    /**
     * `hashCode` must agree with `equals`, because T7 will key an order thread by this type.
     *
     * Replacing it with `System.identityHashCode(this)` leaves the suite green today — nothing in
     * the package ever puts an `OrderId` in a hash-based collection — while making every
     * `HashMap<OrderId, …>` silently miss every lookup.
     */
    @JsName("equal_ids_hash_equally_so_an_order_id_can_key_a_hash_based_collection")
    @Test
    fun `equal ids hash equally, so an order id can key a hash-based collection`() {
        val id = OrderId.mint(RecordingRandomness())
        val same = OrderId.ofHex(id.toHex())

        assertEquals(id, same)
        assertEquals(id.hashCode(), same.hashCode(), "equal values must hash equally")
        assertEquals(1, hashSetOf(id, same).size, "a HashSet must see one id, not two")
        assertEquals(
            "kept",
            hashMapOf(id to "kept")[same],
            "a HashMap keyed by an order id must find it again — which is how T7 will hold a thread",
        )
    }

    /**
     * The 10 000-sample distinctness floor above dedupes on `toHex` strings, so it never calls
     * `equals` at all. This runs the same claim through the equality relation itself.
     */
    @JsName("ten_thousand_minted_ids_are_pairwise_distinct_under_equals_not_merely_under_their_hex")
    @Test
    fun `ten thousand minted ids are pairwise distinct under equals, not merely under their hex`() {
        val randomness = RecordingRandomness()
        val seen = HashSet<OrderId>(20_000)

        repeat(10_000) { assertTrue(seen.add(OrderId.mint(randomness)), "a repeated order id at draw $it") }

        assertEquals(
            10_000,
            seen.size,
            "a HashSet exercises equals and hashCode together; a degraded comparison collapses " +
                "distinct ids here even though every hex string differs",
        )
    }
}
