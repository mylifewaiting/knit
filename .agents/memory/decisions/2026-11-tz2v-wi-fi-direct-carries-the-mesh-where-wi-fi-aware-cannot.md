---
id: "2026-11.tz2v"
slug: wi-fi-direct-carries-the-mesh-where-wi-fi-aware-cannot
title: "Wi-Fi Direct carries the mesh where Wi-Fi Aware cannot"
date: 2026-11-03
topics: [wifi-direct, mesh, transport]
---

# ADR 2026-11.tz2v — Wi-Fi Direct carries the mesh where Wi-Fi Aware cannot

Status: Accepted (2026-11-03)

**What was observed.** Wi-Fi Aware is the fast plane, and most phones do not have it. `FEATURE_WIFI_AWARE`
is missing on anything that is not Pixel-class often enough that those phones mesh on Bluetooth alone, and
a photo then crawls. Wi-Fi Direct already runs on those phones: a transfer or a neighbour call raises one
group, pauses Aware (same-app interface requests do not evict each other on Android 12+), and tears the
group down. It is one shot, one client, and it is not a mesh. A soft-AP plus a station looks like the other
portable multi-hop, but joining a hotspot is a `ConnectivityManager` request, and that class has exactly two
importers on purpose. Stock Android also will not put one phone in two Wi-Fi Direct groups at once, so a
chain of groups is not a radio this app can hold.

**What changed.** A third radio, `mesh/wifihop/WifiHopTransport`, sits in front of Bluetooth in
`CompositeMeshTransport` and speaks the same framed sockets as the other planes. It is off until Diagnostics
(or `…debug.WIFIHOP`) sets Star or Relay. Discovery stays on Bluetooth: the presence advert's flags byte
gains bit 3, `FLAG_WIFI_HOP`, set only while the mode is on. Older parsers test the bits they know and ignore
this one. The phones that set the bit elect a role in `HopRolePolicy`. Star: the lowest id that can host
owns one group and everyone else joins it; the owner's `MeshRouter` flood is the hop from client to client.
Relay: each epoch of 60 s of wall clock, a phone links toward its lowest hop peer on even epochs and its
highest on odd ones, so a phone that can see both ends of a line alternates and custody carries the frames
across the gap. Credentials are derived from the anchor id (`HopCredentials`), the same public-bearer idea
as the Aware PMK, and the socket still runs `LinkHandshake` and keeps the link only when the peer proves its
id. The group goes up through `DirectWifiLease` under the owner `wifihop`, which pauses Aware the way a
transfer does and resumes it on release. A transfer or a call that finds the hop holding the radio asks it
to let go and waits, instead of failing `Busy`. The hop's own `pause()` does nothing: `host()` is already
inside the pause that Aware needs, and releasing from there would take the radio mutex the host still holds.

**What it costs.** Enabling the hop lends Wi-Fi Direct for as long as a hop peer is nearby, so Aware data
paths and a transfer both wait on it (the transfer waits through the yield, it does not lose). Star needs
every hop phone in Wi-Fi range of the lowest id; a line of three uses Relay, and the far hop is store-and-forward,
not a live socket. A phone `DirectHostGuard` will not let host cannot be the star anchor. Relay shares a wall
clock; a skew of most of a minute puts the two ends in different epochs. Nothing here is a second Wi-Fi
interface, a `ConnectivityManager` request, or a wire break. The guards are `HopRolePolicyTest`,
`HopCredentialsTest`, `WifiHopSightingsTest`, `DirectWifiLeaseTest.aTransferWaitsForTheHopHolderToLetGo`,
and `BleAdvertPayloadTest.theWifiHopBitIsBit3AndItsOwn`.
