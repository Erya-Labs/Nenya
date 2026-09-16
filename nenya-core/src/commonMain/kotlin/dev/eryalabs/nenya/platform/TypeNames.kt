package dev.eryalabs.nenya.platform

/**
 * The two type names this library prints that common Kotlin cannot spell identically to the
 * JVM build that shipped before the Kotlin Multiplatform move.
 *
 * Both were `java.lang.Class` reads. Their common-Kotlin stand-ins are not the same function:
 *
 * - `value::class.simpleName` maps JVM classes to Kotlin names — `Int` where `Class.simpleName`
 *   says `Integer`, `ByteArray` where it says `byte[]`, and `null` where it says `""` for an
 *   anonymous class — so [SeamAnswer.Provided.toString][dev.eryalabs.nenya.seam.SeamAnswer.Provided]
 *   would print a different name for the same value on the same platform.
 * - An enum constant with a body is an anonymous subclass of its enum. `constant::class.simpleName`
 *   names that subclass (the constant, on the JVM), not the enum, and common Kotlin has no
 *   supertype lookup without kotlin-reflect. `Capabilities.qualify` publishes the enum's name.
 *
 * So each is an `expect` with a real `actual` per target: the JVM one is the previous code
 * verbatim, and the JavaScript one reaches the same answer through Kotlin/JS class metadata.
 */

/** The platform's simple name for [value]'s runtime class, never `null`. */
internal expect fun runtimeSimpleName(value: Any): String

/**
 * The simple name of the enum that declares [constant] — the enum itself even when [constant]
 * has a body and is therefore an anonymous subclass of it.
 */
internal expect fun declaringEnumSimpleName(constant: Enum<*>): String
