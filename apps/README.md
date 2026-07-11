# ssdp-kmp sample apps

Samples that exercise the `:ssdp` library against real hardware. Each scans the
local network for SSDP/UPnP devices and (except the CLI and bridge) lets you tap
a device to fetch and view its UPnP description document.

| App | Path | How to run |
|-----|------|-----------|
| **JVM CLI** | `apps/cli` | `mise run cli` (optional duration: `mise run cli -- 20`) |
| **SSDP bridge daemon** | `ssdp-bridge` (repo root) | `mise run app:bridge` (optional port: `mise run app:bridge -- 1901`) |
| **Android** | `apps/android` | `mise run open:android`, or `./gradlew :androidApp:installDebug` |
| **iOS** | `apps/ios` | `mise run open:ios` |
| **macOS** | `apps/macos` | `mise run open:macos` |

The **bridge daemon** is not a scanner — it runs on your host so an **Android
emulator** (which can't receive inbound UDP multicast) can discover real LAN
devices. Start it, then build the Android sample on an emulator: it constructs
its client with `Ssdp.createBridgeAwareClient()` (whose `useBridge` defaults to
`isSsdpBridgeNeeded()`), so on an emulator it tunnels to the daemon and on a
physical device it uses the normal multicast client (the bridge unused).

All three GUI apps show the same scanner → detail flow: a list of discovered
devices (grouped by **UDN**, so a device's many service-USNs collapse to one
row), and a detail screen that calls `client.description(...)` to show friendly
name, manufacturer, model, services, and icons.

## Apple apps (iOS + macOS)

Standalone Xcode projects generated from `project.yml` via
[xcodegen](https://github.com/yonaskolb/XcodeGen), consuming the `Ssdp`
framework as a local Swift Package (the repo-root `Package.swift`). They share
all SwiftUI code in `apps/shared`; only the `@main` App entry, `Info.plist`, and
entitlements are per-platform.

**Iteration loop** (pick up local Kotlin edits):

```sh
mise run spm:dev      # build debug Ssdp.xcframework + point Package.swift at it
mise run open:ios     # (or open:macos) — regenerates the .xcodeproj and opens Xcode
# … edit Kotlin … re-run spm:dev … rebuild in Xcode …
mise run spm:restore  # restore the committed Package.swift before committing
```

The generated `.xcodeproj` and the local-dev `Package.swift` rewrite are
working-tree-only — both are gitignored / restored, never committed.

### Network setup

- **iOS** needs the `com.apple.developer.networking.multicast` entitlement to
  join `239.255.255.250` (already in `apps/ios/iOSApp/iOSApp.entitlements`).
  Apple gates it behind a [request form](https://developer.apple.com/contact/request/networking-multicast),
  so the app won't join the group on a free personal team until it's granted.
- Both Apple apps declare `NSLocalNetworkUsageDescription` (the local-network
  prompt) and ATS `NSAllowsLocalNetworking` (so the description fetch can reach
  plain-`http://` LAN `LOCATION` URLs).
- **macOS** is the easiest to run: no simulator/device and no multicast
  entitlement needed — just the sandbox `network.client` capability.

## Android app

A Gradle subproject (`:androidApp`) using Jetpack Compose. Depends on `:ssdp`
directly. The library manifest contributes the multicast permissions; the app
adds `NEARBY_WIFI_DEVICES` (Android 13+). Constructs its client with
`Ssdp.createBridgeAwareClient()` (zero-arg; `useBridge` defaults to
`isSsdpBridgeNeeded()`) — no Context threading (the library captures it via
`SsdpInitializer` at startup) and no hand-rolled emulator check.
