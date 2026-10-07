---
title: search() returns a SearchSession, and searches are additive
change: major
description: SsdpClient.search now returns a SearchSession handle and adds to the searches already running instead of replacing them; the client searches for the union of every active session's targets.
---

Until now each `search()` call **replaced** the client's previous search: the
earlier call's retransmission was cancelled. Two parts of an app sharing one
`SsdpClient` therefore cancelled each other, and a consumer that wanted
overlapping scans had to union every open scan's targets and call
`search(union)` again whenever one opened or closed.

`search()` now returns a `SearchSession`, an `AutoCloseable` handle with
`targets`, `isActive` and `close()`. Each session contributes its targets until it
ends, and the client searches for the union of every active session's targets:

- **One loop per target.** A target in several active sessions has a single
  retransmit loop, reference-counted by those sessions and kept until the last of
  them ends. Each M-SEARCH for it advertises the largest `maxWaitSeconds` among
  them.
- **Nothing restarts.** A target new to the client starts its own cadence at the
  1s step. Targets already being searched keep theirs. A target that is already
  being searched gets one extra M-SEARCH when another session joins it, so the
  new session hears fresh replies straight away.
- **A session ends** when you `close()` it, when its `timeout` elapses, when
  `stopSearch()` runs, or when the client closes. Ending is permanent and
  idempotent: `isActive` turns `false` and later `close()` calls do nothing.
  `close()` doesn't suspend and is safe from any thread.
- **`timeout` is per session.** It ends only that session's contribution;
  another session that includes the same target keeps it searched.

Passive NOTIFY listening and the device registry are unchanged.

### Migrating

Kotlin source keeps compiling: discarding the returned session is legal. Two
behaviors change.

1. A second `search()` no longer stops the first. If you relied on replacement,
   close the previous session (or call `stopSearch()`) before searching again.
2. `search(emptySet())` no longer stops searching. It returns an inactive session
   and leaves the others alone. Use `stopSearch()` to end every session.

A session opened without a `timeout` keeps searching until it is closed, so keep
the handle.

```kotlin
// Before: one search at a time; overlapping scans had to union their targets
// and call search() again on every change.
client.search(scanA + scanB)
// … scan B ends
client.search(scanA)

// After: each scan holds its own session on the shared client.
val a = client.search(scanA)
val b = client.search(scanB, timeout = 10.seconds)
b.close() // withdraws only scanB's targets; scanA keeps its cadence
```

Swift: `search(targets:maxWaitSeconds:timeout:)` now returns a `SearchSession`,
and Swift warns when the result is unused. Its `targets` is a `Set<AnyHashable>`
of `SearchTarget`s, the shape `search(targets:)` takes.

```swift
// Before
try await client.search(targets: [SearchTargetAll.shared], maxWaitSeconds: 1, timeout: nil)
try await Task.sleep(for: .seconds(6))
try await client.stopSearch()

// After
let search = try await client.search(targets: [SearchTargetAll.shared], maxWaitSeconds: 1, timeout: nil)
try await Task.sleep(for: .seconds(6))
search.close()
```

### `:ssdp-testing`

`FakeSsdpClient.search` returns a session too, modeled like the real client's:
`openedSessions` lists every session returned, `openSessions` the active ones, and
`searchingTargets` is their union. `stopSearch()` and `close()` end every session.
`searchedTargets`, `searchedTimeouts` and `searchCallCount` still record each
call. The fake keeps no clock, so to model a timeout elapsing, close the session.

### Implementing `SsdpClient` yourself

`search` must now return a `SearchSession`. A test double can return its own
implementation of the interface.
