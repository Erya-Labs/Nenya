// Top-level build file. Nenya has no Android modules yet on purpose: the protocol
// core is a Kotlin Multiplatform module whose only target so far is the JVM, so the
// whole suite runs headless, with no SDK, no emulator, no Node and no network.
// Android wiring arrives later as a separate thin module.
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
}
