# ssdp-kmp

![iOS 18+](https://img.shields.io/badge/iOS-18%2B-blue.svg?style=for-the-badge&logo=apple)
![macOS 15+](https://img.shields.io/badge/macOS-15%2B-blue.svg?style=for-the-badge&logo=apple)
![Android 11+](https://img.shields.io/badge/Android-11%2B-3DDC84.svg?style=for-the-badge&logo=android&logoColor=white)
![JVM 21+](https://img.shields.io/badge/JVM-21%2B-orange.svg?style=for-the-badge&logo=openjdk&logoColor=white)
![Kotlin 2.3](https://img.shields.io/badge/Kotlin-2.3-7F52FF.svg?style=for-the-badge&logo=kotlin&logoColor=white)
![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg?style=for-the-badge)

A Kotlin Multiplatform **SSDP / UPnP discovery client** for iOS, macOS,
Android, and the JVM:

## Features

- Sends multiple multicast on a stepped cadence (1s → 3s → 10s → 60s) to solve the UDP packets loss over unreliable Wi-Fi
- Listens passively for **NOTIFY** advertisements (`ssdp:alive`,
  `ssdp:byebye`, `ssdp:update`), for devices that announce themselves.
- Discovered **Device Registry** - devices appear, update, and leave
  (via `byebye`, `CACHE-CONTROL: max-age` expiry, or a network change)
- Lazily fetches and caches each device's **UPnP description document** (the
  XML at its `LOCATION`), parsed into a friendly-name / manufacturer / model / services / icons tree.

## Modules

Published to Maven Central:

| Module | Coordinate | Description |
| ------ | ---------- | ---------- |
| <code>:ssdp</code> | <code>com.happycodelucky.ssdp:ssdp</code> | Client library |
| <code>:ssdp&#8209;testing</code> | <code>com.happycodelucky.ssdp:ssdp&#8209;testing</code> | `FakeSsdpClient` + `withFakeSsdpClient { }` for tests |

Not published — repo tools and samples (excluded from the publish/check gate):

| Module | What it is |
|--------|------------|
| <code>:ssdp&#8209;bridge</code> | Host-side daemon that relays SSDP to an Android emulator over TCP (see [Emulator bridge](#android-emulators)). Run with `mise run app:bridge`. |
| <code>:jvm&#8209;cli</code> | Command-line discovery harness — scans the LAN and prints devices + descriptions. Run with `mise run cli` |
| <code>:androidApp</code> | The Android Compose sample scanner (see [apps/](apps/)) |

## Quick examples

### Kotlin

```kotlin
// One factory for all platforms.
// Note: Android it captures the application Context at startup, so no argument is needed (
//       emulators: see "Emulator bridge" below).
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

> `onEnum(of:)` is how you switch a Kotlin sealed type in Swift — it's SKIE's
> intended API, not a workaround. SKIE already generates an exhaustive Swift enum
> for every sealed type (`DeviceChange`, `SearchTarget`, `Notification`,
> `DescriptionResult`); a Kotlin sealed value arrives as a class instance, and
> `onEnum(of:)` maps it to that enum so the `switch` is exhaustive with no
> `default`. There's no SKIE setting that removes the call.

### Listener callback (alternative to observing `changes`)

Prefer a registered callback over a `for await` loop? Implement `SsdpDeviceListener`
and register it — a thin fan-out over the same `changes` stream (the flows stay
available and unchanged):

```swift
final class Listener: SsdpDeviceListener {
    func onFound(device: DiscoveredDevice) { add(device) }
    func onUpdated(device: DiscoveredDevice) { update(device) }
    func onRemoved(device: DiscoveredDevice, reason: DeviceChangeRemovedReason) { remove(device) }
}
let listener = Listener()
client.addListener(listener)
// … later
client.removeListener(listener)   // all listeners are also dropped on close()
```

```kotlin
val listener = object : SsdpDeviceListener {
    override fun onFound(device: DiscoveredDevice) { add(device) }
    override fun onUpdated(device: DiscoveredDevice) { update(device) }
    override fun onRemoved(device: DiscoveredDevice, reason: DeviceChange.Removed.Reason) { remove(device) }
}
client.addListener(listener)
```

## Platforms & permissions

### iOS

joining `239.255.255.250` requires the `com.apple.developer.networking.multicast` entitlement (Apple grants it via a [request form](https://developer.apple.com/contact/request/networking-multicast)). Plain-`http://` description fetches to LAN IPs need ATS `NSAllowsLocalNetworking` and an `NSLocalNetworkUsageDescription`.

### macOS

A sandboxed app needs **both** `com.apple.security.network.client` (outbound) **and** `com.apple.security.network.server` (the sandbox treats `bind()` on port 1900 as a server op). No multicast entitlement needed.

### Android

The library manifest contributes `INTERNET` / `ACCESS_WIFI_STATE` / `CHANGE_WIFI_MULTICAST_STATE`. Use `Ssdp.createClient()` — the library captures the application `Context` at startup (an androidx.startup `SsdpInitializer`) and holds a `WifiManager.MulticastLock` for you, so no `Context` argument is needed. (The explicit `SsdpClient(context)` factory remains for callers who disable androidx.startup.) Without the lock Android drops inbound multicast. Apps on Android 13+ also declare `NEARBY_WIFI_DEVICES`.

#### Android emulators

Emulators sit behind a user-mode NAT and **never receive inbound UDP multicast**, so normal discovery hears nothing there. Run the bridge daemon on your host (`mise run app:bridge`) and build the client with `Ssdp.createBridgeAwareClient()` — its `useBridge` defaults to `isSsdpBridgeNeeded()`, so on an emulator it tunnels SSDP over TCP to the daemon (which does the real multicast on the host LAN) and on a device it's a normal multicast client. The client is otherwise identical (same registry, retransmit, `search`/`description`). The library never silently swaps transport on a plain `createClient()` — but `createBridgeAwareClient()` opts into the auto-decision, pass `useBridge = false`/`true` to override, and either way it logs a warning if you build a multicast client on a likely emulator.

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

### JVM

Plain `MulticastSocket`; on multi-homed hosts pass `bindInterface`.



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
| JVM CLI | `mise run cli` |
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
mise run cli        # live LAN discovery harness
mise run build:xcframework  # assemble the release Ssdp.xcframework
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

Released under the [MIT License](LICENSE).
