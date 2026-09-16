package dev.eryalabs.nenya.conformance

import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The reflection sweep `PaymentStructureTest` specifies, hoisted so this package can run it over
 * six packages instead of one.
 *
 * Every note there applies unchanged and is repeated because both are load-bearing:
 *
 * - **`java.lang.reflect` only.** `kotlin-reflect` is not on any classpath and STOP RULE 11
 *   forbids adding one. `KClass.members` and `KClass.constructors` compile — with a warning — and
 *   then throw `KotlinReflectionNotSupportedError` at run time, while `kotlin.reflect.full.*` does
 *   not compile at all, so reaching for it burns a strike on a red build rather than a red test.
 * - **`getResources`, plural, then the `/classes/kotlin/jvm/main/` URL.** The singular `getResource`
 *   resolves to the **test** output directory, because `build/classes/kotlin/jvm/test` precedes
 *   `build/classes/kotlin/jvm/main` on the test runtime classpath and this repo mirrors package names
 *   into its test tree. A sweep built on it enumerates this file's own class, never sees the
 *   implementation, and passes with it entirely absent.
 *
 * Fails loudly when no main URL is found, for that exact reason.
 */
internal object MainClasses {

    /** Every class compiled from `src/jvmMain` into [packageName], nested and companion ones included. */
    fun of(packageName: String): List<Class<*>> {
        val loader = MainClasses::class.java.classLoader
        val urls = loader.getResources(packageName.replace('.', '/')).toList()
        val main = urls.firstOrNull { it.path.contains("/classes/kotlin/jvm/main/") }
            ?: fail(
                "no /classes/kotlin/jvm/main/ URL for $packageName among $urls — the sweep would " +
                    "otherwise enumerate the test output directory and pass with no implementation",
            )
        val directory = File(main.toURI())
        val files = directory.listFiles { file: File -> file.name.endsWith(".class") }
            ?: fail("${directory.absolutePath} is not a readable directory")
        assertTrue(files.isNotEmpty(), "${directory.absolutePath} holds no classes")
        val floor = MIN_CLASSES[packageName] ?: 1
        assertTrue(
            files.size >= floor,
            "${directory.absolutePath} holds ${files.size} class file(s) for $packageName; at least " +
                "$floor were pinned. A sweep over a partial output directory passes while checking " +
                "less than it claims",
        )
        return files.sortedBy { it.name }
            .map { Class.forName("$packageName.${it.name.removeSuffix(".class")}", false, loader) }
    }

    /**
     * The class files each package compiled to at commit 6432814, pinned as a floor.
     *
     * A floor, not an exact count: a package that grows stays green. It exists for the source
     * layout move to Kotlin Multiplatform — if part of a package lands in an output directory these
     * sweeps do not read, [of] must go red rather than sweep the remainder. Lower an entry only in
     * the same commit that deliberately removes classes, saying which. A package not listed here
     * still needs at least one class.
     */
    private val MIN_CLASSES: Map<String, Int> = mapOf(
        "dev.eryalabs.nenya" to 1,
        "dev.eryalabs.nenya.bid" to 8,
        "dev.eryalabs.nenya.conformance" to 4,
        "dev.eryalabs.nenya.delivery" to 14,
        "dev.eryalabs.nenya.listing" to 14,
        "dev.eryalabs.nenya.money" to 10,
        "dev.eryalabs.nenya.order" to 37,
        "dev.eryalabs.nenya.payment" to 16,
        "dev.eryalabs.nenya.seam" to 33,
        "dev.eryalabs.nenya.tag" to 30,
        "dev.eryalabs.nenya.wire" to 12,
    )

    /** Root plus the ten sub-packages that existed at commit 6432814, pinned as a floor. */
    private const val MIN_PACKAGES: Int = 11

    /** Those of [of] a client can name. */
    fun published(packageName: String): List<Class<*>> =
        of(packageName).filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }

    /**
     * A class's own public methods, minus everything a client cannot name and everything the
     * Kotlin compiler wrote.
     *
     * `'$' !in name` drops both the `…$default` overloads Kotlin emits for default arguments and
     * the `…$nenya_core` mangling it gives `internal` functions; `isSynthetic`/`isBridge` drop the
     * rest. What is left is the published surface a Java caller sees by name.
     */
    fun methods(type: Class<*>): List<Method> =
        type.methods.filter {
            it.declaringClass == type && !it.isSynthetic && !it.isBridge && '$' !in it.name
        }

    /**
     * `values`, `valueOf` and `getEntries` on an enum, which the Kotlin compiler generates and
     * nobody in this repository wrote.
     *
     * They are excluded from the hostile-input sweep rather than swept: `valueOf` takes a String
     * and throws `IllegalArgumentException` for a name the enum does not have, which is the
     * language's contract rather than a decoder this project hardened. Including them would put
     * thirteen compiler-generated members in a list whose purpose is to make a human look at every
     * new decode entry point.
     */
    fun isCompilerGeneratedEnumMember(type: Class<*>, method: Method): Boolean =
        type.isEnum && method.name in setOf("values", "valueOf", "getEntries")

    /** `TagSet$Companion.read` — the label the swept lists and the reflected set both use. */
    fun label(packageName: String, type: Class<*>, method: Method): String =
        "${type.name.removePrefix("$packageName.")}.${method.name}"

    /**
     * Every package this library compiles from `src/jvmMain`, discovered rather than listed.
     *
     * The root package and each directory beneath it in the main output tree. Discovered so that a
     * package added by a later task joins the library-wide sweeps — §14 item 12's no-floating-point
     * rule among them — without anybody remembering to add it to a list.
     */
    fun allPackages(): List<String> {
        val loader = MainClasses::class.java.classLoader
        val urls = loader.getResources(ROOT.replace('.', '/')).toList()
        val url = urls.firstOrNull { it.path.contains("/classes/kotlin/jvm/main/") }
            ?: fail("no /classes/kotlin/jvm/main/ URL for $ROOT among $urls")
        val directory = File(url.toURI())
        val children = directory.listFiles { file: File -> file.isDirectory }
            ?: fail("${directory.absolutePath} is not a readable directory")
        val packages = listOf(ROOT) + children.map { "$ROOT.${it.name}" }.sorted()
        assertTrue(
            packages.size >= MIN_PACKAGES,
            "only ${packages.size} package(s) found under ${directory.absolutePath}, at least " +
                "$MIN_PACKAGES were pinned; that is not this library, and a sweep over it would " +
                "prove less than it claims",
        )
        for (pinned in MIN_CLASSES.keys) {
            assertTrue(
                pinned in packages,
                "$pinned is not among the packages found under ${directory.absolutePath}: $packages",
            )
        }
        return packages
    }

    private const val ROOT: String = "dev.eryalabs.nenya"
}
