# Publishing

Ssdp ships via two independent channels from `.github/workflows/release.yml`:

- **Maven Central** — Android AAR, `kotlinMultiplatform` metadata, per-target klibs. For Gradle/KMP consumers.
- **GitHub Releases** (via KMMBridge) — the SKIE-enhanced `SsdpKit.xcframework` zip. For pure-Swift SPM consumers.

## Maven Central

**Coordinates:** `com.happycodelucky.ssdp:ssdp`

One Gradle invocation publishes:

- The Android AAR.
- The `kotlinMultiplatform` metadata module (`.module` file) that ties every target together.
- Per-target klibs: `ssdp-iosarm64`, `ssdp-iossimulatorarm64`, `ssdp-macosarm64`, `ssdp-android`.
- Sources / javadoc jars next to each, with detached GPG signatures.

The test-fakes module publishes alongside it under `com.happycodelucky.ssdp:ssdp-testing`.

## Release pipeline

Releases are driven by **changesets**: each PR describes its change in a small
Markdown file, and the version and changelog are computed from them. Nobody
picks a version number by hand or runs a release workflow for a normal release.

```mermaid
flowchart LR
    PR["PR + .changeset/*.md"] -->|merge| main
    main -->|Release PR workflow| RPR["Release vX.Y.Z PR<br/>(release/next)"]
    RPR -->|merge| main2["main: version=X.Y.Z"]
    main2 -->|Release workflow| out["Maven Central<br/>GitHub Release + SPM tag"]
```

1. **Every PR adds a changeset** — `mise run changeset` writes
   `.changeset/<branch>.md` with a `title`, a `change` level
   (`major`/`minor`/`patch` — the source of truth for the version, and the
   author's call) and a `description`, followed by the full note in Markdown in
   place of an *Unfilled* callout the check refuses to let through. The **Changeset** check (`changeset.yml`) fails a PR without one;
   label it `no-changeset` if nothing in it reaches consumers. Format and rules:
   [`.changeset/README.md`](../.changeset/README.md).
2. **One rolling release PR.** On every push to `main` with changesets
   pending, `release-pr.yml` rebuilds the `release/next` branch from `main` and
   opens or updates a **Release vX.Y.Z** PR. `scripts/changeset.py version`
   picks the version (the highest `change` level, bumped from `version=` in
   `gradle.properties`; while 0.x a `major` bumps the minor), rewrites
   `gradle.properties` and every version marked `x-release-version` (e.g. the
   README's install snippets), inserts the release into `CHANGELOG.md`, and
   deletes the consumed changesets. Later merges fold into the same PR.
3. **Merging the release PR publishes it.** Its version bump lands on `main`;
   `release.yml` sees `version=` change on a push to `main` and releases exactly
   that version — Maven Central first (irreversible), then the XCFramework to a
   GitHub Release plus the `vX.Y.Z` tag. `main` then *is* the release: its version, README and
   changelog already match what was published.

To leave 0.x (or pin any exact version), add `version: 1.0.0` to a
changeset's front matter.

### Pre-releases and retries (manual runs)

Run **Release** from the Actions tab (`workflow_dispatch`):

| `version` | Does |
|---|---|
| *(empty)* | Releases `gradle.properties`' version from `main` — retries a release that failed. |
| `0.4.0-rc.1` | A pre-release, from **any branch**. Marked pre-release on GitHub; notes are the changesets pending on that branch |
| `0.4.0` | Must equal `gradle.properties`' version on `main` — stable versions come only from release PRs, so the changelog, `main` and Maven Central can't disagree. |

`dryRun` defaults to **true** for manual runs: it uploads to the Central Portal
staging area only (`publishToMavenCentral`). Review the deployment at
https://central.sonatype.com/ and click Publish (or Drop). Nothing is tagged
and no XCFramework is published. Merging a release PR is always a real
release.

**If a release fails part-way**, re-run the failed jobs from the Actions UI (a
re-run replays the same commit). The release job resumes: a version already on
Maven Central skips straight to the GitHub/SPM half; a version that already has
a GitHub Release is refused.

The `automaticRelease = false` flag in the publish convention plugin
(`gradle/plugins/…publish.gradle.kts`) is what makes dry-run behaviour correct.
Do not flip it without reading the comment there.

### One-time setup for the release PR

A push or PR made with the workflow's `GITHUB_TOKEN` triggers no other
workflows, so CI wouldn't run on the release PR by itself. Pick one:

- **Default (`GITHUB_TOKEN`)** — enable **Settings → Actions → General →
  Allow GitHub Actions to create and approve pull requests**. `release-pr.yml`
  then dispatches CI and the Changeset check on `release/next` itself; their
  results show on the PR.
- **GitHub App** — create an App with *Contents* and *Pull requests*
  read/write, install it on the repo, and set the `RELEASE_APP_CLIENT_ID`
  variable and `RELEASE_APP_PRIVATE_KEY` secret. The release PR is then
  authored by the App and CI triggers normally. (Prefer an App to a personal
  token: a PR opened as you can't be approved by you.)

If `main` requires status checks, add **Changeset** alongside CI's jobs.

## Releasing by hand (`mise run publish:maven`)

The CI flow above is the canonical path. `mise run publish:maven`
(→ `scripts/release.sh`) runs the same steps from your machine, under the same
version rules — `gradle.properties`' version by default, `--version` only for a
pre-release:

```bash
mise run publish:maven --dryrun                    # stage gradle.properties' version to Central only
mise run publish:maven                             # finish a release CI couldn't (prompts to confirm)
mise run publish:maven --version 0.4.0-rc.1        # a pre-release, from any branch
```

- **`--dryrun`** — runs `publishToMavenCentral` (Central staging only). Nothing
  is committed, tagged, or released. Safe to run repeatedly.
- **Real release** — after a typed confirmation (it echoes the plan first;
  Maven Central is irreversible), it: builds the XCFramework and writes
  `Package.swift` in its released remote-binary form (URL + checksum), runs
  `publishAndReleaseToMavenCentral`, tags `vX.Y.Z` on a release commit carrying
  that `Package.swift` (never pushed to a branch), and runs `gh release create`
  with the XCFramework zip and the changelog's notes.

Requires a clean tree, an authenticated `gh`, and the Maven Central credentials configured (next section).

## Credentials

The vanniktech plugin reads **four Gradle properties**. It doesn't care where they come from — Gradle resolves a property `foo` from a `-Pfoo=` flag, an `ORG_GRADLE_PROJECT_foo` env var, or a `gradle.properties` file (CLI → env → `~/.gradle` → project). That's why the same setup works in CI and locally.

| Property | What it is | Where to get it |
|---|---|---|
| `mavenCentralUsername` | Central Portal **token** username (not your login) | [central.sonatype.com](https://central.sonatype.com/) → Account → Generate User Token |
| `mavenCentralPassword` | Central Portal token password | same token |
| `signingInMemoryKey` | ASCII-armored **GPG private key** block | `gpg --armor --export-secret-keys <KEY_ID>` (the whole `-----BEGIN…END PGP PRIVATE KEY BLOCK-----`) |
| `signingInMemoryKeyPassword` | The GPG key's passphrase | what you set when creating the key |

Your GPG public key must be published to a keyserver Central checks (e.g. `gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>`), or Central rejects the signatures.

### CI

Set four secrets on the **`continuous-deployment` GitHub environment** (repo Settings → Environments → `continuous-deployment` → Secrets — *not* repository-scoped secrets; `release.yml` binds to that environment):

| GitHub secret | Maps to property |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | `mavenCentralUsername` |
| `MAVEN_CENTRAL_PASSWORD` | `mavenCentralPassword` |
| `MAVEN_CENTRAL_SIGNING_KEY` | `signingInMemoryKey` |
| `MAVEN_CENTRAL_SIGNING_KEY_PASSWORD` | `signingInMemoryKeyPassword` |

`release.yml` exports them as `ORG_GRADLE_PROJECT_*` env vars, which Gradle maps onto the property names above.

### Local (`mise run publish:maven`)

Put them in **`~/.gradle/gradle.properties`** (your home directory — **never** the repo, and never a committed `gradle.properties`; these are secrets):

```properties
mavenCentralUsername=<token-username>
mavenCentralPassword=<token-password>
signingInMemoryKeyPassword=<gpg-passphrase>
# Paste the armored key as a single line with literal \n between lines, or use
# the file-based form: signingInMemoryKeyFile=/absolute/path/to/secret-key.asc
signingInMemoryKey=-----BEGIN PGP PRIVATE KEY BLOCK-----\n…\n-----END PGP PRIVATE KEY BLOCK-----
```

Or export the matching `ORG_GRADLE_PROJECT_*` env vars in your shell instead. `mise run publish:maven` checks they're present before it builds and fails fast with this guidance if not. A `--dryrun` still needs the signing key (Central validates signatures even in staging).

## SPM distribution — KMMBridge → GitHub Releases

Touchlab's KMMBridge publishes the Apple framework to pure-Swift SPM consumers. The pipeline (real publishes only, not dry-run):

1. Gradle builds an `XCFramework` with `iosArm64` + `iosSimulatorArm64` + `macosArm64` slices. No x86. SKIE-enhanced (`produceDistributableFramework()` emits `.swiftinterface` files required by Xcode 26).
2. KMMBridge zips the XCFramework and uploads it as a GitHub Release asset. GitHub *Releases*, not GitHub *Packages* — Packages requires a PAT to download even from public repos; Release assets are public and unauthenticated.
3. KMMBridge regenerates the root `Package.swift` referencing the asset by URL + sha256 checksum. The workflow rewrites KMMBridge's API asset URL to the public `releases/download/…` form, commits `Package.swift` on a detached **release commit**, and force-moves the version tag onto it so the tagged manifest matches the uploaded binary.
4. Swift consumers add this repo's URL as an SPM dependency pinned to a version tag; the tagged `Package.swift` hands them the prebuilt binary.

The release commit lives **only on its tag**. `main` is branch-protected (a bot
push is rejected) and doesn't need it: `main` keeps the local-dev
`Package.swift` the sample apps build against, and a consumer pinned to
`branch: "main"` isn't a supported way to consume a binary target.

### Rules

- KMMBridge config lives in the `kmmbridge { }` block in `ssdp/build.gradle.kts`; the version pin lives in `gradle/libs.versions.toml`. Only `:ssdp` gets KMMBridge — `:ssdp-testing` ships klibs via Maven Central only.
- Do **not** redeclare `XCFramework("SsdpKit")` in the `kotlin { }` block: KMMBridge auto-creates the aggregator tasks (`assembleSsdpKit{Debug,Release}XCFramework`) at config time; a second declaration collides.
- Versioning: the release workflow passes `-Pversion=X.Y.Z` — `gradle.properties`' version, or a pre-release's — and KMMBridge tags `v${version}`. KMMBridge's own timestamp versioning is not used.
- Publishing is CI-only: the `kmmBridgePublish` task only exists when `-PENABLE_PUBLISHING=true` is passed (the release workflow does this).
- Don't vendor `XCFramework` zips into the repo. Everything flows through GitHub Release assets + the committed `Package.swift`.
- `Package.swift` on `main` is the committed local-dev form; `kmmBridgePublish` writes the released form onto each tag's release commit, and `spmDevBuild` rewrites it for local development. Don't commit either rewrite (`mise run spm:restore`).

## Local XCFramework development

The sample apps under `/apps/ios` and `/apps/macos` consume the root `Package.swift` as a local package.

```bash
mise run spm:dev        # rebuild debug XCFramework + flip Package.swift to local path
mise run spm:restore    # restore the committed Package.swift
mise run build:xcframework  # rebuild release XCFramework without touching Package.swift
mise run publish:local  # publish to ~/.m2 as the next X.Y.Z-SNAPSHOT (never the released version)
```

The committed `Package.swift` always points at the local build path; released versions resolve their remote-binary `Package.swift` from their `vX.Y.Z` tag.
