# Mylo Shield: Google Play VPN declaration (placeholders)

Draft material for the Play Console. Fill in the bracketed parts only with facts that the shipped app
**and** the server infrastructure actually support. Re-check Google Play's current VpnService and
foreground-service policies before submitting; they change.

## How Mylo uses VpnService

Mylo is a web browser. Google Play's VpnService policy lists web browsing apps among the categories
allowed to use VpnService when it is needed for their functionality. Mylo Shield is an optional,
user-started feature of the browser:

- It creates a **device-wide** encrypted WireGuard tunnel to a Mylo Shield server the user chooses.
  While connected, traffic from Mylo **and other apps** uses the tunnel.
- The user starts it after an in-app disclosure and Android's VPN permission dialog.
- It does not collect personal or sensitive user data through the tunnel. It does not redirect or
  manipulate other apps' traffic for ads, and it does not inject anything into pages.
- Encryption: WireGuard (Noise protocol framework, Curve25519, ChaCha20-Poly1305), via the official
  WireGuard library. Mylo adds no cryptography of its own.

### Play Console VpnService declaration (draft answers)

- **Core functionality using VpnService:** "An optional, user-started VPN (Mylo Shield) inside the Mylo
  browser that encrypts the device's traffic to a Mylo Shield server chosen by the user."
- **Does the app collect user data through the VPN?** [No, if true: Mylo itself does not inspect or
  record tunnel traffic. State what the servers retain, from the privacy policy.]
- **Video showing the feature:** [Record: Home → Mylo Shield → disclosure → Android VPN dialog → Connected → location list → Disconnect.]

## Prominent disclosure (in app, before Android's dialog)

The shipped text is `SHIELD_DISCLOSURE` in `ShieldScreen.kt`:

1. Mylo Shield uses Android's VPN feature. While it is on, this device's internet traffic, from Mylo
   and from your other apps, goes through an encrypted WireGuard tunnel to the Mylo Shield server you
   choose, and leaves the internet from that server.
2. The Mylo Shield server receives your device's IP address and can see which sites and services your
   traffic goes to, as with any VPN. What the server keeps is set out in the Mylo Shield privacy policy.
3. On your device, Mylo logs only whether Shield is connecting, connected or stopped. It doesn't log your
   browsing, DNS requests or page contents.
4. Mylo Shield doesn't make you anonymous: websites can still recognise you through accounts, cookies
   and your browser.

Raise `ShieldPreferences.CONSENT_VERSION` whenever this text changes materially.

## Foreground service

`MyloVpnService` declares `foregroundServiceType="systemExempted"` with
`FOREGROUND_SERVICE_SYSTEM_EXEMPTED`, which Android lists for VPN apps configured through the system VPN
settings. Before release, confirm with the current Play foreground-service policy that this type (rather
than `specialUse`) is the one to declare for a browser's built-in VPN, and fill in the Play Console FGS
declaration: [justification + video].

## Data safety form (to complete from facts)

| Question | Answer to verify |
|---|---|
| Data collected by the app about browsing through the VPN | [None by the app. Server-side: per the privacy policy] |
| Device or other IDs | [Device WireGuard public key per session; not linked to a person unless accounts are added] |
| IP address | [Seen by the Mylo Shield server while connected; retention per privacy policy] |
| Encrypted in transit | Yes (HTTPS for the service; WireGuard for the tunnel) |
| Data deletion | [Sessions expire after N minutes; describe any server logs] |

## Store listing

Describe Mylo Shield factually: "optional built-in VPN using WireGuard; choose a server location".
Do **not** claim "no-logs", "anonymous", "military-grade", "unhackable", or any country, speed or
protection guarantee unless the infrastructure and an audit support it. List only locations that have
a running gateway.

## Privacy policy (placeholder sections)

- What Mylo Shield servers process (connection IP while connected, session public key, timestamps) and for how long.
- Who operates the servers and in which countries.
- What the app keeps on the device (preferences only; no traffic or DNS logs).
- Contact and data-request process.
