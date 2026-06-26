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
 * All cases follow the canonical UPnP forms:
 *
 * - [All] → `ssdp:all`
 * - [RootDevice] → `upnp:rootdevice`
 * - [Uuid] → `uuid:<UUID>`
 * - [DeviceType] → `urn:<schema>:device:<type>:<version>`
 * - [ServiceType] → `urn:<schema>:service:<type>:<version>`
 *
 * Use [rawValue] to serialize to the wire string, and [SearchTarget.parse] to
 * read one back (returns `null` for unrecognized forms — lenient by design).
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
    }
}
