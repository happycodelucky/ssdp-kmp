/*
 * ssdp-kmp — root build script.
 *
 * Plugins are declared here with `apply false`; they're applied in :ssdp and
 * :ssdp-testing (mostly via the `ssdp.kmp-library` convention plugin). This
 * keeps `gradle/libs.versions.toml` as the single source of truth for versions
 * (CLAUDE.md §10).
 */

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.skie) apply false
    alias(libs.plugins.kmmbridge.github) apply false
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
    // gradle-doctor warns on slow config, JVM mismatches, and cache misses on
    // every build (mise build:doctor surfaces its diagnostics explicitly).
    alias(libs.plugins.dependency.analysis)
    alias(libs.plugins.gradle.doctor)
}

allprojects {
    group = "com.happycodelucky.ssdp"
    // The in-tree version carries `-SNAPSHOT` and a `0` patch slot. Humans bump
    // major/minor here and commit the change; the patch slot stays `0`. CI
    // overrides this at build time via `-Pversion=...` to stamp ephemeral
    // patches (run numbers for CI builds, exact `vX.Y.Z` for releases) without
    // ever committing the override back.
    version = providers.gradleProperty("version").getOrElse("0.1.0-SNAPSHOT")
}

// Gradle Doctor — build-health diagnostics. mise owns the JDK (via the [tools]
// pins), so its JAVA_HOME checks are advisory here, not fatal: a fresh
// `git clone && mise run check` must never fail on a tool's environment opinion.
// The remaining checks (slow config, negative-avoidance, cache misuse) stay on.
doctor {
    javaHome {
        // mise puts the right JDK on PATH; JAVA_HOME may be unset. Warn, don't fail.
        ensureJavaHomeIsSet.set(false)
        ensureJavaHomeMatches.set(false)
        failOnError.set(false)
    }
    // Don't fail a build just because another Gradle daemon is alive (common
    // when an IDE holds one open alongside a terminal build).
    disallowMultipleDaemons.set(false)
}

// dependency-analysis (`mise run dependencies:analyze` → buildHealth). The
// project plugin is applied per published KMP module in `subprojects {}` below —
// currently GATED OFF (see that block: DAGP 3.16.0 can't read Kotlin 2.4.0
// metadata, issue #1724). This `dependencyAnalysis { }` extension is registered
// by the root plugin (still in `plugins {}`), so the config is valid regardless
// of the gate and is ready the moment analysis is enabled.
//
// KMP analysis at dependency-analysis 3.16.0 is real but noisy on modules with
// SHARED (hierarchical) source sets — a dependency declared once in commonMain
// is *visible* in the jvm/android/apple leaf sets, and the plugin's per-leaf
// analysis emits advice that contradicts the common-set declaration. Two whole
// categories are unreliable here and are silenced; a third is narrowed:
//
//   * usedTransitiveDependencies → ignore. The plugin wants ktor-client-core's
//     internal artifacts (ktor-http/ktor-io) and kermit's (kermit-core), plus
//     the :ssdp project dep, "declared directly" in every leaf set. These are
//     deliberately-curated single deps; splitting them into transitive internals
//     would churn the catalog (CLAUDE.md §3). Always wrong here.
//   * incorrectConfiguration → ignore. The api-vs-implementation suggestions
//     (kotlinx.coroutines.core, androidx.startup.runtime) and the
//     coroutines-android → runtimeOnly suggestion are the known KMP `api`
//     over-suggestion (DAGP issue #1700) + intentional design — we keep these as
//     `implementation`.
//   * unusedDependencies → kept at `warn` (so a genuinely-unused NEW dependency
//     still surfaces) but the project refs + kotest are excluded: FakeSsdpClient
//     and the kotest property arbs ARE used in commonTest/jvmTest/androidHostTest,
//     but DAGP under-detects cross-source-set test usage on KMP (issue #1345).
//
// When enabled, buildHealth analyzes :ssdp / :ssdp-testing for real and (with
// this allowlist) reports EMPTY — a tripwire for genuinely-new problems rather
// than a wall of known-false advice. Currently gated off for Kotlin 2.4.0; when
// re-enabled, re-run buildHealth and confirm the exclude list still matches.
dependencyAnalysis {
    issues {
        all {
            onUsedTransitiveDependencies {
                severity("ignore")
            }
            // implementation↔api swaps. The api over-suggestion on
            // kotlinx.coroutines.core and androidx.startup.runtime is the known
            // KMP DAGP issue #1700 — we keep these as `implementation` by design.
            onIncorrectConfiguration {
                severity("ignore")
            }
            // compile→runtimeOnly downgrades (a SEPARATE handler from
            // onIncorrectConfiguration — verified against DAGP 3.16.0's DSL).
            // DAGP flags coroutines-android runtime-only because no compile-time
            // symbol is referenced — the Android MainDispatcherFactory loads via
            // ServiceLoader at runtime. Keeping it `implementation` is the
            // conventional Android + coroutines wiring.
            onRuntimeOnly {
                severity("ignore")
            }
            onUnusedDependencies {
                exclude(
                    // FakeSsdpClient + the kotest property arbs ARE used in
                    // commonTest/jvmTest/androidHostTest, but DAGP under-detects
                    // cross-source-set test usage on KMP (issue #1345).
                    ":ssdp",
                    ":ssdp-testing",
                    "io.kotest:kotest-assertions-core",
                )
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
        // GATED OFF by default. dependency-analysis 3.16.0 bundles a
        // kotlin-metadata-jvm that cannot read Kotlin 2.4.0's bytecode metadata
        // (format 2.4.0 > its max 2.3.0): applying it makes `explodeJar*` — and
        // therefore `buildHealth` — HARD-FAIL, not merely report noise. Upstream
        // issue: https://github.com/autonomousapps/dependency-analysis-gradle-plugin/issues/1724
        // (open as of 2026-06). Flip on with `-PenableDependencyAnalysis=true`
        // once DAGP ships a Kotlin-2.4.0-compatible release (then also re-enable
        // the `dependencies:analyze` step in .github/workflows/ci.yml and verify
        // the exclude list below still matches this repo's graph). Until then the
        // wiring stays inert so CI is green rather than red. This mirrors the
        // sibling kmp-template, which is on the same Kotlin/DAGP versions.
        if (providers.gradleProperty("enableDependencyAnalysis").orNull == "true") {
            apply(plugin = "com.autonomousapps.dependency-analysis")
        }
    }

    plugins.withId("org.jlleitschuh.gradle.ktlint") {
        configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
            version.set(libs.versions.ktlint.get())
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
