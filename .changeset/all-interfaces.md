---
title: Discover on every network interface
change: minor
description: The client now listens and searches on every multicast-capable IPv4 interface instead of the first one the OS lists, which was a VPN tunnel on a Mac with a VPN up and broke discovery. bindInterface narrows it to one.
---

Until now a client joined the SSDP group and sent M-SEARCH on a single
interface. With no `bindInterface`, JVM and Android picked the first one the
OS listed that was up, not loopback and multicast-capable; Apple used the
default route. On a Mac with a VPN up, the JVM lists IPv6-only `utun` tunnels
first. The IPv4 join failed there with "Can't assign requested address", and
the client heard nothing.

Now, on every platform:

- **Every LAN interface.** With no `bindInterface`, the client joins the group
  and sends each M-SEARCH on every interface that is up, multicast-capable and
  has an IPv4 address. It skips loopback and point-to-point tunnels (VPNs, PPP).
  A host on Wi-Fi and Ethernet, or with a VM bridge, finds devices on each.
- **One failure doesn't stop the rest.** A join or send that fails on one
  interface is skipped. `MulticastJoinFailed` / `TransportFailed` are thrown only
  when it fails on all of them, with each interface's error in `details`.
- **`bindInterface` narrows discovery to one interface,** by name (`"en0"`) or
  IPv4 address, on every platform. Apple accepts a name now too.
- **No interface qualifies:** the client falls back to the OS default route, as
  before.

All unicast replies still come back to the one ephemeral M-SEARCH port.

###### Behavior changes

- A `bindInterface` that matches no local IPv4 interface now fails with
  `SsdpError.MulticastJoinFailed`, naming the interfaces it found. Before, JVM
  and Android silently fell back to the first multicast-capable interface.
- A multi-homed host now sees devices from every LAN it's on. Pass
  `bindInterface` to keep the old one-network behavior.

IPv4 only: SSDP over IPv6 (`ff02::c`) is still not implemented.
