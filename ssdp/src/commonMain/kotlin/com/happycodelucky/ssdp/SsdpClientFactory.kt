/*
 * ssdp-kmp — the public platform factory for SsdpClient.
 *
 * Each platform's `actual` opens the platform multicast socket, builds the
 * shared SsdpClientImpl with a SupervisorJob scope and the system clock, and
 * (task #5) wires the reachable-driven network-change trigger into the registry
 * reset. The expect/actual seam is intentionally one function (CLAUDE.md §4).
 *
 * Apple: `SsdpClient()` — no arguments, self-contained.
 * Android: `SsdpClient()` — takes the WifiManager MulticastLock from the
 *          application Context captured at startup; androidMain adds
 *          `SsdpClient(context)`, `SsdpClient.bridgeAware()` and
 *          `SsdpClient.bridged()`.
 * JVM: `SsdpClient()` — no arguments.
 */
package com.happycodelucky.ssdp

/**
 * Create an [SsdpClient] for the current platform.
 *
 * Passive NOTIFY listening starts immediately; call [SsdpClient.search] to begin
 * active discovery. It's the one factory on every platform. On Android it holds a
 * `WifiManager.MulticastLock` from the application Context the library captures
 * at startup; Android also provides an overload taking a `Context`, for apps that
 * disable that capture, and `SsdpClient.bridgeAware()` for emulators (see the
 * `androidMain` factories).
 *
 * @param bindInterface optional local interface/address hint for the multicast
 *   socket; `null` lets the OS pick the default route. Useful on multi-homed
 *   hosts (a server with several NICs) to pin discovery to one LAN.
 * @throws SsdpError if the multicast group cannot be joined.
 */
@Throws(SsdpError::class)
public expect fun SsdpClient(bindInterface: String? = null): SsdpClient
