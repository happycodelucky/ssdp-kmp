/*
 * ssdp-kmp — parsed UPnP device description document (v1.1 feature).
 *
 * The SSDP response/advertisement carries only a LOCATION URL; the document at
 * that URL is the device's full self-description (friendly name, manufacturer,
 * model, icons, services, and recursively-embedded sub-devices). This is the
 * parsed, immutable model of that document, fetched lazily and cached by the
 * client (see SsdpClient.description).
 *
 * House style (matches MSearchResponse / DiscoveredDevice): public classes with
 * identity-keyed equals/hashCode, KDoc per property, URLs as raw String, nullable
 * scalars, lists default to empty, no kotlin.Result, no java.*. Sealed/recursive
 * structure renders cleanly to Swift via SKIE.
 */
package com.happycodelucky.ssdp

import com.happycodelucky.ssdp.internal.UpnpUrl

/**
 * A parsed UPnP device description document (the XML fetched from a device's
 * `LOCATION` URL).
 *
 * Identity is `(sourceUrl, device)` — two descriptions fetched from the same URL
 * describing the same root device are equal.
 *
 * @property specVersion The UPnP architecture version the document declares.
 * @property urlBase The optional `<URLBase>` — the base for resolving the
 *   document's relative URLs (control/event/SCPD/icon/presentation). When absent
 *   or blank, relative URLs resolve against [sourceUrl] instead (UPnP rule).
 * @property device The root [Device]. UPnP documents have exactly one root device
 *   (which may contain embedded sub-devices).
 * @property sourceUrl The `LOCATION` URL the document was fetched from. Used as
 *   the relative-URL base when [urlBase] is absent, and as the cache's staleness
 *   discriminator.
 */
public class DeviceDescription(
    public val specVersion: SpecVersion,
    public val urlBase: String?,
    public val device: Device,
    public val sourceUrl: String,
) {
    /**
     * Resolve a relative URL from this document (e.g. a service's `controlURL`
     * or an icon's `url`) to an absolute URL. Resolves against [urlBase] when it
     * is present and non-blank, otherwise against [sourceUrl], per the UPnP
     * Device Architecture. An already-absolute [url] is returned unchanged.
     */
    public fun resolveUrl(url: String): String = UpnpUrl.resolve(ref = url, base = urlBase?.takeIf { it.isNotBlank() } ?: sourceUrl)

    override fun equals(other: Any?): Boolean = other is DeviceDescription && other.sourceUrl == sourceUrl && other.device == device

    override fun hashCode(): Int = 31 * sourceUrl.hashCode() + device.hashCode()

    override fun toString(): String = "DeviceDescription(sourceUrl=$sourceUrl, device=${device.friendlyName ?: device.udn})"
}

/**
 * The UPnP architecture version a description declares (`<specVersion>`).
 * Almost always 1.0 or 1.1.
 */
public class SpecVersion(
    public val major: Int,
    public val minor: Int,
) {
    override fun equals(other: Any?): Boolean = other is SpecVersion && other.major == major && other.minor == minor

    override fun hashCode(): Int = 31 * major + minor

    override fun toString(): String = "SpecVersion($major.$minor)"
}

/**
 * A device in a description — the root device or a recursively-embedded one.
 *
 * Identity is [udn] (the device's Unique Device Name), which is stable across
 * descriptions of the same physical device.
 *
 * Only [deviceType] and [udn] are guaranteed by the UPnP spec; every other
 * scalar is nullable because real-world devices omit fields freely. The three
 * lists default to empty.
 *
 * @property deviceType `<deviceType>` — `urn:schemas-upnp-org:device:<type>:<v>`.
 * @property friendlyName `<friendlyName>` — the human-readable name (often shown
 *   to users).
 * @property manufacturer `<manufacturer>`.
 * @property manufacturerUrl `<manufacturerURL>` — relative or absolute; resolve
 *   via [DeviceDescription.resolveUrl] if you need an absolute URL.
 * @property modelName `<modelName>`.
 * @property modelNumber `<modelNumber>`.
 * @property modelDescription `<modelDescription>`.
 * @property modelUrl `<modelURL>`.
 * @property serialNumber `<serialNumber>`.
 * @property udn `<UDN>` — the stable unique device name (e.g. `uuid:...`).
 * @property upc `<UPC>` — Universal Product Code, rarely present.
 * @property presentationUrl `<presentationURL>` — a web UI for the device.
 * @property icons `<iconList>` entries; empty when none.
 * @property services `<serviceList>` entries; empty when none.
 * @property embeddedDevices `<deviceList>` sub-devices (recursive); empty when
 *   none.
 * @property extraProperties Vendor/non-standard *leaf* elements that appear as
 *   direct children of this `<device>` and aren't surfaced as typed properties
 *   above — generic access without per-vendor specialization. Keyed by element
 *   local name (any namespace prefix stripped, so `qq:X_QPlay_SoftwareCapability`
 *   → `X_QPlay_SoftwareCapability`), value is the trimmed text content. Real
 *   examples: Sonos `roomName`, `MACAddress`, `softwareVersion`, `feature1`;
 *   Roku `X_...` extensions. Elements that themselves contain child elements
 *   (e.g. Sonos `<versions>`) are not flattened here. Empty when the device
 *   emits no such elements. When a device repeats a leaf name, the last one wins.
 */
public class Device(
    public val deviceType: String,
    public val friendlyName: String?,
    public val manufacturer: String?,
    public val manufacturerUrl: String?,
    public val modelName: String?,
    public val modelNumber: String?,
    public val modelDescription: String?,
    public val modelUrl: String?,
    public val serialNumber: String?,
    public val udn: String,
    public val upc: String?,
    public val presentationUrl: String?,
    public val icons: List<Icon> = emptyList(),
    public val services: List<Service> = emptyList(),
    public val embeddedDevices: List<Device> = emptyList(),
    public val extraProperties: Map<String, String> = emptyMap(),
) {
    override fun equals(other: Any?): Boolean = other is Device && other.udn == udn && other.deviceType == deviceType

    override fun hashCode(): Int = 31 * udn.hashCode() + deviceType.hashCode()

    override fun toString(): String = "Device(udn=$udn, type=$deviceType, name=$friendlyName)"
}

/**
 * An icon advertised in a device's `<iconList>`.
 *
 * @property mimeType `<mimetype>`, e.g. `image/png`.
 * @property width `<width>` in pixels.
 * @property height `<height>` in pixels.
 * @property depth `<depth>` — colour depth in bits.
 * @property url `<url>` — relative or absolute; resolve via
 *   [DeviceDescription.resolveUrl].
 */
public class Icon(
    public val mimeType: String?,
    public val width: Int?,
    public val height: Int?,
    public val depth: Int?,
    public val url: String?,
) {
    override fun equals(other: Any?): Boolean =
        other is Icon &&
            other.mimeType == mimeType &&
            other.width == width &&
            other.height == height &&
            other.depth == depth &&
            other.url == url

    override fun hashCode(): Int {
        var result = mimeType?.hashCode() ?: 0
        result = 31 * result + (width ?: 0)
        result = 31 * result + (height ?: 0)
        result = 31 * result + (depth ?: 0)
        result = 31 * result + (url?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = "Icon($mimeType ${width}x$height, url=$url)"
}

/**
 * A service advertised in a device's `<serviceList>`.
 *
 * Identity is `(serviceId, serviceType)`.
 *
 * @property serviceType `<serviceType>` — `urn:schemas-upnp-org:service:<type>:<v>`.
 * @property serviceId `<serviceId>` — `urn:upnp-org:serviceId:<id>`.
 * @property controlUrl `<controlURL>` — endpoint for SOAP control actions;
 *   relative, resolve via [DeviceDescription.resolveUrl].
 * @property eventSubUrl `<eventSubURL>` — endpoint for GENA event subscription;
 *   present-but-may-be-empty per spec.
 * @property scpdUrl `<SCPDURL>` — URL of the Service Control Protocol Description
 *   (the service's action/state-variable definitions).
 */
public class Service(
    public val serviceType: String,
    public val serviceId: String,
    public val controlUrl: String?,
    public val eventSubUrl: String?,
    public val scpdUrl: String?,
) {
    override fun equals(other: Any?): Boolean = other is Service && other.serviceId == serviceId && other.serviceType == serviceType

    override fun hashCode(): Int = 31 * serviceId.hashCode() + serviceType.hashCode()

    override fun toString(): String = "Service(id=$serviceId, type=$serviceType)"
}
