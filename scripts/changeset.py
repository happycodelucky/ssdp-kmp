#!/usr/bin/env python3
"""
changeset.py — per-PR change notes that drive the version and the changelog.

Every PR adds a Markdown file under .changeset/ describing its change:

    ---
    title: Add a retry policy to Fetcher
    change: minor
    description: Fetcher retries transient failures with exponential backoff.
    ---

    Full Markdown: what changed, why, and what a consumer has to do.

`change` is the semver level (major | minor | patch), and it is the source of
truth for the version: the author's call, never second-guessed by tooling.
While the library is 0.x a `major` change bumps the MINOR version: 0.x has no
stable API to break. An optional `version: X.Y.Z` pins the next release
outright — the only way out of 0.x (1.0.0 is a decision, not an accident) —
and may even sit below what the levels add up to; it only has to move forward.

The body starts as an "Unfilled" callout (the PR template's convention);
`check` and `version` refuse a changeset that still holds it, so no release
note ships empty by accident.

When changesets are pending on main, the Release PR workflow runs `version`:
it bumps `version=` in gradle.properties plus every line marked
`x-release-version` (see .changeset/README.md), inserts the release's section
into CHANGELOG.md, and deletes the consumed changesets. Merging that PR
publishes the release (.github/workflows/release.yml).

Commands (stdout carries only machine-readable output; diagnostics go to
stderr):

    new       add a changeset for the current branch (prompts when interactive)
    check     validate every pending changeset; with --require-since REF, also
              require the branch to add or edit one (the PR check)
    status    pending changesets, the next version, a changelog preview
              (--json for workflows)
    version   apply the pending changesets (the Release PR workflow's job)
    notes     print one version's release notes, for the GitHub release
    current   print the version in gradle.properties (optionally at a git ref)
    snapshot  print the version under development as X.Y.Z-SNAPSHOT (publish:local)

Standard library only, Python 3.9+ — it runs on bare GitHub runners.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

ROOT = Path(__file__).resolve().parent.parent
CHANGESET_DIR = ROOT / ".changeset"
VERSION_FILE = ROOT / "gradle.properties"
CHANGELOG = ROOT / "CHANGELOG.md"

# Ascending; the release takes the highest level among its changesets.
LEVELS = ("patch", "minor", "major")
SECTION_TITLES = {"major": "Breaking changes", "minor": "Features", "patch": "Fixes"}

REQUIRED_KEYS = ("title", "change", "description")
OPTIONAL_KEYS = ("version",)

# The release section goes directly below this line in the changelog.
CHANGELOG_MARKER = "<!-- changesets:"

# Lines tagged with the inline marker, and every line between a start/end
# pair, have their version rewritten on release. Both live in a comment, so
# prose that merely names them is inert; a start/end line holds nothing else.
INLINE_MARKER = re.compile(r"(?:<!--|//|#|/\*|--)\s*x-release-version(?!-)")
BLOCK_MARKER = re.compile(
    r"\s*(?:<!--|//|#|/\*|--)?\s*x-release-version-(start|end)\s*(?:-->|\*/)?\s*"
)
# A version inside a marked region: X.Y.Z with an optional pre-release and an
# optional `v` prefix (kept). The guards stop it matching inside a longer
# dotted number (1.2.3.4) or a word.
MARKED_VERSION = re.compile(
    r"(?<![0-9A-Za-z.-])(v?)\d+\.\d+\.\d+(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?![\w-]|\.\d)"
)

VERSION_LINE = re.compile(r"^(version[ \t]*[=:][ \t]*)(\S*)[ \t]*$", re.MULTILINE)
STABLE_VERSION = re.compile(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)")

RELEASE_BRANCH = "release/next"

# The body placeholder `new` writes — a GitHub callout whose text starts with
# "Unfilled", the same convention as .github/PULL_REQUEST_TEMPLATE.md.
UNFILLED = re.compile(r"^\s*>\s*_?Unfilled\b")


class ChangesetError(Exception):
    """A user-facing failure. `problems` are (path, line, message) triples."""

    def __init__(self, problems: list[tuple[Optional[Path], Optional[int], str]]):
        super().__init__("\n".join(p[2] for p in problems))
        self.problems = problems


def fail(message: str, path: Optional[Path] = None, line: Optional[int] = None):
    raise ChangesetError([(path, line, message)])


# --- versions ----------------------------------------------------------------


@dataclass(frozen=True)
class Version:
    """A STABLE semver (X.Y.Z). main only ever holds stable versions —
    pre-releases are cut by the Release workflow and never committed."""

    major: int
    minor: int
    patch: int

    @classmethod
    def parse(cls, text: str) -> "Version":
        match = STABLE_VERSION.fullmatch(text.strip())
        if not match:
            raise ValueError(f"'{text}' is not a stable semantic version (X.Y.Z)")
        return cls(*(int(g) for g in match.groups()))

    def bump(self, level: str) -> "Version":
        # 0.x: a breaking change bumps the minor. 1.0.0 only comes from an
        # explicit `version:` in a changeset.
        if level == "major" and self.major == 0:
            level = "minor"
        if level == "major":
            return Version(self.major + 1, 0, 0)
        if level == "minor":
            return Version(self.major, self.minor + 1, 0)
        return Version(self.major, self.minor, self.patch + 1)

    def key(self) -> tuple[int, int, int]:
        return (self.major, self.minor, self.patch)

    def __str__(self) -> str:
        return f"{self.major}.{self.minor}.{self.patch}"


def read_version(text: Optional[str] = None) -> Version:
    """The version in gradle.properties (or in `text`, its content at a ref)."""
    if text is None:
        text = VERSION_FILE.read_text(encoding="utf-8")
    match = VERSION_LINE.search(text)
    if not match:
        fail("no `version=` line in gradle.properties", VERSION_FILE)
    try:
        return Version.parse(match.group(2))
    except ValueError as e:
        fail(f"gradle.properties: {e}", VERSION_FILE)


def write_version(version: Version) -> None:
    text = VERSION_FILE.read_text(encoding="utf-8")
    VERSION_FILE.write_text(VERSION_LINE.sub(rf"\g<1>{version}", text, count=1), encoding="utf-8")


# --- changesets ----------------------------------------------------------------


@dataclass
class Changeset:
    path: Path
    title: str
    change: str
    description: str
    version: Optional[Version]
    body: str
    # Line of a leftover "Unfilled" callout: the release note was never written.
    unfilled_line: Optional[int] = None
    # Filled in from git history for the changelog; absent for uncommitted files.
    added_at: Optional[int] = None
    pr: Optional[int] = None

    @property
    def name(self) -> str:
        return rel(self.path)


def rel(path: Path) -> str:
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def changeset_paths() -> list[Path]:
    if not CHANGESET_DIR.is_dir():
        return []
    return sorted(
        p
        for p in CHANGESET_DIR.glob("*.md")
        if p.is_file() and p.name.lower() != "readme.md" and not p.name.startswith(".")
    )


def parse_scalar(raw: str, path: Path, line: int) -> str:
    """One front-matter value. A strict subset of YAML: anything a real YAML
    parser would read differently (or reject) is an error, so the files stay
    valid YAML for editors and for GitHub's front-matter table."""
    value = raw.strip()
    if not value:
        return ""
    if value[0] == '"':
        if len(value) < 2 or not value.endswith('"'):
            fail("unterminated double-quoted value", path, line)
        try:
            return json.loads(value)
        except json.JSONDecodeError:
            fail("invalid escape in double-quoted value", path, line)
    if value[0] == "'":
        if len(value) < 2 or not value.endswith("'"):
            fail("unterminated single-quoted value", path, line)
        return value[1:-1].replace("''", "'")
    if value in ("|", "|-", ">", ">-"):
        fail("multi-line values aren't supported — keep it on one line (the body holds the details)", path, line)
    if value[0] in "[]{}&*!%@`,?-#|>" or ": " in value or " #" in value or value.endswith(":"):
        fail(f"quote this value — YAML would misread it: {value}", path, line)
    return value


def parse_changeset(path: Path) -> Changeset:
    text = path.read_text(encoding="utf-8").lstrip("﻿")
    lines = text.splitlines()
    if not lines or lines[0].strip() != "---":
        fail("must start with a `---` front-matter block (title / change / description)", path, 1)
    try:
        end = next(i for i in range(1, len(lines)) if lines[i].strip() == "---")
    except StopIteration:
        fail("front matter is never closed with `---`", path, 1)

    problems: list[tuple[Optional[Path], Optional[int], str]] = []
    values: dict[str, str] = {}
    invalid: set[str] = set()  # keys already reported — don't also call them missing
    for i in range(1, end):
        line_no = i + 1
        line = lines[i]
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        match = re.fullmatch(r"([A-Za-z][\w-]*):(?:\s+(.*))?\s*", line)
        if not match:
            problems.append((path, line_no, f"expected `key: value`, got: {line.strip()}"))
            continue
        key, raw = match.group(1), match.group(2) or ""
        if key not in REQUIRED_KEYS + OPTIONAL_KEYS:
            allowed = ", ".join(REQUIRED_KEYS + OPTIONAL_KEYS)
            problems.append((path, line_no, f"unknown key `{key}` (allowed: {allowed})"))
            continue
        if key in values:
            problems.append((path, line_no, f"duplicate key `{key}`"))
            continue
        try:
            values[key] = parse_scalar(raw, path, line_no)
        except ChangesetError as e:
            problems.extend(e.problems)
            invalid.add(key)

    for key in REQUIRED_KEYS:
        if not values.get(key, "").strip() and key not in invalid:
            problems.append((path, 1, f"`{key}` is required and can't be empty"))

    change = values.get("change", "").strip()
    if change and change not in LEVELS:
        problems.append((path, 1, f"`change` must be one of {', '.join(reversed(LEVELS))} (got `{change}`)"))

    version: Optional[Version] = None
    if values.get("version"):
        try:
            version = Version.parse(values["version"])
        except ValueError as e:
            problems.append((path, 1, f"`version`: {e}"))

    if problems:
        raise ChangesetError(problems)

    unfilled_line = next((end + 2 + i for i, line in enumerate(lines[end + 1 :]) if UNFILLED.match(line)), None)
    body = "\n".join(lines[end + 1 :])
    # Template guidance lives in HTML comments; it's not part of the note.
    body = re.sub(r"<!--.*?-->", "", body, flags=re.DOTALL).strip("\n")
    body = "\n".join(line.rstrip() for line in body.splitlines()).strip()
    return Changeset(
        path=path,
        title=values["title"].strip(),
        change=change,
        description=values["description"].strip(),
        version=version,
        body=body,
        unfilled_line=unfilled_line,
    )


def require_filled(changesets: list[Changeset]) -> None:
    """A release note must be written before it can ship: `check` (the PR
    gate) and `version` (the release PR) refuse a leftover Unfilled callout."""
    problems = [
        (c.path, c.unfilled_line, "replace the Unfilled callout with the release note (what changed, why, what "
         "consumers do) — or delete it if the description says it all")
        for c in changesets
        if c.unfilled_line
    ]
    if problems:
        raise ChangesetError(problems)


def load_changesets() -> list[Changeset]:
    changesets, problems = [], []
    for path in changeset_paths():
        try:
            changesets.append(parse_changeset(path))
        except ChangesetError as e:
            problems.extend(e.problems)
    if problems:
        raise ChangesetError(problems)
    return changesets


# --- planning --------------------------------------------------------------------


@dataclass
class Plan:
    current: Version
    changesets: list[Changeset]
    level: Optional[str] = None
    computed: Optional[Version] = None
    pinned: Optional[Version] = None
    next: Optional[Version] = None
    notes: list[str] = field(default_factory=list)


def plan_release(changesets: list[Changeset], current: Version) -> Plan:
    plan = Plan(current=current, changesets=changesets)
    if not changesets:
        return plan
    plan.level = max((c.change for c in changesets), key=LEVELS.index)
    plan.computed = current.bump(plan.level)

    pins = [c for c in changesets if c.version]
    if pins:
        top = max(pins, key=lambda c: c.version.key())
        plan.pinned = top.version
        if top.version.key() <= current.key():
            fail(f"`version: {top.version}` must be higher than the current {current}", top.path)
        plan.notes.append(f"pinned by `version: {top.version}` in {top.name}")
        if top.version.key() < plan.computed.key():
            # An override, and the author's call — honoured, but made visible.
            plan.notes.append(f"below the {plan.computed} a `{plan.level}` change gives")
            warn(
                f"{top.name}: `version: {top.version}` is below the {plan.computed} a `{plan.level}` "
                f"change gives; releasing {top.version} as pinned"
            )
        plan.next = top.version
    else:
        plan.next = plan.computed
        if plan.level == "major" and current.major == 0:
            plan.notes.append("0.x: a major change bumps the minor version")
    return plan


# --- git / GitHub ------------------------------------------------------------------


def git(*args: str, check: bool = True) -> str:
    result = subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True, check=False)
    if check and result.returncode != 0:
        fail(f"git {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


def github_repo() -> Optional[str]:
    repo = os.environ.get("GITHUB_REPOSITORY")
    if repo:
        return repo
    url = git("remote", "get-url", "origin", check=False).strip()
    match = re.search(r"github\.com[:/]([^/]+/[^/]+?)(?:\.git)?$", url)
    return match.group(1) if match else None


def annotate_history(changesets: list[Changeset], repo: Optional[str]) -> None:
    """When each changeset landed, and from which PR — best-effort: the
    changelog is still correct without it, just unlinked."""
    for c in changesets:
        log = git("log", "--diff-filter=A", "--format=%H%x09%ct%x09%s", "--", c.name, check=False).strip()
        if not log:
            continue  # not committed yet (a local preview)
        # Newest add first: a file name comes back when a branch name is reused,
        # and the older adds belong to changesets that were already released.
        sha, timestamp, subject = log.splitlines()[0].split("\t", 2)
        c.added_at = int(timestamp)
        # A squash merge's subject ends with "(#123)" — no API call needed.
        match = re.search(r"\(#(\d+)\)\s*$", subject)
        if match:
            c.pr = int(match.group(1))
        elif repo:
            c.pr = pr_for_commit(repo, sha)


def pr_for_commit(repo: str, sha: str) -> Optional[int]:
    try:
        result = subprocess.run(
            ["gh", "api", f"repos/{repo}/commits/{sha}/pulls", "--jq", "[.[] | select(.merged_at != null) | .number][0]"],
            capture_output=True,
            text=True,
            timeout=15,
            check=False,
        )
    except (FileNotFoundError, subprocess.TimeoutExpired):
        return None
    number = result.stdout.strip()
    return int(number) if result.returncode == 0 and number.isdigit() else None


# --- rendering ---------------------------------------------------------------------


def demote_headings(markdown: str, floor: int = 5) -> str:
    """Push a body's headings below the changelog's own (## version,
    ### section, #### entry) so they can't break its structure or TOC."""
    out, fence = [], None
    for line in markdown.splitlines():
        stripped = line.lstrip()
        match = re.match(r"(`{3,}|~{3,})", stripped)
        if match:
            marker = match.group(1)
            if fence is None:
                fence = marker
            elif marker[0] == fence[0] and len(marker) >= len(fence):
                fence = None
        elif fence is None:
            heading = re.match(r"(#{1,6})(\s.*)", line)
            if heading:
                level = min(6, len(heading.group(1)) + floor - 1)
                line = "#" * level + heading.group(2)
        out.append(line)
    return "\n".join(out)


def render_entries(changesets: list[Changeset], repo: Optional[str]) -> str:
    server = os.environ.get("GITHUB_SERVER_URL", "https://github.com")
    ordered = sorted(changesets, key=lambda c: (c.added_at is None, c.added_at or 0, c.pr or 0, c.name))
    blocks = []
    for level in reversed(LEVELS):
        group = [c for c in ordered if c.change == level]
        if not group:
            continue
        lines = [f"### {SECTION_TITLES[level]}", ""]
        for c in group:
            heading = f"#### {c.title}"
            if c.pr and repo:
                heading += f" ([#{c.pr}]({server}/{repo}/pull/{c.pr}))"
            elif c.pr:
                heading += f" (#{c.pr})"
            lines += [heading, "", c.description, ""]
            if c.body:
                lines += [demote_headings(c.body), ""]
        blocks.append("\n".join(lines).rstrip())
    return "\n\n".join(blocks) + "\n"


def render_section(version: Version, changesets: list[Changeset], repo: Optional[str]) -> str:
    today = dt.datetime.now(dt.timezone.utc).date().isoformat()
    return f"## {version} — {today}\n\n" + render_entries(changesets, repo)


def render_pr_body(plan: Plan, section: str) -> str:
    rows = "\n".join(
        f"| {c.change} | {table_cell(c.title)} | {table_cell(c.description)} |"
        for c in sorted(plan.changesets, key=lambda c: (-LEVELS.index(c.change), c.name))
    )
    how = f"highest change: **{plan.level}**"
    if plan.notes:
        how += " — " + "; ".join(plan.notes)
    return f"""\
Merging this PR releases **{plan.next}** (current: {plan.current}; {how}).

The Release workflow then publishes to Maven Central and creates the `v{plan.next}` GitHub release with the XCFramework for SPM.

| Change | Title | Description |
| --- | --- | --- |
{rows}

<details>
<summary>Changelog entry</summary>

{section.strip()}

</details>

---

This PR is rebuilt from `main` every time a PR merges — don't push to `{RELEASE_BRANCH}`. To change an entry, edit its changeset in a normal PR. To choose the version yourself (e.g. 1.0.0), add `version: X.Y.Z` to a changeset's front matter.
"""


def table_cell(text: str) -> str:
    return text.replace("|", "\\|")


# --- writing -----------------------------------------------------------------------


def with_section(text: str, section: str) -> str:
    """The changelog `text` with `section` inserted below the marker line."""
    lines = text.splitlines(keepends=True)
    try:
        at = next(i for i, line in enumerate(lines) if line.lstrip().startswith(CHANGELOG_MARKER))
    except StopIteration:
        fail(f"{rel(CHANGELOG)} has no `{CHANGELOG_MARKER} … -->` line to insert releases below", CHANGELOG)
    before = "".join(lines[: at + 1])
    after = "".join(lines[at + 1 :]).lstrip("\n")
    joined = before.rstrip("\n") + "\n\n" + section.rstrip("\n") + "\n"
    if after:
        joined += "\n" + after
    return joined


def marked_files() -> list[Path]:
    skip = {rel(CHANGELOG), rel(VERSION_FILE), rel(Path(__file__).resolve())}
    paths = []
    for name in git("ls-files", "-z").split("\0"):
        if not name or name in skip or name.startswith(".changeset/"):
            continue
        path = ROOT / name
        try:
            data = path.read_bytes()
        except OSError:
            continue
        if b"x-release-version" in data and b"\0" not in data:
            paths.append(path)
    return paths


def rewrite_marked(path: Path, version: Version, warnings: list[str]) -> Optional[str]:
    """`path`'s text with every marked version set to `version`, or None when
    nothing changes. Pure: `version` writes only after every file passed."""
    lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
    changed, block_start, block_hits = False, None, 0
    for i, line in enumerate(lines):
        marker = BLOCK_MARKER.fullmatch(line.rstrip("\r\n"))
        if marker:
            if marker.group(1) == "start":
                if block_start is not None:
                    fail(f"nested x-release-version-start (the block opened on line {block_start + 1})", path, i + 1)
                block_start, block_hits = i, 0
            else:
                if block_start is None:
                    fail("x-release-version-end without a start", path, i + 1)
                if block_hits == 0:
                    warnings.append(f"{rel(path)}:{block_start + 1}: x-release-version block contains no version")
                block_start = None
            continue
        inline = INLINE_MARKER.search(line) is not None
        if block_start is None and not inline:
            continue
        new_line, hits = MARKED_VERSION.subn(lambda m: m.group(1) + str(version), line)
        if inline and hits == 0 and block_start is None:
            warnings.append(f"{rel(path)}:{i + 1}: x-release-version line contains no version")
        block_hits += hits
        if new_line != line:
            lines[i], changed = new_line, True
    if block_start is not None:
        fail("x-release-version-start is never closed", path, block_start + 1)
    return "".join(lines) if changed else None


# --- commands ----------------------------------------------------------------------


def cmd_new(args: argparse.Namespace) -> int:
    interactive = sys.stdin.isatty()

    def ask(label: str, current: Optional[str], hint: str = "") -> str:
        if current:
            return current.strip()
        if not interactive:
            fail(f"--{label} is required when not running interactively")
        while True:
            answer = input(f"{label}{hint}: ").strip()
            if answer:
                return answer

    title = ask("title", args.title, " (one line: what changed)")
    change = ask("change", args.change, " (major | minor | patch)").lower()
    while change not in LEVELS:
        if not interactive:
            fail(f"--change must be one of {', '.join(reversed(LEVELS))}")
        change = input("change (major | minor | patch): ").strip().lower()
    description = ask("description", args.description, " (a sentence or two for consumers)")

    name = args.name or branch_leaf() or title
    slug = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")[:60].strip("-") or "change"
    path = CHANGESET_DIR / f"{slug}.md"
    if path.exists():
        fail(f"{rel(path)} already exists — edit it, or pass --name for a second changeset on this branch")

    body = args.body or (
        "<!-- AI: The full release note, in Markdown: what changed, why, and what a\n"
        "     consumer has to do — migration steps, before/after snippets. Headings\n"
        "     are fine. It's published in the changelog and the GitHub release.\n"
        "     Replace the callout below with it (or delete the callout if the\n"
        "     description says it all); this comment is dropped. -->\n"
        "\n"
        "> [!IMPORTANT]\n"
        "> _Unfilled — the release note for this change._\n"
    )
    CHANGESET_DIR.mkdir(exist_ok=True)
    path.write_text(
        "---\n"
        f"title: {yaml_scalar(title)}\n"
        f"change: {change}\n"
        f"description: {yaml_scalar(description)}\n"
        "---\n\n"
        f"{body.rstrip()}\n",
        encoding="utf-8",
    )
    parse_changeset(path)  # never write something `check` would reject (bar the placeholder)
    print(rel(path))
    return 0


def branch_leaf() -> Optional[str]:
    branch = git("rev-parse", "--abbrev-ref", "HEAD", check=False).strip()
    if not branch or branch in ("HEAD", "main", "master", RELEASE_BRANCH):
        return None
    return branch.rsplit("/", 1)[-1]


def yaml_scalar(text: str) -> str:
    text = " ".join(text.split())
    try:
        if parse_scalar(text, Path("-"), 0) == text and text.lower() not in (
            "true", "false", "yes", "no", "null", "on", "off", "~"
        ):
            return text
    except ChangesetError:
        pass
    return json.dumps(text, ensure_ascii=False)


def cmd_check(args: argparse.Namespace) -> int:
    changesets = load_changesets()
    require_filled(changesets)
    current = read_version()
    plan = plan_release(changesets, current)
    with_section(CHANGELOG.read_text(encoding="utf-8"), "")  # the insertion marker exists

    if args.require_since:
        touched = git(
            "diff", "--name-only", "--diff-filter=AM", f"{args.require_since}...HEAD", "--", ".changeset/"
        ).split()
        touched = [t for t in touched if t.endswith(".md") and Path(t).name.lower() != "readme.md"]
        if not touched:
            fail(
                "this branch adds no changeset. Run `mise run changeset` and commit the file it "
                "creates — or, if nothing here reaches consumers (docs, CI, tests), add the "
                "`no-changeset` label to the PR."
            )
        print(f"changeset(s) on this branch: {', '.join(touched)}", file=sys.stderr)

    summary = f"{len(changesets)} pending changeset(s)"
    if plan.next:
        summary += f"; next release {current} → {plan.next}"
    print(f"changesets ok — {summary}", file=sys.stderr)
    return 0


def cmd_status(args: argparse.Namespace) -> int:
    changesets = load_changesets()
    plan = plan_release(changesets, read_version())
    if args.json:
        print(
            json.dumps(
                {
                    "current": str(plan.current),
                    "next": str(plan.next) if plan.next else None,
                    "level": plan.level,
                    "count": len(changesets),
                    "changesets": [
                        {
                            "file": c.name,
                            "title": c.title,
                            "change": c.change,
                            "description": c.description,
                            "version": str(c.version) if c.version else None,
                        }
                        for c in changesets
                    ],
                },
                indent=2,
            )
        )
        return 0

    print(f"Current version: {plan.current}")
    if not changesets:
        print("No pending changesets — nothing to release.")
        return 0
    print(f"Pending changesets: {len(changesets)}")
    for c in sorted(changesets, key=lambda c: (-LEVELS.index(c.change), c.name)):
        unfilled = "  — release note Unfilled" if c.unfilled_line else ""
        print(f"  [{c.change}] {c.title}  ({c.name}){unfilled}")
    detail = f"highest change: {plan.level}" + ("; " + "; ".join(plan.notes) if plan.notes else "")
    print(f"Next version:    {plan.next}  ({detail})")
    print("\nChangelog preview\n-----------------\n")
    annotate_history(changesets, github_repo())
    print(render_section(plan.next, changesets, github_repo()))
    return 0


def cmd_version(args: argparse.Namespace) -> int:
    changesets = load_changesets()
    if not changesets:
        fail("no pending changesets — nothing to version")
    require_filled(changesets)
    plan = plan_release(changesets, read_version())
    repo = github_repo()
    annotate_history(changesets, repo)
    section = render_section(plan.next, changesets, repo)

    warnings: list[str] = []
    rewrites = {p: text for p in marked_files() if (text := rewrite_marked(p, plan.next, warnings)) is not None}
    changelog = with_section(CHANGELOG.read_text(encoding="utf-8"), section)

    write_version(plan.next)
    for path, text in rewrites.items():
        path.write_text(text, encoding="utf-8")
    CHANGELOG.write_text(changelog, encoding="utf-8")
    for c in changesets:
        c.path.unlink()
    if args.pr_body:
        Path(args.pr_body).write_text(render_pr_body(plan, section), encoding="utf-8")

    for w in warnings:
        warn(w)
    print(f"{plan.current} → {plan.next}", file=sys.stderr)
    print(f"  updated: {', '.join([rel(VERSION_FILE), rel(CHANGELOG), *map(rel, rewrites)])}", file=sys.stderr)
    print(f"  consumed: {', '.join(c.name for c in changesets)}", file=sys.stderr)
    print(plan.next)
    return 0


def cmd_notes(args: argparse.Namespace) -> int:
    target = args.version.lstrip("v")
    lines = CHANGELOG.read_text(encoding="utf-8").splitlines()
    heading = re.compile(rf"^## \[?{re.escape(target)}\]?(?:\s|$)")
    start = next((i for i, line in enumerate(lines) if heading.match(line)), None)
    if start is not None:
        end = next((i for i in range(start + 1, len(lines)) if lines[i].startswith("## ")), len(lines))
        print("\n".join(lines[start + 1 : end]).strip())
        return 0

    # No section yet: a pre-release, cut before its release PR merged. Its
    # notes are whatever is pending.
    changesets = load_changesets()
    if not changesets:
        print(f"no changelog section for {target} and no pending changesets", file=sys.stderr)
        return 1
    annotate_history(changesets, github_repo())
    print(f"Pre-release of the changes pending for the next version.\n\n{render_entries(changesets, github_repo())}".strip())
    return 0


def cmd_current(args: argparse.Namespace) -> int:
    if args.ref:
        result = subprocess.run(
            ["git", "show", f"{args.ref}:{rel(VERSION_FILE)}"], cwd=ROOT, capture_output=True, text=True, check=False
        )
        if result.returncode != 0 or not VERSION_LINE.search(result.stdout):
            print(f"no version in {rel(VERSION_FILE)} at {args.ref}", file=sys.stderr)
            return 1
        print(read_version(result.stdout))
    else:
        print(read_version())
    return 0


def cmd_snapshot(args: argparse.Namespace) -> int:
    # The version under development: what the pending changesets add up to,
    # else the next patch. Never the released version itself — a local build
    # of it in ~/.m2 would shadow the real artifact from Maven Central.
    plan = plan_release(load_changesets(), read_version())
    print(f"{plan.next or plan.current.bump('patch')}-SNAPSHOT")
    return 0


# --- entry point -------------------------------------------------------------------


def warn(message: str) -> None:
    if os.environ.get("GITHUB_ACTIONS"):
        print(f"::warning::{message}", file=sys.stderr)
    else:
        print(f"warning: {message}", file=sys.stderr)


def report(error: ChangesetError) -> None:
    in_actions = bool(os.environ.get("GITHUB_ACTIONS"))
    for path, line, message in error.problems:
        where = rel(path) if path else ""
        if in_actions:
            loc = f" file={where}" + (f",line={line}" if line else "") if where else ""
            print(f"::error{loc}::{message}", file=sys.stderr)
        else:
            prefix = f"{where}:{line}: " if where and line else (f"{where}: " if where else "")
            print(f"error: {prefix}{message}", file=sys.stderr)


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        prog="changeset.py", description=__doc__.split("\n\n")[0].split("—", 1)[-1].strip()
    )
    sub = parser.add_subparsers(dest="command", required=True)

    new = sub.add_parser("new", help="add a changeset for the current branch")
    new.add_argument("--title", help="one line: what changed")
    new.add_argument("--change", type=str.lower, choices=list(reversed(LEVELS)), help="the semver level")
    new.add_argument("--description", help="a sentence or two for consumers")
    new.add_argument("--body", help="the full Markdown note (default: a placeholder to fill in)")
    new.add_argument("--name", help="file name (default: the branch name's last segment)")
    new.set_defaults(run=cmd_new)

    check = sub.add_parser("check", help="validate every pending changeset")
    check.add_argument(
        "--require-since", metavar="REF", help="also fail unless HEAD adds or edits a changeset since REF (the PR check)"
    )
    check.set_defaults(run=cmd_check)

    status = sub.add_parser("status", help="pending changesets and the next version")
    status.add_argument("--json", action="store_true", help="machine-readable output")
    status.set_defaults(run=cmd_status)

    version = sub.add_parser("version", help="apply the pending changesets (the Release PR workflow's job)")
    version.add_argument("--pr-body", metavar="FILE", help="also write the release PR's description here")
    version.set_defaults(run=cmd_version)

    notes = sub.add_parser("notes", help="print one version's release notes")
    notes.add_argument("version")
    notes.set_defaults(run=cmd_notes)

    current = sub.add_parser("current", help="print the version in gradle.properties")
    current.add_argument("--ref", help="read it at this git ref instead of the working tree")
    current.set_defaults(run=cmd_current)

    snapshot = sub.add_parser("snapshot", help="print the version under development as X.Y.Z-SNAPSHOT")
    snapshot.set_defaults(run=cmd_snapshot)

    args = parser.parse_args(argv)
    try:
        return args.run(args)
    except ChangesetError as e:
        report(e)
        return 1
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
