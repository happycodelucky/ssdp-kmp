---
title: description() returns KotlinResult's Result instead of DescriptionResult
change: major
description: SsdpClient.description now returns com.happycodelucky.kotlinresult.Result<DeviceDescription>, failing with a sealed DescriptionException; DescriptionResult is removed.
---

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

### Migrating

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

### Linking ssdp into your own Apple framework

`ssdp` depends on `com.happycodelucky.kotlinresult:kotlinresult` as an API
dependency. SPM users of `SsdpKit` need to do nothing. If you link `ssdp` into
your **own** framework with SKIE, `export(libs.kotlinresult)` into it, or the link
fails with "cannot find type 'KotlinResult' in scope".
