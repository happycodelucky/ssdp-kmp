/*
 * ssdp-kmp — :ssdp-testing module.
 *
 * Public, scriptable test fakes and helpers for consumers of `:ssdp`:
 * `FakeSsdpClient` plus the `withFakeSsdpClient { … }` helper. Same module
 * shape as `:ssdp` via the `ssdp.kmp-library` convention plugin; published in
 * lockstep (same group / version / pipeline) via `ssdp.publish`. Consumers wire
 * it on `testImplementation` (or KMP `commonTest` deps); the production `:ssdp`
 * artifact does not depend on this module.
 *
 * No XCFramework and no SKIE `produceDistributableFramework()`: test code is
 * consumed as KMP klibs from Maven Central, not via SPM. The Apple targets
 * exist so KMP consumers can resolve this module from their Apple test source
 * sets, but we don't ship a binary framework for it.
 */

plugins {
    id("ssdp.kmp-library")
    id("ssdp.publish")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // `api` so consumers writing `testImplementation(ssdp-testing)` get
            // the public `:ssdp` types (SsdpClient, DiscoveredDevice,
            // DeviceChange, SearchTarget) transitively — they will assert
            // against those types.
            api(project(":ssdp"))
            // FakeSsdpClient's description stubs take a KotlinResult `Result`,
            // so declare it directly rather than lean on `:ssdp`'s transitive `api`.
            api(libs.kotlinresult)

            // StateFlow / SharedFlow plumbing inside FakeSsdpClient. `api`:
            // its public surface exposes those flow types.
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.atomicfu)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }

        // androidHostTest source set is created by the convention plugin's
        // withHostTestBuilder. Configure its deps here.
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
    }
}

skie {
    // Swift bundling ON here, unlike the convention plugin's default (LESSONS
    // D-012, D-015): SKIE compiles a linked klib's bundled Swift only when the
    // framework's module has bundling enabled, and KotlinResult's
    // `KotlinResult+Swift.swift` (`try r.get()`, `r.result(as:)`,
    // `KotlinThrowable: Error`) is how Swift consumes `description()`. This module
    // has no Swift sources of its own, so it ships nothing new in its klib and
    // adds no `export` of this module downstream.
    swiftBundling {
        enabled.set(true)
    }
}

// Export KotlinResult into SsdpTestingKit. Required, not optional: its bundled Swift
// only compiles where `KotlinResult` keeps its plain Swift name, which the
// export guarantees ("cannot find type 'KotlinResult' in scope" otherwise).
// Any consumer framework that links this module needs the same export (README).
kotlin {
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.withType<org.jetbrains.kotlin.gradle.plugin.mpp.Framework>().configureEach {
            export(libs.kotlinresult)
        }
    }
}

mavenPublishing {
    pom {
        name.set("Ssdp Testing")
        description.set(
            "Test fakes and helpers for the ssdp-kmp library: FakeSsdpClient + " +
                "withFakeSsdpClient for scripting discovery events in tests " +
                "without a real multicast socket.",
        )
    }
}
