/*
 * ssdp-kmp — UPnP description XML parser (v1.1 feature).
 *
 * Parses the <root> device-description document (namespace
 * urn:schemas-upnp-org:device-1-0) into the public DeviceDescription tree.
 *
 * Behind an interface so (a) tests can inject a fake parser, and (b) if xmlutil's
 * native (ARM) support proves shaky, a hand-written kotlinx.io pull-parser can be
 * dropped in behind this same seam without touching the service/cache/client
 * (CLAUDE.md §4, and the design's top open risk).
 *
 * Robustness: real devices (e.g. Sonos) interleave dozens of vendor-specific and
 * foreign-namespace elements with the standard ones. xmlutil is configured to
 * ignore unknown children so those don't fail the typed parse. A second,
 * additive pull-parser pass (captureExtras) then recovers those otherwise-dropped
 * per-device leaf elements into [Device.extraProperties] for generic access
 * without per-vendor specialization.
 */
package com.happycodelucky.ssdp.internal

import com.happycodelucky.ssdp.Device
import com.happycodelucky.ssdp.DeviceDescription
import com.happycodelucky.ssdp.Icon
import com.happycodelucky.ssdp.Service
import com.happycodelucky.ssdp.SpecVersion
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.serialization.XML
import nl.adaptivity.xmlutil.serialization.XmlChildrenName
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName
import nl.adaptivity.xmlutil.xmlStreaming

/** Parses a UPnP description document body into a [DeviceDescription]. */
internal interface DescriptionParser {
    /**
     * @param body the raw XML text.
     * @param sourceUrl the LOCATION url it was fetched from (becomes
     *   [DeviceDescription.sourceUrl] for relative-URL resolution).
     * @throws Exception if the XML can't be parsed; the caller maps this to
     *   [com.happycodelucky.ssdp.DescriptionResult.ParseFailed].
     */
    fun parse(
        body: String,
        sourceUrl: String,
    ): DeviceDescription
}

/** The production xmlutil-backed parser. */
internal object XmlDescriptionParser : DescriptionParser {
    // UPnP device descriptions live in this namespace. xmlutil matches element
    // names against it; foreign-namespace vendor extensions (Sonos ships several)
    // are simply unknown children and are ignored.
    private const val UPNP_NS = "urn:schemas-upnp-org:device-1-0"

    private val xml =
        // xmlutil 1.0.0 deprecated the bare `XML { }` constructor and the compat/
        // unversioned factories in favour of the version-pinned `XML.v1` companion
        // (its @Deprecated ReplaceWith points here) — this locks the 1.0 serialized
        // format so a future xmlutil bump can't silently change how device XML is
        // read. The parser's real-device fixtures (eero/Sonos captures in
        // DescriptionParserTest) guard that behaviour is preserved.
        XML.v1 {
            // Tolerate the unknown/vendor elements real devices emit. Without
            // this, a single <X_Rhapsody-Extension> or <roomName> fails the parse.
            recommended_1_0_0 {
                ignoreUnknownChildren()
            }
        }

    override fun parse(
        body: String,
        sourceUrl: String,
    ): DeviceDescription {
        val root = xml.decodeFromString(WireRoot.serializer(), body)
        // Second, additive pass: the xmlutil deserialization above intentionally
        // drops unknown/vendor elements (ignoreUnknownChildren). Walk the same
        // document with the pull-parser to recover those leaf elements per device
        // as a generic bag, then graft them onto the typed tree. Kept separate so
        // the proven typed extraction is untouched; a capture failure degrades to
        // empty bags rather than failing the whole parse.
        val extras = runCatching { captureExtras(body) }.getOrNull()
        return DeviceDescription(
            specVersion = root.specVersion?.toModel() ?: SpecVersion(1, 0),
            urlBase = root.urlBase?.trim()?.takeIf { it.isNotEmpty() },
            device = root.device.toModel(extras),
            sourceUrl = sourceUrl,
        )
    }

    // --- generic extra-property capture (pull-parser) -------------------------

    /**
     * A parallel tree of the vendor/unknown leaf elements found under each
     * `<device>`, mirroring the `<deviceList>` nesting so it can be zipped onto
     * the typed [Device] tree structurally (by position), independent of how
     * xmlutil orders its own deserialization.
     */
    private class DeviceExtras(
        val properties: Map<String, String>,
        val children: List<DeviceExtras>,
    )

    /**
     * Parse [body] with the multiplatform generic pull-reader and return the
     * root device's [DeviceExtras], or `null` if no root `<device>` is found.
     * Uses `newGenericReader` (not the platform reader) so traversal is
     * byte-identical across JVM/Native/Apple.
     */
    private fun captureExtras(body: String): DeviceExtras? {
        // xmlutil's XmlReader implements its own multiplatform Closeable (not
        // kotlin.AutoCloseable), which has no exported `use` extension — close it
        // explicitly.
        val reader = xmlStreaming.newGenericReader(body)
        try {
            while (reader.hasNext()) {
                if (reader.next() == EventType.START_ELEMENT && reader.localName == "device") {
                    return readDeviceExtras(reader)
                }
            }
            return null
        } finally {
            reader.close()
        }
    }

    /**
     * Called positioned on a `<device>` START_ELEMENT; consumes through its
     * matching END_ELEMENT. Records direct-child *leaf* elements not surfaced as
     * typed properties into [DeviceExtras.properties], and recurses into the
     * `<deviceList>`/`<device>` children to build the nested structure.
     */
    private fun readDeviceExtras(reader: XmlReader): DeviceExtras {
        val properties = LinkedHashMap<String, String>()
        val children = ArrayList<DeviceExtras>()
        // Depth relative to this <device>: its direct children sit at depth 1.
        var depth = 1
        while (reader.hasNext() && depth > 0) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                // Either descends a level or is consumed whole by a handler;
                // onStartElement returns the new depth so the loop stays flat.
                depth = onStartElement(reader, depth, properties, children)
            } else if (event == EventType.END_ELEMENT) {
                depth--
            }
            // TEXT/CDATA/comments/whitespace between elements: ignored.
        }
        return DeviceExtras(properties, children)
    }

    /**
     * Dispatch a START_ELEMENT encountered inside a `<device>` at [depth]. Returns
     * the depth the caller's loop should continue at:
     * - a nested `<device>` (inside `<deviceList>`, depth 2) is read as a child via
     *   [readDeviceExtras] and consumed whole → depth unchanged;
     * - an unknown direct-child leaf (depth 1) is captured into [properties] and
     *   consumed whole → depth unchanged;
     * - anything else (a `<deviceList>` we step into, a typed container, or a
     *   deeper element) → depth + 1, letting the matching END_ELEMENT pop it.
     */
    private fun onStartElement(
        reader: XmlReader,
        depth: Int,
        properties: MutableMap<String, String>,
        children: MutableList<DeviceExtras>,
    ): Int {
        val name = reader.localName
        return when {
            depth == 2 && name == "device" -> {
                children.add(readDeviceExtras(reader))
                depth
            }

            depth == 1 && name !in TYPED_DEVICE_CHILDREN -> {
                captureLeaf(reader)?.let { properties[name] = it }
                depth
            }

            else -> {
                depth + 1
            }
        }
    }

    /**
     * Called positioned on a candidate leaf START_ELEMENT. Consumes through its
     * matching END_ELEMENT and returns the concatenated, trimmed text if the
     * element is a true leaf (no child elements); returns `null` if it contains
     * child elements (e.g. Sonos `<versions>`), so nested vendor blocks aren't
     * flattened into a misleading string.
     */
    private fun captureLeaf(reader: XmlReader): String? {
        val text = StringBuilder()
        var hasChildElement = false
        var depth = 1
        while (reader.hasNext() && depth > 0) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT) {
                hasChildElement = true
                depth++
            } else if (event == EventType.END_ELEMENT) {
                depth--
            } else if ((event == EventType.TEXT || event == EventType.CDSECT) && !hasChildElement) {
                // Concatenate this leaf's direct text; once a child element is
                // seen, further text belongs to that child and is ignored.
                text.append(reader.text)
            }
        }
        if (hasChildElement) return null
        return text.toString().trim().takeIf { it.isNotEmpty() } ?: ""
    }

    // Direct children of <device> that the typed model already surfaces, so the
    // generic bag doesn't duplicate them. Matched by local name (namespace-
    // agnostic), consistent with how xmlutil binds them. iconList/serviceList/
    // deviceList are structural containers; deviceList is handled explicitly above.
    private val TYPED_DEVICE_CHILDREN =
        setOf(
            "deviceType",
            "friendlyName",
            "manufacturer",
            "manufacturerURL",
            "modelName",
            "modelNumber",
            "modelDescription",
            "modelURL",
            "serialNumber",
            "UDN",
            "UPC",
            "presentationURL",
            "iconList",
            "serviceList",
            "deviceList",
        )

    // --- wire types (xmlutil @Serializable) — never cross SKIE ----------------

    @Serializable
    @XmlSerialName(value = "root", namespace = UPNP_NS, prefix = "")
    private class WireRoot(
        val specVersion: WireSpecVersion? = null,
        @XmlElement(true)
        @XmlSerialName(value = "URLBase", namespace = UPNP_NS, prefix = "")
        val urlBase: String? = null,
        val device: WireDevice,
    )

    @Serializable
    @XmlSerialName(value = "specVersion", namespace = UPNP_NS, prefix = "")
    private class WireSpecVersion(
        @XmlElement(true)
        @XmlSerialName(value = "major", namespace = UPNP_NS, prefix = "")
        val major: Int = 1,
        @XmlElement(true)
        @XmlSerialName(value = "minor", namespace = UPNP_NS, prefix = "")
        val minor: Int = 0,
    ) {
        fun toModel() = SpecVersion(major, minor)
    }

    @Serializable
    @XmlSerialName(value = "device", namespace = UPNP_NS, prefix = "")
    private class WireDevice(
        @XmlElement(true) @XmlSerialName("deviceType", UPNP_NS, "") val deviceType: String = "",
        @XmlElement(true) @XmlSerialName("friendlyName", UPNP_NS, "") val friendlyName: String? = null,
        @XmlElement(true) @XmlSerialName("manufacturer", UPNP_NS, "") val manufacturer: String? = null,
        @XmlElement(true) @XmlSerialName("manufacturerURL", UPNP_NS, "") val manufacturerUrl: String? = null,
        @XmlElement(true) @XmlSerialName("modelName", UPNP_NS, "") val modelName: String? = null,
        @XmlElement(true) @XmlSerialName("modelNumber", UPNP_NS, "") val modelNumber: String? = null,
        @XmlElement(true) @XmlSerialName("modelDescription", UPNP_NS, "") val modelDescription: String? = null,
        @XmlElement(true) @XmlSerialName("modelURL", UPNP_NS, "") val modelUrl: String? = null,
        @XmlElement(true) @XmlSerialName("serialNumber", UPNP_NS, "") val serialNumber: String? = null,
        @XmlElement(true) @XmlSerialName("UDN", UPNP_NS, "") val udn: String = "",
        @XmlElement(true) @XmlSerialName("UPC", UPNP_NS, "") val upc: String? = null,
        @XmlElement(true) @XmlSerialName("presentationURL", UPNP_NS, "") val presentationUrl: String? = null,
        @XmlChildrenName("icon", UPNP_NS, "")
        @XmlSerialName("iconList", UPNP_NS, "")
        val iconList: List<WireIcon> = emptyList(),
        @XmlChildrenName("service", UPNP_NS, "")
        @XmlSerialName("serviceList", UPNP_NS, "")
        val serviceList: List<WireService> = emptyList(),
        @XmlChildrenName("device", UPNP_NS, "")
        @XmlSerialName("deviceList", UPNP_NS, "")
        val deviceList: List<WireDevice> = emptyList(),
    ) {
        // [extras] is this device's captured leaf bag + child bags from the
        // parallel pull-parser walk (null if capture was skipped/failed). It and
        // [deviceList] mirror the same <deviceList> nesting in document order, so
        // embedded devices zip by position.
        fun toModel(extras: DeviceExtras? = null): Device =
            Device(
                deviceType = deviceType,
                friendlyName = friendlyName?.trim(),
                manufacturer = manufacturer?.trim(),
                manufacturerUrl = manufacturerUrl?.trim(),
                modelName = modelName?.trim(),
                modelNumber = modelNumber?.trim(),
                modelDescription = modelDescription?.trim(),
                modelUrl = modelUrl?.trim(),
                serialNumber = serialNumber?.trim(),
                udn = udn,
                upc = upc?.trim(),
                presentationUrl = presentationUrl?.trim(),
                icons = iconList.map { it.toModel() },
                services = serviceList.map { it.toModel() },
                embeddedDevices = deviceList.mapIndexed { i, child -> child.toModel(extras?.children?.getOrNull(i)) },
                extraProperties = extras?.properties ?: emptyMap(),
            )
    }

    @Serializable
    @XmlSerialName(value = "icon", namespace = UPNP_NS, prefix = "")
    private class WireIcon(
        @XmlElement(true) @XmlSerialName("mimetype", UPNP_NS, "") val mimeType: String? = null,
        @XmlElement(true) @XmlSerialName("width", UPNP_NS, "") val width: Int? = null,
        @XmlElement(true) @XmlSerialName("height", UPNP_NS, "") val height: Int? = null,
        @XmlElement(true) @XmlSerialName("depth", UPNP_NS, "") val depth: Int? = null,
        @XmlElement(true) @XmlSerialName("url", UPNP_NS, "") val url: String? = null,
    ) {
        fun toModel() = Icon(mimeType?.trim(), width, height, depth, url?.trim())
    }

    @Serializable
    @XmlSerialName(value = "service", namespace = UPNP_NS, prefix = "")
    private class WireService(
        @XmlElement(true) @XmlSerialName("serviceType", UPNP_NS, "") val serviceType: String = "",
        @XmlElement(true) @XmlSerialName("serviceId", UPNP_NS, "") val serviceId: String = "",
        @XmlElement(true) @XmlSerialName("controlURL", UPNP_NS, "") val controlUrl: String? = null,
        @XmlElement(true) @XmlSerialName("eventSubURL", UPNP_NS, "") val eventSubUrl: String? = null,
        @XmlElement(true) @SerialName("SCPDURL") @XmlSerialName("SCPDURL", UPNP_NS, "") val scpdUrl: String? = null,
    ) {
        fun toModel() = Service(serviceType, serviceId, controlUrl?.trim(), eventSubUrl?.trim(), scpdUrl?.trim())
    }
}
