# Changesets

Every PR that changes what consumers of Ssdp get adds one Markdown
file here, describing its change. The files pile up on `main` until the next
release gathers them into the changelog and picks the version from them.

```bash
mise run changeset          # prompts for title / change / description
mise run changeset --title "Add a retry policy to Fetcher" --change minor \
    --description "Fetcher retries transient failures with exponential backoff."
mise run changeset:status   # what's pending, the next version, a changelog preview
```

`mise run changeset` names the file after your branch
(`.changeset/<branch>.md`) and starts the body as an *Unfilled* callout (the PR
template's convention). Replace it with the release note — the Changeset check
fails until you do — or delete it if the description says it all. Commit the
file with the change.

## Format

```markdown
---
title: Add a retry policy to Fetcher
change: minor
description: Fetcher retries transient failures with exponential backoff.
---

The full release note, in Markdown: what changed, why, and what a consumer
has to do — migration steps, before/after snippets. Headings are fine; they
are nested under the entry in the changelog.
```

| Key | Required | Meaning |
|---|---|---|
| `title` | yes | One line: what changed. The entry's heading in the changelog. |
| `change` | yes | `major` (breaking) · `minor` (new, compatible) · `patch` (fix). The source of truth for the version — your call; see below. |
| `description` | yes | A sentence or two for consumers. Shown under the title and in the release PR's table. |
| `version` | no | Pin the next release to exactly this version — see below. |

Values are one line each. Quote a value that contains `: ` or ` #`, or starts
with a symbol (`"Fix issue #7"`). `mise run changeset:check` flags anything
YAML would misread. HTML comments in the body are dropped; a leftover
*Unfilled* callout fails the check.

## From changesets to a release

1. **PR check.** The `Changeset` check fails a PR that adds or edits no
   changeset here. Label the PR `no-changeset` when nothing in it reaches
   consumers: docs, CI, tests, sample apps. Renovate PRs carry that label
   automatically.
2. **Release PR.** Every merge to `main` with changesets pending rebuilds one
   rolling PR, **Release vX.Y.Z**, on the `release/next` branch. It bumps
   `version=` in `gradle.properties` and every line marked for release (below),
   adds the release's section to `CHANGELOG.md`, and deletes the
   changesets it consumed. Don't push to that branch: it's rebuilt from `main`
   on every merge. To reword an entry, edit its changeset in a normal PR.
3. **Release.** Merging the release PR publishes that version to Maven Central
   and GitHub Releases (SPM). See
   [`.github/PUBLISHING.md`](../.github/PUBLISHING.md).

### Picking the version

A changeset's `change` is the version decision, and it's the author's: nothing
checks it against the code or the PR (whose *Type of change* just restates
it). The labels above are the usual reading, not a rule — there are reasons to
ship a change at another level (say, removing an API nobody could have called
as a `minor`). When you do, say why in the body, so the release PR's reviewer
sees it.

The release takes the highest `change` among the pending changesets, bumped
from the current `version=` in `gradle.properties`:

| Current | Highest change | Next |
|---|---|---|
| 1.4.2 | `patch` / `minor` / `major` | 1.4.3 / 1.5.0 / 2.0.0 |
| 0.4.2 | `patch` / `minor` / `major` | 0.4.3 / 0.5.0 / **0.5.0** |

While the library is 0.x, a breaking change bumps the **minor** version: 0.x
makes no stability promise, so `major` never moves it to 1.0.0 by accident.
Leaving 0.x is a decision. Add `version: 1.0.0` to a changeset's front matter.
A pin overrides the levels outright — any version above the current one works,
even one below what the levels add up to (the release PR notes the gap). With
several pins, the highest wins.

## Versions in docs and code

`gradle.properties` holds the version. To keep a copy elsewhere in step, e.g.
an install snippet in a README, mark it and the release PR rewrites every
`X.Y.Z` in the marked region:

````markdown
<!-- x-release-version-start -->
```kotlin
implementation("com.example:lib:1.4.2")
```
<!-- x-release-version-end -->
````

or a single line, with the marker in a trailing comment:

```kotlin
const val SSDP_VERSION = "1.4.2" // x-release-version
```

The marker must sit in a comment (`<!-- -->`, `//`, `#`, `/* */`, `--`); a
start/end line holds nothing else.
