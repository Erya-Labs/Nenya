pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // The Kotlin/JS Yarn download. The Kotlin plugin would normally add this as a
        // project-level repository, which FAIL_ON_PROJECT_REPOS rejects, so the root
        // build.gradle.kts nulls the plugin's downloadBaseUrl and it is declared here, with
        // a content filter restricting it to the single module it exists for. Only JS test,
        // run and distribution tasks resolve it; jvmTest and compileTestKotlinJs never do.
        // There is no Node.js repository: Node comes from PATH (see build.gradle.kts).
        ivy("https://github.com/yarnpkg/yarn/releases/download") {
            name = "Yarn distributions"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.yarnpkg", "yarn") }
        }
    }
}

rootProject.name = "Nenya"
include(":nenya-core")
