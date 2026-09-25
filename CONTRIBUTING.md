# Contributing to ssdp-kmp

Thanks for contributing. This repo uses [mise](https://mise.jdx.dev) as the task
contract — every action is a `mise run <task>`.

## Setup

```bash
brew install mise
mise trust
mise install            # provisions JDK, Gradle, gh
cp local.properties.example local.properties   # point sdk.dir at your Android SDK
```

Xcode is not managed by mise — install a recent Xcode that SKIE supports.
`xcodegen` (used by `mise run xcodeproj:ios` / `open:ios` for the sample apps) is
also not mise-managed: `brew install xcodegen` if you need it.

## Workflow

1. Read [`CLAUDE.md`](CLAUDE.md), then `gradle/libs.versions.toml`, then
   `.claude/lessons/LESSONS.md`.
2. Keep the `expect`/`actual` seam tiny; push logic into `commonMain`.
3. Public API that crosses to Swift? Apply CLAUDE.md §7 (SKIE) at design time.
4. Adding a dependency? Web-search the latest stable and add it to
   `gradle/libs.versions.toml` only. `mise run dependencies:outdated` helps.
5. Changing the public API on purpose? Regenerate the committed ABI dump with
   `mise run api:dump` and review the `*/api/` diff like any other change —
   `check` fails on an unreviewed public-surface change (CLAUDE.md §8).
6. Does the change reach consumers? Add a changeset — `mise run changeset` —
   and replace its *Unfilled* callout with the release note (see
   [`.changeset/README.md`](.changeset/README.md)). Its `change` level decides
   the version; the PR's *Type of change* just restates it.

## The done gate

```bash
mise run check
```

`check` runs ktlint + detekt + the public-API/ABI check + every unit-test target
(iOS simulator, macOS, Android host, JVM). A JVM-only run hides
native-test-compile, detekt, and ABI failures, so `check` — not `test:jvm` — is
the gate. Format first if ktlint complains:

```bash
mise run format
```

## Commits & PRs

- Keep commits focused; explain *why* in the body when it isn't obvious.
- CI runs the same `mise run check` + `mise run build:xcframework`. Green CI is
  required to merge.
- The **Changeset** check fails a PR that adds no `.changeset/*.md`. If nothing
  in the PR reaches consumers (docs, CI, tests, samples), label it
  `no-changeset` instead.
- Learned something non-obvious? Add a terse line to
  `.claude/lessons/LESSONS.md`.

## Releases

Releases come from the changesets. Merges to `main` keep a **Release vX.Y.Z**
PR open with the computed version and changelog; merging it publishes the
release. Pre-releases and retries are manual runs of the Release workflow. See
[`.github/PUBLISHING.md`](.github/PUBLISHING.md). Don't hand-edit `version=` in
`gradle.properties` or `Package.swift` — both are generated.
