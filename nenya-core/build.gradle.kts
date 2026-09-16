import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        // The published surface is a contract for third-party clients. Explicit API
        // mode forces every public declaration to carry an explicit visibility and
        // return type, so nothing leaks into the ABI by accident.
        explicitApi()
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}

tasks.test {
    useJUnit()
    testLogging { events("failed") }
}

// ---------------------------------------------------------------------------------
// Test-count floor. On Gradle 8.13 a Test task that discovers ZERO tests still
// reports BUILD SUCCESSFUL, so a misplaced test tree (or a source-set rename during
// the multiplatform conversion) would pass while running nothing. Every Test task
// therefore re-reads its OWN JUnit XML after it runs and fails the build when the
// result directory is empty or fewer tests executed than `nenya.minTests`
// (root gradle.properties). The location comes from the task, never a hardcoded
// path, so the guard survives `test` becoming `jvmTest`. JDK XML parser only.
// ---------------------------------------------------------------------------------
val minTestsProperty: Provider<String> = providers.gradleProperty("nenya.minTests")

tasks.withType<Test>().configureEach {
    val taskPath = path
    doLast {
        // Read from the task at execution time: a provider mapped off this output property
        // at configuration time loses its task association and Gradle refuses to query it.
        val xmlDir = (this as Test).reports.junitXml.outputLocation.get().asFile
        val floorText = minTestsProperty.orNull
            ?: throw GradleException("$taskPath: Gradle property nenya.minTests is not set (expected in the root gradle.properties)")
        val floor = floorText.trim().toIntOrNull()
            ?: throw GradleException("$taskPath: nenya.minTests must be an integer, was '$floorText'")
        val dir = xmlDir
        val results = dir.listFiles { f -> f.isFile && f.name.startsWith("TEST-") && f.name.endsWith(".xml") }
            ?.sortedBy { it.name }
            .orEmpty()
        if (results.isEmpty()) {
            throw GradleException(
                "$taskPath: test-count floor FAILED: expected at least $floor executed tests, " +
                    "actual 0 - no JUnit XML result files in ${dir.absolutePath}"
            )
        }
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        var tests = 0
        var skipped = 0
        for (file in results) {
            val suite = factory.newDocumentBuilder().parse(file).documentElement
            fun count(name: String): Int = suite.getAttribute(name).ifEmpty { "0" }.toInt()
            tests += count("tests")
            skipped += count("skipped")
        }
        val executed = tests - skipped
        if (executed < floor) {
            throw GradleException(
                "$taskPath: test-count floor FAILED: expected at least $floor executed tests, " +
                    "actual $executed ($tests reported, $skipped skipped, ${results.size} suites) in ${dir.absolutePath}"
            )
        }
        logger.lifecycle("$taskPath: test-count floor OK: $executed executed tests in ${results.size} suites (floor $floor)")
    }
}
