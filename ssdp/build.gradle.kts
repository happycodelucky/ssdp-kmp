/*
 * ssdp-kmp — :ssdp module.
 *
 * Headless KMP SSDP (UPnP discovery) client: business logic only, no UI
 * dependencies (CLAUDE.md §1, §7). The module shape — target matrix (incl.
 * jvm()), apple intermediate source set, Android library block, compiler
 * options, SKIE settings — comes from the `ssdp.kmp-library` convention plugin;
 * Maven Central publishing comes from `ssdp.publish`. This script keeps only
 * what is unique to this module: dependencies, the KMMBridge SPM distribution
 * config, and POM name/description.
 */

plugins {
    id("ssdp.kmp-library")
    id("ssdp.publish")
    // kotlin-serialization (v1.1 description-XML): drives xmlutil's @Serializable
    // codegen for the UPnP description wire types in internal/DescriptionParser.kt.
    alias(libs.plugins.kotlin.serialization)
    // KMMBridge (CLAUDE.md §9): aggregates the per-target Apple frameworks the
    // convention plugin declared into `Ssdp.xcframework`, publishes the release
    // zip as a GitHub Release asset, and regenerates the root /Package.swift.
    // The `.github` plugin variant is a superset of the core plugin in 1.2.x —
    // applying both produces a duplicate-extension error, so only this one.
    //
    // Do NOT redeclare `XCFramework("Ssdp")` in the kotlin { } block: KMMBridge
    // auto-creates the aggregator from the framework binaries at config time.
    alias(libs.plugins.kmmbridge.github)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
            implementation(libs.kotlinx.io.core)
            // reachable supplies the network-change signal (Reachability.status)
            // that the device registry uses to reset when the LAN changes
            // (plan decision 4). `api` rather than `implementation` is not
            // needed — SSDP does not expose reachable types in its public API;
            // it consumes the status Flow internally.
            implementation(libs.reachable)
            // v1.1 description-XML fetch + cache. All `implementation`: the
            // public API returns ssdp's own DescriptionResult/DeviceDescription,
            // never a Ktor or xmlutil type. CIO is the multiplatform engine, so
            // one dep covers every target with zero expect/actual.
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            implementation(libs.xmlutil.serialization)
            // ktor-network: raw multiplatform TCP for the Android-emulator host
            // bridge. BridgeMulticastSocket (commonMain) tunnels SSDP over TCP to
            // the jvmMain bridge daemon, which does the real multicast on the host
            // LAN. Emulators can't receive inbound UDP multicast; physical devices
            // never touch this path. Gradle resolves the -jvm variant on Android
            // (no ktor-network-android artifact), native slices on Apple.
            implementation(libs.ktor.network)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
            implementation(libs.kotest.assertions.core)
            implementation(libs.kotest.property)
            // Ktor MockEngine drives the description fetch under runTest with no
            // real HTTP.
            implementation(libs.ktor.client.mock)
            // `:ssdp-testing` provides the public `FakeSsdpClient` used in
            // registry / client tests. The testing module `api`s `:ssdp`'s
            // `main` configuration; the back-edge here is on `commonTest`, not
            // `main`, so Gradle resolves both without a circular `main`
            // dependency.
            implementation(project(":ssdp-testing"))
        }

        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
        }

        // androidHostTest source set is created by the convention plugin's
        // withHostTestBuilder. Configure its deps here. (These test source sets
        // don't inherit commonTest's deps, so the MockEngine is repeated.)
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
            implementation(libs.ktor.client.mock)
            implementation(project(":ssdp-testing"))
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
            implementation(libs.ktor.client.mock)
            implementation(project(":ssdp-testing"))
        }
    }
}

skie {
    build {
        // Xcode 26 requires .swiftinterface files in every framework slice
        // before xcodebuild -create-xcframework will accept them (exit 70
        // otherwise). produceDistributableFramework() enables Swift library
        // evolution so SKIE emits .swiftinterface alongside .swiftmodule.
        // `:ssdp-testing` doesn't need this — it isn't shipped as an XCFramework.
        produceDistributableFramework()
    }
}

// --- KMMBridge: XCFramework → GitHub Release asset → SPM (CLAUDE.md §9) ------
//
// Two distribution channels run from this module, and they don't overlap:
//
//   1. Maven Central (`ssdp.publish` convention plugin) — Android AAR, the jvm
//      jar, `kotlinMultiplatform` metadata, and per-target klibs. KMP consumers
//      resolve these from `commonMain`; no XCFramework involved.
//   2. GitHub Releases (this block) — the SKIE-enhanced `Ssdp.xcframework` zip
//      for pure-Swift consumers, referenced from the root /Package.swift by URL
//      + checksum so `swift package resolve` needs no local Gradle build and no
//      authentication.
//
// Publishing is CI-only: the `kmmBridgePublish` umbrella task is only
// registered when `-PENABLE_PUBLISHING=true` is passed, and the upload reads
// the `GITHUB_REPO` / `GITHUB_PUBLISH_TOKEN` Gradle properties —
// .github/workflows/release.yml supplies all three. Local builds skip the
// publish wiring entirely; `spmDevBuild` (always registered) is the local-dev
// entry point — see mise task `spm:dev`.
gitHubReleaseArtifacts(releasString = "v${project.version}")

kmmbridge {
    // The XCFramework's Swift module name. Must match the `baseName` the
    // convention plugin sets on each framework binary, or the generated
    // Package.swift references a binary that doesn't exist.
    frameworkName.set("Ssdp")

    // `swiftToolVersion = "6.0"` because the platform constants `.iOS(.v18)`
    // and `.macOS(.v15)` need PackageDescription 6.0; KMMBridge defaults to
    // 5.3, which can't compile them.
    //
    // Platform floors match `gradle/libs.versions.toml`
    // (ios-deployment-target = 18.0, macos-deployment-target = 15.0). They're
    // spelled "18" / "15" here because KMMBridge emits `.iOS(.v$value)`
    // verbatim — "18.0" would produce the non-existent constant `.v18.0`.
    spm(swiftToolVersion = "6.0") {
        iOS { v("18") }
        macOS { v("15") }
    }
}

mavenPublishing {
    pom {
        name.set("Ssdp")
        description.set(
            "Kotlin Multiplatform SSDP (UPnP) discovery client for iOS, macOS, " +
                "Android, and JVM — multicast M-SEARCH with retransmit, NOTIFY " +
                "lifecycle tracking, and a live device registry.",
        )
    }
}
