# ssdp-kmp — Lessons

Terse, symptom-first notes on bugs hit and decisions made, so a future session
(human or agent) doesn't re-derive them. 1–3 lines each. Mirrors the sibling
`backgrounder` / `reachable` repos' LESSONS convention.

## Decisions (D)

### D-007 — `Ssdp` named factory object + androidx.startup Context initializer; emulator detection in the library — 2026-06-28
**Goal:** stop the app threading a Context and hand-rolling emulator detection. Now: `Ssdp.createClient()` on every platform (plain per-platform `object`, NOT `expect`/`actual` — see gotcha); Android adds `Ssdp.createBridgeAwareClient(useBridge = …, host, port)`. Emulator detection moved into the library as public `isSsdpBridgeNeeded()` (androidMain, reads `android.os.Build`). The common case is one line: `Ssdp.createBridgeAwareClient(useBridge = isSsdpBridgeNeeded())`.
**Context capture:** `SsdpInitializer : androidx.startup.Initializer<Context>` captures `applicationContext` into a module-global (`internal/AppContext.kt`, get-or-error) during the ContentProvider startup pass (before `Application.onCreate`), so the Android factory needs no Context arg. Mirrors reachable's `ReachabilityInitializer` exactly, incl. the manifest `<provider android:name="androidx.startup.InitializationProvider" … tools:node="merge">`. **`tools:node="merge"` is load-bearing:** ssdp + reachable both declare `InitializationProvider` at `${applicationId}.androidx-startup`; without merge that's a duplicate-provider build error. VERIFIED: the merged APK manifest hosts BOTH initializers under one provider. androidx.startup `1.2.0` (androidMain), matching reachable.
**Warn, don't auto-switch:** `createClient()` / `createBridgeAwareClient(useBridge=false)` log a Kermit warning when `isSsdpBridgeNeeded()` (you built a multicast client on a likely emulator → discovery will hear nothing). Kermit `2.1.0` added (commonMain; was aspirational in CLAUDE.md §5 — now wired). Shared `internal val ssdpLog = Logger.withTag("ssdp")` in commonMain.
**GOTCHA — `expect object` is Beta and `allWarningsAsErrors=true` turns the warning into an error** (KT-61573: expect/actual *functions* are stable, *classes/objects* aren't). Don't `expect object Ssdp` unless you add `-Xexpect-actual-classes`. Resolution: plain per-platform `object Ssdp` in each source set (no common file) — same consumer API (`createClient` everywhere, `createBridgeAwareClient` Android-only), more flexible (Android adds a first-class member, not an "extra on an actual"), no Beta flag. Cost: detekt `MatchingDeclarationName` fires on a single-top-level-`object` file with a `.platform.kt` suffix → `@file:Suppress("MatchingDeclarationName")` (and mind ktlint `no-consecutive-comments`: the file annotation can't sit as an EOL comment right after the block KDoc — fold the note into the KDoc).

### D-006 — Android-emulator host bridge = a new MulticastSocket actual + dumb-pipe daemon, not an engine change — 2026-06-27
**Problem:** Android emulators (user-mode NAT, `10.0.2.x`) never receive inbound UDP multicast, so SSDP discovery hears nothing on an emulator. Physical devices are fine.
**Design:** A host-side daemon (`:jvm-bridge` → `runSsdpBridgeDaemon` in `:ssdp` jvmMain) does the real multicast on the host LAN; the emulator app tunnels SSDP over TCP to it via `SsdpClient.bridged(host = "10.0.2.2", port = 1901)`. Because the engine talks to the net through one injected `MulticastSocket` (`SsdpClientImpl(socketFactory = …)`), the bridge is just a NEW `MulticastSocket` impl (`BridgeMulticastSocket`, commonMain over **ktor-network** TCP) + a new factory — registry/retransmit/parser/network-monitor untouched.
**Dumb pipe, not smart bridge:** the daemon relays bytes both ways (DATAGRAM_OUT → re-multicast verbatim; every reply/NOTIFY → DATAGRAM_IN); the **app keeps owning retransmit + registry**, so the emulator path is byte-identical to a physical device and the protocol stays two frame types (no command layer). Explicit opt-in: the library never auto-detects emulators; the app picks `bridged()` vs `SsdpClient(context)` on a `Build.HARDWARE`/`FINGERPRINT` check. `networkTransportTags = null` for the bridged client (emulator NAT changes are meaningless).
**ktor-network is multiplatform-clean:** `io.ktor:ktor-network` 3.5.1 publishes iosArm64/iosSimulatorArm64/macosArm64/jvm slices (Android resolves the `-jvm` variant; there is no `ktor-network-android`), so the whole transport lives in commonMain with no expect/actual — proven by `:ssdp:check` compiling+running the bridge tests on all four target families. The iOS-simulator `bridged()` twin is thus ~1 factory away (deferred to v1.1). Wire codec uses kotlinx-io `Buffer` (BE `writeInt`/`readInt`) — no manual byte-shift literals, sidesteps detekt MagicNumber (B-007). The `DuplexConnection` seam (read N / write / close) is injected into both `BridgeMulticastSocket` and `runBridgePipe`, so both are tested with an in-memory duplex + the existing `FakeMulticastSocket` under `runTest` virtual time — no real TCP, no real multicast; the perpetual connection/reconnect loop runs on `backgroundScope` (B-005) with `delay`-driven capped backoff (N-002).

### B-007 — `:ssdp:check` (detekt + all-target test compile) is the real gate; JVM-only runs hide failures — 2026-06-26
**Symptom:** v1.1 was "green" on `:ssdp:jvmTest` + targeted production compiles, but the first real `:ssdp:check` surfaced two latent pre-existing failures: 69 detekt findings, and `Arb.stringPattern` (Kotest, JVM-only — backed by a regex generator with no K/N impl) breaking `compileTestKotlinIosSimulatorArm64`.
**Cause:** Earlier verification used `:ssdp:jvmTest` and *production* compiles, never `:ssdp:check`, which also compiles *test* sources for every target and runs detekt. Both gates were never actually executed before.
**Fix:** detekt → tuned `config/detekt/detekt.yml` (domain-appropriate thresholds; `ignoreNumbers` matches literal text so `0xFF` ≠ `255`). Kotest → use multiplatform arbs (`Arb.string(range, Codepoint.alphanumeric())`), never `Arb.stringPattern`. ALWAYS run `./gradlew :ssdp:check :ssdp-testing:check` as the done gate, not just jvmTest (CLAUDE.md §11 already says this — now enforced).

### B-005 — A perpetual collect{} on the TestScope hangs runTest — use backgroundScope — 2026-06-26
**Symptom:** Every `DescriptionServiceTest` failed after a 1-minute wait with `UncompletedCoroutinesError: there were active child jobs` (9-min total run).
**Cause:** `DescriptionService.init` launches a never-completing `registryChanges.collect{}` eviction collector on its injected scope. When that scope is the `TestScope`, `runTest` waits for all children before completing — a perpetual collect never ends. Production is fine (the client's child job is cancelled on close()).
**Fix:** In tests, pass `backgroundScope` (not `this`) as the service's scope — runTest auto-cancels it at test-body end, and it shares the scheduler so virtual-time fetches still resolve. First component here with a perpetual collector; the registry's collectors are short-lived (delay-based) so they never tripped this.

### B-006 — Never cancel a shared Deferred that external callers await — 2026-06-26
**Symptom:** `descriptionFetchesForDiscoveredDevice` failed with `JobCancellationException: DeferredCoroutine was cancelled` from `DescriptionService.evict()`.
**Cause:** Cache eviction (`evict()`) called `inFlight[usn]?.cancel()` on the in-flight fetch Deferred. But `Deferred.cancel()` propagates cancellation to *every* awaiter, so a caller mid-`describe()` got a raw exception instead of a clean `DescriptionResult`. The trigger was incidental — `runTest` auto-advances virtual time, firing the Sonos fixture's `max-age=1800` expiry → `Removed(Expired)` → eviction, racing the fetch.
**Fix:** `evict()` removes the `inFlight` slot (so the result won't be cached → next call refetches) but does NOT cancel the Deferred. `fetchParseAndStore` already checks slot ownership before caching, so an evicted fetch completes for its awaiters yet isn't stored. Pattern: to drop unwanted in-flight work that has external awaiters, *detach*, don't cancel.

### B-009 — iOS sim build fails on ARM-only XCFramework — exclude x86_64 — 2026-06-26
**Symptom:** Xcode "cannot find the Ssdp library" / `ScannerModel.swift: value of type 'any SsdpClient' has no member 'search'` / "cannot be constructed" when building the iOS app for the simulator. The package RESOLVED fine — not a wiring problem.
**Cause:** The Ssdp.xcframework simulator slice is `ios-arm64-simulator` only (ARM-only library, CLAUDE.md §1 — no `iosX64`). On Apple Silicon, `generic/platform=iOS Simulator` builds BOTH arm64 and x86_64; x86_64 finds no matching framework slice → the Ssdp module is absent on that arch → cascading "no member" errors that masquerade as "can't find the library."
**Fix:** `EXCLUDED_ARCHS[sdk=iphonesimulator*]: x86_64` in `apps/ios/project.yml` settings (regenerate the project after). The Apple Silicon simulator runs arm64, so this loses nothing. (tvctl's iOS sample already did this; I'd missed copying it.) Diagnosis tip: "no member X" on a binary framework → `lipo -info` the slice vs the build's target archs.

### B-008 — macOS sandbox: SSDP bind() needs network.SERVER, not just network.client — 2026-06-26
**Symptom:** The signed macOS sample crashed at launch: `SSDP multicast join failed: bind() failed (errno=1)` (EPERM). The same socket code runs fine unsandboxed (the JVM CLI against real devices).
**Cause:** The App Sandbox treats `bind()`/listen as a SERVER operation. `com.apple.security.network.client` only permits *outbound* connections. SSDP must `bind()` UDP 1900 to receive NOTIFY broadcasts + unicast M-SEARCH replies, so it needs `com.apple.security.network.server` too. (Multicast group join itself needs no special entitlement on macOS — only iOS needs `com.apple.developer.networking.multicast`.)
**Fix:** add `com.apple.security.network.server` to `apps/macos/macOSApp/macOSApp.entitlements`. NOTE: entitlements only take effect when the app is CODE-SIGNED — a `CODE_SIGNING_ALLOWED=NO` build won't reproduce or verify this; build with `CODE_SIGN_IDENTITY="-"` (ad-hoc) to test. Documented in CLAUDE.md §9. Consumer macOS apps embedding this library hit the same requirement.

### N-004 — SKIE Swift-bridging idioms for the sample apps; Duration is poor at the boundary — 2026-06-26
Confirmed by building the iOS/macOS samples against the real SKIE output (apps/shared/*.swift):
- A Kotlin `data object` (e.g. `SearchTarget.All`, `DescriptionResult.NotFound`) flattens to a **top-level** Swift singleton `SearchTargetAll.shared` / `DescriptionResultNotFound.shared` — NOT a nested `.All`.
- A `@Throws` factory/suspend fun becomes a Swift **throwing** function: `try SsdpClient(bindInterface:)`, `try await client.descriptionForUsn(usn:)`.
- The `@ObjCName("descriptionForUsn")` overload-disambiguation IS the Swift name — so the device overload is `description(device:)` and the USN one is `descriptionForUsn(usn:)`.
- A sealed interface switches via `onEnum(of: result)` with the lowercased leaf cases (`.success/.notFound/.fetchFailed/.parseFailed`); `Success.description` collided with Swift so SKIE renamed it `description_`.
- `StateFlow<Map<String,T>>` bridges as a typed `SkieSwiftStateFlow` whose `for await` element is a real `[String: T]`.
- **`kotlin.time.Duration` does NOT bridge** — it's an inline value class, so SKIE types a `Duration?` param as `Any?` with no Swift constructor. The Swift sample passes `timeout: nil` and drives the window with `stopSearch()` after a `Task.sleep`. If a Swift-friendly timeout matters later, add an overload taking `timeoutSeconds: Int` or a `Long` millis param. mise tasks for the apps: `open:{ios,macos,android}`, `xcodeproj:{ios,macos}`, `app:cli`.

### D-005 — search() takes an optional timeout: Duration?; mise.toml added — 2026-06-26
**Decision:** `search(targets, maxWaitSeconds, timeout: Duration? = null)`. A finite timeout wraps each target's retransmit loop in `withTimeoutOrNull(timeout)` — broadcasting stops cleanly after the window, but the socket stays joined so passive NOTIFY listening continues and discovered devices persist. `null` = retransmit indefinitely (prior default, unchanged). Devices on a LAN are found in a few seconds, so a finite timeout is the common case (continued M-SEARCH is just noise). jvm-cli now passes `timeout = durationSeconds.seconds`.
**Also:** added `mise.toml` (modeled on ../wake-kmp) — tools java/gradle/gh; tasks check/test/test:jvm/lint/format/build/build:{ios,macos,android,jvm}/xcframework/run:cli/spm:{dev,restore}/docs/clean. `run:cli` runs the jvm-cli harness with an optional duration arg.

### D-004 — v1.1 description-XML: shared Ktor CIO + xmlutil, USN-keyed cache, registry.changes eviction — 2026-06-26
**Decision:** Ktor 3.5.1 CIO engine (multiplatform, zero expect/actual) + xmlutil 0.91.3 (`ignoreUnknownChildren()`) for the description fetch/parse; cache keyed by USN (network flush rides `registry.reset()`'s `Removed(_,NetworkChanged)` events through ONE collector); negative-cache failures with a 30s TTL; in-flight dedup via a Mutex-guarded `Deferred` map (await outside the lock). See `DescriptionService.kt`, `DescriptionParser.kt`, the design blueprint in task wwogyymrf.

### D-001 — Apple multicast = POSIX BSD sockets, not Network.framework — 2026-06-25
**Decision:** `appleMain` uses `platform.posix` (`socket`/`setsockopt IP_ADD_MEMBERSHIP`/`bind`/`recvfrom`/`sendto`), shared 1:1 by iOS+macOS.
**Why:** `NWConnectionGroup`/`NWMulticastGroup` (what swift-ssdp uses) aren't in K/N's `platform.Network` cinterop bindings. POSIX is fully exposed, identical across Apple targets, and matches the JVM/Android datagram model — so all socket logic feeds one common parser/registry. See `MulticastSocket.apple.kt`.

### D-002 — Compute network byte order in Kotlin, not via htons/htonl/inet_addr — 2026-06-25
**Decision:** Hand-write `SSDP_PORT_BE` and `ipv4ToNetworkOrder()` in `MulticastSocket.apple.kt` instead of calling the POSIX byte-order functions.
**Why:** `htons`/`htonl`/`inet_addr` did not resolve as imported functions on Apple K/N (macro/function ambiguity). Pure-Kotlin math compiles everywhere and is deterministically testable. Apple arm64 hosts are little-endian, so octet[0] lands in the LSB of the host-order UInt that K/N reads back from `s_addr`.

### D-003 — reachable 0.14.0 is the dependency floor — 2026-06-25
**Decision:** Pin `reachable = "0.14.0"` in the catalog.
**Why:** It's the first reachable release with a `reachable-jvm` slice (verified on Maven Central). ssdp-kmp adds a `jvm()` target, and a KMP consumer's jvm slice can't resolve a dependency that has no jvm artifact. Earlier reachable versions would fail resolution on `:ssdp`'s jvm target.

## Bugs (B)

### B-001 — @Throws on an expect must be replicated verbatim on every actual — 2026-06-25
**Symptom:** `compileKotlinJvm` fails: "Annotation `@Throws(...)` is missing on actual declaration."
**Fix:** Every `actual fun SsdpClient(...)` carries the same `@Throws(SsdpError::class)` as the `expect`. This isn't pedantry — `@Throws` is what bridges the exception to Swift `throws` on the Apple slice; a dropped annotation would silently swallow it there.

### B-002 — @Throws on a suspend fun must list CancellationException — 2026-06-25
**Symptom:** Apple/common compile fails: "`@Throws` on suspend declaration must have `kotlin.coroutines.cancellation.CancellationException` listed."
**Fix:** `SsdpClient.search` is `@Throws(SsdpError::class, CancellationException::class)`. The JVM compile didn't catch this; the Native compile did.

### B-003 — close() must cancel the client's own job, never the injected scope — 2026-06-25
**Symptom:** All client tests throw `JobCancellationException: TestScopeImpl was cancelled` — `close()` called `scope.cancel()` on the injected `TestScope`, which `runTest` forbids.
**Fix:** `SsdpClientImpl` roots its own `SupervisorJob` *under* the parent scope and `close()` cancels only that job. This is also the production-correct shape (mirrors reachable's `StateFlowReachability`). See `SsdpClientImpl.kt`.

### B-004 — sortedMapOf / Dispatchers.IO aren't in common/Native — 2026-06-25
**Symptom:** Native compile fails on `sortedMapOf` (JVM-only) and `Dispatchers.IO` (internal on K/N).
**Fix:** Use a sorted `List<Pair>` for deterministic header ordering; use `Dispatchers.Default` on Apple. The JVM compile is not a sufficient gate for common code — always compile a Native target too.

## Verification (V)

### V-001 — Live JVM discovery proven against real LAN — 2026-06-25
The `apps/jvm-cli` harness (`./gradlew :jvm-cli:run`) ran the real `SsdpClient()` on the local network and discovered real devices: Sonos players (MediaServer/ConnectionManager/ContentDirectory services) and an eero router (full InternetGatewayDevice tree). Confirms the whole path — POSIX/JVM multicast join, M-SEARCH send, **unicast replies from arbitrary device IPs** received on the bound port (the case the Swift client's NWConnectionGroup comment flags — plain `MulticastSocket` handles it because it's port-bound, not peer-connected), real-world header parsing (`max-age=1800` → 30m Duration), and USN-keyed registry dedup across the many per-service announcements each device emits. Unit tests use virtual time + a fake socket; this is the only end-to-end network proof.

### V-002 — Live bridge discovery proven over real TCP + real multicast — 2026-06-27
Ran the daemon (`mise run app:bridge` → `runSsdpBridgeDaemon` on 1901) and a real bridged client (`SsdpClientImpl(socketFactory = { BridgeMulticastSocket("127.0.0.1", 1901) })`). The client discovered **31 real devices** through the bridge — a Sonos player (`RINCON_…` at 192.168.4.20, all ZonePlayer/MediaServer/MediaRenderer services) and the gateway IGD (192.168.4.1) — proving the whole emulator path end to end: M-SEARCH framed over TCP → daemon re-multicast on the host LAN → real replies/NOTIFY framed back over TCP → identical registry ingestion. The dumb-pipe design holds: the client's retransmit/registry/parser ran unchanged, exactly as a physical device. (An emulator pointed at `10.0.2.2:1901` gets the same.) The loopback ktor-TCP round-trip is also kept as a permanent regression test (`BridgeEndToEndTcpTest`, real sockets + fake transport, CI-safe); the manual full-LAN harness was a one-off, removed.

## Notes (N)

### N-001 — Android compile task is `compileAndroidMain`, not `compileDebugKotlinAndroid` — 2026-06-25
The `com.android.kotlin.multiplatform.library` plugin names the Kotlin Android compile task `:<module>:compileAndroidMain`. CI scripts copied from classic-AGP setups will reference the wrong task.

### N-003 — getifaddrs is in platform.darwin, not platform.posix — 2026-06-25
**Symptom:** A whole batch of C symbols (`getifaddrs`, `ifaddrs`, `freeifaddrs`, `ifa_addr`/`ifa_netmask`/`ifa_next`) unresolved at once in `NetworkKey.apple.kt`.
**Cause:** They're declared in `<ifaddrs.h>`, which K/N maps to `platform.darwin` — `platform.posix` only has the strict-POSIX set (`socket`/`bind`/`recvfrom`/`sockaddr_in`/`AF_INET`). When a *batch* of C symbols fails together, suspect a wrong *package*, not wrong names. Find the right one by grepping the `.def` files under `~/.konan/.../konan/platformDef/<target>/`. Also avoided `IFF_UP`/`IFF_LOOPBACK` constants by filtering loopback on the address (127.x) — fewer symbols, fewer surprises.

### N-002 — Inject Clock + scope into the registry; never read wall-clock in timer logic — 2026-06-25
`DeviceRegistry` takes `Clock` and a `CoroutineScope`; expiry uses `delay`. A `TestClock` reading `TestCoroutineScheduler.currentTime` keeps "now" and `delay` in lockstep under `runTest`. (Carries backgrounder's N-011 forward.) `TestCoroutineScheduler.currentTime` needs `@OptIn(ExperimentalCoroutinesApi::class)`.
