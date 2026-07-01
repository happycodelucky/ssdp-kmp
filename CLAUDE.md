# CLAUDE.md — ssdp-kmp Project Guide

Kotlin Multiplatform SSDP (UPnP discovery) **client** library for iOS, macOS,
Android, and JVM. Sibling to `/reachable` and `/backgrounder`; this file is the
contract a contributor (human or agent) reads first. Start here, then
`gradle/libs.versions.toml`, then `.claude/lessons/LESSONS.md`.

## 1. Scope

- **Shared (in `:ssdp`):** SSDP wire parsing, multicast send/receive, M-SEARCH
  with retransmit, NOTIFY (alive/byebye/update) handling, and a live device
  registry. Headless — no UI dependencies.
- **Not shared:** UI. Each platform app has its own native UI and consumes the
  library's `StateFlow`/`SharedFlow`.
- **Client only.** No SSDP *server*/responder. We discover; we don't advertise.
- **Behavioral source of truth:** the Swift client at `/Users/paulbates/Developer/swift-ssdp`.
  ssdp-kmp ports its parser, retransmit cadence, and lifecycle, and goes
  *further* with a built-in device registry and per-network reset.
- **Deferred to v1.1 (not in scope now):** description-XML fetch/parse and a
  per-network XML cache. The registry already resets on network change; the XML
  cache builds on that later.

## 2. Decisions (load-bearing — see `.claude/lessons/LESSONS.md` D-001..D-003)

1. **v1 = core + registry, defer XML.** Discovery + retransmit + NOTIFY + a
   device registry (`StateFlow<Map<USN, DiscoveredDevice>>` + `SharedFlow<DeviceChange>`)
   with byebye + `max-age` expiry. No description-XML fetch yet.
2. **Apple socket = POSIX BSD sockets** (`platform.posix`/`platform.darwin`),
   shared 1:1 by iOS+macOS. NOT Network.framework (`NWConnectionGroup` isn't in
   K/N cinterop). See `MulticastSocket.apple.kt`.
3. **Multi-target search in one session.** `search(targets: Set<SearchTarget>)`
   fans out one M-SEARCH per target over the shared socket and merges results.
4. **Per-network reset via reachable + subnet.** Depend on
   `com.happycodelucky.reachable` for the change *signal*; derive the *key* from
   the local IPv4 subnet (no SSID entitlement). The registry resets when the key
   changes (`NetworkMonitor`).

## 3. Versions

Latest stable only — no EAP/RC/Beta on `main`. K2 only. Single source of truth:
`gradle/libs.versions.toml`. **Before bumping anything, web-search the latest
stable** (training data goes stale). The Kotlin pin (`2.3.21`) is bounded above
by SKIE (`0.10.12`). Gradle 9.5.x, AGP 9.2.x, JVM target 21, JDK 21.
**`reachable = "0.14.0"` is a hard floor** — first release with a `jvm` slice,
which our `jvm()` target needs (D-003).

## 4. Targets & module layout

- Targets: `iosArm64`, `iosSimulatorArm64`, `macosArm64`, Android (arm64-v8a),
  and **`jvm()`** (the one target the ARM-only rule doesn't touch — serves
  desktop/server/Linux/Windows). No x86, no Intel Macs, no watchOS/tvOS.
- `applyDefaultHierarchyTemplate { common { group("apple") { withIos(); withMacos() } } }` —
  iOS+macOS coalesce into `appleMain` (shared POSIX socket). Don't hand-roll.
- Module shape lives in the `ssdp.kmp-library` convention plugin
  (`gradle/plugins/`). The only delta from reachable's plugin is the `jvm()`
  block. Adding a module = apply `ssdp.kmp-library` + `ssdp.publish`.
- `expect`/`actual` is intentionally tiny: `openMulticastSocket()`,
  `SsdpClient()`, and `localSubnetKey()`. Everything else is common.
- Modules: `:ssdp` (the library) and `:ssdp-testing` (`FakeSsdpClient` +
  `withFakeSsdpClient`).

## 5. Libraries — Kotlin-first

Ktor/Ktorfit for HTTP (v1.1 XML fetch), kotlinx.* family (coroutines, atomicfu,
io), Kermit for logging, `kotlin.time` for `Duration`/`Instant`/`Clock` (NOT
`java.time` in common — and `kotlin.time.Instant`/`Clock` are stable in 2.3.21,
no opt-in needed). Testing: `kotlin.test` + Turbine + `kotlinx-coroutines-test` +
Kotest (property tests). Library code uses **constructor injection only** — no
Koin/service locator inside `:ssdp`.

## 6. Concurrency

- `kotlinx.coroutines` only. No `GlobalScope`.
- `Flow`/`StateFlow`/`SharedFlow` over callbacks. No callback APIs in common.
- Shared mutable state across suspend boundaries → `kotlinx.coroutines.sync.Mutex`
  (the registry's map). Non-suspending critical sections →
  `kotlinx.atomicfu.locks.synchronized`. Never `kotlin.synchronized`,
  `@Synchronized`, `java.util.concurrent.locks.*`, `volatile`.
- **Inject `Clock` + `CoroutineScope`** into time-driven code (the registry,
  retransmit). Never read wall-clock in timer logic — it breaks `runTest`
  virtual time (LESSONS N-002).
- The client owns a `SupervisorJob` *child* of the scope it's given; `close()`
  cancels only that child, never the caller's scope (LESSONS B-003).

## 7. Swift interop

SKIE mandatory (convention plugin configures it; `produceDistributableFramework()`
in `:ssdp`). `Flow`/`StateFlow` → `AsyncSequence`. Sealed types
(`SearchTarget`, `Notification`, `DeviceChange`, `SsdpError`) → exhaustive Swift
enums. **`@Throws` on an `expect` must be replicated verbatim on every `actual`**,
and a `@Throws` on a `suspend fun` must list `CancellationException`
(LESSONS B-001/B-002). Never `kotlin.Result<T>` at the boundary. Apple casing
everywhere (`iOS`, `macOS`) except JetBrains spellings (`iosArm64`, `withMacos()`).

## 8. Distribution

Two channels, copied from reachable: Maven Central (Android AAR + jvm jar + KMP
metadata + klibs) via `ssdp.publish`/vanniktech; GitHub Releases (SKIE-enhanced
`Ssdp.xcframework` for SPM) via KMMBridge in `ssdp/build.gradle.kts`. Don't
redeclare `XCFramework("Ssdp")` — KMMBridge auto-creates it. CI-only publishing.

## 9. Platform notes

- **iOS — multicast:** joining `239.255.255.250` needs the
  `com.apple.developer.networking.multicast` entitlement (Apple gates it behind
  a request form). Without it, `IP_ADD_MEMBERSHIP` fails → `MulticastJoinFailed`.
- **iOS/macOS — description fetch (v1.1) & App Transport Security:** UPnP
  `LOCATION` URLs are plain `http://` to a LAN IP. iOS/macOS ATS blocks
  arbitrary plain-HTTP by default, so `description()` will fail with
  `FetchFailed` unless the **host app** adds to its `Info.plist`:
  `NSAppTransportSecurity` → `NSAllowsLocalNetworking = true` (App-Store-friendly,
  scoped to RFC-1918 / `.local`, no arbitrary-loads). The library cannot set
  this — it's enforced in the consuming app's plist. Switching Ktor engines
  doesn't help; ATS applies regardless. Document this in the iOS host-app setup
  guide next to the multicast entitlement.
- **Android:** needs a `WifiManager.MulticastLock` (held by the transport) or
  inbound multicast is dropped. Prefer `SsdpClient(context)`. The library
  manifest contributes `INTERNET`/`ACCESS_WIFI_STATE`/`CHANGE_WIFI_MULTICAST_STATE`.
- **macOS — App Sandbox:** a sandboxed macOS app needs BOTH
  `com.apple.security.network.client` (outbound: the description fetch + sending
  M-SEARCH) AND `com.apple.security.network.server` (the sandbox treats `bind()`
  as a server op, and SSDP must bind UDP 1900 to receive NOTIFY/replies). With
  only `network.client`, `bind()` fails `EPERM` at launch (LESSONS B-008).
  Entitlements only apply to a *signed* app. No multicast-specific entitlement is
  needed on macOS (only iOS needs the multicast entitlement).
- **JVM:** plain `MulticastSocket`; on multi-homed hosts pass `bindInterface`.
- Android compile task is `:ssdp:compileAndroidMain` (LESSONS N-001).

## 10. Testing

All shared logic gets `commonTest` coverage with `runTest` virtual time (never
`Thread.sleep`). Turbine for Flow assertions; Kotest for parser property tests
(use multiplatform arbs only — `Arb.stringPattern` is JVM-only and breaks the
native test compile, LESSONS B-007). A `TestClock`/`TestTimeSource` reading the
test scheduler keeps `now` and `delay` in lockstep. Parser fixtures are real
device captures (eero, Sonos). Tasks (mise): `mise run test:jvm` (fast loop),
`mise run check` (full gate, all targets + ktlint + detekt), `mise run cli`
(live LAN discovery harness). The full `:ssdp:check`/`:ssdp-testing:check` —
which compiles *test* sources for every target and runs detekt — is the real
done gate; a JVM-only run hides native-test-compile and detekt failures
(LESSONS B-007).

## 11. Task workflow

1. Read this file, then `gradle/libs.versions.toml`, then `.claude/lessons/LESSONS.md`.
2. Adding a dependency? Web-search the latest stable; add to the catalog only.
3. Platform-specific? Keep the `expect`/`actual` seam tiny; push logic to common.
4. Public API crossing to Swift? Apply §7 at design time.
5. Done when `./gradlew :ssdp:check :ssdp-testing:check` passes AND
   `:ssdp:compileKotlinMacosArm64` / `compileKotlinIosArm64` /
   `compileAndroidMain` build clean (common-code bugs often only surface on
   Native — the JVM compile is not a sufficient gate, LESSONS B-004).
6. Learned something non-obvious? Add it to `.claude/lessons/LESSONS.md` (terse).

## 12. Hard rules

No Compose Multiplatform. No CocoaPods. No Network.framework on Apple (POSIX
only). No x86/Intel Macs/watchOS/tvOS. No `GlobalScope`, no `!!` in production,
no `java.time` in common, no `kotlin.synchronized`/`@Synchronized`/`volatile`.
No callback-based public APIs. No `kotlin.Result<T>` at the Swift boundary. No
SSDP server. No EAP/RC/Beta on `main`. `reachable` ≥ 0.14.0.
