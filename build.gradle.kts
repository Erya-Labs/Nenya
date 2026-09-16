// Top-level build file. Nenya has no Android modules yet on purpose: the protocol
// core is a Kotlin Multiplatform module (JVM, plus JS/IR for Node), and the JVM suite
// runs headless, with no SDK, no emulator, no Node and no network.
// Android wiring arrives later as a separate thin module.
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
}

// The Kotlin/JS plugin registers its Node.js and Yarn download locations as PROJECT-level
// ivy repositories. settings.gradle.kts uses FAIL_ON_PROJECT_REPOS, which rejects those,
// so without this every JS task that needs Node fails to even configure ("repository
// 'Distributions at https://nodejs.org/dist' was added by unknown code"). Nulling the base
// URLs stops the plugin adding them; the two repositories are declared in settings
// instead, each filtered to the one module it may serve. The loop's gate (jvmTest +
// compileTestKotlinJs) never touches Node or Yarn; only JS test, run and dist tasks do.
plugins.withType<NodeJsRootPlugin> {
    the<NodeJsRootExtension>().downloadBaseUrl = null
}
plugins.withType<YarnPlugin> {
    the<YarnRootExtension>().downloadBaseUrl = null
}
