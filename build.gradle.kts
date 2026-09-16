// Top-level build file. Nenya has no Android modules yet on purpose: the protocol
// core is a Kotlin Multiplatform module (JVM, plus JS/IR for Node), and the JVM suite
// runs headless, with no SDK, no emulator, no Node and no network.
// Android wiring arrives later as a separate thin module.
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnLockMismatchReport
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
}

// Node.js and Yarn for the JavaScript test run (jsNodeTest). The loop's gate (jvmTest,
// compileKotlinJs, compileTestKotlinJs) never touches either; only JS test, run and
// distribution tasks do, and those run from a separate job with network.
//
// Node: the `node` already on PATH (Kotlin 2.0.21 NodeJsRootExtension.download = false,
// command "node"), never a download. The Kotlin plugin would otherwise fetch its own
// Node 22.0.0 from nodejs.org, a second Node on the machine that nothing else uses. A
// machine running JS tests therefore needs Node installed (here /usr/bin/node, Ubuntu's
// nodejs package); jsNodeTest fails at once if it is missing, it does not fall back.
//
// Yarn: downloaded by the plugin (1.22.17, from github.com). The plugin registers that
// download location as a PROJECT-level ivy repository, which settings.gradle.kts's
// FAIL_ON_PROJECT_REPOS rejects ("repository ... was added by unknown code"), so the base
// URL is nulled here and the repository is declared in settings instead, filtered to the
// one module it may serve. The npm packages Yarn installs are pinned by the committed
// kotlin-js-store/yarn.lock, and the settings below make any drift from that file fail
// the build instead of silently rewriting it: a Kotlin bump or a new npm() dependency that
// resolves different packages must come with a deliberately reviewed, committed yarn.lock.
plugins.withType<NodeJsRootPlugin> {
    the<NodeJsRootExtension>().apply {
        download = false
        command = "node"
        downloadBaseUrl = null
    }
}
plugins.withType<YarnPlugin> {
    the<YarnRootExtension>().apply {
        downloadBaseUrl = null
        // Never run npm install scripts from the test-only packages (the plugin default).
        ignoreScripts = true
        // The installed lock differs from kotlin-js-store/yarn.lock: fail, do not replace.
        yarnLockMismatchReport = YarnLockMismatchReport.FAIL
        yarnLockAutoReplace = false
        // No committed lock at all (a fresh clone with it deleted): fail too.
        reportNewYarnLock = true
    }
}
