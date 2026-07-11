/*
 * ssdp-kmp — JVM CLI sample / live discovery harness.
 *
 * A plain JVM application that runs the real SsdpClient against the local
 * network: searches for all SSDP targets and prints the live device registry.
 * Used both as a usage example and as the manual end-to-end test for the
 * multicast send/receive path (which virtual-time unit tests can't cover).
 *
 * Not a published artifact — excluded from the library lint/check gate. Depends
 * on :ssdp via the JVM slice.
 */
plugins {
    // Version omitted: the Kotlin plugin is already on the build classpath from
    // the root build's pluginManagement, so the subproject applies it
    // version-less (re-requesting a version is a hard error in a composite build).
    kotlin("jvm")
    application
}

// This sample is NOT subject to the root build's FAIL_ON_PROJECT_REPOS, because
// repositories are declared at the dependencyResolutionManagement level there.
// It resolves :ssdp via the project dependency; transitive deps come from the
// root-configured repos.

dependencies {
    implementation(project(":ssdp"))
    implementation(libs.kotlinx.coroutines.core)
    // SLF4J provider for Ktor's JVM client engine (transitively depends on
    // slf4j-api but ships no binding). Without it a run prints "No SLF4J
    // providers were found". runtimeOnly — nothing here compiles against it.
    runtimeOnly(libs.slf4j.simple)
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.happycodelucky.ssdp.cli.MainKt")
}
