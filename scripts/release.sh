#!/bin/sh
# release.sh — release this KMP library by hand, from your machine.
#
#   scripts/release.sh [--version X.Y.Z[-suffix]] [--dryrun]
#
# Invoked by `mise run publish:maven`. The canonical path is CI — merging the
# release PR runs .github/workflows/release.yml. This is the by-hand
# equivalent, under the same version rules:
#
#   (no --version)   the version in gradle.properties, i.e. what the last
#                    merged release PR set. Use it to finish a release CI
#                    couldn't.
#   --version X.Y.Z-suffix
#                    a pre-release (0.4.0-rc.1), from any branch. Its release
#                    notes are the changesets pending on that branch.
#
# A stable --version must equal gradle.properties' — stable versions come only
# from release PRs (.changeset/README.md) — and is released from main.
#
#   --dryrun : `publishToMavenCentral` — uploads to the Central Portal STAGING
#              area and stops. Nothing is committed, tagged, or released.
#              Review at https://central.sonatype.com/.
#
#   (real)   : the full release, after a typed confirmation:
#              1. build + zip the release XCFramework, and write Package.swift
#                 in its released remote-binary form (URL + checksum of this
#                 tag's asset);
#              2. publishAndReleaseToMavenCentral  (IRREVERSIBLE);
#              3. commit Package.swift on a detached HEAD and tag it vX.Y.Z —
#                 like CI, the release commit lives only on the tag; main
#                 keeps the local-dev Package.swift and is never pushed;
#              4. push the tag, then `gh release create` with the XCFramework
#                 zip and the changelog's notes.
#
# Required for a REAL release: a clean git tree, `gh` authenticated, and the
# Maven Central credentials exported as the ORG_GRADLE_PROJECT_* env vars
# vanniktech reads (see .github/PUBLISHING.md).

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

DRYRUN=""
VERSION=""
while [ $# -gt 0 ]; do
    case "$1" in
        --dryrun) DRYRUN="true"; shift ;;
        --version) VERSION="${2:-}"; shift 2 ;;
        --version=*) VERSION="${1#--version=}"; shift ;;
        *) echo "release.sh: unknown argument '$1'" >&2; exit 2 ;;
    esac
done

CURRENT=$(python3 "$SCRIPT_DIR/changeset.py" current)
VERSION=${VERSION#v}
VERSION=${VERSION:-$CURRENT}
BRANCH=$(git rev-parse --abbrev-ref HEAD)

# Same grammar as release.yml: SemVer 2.0 with a lowercase, dot-separated
# pre-release (uppercase is a Maven version-comparison foot-gun).
if ! printf '%s' "$VERSION" | grep -Eq '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9a-z][0-9a-z-]*(\.[0-9a-z][0-9a-z-]*)*)?$'; then
    echo "error: '$VERSION' is not a valid version (X.Y.Z, or X.Y.Z-suffix like rc.1)." >&2
    exit 2
fi
case "$VERSION" in
    *-*) PRERELEASE="true" ;;
    *)
        PRERELEASE="false"
        if [ "$VERSION" != "$CURRENT" ]; then
            echo "error: stable versions come from release PRs — $VERSION isn't the $CURRENT in gradle.properties." >&2
            echo "       Merge the release PR (see .changeset/README.md), or cut a pre-release like $VERSION-rc.1." >&2
            exit 2
        fi
        if [ -z "$DRYRUN" ] && [ "$BRANCH" != "main" ]; then
            echo "error: stable releases run from main (on '$BRANCH'). Pre-releases can run from any branch." >&2
            exit 2
        fi
        ;;
esac
TAG="v$VERSION"
FRAMEWORK="SsdpKit"   # init.sh rewrites this to the framework module name.

# Fail fast if the Maven Central credentials vanniktech needs aren't available.
# A Gradle property `foo` resolves from an ORG_GRADLE_PROJECT_foo env var or from
# ~/.gradle/gradle.properties — check both so either setup passes. This runs for
# dry runs too: Central validates GPG signatures even in staging. See
# .github/PUBLISHING.md for what these are and where to get them.
require_credentials() {
    home_props="$HOME/.gradle/gradle.properties"
    missing=""
    for prop in mavenCentralUsername mavenCentralPassword signingInMemoryKey signingInMemoryKeyPassword; do
        env_name=$(printf 'ORG_GRADLE_PROJECT_%s' "$prop")
        eval "env_val=\${$env_name:-}"
        if [ -n "$env_val" ]; then
            continue
        fi
        if [ -f "$home_props" ] && grep -q "^[[:space:]]*$prop[[:space:]]*=" "$home_props"; then
            continue
        fi
        missing="$missing $prop"
    done
    if [ -n "$missing" ]; then
        echo "error: missing Maven Central credentials:$missing" >&2
        echo "       Set them in ~/.gradle/gradle.properties or as ORG_GRADLE_PROJECT_* env vars." >&2
        echo "       See .github/PUBLISHING.md → Credentials." >&2
        exit 1
    fi
}

# --- Dry run: stage only ----------------------------------------------------
if [ "$DRYRUN" = "true" ]; then
    require_credentials
    echo "Dry run — computed version: $VERSION"
    echo "Uploading to Maven Central STAGING only (no release, no tag, no commit)."
    ./gradlew publishToMavenCentral -Pversion="$VERSION"
    echo ""
    echo "Staged. Review at https://central.sonatype.com/ and Publish or Drop there."
    exit 0
fi

# --- Real release: preflight ------------------------------------------------
require_credentials
if [ -n "$(git status --porcelain)" ]; then
    echo "error: working tree is not clean. Commit or stash before releasing." >&2
    exit 1
fi
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null 2>&1; then
    echo "error: tag $TAG already exists — $VERSION is already released." >&2
    exit 1
fi
if ! command -v gh >/dev/null 2>&1; then
    echo "error: gh is required for the GitHub release step." >&2
    exit 1
fi

REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null || true)
echo "About to PUBLISH AND RELEASE — this is IRREVERSIBLE:"
echo "  version : $VERSION"
echo "  tag     : $TAG"
echo "  repo    : ${REPO:-<unknown>}"
echo "  pre-release: $PRERELEASE"
echo "  Maven Central: publishAndReleaseToMavenCentral (cannot be undone)"
echo "  git     : tag $TAG on a release commit (Package.swift) + push the tag"
echo "  GitHub  : create release $TAG with the $FRAMEWORK.xcframework zip asset"
echo ""
printf 'Type the version (%s) to confirm: ' "$VERSION"
read -r CONFIRM
if [ "$CONFIRM" != "$VERSION" ]; then
    echo "Aborted — confirmation did not match." >&2
    exit 1
fi

# 1. Build the release XCFramework + zip it (the SPM asset).
echo "==> Building release XCFramework"
./gradlew ":ssdp:assemble${FRAMEWORK}XCFramework" -Pversion="$VERSION"
XCF_DIR="ssdp/build/XCFrameworks/release"
ZIP="$XCF_DIR/$FRAMEWORK.xcframework.zip"
( cd "$XCF_DIR" && rm -f "$FRAMEWORK.xcframework.zip" && zip -qry "$FRAMEWORK.xcframework.zip" "$FRAMEWORK.xcframework" )

# 2. Rewrite Package.swift to the released remote-binary form (URL + checksum).
ASSET_URL="https://github.com/$REPO/releases/download/$TAG/$FRAMEWORK.xcframework.zip"
CHECKSUM=$(swift package compute-checksum "$ZIP")
echo "==> Pointing Package.swift at $ASSET_URL"
cat > Package.swift <<EOF
// swift-tools-version:6.0
import PackageDescription

let packageName = "$FRAMEWORK"

let package = Package(
    name: packageName,
    platforms: [
        .iOS(.v18),
        .macOS(.v15),
    ],
    products: [
        .library(
            name: packageName,
            targets: [packageName]
        ),
    ],
    targets: [
        .binaryTarget(
            name: packageName,
            url: "$ASSET_URL",
            checksum: "$CHECKSUM"
        ),
    ]
)
EOF
swift package dump-package > /dev/null   # prove the manifest still parses.

# 3. Publish + release to Maven Central (IRREVERSIBLE).
echo "==> Publishing to Maven Central"
./gradlew publishAndReleaseToMavenCentral -Pversion="$VERSION"

# 4. Tag a release commit that carries Package.swift. It lives only on the tag
#    (SPM resolves the manifest from the tag); main is branch-protected and
#    keeps the local-dev form, so nothing is pushed to a branch.
echo "==> Tagging the release commit"
git switch --detach --quiet
git add Package.swift
git commit --quiet -m "Release $TAG" -m "Package.swift points at the $TAG XCFramework release asset."
git tag -a "$TAG" -m "Release $TAG"
git push origin "refs/tags/$TAG"
git switch --quiet "$BRANCH"

# 5. GitHub release with the XCFramework asset and the changelog's notes (for
#    a pre-release: the changesets pending on this branch).
echo "==> Creating GitHub release $TAG"
NOTES=$(mktemp)
if python3 "$SCRIPT_DIR/changeset.py" notes "$VERSION" > "$NOTES"; then
    NOTES_ARGS="--notes-file $NOTES"
else
    NOTES_ARGS="--generate-notes"
fi
if [ "$PRERELEASE" = "true" ]; then
    LATEST_ARGS="--prerelease --latest=false"
else
    LATEST_ARGS="--latest"
fi
# shellcheck disable=SC2086
gh release create "$TAG" "$ZIP" --title "$TAG" $NOTES_ARGS $LATEST_ARGS
rm -f "$NOTES"

echo ""
echo "Released $VERSION."
echo "  Maven Central: https://central.sonatype.com/artifact/com.happycodelucky.ssdp/ssdp/$VERSION"
echo "  GitHub:        https://github.com/$REPO/releases/tag/$TAG"
