/*
 * ssdp-kmp — public API surface (CLAUDE.md §8).
 *
 * Ported from swift-ssdp's SSDPSearchTarget. Designed for Swift consumers as
 * much as Kotlin ones: the sealed interface becomes an exhaustive Swift enum
 * via SKIE (`onEnum(of:)`), and each leaf is a value-type-like data object.
 */
package com.happycodelucky.ssdp

/**
 * A device or service search target.
 *
 * Search targets serve double duty in SSDP: they appear as `ST` in M-SEARCH
 * requests and search responses, and as `NT` in NOTIFY broadcasts. The wire
 * format is identical in both roles, so a single type covers both.
 *
 * The canonical UPnP forms plus a [Custom] escape hatch for vendor targets:
 *
 * - [All] → `ssdp:all`
 * - [RootDevice] → `upnp:rootdevice`
 * - [Uuid] → `uuid:<UUID>`
 * - [DeviceType] → `urn:<schema>:device:<type>:<version>`
 * - [ServiceType] → `urn:<schema>:service:<type>:<version>`
 * - [Custom] → any non-UPnP wire string (e.g. `roku:ecp`)
 *
 * Use [rawValue] to serialize to the wire string. Two read-back helpers:
 * [SearchTarget.parse] is strict (returns `null` for anything not one of the
 * canonical forms above), while [SearchTarget.parseOrCustom] falls back to
 * [Custom] for any non-blank unrecognized string. Use `parse` when you only
 * accept UPnP forms; use `parseOrCustom` to preserve whatever a device
 * announced (including vendor targets).
 */
public sealed interface SearchTarget {
    /** The wire-string form, suitable for use as an `ST` or `NT` header value. */
    public val rawValue: String

    /** `ssdp:all` — match any device or service. */
    public data object All : SearchTarget {
        override val rawValue: String get() = "ssdp:all"
    }

    /** `upnp:rootdevice` — match root devices only. */
    public data object RootDevice : SearchTarget {
        override val rawValue: String get() = "upnp:rootdevice"
    }

    /** `uuid:<UUID>` — match a specific device by its UUID. */
    public data class Uuid(
        val id: String,
    ) : SearchTarget {
        override val rawValue: String get() = "uuid:$id"
    }

    /**
     * `urn:<schema>:device:<type>:<version>` — match any device of the given
     * type. Per RFC 2141, period characters in the schema must be replaced
     * with hyphens.
     */
    public data class DeviceType(
        val schema: String,
        val deviceType: String,
        val version: Int,
    ) : SearchTarget {
        override val rawValue: String get() = "urn:$schema:device:$deviceType:$version"
    }

    /**
     * `urn:<schema>:service:<type>:<version>` — match any service of the given
     * type. Per RFC 2141, period characters in the schema must be replaced
     * with hyphens.
     */
    public data class ServiceType(
        val schema: String,
        val serviceType: String,
        val version: Int,
    ) : SearchTarget {
        override val rawValue: String get() = "urn:$schema:service:$serviceType:$version"
    }

    /**
     * An arbitrary, non-UPnP search target carried verbatim on the wire — e.g.
     * Roku's `roku:ecp`, which is not one of the canonical forms.
     *
     * Unlike the other leaves (which compute [rawValue] from their parts), the
     * wire string *is* the value here, so [rawValue] is the stored property.
     * Prefer the canonical cases where one applies; [SearchTarget.parseOrCustom]
     * only produces a [Custom] when no canonical form matches.
     */
    public data class Custom(
        override val rawValue: String,
    ) : SearchTarget

    public companion object {
        /** Schema string for UPnP-forum working-committee devices and services. */
        public const val UPNP_ORG_SCHEMA: String = "schemas-upnp-org"

        /**
         * Parse a search target from a wire string (`ST` / `NT` header value).
         *
         * Returns `null` if the string does not match one of the recognized
         * SSDP forms. Lenient parsing is intentional — some devices send
         * slightly malformed URNs and we'd rather surface them as `null` than
         * throw, but valid forms are accepted.
         */
        public fun parse(rawValue: String): SearchTarget? {
            val components = rawValue.split(":")
            if (components.isEmpty()) return null

            return when (components.size) {
                2 -> {
                    when (components[0]) {
                        "ssdp" -> if (components[1] == "all") All else null
                        "upnp" -> if (components[1] == "rootdevice") RootDevice else null
                        "uuid" -> Uuid(components[1])
                        else -> null
                    }
                }

                5 -> {
                    // urn:<schema>:device|service:<type>:<version>
                    val version = components[4].toIntOrNull()
                    if (components[0] != "urn" || version == null) {
                        null
                    } else {
                        when (components[2]) {
                            "device" -> {
                                DeviceType(schema = components[1], deviceType = components[3], version = version)
                            }

                            "service" -> {
                                ServiceType(
                                    schema = components[1],
                                    serviceType = components[3],
                                    version = version,
                                )
                            }

                            else -> {
                                null
                            }
                        }
                    }
                }

                else -> {
                    null
                }
            }
        }

        /**
         * Parse a search target, falling back to [Custom] for a non-UPnP form.
         *
         * Tries the canonical forms first (via [parse]), so `ssdp:all` still
         * yields [All], never `Custom("ssdp:all")`. Any other **non-blank**
         * string becomes [Custom] verbatim — e.g. `parseOrCustom("roku:ecp")`
         * is `Custom("roku:ecp")`. A blank string returns `null` (a blank
         * `ST`/`NT` is junk, not a target).
         *
         * This is what the wire parser uses so a device announcing a vendor
         * target is surfaced rather than dropped; use the strict [parse] when
         * only canonical UPnP forms are acceptable.
         */
        public fun parseOrCustom(rawValue: String): SearchTarget? {
            if (rawValue.isBlank()) return null
            return parse(rawValue) ?: Custom(rawValue)
        }
    }
}
