/*
 * ssdp-kmp — wire-format parser (ported from swift-ssdp's SSDPMessageParser).
 *
 * Lenient by design: real-world devices don't all follow the spec strictly.
 * Missing non-critical headers (EXT, SERVER, DATE, CACHE-CONTROL) are
 * tolerated; only the genuinely required ones cause a `null` return
 * (LOCATION/ST/USN for responses; NT/NTS/USN for NOTIFY, with LOCATION
 * additionally required for alive/update).
 */
package com.happycodelucky.ssdp.internal

import com.happycodelucky.ssdp.Advertisement
import com.happycodelucky.ssdp.MSearchResponse
import com.happycodelucky.ssdp.Notification
import com.happycodelucky.ssdp.SearchTarget
import com.happycodelucky.ssdp.SsdpHeaders
import kotlin.time.Duration.Companion.seconds

/**
 * A typed wire-format SSDP message. SSDP messages share an HTTP/1.1-like syntax
 * (request line + headers + blank line) but fall into three families.
 */
internal sealed interface SsdpMessage {
    /** An M-SEARCH request observed on the multicast group (rare for clients to receive). */
    data object SearchRequest : SsdpMessage

    /** A unicast response to an M-SEARCH (`HTTP/1.1 200 OK`). */
    data class SearchResponse(
        val response: MSearchResponse,
    ) : SsdpMessage

    /** A NOTIFY broadcast — alive, byebye, or update. */
    data class Notify(
        val notification: Notification,
    ) : SsdpMessage
}

internal object SsdpMessageParser {
    /**
     * Parse a UTF-8 string of an SSDP message. Returns `null` if the message
     * can't be recognized. Returns [SsdpMessage.SearchRequest] for an observed
     * M-SEARCH (surfaced for completeness; the client doesn't act on them).
     */
    fun parse(raw: String): SsdpMessage? {
        if (raw.isEmpty()) return null

        // First non-empty line is the request/status line. Split on any
        // newline; lines may be CRLF- or LF-terminated.
        val lines = raw.split('\r', '\n')
        val iterator = lines.iterator()
        val firstLine = iterator.nextNonBlankOrNull() ?: return null
        val leading = firstLine.trim().substringBefore(' ').uppercase()

        // Collect headers from the remaining lines. Headers continue until a
        // blank line; once headers are done the rest is ignored.
        val pairs = LinkedHashMap<String, String>()
        while (iterator.hasNext()) {
            val line = iterator.next()
            if (line.isEmpty()) {
                // A blank line ends the header block — but split('\r','\n') turns
                // each CRLF into one empty token, so a single empty line between
                // header rows is normal. Only stop once we've started reading
                // headers AND hit a true blank separator. Mirroring Swift's
                // `split(whereSeparator: \.isNewline)` (which omits empties), we
                // simply skip empties here and rely on key:value structure.
                continue
            }
            val colon = line.indexOf(':')
            if (colon < 0) continue
            val key = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            if (key.isEmpty()) continue
            pairs[key] = value
        }
        val headers = SsdpHeaders.of(pairs)

        return when (leading) {
            "HTTP/1.1" -> makeSearchResponse(headers)?.let { SsdpMessage.SearchResponse(it) }
            "NOTIFY" -> makeNotification(headers)?.let { SsdpMessage.Notify(it) }
            "M-SEARCH" -> SsdpMessage.SearchRequest
            else -> null
        }
    }

    // --- MSearchResponse construction ---------------------------------------

    private fun makeSearchResponse(headers: SsdpHeaders): MSearchResponse? {
        // Required: LOCATION, ST, USN.
        val location = headers[SsdpHeaderKeys.LOCATION]?.takeIf { it.isNotBlank() } ?: return null
        val stString = headers[SsdpHeaderKeys.SEARCH_TARGET] ?: return null
        val st = SearchTarget.parse(stString) ?: return null
        val usn = headers[SsdpHeaderKeys.USN] ?: return null

        // EXT is required by spec but omitted by Hue bridges and some Roku
        // firmware. Lenient — presence-only.
        val ext = headers[SsdpHeaderKeys.EXT] != null

        val consumed =
            listOf(
                SsdpHeaderKeys.CACHE_CONTROL,
                SsdpHeaderKeys.DATE,
                SsdpHeaderKeys.EXT,
                SsdpHeaderKeys.LOCATION,
                SsdpHeaderKeys.SEARCH_TARGET,
                SsdpHeaderKeys.SERVER,
                SsdpHeaderKeys.USN,
            )

        return MSearchResponse(
            cacheControl = parseCacheControl(headers[SsdpHeaderKeys.CACHE_CONTROL]),
            date = headers[SsdpHeaderKeys.DATE],
            ext = ext,
            location = location,
            server = headers[SsdpHeaderKeys.SERVER],
            searchTarget = st,
            usn = usn,
            otherHeaders = headers.removing(consumed),
        )
    }

    // --- Notification construction ------------------------------------------

    private fun makeNotification(headers: SsdpHeaders): Notification? {
        // Required for all NOTIFYs: NT, NTS, USN.
        val ntString = headers[SsdpHeaderKeys.NOTIFY_TYPE] ?: return null
        val nt = SearchTarget.parse(ntString) ?: return null
        val ntsString = headers[SsdpHeaderKeys.NOTIFY_SUB_TYPE] ?: return null
        val nts = MessageAnnouncement.parse(ntsString.lowercase()) ?: return null
        val usn = headers[SsdpHeaderKeys.USN] ?: return null

        // LOCATION is required for alive/update; absent (optional) for byebye.
        val location: String?
        when (nts) {
            MessageAnnouncement.ALIVE, MessageAnnouncement.UPDATE -> {
                location = headers[SsdpHeaderKeys.LOCATION]?.takeIf { it.isNotBlank() } ?: return null
            }

            MessageAnnouncement.BYEBYE -> {
                location = headers[SsdpHeaderKeys.LOCATION]?.takeIf { it.isNotBlank() }
            }

            MessageAnnouncement.DISCOVER -> {
                return null
            } // ssdp:discover is not a valid NTS.
        }

        val consumed =
            listOf(
                SsdpHeaderKeys.CACHE_CONTROL,
                SsdpHeaderKeys.LOCATION,
                SsdpHeaderKeys.NOTIFY_TYPE,
                SsdpHeaderKeys.NOTIFY_SUB_TYPE,
                SsdpHeaderKeys.SERVER,
                SsdpHeaderKeys.USN,
                SsdpHeaderKeys.BOOT_ID,
                SsdpHeaderKeys.CONFIG_ID,
                SsdpHeaderKeys.NEXT_BOOT_ID,
            )

        val advertisement =
            Advertisement(
                notificationTarget = nt,
                usn = usn,
                location = location,
                server = headers[SsdpHeaderKeys.SERVER],
                cacheControl = parseCacheControl(headers[SsdpHeaderKeys.CACHE_CONTROL]),
                bootId = headers[SsdpHeaderKeys.BOOT_ID]?.toIntOrNull(),
                configId = headers[SsdpHeaderKeys.CONFIG_ID]?.toIntOrNull(),
                nextBootId = headers[SsdpHeaderKeys.NEXT_BOOT_ID]?.toIntOrNull(),
                otherHeaders = headers.removing(consumed),
            )

        return when (nts) {
            MessageAnnouncement.ALIVE -> Notification.Alive(advertisement)
            MessageAnnouncement.BYEBYE -> Notification.Byebye(advertisement)
            MessageAnnouncement.UPDATE -> Notification.Update(advertisement)
            MessageAnnouncement.DISCOVER -> null
        }
    }

    // --- Header value parsing -----------------------------------------------

    /**
     * Parse a `CACHE-CONTROL` value, extracting the `max-age` directive as a
     * [kotlin.time.Duration]. `CACHE-CONTROL` can carry multiple directives
     * separated by commas, e.g. `max-age=1800, no-cache`; only `max-age` is
     * used. Returns `null` if absent or unparseable.
     */
    private fun parseCacheControl(value: String?): kotlin.time.Duration? {
        if (value == null) return null
        for (directive in value.split(",")) {
            val trimmed = directive.trim()
            val eq = trimmed.indexOf('=')
            if (eq < 0) continue
            val key = trimmed.substring(0, eq).trim().lowercase()
            val rawSeconds = trimmed.substring(eq + 1).trim()
            if (key != "max-age") continue
            val seconds = rawSeconds.toIntOrNull() ?: continue
            return seconds.seconds
        }
        return null
    }

    private fun Iterator<String>.nextNonBlankOrNull(): String? {
        while (hasNext()) {
            val next = next()
            if (next.isNotBlank()) return next
        }
        return null
    }
}
