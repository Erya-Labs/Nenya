package dev.eryalabs.nenya.platform

/**
 * JavaScript `actual`s for [TypeNames.kt][runtimeSimpleName], read from Kotlin/JS class metadata.
 *
 * - [runtimeSimpleName]: the Kotlin simple name, or `""` for a class that has none (an anonymous
 *   object), which is what the JVM `actual` prints for one.
 * - [declaringEnumSimpleName]: Kotlin/JS compiles an enum constant with a body to a JavaScript
 *   class extending its enum's class, which extends the runtime's `Enum`. Walk the constructor's
 *   prototype chain to the class whose parent is `Enum`, and name that one.
 */

internal actual fun runtimeSimpleName(value: Any): String = value::class.simpleName ?: ""

internal actual fun declaringEnumSimpleName(constant: Enum<*>): String {
    val enumBase: dynamic = Enum::class.js
    var type: dynamic = constant.asDynamic().constructor
    while (true) {
        val parentPrototype: dynamic = js("Object").getPrototypeOf(type.prototype)
        val parent: dynamic = if (parentPrototype == null) null else parentPrototype.constructor
        if (parent == null || parent === enumBase) break
        type = parent
    }
    return type.unsafeCast<JsClass<Any>>().kotlin.simpleName ?: ""
}
