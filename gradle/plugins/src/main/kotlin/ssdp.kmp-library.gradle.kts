/*
 * Convention plugin: the shared module shape for ssdp-kmp's published KMP
 * libraries (`:ssdp`, `:ssdp-testing`).
 *
 * Owns everything the two modules would otherwise duplicate (CLAUDE.md §1, §2,
 * §4): the target matrix, the Android library block, the jvm() target,
 * compiler options, JVM target wiring, and the SKIE settings that must match across modules. Per-module identity
 * (framework base name, bundle id, Android namespace) is derived from the
 * project name so adding a module means applying this plugin and nothing else:
 *
 *   ssdp          → framework "Ssdp",        namespace com.happycodelucky.ssdp
 *   ssdp-testing  → framework "SsdpTesting",  namespace com.happycodelucky.ssdp.testing
 *
 * Module build scripts keep only what genuinely differs: dependencies, the
 * KMMBridge SPM distribution config (`:ssdp` only), and POM name/description.
 *
 * Difference from the sibling `reachable` convention plugin: ssdp adds a
 * `jvm()` target. SSDP discovery runs unchanged on the desktop/server JVM via
 * java.net.MulticastSocket (jvmMain), so the JVM is a first-class target here —
 * it serves Linux/Windows/desktop, which the ARM-only native rule excludes.
 * The JVM slice ships through Maven Central only — no SKIE, no KMMBridge (those
 * are Apple-framework concerns).
 */

import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("co.touchlab.skie")
    id("org.jetbrains.dokka")
}

// Typed `libs` accessors aren't generated inside precompiled script plugins;
// the named-lookup API reads the same catalog the main build uses.
val libs = the<VersionCatalogsExtension>().named("libs")

// ssdp → "Ssdp"; ssdp-testing → "SsdpTesting".
val frameworkBaseName = name.split("-").joinToString("") { part -> part.replaceFirstChar(Char::uppercase) }

// ssdp → com.happycodelucky.ssdp; ssdp-testing → ….ssdp.testing.
// Doubles as the framework bundle id, pinned so SKIE doesn't fall back to the
// framework name.
val moduleNamespace = "com.happycodelucky." + name.replace("-", ".")

// Bytecode level for BOTH JVM-flavored targets (android + jvm) — a consumer
// contract, deliberately independent of the JDK that runs the build.
val jvmBytecodeTarget =
    JvmTarget.fromTarget(
        libs
            .findVersion("jvm-target")
            .get()
            .requiredVersion,
    )

kotlin {
    // CLAUDE.md §4: source-set wiring is Kotlin's DEFAULT hierarchy template,
    // applied implicitly — no `applyDefaultHierarchyTemplate { }` block. For these
    // targets it yields commonMain → nativeMain → appleMain → {iosMain, macosMain},
    // plus jvmMain / androidMain siblings. appleMain holds the POSIX multicast
    // socket both Apple platforms share 1:1 (platform.posix bindings); iosMain /
    // macosMain hold any platform-only remainder. Declaring any manual
    // dependsOn() edge disables the template.

    // --- Apple targets (CLAUDE.md §1) ---------------------------------------
    // Static framework binaries with a stable bundle id. In `:ssdp`, KMMBridge
    // aggregates these into `Ssdp.xcframework` at config time (no explicit
    // XCFramework declaration — see ssdp/build.gradle.kts).
    listOf(iosArm64(), iosSimulatorArm64(), macosArm64()).forEach { target ->
        target.binaries.framework {
            baseName = frameworkBaseName
            isStatic = true
            binaryOption("bundleId", moduleNamespace)
        }
    }

    // --- Android target (CLAUDE.md §1, §4) ----------------------------------
    // The new com.android.kotlin.multiplatform.library plugin's android {} block.
    //
    // CLAUDE.md §1: arm64-v8a only. The new KMP Android plugin doesn't wire ABI
    // filters directly; consumers' app modules pin the splits. We test
    // arm64-v8a only; documented in README.
    android {
        namespace = moduleNamespace
        compileSdk =
            libs
                .findVersion("android-compile-sdk")
                .get()
                .requiredVersion
                .toInt()
        minSdk =
            libs
                .findVersion("android-min-sdk")
                .get()
                .requiredVersion
                .toInt()

        withHostTestBuilder { /* enables the androidHostTest source set */ }

        // Explicit, never inherited. Left unset, AGP wires this target's
        // jvmTarget to the JDK running the build — so building on a newer JDK
        // would silently ship newer bytecode in the AAR. (This target is not a
        // KotlinJvmTarget, so a `targets.withType<KotlinJvmTarget>()` block
        // never reaches it — LESSONS N-007.)
        compilerOptions {
            jvmTarget.set(jvmBytecodeTarget)
        }
    }

    // --- JVM target (desktop / server / Linux / Windows) --------------------
    // Architecture-neutral bytecode — the one target where the ARM-only rule
    // has nothing to say. SSDP discovery runs over java.net.MulticastSocket in
    // jvmMain. No SKIE, no KMMBridge — the JVM ships through Maven Central only,
    // like Android.
    jvm {
        compilerOptions {
            jvmTarget.set(jvmBytecodeTarget)
        }
    }

    // --- Compiler options (CLAUDE.md §2, §3) ---------------------------------
    compilerOptions {
        // K2 stable APIs only (CLAUDE.md §3).
        languageVersion.set(KotlinVersion.KOTLIN_2_3)
        apiVersion.set(KotlinVersion.KOTLIN_2_3)
        allWarningsAsErrors.set(true)
    }

    // --- Public-API / ABI validation (CLAUDE.md §8) -------------------------
    // The Kotlin Gradle plugin's built-in ABI validation tracks the public API
    // surface across ALL targets (JVM + KLib/native) in one checked-in dump
    // under <module>/api/. `checkKotlinAbi` (wired into `check`, so it runs in
    // CI and `mise run check`) fails if the public surface changes without an
    // explicit `mise run api:dump` — so breaking changes to these published
    // libraries are always deliberate and reviewed.
    //
    // When the host can't compile every target (e.g. a Linux box can't build the
    // Apple slices), the plugin infers their ABI from the prior dump instead of
    // failing — so the checked-in dump stays complete. The Apple-target ABI is
    // verified on the macOS leg of CI, which can build those slices.
    //
    // Scoped to `:ssdp` only — NOT `:ssdp-testing`. The testing module is test
    // infrastructure (FakeSsdpClient + withFakeSsdpClient); its surface is
    // expected to churn alongside the tests that use it, and locking it behind a
    // committed dump would add ceremony (api:dump on every test-helper tweak)
    // without the consumer-stability payoff that justifies ABI tracking for the
    // real library. `:ssdp-testing` re-exports `:ssdp`'s already-locked API, so
    // the surface consumers actually depend on is still covered.
    // Kotlin 2.4.0 changed the enable model: calling `abiValidation { }` IS the
    // enable (the old `enabled` property is deprecated). To keep `:ssdp-testing`
    // OUT of ABI validation we simply DON'T invoke the block for it — the shared
    // convention plugin conditions on the module name.
    if (name != "ssdp-testing") {
        @OptIn(ExperimentalAbiValidation::class)
        abiValidation {
            // Defaults are fine: tracks the public API across all targets into
            // <module>/api/. `checkKotlinAbi` joins `check`.
        }
    }
}

skie {
    // SKIE handles the Kotlin → Swift bridge enhancements (CLAUDE.md §8):
    // exhaustive sealed switching, suspend → async/await, Flow → AsyncSequence,
    // default-arg overloads. All feature defaults stay on; tighten only when
    // something bites.
    analytics {
        // Disable opt-in analytics; we'll revisit if useful.
        disableUpload.set(true)
    }
    // Prevent SKIE from copying bundled Swift sources into the klib. Both
    // modules may ship hand-written Swift sweeteners whose `extension` is only
    // valid inside the module where the type keeps its short swift_name; if
    // bundled into the klib, SKIE unpacks and recompiles them in downstream
    // modules where the type is module-prefixed, causing a compile error. With
    // bundling disabled, SKIE still compiles the Swift sources into each
    // framework binary via its own compile task.
    swiftBundling {
        enabled.set(false)
    }
}
