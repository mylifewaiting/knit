# Wi-Fi hop

A mesh plane that does not use Wi-Fi Aware (ADR 2026-11.tz2v). It is off until someone turns it on.
Bluetooth stays the way phones find each other. Wi-Fi Direct carries the bytes when the hop is on.

## What it does

Phones that advertise the hop bit elect one Wi-Fi Direct group.

- **Star.** The lowest node id that is allowed to host owns the group. The others join it and open a
  socket to the owner. The owner's ordinary flood is the hop from one client to another, so two phones
  that cannot see each other still exchange while both can see the owner.
- **Relay.** Once a minute of wall clock, each phone links toward its lowest hop neighbour (even minute)
  or its highest (odd minute). A phone in the middle of a line alternates. Messages the middle already
  holds go out on the next minute. This is store-and-forward, not a live three-phone socket.

The group name and passphrase are derived from the owner's node id. The socket still requires a link
proof, same as Bluetooth and Wi-Fi Aware. While the group is up, Wi-Fi Aware is paused. A file transfer
or a call between neighbours asks the hop to drop the group and then takes the radio.

## Turn it on

Debug builds, and a release build on a phone with no Wi-Fi Aware: Diagnostics, under Transports,
**Wi-Fi hop**. Or, on a debug build:

```sh
adb shell am broadcast -a app.getknit.knit.debug.WIFIHOP -p app.getknit.knit --es mode star
```

`star`, `relay`, or `off`. Log tag `WifiHop`. The row's linked count is the sockets, not the Bluetooth
neighbours.

## Try it with two or three phones

All of them on the same build, Wi-Fi and Bluetooth on, Knit open, hop set the same way. No LoRa board.

**Two phones, Star.** Stand them a few metres apart. The lower node id (Diagnostics, the id at the top)
hosts. Within about half a minute each row should show 1 linked. Send a message each way, then a photo.
The photo should move far faster than Bluetooth alone. `WifiHop` logs `up host` on one phone and
`up join` on the other.

**Three phones together, Star.** Same room, so every phone is in Wi-Fi range of the lowest id. That
phone hosts. The other two join. A message from one joiner to the other arrives via the host. Each
joiner shows 1 linked. The host shows 2.

**Three phones in a line, Relay.** Put them so the ends can each see the middle phone and not each
other (a doorway often does this; Bluetooth discovery has to match, because the hop only elects phones
Bluetooth can see). Set Relay on all three. Messages between the ends can take about a minute, because
the middle phone holds one Wi-Fi link at a time and forwards on the next slot. Star will not bridge
that line unless the lowest id is the middle phone and both ends can still reach its Wi-Fi.

Turn the hop off when finished. Aware comes back, and transfers stop having to wait for the group to drop.
