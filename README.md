# ssdp-kmp

![iOS 18+](https://img.shields.io/badge/iOS-18%2B-blue.svg?style=for-the-badge&logo=apple)
![macOS 15+](https://img.shields.io/badge/macOS-15%2B-blue.svg?style=for-the-badge&logo=apple)
![Android 11+](https://img.shields.io/badge/Android-11%2B-3DDC84.svg?style=for-the-badge&logo=android&logoColor=white)
![JVM 21+](https://img.shields.io/badge/JVM-21%2B-orange.svg?style=for-the-badge&logo=openjdk&logoColor=white)
![Kotlin 2.3](https://img.shields.io/badge/Kotlin-2.3-7F52FF.svg?style=for-the-badge&logo=kotlin&logoColor=white)
![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg?style=for-the-badge)

A Kotlin Multiplatform **SSDP / UPnP discovery client** for iOS, macOS,
Android, and the JVM, behind one API:

- Sends multicast **M-SEARCH** queries on `239.255.255.250:1900` and
  retransmits them on a stepped cadence (1s → 3s → 10s → 60s) to fill in for
  UDP packets lost over flaky Wi-Fi.
- Listens passively for **NOTIFY** advertisements (`ssdp:alive`,
  `ssdp:byebye`, `ssdp:update`).
- Maintains a live **device registry** — devices appear, update, and leave
  (via `byebye`, `CACHE-CONTROL: max-age` expiry, or a network change), exposed
  as a `StateFlow` and a delta `SharedFlow`.
- Lazily fetches and caches each device's **UPnP description document** (the
  XML at its `LOCATION`), parsed into a friendly-name / manufacturer / model /
  services / icons tree.

The socket layer is per-platform but the parser, retransmit cadence, registry,
and cache are all shared Kotlin: POSIX BSD multicast sockets on Apple
(iOS + macOS, via Kotlin/Native cinterop), `java.net.MulticastSocket` on Android
(with a `WifiManager.MulticastLock`) and the JVM. HTTP (Ktor CIO) and XML
(xmlutil) are shared too.

UI is out of scope — ssdp-kmp is the headless `:ssdp` KMP module (CLAUDE.md §1).
Each platform app consumes it natively; see [`apps/`](apps/) for sample
scanners on every platform.

## Modules

Published to Maven Central:

| Module | Coordinate | What it is |
|--------|-----------|-----------|
| `:ssdp` | `com.happycodelucky.ssdp:ssdp` | The library. |
| `:ssdp-testing` | `com.happycodelucky.ssdp:ssdp-testing` | `FakeSsdpClient` + `withFakeSsdpClient { }` for tests. |

Not published — repo tools and samples (excluded from the publish/check gate):

| Module | What it is |
|--------|-----------|
| `:ssdp-bridge` | Host-side daemon that relays SSDP to an Android emulator over TCP (see [Emulator bridge](#emulator-bridge)). Run with `mise run app:bridge`. |
| `:jvm-cli` | Command-line discovery harness — scans the LAN and prints devices + descriptions. Run with `mise run app:cli`. |
| `:androidApp` | The Android Compose sample scanner (see [apps/](apps/)). |

## Quick example

### Kotlin

```kotlin
// One factory on every platform. On Android it captures the application Context
// at startup, so no argument is needed (emulators: see "Emulator bridge" below).
val client: SsdpClient = Ssdp.createClient()

// Search every SSDP target; stop broadcasting after 6s (passive listening and
// the discovered devices persist). Omit `timeout` to broadcast indefinitely.
client.search(setOf(SearchTarget.All), timeout = 6.seconds)

// The always-current device set, keyed by USN.
client.devices.collect { byUsn ->
    byUsn.values.forEach { device -> println("${device.usn} @ ${device.location}") }
}

// Or react to deltas.
client.changes.collect { change ->
    when (change) {
        is DeviceChange.Found -> add(change.device)
        is DeviceChange.Updated -> update(change.device)
        is DeviceChange.Removed -> remove(change.device) // reason: Byebye / Expired / NetworkChanged
    }
}

// Fetch a device's description on demand (cached; concurrent calls share one fetch).
when (val result = client.description(device)) {
    is DescriptionResult.Success -> show(result.description.device.friendlyName)
    DescriptionResult.NotFound -> showUnknown()
    is DescriptionResult.FetchFailed -> retryLater(result.statusCode)
    is DescriptionResult.ParseFailed -> log(result.message)
}

client.close()
```

Search for several specific targets in one session — fanned out over the shared
socket, merged into the one registry:

```kotlin
client.search(
    setOf(
        SearchTarget.DeviceType("schemas-upnp-org", "MediaServer", 1),
        SearchTarget.DeviceType("schemas-upnp-org", "MediaRenderer", 1),
    ),
)
```

### Swift

The same flows bridge to `AsyncSequence` and the sealed types to exhaustive Swift
enums via SKIE:

```swift
let client = try SsdpClient(bindInterface: nil)
try await client.search(targets: [SearchTargetAll.shared], maxWaitSeconds: 1, timeout: nil)

for await byUsn in client.devices {
    render(Array(byUsn.values))
}

switch onEnum(of: try await client.description(device: device)) {
case .success(let s): show(s.description_.device.friendlyName)
case .notFound: showUnknown()
case .fetchFailed(let f): retryLater(f.statusCode)
case .parseFailed(let p): log(p.message)
}
```

## Platforms & permissions

- **iOS** — joining `239.255.255.250` requires the
  `com.apple.developer.networking.multicast` entitlement (Apple grants it via a
  [request form](https://developer.apple.com/contact/request/networking-multicast)).
  Plain-`http://` description fetches to LAN IPs need ATS `NSAllowsLocalNetworking`
  and an `NSLocalNetworkUsageDescription`.
- **macOS** — a sandboxed app needs **both** `com.apple.security.network.client`
  (outbound) **and** `com.apple.security.network.server` (the sandbox treats
  `bind()` on port 1900 as a server op). No multicast entitlement needed.
- **Android** — the library manifest contributes
  `INTERNET` / `ACCESS_WIFI_STATE` / `CHANGE_WIFI_MULTICAST_STATE`. Use
  `Ssdp.createClient()` — the library captures the application `Context` at
  startup (an androidx.startup `SsdpInitializer`) and holds a
  `WifiManager.MulticastLock` for you, so no `Context` argument is needed. (The
  explicit `SsdpClient(context)` factory remains for callers who disable
  androidx.startup.) Without the lock Android drops inbound multicast. Apps on
  Android 13+ also declare `NEARBY_WIFI_DEVICES`.
- **Android emulators** — emulators sit behind a user-mode NAT and **never
  receive inbound UDP multicast**, so normal discovery hears nothing there. Run
  the bridge daemon on your host (`mise run app:bridge`) and build the client with
  `Ssdp.createBridgeAwareClient()` — its `useBridge` defaults to
  `isSsdpBridgeNeeded()`, so on an emulator it tunnels SSDP over TCP to the daemon
  (which does the real multicast on the host LAN) and on a device it's a normal
  multicast client. The client is otherwise identical (same registry, retransmit,
  `search`/`description`). The library never silently swaps transport on a plain
  `createClient()` — but `createBridgeAwareClient()` opts into the auto-decision,
  pass `useBridge = false`/`true` to override, and either way it logs a warning if
  you build a multicast client on a likely emulator. See
  [Emulator bridge](#emulator-bridge).
- **JVM** — plain `MulticastSocket`; on multi-homed hosts pass `bindInterface`.

### Emulator bridge

```kotlin
// Android: one line, zero args — bridge on an emulator, multicast on a device.
val client = Ssdp.createBridgeAwareClient()
```

Start the host daemon first (it does the real multicast on your LAN):

```sh
mise run app:bridge            # listen on 1901
mise run app:bridge -- 1901    # explicit port
```

`createBridgeAwareClient(useBridge = isSsdpBridgeNeeded(), host = "10.0.2.2", port = 1901)`
is the full signature; `useBridge` and the host/port all default, so the common
call takes no arguments. The lower-level `SsdpClient.bridged(host, port)` is the
building block it delegates to.

The daemon is a **dumb pipe**: the app keeps owning retransmit and the registry,
so the emulator path is byte-identical to a physical device — only the wire hop
differs. It connects over TCP, frames each M-SEARCH to the daemon, and the daemon
streams every reply/NOTIFY it sees back.

## Testing support

`:ssdp-testing` ships a scriptable fake — no real socket, usable from
`commonTest` on every target:

```kotlin
withFakeSsdpClient { fake ->
    fake.emitFound(sampleDevice)
    fake.stubDescription(sampleDevice.usn, DescriptionResult.Success(sampleDescription))
    val vm = DeviceListViewModel(fake)   // takes an SsdpClient
    assertEquals(1, vm.devices.value.size)
}
```

## Sample apps

[`apps/`](apps/) has four scanners — see [apps/README.md](apps/README.md):

| App | Run |
|-----|-----|
| JVM CLI | `mise run app:cli` |
| SSDP bridge daemon (for Android emulators; `ssdp-bridge` at the repo root) | `mise run app:bridge` |
| Android | `mise run open:android` |
| iOS | `mise run open:ios` |
| macOS | `mise run open:macos` |

The CLI prints discovered devices and their fetched descriptions against your
real network; the GUI apps show a scanner → detail flow (devices grouped by UDN,
tap to fetch the description).

## Build and test

Toolchain is pinned via [mise](https://mise.jdx.dev) (`mise install`). Common
tasks:

```sh
mise run check      # ktlint + detekt + all unit tests (iOS sim, macOS, Android host, JVM)
mise run test:jvm   # JVM-only — the fast inner loop
mise run app:cli    # live LAN discovery harness
mise run build      # assemble the release Ssdp.xcframework
mise run open:macos # generate + open the macOS sample in Xcode
```

Or directly: `./gradlew :ssdp:check :ssdp-testing:check`.

## Repository conventions

[`CLAUDE.md`](CLAUDE.md) is the contract: ARM-only native targets, Kotlin-first
libraries, the Swift-interop rules (SKIE, sealed result types, `@Throws`), and
the four product decisions behind the design. `.claude/lessons/LESSONS.md`
records bugs hit and decisions made. Versions live only in
[`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## License

Apache License 2.0. <!-- TODO: add a LICENSE file before the first release. -->
