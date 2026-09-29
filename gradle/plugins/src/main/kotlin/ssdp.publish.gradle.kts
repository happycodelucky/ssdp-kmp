/*
 * Convention plugin: Maven Central publishing for ssdp-kmp modules
 * (CLAUDE.md §9).
 *
 * All ssdp-kmp modules publish together — same group, same version (inherited
 * from `allprojects { version = … }` in the root build script), same signing /
 * release pipeline, same POM metadata except name and description. This plugin
 * is that lockstep, as structure instead of "keep these blocks in sync"
 * comments. The artifactId is the project name; module build scripts contribute
 * only `pom { name / description }`.
 *
 * One Gradle invocation per module publishes the Android AAR, the
 * `kotlinMultiplatform` metadata module, per-target klibs (including the jvm
 * jar), and sources / javadoc jars — each with a detached GPG signature. KMP
 * consumers add `mavenCentral()` and depend on the coordinate from
 * `commonMain`; Gradle resolves the right per-target artifact automatically.
 *
 * Credentials: vanniktech reads `mavenCentralUsername`, `mavenCentralPassword`,
 * `signingInMemoryKey`, and `signingInMemoryKeyPassword` as Gradle properties.
 * Gradle auto-populates those from `ORG_GRADLE_PROJECT_*` env vars in CI; the
 * release workflow wires the four `MAVEN_CENTRAL_*` GitHub Actions secrets to
 * those env names. Locally these properties are unset and signing is silently
 * skipped — fine for `publishToMavenLocal` dry-runs.
 *
 * Every jar and the AAR also carry llms.txt + llms-full.txt (LlmsTxt.kt) under
 * META-INF/<groupId>/<artifactId>/ — the artifact's own docs for AI tools.
 */

plugins {
    id("com.vanniktech.maven.publish")
}

// Capture before the extension lambdas below, where `name` would resolve to
// the receiver's own `name` property (e.g. MavenPomLicense.name).
val moduleArtifactId = project.name
val moduleGroupId = "com.happycodelucky.ssdp"
val repositoryUrl = "https://github.com/happycodelucky/ssdp-kmp"

mavenPublishing {
    // Targets the Central Portal (central.sonatype.com) — NOT the legacy
    // s01.oss.sonatype.org OSSRH endpoint, which Sonatype is decommissioning.
    //
    // `automaticRelease = false` is intentional and load-bearing. It controls
    // what `./gradlew publishToMavenCentral` does:
    //   * `false` — uploads to the Central Portal staging area and stops. The
    //     deployment sits in "validated" state until someone clicks Publish (or
    //     Drop) in the Portal web UI. This is what makes the release workflow's
    //     `dryRun=true` branch an actual dry run.
    //   * `true` — uploads *and* auto-releases on success. Every "dry run"
    //     becomes an irreversible public publish. Do NOT flip this without
    //     understanding the cascade in `.github/workflows/release.yml`.
    publishToMavenCentral(automaticRelease = false)

    // Required by Central — every artifact must carry a detached GPG signature.
    signAllPublications()

    coordinates(
        groupId = moduleGroupId,
        artifactId = moduleArtifactId,
        version = project.version.toString(),
    )

    pom {
        // `name` and `description` are the module build script's job.
        url.set(repositoryUrl)
        inceptionYear.set("2026")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("happycodelucky")
                name.set("Paul Bates")
                url.set("https://github.com/happycodelucky")
            }
        }
        scm {
            url.set(repositoryUrl)
            connection.set("scm:git:https://github.com/happycodelucky/ssdp-kmp.git")
            developerConnection.set("scm:git:ssh://git@github.com/happycodelucky/ssdp-kmp.git")
        }
    }
}

// --- llms.txt in the artifacts (LlmsTxt.kt) --------------------------------
// Generated from this module's Dokka Markdown on every build that PUBLISHES —
// publishToMavenCentral in release.yml, publish:maven, publish:local — so the
// files always describe the exact version they ship in. Only then: the build
// hands these jars to its own consumers too (:ssdp-testing, :cli, :ssdp-bridge,
// :androidApp), and packing llms files into them would make every check/test run Dokka.
//
// Namespaced by coordinates, like Maven's own META-INF/maven/<g>/<a>/: a bare
// META-INF/llms.txt from two libraries collides on a consumer's classpath and
// FAILS their Android packaging ("More than one file was found with OS
// independent path"). In the AAR the pair sits at the archive root, beside
// AGP's META-INF/com/android/…, never in classes.jar — so it doesn't end up
// in every consumer's APK.
apply<DokkaMarkdownPlugin>()

val publications = the<PublishingExtension>().publications

val generateLlmsTxt = tasks.register<GenerateLlmsTxt>("generateLlmsTxt") {
    group = "documentation"
    description = "Writes llms.txt + llms-full.txt (this module's public API as Markdown) for its artifacts."
    apiMarkdown.set(
        tasks.named<org.jetbrains.dokka.gradle.tasks.DokkaGenerateTask>("dokkaGeneratePublicationMarkdown")
            .flatMap { it.outputDirectory },
    )
    // The POM's name/description, which the module's build script sets.
    val pom = provider { publications.getByName<MavenPublication>("kotlinMultiplatform").pom }
    title.set(pom.flatMap { it.name })
    summary.set(pom.flatMap { it.description })
    coordinates.set("$moduleGroupId:$moduleArtifactId:${project.version}")
    repoUrl.set(repositoryUrl)
    outputDirectory.set(layout.buildDirectory.dir("llms"))
}

// jvmJar, the root publication's allMetadataJar, each Apple target's
// host-specific -metadata.jar (KGP's <target>MetadataElements Jar task), every
// <target>SourcesJar and the root sourcesJar (a klib carries no resources, so
// these jars are where a Kotlin/Native target holds them), the javadoc jars,
// and the AAR (bundle<Variant>Aar). `mise run llms:check` catches any missed.
val publishes = gradle.startParameter.taskNames.any { "publish" in it.lowercase() }
tasks.withType<Zip>().configureEach {
    val ships = name == "jvmJar" ||
        name == "allMetadataJar" ||
        name.endsWith("MetadataElements") ||
        name == "sourcesJar" ||
        name.endsWith("SourcesJar") ||
        name.endsWith("JavadocJar", ignoreCase = true) ||
        (name.startsWith("bundle") && name.endsWith("Aar"))
    if (publishes && ships) {
        from(generateLlmsTxt) { into("META-INF/$moduleGroupId/$moduleArtifactId") }
    }
}
