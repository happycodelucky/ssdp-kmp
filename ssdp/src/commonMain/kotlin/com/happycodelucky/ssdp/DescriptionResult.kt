/*
 * ssdp-kmp — result of a description fetch (v1.1 feature).
 *
 * A sealed interface, NOT kotlin.Result (CLAUDE.md §7): SKIE renders it as an
 * exhaustive Swift enum so consumers `switch` over it with no default branch,
 * and the description fetch never *throws* domain failures — every outcome is a
 * case here. (Only CancellationException propagates, per the suspend contract.)
 */
package com.happycodelucky.ssdp

/**
 * The outcome of fetching and parsing a device's description document.
 *
 * Returned by [SsdpClient.description]. Exhaustive: every non-cancellation
 * outcome is one of these cases.
 *
 * ```kotlin
 * when (val result = client.description(device)) {
 *     is DescriptionResult.Success -> render(result.description)
 *     DescriptionResult.NotFound -> showUnknown()
 *     is DescriptionResult.FetchFailed -> retryLater(result.statusCode)
 *     is DescriptionResult.ParseFailed -> logBadDevice(result.message)
 * }
 * ```
 */
public sealed interface DescriptionResult {
    /** The document was fetched and parsed. [description] is the model tree. */
    public data class Success(
        public val description: DeviceDescription,
    ) : DescriptionResult

    /**
     * No description could be requested: the device has no `LOCATION` URL (it was
     * first seen via a `byebye`), or the USN is not in the registry.
     */
    public data object NotFound : DescriptionResult

    /**
     * The HTTP fetch failed. [statusCode] is the HTTP status when the server
     * responded with a non-2xx (e.g. 404), or `null` for a transport-level
     * failure (timeout, connection refused, host unreachable). [message] is a
     * human-readable detail.
     */
    public data class FetchFailed(
        public val statusCode: Int?,
        public val message: String,
    ) : DescriptionResult

    /** The document was fetched but the XML could not be parsed. */
    public data class ParseFailed(
        public val message: String,
    ) : DescriptionResult
}
