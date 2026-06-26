/*
 * ssdp-kmp — the public platform factory for SsdpClient.
 *
 * Each platform's `actual` opens the platform multicast socket, builds the
 * shared SsdpClientImpl with a SupervisorJob scope and the system clock, and
 * (task #5) wires the reachable-driven network-change trigger into the registry
 * reset. The expect/actual seam is intentionally one function (CLAUDE.md §4).
 *
 * Apple: `SsdpClient()` — no arguments, self-contained.
 * Android: `SsdpClient(context)` — needs a Context for the WifiManager
 *          MulticastLock; declared in androidMain only.
 * JVM: `SsdpClient()` — no arguments.
 */
package com.happycodelucky.ssdp

/**
 * Create an [SsdpClient] for the current platform.
 *
 * Passive NOTIFY listening starts immediately; call [SsdpClient.search] to begin
 * active discovery. On Apple and JVM this takes no arguments; Android provides
 * an overload taking a `Context` (see the `androidMain` factory).
 *
 * @param bindInterface optional local interface/address hint for the multicast
 *   socket; `null` lets the OS pick the default route. Useful on multi-homed
 *   hosts (a server with several NICs) to pin discovery to one LAN.
 * @throws SsdpError if the multicast group cannot be joined.
 */
@Throws(SsdpError::class)
public expect fun SsdpClient(bindInterface: String? = null): SsdpClient
