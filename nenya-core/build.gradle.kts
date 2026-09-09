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
