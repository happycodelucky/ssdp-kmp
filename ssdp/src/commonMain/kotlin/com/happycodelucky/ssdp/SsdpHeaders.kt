/*
 * ssdp-kmp — case-insensitive SSDP header map (ported from swift-ssdp's
 * SSDPHeaders).
 */
package com.happycodelucky.ssdp

/**
 * A case-insensitive collection of SSDP headers.
 *
 * Header names in SSDP are case-insensitive (they share HTTP/1.1's rules), but
 * real-world devices freely mix `CACHE-CONTROL`, `Cache-Control`, and
 * `cache-control`. This wrapper normalizes lookups so the parser and consumers
 * can read by canonical key without juggling case variants.
 *
 * Values are stored as supplied; only lookup is normalized (keys are stored
 * uppercase, the SSDP/UPnP canonical form). Exposed as the `otherHeaders`
 * property on [DiscoveredDevice] so consumers can read UPnP 1.1 fields
 * (`SEARCHPORT.UPNP.ORG`, `SECURELOCATION.UPNP.ORG`, …) that aren't surfaced as
 * typed properties.
 */
public class SsdpHeaders private constructor(
    private val storage: Map<String, String>,
) {
    /** Case-insensitive lookup by header name. */
    public operator fun get(key: String): String? = storage[key.uppercase()]

    /** Case-insensitive membership test — `"x-rincon-bootseq" in headers`. */
    public operator fun contains(key: String): Boolean = storage.containsKey(key.uppercase())

    /**
     * The header names present, in canonical uppercase form. Lets consumers
     * *discover* which custom/vendor headers a device sent (e.g. to enumerate
     * `X-…` extensions) rather than only look them up by a name they already
     * know.
     */
    public val keys: Set<String> get() = storage.keys

    /** True if no headers are set. */
    public val isEmpty: Boolean get() = storage.isEmpty()

    /** Number of headers in the set. */
    public val size: Int get() = storage.size

    /** The underlying map (uppercase-keyed) for advanced use. */
    public val asMap: Map<String, String> get() = storage

    /**
     * Iterate name/value pairs (names in canonical uppercase form) — enables
     * `for ((name, value) in headers)` and other stdlib iterable use.
     */
    public operator fun iterator(): Iterator<Map.Entry<String, String>> = storage.entries.iterator()

    /**
     * Returns a copy of the header set with the given keys removed
     * (case-insensitive). Used by the parser to compute `otherHeaders` after
     * pulling out the keys it surfaces as typed properties.
     */
    internal fun removing(keys: Collection<String>): SsdpHeaders {
        val drop = keys.mapTo(HashSet()) { it.uppercase() }
        return SsdpHeaders(storage.filterKeys { it !in drop })
    }

    override fun equals(other: Any?): Boolean = other is SsdpHeaders && other.storage == storage

    override fun hashCode(): Int = storage.hashCode()

    override fun toString(): String = "SsdpHeaders($storage)"

    public companion object {
        /** An empty header set. */
        public val EMPTY: SsdpHeaders = SsdpHeaders(emptyMap())

        /**
         * Build a header set from raw key/value pairs. If the input contains
         * multiple keys that compare equal case-insensitively, the last one
         * encountered wins.
         */
        public fun of(pairs: Map<String, String>): SsdpHeaders {
            if (pairs.isEmpty()) return EMPTY
            val normalized = LinkedHashMap<String, String>(pairs.size)
            for ((k, v) in pairs) {
                normalized[k.uppercase()] = v
            }
            return SsdpHeaders(normalized)
        }
    }
}
