---
title: Send M-SEARCH from an ephemeral port so every client gets its replies
change: patch
description: M-SEARCH now leaves from its own ephemeral-port socket, so a device's unicast reply reaches the client that asked even when other sockets on the host share port 1900.
---

`SsdpClient` used to send M-SEARCH from the same UDP socket it binds to port
1900 for NOTIFY. Devices answer an M-SEARCH by unicast to the request's source
port, so every reply went to port 1900. When more than one socket on the host
shares 1900 through address reuse (two `SsdpClient`s in one process, or a
browser, Spotify or a media server running alongside), the operating system
delivers a unicast datagram to only one of those sockets, and the other client
silently missed the reply. On a real LAN, a JVM consumer running three clients
found a Roku answering `roku:ecp` in 1 of 5 scans.

Each client now opens two sockets, as UPnP control points do: the 1900 socket
joined to `239.255.255.250` still hears `ssdp:alive` / `ssdp:byebye` /
`ssdp:update`, and M-SEARCH goes out on a second socket bound to an ephemeral
port, which receives the replies. This applies on every platform (JVM, Android,
iOS and macOS) and to the emulator bridge daemon. There is no API change:
`devices`, `changes`, `search()` and the factories behave as before.

Consumer obligations are unchanged. A sandboxed macOS app already needs
`com.apple.security.network.server` to bind, and iOS still needs the multicast
entitlement to join the group.

`main` also carries an unreleased breaking change (`description()` returning
KotlinResult's `Result`), so the next release's version is decided by all the
pending changesets together, not by this patch.
