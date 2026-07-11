/*
 * ssdp-kmp — KMP SSDP (UPnP discovery) client library.
 *
 * /ssdp is the core headless KMP module: it exposes an `SsdpClient` interface
 * and platform multicast-socket implementations over POSIX BSD sockets (Apple)
 * and `java.net.MulticastSocket` (Android + JVM). Platform apps live under
 * /apps and consume /ssdp via KMMBridge → Maven → SPM on Apple (CLAUDE.md §9),
 * as an AAR on Android, and as a plain jar on the JVM.
 *
 * Targets, unlike the sibling `reachable` repo, include `jvm()` — desktop /
 * server / Linux / Windows are served by the JVM slice, not native targets.
 */

@file:Suppress("UnstableApiUsage")

pluginManagement {
    // Convention plugins (`ssdp.kmp-library`, `ssdp.publish`) live in
    // gradle/plugins; versions still come from gradle/libs.versions.toml,
    // which gradle/plugins shares.
    includeBuild("gradle/plugins")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Project-level repos win; subprojects must not redeclare.
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        // mavenLocal lets a locally-built `reachable` snapshot resolve ahead of
        // Maven Central during cross-repo development (e.g. testing an unreleased
        // reachable change against ssdp-kmp). For released builds, reachable
        // 0.14.0+ resolves from mavenCentral above. Listed last so Central wins
        // for everything that isn't an explicit local install.
        mavenLocal()
    }
}

rootProject.name = "ssdp-kmp"

include(":ssdp")

// :ssdp-testing — public, scriptable `FakeSsdpClient` plus the
// `withFakeSsdpClient { … }` helper that installs/uninstalls it for the
// lifetime of a test. Headless KMP module; same targets as `:ssdp`; published
// as a sibling Maven Central artifact (`com.happycodelucky.ssdp:ssdp-testing`).
// Consumers wire it on `testImplementation` (or KMP `commonTest` deps).
include(":ssdp-testing")

// --- Sample apps (CLAUDE.md §4) -----------------------------------------------
// The Android sample is a normal Gradle subproject because Compose + AGP play
// best inside the same Gradle build that produces the AAR. The iOS and macOS
// samples are standalone Xcode projects under /apps/ios and /apps/macos; they
// consume the shared module via SPM, NOT Gradle, and so are deliberately not
// included here. A JVM CLI sample lives under /apps/cli for on-LAN testing.
//
// The Android SSDP scanner sample: Compose UI listing discovered devices
// (grouped by UDN) with a description-detail screen. Consumes :ssdp as a
// project dependency.
include(":androidApp")
project(":androidApp").projectDir = file("apps/android")

// JVM CLI live-discovery harness / sample. Plain kotlin("jvm") app (its own
// repositories block), depends on :ssdp via the JVM slice. Not published, not
// in the library check gate.
include(":cli")
project(":cli").projectDir = file("apps/cli")

// SSDP bridge daemon — runs on the host so an Android emulator (which can't
// receive inbound UDP multicast) can discover real LAN devices over TCP. Plain
// kotlin("jvm") app, a thin shell over :ssdp's runSsdpBridgeDaemon. Lives at the
// repo root (not under apps/) since it's a distributable host tool, not a sample.
// Not published, not in the library check gate.
include(":ssdp-bridge")
project(":ssdp-bridge").projectDir = file("ssdp-bridge")
