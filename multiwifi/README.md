# MultiWifi (Android, v0.1)

Connects to several networks at once and spreads your traffic across them.

## How it works

- `NetworkPool` asks Android for extra WiFi networks (by SSID), tracks the WiFi you are already on, and can add mobile data.
- `Socks5Proxy` listens on `127.0.0.1:1080`. Each new connection goes out over the least busy network, and DNS is resolved on that same network.
- `BondService` keeps it all alive as a foreground service.

## Build

Open the folder in Android Studio and run it, or `gradle assembleDebug` with the Android SDK installed.

## Use

1. Enter the extra WiFi name(s) and password(s), tick mobile data if you want it, press Start.
2. Approve the system prompt for each WiFi.
3. Set an app's proxy to SOCKS5 `127.0.0.1:1080` (Firefox, Telegram, many torrent and download apps support this).

## Limits you should know about

- **Dual WiFi needs hardware support.** Many phones can only hold one WiFi connection. On those, joining an extra SSID drops the first one. Phones with STA+STA concurrency (several recent Pixels and Samsung models on Android 12+) can hold two. Mobile data + WiFi works on nearly every phone.
- **Balancing is per connection, not per packet.** A single download stays on one network. Many parallel connections (browsing, multi-connection downloaders) get faster. It does not merge two links into one faster link for a single stream; that would need a server-side bonding endpoint.
- **Not system-wide yet.** Only apps set to use the proxy are affected.

## Next steps

- System-wide mode with `VpnService` plus a tun2socks layer, so no per-app proxy setup is needed.
- Failover, per-link speed weighting, and a bonding server for true single-stream aggregation.
- Store credentials encrypted rather than asking each time.
