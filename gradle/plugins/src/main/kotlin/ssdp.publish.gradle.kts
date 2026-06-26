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
 */

plugins {
    id("com.vanniktech.maven.publish")
}

// Capture before the extension lambdas below, where `name` would resolve to
// the receiver's own `name` property (e.g. MavenPomLicense.name).
val moduleArtifactId = project.name

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
        groupId = "com.happycodelucky.ssdp",
        artifactId = moduleArtifactId,
        version = project.version.toString(),
    )

    pom {
        // `name` and `description` are the module build script's job.
        url.set("https://github.com/happycodelucky/ssdp-kmp")
        inceptionYear.set("2026")

        licenses {
            license {
                name.set("Apache License 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
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
            url.set("https://github.com/happycodelucky/ssdp-kmp")
            connection.set("scm:git:https://github.com/happycodelucky/ssdp-kmp.git")
            developerConnection.set("scm:git:ssh://git@github.com/happycodelucky/ssdp-kmp.git")
        }
    }
}
