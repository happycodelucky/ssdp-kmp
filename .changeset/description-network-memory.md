---
title: Remember device descriptions per network
change: minor
description: Descriptions fetched on a network are kept when you leave it and restored when you return, so a device re-found at the same LOCATION needs no refetch.
---

Until now a network change dropped every cached description along with the
device registry, so going home → café → home refetched each device's
description from scratch.

Now, when the network changes, the successful descriptions from the network
you're leaving are parked under that network (identified by transport and IPv4
subnet). When you come back, each device's description is restored as soon as
the device is re-found at the same `LOCATION`. `cachedDescription` returns it
again and `description` serves it without an HTTP request.

- A device that comes back at a different `LOCATION` is fetched fresh.
- A device that says `byebye` or expires loses its parked description.
- Up to 4 networks are remembered, for 24 hours each. The least recently left
  network is dropped first.
- When the subnet can't be determined, nothing is parked, because two LANs
  could look the same.

Nothing changes in the API and there's nothing to do. `description(device,
refresh = true)` still forces a refetch.
