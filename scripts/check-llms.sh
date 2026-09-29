#!/bin/sh
# check-llms.sh — verify every published jar and AAR carries llms.txt and
# llms-full.txt (gradle/plugins/src/main/kotlin/LlmsTxt.kt).
#
#   scripts/check-llms.sh VERSION
#
# Run after `mise run publish:local` (`mise run llms:check` does both): it
# inspects what landed in ~/.m2 for this project's group at VERSION — the
# same artifacts a release uploads, unsigned. Klibs are skipped: a klib
# carries no resources, so native targets ship the files in their sources jar.

set -eu

VERSION="${1:?usage: scripts/check-llms.sh VERSION}"
ROOT=$(cd "$(dirname "$0")/.." && pwd)
# The Maven group from the root build's `allprojects { group = "…" }` — the one
# dotted `group = ` there (task groups like "documentation" have no dot).
GROUP=$(sed -n 's/^[[:space:]]*group = "\([^"]*\.[^"]*\)"$/\1/p' "$ROOT/build.gradle.kts" | head -n 1)
[ -n "$GROUP" ] || { echo "error: no \`group = \"…\"\` in build.gradle.kts" >&2; exit 1; }
REPO="${MAVEN_LOCAL:-$HOME/.m2/repository}/$(printf '%s' "$GROUP" | tr . /)"

ARTIFACTS=$(find "$REPO" -path "*/$VERSION/*" \( -name '*.jar' -o -name '*.aar' \) 2>/dev/null | sort)
[ -n "$ARTIFACTS" ] || { echo "error: nothing published for $GROUP at $VERSION under $REPO" >&2; exit 1; }

missing=0
count=0
for artifact in $ARTIFACTS; do
    count=$((count + 1))
    entries=$(unzip -Z1 "$artifact")
    for file in llms.txt llms-full.txt; do
        if ! printf '%s\n' "$entries" | grep -q "^META-INF/$GROUP/[^/]*/$file\$"; then
            echo "error: $(basename "$artifact") has no META-INF/$GROUP/<artifactId>/$file" >&2
            missing=1
        fi
    done
done

[ "$missing" -eq 0 ] || exit 1
echo "llms.txt + llms-full.txt in all $count jar/AAR artifact(s) of $GROUP $VERSION"
