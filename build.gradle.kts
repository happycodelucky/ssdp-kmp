/*
 * ssdp-kmp — root build script.
 *
 * Plugins are declared here with `apply false`; they're applied in :ssdp and
 * :ssdp-testing (mostly via the `ssdp.kmp-library` convention plugin). This
 * keeps `gradle/libs.versions.toml` as the single source of truth for versions
 * (CLAUDE.md §3).
 */

import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import nl.littlerobots.vcu.plugin.versionSelector

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.skie) apply false
    alias(libs.plugins.kmmbridge.github) apply false
    // vanniktech Maven Central publish. Applied in :ssdp / :ssdp-testing via the
    // `ssdp.publish` convention plugin; declared here `apply false` so its classes
    // (notably the shared MavenCentralBuildService) load once into the root
    // classloader scope. Without this, each sibling loads its own copy and the
    // cross-project build-service reference fails to type-check under parallel +
    // configuration-cache (`prepareMavenCentralPublishing` buildService conflict).
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false

    // Dokka v2: Kotlin API doc generator. Produces HTML for the public API of
    // every source set. The HTML is copied into docs/api/ for mkdocs to bundle.
    alias(libs.plugins.dokka)

    // Dependency-update tooling (mise dependencies:outdated / dependencies:update).
    // ben-manes reports updates; version-catalog-update rewrites libs.versions.toml.
    alias(libs.plugins.ben.manes.versions)
    alias(libs.plugins.version.catalog.update)

    // Build-health tooling. dependency-analysis adds the root `buildHealth` task
    // (mise dependencies:analyze) — unused/misused/transitive dependency advice.
    alias(libs.plugins.dependency.analysis)
}

allprojects {
    group = "com.happycodelucky.ssdp"
    // `version` lives in gradle.properties: the last version released from
    // main, bumped only by the release PR (scripts/changeset.py). CI stamps
    // non-release builds with `-Pversion=…-ci.N`; a pre-release passes its own
    // `-Pversion`. Nothing ever writes an override back.
    version = providers.gradleProperty("version").get()
}

// dependency-analysis (`mise run dependencies:analyze` → buildHealth). The
// project plugin is applied per published KMP module in the `subprojects {}`
// block below.
//
// KMP analysis is real but still noisy on modules with SHARED (hierarchical)
// source sets — a dependency declared once in commonMain is *visible* in the
// jvm/android/apple leaf sets, and the plugin's per-leaf analysis emits advice
// that contradicts the common-set declaration. The tuning below was derived by
// RUNNING buildHealth on this repo at DAGP 3.19.2, not copied forward:
//
//   * usedTransitiveDependencies → ignore. Structurally wrong on hierarchical
//     KMP: it wants curated single deps (ktor-client-core's internals, kermit's
//     kermit-core, the :ssdp project dep) re-declared in every leaf set.
//     Splitting them into transitive internals would churn the catalog.
//   * incorrectConfiguration → warn. Its api-vs-implementation advice was real
//     here (SsdpClient exposes StateFlow/SharedFlow; SsdpInitializer implements
//     androidx.startup's Initializer) and is fixed — those are now `api`.
//   * runtimeOnly → warn, with ONE exclusion: coroutines-android is
//     compile-invisible because its MainDispatcherFactory loads via
//     ServiceLoader — kept as the conventional `implementation`.
//   * unusedDependencies → warn, no exclusions. The old list excluded
//     :ssdp-testing and kotest-assertions-core from :ssdp's tests as DAGP
//     "under-detection"; buildHealth shows they really are unused there.
//
// Warnings only — buildHealth exits 0, so CI surfaces the advice without gating.
dependencyAnalysis {
    issues {
        all {
            onUsedTransitiveDependencies {
                severity("ignore")
            }
            onIncorrectConfiguration {
                severity("warn")
            }
            // compile→runtimeOnly downgrades (a SEPARATE handler from
            // onIncorrectConfiguration).
            onRuntimeOnly {
                severity("warn")
                exclude("org.jetbrains.kotlinx:kotlinx-coroutines-android")
            }
            onUnusedDependencies {
                severity("warn")
            }
        }
    }
}

subprojects {
    // ktlint + detekt wire onto the KMP plugin — i.e. onto the published
    // library modules only. CLAUDE.md §3: "ktlint + detekt must pass."
    //
    // Deliberate scope: the sample apps (`:androidApp` here; /apps/ios and
    // /apps/macos outside this Gradle build) are demo scaffolding, not shipped
    // code, and are intentionally excluded from Kotlin lint and from CI's
    // check task.
    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
        apply(plugin = "io.gitlab.arturbosch.detekt")
        // dependency-analysis's project plugin does NOT auto-apply from the root
        // `plugins {}` block (only the `com.autonomousapps.build-health` settings
        // plugin fans out) — without a per-module apply, `buildHealth` runs but
        // analyzes ZERO projects ("No project health reports found"). Applying it
        // to the published KMP modules only (alongside ktlint/detekt) makes
        // buildHealth actually inspect `:ssdp` / `:ssdp-testing`; the advice is
        // then tuned in the root `dependencyAnalysis { }` block below.
        //
        // This used to be gated behind `-PenableDependencyAnalysis=true`: DAGP
        // 3.16.0 bundled a kotlin-metadata-jvm that could not read Kotlin 2.4.0
        // bytecode metadata, so applying it made `explodeJar*` — and therefore
        // `buildHealth` — HARD-FAIL. Fixed in 3.18.0, which isolates
        // kotlin-metadata-jvm into workers
        // (autonomousapps/dependency-analysis-gradle-plugin#1724). The gate is
        // gone and CI runs `dependencies:analyze` again.
        apply(plugin = "com.autonomousapps.dependency-analysis")
    }

    plugins.withId("org.jlleitschuh.gradle.ktlint") {
        configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
            version.set(libs.versions.ktlint.cli.get())
            android.set(false)
            outputToConsole.set(true)
            ignoreFailures.set(false)
            filter {
                exclude { element -> element.file.path.contains("/build/generated/") }
                exclude("**/build/**")
                exclude("**/generated/**")
            }
        }

        tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask>().configureEach {
            exclude { element -> element.file.path.contains("/build/generated/") }
        }
    }

    plugins.withId("io.gitlab.arturbosch.detekt") {
        configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
            buildUponDefaultConfig = true
            // Project overrides layered on the defaults: rules that systematically
            // misfire on network-protocol/parser code are tuned in config/detekt
            // (LongParameterList for UPnP data carriers, MagicNumber for
            // byte-order math, TooGenericExceptionCaught for resilient I/O, etc.).
            // Each override is documented in that file.
            config.setFrom(rootProject.files("config/detekt/detekt.yml"))
            // detekt's default source resolution only knows JVM layouts
            // (src/main/kotlin); point it at the module root so every KMP
            // source set (commonMain, appleMain, jvmMain, androidHostTest, …)
            // is scanned. The task itself filters to *.kt, and build/ output is
            // excluded by default.
            source.setFrom(files("src"))
        }
    }
}

// Apply Dokka to the published modules and aggregate into docs/api/.
dokka {
    moduleName.set("Ssdp")
}

dependencies {
    // Aggregate Dokka HTML from the published modules into the root build
    // (Dokka v2 pattern). `:ssdp-testing` is a public-API module too — consumers
    // writing tests want the FakeSsdpClient / withFakeSsdpClient surface
    // documented next to the main library.
    dokka(project(":ssdp"))
    dokka(project(":ssdp-testing"))
}

/**
 * Copies Dokka v2 HTML output into docs/api/, where mkdocs picks it up.
 *
 * The aggregated HTML lives at build/dokka/html after
 * dokkaGeneratePublicationHtml. mkdocs looks at docs/api/ when it builds the
 * site; CI runs Dokka before mkdocs.
 */
tasks.register<Copy>("copyDokkaToDocs") {
    group = "documentation"
    description = "Copies aggregated Dokka HTML into docs/api/ for mkdocs."

    dependsOn("dokkaGeneratePublicationHtml")
    from(layout.buildDirectory.dir("dokka/html"))
    into(layout.projectDirectory.dir("docs/api"))
}

// Stable-only dependency updates (CLAUDE.md §3: no EAP/RC/Beta on main).
// `-Drevision=release` only chooses which Maven metadata channel ben-manes
// reads — it does NOT reject versions whose string is a pre-release, so without
// this rule `dependencyUpdates` happily suggests 1.5.0-alpha22 over 1.4.0. The
// `stableVersion` predicate below is handed to BOTH plugins: `rejectVersionIf`
// on ben-manes (`dependencies:outdated`, the report) and `versionSelector` on
// version-catalog-update (`dependencies:update`, the rewrite). VCU has resolved
// versions itself since 1.0 — it no longer reads the ben-manes report — and its
// built-in default selector uses a DIFFERENT stability rule, so it must be given
// this one explicitly to stay in lockstep (LESSONS N-010).
//
// A version is STABLE only if it is digits-and-dots and nothing else. Accepts:
// 1.2.3, 2026.06.01. Rejects anything carrying a qualifier (-alpha/-beta/-rc/
// -eap/-m1/-snapshot/…), because a qualifier necessarily introduces a non-digit,
// non-dot character. That whitelist IS the whole stability test; if you ever
// loosen it (e.g. to allow a `-jre` classifier), add a qualifier check back.
val stableVersion = "^[0-9][0-9.]*$".toRegex()

tasks.withType<DependencyUpdatesTask>().configureEach {
    // Read the stable release channel, not integration/milestone metadata.
    revision = "release"
    rejectVersionIf {
        // Only candidate upgrades pass through here; the current version is never
        // rejected.
        !stableVersion.matches(candidate.version)
    }
}

versionCatalogUpdate {
    versionSelector { stableVersion.matches(it.candidate.version) }
    // Keep the catalog's hand-grouped sections (Toolchain, kotlinx, Testing, …)
    // instead of alphabetizing them.
    sortByKey.set(false)
    keep {
        // Keys no library/plugin references: android-compile-sdk, android-min-sdk
        // and jvm-target (read via the string-based findVersion("…") API in the
        // convention plugin, invisible to VCU's usage scan), and the Apple
        // deployment targets (documentation for the floors spelled out in
        // ssdp/build.gradle.kts and Package.swift). Without this, VCU prunes them.
        keepUnusedVersions.set(true)
    }
    pin {
        // Kotlin is bounded above by SKIE (CLAUDE.md §3): a Kotlin bump is a
        // manual, SKIE-paired change — the same policy renovate.json5 encodes.
        // Pinning the `kotlin` ref also holds the compose-compiler plugin, which
        // versions in lockstep with it.
        versions.add("kotlin")
    }
}
