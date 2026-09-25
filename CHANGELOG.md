# Changelog

Every release of Ssdp, newest first. Each entry is assembled from the
changesets merged since the previous release, when its release PR is opened
(`.changeset/README.md`). Releases up to v0.6.0 predate changesets — their
notes are on [GitHub Releases](https://github.com/happycodelucky/ssdp-kmp/releases).

<!-- changesets: the Release PR workflow inserts each new release below this line. Keep it. -->

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
