/*
 * ssdp-kmp — portable URL resolution for UPnP relative references.
 *
 * UPnP description documents carry relative URLs (e.g. controlURL "/l3f",
 * SCPDURL "/ipc.xml", icon url "/img/icon.png"). These must be resolved against
 * the document's <URLBase> if present, else against the description's own URL.
 *
 * commonMain has no java.net.URI, so this is a small hand-rolled resolver
 * covering the reference forms UPnP devices actually emit (RFC 3986 §5.2/5.3,
 * the practical subset). It is deliberately lenient: a base it can't parse is
 * returned with the ref appended sensibly rather than throwing — a bad URL is a
 * data problem for the consumer, not a crash.
 */
package com.happycodelucky.ssdp.internal

internal object UpnpUrl {
    /**
     * Resolve [ref] against [base].
     *
     * - [ref] absolute (has a scheme like `http:`) → returned unchanged.
     * - [ref] is protocol-relative (`//host/path`) → take [base]'s scheme.
     * - [ref] is an absolute path (`/path`) → take [base]'s scheme + authority.
     * - [ref] is a relative path (`path`) → merge onto [base]'s directory.
     * - [ref] blank → [base].
     */
    fun resolve(
        ref: String,
        base: String,
    ): String {
        val r = ref.trim()
        if (r.isEmpty()) return base
        if (hasScheme(r)) return r

        val parsed = parse(base) ?: return r // base unparseable → best we can do is the ref itself

        return when {
            r.startsWith("//") -> "${parsed.scheme}:$r"
            r.startsWith("/") -> "${parsed.scheme}://${parsed.authority}$r"
            else -> "${parsed.scheme}://${parsed.authority}${mergePath(parsed.path, r)}"
        }
    }

    /** True if [s] begins with a URI scheme (`scheme:`), e.g. `http:`, `https:`. */
    private fun hasScheme(s: String): Boolean {
        val colon = s.indexOf(':')
        if (colon <= 0) return false
        // A scheme is ALPHA *( ALPHA / DIGIT / "+" / "-" / "." ), and the part
        // before ':' must contain no '/' (otherwise the colon is in a path/port).
        val candidate = s.substring(0, colon)
        if (candidate.any { it == '/' }) return false
        if (!candidate[0].isLetter()) return false
        return candidate.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }
    }

    private data class Parts(
        val scheme: String,
        val authority: String,
        val path: String,
    )

    /** Parse `scheme://authority/path...` — the only base shape UPnP LOCATION/URLBase take. */
    private fun parse(url: String): Parts? {
        val schemeSep = url.indexOf("://")
        if (schemeSep <= 0) return null
        val scheme = url.substring(0, schemeSep)
        val rest = url.substring(schemeSep + 3)
        // Authority runs up to the first '/', '?' or '#'.
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        return if (authorityEnd < 0) {
            Parts(scheme, rest, "/")
        } else {
            val authority = rest.substring(0, authorityEnd)
            // Path is from the first '/' up to (but not including) any query/fragment.
            val tail = rest.substring(authorityEnd)
            val path = tail.takeWhile { it != '?' && it != '#' }.ifEmpty { "/" }
            Parts(scheme, authority, path)
        }
    }

    /**
     * Merge a relative [rel] path onto the directory of [basePath] (RFC 3986
     * §5.3 "merge"): everything up to and including the last '/' of [basePath],
     * then [rel]. If [basePath] has no '/', the merged path is just `/rel`.
     */
    private fun mergePath(
        basePath: String,
        rel: String,
    ): String {
        val lastSlash = basePath.lastIndexOf('/')
        val dir = if (lastSlash < 0) "/" else basePath.substring(0, lastSlash + 1)
        return dir + rel
    }
}
