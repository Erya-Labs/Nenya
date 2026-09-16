package dev.eryalabs.nenya.platform

/**
 * JVM `actual`s for [TypeNames.kt][runtimeSimpleName]: `java.lang.Class` reads, exactly as they
 * were before the code that calls them moved to common Kotlin. This file stays in `src/jvmMain`
 * because `Class.getSimpleName` and `Class.isEnum`/`getSuperclass` have no common spelling that
 * gives the same strings; see the `expect` file for the differences.
 */

internal actual fun runtimeSimpleName(value: Any): String = value::class.java.simpleName

internal actual fun declaringEnumSimpleName(constant: Enum<*>): String {
    val type = constant.javaClass
    val declaring = if (type.isEnum) type else type.superclass
    return declaring.simpleName
}
