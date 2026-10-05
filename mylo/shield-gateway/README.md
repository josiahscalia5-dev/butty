# Mylo Shield test gateway (one real location)

Everything needed to run **one** real Mylo Shield VPN gateway for the first milestone:

> Disconnected → Android VPN permission → Connecting → real WireGuard tunnel → Connected → the
> device's public IP is the gateway's → Disconnect.

Only after that works should more locations be added. This kit sets up WireGuard plus a small session
API on a single VPS. It contains no keys or tokens: the gateway generates its own WireGuard key
pair, and every Android device generates its own key pair per session.

## 1. What you need

| Item | Requirement |
|---|---|
| VPS | Any KVM-based cloud VM (for example DigitalOcean, Vultr, Hetzner, Linode/Akamai, AWS Lightsail). **Not** OpenVZ/LXC containers, because WireGuard needs the kernel module. |
| Location | The city you want as the first location. The app shows the city and country you enter, so they must be where the VPS actually runs (the provider's datacenter). |
| Size | 1 vCPU, 1 GB RAM, 25 GB disk is enough for testing. |
| OS | Ubuntu 24.04 LTS (22.04 also works). |
| Network | A static public IPv4 address. IPv6 is optional and not used by this kit; Mylo then blocks IPv6 while connected rather than leaking it. |
| Access | SSH as root or a sudo user. |
| DNS name | A domain or subdomain you control (for example `shield-test.yourdomain.com`) with an **A record pointing at the VPS IPv4**. Needed for the HTTPS certificate; Mylo refuses plain-HTTP services. A free dynamic-DNS name works for testing. |
| Firewall | Open **UDP 51820** (WireGuard), **TCP 443** and **TCP 80** (HTTPS and its certificate) and TCP 22 (SSH). The installer configures `ufw` on the VPS; also open these in your provider's cloud firewall if it has one. |

## 2. Install

```sh
# on the VPS, from a copy of this folder
sudo MYLO_HOSTNAME=shield-test.yourdomain.com \
     MYLO_SERVER_ID=us-nyc-1 \
     MYLO_COUNTRY_CODE=US MYLO_COUNTRY="United States" MYLO_CITY="New York" \
     ./install.sh
```

The installer:

- installs `wireguard-tools`, `unbound`, `ufw`, Caddy and Python 3;
- generates the gateway's WireGuard key pair in `/etc/wireguard` (the private key never leaves the VPS);
- brings up `wg0` on `10.64.0.1/16`, UDP 51820, with NAT so device traffic leaves from the VPS's
  public IPv4;
- runs Unbound on `10.64.0.1` for DNS inside the tunnel, with query logging off;
- configures the firewall: devices can reach the internet but not each other;
- runs the session API (`mylo_shield_gateway.py`) as an unprivileged user with only `CAP_NET_ADMIN`,
  behind Caddy (automatic Let's Encrypt HTTPS);
- prints the **service URL** and a newly generated **access token**. It shows the token only once
  and stores only its SHA-256 hash.

Check it:

```sh
curl -fsS -H "Authorization: Bearer <token>" https://shield-test.yourdomain.com/v1/servers
sudo wg show wg0
```

## 3. What to give Mylo

Only two values, and neither goes into the repository or the APK:

| Value | Where |
|---|---|
| Service URL, `https://shield-test.yourdomain.com` | GitHub secret `MYLO_SHIELD_TEST_URL`, and on your phone: Mylo → VPN strip → Mylo Shield → *Test gateway (debug build)* |
| Access token | GitHub secret `MYLO_SHIELD_TEST_TOKEN`, and the same *Test gateway* sheet on your phone |

Everything else (endpoint `IP:51820`, the gateway's WireGuard public key, city and country, tunnel
addresses, DNS, allowed IPs) comes from the session API at connect time and is validated by the app.

With the two secrets set, the `Mylo debug APK` workflow's **`shield`** scope runs the whole milestone
on an Android emulator (`ShieldFlowTest#realTunnelMilestone`). It captures screenshots of each step and
checks the public IP before connecting, through the tunnel and after disconnecting.

## 4. What the client is given (per session)

| Setting | Value from this gateway |
|---|---|
| Interface address | one `/32` from `10.64.0.0/16` (the gateway keeps `10.64.0.1`) |
| Private key | generated on the phone for each session, kept in memory only, never sent |
| DNS | `10.64.0.1` (Unbound on the gateway, reached through the tunnel) |
| MTU | 1280 |
| Peer public key | the gateway's `/etc/wireguard/server_public.key` |
| Preshared key | a new random 32-byte key per session (extra symmetric layer) |
| Endpoint | `<VPS public IPv4>:51820`, numeric so no DNS lookup is needed to connect |
| Allowed IPs | `0.0.0.0/0`, so all IPv4 traffic uses the tunnel; IPv6 is blocked while connected because the gateway has none |
| Persistent keepalive | 25 s |
| Expiry | 60 minutes; Mylo renews the session before it ends and closes it on disconnect |

## 5. Authentication

- **Now (testing):** a development bearer token from the installer. Treat it like a password. To rotate
  it, generate a new token, replace its hash in `/etc/mylo-shield/token_hashes`, and restart
  `mylo-shield-gateway`. Several hashes (one per tester) may be listed.
- **Before release:** replace the shared token with per-user authorization: account sign-in (and
  ideally Play Integrity), with the backend issuing short-lived tokens per user. Debug builds' *Test
  gateway* sheet and the build-time token do not exist in release builds.

## 6. Logging

The session API logs nothing about clients (no addresses, keys, tokens or traffic), Caddy has no access
log, and Unbound does not log queries. WireGuard holds each connected device's current public IP in
memory while the session is active (it must, to send replies). Write down what you actually retain
before publishing any privacy statement, and claim nothing beyond it.

## 7. Adding locations later

Repeat this setup per city with its own `MYLO_SERVER_ID` and real location. Then put one service in
front that lists every gateway in `GET /v1/servers` and routes `POST /v1/sessions` to the chosen one;
the app already handles many servers, fastest-server measurement and switching.

`test_gateway.py` exercises the API against a fake `wg` (`python3 -m unittest test_gateway`).
