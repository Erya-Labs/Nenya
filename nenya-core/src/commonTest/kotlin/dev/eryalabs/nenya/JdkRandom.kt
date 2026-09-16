package dev.eryalabs.nenya

/**
 * `java.util.Random`, written out in common Kotlin, so a seeded fixture produces the **same**
 * values on the JVM and on JavaScript.
 *
 * Every generator in this suite was written against `java.util.Random` pinned to a seed, and
 * several comments say why: its algorithm is specified by the JDK, so a run is reproducible on any
 * JVM. `kotlin.random.Random(seed)` is not a substitute — it is a different algorithm, so every
 * generated corpus would change on the swap, and its KDoc reserves the right to change the
 * algorithm between Kotlin versions. This class is the JDK's specified algorithm instead: the
 * 48-bit linear congruential generator of `java.util.Random`'s documentation (Knuth, TAOCP
 * vol. 2, §3.2.1), with `nextInt`, `nextInt(bound)`, `nextLong`, `nextBoolean` and `nextBytes`
 * exactly as the JDK documents them. Nothing else is implemented because nothing else is used.
 *
 * It is a test fixture, never a source of anything secret. `CommonTestOraclesTest` (jvmTest)
 * asserts it agrees with `java.util.Random` call for call across many seeds and every method,
 * so a slip here fails loudly rather than quietly changing a corpus.
 */
internal class JdkRandom(seed: Long) {

    private var state: Long = (seed xor MULTIPLIER) and MASK

    private fun next(bits: Int): Int {
        state = (state * MULTIPLIER + ADDEND) and MASK
        return (state ushr (48 - bits)).toInt()
    }

    fun nextInt(): Int = next(32)

    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        var r = next(31)
        val m = bound - 1
        if ((bound and m) == 0) {
            // A power of two: the high bits, as the JDK takes them.
            return ((bound.toLong() * r.toLong()) shr 31).toInt()
        }
        var u = r
        while (true) {
            r = u % bound
            // Int overflow here is the JDK's rejection test, and Kotlin wraps Int on every target.
            if (u - r + m >= 0) break
            u = next(31)
        }
        return r
    }

    fun nextLong(): Long = (next(32).toLong() shl 32) + next(32).toLong()

    fun nextBoolean(): Boolean = next(1) != 0

    fun nextBytes(bytes: ByteArray) {
        var i = 0
        val len = bytes.size
        while (i < len) {
            var rnd = nextInt()
            var n = minOf(len - i, 4)
            while (n-- > 0) {
                bytes[i++] = rnd.toByte()
                rnd = rnd shr 8
            }
        }
    }

    private companion object {
        const val MULTIPLIER: Long = 0x5DEECE66DL
        const val ADDEND: Long = 0xBL
        const val MASK: Long = (1L shl 48) - 1
    }
}
