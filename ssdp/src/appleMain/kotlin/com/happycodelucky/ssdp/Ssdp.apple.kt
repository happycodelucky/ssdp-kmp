/*
 * ssdp-kmp — Apple (iOS + macOS) `Ssdp` factory.
 *
 * Self-contained: just delegates to the existing `SsdpClient(bindInterface)`
 * factory. No bridge concept on Apple (the host bridge is an Android-emulator
 * concern); the iOS-simulator twin is deferred to v1.1. Defined as a plain
 * per-platform object (not expect/actual) — there is no shared implementation to
 * factor, and `expect object` would force the Beta `-Xexpect-actual-classes`
 * opt-in for no benefit.
 *
 * `@file:Suppress("MatchingDeclarationName")`: the file uses the KMP `.apple`
 * platform suffix while the single declaration is the object `Ssdp`.
 */
@file:Suppress("MatchingDeclarationName")

package com.happycodelucky.ssdp

/** Named factory for [SsdpClient] on Apple platforms. */
public object Ssdp {
    /**
     * Create an [SsdpClient]. Passive NOTIFY listening starts immediately; call
     * [SsdpClient.search] to begin active discovery.
     *
     * @param bindInterface optional local interface/address hint; `null` lets the
     *   OS pick the default route.
     * @throws SsdpError if the multicast group cannot be joined.
     */
    @Throws(SsdpError::class)
    public fun createClient(bindInterface: String? = null): SsdpClient = SsdpClient(bindInterface)
}
