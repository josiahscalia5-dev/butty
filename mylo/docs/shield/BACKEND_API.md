# Mylo Shield service API (v1)

The contract between the Android app (`HttpShieldService`, `ShieldContract`) and any Mylo Shield
backend. `shield-gateway/mylo_shield_gateway.py` implements it for a single gateway.

## Principles

- **No reusable secrets in the app or the repository.** Each device generates a WireGuard key pair per
  session; only the public key is sent. The backend returns short-lived session configuration.
- **HTTPS only.** The app refuses any other scheme. The one exception is debug builds talking to a
  development service on `10.0.2.2` (the emulator's host) or `localhost`. The session response
  decides where the device's traffic goes, so it must not be alterable in transit.
- **Strict validation in the app.** Responses that would weaken or redirect the tunnel are rejected,
  not repaired (see "Session" below).

## Authentication

`Authorization: Bearer <token>` on every request except `GET /v1/connection-check`.

- Testing: a development token (generated on the gateway; only its SHA-256 hash is stored there). It is
  given to debug builds at runtime (*Test gateway* sheet, or instrumentation arguments in CI), never
  compiled into a published APK.
- Production (to build): per-user sign-in issuing short-lived tokens, optionally bound to Play Integrity
  verdicts.

Errors: `401`/`403` → "the service didn't accept this device"; `404`/`410` on sessions → server
unavailable; `409`/`429`/`503` → server unavailable or full; other non-2xx → service unreachable.

## `GET /v1/servers`

```json
{
  "servers": [
    {
      "id": "us-nyc-1",
      "countryCode": "US",
      "country": "United States",
      "city": "New York",
      "hostname": "nyc-1.shield.example.com",
      "endpoint": "203.0.113.10:51820",
      "publicKey": "<gateway WireGuard public key, base64>",
      "protocol": "wireguard",
      "ipv6": false,
      "probe": {"host": "203.0.113.10", "port": 443},
      "load": 12,
      "available": true
    }
  ],
  "ttlSeconds": 300
}
```

- `countryCode`/`country`/`city` must be the gateway's **actual** location; the app displays them as-is.
- `endpoint` must be numeric (`a.b.c.d:port` or `[v6]:port`) so connecting needs no DNS lookup.
- `probe` is a TCP port the app connects to (outside the tunnel) to measure latency from the device.
- `load` (0–100) is optional; omit it rather than invent it.
- Entries with another `protocol` or invalid fields are ignored by the app.

## `POST /v1/sessions`

Request: `{"serverId": "us-nyc-1", "publicKey": "<device public key, base64>"}`

Response `201`:

```json
{
  "sessionId": "opaque",
  "serverId": "us-nyc-1",
  "interface": {"addresses": ["10.64.0.2/32"], "dns": ["10.64.0.1"], "mtu": 1280},
  "peer": {
    "publicKey": "<same as the server's publicKey>",
    "presharedKey": "<optional, base64, new per session>",
    "endpoint": "203.0.113.10:51820",
    "allowedIps": ["0.0.0.0/0"],
    "persistentKeepalive": 25
  },
  "expiresAt": 1893456000,
  "exit": {"ipv4": ["203.0.113.10"], "ipv6": []}
}
```

The app refuses the session unless:

- `serverId`, `peer.endpoint` and `peer.publicKey` match the server it asked for;
- `allowedIps` includes `0.0.0.0/0`, and also `::/0` whenever an IPv6 interface address is assigned;
- `addresses` and `dns` are non-empty, valid IP literals (the DNS resolvers are reached through the tunnel);
- `expiresAt` (epoch seconds or ISO-8601) is in the future;
- `mtu` is within 1280–1500 and `persistentKeepalive` within 1–120 when given.

The app renews a session about a minute before `expiresAt` (with a new key pair) and closes it on disconnect.

## `DELETE /v1/sessions/{sessionId}`

`204` when removed, `404` when unknown. The gateway removes the WireGuard peer.

## `GET /v1/connection-check`

Called **through the tunnel** right after the handshake, and again after network changes.

`{"ip": "203.0.113.10", "viaServerId": "us-nyc-1"}` when the request arrived through that gateway,
otherwise `{"ip": "<address seen>", "viaServerId": null}`.

The app shows "exit verified" only when `viaServerId` is the connected server, or `ip` is one of the
session's `exit` addresses. If the service reports another gateway or an address outside the session's
`exit` list, the app disconnects with an error instead of claiming a protected connection.
