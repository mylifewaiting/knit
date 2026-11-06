# Wi-Fi hop — which decision governs which symbol

The Wi-Fi Direct mesh plane for phones that have no Wi-Fi Aware (ADR 2026-11.tz2v). It does not import
`android.net.wifi.aware.*` or `android.net.wifi.p2p.*`. The p2p calls stay in `transfer/AndroidDirectWifi`,
behind `DirectWifiLease` with owner `wifihop`.

## Entries

- **When touching `WifiHopTransport`, `HopLinks`, `HopRolePolicy`, `HopCredentials`, `HopMode`,
  `WifiHopSightings`, `DirectWifiLease.HOP` / `yields`, or `SettingsStore.wifiHopMode`:** READ ADR 2026-11.tz2v
  and `docs/WIFI_HOP.md`. One group at a time. Star elects the lowest id. Relay alternates once a minute of
  wall clock. The socket is kept only after a link proof. `pause()` on this plane does nothing: the Aware
  hand-over that `host()` triggers is already on this stack. A transfer or a call yields the group instead
  of failing busy. Tests: `HopRolePolicyTest`, `HopCredentialsTest`, `WifiHopSightingsTest`,
  `DirectWifiLeaseTest`.
- **When touching `BleAdvertPayload.FLAG_WIFI_HOP`:** READ ADR 2026-11.tz2v. Bit 3 of the flags byte, set
  only while the mode is on. Older parsers ignore it. Test: `BleAdvertPayloadTest`.
