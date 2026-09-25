---
title: Rename the Apple framework and Swift module to SsdpKit
change: major
description: Swift and SPM consumers now import SsdpKit instead of Ssdp, and the Ssdp factory object is no longer renamed Ssdp_ in Swift.
---

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
