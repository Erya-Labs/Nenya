// Top-level build file. Nenya has no Android modules yet on purpose: the protocol
// core is plain Kotlin/JVM so the whole suite runs headless, with no SDK, no
// emulator and no network. Android wiring arrives later as a separate thin module.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}
