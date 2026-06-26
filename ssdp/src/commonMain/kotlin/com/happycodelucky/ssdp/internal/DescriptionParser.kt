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
 * ignore unknown children so those don't fail the parse.
 */
package com.happycodelucky.ssdp.internal

import com.happycodelucky.ssdp.Device
import com.happycodelucky.ssdp.DeviceDescription
import com.happycodelucky.ssdp.Icon
import com.happycodelucky.ssdp.Service
import com.happycodelucky.ssdp.SpecVersion
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XML
import nl.adaptivity.xmlutil.serialization.XmlChildrenName
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

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
        XML {
            // Tolerate the unknown/vendor elements real devices emit. Without
            // this, a single <X_Rhapsody-Extension> or <roomName> fails the parse.
            defaultPolicy {
                ignoreUnknownChildren()
            }
        }

    override fun parse(
        body: String,
        sourceUrl: String,
    ): DeviceDescription {
        val root = xml.decodeFromString(WireRoot.serializer(), body)
        return DeviceDescription(
            specVersion = root.specVersion?.toModel() ?: SpecVersion(1, 0),
            urlBase = root.urlBase?.trim()?.takeIf { it.isNotEmpty() },
            device = root.device.toModel(),
            sourceUrl = sourceUrl,
        )
    }

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
        fun toModel(): Device =
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
                embeddedDevices = deviceList.map { it.toModel() },
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
