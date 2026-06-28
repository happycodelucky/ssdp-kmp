/*
 * ssdp-kmp — JVM `Ssdp` factory.
 *
 * Delegates to the existing `SsdpClient(bindInterface)` factory. No bridge
 * concept on the JVM (the host bridge is an Android-emulator concern; the JVM is
 * itself the host that runs the bridge daemon). Plain per-platform object — see
 * the Apple actual for why this isn't expect/actual.
 *
 * `@file:Suppress("MatchingDeclarationName")`: the file uses the KMP `.jvm`
 * platform suffix while the single declaration is the object `Ssdp`.
 */
@file:Suppress("MatchingDeclarationName")

package com.happycodelucky.ssdp

/** Named factory for [SsdpClient] on the JVM. */
public object Ssdp {
    /**
     * Create an [SsdpClient]. Passive NOTIFY listening starts immediately; call
     * [SsdpClient.search] to begin active discovery.
     *
     * @param bindInterface optional local interface/address hint; `null` lets the
     *   OS pick the default route. Useful on multi-homed hosts.
     * @throws SsdpError if the multicast group cannot be joined.
     */
    @Throws(SsdpError::class)
    public fun createClient(bindInterface: String? = null): SsdpClient = SsdpClient(bindInterface)
}
