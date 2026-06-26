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

            // StateFlow / SharedFlow plumbing and the atomic state inside
            // FakeSsdpClient.
            implementation(libs.kotlinx.coroutines.core)
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
