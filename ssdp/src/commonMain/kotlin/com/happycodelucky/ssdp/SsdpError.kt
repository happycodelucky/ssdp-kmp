/*
 * ssdp-kmp — error type (ported from swift-ssdp's SSDPError).
 *
 * A sealed exception hierarchy (not kotlin.Result) so Swift consumers can
 * `switch` exhaustively via SKIE, and so `@Throws`-annotated suspend functions
 * surface typed failures. Underlying platform transport errors are stringified
 * into `details` because native socket error types aren't portable across the
 * KMP targets.
 */
package com.happycodelucky.ssdp

/**
 * Errors raised by ssdp-kmp operations. A sealed `Exception` subtree: the
 * search/listen APIs annotate `@Throws(SsdpError::class)` so Swift sees typed
 * throwing functions, and SKIE renders the subtypes for exhaustive handling.
 */
public sealed class SsdpError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** The underlying network transport failed (UDP send, socket setup, etc.). */
    public class TransportFailed(
        public val details: String,
        cause: Throwable? = null,
    ) : SsdpError("SSDP transport failed: $details", cause)

    /**
     * Joining the SSDP multicast group (`239.255.255.250:1900`) failed. On
     * Apple platforms the most common cause is a missing
     * `com.apple.developer.networking.multicast` entitlement (iOS); on Android,
     * a missing or unacquired `WifiManager.MulticastLock`.
     */
    public class MulticastJoinFailed(
        public val details: String,
        cause: Throwable? = null,
    ) : SsdpError("SSDP multicast join failed: $details", cause)

    /**
     * The multicast entitlement (`com.apple.developer.networking.multicast`) is
     * required on iOS / iPadOS but is not present in the host app. Apple gates
     * it behind a manual request:
     * https://developer.apple.com/contact/request/networking-multicast
     */
    public class MulticastEntitlementMissing : SsdpError("Missing com.apple.developer.networking.multicast entitlement")

    /** A wire-format SSDP message could not be parsed. */
    public class InvalidResponse(
        public val reason: String,
    ) : SsdpError("Invalid SSDP response: $reason")
}
