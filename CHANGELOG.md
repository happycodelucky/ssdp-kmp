# Changelog

Every release of Ssdp, newest first. Each entry is assembled from the
changesets merged since the previous release, when its release PR is opened
(`.changeset/README.md`). Releases up to v0.6.0 predate changesets — their
notes are on [GitHub Releases](https://github.com/happycodelucky/ssdp-kmp/releases).

<!-- changesets: the Release PR workflow inserts each new release below this line. Keep it. -->

## 0.8.0 — 2026-09-30

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
