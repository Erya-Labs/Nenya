package dev.eryalabs.nenya.seam

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every "not yet" this library tells a client about itself, pinned — because a narrowing that has
 * stopped being true is a lie the compiler cannot see.
 *
 * ### Why a sweep and not a review
 *
 * A KDoc saying "there is no parser yet" is load-bearing documentation the day it is written: it
 * tells an integrating client what this library does **not** do, which is exactly what §17 says an
 * implementation must be honest about. It is also the one kind of statement that rots silently. T22
 * built the event codec and T31 built the BOLT-11 reader, and both of those sentences survived the
 * tasks that falsified them — for months, in the two KDocs a client implementing a seam is most
 * likely to read. Nothing failed, because nothing was watching.
 *
 * So the set of them is pinned here, one entry per comment line, each with the reason it is **still
 * true**. Adding a narrowing means adding its reason; closing one means deleting the line and the
 * entry together. The pin is deliberately brittle: rewording one of these lines turns this red and
 * makes somebody re-read whether it still holds.
 *
 * ### What counts as a comment line
 *
 * A line whose first non-blank characters open or continue a block comment, or are a line comment's
 * two slashes, and which carries `yet` as a whole word. ([isComment] spells the three prefixes out;
 * they are not repeated here, because a block comment's opener written inside a KDoc opens a
 * **nested** comment in Kotlin and swallows the rest of the file.)
 * That is a **selector**, and the opposite of [AmbientEffectsTest]'s `codeLines`, which strips
 * comments so the ambient-effect patterns never match their own documentation. The queue's T9 says
 * not to write a second comment *stripper*; this is not one, and it deliberately does not reuse
 * that one, because a sweep whose subject is comments cannot be built out of a function whose job
 * is to remove them.
 *
 * The cost of the simple rule is stated rather than hidden: a `yet` inside a string literal on a
 * line that also starts with `*` would be counted as a comment, and one on a line of code would be
 * missed. Neither exists under `src/commonMain/kotlin` today, and the failure mode of the second is
 * a narrowing written somewhere no reader would look for one.
 *
 * ### Where this runs
 *
 * It reads the source tree from disk through `java.io.File`, so it is JVM-only for the same reason
 * [AmbientEffectsTest]'s sweeps are.
 */
class StaleNarrowingTest {

    @Test
    fun `every not-yet in common production code is one of the pinned narrowings`() {
        val swept = sweep(File(COMMON_ROOT))

        assertEquals(
            STILL_TRUE.keys,
            swept.lines.toSet(),
            "a `yet` in common production code is a promise to a client about what this library " +
                "does not do. A new one needs an entry here saying why it is still true; a missing " +
                "one is a narrowing that was closed and whose KDoc still claims it.",
        )
    }

    /**
     * The three passages T35 was queued to correct, asserted **absent** rather than merely not
     * pinned.
     *
     * Two of them are `yet` comments the sweep above would catch on its own. The third is not a
     * `yet` at all — it is `GiftWrap`'s argument that a refused decryption and an unavailable one
     * are "deliberately not distinguished", which was the §17 over-claim in the direction nobody
     * watches. Reinstating any of the three turns this red, which the set-equality above could not
     * do for the third.
     */
    @Test
    fun `the passages T35 corrected are gone from common production code`() {
        val text = sources(File(COMMON_ROOT)).joinToString("\n") { it.readText() }

        for ((phrase, why) in CORRECTED) {
            assertTrue(phrase !in text, "`$phrase` is back in $COMMON_ROOT. $why")
        }
        assertTrue(
            text.length > MIN_SOURCE_CHARS,
            "the sweep read ${text.length} characters, which is not the tree — and 'the phrase is " +
                "absent' is true of an empty string",
        )
    }

    /**
     * The non-vacuity floor: the sweep read real files, and one of them is the file this task is
     * about.
     *
     * Without it, "every `yet` is pinned" is equally true of a sweep pointed at an empty directory —
     * and the empty-directory failure is exactly what a source-layout move produces.
     */
    @Test
    fun `the sweep read the tree it is about, including the seam package`() {
        val swept = sweep(File(COMMON_ROOT))

        assertTrue(
            swept.files.size >= MIN_FILES,
            "the sweep read only ${swept.files.size} file(s), which is not src/commonMain/kotlin",
        )
        assertTrue(
            "seam/Seams.kt" in swept.files,
            "the file whose two stale narrowings T35 rewrote was not among the ${swept.files.size} " +
                "files read; found ${swept.files.sorted()}",
        )
        assertTrue(swept.lines.isNotEmpty(), "and it found narrowings, or the pin above is a claim about nothing")
    }

    /**
     * The negative control: a probe comment added to a **copy** of the tree is caught.
     *
     * A copy rather than the tree itself, because a sweep proved against a file it also has to leave
     * alone cannot be proved at all — and because the probe must be reachable by the same walk, the
     * same comment rule and the same whole-word match as the real lines, not by a second code path
     * written to find it. The copy lives under `build/` and is removed in a `finally`, so a failing
     * assertion cannot leave it behind (STOP RULE 8).
     */
    @Test
    fun `a probe narrowing added to a copy of the tree is caught by the same sweep`() {
        val real = sweep(File(COMMON_ROOT))
        val temporary = Files.createTempDirectory(File("build").toPath(), "yet-sweep").toFile()
        try {
            File(COMMON_ROOT).copyRecursively(temporary, overwrite = true)
            File(temporary, PACKAGE_PATH + PROBE_FILE).writeText(PROBE_SOURCE)

            val probed = sweep(temporary)

            assertEquals(
                real.lines.toSet() + "$PROBE_FILE | $PROBE_COMMENT",
                probed.lines.toSet(),
                "the sweep must find a narrowing added anywhere under the root, and must find " +
                    "nothing else that copying the tree did not change",
            )
            assertTrue(
                PROBE_FILE in probed.files,
                "and must have read the probe's file rather than inferred it",
            )
        } finally {
            temporary.deleteRecursively()
        }
    }

    /** The floor under the control above: the probe really is invisible to the real tree. */
    @Test
    fun `the probe is not something the real tree already says`() {
        assertTrue(
            "$PROBE_FILE | $PROBE_COMMENT" !in STILL_TRUE.keys,
            "the probe must be a line the pin does not already hold, or the control proves nothing",
        )
        assertTrue(
            mentionsYet(PROBE_COMMENT) && isComment(PROBE_COMMENT),
            "and it must satisfy both halves of the rule the sweep applies",
        )
        assertTrue(!mentionsYet("* a yeti is not a narrowing"), "`yet` is matched as a whole word")
        assertTrue(!isComment("""val yet: String = "yet" // and this is not a comment line"""))
    }

    private class Swept(val files: Set<String>, val lines: List<String>)

    private companion object {

        /**
         * The production root this sweep is about. Only `src/commonMain/kotlin`: `jvmMain` and
         * `jsMain` hold the platform `actual`s and no published KDoc a client reads, and the
         * narrowings this is about are statements on the common API surface.
         */
        const val COMMON_ROOT: String = "src/commonMain/kotlin"

        /** Every production source sits under this; [relativeName] fails loudly on one that does not. */
        const val PACKAGE_PATH: String = "dev/eryalabs/nenya/"

        /** `yet`, matched as a whole word so `yeti` and `yetis` are not narrowings. */
        const val WORD: String = "yet"

        /** Every `.kt` file under [COMMON_ROOT] at this commit was well past this. */
        const val MIN_FILES: Int = 30

        /** Likewise the source text the phrase sweep reads. */
        const val MIN_SOURCE_CHARS: Int = 100_000

        const val PROBE_FILE: String = "Probe.kt"

        const val PROBE_COMMENT: String =
            "* there is no probe in this library yet, which is the point of this fixture."

        val PROBE_SOURCE: String = """
            |package dev.eryalabs.nenya
            |
            |/**
            | $PROBE_COMMENT
            | */
            |internal const val PROBE: Int = 0
        """.trimMargin()

        /**
         * The three passages T35 corrected, and why each must stay gone.
         *
         * Short fragments rather than whole sentences, so a reinstatement that rewrapped the line is
         * still caught.
         */
        val CORRECTED: Map<String, String> = mapOf(
            "no event codec" to
                "T22 built the event codec and T31 the reader; `RelayTransport` deals in serialised " +
                    "events because the codec is the caller's to apply, not because there is none.",
            "there is no parser yet" to
                "T31 built Appendix C's BOLT-11 reader; `Wallet.payInvoice` hands the wallet the " +
                    "invoice verbatim so the string this library checked is the one the wallet pays.",
            "deliberately not distinguished here" to
                "T35 split a decryption the signer performed and refused from one it never " +
                    "attempted. §17 requires a check that ran and failed be reported as such, and " +
                    "the `oracle` argument that justified folding them did not hold: both answers " +
                    "are produced by the reader's own signer, for the reader.",
        )

        /**
         * Every narrowing common production code still states, and why each is still true.
         *
         * Keyed by `<path under the root> | <the comment line, trimmed>`. The path is part of the
         * key because a narrowing that moved between files is one whose reason has to be re-read.
         */
        val STILL_TRUE: Map<String, String> = mapOf(
            at(
                "channel/AttributedRumor.kt",
                "* §7.4 says MUST NOT carry one \"because no order exists yet\".",
            ) to "§7.4's own words, quoted: a private bid predates the order, so it carries no order id.",
            at(
                "channel/ChannelRejection.kt",
                "* cannot exist yet, not a peer with one tag too many.",
            ) to "The same §7.4 rule, as the reason a private bid carrying an order id is refused.",
            at(
                "channel/DeliveryMessage.kt",
                "* tag codec in this library yet, so the caller supplies the four values it read out of the tags\",",
            ) to "Still true: §10's delivery tags have no codec, and the queue has not yet asked for one.",
            at(
                "channel/RumorVocabulary.kt",
                "* `false` for [PRIVATE_BID] alone — §7.4's single exception, \"because no order exists yet\".",
            ) to "§7.4's own words again, at the vocabulary that declares which kinds carry an order id.",
            at(
                "listing/Listing.kt",
                "/** The listing carries no `expiration`, or the injected clock says it has not arrived yet. */",
            ) to "About a deadline in the future, not about this library: nothing here can go stale.",
            at(
                "listing/ListingStatus.kt",
                "* Nenya has not written yet is exactly what §5.2's unknown-treatment clause is for.",
            ) to "Still true: §5.2's unknown-status rule exists for statuses no version of this library emits.",
            at(
                "order/OrderMachine.kt",
                "* message decides nothing\" from \"the deadline has not passed yet\".",
            ) to "About a deadline in the future; a statement of §11.2's vocabulary, not of a missing feature.",
            at(
                "order/OrderMachine.kt",
                "/** §11.2 — not every required receipt has verified yet. The order stays `awaiting_payment`. */",
            ) to "About one order's progress, not about this library's capabilities.",
            at(
                "order/OrderMachine.kt",
                "* provenance — the hash parsed out of each invoice — which nothing here does yet. This check",
            ) to
                "Still true: §9.2 check 3's extraction is the settlement package's and the order " +
                    "machine does not perform it; decision B keeps the order out of `paid` for it.",
            at(
                "order/OrderMachine.kt",
                "* yet\"; this one says \"never, on the readings this library has\".",
            ) to "Contrasts two refusals; the `yet` is the other one's wording, not a narrowing here.",
            at(
                "order/OrderMachine.kt",
                "* checks this library cannot yet perform, so a client can say \"the invoice amount was",
            ) to "Still true: §9.2 checks this library does not perform remain reported as not performed (§17).",
            at(
                "order/OrderMachine.kt",
                "*   performed\" to a caller who had simply not sent the fee receipt yet — true, useless, and it",
            ) to "About a caller's missing message, not about a missing feature.",
            at(
                "order/OrderState.kt",
                "* looks like a success. There is no listing codec yet; when there is, it is a separate",
            ) to "Still true: §5's listing codec does not exist, and the queue has not yet asked for one.",
            at(
                "order/OrderTerms.kt",
                "* yet, so the caller supplies what it read out of `[\"amount_msat\", ...]`, `[\"fee\", ...]`,",
            ) to "Still true: §8's order-terms tag codec does not exist; the caller supplies the values.",
            at(
                "settlement/PaymentRequest.kt",
                "* request, which carries no proof because nothing has been paid yet, and §9.2 prints",
            ) to "About the state of one payment, not about this library.",
            at(
                "settlement/Settlement.kt",
                "* The same two checks over a reference and an acceptance reading that are not yet a",
            ) to "About values the caller has not yet assembled into a stored request, not about a missing feature.",
            at(
                "settlement/SettlementRejection.kt",
                "/** §8.6's request form: name, medium, reference. No proof — nothing has been paid yet. */",
            ) to "About the state of one payment, not about this library.",
        )

        fun at(file: String, comment: String): String = "$file | $comment"

        /** A line whose first non-blank characters open or continue a comment. */
        fun isComment(line: String): Boolean {
            val trimmed = line.trimStart()
            return trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")
        }

        /**
         * [WORD] as a whole word: `yet.`, `yet"` and `yet;` all count, and `yeti` does not.
         *
         * Written out rather than expressed as a `Regex` because a `Regex` here would be the third
         * place in this repository where a pattern has to be re-read for JavaScript's `u` mode, and
         * it buys nothing over a scan two operators long.
         */
        fun mentionsYet(line: String): Boolean {
            var from = 0
            while (true) {
                val index = line.indexOf(WORD, from)
                if (index < 0) return false
                val before = line.getOrNull(index - 1)
                val after = line.getOrNull(index + WORD.length)
                if (before?.isLetter() != true && after?.isLetter() != true) return true
                from = index + 1
            }
        }

        fun sources(root: File): List<File> {
            if (!root.isDirectory) {
                fail(
                    "${root.absolutePath} is not a directory. These tests run with the module " +
                        "directory as the working directory; this one is " +
                        "${File(".").absoluteFile.normalize()}. A sweep that silently read nothing " +
                        "would report every narrowing as closed.",
                )
            }
            return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()
        }

        /**
         * The path a key names: relative to [root], with the package directories stripped.
         *
         * A file outside `dev/eryalabs/nenya/` **fails** rather than being named some other way.
         * Every production source is in that package today, and one that was not would be a source
         * layout this pin has never been read against.
         */
        fun relativeName(root: File, file: File): String {
            val relative = file.toRelativeString(root).replace(File.separatorChar, '/')
            if (!relative.startsWith(PACKAGE_PATH)) {
                fail(
                    "$relative is under $COMMON_ROOT but outside $PACKAGE_PATH; this sweep keys " +
                        "every narrowing by its path within that package and has no name for it.",
                )
            }
            return relative.removePrefix(PACKAGE_PATH)
        }

        /** Every comment line under [root] carrying [WORD], keyed by [relativeName]. */
        fun sweep(root: File): Swept {
            val files = sources(root)
            val lines = files.flatMap { file ->
                val relative = relativeName(root, file)
                file.readLines()
                    .filter { isComment(it) && mentionsYet(it) }
                    .map { at(relative, it.trim()) }
            }
            return Swept(files.map { relativeName(root, it) }.toSet(), lines)
        }
    }
}
