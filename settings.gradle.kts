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
        // Kotlin/JS toolchain downloads. The Kotlin plugin would normally add these as
        // project-level repositories, which FAIL_ON_PROJECT_REPOS rejects, so the root
        // build.gradle.kts nulls the plugin's downloadBaseUrl and they are declared here.
        // Each has a content filter restricting it to the single module it exists for, so
        // neither can serve any other dependency. Only JS test, run and distribution tasks
        // resolve them; jvmTest and compileTestKotlinJs never do.
        ivy("https://nodejs.org/dist") {
            name = "Node.js distributions"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
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
