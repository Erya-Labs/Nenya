package dev.eryalabs.nenya.js

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

// =================================================================================================
// §7.1's two procedures again, for a signer that answers later (T55, decision R).
//
// NIP-07's `window.nostr` answers every one of `JsSigner`'s four questions as a Promise, and §7.1's
// procedures are synchronous: `GiftWrap.seal` asks its signer, uses the answer on the next line, and
// asks again. Decision P keeps every seam synchronous, so nothing below makes `Signer` asynchronous.
// Instead the SAME `seal`/`open` call is re-run, and every answer a seam has already given is replayed
// to it from a memo, in call order. A run that reaches a question nobody has answered stops there —
// the signer answers unavailable, and §7.1 refuses at its first unavailable signer call — and its
// refusal is thrown away rather than delivered. The question is then asked of the page once, and its
// answer starts the next run. The one delivered answer is the run that asked nothing new: the unchanged
// `GiftWrap` call's own, over answers the page actually gave.
//
// Everything a run reads from a seam is replayed, not only the signer's answers: the clock (read once
// per run), the four random draws of §7.1 step 5, the verifier, and `freshSigner` together with every
// answer of the throwaway signers it mints. Without that, each re-run would hand the signer a seal with
// new timestamps or a new throwaway key, the question asked would never be the one answered, and the
// runs would never converge.
// =================================================================================================

/**
 * The §3 signer seam as NIP-07 offers it: four functions that answer later, through a completion.
 *
 * Each takes [JsSigner]'s arguments plus `complete`, and answers by calling `complete` with what
 * [JsSigner]'s function of the same name would have returned — `null` still meaning unavailable, with
 * the detail this library authors (see [JsSigner]). A page wraps `window.nostr` in a few lines:
 * `(event, complete) => window.nostr.signEvent(…).then(e => complete(e.sig), () => complete(null))`.
 *
 * A completion called twice, or after the answer was delivered, changes nothing. One that is never
 * called means [sealAsync] or [openAsync] never answers: there is no timeout here, because a deadline
 * would be a clock this library does not have and a guess it must not make (§4.6).
 *
 * Nothing these functions complete with is evidence of anything (§3, §9.1), exactly as for [JsSigner].
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public class JsAsyncSigner public constructor(

    /** §4.1's own x-only public key, 64 hex characters, or `null` for unavailable. */
    public val publicKeyHex: ((String?) -> Unit) -> Unit,

    /** A BIP-340 signature over the canonical serialisation handed in, 128 hex characters, or `null`. */
    public val signEvent: (String, (String?) -> Unit) -> Unit,

    /** NIP-44 v2 encryption to the counterparty key handed in, or `null` for unavailable. */
    public val nip44Encrypt: (String, String, (String?) -> Unit) -> Unit,

    /** NIP-44 v2 decryption, answering [JsDecryption], or `null` for unavailable. */
    public val nip44Decrypt: (String, String, (JsDecryption?) -> Unit) -> Unit,
) {

    /** Names no function and no key (§12 item 11, STOP RULE 14). */
    override fun toString(): String = "JsAsyncSigner(redacted)"
}

/**
 * [seal], for a sender whose signer answers later: the same answer, delivered once through [onResult].
 *
 * The answer is the one [seal] gives over the same seams answering the same things — byte for byte,
 * both copies — because it **is** [seal]'s, made by the run in which every one of the sender's
 * questions had been answered. [environment] stays synchronous, as it is for [seal]; its answers are
 * replayed to every run so that each run asks the sender the same questions.
 *
 * [onResult] is called exactly once, with a refusal as a value exactly where [seal] refuses — or never,
 * if the sender never completes a question. This function itself returns before or after [onResult]
 * is called, depending on whether the sender completes synchronously, and throws nothing [seal] would
 * not throw — though a page function that throws during a run a completion started throws into that
 * completion's caller, not into this one's.
 *
 * [onResult] comes before the two parameters with defaults, unlike a Kotlin trailing lambda: a required
 * parameter after optional ones is not valid TypeScript, and the declarations are generated for pages.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun sealAsync(
    rumor: JsWireEvent,
    recipientPubkey: String,
    sender: JsAsyncSigner,
    onResult: (JsSealResult) -> Unit,
    environment: JsEnvironment = JsEnvironment(),
    recipientRelayHint: String? = null,
) {
    ReplayDriver(onResult) { memo ->
        seal(rumor, recipientPubkey, memo.signer(sender), memo.environment(environment), recipientRelayHint)
    }.drive()
}

/**
 * [open], for a reader whose signer answers later: the same answer, delivered once through [onResult].
 *
 * The reader is asked what [open] asks — its public key, then the two decryptions — and nothing is
 * decrypted for a wrap addressed to somebody else, because the `p` tag is still checked first: the run
 * that learns the reader's key is refused there and asks nothing more. [environment] is read for its
 * verifier alone, as [open] reads it, and the verifier's answers are replayed like every other seam's.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun openAsync(
    wrapJson: String,
    me: JsAsyncSigner,
    onResult: (JsOpenResult) -> Unit,
    environment: JsEnvironment = JsEnvironment(),
) {
    ReplayDriver(onResult) { memo -> open(wrapJson, memo.signer(me), memo.environment(environment)) }.drive()
}

// =================================================================================================
// The driver and its memo. Neither crosses: both are private to this file.
// =================================================================================================

/**
 * Re-runs [attempt] until a run asks no question that is still unanswered, then hands that run's
 * answer to [onResult] — once.
 *
 * [attempt] is the synchronous entry point itself, so each run is made inside its `jsGuarded` and a
 * refusal raised during a run that a completion started crosses as a value, as it does for [seal].
 */
private class ReplayDriver<R>(
    private val onResult: (R) -> Unit,
    private val attempt: (ReplayMemo) -> R,
) {

    private val memo = ReplayMemo(::drive)

    private var delivered = false

    /** True while a run is in progress, so a completion arriving inside one cannot start another. */
    private var running = false

    /** A completion arrived while a run was in progress: run again once it has finished. */
    private var again = false

    /**
     * Started by the entry point, and again by each first completion of a pending question.
     *
     * A loop rather than a recursion: a page completing inside the call that asked — a synchronous
     * fake, or a cached answer — asks for the next run while this one is still on the stack, and is
     * answered when this one returns.
     */
    fun drive() {
        if (running) {
            again = true
            return
        }
        running = true
        try {
            do {
                again = false
                if (delivered) return
                runOnce()
            } while (again)
        } finally {
            running = false
        }
    }

    private fun runOnce() {
        memo.rewind()
        val answer = attempt(memo)
        val ask = memo.unanswered
        when {
            // The run stopped at a question nobody has answered: its refusal is not the answer. Ask
            // the page, once; its completion starts the next run.
            ask != null -> ask()
            // The run reached an answer the replay could not stand behind — a question asked in a
            // different order from the run before. A deterministic procedure over replayed answers
            // never does, so this is a defect here, and delivering the refusal it produced would
            // report the page's signer as unavailable when it was not. Nothing is delivered.
            memo.diverged -> Unit
            else -> {
                delivered = true
                onResult(answer)
            }
        }
    }

    override fun toString(): String = "ReplayDriver(redacted)"
}

/**
 * Every answer the seams have given across all runs, in the order the procedure asked for them.
 *
 * Each answer is filed with the question it answers — the function's name and its arguments — and a
 * run is handed a filed answer only when it asks the **same** question at the same position. Holds the
 * questions a run asked, which include a rumor's plaintext, in memory only: nothing here is logged and
 * [toString] names none of it (STOP RULE 14).
 */
private class ReplayMemo(private val rerun: () -> Unit) {

    private class Filed(val question: List<String?>, var answered: Boolean, var answer: Any?)

    private val filed = mutableListOf<Filed>()

    /** How many answers this run has been handed so far. */
    private var position = 0

    /** Set once this run has stopped; every seam call after it answers unavailable without asking. */
    private var stopped = false

    /** The question this run stopped at, ready to be asked of the page. `null` if it asked none new. */
    var unanswered: (() -> Unit)? = null
        private set

    /** True when this run stopped because a question did not match the one filed at its position. */
    var diverged: Boolean = false
        private set

    /** Back to the first answer, for a new run. */
    fun rewind() {
        position = 0
        stopped = false
        unanswered = null
        diverged = false
    }

    /**
     * A synchronous seam's answer: the filed one at this position, or [ask]'s — asked now and filed.
     *
     * Asked of the page exactly once per position across every run, which is what makes a seeded
     * source drawn per run, or a one-time signer minting a key per call, give each run the same values.
     */
    fun <T> replay(question: List<String?>, ask: () -> T?): T? {
        if (stopped) return null
        val at = position++
        if (at < filed.size) return filedAnswer(at, question)
        val answer = ask()
        filed += Filed(question, true, answer)
        return answer
    }

    /**
     * The asynchronous signer's answer: the filed one at this position, or nothing for now.
     *
     * A question not filed before stops the run. It is filed unanswered, and [unanswered] becomes the
     * one call that asks it — through [fire], with a completion that files the answer and starts the
     * next run the first time it is called, and does nothing every time after.
     */
    fun <T> await(question: List<String?>, fire: ((T?) -> Unit) -> Unit): T? {
        if (stopped) return null
        val at = position++
        if (at < filed.size) return filedAnswer(at, question)
        val entry = Filed(question, false, null)
        filed += entry
        stopped = true
        unanswered = {
            fire { answer ->
                if (!entry.answered) {
                    entry.answered = true
                    entry.answer = answer
                    rerun()
                }
            }
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> filedAnswer(at: Int, question: List<String?>): T? {
        val entry = filed[at]
        if (entry.question != question || !entry.answered) {
            stopped = true
            diverged = true
            return null
        }
        return entry.answer as T?
    }

    /** [async] as the synchronous [JsSigner] [seal] and [open] take, answering from this memo. */
    fun signer(async: JsAsyncSigner): JsSigner = JsSigner(
        publicKeyHex = { await(listOf("publicKeyHex")) { done -> async.publicKeyHex(done) } },
        signEvent = { serialisation ->
            await(listOf("signEvent", serialisation)) { done -> async.signEvent(serialisation, done) }
        },
        nip44Encrypt = { key, plaintext ->
            await(listOf("nip44Encrypt", key, plaintext)) { done -> async.nip44Encrypt(key, plaintext, done) }
        },
        nip44Decrypt = { key, payload ->
            await(listOf("nip44Decrypt", key, payload)) { done -> async.nip44Decrypt(key, payload, done) }
        },
    )

    /** [page]'s seams, each answering from this memo. An absent one stays absent: it fails closed. */
    fun environment(page: JsEnvironment): JsEnvironment = JsEnvironment(
        nowSeconds = page.nowSeconds?.let { now -> { replay(listOf("nowSeconds")) { now() } } },
        randomBytesHex = page.randomBytesHex?.let { bytes ->
            { count -> replay(listOf("randomBytesHex", count.toString())) { bytes(count) } }
        },
        verifySchnorr = page.verifySchnorr?.let { verify ->
            { key, message, signature ->
                replay(listOf("verifySchnorr", key, message, signature)) { verify(key, message, signature) }
            }
        },
        freshSigner = page.freshSigner?.let { fresh ->
            { replay(listOf("freshSigner")) { fresh()?.let { throwaway(it) } } }
        },
    )

    /** One throwaway signer [JsEnvironment.freshSigner] minted, each of its answers replayed too. */
    private fun throwaway(minted: JsSigner): JsSigner = JsSigner(
        publicKeyHex = { replay(listOf("throwaway.publicKeyHex")) { minted.publicKeyHex() } },
        signEvent = { serialisation ->
            replay(listOf("throwaway.signEvent", serialisation)) { minted.signEvent(serialisation) }
        },
        nip44Encrypt = { key, plaintext ->
            replay(listOf("throwaway.nip44Encrypt", key, plaintext)) { minted.nip44Encrypt(key, plaintext) }
        },
        nip44Decrypt = { key, payload ->
            replay(listOf("throwaway.nip44Decrypt", key, payload)) { minted.nip44Decrypt(key, payload) }
        },
    )

    override fun toString(): String = "ReplayMemo(redacted)"
}
