# Mylo Shield: architecture

Mylo Shield is a VPN client built into Mylo. It uses Android's `VpnService` and the official WireGuard
implementation (`com.wireguard.android:tunnel`, wireguard-go, Apache-2.0). Mylo adds no protocol and no
cryptography of its own.

**Status:** the client is complete, but no gateway is configured, so the app shows *VPN unavailable ·
Server setup required*. The first real gateway is set up with [`shield-gateway/`](../../shield-gateway/README.md).

## Components (`app/src/main/java/com/mylo/browser/shield/`)

| Part | Role |
|---|---|
| `ShieldModels.kt` | Server, session (`TunnelSpec`), state and problem types. |
| `ShieldContract.kt` | Parses and validates the service's JSON ([BACKEND_API.md](BACKEND_API.md)). |
| `ShieldEngine.kt` | Connection state machine (pure Kotlin, unit-tested): connect, handshake, exit check, reconnect, renewal, switching, revoke. Also the fastest-server choice. |
| `HttpShieldService.kt` | HTTPS client for the service; the address comes from the build environment or a debug test gateway. |
| `WireGuardDriver.kt` | Builds the WireGuard config from a validated session and runs it with `GoBackend`; reads handshake times from WireGuard's statistics. Also has the per-session key generator and the TCP latency meter. |
| `MyloVpnService.kt` | The app's `VpnService` (via `GoBackend.VpnService`, declared with `BIND_VPN_SERVICE`). Foreground notification while the tunnel carries traffic, `onRevoke`, underlying-network watching, Always-on starts. |
| `MyloShield.kt` | Process-wide runtime: preferences, the service hold, unattended-connect rules, the notification. |
| `ShieldScreen.kt` | The Shield UI, disclosure, location list and kill-switch guidance. |

## Connect flow

1. The user taps Connect. The Mylo disclosure is shown once (consent is versioned), then
   `VpnService.prepare()` (Android's own permission dialog).
2. `MyloVpnService` starts; the engine gets the server list and, for *Fastest*, measures TCP connect time
   to each gateway's probe port **outside the tunnel**.
3. A fresh WireGuard key pair is generated on the device; only the public key goes to `POST /v1/sessions`.
4. The validated session becomes a WireGuard config. `GoBackend` establishes the VPN interface through
   `MyloVpnService` (routes `0.0.0.0/0` (+ `::/0`), the session's DNS, MTU) and starts wireguard-go.
5. **Connecting → Connected only after the gateway completes a WireGuard handshake** (from WireGuard's
   own statistics). Then `GET /v1/connection-check` through the tunnel verifies the exit: *verified*,
   *not verified* (shown as such), or a mismatch, in which case Mylo disconnects with an error.

## Staying honest after connecting

- **Network changes:** a callback on non-VPN networks reports loss and return. With no network the state
  is *Reconnecting*; after a network returns, a new handshake or a verified exit check is required
  before *Connected* is shown again. WireGuard roams to the new network itself.
- **Stale handshake** (none for more than 195 s, beyond WireGuard's 180 s session limit):
  *Reconnecting*. After 90 s the engine starts over with a new session on the same gateway; if that
  fails, the state becomes *Error*.
- **Session renewal** about a minute before expiry, with new keys; the old session is closed.
- **Switching servers** replaces the tunnel (WireGuard goes down and up again). Mylo keeps
  `MyloVpnService` bound during the switch, because `GoBackend` calls `stopSelf()` when it takes a
  tunnel down.
- **`onRevoke`** (another VPN started, or the user turned Mylo off in Settings) → *Disconnected*,
  tunnel torn down, session closed.
- **Service or process stop:** wireguard-go runs in the app process, so the tunnel ends with it; the
  state returns to *Disconnected*. Android's Always-on VPN restarts the service, and Mylo reconnects to
  the remembered choice only if the disclosure was accepted, a service is configured, and the VPN
  permission is held.

## Leak protection

- All IPv4 routes go into the tunnel. IPv6 is either routed into the tunnel (when the session assigns an
  IPv6 address and `::/0`) or **blocked** while connected. With exactly one peer and a default route,
  `GoBackend` does not allow address families to bypass the VPN.
- DNS servers come from the session and are reached through the tunnel.
- **Kill switch:** Android's *Always-on VPN* plus *Block connections without VPN* is the only reliable one
  on Android, and only the user can turn it on. Mylo explains how and shows the current setting.
  Without it, traffic can leave outside the tunnel briefly while servers switch or a session renews.

## Logging

Logcat gets state names, step names, problem names and server IDs. It never gets addresses, keys,
tokens, DNS names, URLs or page contents. GoBackend logs the tunnel name and, if endpoint resolution
fails, the endpoint host (endpoints are numeric, so this does not happen).

## Testing

- JVM: `ShieldContractTest` (validation, URL rules, the reference gateway's real responses) and
  `ShieldEngineTest` (state machine with fake tunnel/service and virtual time).
- Layoutlib: `ShieldPreviewTest` renders the current real state (no gateway).
- Device (`ShieldFlowTest`, CI scope `shield`): the unconfigured screens always; the real-tunnel
  milestone when `MYLO_SHIELD_TEST_URL`/`MYLO_SHIELD_TEST_TOKEN` secrets exist. It checks the public IP
  before connecting, through the tunnel and after disconnecting.
- Gateway: `shield-gateway/test_gateway.py`.

## Before release

- Per-user authorization instead of the development token ([BACKEND_API.md](BACKEND_API.md)).
- A privacy policy that states exactly what the gateways and service retain.
- The Play Console VPN declaration and Data safety form ([PLAY_VPN_DECLARATION.md](PLAY_VPN_DECLARATION.md)).
- Optionally, certificate pinning for the service and per-ABI APK splits (the WireGuard native libraries
  add about 3.5 MB per ABI).
