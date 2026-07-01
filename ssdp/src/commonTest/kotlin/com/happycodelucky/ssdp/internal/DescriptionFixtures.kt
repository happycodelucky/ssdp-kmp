/*
 * ssdp-kmp — real UPnP description-document fixtures for parser tests.
 *
 * EERO_IGD is a verbatim capture from an eero Pro 6 router: recursive
 * deviceList nesting (root -> WANDevice -> WANConnectionDevice), a URLBase, and
 * absolute-path service URLs (/l3f, /ifc, /ipc).
 *
 * SONOS_ZONEPLAYER is a representative excerpt of a Sonos Arc Ultra
 * device_description.xml — trimmed but preserving the parser-stressing quirks:
 * many non-standard vendor elements interleaved with standard ones
 * (softwareVersion, roomName, feature1, an empty <extraVersion/>, a nested
 * <versions> block with repeated <version> children), foreign-namespace
 * extensions (X_Rhapsody-Extension with its own xmlns, qq:X_QPlay with a prefix),
 * an <iconList>, and multiple services. A correct parser must ignore every
 * unknown/foreign element and still extract the standard fields.
 */
package com.happycodelucky.ssdp.internal

internal object DescriptionFixtures {
    val EERO_IGD =
        """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
           <specVersion><major>1</major><minor>0</minor></specVersion>
           <URLBase>http://192.168.4.1:1900/</URLBase>
           <device>
              <deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:1</deviceType>
              <friendlyName>eero</friendlyName>
              <manufacturer>eero inc.</manufacturer>
              <manufacturerURL>https://eero.com</manufacturerURL>
              <modelDescription>eero</modelDescription>
              <modelNumber>0.0.1</modelNumber>
              <modelName>eero Pro 6</modelName>
              <UDN>uuid:fcdb9233-a63f-41da-b42c-7cfeb99c8adf</UDN>
              <serviceList>
                 <service>
                    <serviceType>urn:schemas-upnp-org:service:Layer3Forwarding:1</serviceType>
                    <serviceId>urn:upnp-org:serviceId:L3Forwarding1</serviceId>
                    <controlURL>/l3f</controlURL>
                    <eventSubURL>/l3f/events</eventSubURL>
                    <SCPDURL>/l3f.xml</SCPDURL>
                 </service>
              </serviceList>
              <deviceList>
                 <device>
                    <deviceType>urn:schemas-upnp-org:device:WANDevice:1</deviceType>
                    <friendlyName>eero</friendlyName>
                    <manufacturer>eero inc.</manufacturer>
                    <UDN>uuid:fcdb9233-a63f-41da-b42c-7cfeb99c8adf-wan</UDN>
                    <serviceList>
                       <service>
                          <serviceType>urn:schemas-upnp-org:service:WANCommonInterfaceConfig:1</serviceType>
                          <serviceId>urn:upnp-org:serviceId:WANCommonInterfaceConfig</serviceId>
                          <controlURL>/ifc</controlURL>
                          <eventSubURL>/ifc/events</eventSubURL>
                          <SCPDURL>/ifc.xml</SCPDURL>
                       </service>
                    </serviceList>
                    <deviceList>
                       <device>
                          <deviceType>urn:schemas-upnp-org:device:WANConnectionDevice:1</deviceType>
                          <friendlyName>eero</friendlyName>
                          <UDN>uuid:fcdb9233-a63f-41da-b42c-7cfeb99c8adf-wanconn</UDN>
                          <serviceList>
                             <service>
                                <serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType>
                                <serviceId>urn:upnp-org:serviceId:WANIPConnection</serviceId>
                                <controlURL>/ipc</controlURL>
                                <eventSubURL>/ipc/events</eventSubURL>
                                <SCPDURL>/ipc.xml</SCPDURL>
                             </service>
                          </serviceList>
                       </device>
                    </deviceList>
                 </device>
              </deviceList>
              <presentationURL>https://eero.com</presentationURL>
           </device>
        </root>
        """.trimIndent()

    val SONOS_ZONEPLAYER =
        """
        <?xml version="1.0" encoding="utf-8" ?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <device>
            <deviceType>urn:schemas-upnp-org:device:ZonePlayer:1</deviceType>
            <friendlyName>192.168.4.20 - Sonos Arc Ultra - RINCON_C438751026E501400</friendlyName>
            <manufacturer>Sonos, Inc.</manufacturer>
            <manufacturerURL>http://www.sonos.com</manufacturerURL>
            <modelNumber>S45</modelNumber>
            <modelDescription>Sonos Arc Ultra</modelDescription>
            <modelName>Sonos Arc Ultra</modelName>
            <modelURL>http://www.sonos.com/products/zoneplayers/S45</modelURL>
            <softwareVersion>95.1-78010</softwareVersion>
            <swGen>2</swGen>
            <hardwareVersion>1.41.1.7-1.1</hardwareVersion>
            <serialNum>C4-38-75-10-26-E5:9</serialNum>
            <MACAddress>C4:38:75:10:26:E5</MACAddress>
            <UDN>uuid:RINCON_C438751026E501400</UDN>
            <iconList>
              <icon>
                <id>0</id>
                <mimetype>image/png</mimetype>
                <width>48</width>
                <height>48</height>
                <depth>24</depth>
                <url>/img/icon-S45.png</url>
              </icon>
            </iconList>
            <minCompatibleVersion>94.0-00000</minCompatibleVersion>
            <extraVersion></extraVersion>
            <versions>
              <audioTxProtocol><version>3</version></audioTxProtocol>
              <controlAPI><version>3.5.0</version><version>1.52.1</version></controlAPI>
            </versions>
            <roomName>Living Room</roomName>
            <feature1>0x00006000</feature1>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AlarmClock:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:AlarmClock</serviceId>
                <controlURL>/AlarmClock/Control</controlURL>
                <eventSubURL>/AlarmClock/Event</eventSubURL>
                <SCPDURL>/xml/AlarmClock1.xml</SCPDURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:MusicServices:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:MusicServices</serviceId>
                <controlURL>/MusicServices/Control</controlURL>
                <eventSubURL>/MusicServices/Event</eventSubURL>
                <SCPDURL>/xml/MusicServices1.xml</SCPDURL>
              </service>
            </serviceList>
            <X_Rhapsody-Extension xmlns="http://www.real.com/rhapsody/xmlns/upnp-1-0">
              <deviceID>urn:rhapsody-real-com:device-id-1-0:sonos_1:RINCON_C438751026E501400</deviceID>
            </X_Rhapsody-Extension>
            <qq:X_QPlay_SoftwareCapability xmlns:qq="http://www.tencent.com">QPlay:2</qq:X_QPlay_SoftwareCapability>
          </device>
        </root>
        """.trimIndent()

    /** A truncated document — closing tags missing — to exercise ParseFailed. */
    val MALFORMED =
        """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <device>
            <deviceType>urn:schemas-upnp-org:device:ZonePlayer:1</deviceType>
            <friendlyName>truncated
        """.trimIndent()

    /**
     * A 200 body that is NOT XML at all — what a path-less LOCATION or a health
     * endpoint can return (observed in the field from a gld4tv set-top box whose
     * LOCATION is `http://host:9080`). Must surface a clear ParseFailed, not the
     * raw xmlutil "Non-whitespace text where not expected" error.
     */
    const val NOT_XML = "status=ok"

    /**
     * A valid description prefixed with a UTF-8 BOM, immediately before the `<?xml`
     * prolog as real BOM-emitting devices send it — the content sniff must still
     * recognize this as XML and parse it (no false-negative). Per the XML spec the
     * prolog must be the first content, so a BOM is allowed here but intervening
     * whitespace would not be; the sniff deliberately does not repair such input.
     */
    val BOM_PREFIXED_XML = "\uFEFF" + SONOS_ZONEPLAYER
}
