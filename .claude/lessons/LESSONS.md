# LESSONS — ssdp-kmp

Terse log of non-obvious things learned while building this library, one line
each. IDs are stable (CLAUDE.md, code comments and PRs cite them), so append new
ones and don't renumber; gaps are retired entries. Anything a comment at its
point of use already explains belongs in that comment, not here.

- **D-NNN** — Decisions (load-bearing architecture choices).
- **B-NNN** — Bugs / gotchas (the thing that bit, and the fix).
- **N-NNN** — Notes (build-system / toolchain / interop quirks).

## Decisions

- **D-001** — Apple multicast uses POSIX BSD sockets (`platform.posix`), shared 1:1 by iOS and macOS: `NWConnectionGroup` isn't in K/N's `platform.Network` cinterop, and POSIX matches the JVM/Android datagram model. See `MulticastSocket.apple.kt`.
- **D-002** — Network byte order is computed in Kotlin (`SSDP_PORT_BE`, `ipv4ToNetworkOrder()`): `htons`/`htonl`/`inet_addr` don't resolve as functions on Apple K/N. Apple arm64 is little-endian, so octet[0] lands in the LSB of `s_addr`.
- **D-003** — `reachable` 0.14.0 is the floor: the first release with a `jvm` slice, without which our `jvm()` target can't resolve it.
- **D-004** — Description XML: Ktor CIO + xmlutil (`ignoreUnknownChildren()`), cached by USN. Byebye, expiry and network change evict it through one `registry.changes` collector; failures are negative-cached for 30s; concurrent fetches share one Mutex-guarded `Deferred`, awaited outside the lock. See `DescriptionService.kt`.
- **D-005** — `search(targets, maxWaitSeconds, timeout: Duration? = null)`: a finite timeout stops M-SEARCH retransmission (`withTimeoutOrNull`) but keeps the socket joined, so NOTIFY listening and discovered devices persist. `null` retransmits until `stopSearch()`.
- **D-006** — Android emulators get no inbound multicast (user-mode NAT), so a host daemon (`:ssdp-bridge` → `runSsdpBridgeDaemon`) relays SSDP over TCP (`10.0.2.2:1901`) as a dumb pipe: the app keeps retransmit and the registry, and the bridge is just another `MulticastSocket` (`BridgeMulticastSocket`, commonMain over ktor-network). The `DuplexConnection` seam tests it in memory under `runTest`.
- **D-007** — `object Ssdp` is declared per platform, not as `expect object` (Beta, so `allWarningsAsErrors` fails it): `Ssdp.createClient()` everywhere, plus Android's `createBridgeAwareClient()`, which bridges when `isSsdpBridgeNeeded()` detects an emulator. `SsdpInitializer` (androidx.startup) captures the application Context; its manifest `<provider … tools:node="merge">` is load-bearing, because reachable declares the same `InitializationProvider`.
- **D-008** — Keep SKIE's `onEnum(of:)` for switching a sealed type in Swift: a sealed subtype crosses as a class instance, so no config switches without it. `SsdpDeviceListener` (`addListener`/`removeListener`) is the one sanctioned callback API: a fan-out over `changes` under atomicfu `synchronized`, invoking listeners outside the lock, each isolated by `runCatching`.
- **D-009** — Line length is 140, set once as `max_line_length` in the root `.editorconfig`: editors show it, ktlint enforces it and `mise run format` wraps to it; detekt's `MaxLineLength` is off. ktlint ignores `max_line_length` in every rule when its `max-line-length` rule is disabled, so that rule stays on. The forced-multiline signature thresholds are `unset`, so a signature that fits in 140 is joined onto one line.
- **D-010** — The Apple framework / Swift module is `SsdpKit` (`<PascalName>Kit`, derived in the convention plugin and `ssdp/build.gradle.kts`): a module named like a public type (`object Ssdp` in module `Ssdp`) makes SKIE rename the type (`Ssdp_`). Renaming a shipped framework breaks every Swift consumer's `import`.
- **D-011** — Releases are changeset-driven (`.changeset/`, `scripts/changeset.py`). `version=` in gradle.properties is the single source of the version, bumped only by the rolling release PR; release.yml publishes when a push to main changes it. A changeset's `change` is the author's call; while 0.x a `major` bumps the minor, and only a `version:` pin leaves 0.x. A PR needs one only when it touches release scope (`.changeset/config.toml`). Stable versions never come from a manual dispatch.
- **D-012** — SKIE Swift bundling is off: SKIE compiles the bundled Swift of every linked klib into every framework it builds, with no per-dependency opt-out, and that Swift only compiles where the module is `export`ed. A module that turns bundling on must be exported by every downstream framework.
- **D-013** — Every published jar and the AAR carry `llms.txt` + `llms-full.txt` (public API with KDoc, from Dokka Markdown) under `META-INF/com.happycodelucky.ssdp/<artifactId>/`: namespaced, because a bare `META-INF/llms.txt` from two libraries fails Android packaging; at the AAR root, so never in an APK. Packed only by publishing builds, so `check` never runs Dokka. `mise run llms:check` verifies them.

## Bugs

- **B-001** — `@Throws` on an `expect` must be repeated verbatim on every `actual`, or `compileKotlinJvm` fails; it's also what makes the Apple slice `throws` in Swift.
- **B-002** — `@Throws` on a `suspend fun` must list `CancellationException`. Only the Native compile catches it.
- **B-003** — `close()` must cancel the client's own `SupervisorJob` (a child of the injected scope), never the injected scope itself; `runTest` forbids cancelling its `TestScope`.
- **B-004** — `sortedMapOf` is JVM-only and `Dispatchers.IO` is internal on K/N. The JVM compile is not a sufficient gate for common code: always compile a Native target too.
- **B-005** — A perpetual `collect {}` launched on the `TestScope` hangs `runTest` (`UncompletedCoroutinesError`). Give such components `backgroundScope`, which shares the scheduler and is cancelled at test end.
- **B-006** — Never `cancel()` a shared `Deferred` that external callers await: every awaiter gets the cancellation. To drop unwanted in-flight work, detach it (remove it from the map) and let it finish uncached.
- **B-007** — `:ssdp:check` is the real gate: it compiles test sources for every target and runs detekt, which `jvmTest` doesn't. Kotest's `Arb.stringPattern` is JVM-only and breaks the native test compile; use multiplatform arbs.
- **B-008** — A sandboxed macOS app needs `com.apple.security.network.server` as well as `network.client`: the sandbox treats `bind()` on UDP 1900 as a server operation (`EPERM` otherwise). Entitlements only apply to a signed build (use ad-hoc `CODE_SIGN_IDENTITY="-"` to test).
- **B-009** — The XCFramework has no x86_64 simulator slice, so a simulator build for both archs fails with misleading "no member" errors. Set `EXCLUDED_ARCHS[sdk=iphonesimulator*]: x86_64` (`apps/ios/project.yml`). Diagnose with `lipo -info` on the slice.
- **B-010** — ktlint's `when-entry-bracing` rewrites a bare `else -> Unit` to `else -> { Unit }`, which K/N's warnings-as-errors then rejects as an unused expression. Use `if / else if` for a statement-`when` with a no-op catch-all.
- **B-011** — `runTest` auto-advances virtual time while every coroutine is idle, so a `max-age` expiry timer can fire between two calls and evict the description cache. In tests asserting state across suspend points, build devices with `cacheControl = null`.
- **B-012** — AGP stamps an AAR's `minCompileSdk` with the compileSdk it was built with, and consumers' `check<Variant>AarMetadata` enforces it: our 37 (raised for the sample, N-009) was forced on every consumer through v0.7.0. Fixed by `android { aarMetadata { minCompileSdk } }` from its own catalog key, `android-min-compile-sdk`.
- **B-013** — A test on real dispatchers (`Dispatchers.IO`, own `SupervisorJob` scopes) must `cancelAndJoin` its scopes BEFORE closing sockets: closing first lets a read loop throw on EOF with no handler, and kotlinx-coroutines-test reports that uncaught exception against the NEXT `runTest` (`UncaughtExceptionsBeforeTest`), failing an unrelated, order-dependent test (`BridgeEndToEndTcpTest` → `BridgePipeTest`).

## Notes

- **N-001** — The `com.android.kotlin.multiplatform.library` plugin names the Android compile task `:<module>:compileAndroidMain`, not `compileDebugKotlinAndroid`.
- **N-002** — Inject `Clock` + `CoroutineScope` into time-driven code (the registry, retransmit, the description cache) and never read the wall clock: a `TestClock` reading `TestCoroutineScheduler.currentTime` keeps "now" and `delay` in lockstep under `runTest`.
- **N-003** — `getifaddrs`/`ifaddrs` live in `platform.darwin`, not `platform.posix`. When a batch of C symbols fails to resolve at once, suspect the package, and grep the K/N `platformDef` `.def` files for it.
- **N-004** — SKIE shapes in Swift: a `data object` becomes a top-level singleton (`SearchTargetAll.shared`); `@ObjCName` picks the Swift name (`description(device:)`, `descriptionForUsn(usn:)`); a `StateFlow<Map<…>>` becomes a typed `SkieSwiftStateFlow`. `kotlin.time.Duration` does NOT bridge (an inline value class becomes `Any?`), so Swift passes `timeout: nil`.
- **N-005** — Custom SSDP headers are kept (the parser consumes known headers and returns the rest in `otherHeaders`), but xmlutil's `ignoreUnknownChildren()` drops vendor description elements. A second pass with `xmlStreaming.newGenericReader` (identical on every target) captures them into `Device.extraProperties`, degrading to empty on failure.
- **N-006** — Kotlin-only sugar over a Swift-facing API: a top-level extension annotated `@HiddenFromObjC` stays out of the Obj-C header, so Swift keeps the canonical member (`DiscoveredDeviceDescription.kt`). It still appears in the klib ABI dump.
- **N-007** — AGP's KMP Android target is a `DecoratedExternalKotlinTarget`, not a `KotlinJvmTarget`, so `withType<KotlinJvmTarget>()` never reaches it and an unset `jvmTarget` follows the build JDK. The convention plugin sets `jvmTarget` on both `android {}` and `jvm {}` from the catalog's `jvm-target`.
- **N-008** — A custom `applyDefaultHierarchyTemplate { … group("apple") { … } }` puts targets directly under appleMain, with no iosMain/macosMain. The implicit default template has them.
- **N-009** — AndroidX AARs carry `minCompileSdk`, which AGP enforces on the consuming app: a library bump can force a compileSdk bump. `check` never builds the samples; `mise run build:samples` does (CI's fast leg).
- **N-012** — A push or PR made with `GITHUB_TOKEN` triggers no workflows (`workflow_dispatch` excepted), so release-pr.yml dispatches ci.yml + changeset.yml on `release/next` itself, unless a GitHub App token is configured.
- **N-013** — main is branch-protected, so no workflow pushes to it: the release commit with the remote-binary `Package.swift` lives only on its `vX.Y.Z` tag; main keeps the local-dev form.
- **N-015** — PR templates and issue forms apply only in GitHub's web UI; `gh … create --body` bypasses them, so agents build the body from them (CLAUDE.md §11). HTML comments don't nest, so a template can't quote `<!-- AI: … -->` inside a comment.
- **N-016** — Every `- [ ]` in a PR body is live and feeds the "N of M tasks" counter, so checkboxes appear only in the done-gate; choices are plain bullets, and review is signalled by leaving draft.
- **N-017** — Dokka 2 has no Gradle switch for Markdown output: register a `DokkaFormatPlugin(formatName = "markdown")` subclass (an `@InternalDokkaGradlePluginApi` opt-in; re-check on Dokka bumps). See `LlmsTxt.kt`.
