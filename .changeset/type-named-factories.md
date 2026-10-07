---
title: Build clients with SsdpClient() only; the Ssdp object is gone
change: major
description: Ssdp.createClient() becomes SsdpClient() and Android's Ssdp.createBridgeAwareClient() becomes SsdpClient.bridgeAware(); on Android a plain SsdpClient() now holds the multicast lock.
---

There were two ways to build a client: the type-named factories
(`SsdpClient()`, Android's `SsdpClient(context)` and `SsdpClient.bridged()`) and
a per-platform `object Ssdp` (`createClient()`, plus Android's
`createBridgeAwareClient()`). They had drifted apart on Android. Only
`Ssdp.createClient()` used the application `Context` that `SsdpInitializer`
captures at startup, so a plain `SsdpClient()` held no `WifiManager.MulticastLock`
and on many devices the Wi-Fi driver dropped inbound multicast. The `Ssdp`
object is removed on every platform, and the type-named factories are the only
way to build a client.

### Android: `SsdpClient()` now holds the multicast lock

`SsdpClient()` takes the lock from the startup-captured Context and logs a warning
on a likely emulator, as `Ssdp.createClient()` did. If your app disables
androidx.startup's `InitializationProvider`, nothing is captured: `SsdpClient()`
then opens without the lock and logs a warning naming `SsdpClient(context)`, which
is the fix. `SsdpClient(context)` now logs the emulator warning too.

### Migrating

| Before | After |
|---|---|
| `Ssdp.createClient(bindInterface)` | `SsdpClient(bindInterface)` |
| `Ssdp.createBridgeAwareClient(useBridge, host, port)` (Android) | `SsdpClient.bridgeAware(useBridge, host, port)` |

`bridgeAware` has the same defaults (`useBridge = isSsdpBridgeNeeded()`,
`host = EMULATOR_HOST_LOOPBACK`, `port = 1901`) and the same behavior: the bridge
on an emulator, otherwise the normal multicast client.

```kotlin
// Before
val client = Ssdp.createClient()
val androidClient = Ssdp.createBridgeAwareClient()

// After
val client = SsdpClient()
val androidClient = SsdpClient.bridgeAware() // import com.happycodelucky.ssdp.bridgeAware
```

Swift: `Ssdp.shared.createClient(bindInterface:)` becomes
`SsdpClient(bindInterface:)`.

```swift
// Before
let client = try Ssdp.shared.createClient(bindInterface: nil)

// After
let client = try SsdpClient(bindInterface: nil)
```
