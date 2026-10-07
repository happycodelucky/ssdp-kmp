# Changelog

Every release of Ssdp, newest first. Each entry is assembled from the
changesets merged since the previous release, when its release PR is opened
(`.changeset/README.md`). Releases up to v0.6.0 predate changesets — their
notes are on [GitHub Releases](https://github.com/happycodelucky/ssdp-kmp/releases).

<!-- changesets: the Release PR workflow inserts each new release below this line. Keep it. -->

## 0.8.0 — 2026-10-07

### Breaking changes

#### description() returns KotlinResult's Result instead of DescriptionResult ([#13](https://github.com/happycodelucky/ssdp-kmp/pull/13))

SsdpClient.description now returns com.happycodelucky.kotlinresult.Result<DeviceDescription>, failing with a sealed DescriptionException; DescriptionResult is removed.

`SsdpClient.description(device)` and `description(usn)` now return
[KotlinResult](https://github.com/happycodelucky/kotlinresult-kmp)'s
`Result<DeviceDescription>`, the same result type the sibling wake-kmp library
uses. A failure holds a sealed `DescriptionException`. The sealed
`DescriptionResult` type is gone.

In Kotlin, `Result` has the standard `kotlin.Result` API (`onSuccess`,
`onFailure`, `fold`, `getOrNull`, `exceptionOrNull`, …). Swift gets
`KotlinResult<DeviceDescription>`, and `try r.get()` throws the
`DescriptionException` itself.

| Before | After |
|---|---|
| `DescriptionResult.Success(description)` | a success holding the `DeviceDescription` |
| `DescriptionResult.NotFound` | `DescriptionException.NotFound(usn)` |
| `DescriptionResult.FetchFailed(statusCode, message)` | `DescriptionException.FetchFailed(statusCode, message, cause)` |
| `DescriptionResult.ParseFailed(message)` | `DescriptionException.ParseFailed(message, cause)` |

###### Migrating

Kotlin:

```kotlin
import com.happycodelucky.kotlinresult.Result // not kotlin.Result

// Before
when (val r = client.description(device)) {
    is DescriptionResult.Success -> show(r.description)
    DescriptionResult.NotFound -> showUnknown()
    is DescriptionResult.FetchFailed -> retryLater(r.statusCode)
    is DescriptionResult.ParseFailed -> log(r.message)
}

// After
client.description(device)
    .onSuccess { show(it) }
    .onFailure { e ->
        when (e as? DescriptionException) {
            is DescriptionException.NotFound -> showUnknown()
            is DescriptionException.FetchFailed -> retryLater(e.statusCode)
            is DescriptionException.ParseFailed -> log(e.message)
            null -> throw e
        }
    }
```

Swift:

```swift
// Before
switch onEnum(of: try await client.description(device: device)) {
case .success(let s): show(s.description_)
...
}

// After
do {
    let description: DeviceDescription = try await client.description(device: device).get()
    show(description)
} catch let e as DescriptionException {
    switch onEnum(of: e) {
    case .notFound: showUnknown()
    case .fetchFailed(let f): retryLater(f.statusCode)
    case .parseFailed(let p): log(p.message)
    }
}
```

`:ssdp-testing`: `FakeSsdpClient.stubDescription(usn, result)` and
`defaultDescriptionResult` take a `Result<DeviceDescription>`
(`Result.success(description)` or `Result.failure(DescriptionException…)`).
`defaultDescriptionResult` is now nullable, and `null` (the default) fails with
`NotFound`.

###### Linking ssdp into your own Apple framework

`ssdp` depends on `com.happycodelucky.kotlinresult:kotlinresult` as an API
dependency. SPM users of `SsdpKit` need to do nothing. If you link `ssdp` into
your **own** framework with SKIE, `export(libs.kotlinresult)` into it, or the link
fails with "cannot find type 'KotlinResult' in scope".

#### search() returns a SearchSession, and searches are additive ([#15](https://github.com/happycodelucky/ssdp-kmp/pull/15))

SsdpClient.search now returns a SearchSession handle and adds to the searches already running instead of replacing them; the client searches for the union of every active session's targets.

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

###### Migrating

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

###### `:ssdp-testing`

`FakeSsdpClient.search` returns a session too, modeled like the real client's:
`openedSessions` lists every session returned, `openSessions` the active ones, and
`searchingTargets` is their union. `stopSearch()` and `close()` end every session.
`searchedTargets`, `searchedTimeouts` and `searchCallCount` still record each
call. The fake keeps no clock, so to model a timeout elapsing, close the session.

###### Implementing `SsdpClient` yourself

`search` must now return a `SearchSession`. A test double can return its own
implementation of the interface.

#### Build clients with SsdpClient() only; the Ssdp object is gone ([#15](https://github.com/happycodelucky/ssdp-kmp/pull/15))

Ssdp.createClient() becomes SsdpClient() and Android's Ssdp.createBridgeAwareClient() becomes SsdpClient.bridgeAware(); on Android a plain SsdpClient() now holds the multicast lock.

There were two ways to build a client: the type-named factories
(`SsdpClient()`, Android's `SsdpClient(context)` and `SsdpClient.bridged()`) and
a per-platform `object Ssdp` (`createClient()`, plus Android's
`createBridgeAwareClient()`). They had drifted apart on Android. Only
`Ssdp.createClient()` used the application `Context` that `SsdpInitializer`
captures at startup, so a plain `SsdpClient()` held no `WifiManager.MulticastLock`
and on many devices the Wi-Fi driver dropped inbound multicast. The `Ssdp`
object is removed on every platform, and the type-named factories are the only
way to build a client.

###### Android: `SsdpClient()` now holds the multicast lock

`SsdpClient()` takes the lock from the startup-captured Context and logs a warning
on a likely emulator, as `Ssdp.createClient()` did. If your app disables
androidx.startup's `InitializationProvider`, nothing is captured: `SsdpClient()`
then opens without the lock and logs a warning naming `SsdpClient(context)`, which
is the fix. `SsdpClient(context)` now logs the emulator warning too.

###### Migrating

| Before | After |
|---|---|
| `Ssdp.createClient(bindInterface)` | `SsdpClient(bindInterface)` |
| `Ssdp.createBridgeAwareClient(useBridge, host, port)` (Android) | `SsdpClient.bridgeAware(useBridge, host, port)` |

`bridgeAware` has the same defaults (`useBridge = isSsdpBridgeNeeded()`,
`host = EMULATOR_HOST_LOOPBACK`, `port = 1901`) and the same behavior: the bridge
on an emulator, otherwise the normal multicast client.

```kotlin
// Before
val client = Ssdp.createClient()
val androidClient = Ssdp.createBridgeAwareClient()

// After
val client = SsdpClient()
val androidClient = SsdpClient.bridgeAware() // import com.happycodelucky.ssdp.bridgeAware
```

Swift: `Ssdp.shared.createClient(bindInterface:)` becomes
`SsdpClient(bindInterface:)`.

```swift
// Before
let client = try Ssdp.shared.createClient(bindInterface: nil)

// After
let client = try SsdpClient(bindInterface: nil)
```

### Features

#### Ship llms.txt and llms-full.txt inside every published artifact ([#11](https://github.com/happycodelucky/ssdp-kmp/pull/11))

Every published jar and the Android AAR now carry llms.txt and llms-full.txt, the module's public API with its KDoc, for AI coding tools.

Each module generates the pair from its Dokka Markdown output at publish time
and packs it into every jar (jvm, metadata, sources, javadoc) and the AAR under
`META-INF/com.happycodelucky.ssdp/<artifactId>/`:

- `llms.txt` indexes the artifact: what it is, its coordinates, and links to
  `llms-full.txt`, the README (installation and per-platform setup) and the
  changelog.
- `llms-full.txt` is the module's full public API with its documentation,
  stamped with the version it ships in.

An AI tool that has the dependency (in a Gradle cache, a sources jar or an AAR)
can read the library's API without network access. The files are namespaced by
coordinates, so they can't collide with another library's `llms.txt` on your
classpath. In the AAR they sit at the archive root, so they never end up in your
APK. There is no API change and nothing to do.

`minor` rather than `patch`: this adds new artifact contents rather than fixing
anything.

#### Remember device descriptions per network ([#13](https://github.com/happycodelucky/ssdp-kmp/pull/13))

Descriptions fetched on a network are kept when you leave it and restored when you return, so a device re-found at the same LOCATION needs no refetch.

Until now a network change dropped every cached description along with the
device registry, so going home → café → home refetched each device's
description from scratch.

Now, when the network changes, the successful descriptions from the network
you're leaving are parked under that network (identified by transport and IPv4
subnet). When you come back, each device's description is restored as soon as
the device is re-found at the same `LOCATION`. `cachedDescription` returns it
again and `description` serves it without an HTTP request.

- A device that comes back at a different `LOCATION` is fetched fresh.
- A device that says `byebye` or expires loses its parked description.
- Up to 4 networks are remembered, for 24 hours each. The least recently left
  network is dropped first.
- When the subnet can't be determined, nothing is parked, because two LANs
  could look the same.

Nothing changes in the API and there's nothing to do. `description(device,
refresh = true)` still forces a refetch.

### Fixes

#### Stop forcing compileSdk 37 on Android consumers ([#11](https://github.com/happycodelucky/ssdp-kmp/pull/11))

The Android AAR now declares minCompileSdk 34 instead of inheriting the library's own compileSdk 37.

AGP writes a library AAR's `minCompileSdk` from the compileSdk the library was
built with, and every consuming app's `check<Variant>AarMetadata` enforces it.
Through v0.7.0 that was 37 — raised only for our sample app — so any app on
compileSdk 36 or lower failed its release build with an AAR-metadata error.

The AAR now declares `minCompileSdk = 34` (Android 14) explicitly. Apps on
compileSdk 34 or newer can consume ssdp again; nothing else changes, and there
is no API change. Transitive dependencies (e.g. `androidx.startup`) still carry
their own floors.

#### Send M-SEARCH from an ephemeral port so every client gets its replies ([#14](https://github.com/happycodelucky/ssdp-kmp/pull/14))

M-SEARCH now leaves from its own ephemeral-port socket, so a device's unicast reply reaches the client that asked even when other sockets on the host share port 1900.

`SsdpClient` used to send M-SEARCH from the same UDP socket it binds to port
1900 for NOTIFY. Devices answer an M-SEARCH by unicast to the request's source
port, so every reply went to port 1900. When more than one socket on the host
shares 1900 through address reuse (two `SsdpClient`s in one process, or a
browser, Spotify or a media server running alongside), the operating system
delivers a unicast datagram to only one of those sockets, and the other client
silently missed the reply. On a real LAN, a JVM consumer running three clients
found a Roku answering `roku:ecp` in 1 of 5 scans.

Each client now opens two sockets, as UPnP control points do: the 1900 socket
joined to `239.255.255.250` still hears `ssdp:alive` / `ssdp:byebye` /
`ssdp:update`, and M-SEARCH goes out on a second socket bound to an ephemeral
port, which receives the replies. This applies on every platform (JVM, Android,
iOS and macOS) and to the emulator bridge daemon. There is no API change:
`devices`, `changes`, `search()` and the factories behave as before.

Consumer obligations are unchanged. A sandboxed macOS app already needs
`com.apple.security.network.server` to bind, and iOS still needs the multicast
entitlement to join the group.

`main` also carries an unreleased breaking change (`description()` returning
KotlinResult's `Result`), so the next release's version is decided by all the
pending changesets together, not by this patch.

## 0.7.0 — 2026-09-25

### Breaking changes

#### Rename the Apple framework and Swift module to SsdpKit ([#9](https://github.com/happycodelucky/ssdp-kmp/pull/9))

Swift and SPM consumers now import SsdpKit instead of Ssdp, and the Ssdp factory object is no longer renamed Ssdp_ in Swift.

The XCFramework, its Swift module and the Swift package product are now
`SsdpKit` (the release asset is `SsdpKit.xcframework.zip`). The old module name
`Ssdp` collided with the library's `Ssdp` factory object, so SKIE exported that
object to Swift as `Ssdp_`. With the module renamed, it's plain `Ssdp` again.

**Swift / SPM migration**

```swift
// Before
import Ssdp
let client = try Ssdp_.shared.createClient(bindInterface: nil)

// After
import SsdpKit
let client = try Ssdp.shared.createClient(bindInterface: nil)
```

In Xcode or `Package.swift`, depend on the `SsdpKit` product of
`https://github.com/happycodelucky/ssdp-kmp` (was `Ssdp`).

Kotlin, Android and JVM consumers aren't affected: Maven coordinates and
packages are unchanged.

### Fixes

#### Expose kotlinx-coroutines and androidx.startup as api dependencies ([#9](https://github.com/happycodelucky/ssdp-kmp/pull/9))

The published metadata now puts kotlinx-coroutines-core (and, on Android, androidx.startup) on consumers' compile classpath, matching the types the public API exposes.

SsdpClient.devices and changes are a StateFlow and a SharedFlow, and the Android SsdpInitializer implements androidx.startup's Initializer, but both libraries were published as runtime-only (implementation) dependencies. A consumer that collected the flows without declaring kotlinx-coroutines itself could fail to compile. They're now api dependencies of ssdp (and coroutines of ssdp-testing), so no consumer change is needed; builds that already declare coroutines are unaffected.
