@file:JsModule("node:crypto")

package dev.eryalabs.nenya.nodecrypto

/**
 * The two members of Node's built-in `crypto` module the test oracle uses. Imported as a module
 * (not through `require`) so it works under the ES-module output this build produces. `node:crypto`
 * ships with Node itself: no npm package is involved.
 */
internal external fun createHash(algorithm: String): Hash

internal external interface Hash {
    fun update(data: dynamic): Hash
    fun digest(): dynamic
}
