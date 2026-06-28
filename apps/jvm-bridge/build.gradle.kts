/*
 * ssdp-kmp — host-side SSDP bridge daemon (JVM application).
 *
 * Runs on the developer's host machine so an Android emulator (which can't
 * receive inbound UDP multicast) can discover real LAN devices: the daemon does
 * the real multicast on the host LAN and relays it over TCP to the emulator app,
 * which connects via `SsdpClient.bridged()`. See ssdp/.../bridge/BridgeDaemon.kt.
 *
 * A thin shell over `runSsdpBridgeDaemon` in :ssdp's jvmMain (where the pipe
 * logic is unit-tested). Not a published artifact — excluded from the library
 * lint/check gate, exactly like :jvm-cli.
 */
plugins {
    // Version omitted: the Kotlin plugin is already on the build classpath from
    // the root build's pluginManagement, so the subproject applies it
    // version-less (re-requesting a version is a hard error in a composite build).
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":ssdp"))
    implementation(libs.kotlinx.coroutines.core)
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.happycodelucky.ssdp.bridge.MainKt")
}
