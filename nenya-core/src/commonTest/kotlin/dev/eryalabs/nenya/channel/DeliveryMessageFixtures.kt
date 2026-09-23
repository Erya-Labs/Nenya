package dev.eryalabs.nenya.channel

import dev.eryalabs.nenya.JdkRandom
import dev.eryalabs.nenya.NenyaProtocol
import dev.eryalabs.nenya.oracleSha256
import dev.eryalabs.nenya.tag.NenyaKind
import dev.eryalabs.nenya.tag.TagFixtures
import dev.eryalabs.nenya.utf8Bytes
import kotlin.test.fail

/**
 * The generator behind every §10.1 and §10.3 fixture.
 *
 * This is not a scratch file. The queue's Definition of done forbids an encoded value appearing in a
 * test as something somebody typed out, and nothing here is one:
 *
 * - the public keys are real BIP-340 keys read out of the vendored, externally-authored
 *   `bip340-vectors.csv` through [TagFixtures.distinctPubkeys];
 * - every `x` and `ox` is the SHA-256 of a seeded blob, computed by the platform's own SHA-256
 *   ([oracleSha256]) rather than written out;
 * - every order id is a SHA-256 digest of a per-index label;
 * - every `decryption-key` and `decryption-nonce` is drawn from [JdkRandom] and then **encoded here**
 *   — by [hex] or by [base64], both of which are code in this repository a reviewer can re-run —
 *   rather than transcribed;
 * - every event id under test is computed inside T8.
 *
 * A reviewer can change [SEED], re-run the suite, and every property must still hold.
 *
 * ### Why §10's own example values are mostly not used
 *
 * §10.1 and §10.3 print `<sha256 hex of the ENCRYPTED bytes exactly as served>`, `<64 hex chars>`,
 * `<24 hex chars>`, `<same value as the commitment>` and `<buyer-pubkey-hex>`. Every one is a
 * placeholder rather than a value: none is hexadecimal and none survives §4.3, so none may be fed to
 * the codec. `Section10` therefore asserts over the examples' tag **names** and the values come from
 * here. The two exceptions are `["m", "video/mp4"]` and `["size", "18342912"]`, which are real
 * values in the document and are neither of them an encoding anybody had to reason about.
 *
 * ### What the corpus carries and why
 *
 * The non-vacuity floor is §4.3's round-trip rule proved through the **event id**, plus the values a
 * codec could quietly replace with a constant. So the corpus draws the size, the MIME type, both
 * key encodings and both nonce encodings independently, and `DeliveryMessagePropertyTest` asserts
 * each decoded value is the one drawn — and asserts the encoding counts are non-zero, because a
 * corpus that happened to draw hex ten thousand times would leave §10.2's base64 clause untested
 * while looking thorough.
 */
internal object DeliveryMessageFixtures {

    /**
     * Pinned so a failure is reproducible. `java.util.Random`'s algorithm ([JdkRandom], identical on
     * the JVM and JavaScript), for the reason `WireFixtures` gives.
     */
    const val SEED: Long = 20260923L

    /** T9's fixed, non-round `created_at`. A codec that emitted a constant would still be visible. */
    const val CREATED_AT: Long = ChannelFixtures.CREATED_AT

    /** §4.5's version token this implementation implements, as it appears in a tag. */
    val VERSION: String = NenyaProtocol.VERSION.toString()

    /** §7.4's `type` number for a delivery commitment, read off T9's constants. */
    val COMMITMENT_TYPE: String = NenyaKind.OrderMessageType.DELIVERY_COMMITMENT.toString()

    /** §10.1's own example MIME type — a real value in the document, not a placeholder. */
    const val MIME_TYPE: String = "video/mp4"

    /** §10.1's own example `size`, likewise. */
    const val SIZE: String = "18342912"

    /** §10.2's only algorithm token in v1, read off the constant rather than typed twice. */
    val ALGORITHM: String = DeliveryTags.AES_GCM

    /** The four operands §10.3 compares, in §10.3's own order, as the release names them. */
    val OPERAND_NAMES: List<String> = listOf(
        DeliveryTags.FILE_TYPE,
        DeliveryTags.SERVED_HASH,
        DeliveryTags.PLAINTEXT_HASH,
        DeliveryTags.SIZE,
    )

    /** Tag names §5.3 does not name and §10 does not read, so §4.3's unknown-tag rule applies. */
    private val UNKNOWN_NAMES: List<String> = listOf("client", "zap", "relays", "x-nenya-experiment")

    /** Keeps the generated provider and buyer distinct at every index. */
    private const val BUYER_OFFSET: Int = 1

    /** A blob host that cannot resolve: nothing in this library fetches, and `.invalid` is reserved. */
    private const val URL_PREFIX: String = "https://blob.example.invalid/"

    /**
     * A real BIP-340 public key in §4.3's canonical lowercase, from the vendored file.
     *
     * [TagFixtures.distinctPubkeys] and not the raw column: the file reuses one key across eight of
     * its rows, so indexing the raw column would hand this generator the same key for a provider at
     * one index and a buyer at the next.
     */
    fun pubkey(index: Int): String {
        val keys = TagFixtures.distinctPubkeys
        return keys[((index % keys.size) + keys.size) % keys.size].lowercase()
    }

    /** §7.4's order id for [index], a digest and never a typed string. */
    fun orderHex(index: Int): String = ChannelFixtures.orderHexFor(index)

    /** The SHA-256 of a seeded blob, in lowercase hex — §10.1's `x` or `ox`. */
    fun digestHex(label: String): String = TagFixtures.lowerHex(oracleSha256(label.utf8Bytes()))

    /** §10.1's `x` for [index]: the digest of the *encrypted* blob. */
    fun servedHash(index: Int): String = digestHex("nenya-served-blob-$index")

    /** §10.1's `ox` for [index]: the digest of the *plaintext*, a different blob. */
    fun plaintextHash(index: Int): String = digestHex("nenya-plaintext-blob-$index")

    /** §10.1's blob URL for [index]. Nothing here fetches it (§12 item 10, STOP RULE 13). */
    fun url(index: Int): String = "$URL_PREFIX${digestHex("nenya-blob-url-$index").take(URL_SUFFIX)}"

    /** [bytes] as lowercase hex — §10.2's mandatory emission form. */
    fun hex(bytes: ByteArray): String = TagFixtures.lowerHex(bytes)

    /**
     * [bytes] as standard (RFC 4648 §4) padded base64 — the form §10.2 says implementations SHOULD
     * additionally accept, "because NIP-17 does not specify the encoding and other clients may emit
     * it".
     *
     * Computed here rather than transcribed, which is the whole point: the fixture and the library
     * are two independent implementations of the same table, so a value that decodes to 32 bytes
     * here and not there turns the suite red instead of agreeing with itself.
     */
    fun base64(bytes: ByteArray): String {
        val out = StringBuilder()
        var index = 0
        while (index < bytes.size) {
            val remaining = bytes.size - index
            val first = bytes[index].toInt() and 0xff
            val second = if (remaining > 1) bytes[index + 1].toInt() and 0xff else 0
            val third = if (remaining > 2) bytes[index + 2].toInt() and 0xff else 0
            val group = (first shl 16) or (second shl 8) or third
            out.append(BASE64_ALPHABET[(group ushr 18) and 0x3f])
            out.append(BASE64_ALPHABET[(group ushr 12) and 0x3f])
            out.append(if (remaining > 1) BASE64_ALPHABET[(group ushr 6) and 0x3f] else '=')
            out.append(if (remaining > 2) BASE64_ALPHABET[group and 0x3f] else '=')
            index += 3
        }
        return out.toString()
    }

    /**
     * [count] bytes derived from [label] and [SEED], so every fixture is reproducible **and
     * distinct**.
     *
     * A SHA-256 of the label rather than a seeded RNG, and the difference is not stylistic: an
     * earlier draft seeded `JdkRandom(SEED + label.length)`, which depends on the label's *length*
     * and not its content — so `key-0` through `key-9` were byte-identical and the ten-thousand-pair
     * corpus carried four distinct keys. It still passed every assertion, because the property under
     * test is a length rule. A digest of the whole label cannot have that defect, and [SEED] is
     * inside it so that changing the seed still changes every fixture.
     *
     * [count] is at most the digest's own 32 bytes, which covers §10.2's key and nonce; a larger
     * request fails loudly rather than silently repeating the digest.
     */
    fun bytes(label: String, count: Int): ByteArray {
        val digest = oracleSha256("nenya-$SEED-$label".utf8Bytes())
        if (count > digest.size) fail("$count bytes requested from a ${digest.size}-byte digest")
        return digest.copyOf(count)
    }

    /** §10.2's 256-bit decryption key for [index], as raw bytes. */
    fun keyBytes(index: Int): ByteArray = bytes("key-$index", DeliveryTags.KEY_BYTES)

    /** §10.2's 96-bit nonce for [index], as raw bytes. */
    fun nonceBytes(index: Int): ByteArray = bytes("nonce-$index", DeliveryTags.NONCE_BYTES)

    /**
     * §10.1's worked commitment, in the tag order the example prints, with every value defaulted to
     * the example's own shape where the example prints a real one.
     *
     * Mutable, so a control can break exactly one thing about it.
     */
    fun commitmentTags(
        index: Int = 0,
        order: String? = orderHex(index),
        type: String? = COMMITMENT_TYPE,
        url: String? = url(index),
        x: String? = servedHash(index),
        ox: String? = plaintextHash(index),
        mimeType: String? = MIME_TYPE,
        size: String? = SIZE,
        algorithm: String? = ALGORITHM,
        version: String? = VERSION,
    ): MutableList<List<String>> {
        val tags = mutableListOf<List<String>>()
        if (order != null) tags += listOf(ChannelTags.ORDER, order)
        if (type != null) tags += listOf(ChannelTags.TYPE, type)
        if (url != null) tags += listOf(DeliveryTags.URL, url)
        if (x != null) tags += listOf(DeliveryTags.SERVED_HASH, x)
        if (ox != null) tags += listOf(DeliveryTags.PLAINTEXT_HASH, ox)
        if (mimeType != null) tags += listOf(DeliveryTags.MIME_TYPE, mimeType)
        if (size != null) tags += listOf(DeliveryTags.SIZE, size)
        if (algorithm != null) tags += listOf(DeliveryTags.ENCRYPTION_ALGORITHM, algorithm)
        if (version != null) tags += listOf(ChannelVocabulary.VERSION, version)
        tags += listOf(ChannelVocabulary.COUNTERPARTY, pubkey(index + BUYER_OFFSET))
        return tags
    }

    /**
     * §10.3's worked release, in the tag order the example prints, with `x`, `ox`, `size` and
     * `file-type` matching [commitmentTags] at the same [index] — which is §10.3's identity case.
     */
    fun releaseTags(
        index: Int = 0,
        order: String? = orderHex(index),
        fileType: String? = MIME_TYPE,
        algorithm: String? = ALGORITHM,
        key: String? = hex(keyBytes(index)),
        nonce: String? = hex(nonceBytes(index)),
        x: String? = servedHash(index),
        ox: String? = plaintextHash(index),
        size: String? = SIZE,
        version: String? = VERSION,
    ): MutableList<List<String>> {
        val tags = mutableListOf<List<String>>()
        tags += listOf(ChannelVocabulary.COUNTERPARTY, pubkey(index + BUYER_OFFSET))
        if (order != null) tags += listOf(ChannelTags.ORDER, order)
        if (fileType != null) tags += listOf(DeliveryTags.FILE_TYPE, fileType)
        if (algorithm != null) tags += listOf(DeliveryTags.ENCRYPTION_ALGORITHM, algorithm)
        if (key != null) tags += listOf(DeliveryTags.DECRYPTION_KEY, key)
        if (nonce != null) tags += listOf(DeliveryTags.DECRYPTION_NONCE, nonce)
        if (x != null) tags += listOf(DeliveryTags.SERVED_HASH, x)
        if (ox != null) tags += listOf(DeliveryTags.PLAINTEXT_HASH, ox)
        if (size != null) tags += listOf(DeliveryTags.SIZE, size)
        if (version != null) tags += listOf(ChannelVocabulary.VERSION, version)
        return tags
    }

    /** [tags] as a `kind:16` rumor attributed to the key that authored it — §7.2's positive case. */
    fun bound(tags: List<List<String>>, kind: Int, index: Int = 0): AttributedRumor.Bound {
        val author = pubkey(index)
        val rumor = AttributedRumor.attribute(
            author,
            ChannelFixtures.checked(author, kind, tags),
        )
        return rumor as? AttributedRumor.Bound
            ?: fail("a kind:$kind must decode to a bound rumor, not to $rumor")
    }

    /** [tags] decoded as §10.1's commitment, through T8's and T13's doors. */
    fun commitment(tags: List<List<String>>, index: Int = 0): DeliveryCommitmentMessage =
        DeliveryCommitmentMessage.decode(bound(tags, NenyaKind.ORDER_MESSAGE, index))

    /** [tags] decoded as §10.3's release. */
    fun release(tags: List<List<String>>, index: Int = 0): DeliverableReleaseMessage =
        DeliverableReleaseMessage.decode(bound(tags, NenyaKind.FILE_MESSAGE, index))

    /** [tags] with every occurrence of [name] removed. */
    fun without(tags: List<List<String>>, name: String): List<List<String>> =
        tags.filter { it[0] != name }

    /** [tags] with every occurrence of [tag]'s name replaced by [tag]. */
    fun replacing(tags: List<List<String>>, tag: List<String>): List<List<String>> =
        tags.map { if (it[0] == tag[0]) tag else it }

    /** [tags] with [tag] appended. */
    fun with(tags: List<List<String>>, tag: List<String>): List<List<String>> = tags + listOf(tag)

    /** One generated commitment/release pair, and every value drawn for it. */
    data class Fixture(
        val index: Int,
        val commitmentTags: List<List<String>>,
        val releaseTags: List<List<String>>,
        val mimeType: String,
        val size: Long,
        val sizeText: String,
        val keyIsHex: Boolean,
        val nonceIsHex: Boolean,
    )

    /**
     * [count] **legal** commitment/release pairs from one seeded run.
     *
     * Every fixture decodes and every pair is §10.3-identical, because the property being proved is
     * about round-tripping and about the delegated values rather than about rejection: a corpus that
     * was refused would prove nothing, which is why `DeliveryMessagePropertyTest` asserts the whole
     * corpus decodes before it asserts anything else.
     */
    fun pairs(count: Int): List<Fixture> {
        val random = JdkRandom(SEED)
        return List(count) { index -> pair(random, index) }
    }

    private fun pair(random: JdkRandom, index: Int): Fixture {
        val size = random.nextInt(MAX_DRAWN_SIZE).toLong()
        val sizeText = size.toString()
        val mimeType = MIME_TYPES[random.nextInt(MIME_TYPES.size)]
        val keyIsHex = random.nextBoolean()
        val nonceIsHex = random.nextBoolean()
        val key = if (keyIsHex) hex(keyBytes(index)) else base64(keyBytes(index))
        val nonce = if (nonceIsHex) hex(nonceBytes(index)) else base64(nonceBytes(index))

        val commitment = commitmentTags(index = index, mimeType = mimeType, size = sizeText)
        val release = releaseTags(
            index = index,
            fileType = mimeType,
            key = key,
            nonce = nonce,
            size = sizeText,
        )
        // §7.4 makes `subject` display metadata that MAY appear on any rumor, and §4.3 requires it
        // and every unknown tag be preserved verbatim — which is what the id property is about.
        if (random.nextBoolean()) commitment += listOf(ChannelVocabulary.SUBJECT, "order $index")
        if (random.nextBoolean()) release += listOf(ChannelVocabulary.SUBJECT, "order $index")
        addUnknownTags(random, index, commitment)
        addUnknownTags(random, index, release)
        commitment.shuffleAll(random)
        release.shuffleAll(random)

        return Fixture(
            index = index,
            commitmentTags = commitment.toList(),
            releaseTags = release.toList(),
            mimeType = mimeType,
            size = size,
            sizeText = sizeText,
            keyIsHex = keyIsHex,
            nonceIsHex = nonceIsHex,
        )
    }

    private fun addUnknownTags(random: JdkRandom, index: Int, tags: MutableList<List<String>>) {
        repeat(1 + random.nextInt(2)) {
            val name = UNKNOWN_NAMES[random.nextInt(UNKNOWN_NAMES.size)]
            tags += listOf(name, "$index-$it")
        }
    }

    /**
     * Reorders the whole list, so the corpus does not test one tag order ten thousand times.
     *
     * §10 fixes no tag order and §4.1 forbids *changing* one, and the id property is what would
     * catch a codec that sorted or normalised on the way out.
     */
    private fun MutableList<List<String>>.shuffleAll(random: JdkRandom) {
        for (position in size - 1 downTo 1) {
            val other = random.nextInt(position + 1)
            val swap = this[position]
            this[position] = this[other]
            this[other] = swap
        }
    }

    /** RFC 4648 §4's alphabet, in its own order. */
    private const val BASE64_ALPHABET: String =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** Distinct MIME types, so a codec that returned a constant would be visible. */
    private val MIME_TYPES: List<String> =
        listOf(MIME_TYPE, "image/png", "audio/ogg", "application/pdf", "model/gltf-binary")

    /** Comfortably inside §10.4's default 64 MiB bound and large enough to vary. */
    private const val MAX_DRAWN_SIZE: Int = 60_000_000

    /** How much of a digest a generated blob URL carries. Long enough to be unique per index. */
    private const val URL_SUFFIX: Int = 12
}
