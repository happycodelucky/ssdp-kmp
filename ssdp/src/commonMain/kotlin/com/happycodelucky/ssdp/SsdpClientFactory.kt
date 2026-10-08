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
 * @param bindInterface limits discovery to one local interface, by name (`en0`)
 *   or IPv4 address. `null` (the default) listens and searches on every interface
 *   that is up, multicast-capable and has an IPv4 address, skipping loopback and
 *   point-to-point tunnels such as VPNs.
 * @throws SsdpError if the multicast group cannot be joined on any interface, or
 *   [bindInterface] names no local IPv4 interface.
 */
@Throws(SsdpError::class)
public expect fun SsdpClient(bindInterface: String? = null): SsdpClient
