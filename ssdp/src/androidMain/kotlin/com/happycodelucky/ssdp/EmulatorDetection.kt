/*
 * ssdp-kmp — Android emulator detection.
 *
 * Android emulators sit behind a user-mode NAT and never receive inbound UDP
 * multicast, so normal SSDP discovery hears nothing there — the host bridge is
 * needed instead (see `Ssdp.createBridgeAwareClient` / `SsdpClient.bridged`).
 * This is a best-effort heuristic over `android.os.Build`, exposed publicly so
 * consumers can drive the same decision the library does without re-deriving it.
 */
package com.happycodelucky.ssdp

import android.os.Build

/**
 * Whether this device looks like an Android emulator, i.e. whether the SSDP host
 * bridge is needed for discovery to work.
 *
 * Emulators NAT inbound UDP multicast away, so the normal multicast client hears
 * nothing; pass the result to `Ssdp.createBridgeAwareClient(useBridge = …)` (or
 * branch to `SsdpClient.bridged()`) to tunnel discovery through a host daemon.
 *
 * Best-effort: matches the standard emulator's `ranchu`/`goldfish` virtual
 * hardware and the `generic` / `sdk_gphone` build fingerprints. A custom ROM or
 * exotic device could fool it either way; for full control, decide explicitly
 * and call the factory you want.
 */
public fun isSsdpBridgeNeeded(): Boolean =
    Build.HARDWARE in setOf("ranchu", "goldfish") ||
        Build.FINGERPRINT.contains("generic") ||
        Build.FINGERPRINT.startsWith("unknown") ||
        Build.MODEL.contains("Emulator") ||
        Build.MODEL.contains("Android SDK built for") ||
        Build.PRODUCT.startsWith("sdk") ||
        Build.PRODUCT.contains("emulator") ||
        Build.PRODUCT.contains("simulator")
