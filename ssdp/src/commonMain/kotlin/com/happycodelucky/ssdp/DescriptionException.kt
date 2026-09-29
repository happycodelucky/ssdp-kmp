/*
 * ssdp-kmp — the failure half of `SsdpClient.description`'s `Result`
 * (CLAUDE.md §7, LESSONS D-015).
 *
 * A sealed `Exception` hierarchy rather than a sealed result type: it is what a
 * KotlinResult `Result` carries, so Kotlin consumers get the standard `Result`
 * API with a closed, exhaustively-matchable error set. Swift catches it as
 * itself (`catch let e as DescriptionException`) — KotlinResult's bundled Swift
 * throws Kotlin exceptions as Swift errors — and switches with SKIE's
 * `onEnum(of:)`. Same shape as the sibling wake-kmp's `WakeException`.
 */
package com.happycodelucky.ssdp

/**
 * Why a [SsdpClient.description] call failed. Always the exception inside a
 * failed `Result<DeviceDescription>`.
 *
 * ```kotlin
 * client.description(device)
 *     .onSuccess { show(it.device.friendlyName) }
 *     .onFailure { e ->
 *         when (e as? DescriptionException) {
 *             is DescriptionException.NotFound -> showUnknown()
 *             is DescriptionException.FetchFailed -> retryLater(e.statusCode)
 *             is DescriptionException.ParseFailed -> logBadDevice(e.message)
 *             null -> throw e
 *         }
 *     }
 * ```
 *
 * ```swift
 * do {
 *     let description: DeviceDescription = try await client.description(device: device).get()
 * } catch let e as DescriptionException {
 *     switch onEnum(of: e) { … }
 * }
 * ```
 */
public sealed class DescriptionException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Non-null: every [DescriptionException] is constructed with a message. */
    override val message: String
        get() = super.message.orEmpty()

    /**
     * No description could be requested: the device has no `LOCATION` URL (it was
     * first seen via a `byebye`), the USN is not in the registry, or the client
     * is closed.
     *
     * @property usn the device the description was requested for.
     */
    public class NotFound(public val usn: String) : DescriptionException("no description location for $usn")

    /**
     * The HTTP fetch failed.
     *
     * @property statusCode the HTTP status when the server responded with a
     *   non-2xx (e.g. 404), or `null` for a transport-level failure (timeout,
     *   connection refused, host unreachable) — then [cause] holds the platform
     *   exception.
     */
    public class FetchFailed(public val statusCode: Int?, message: String, cause: Throwable? = null) :
        DescriptionException(message, cause)

    /**
     * The document was fetched but could not be parsed: not XML at all, or XML
     * that isn't a UPnP device description. [cause] holds the parser's exception
     * where there was one.
     */
    public class ParseFailed(message: String, cause: Throwable? = null) : DescriptionException(message, cause)
}
