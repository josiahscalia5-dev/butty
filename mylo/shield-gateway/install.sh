#!/usr/bin/env bash
# Sets up ONE Mylo Shield test gateway on a fresh Ubuntu 24.04 (or 22.04) VPS:
#   WireGuard (UDP 51820) · NAT to the VPS's public IPv4 · Unbound DNS inside the tunnel (no query logs)
#   · ufw firewall · Caddy (automatic HTTPS) · the Mylo Shield session API (mylo_shield_gateway.py).
#
# Run as root from this directory, with the gateway's REAL location (where the VPS actually runs):
#   sudo MYLO_HOSTNAME=shield-test.example.com MYLO_SERVER_ID=us-nyc-1 \
#        MYLO_COUNTRY_CODE=US MYLO_COUNTRY="United States" MYLO_CITY="New York" ./install.sh
#
# The WireGuard private key is generated here and never leaves this machine. A development access
# token is generated and printed ONCE; only its SHA-256 hash is stored.
set -euo pipefail

[[ $EUID -eq 0 ]] || { echo "Run as root (sudo)." >&2; exit 1; }
: "${MYLO_HOSTNAME:?Set MYLO_HOSTNAME to the DNS name that points at this VPS}"
: "${MYLO_SERVER_ID:?Set MYLO_SERVER_ID, for example us-nyc-1}"
: "${MYLO_COUNTRY_CODE:?Set MYLO_COUNTRY_CODE (ISO 3166-1 alpha-2) for where this VPS runs}"
: "${MYLO_COUNTRY:?Set MYLO_COUNTRY}"
: "${MYLO_CITY:?Set MYLO_CITY}"
[[ "$MYLO_COUNTRY_CODE" =~ ^[A-Z]{2}$ ]] || { echo "MYLO_COUNTRY_CODE must be two capital letters" >&2; exit 1; }
[[ "$MYLO_SERVER_ID" =~ ^[A-Za-z0-9._-]{1,64}$ ]] || { echo "MYLO_SERVER_ID: letters, digits, . _ - only" >&2; exit 1; }

here="$(cd "$(dirname "$0")" && pwd)"
wg_port="${MYLO_WG_PORT:-51820}"
tunnel_v4="10.64.0.0/16"
gateway_v4="10.64.0.1"
wan="$(ip -4 route show default | awk '{print $5; exit}')"
[[ -n "$wan" ]] || { echo "Could not find the default network interface." >&2; exit 1; }

echo "== Packages"
apt-get update -q
DEBIAN_FRONTEND=noninteractive apt-get install -y -q wireguard-tools unbound python3 iptables ufw curl gnupg \
  debian-keyring debian-archive-keyring apt-transport-https
if ! command -v caddy >/dev/null; then
  curl -1sLf https://dl.cloudsmith.io/public/caddy/stable/gpg.key | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt > /etc/apt/sources.list.d/caddy-stable.list
  apt-get update -q && DEBIAN_FRONTEND=noninteractive apt-get install -y -q caddy
fi

public_ipv4="${MYLO_PUBLIC_IPV4:-$(curl -4fsS https://api.ipify.org)}"
echo "== Public IPv4: $public_ipv4 (interface $wan)"

echo "== IP forwarding"
echo 'net.ipv4.ip_forward=1' > /etc/sysctl.d/99-mylo-shield.conf
sysctl -q --system

echo "== WireGuard"
umask 077
mkdir -p /etc/wireguard
[[ -s /etc/wireguard/server_private.key ]] || wg genkey > /etc/wireguard/server_private.key
wg pubkey < /etc/wireguard/server_private.key > /etc/wireguard/server_public.key
chmod 644 /etc/wireguard/server_public.key
cat > /etc/wireguard/wg0.conf <<EOF
# Mylo Shield gateway. Peers are added and removed by the session API, never listed here.
[Interface]
Address = $gateway_v4/16
ListenPort = $wg_port
PrivateKey = $(cat /etc/wireguard/server_private.key)
PostUp = iptables -t nat -A POSTROUTING -s $tunnel_v4 -o $wan -j MASQUERADE
PostDown = iptables -t nat -D POSTROUTING -s $tunnel_v4 -o $wan -j MASQUERADE
EOF
systemctl enable -q --now wg-quick@wg0
systemctl restart wg-quick@wg0
umask 022

echo "== Unbound (DNS inside the tunnel, no query logging)"
cat > /etc/unbound/unbound.conf.d/mylo-shield.conf <<EOF
server:
  interface: $gateway_v4
  ip-freebind: yes
  access-control: 0.0.0.0/0 refuse
  access-control: $tunnel_v4 allow
  hide-identity: yes
  hide-version: yes
  qname-minimisation: yes
  verbosity: 0
  log-queries: no
  log-replies: no
EOF
systemctl enable -q unbound
systemctl restart unbound

echo "== Firewall"
ufw --force reset >/dev/null
ufw default deny incoming
ufw default allow outgoing
ufw default deny routed
ufw allow OpenSSH
ufw allow 80/tcp comment 'Caddy certificate challenge'
ufw allow 443/tcp comment 'Mylo Shield API'
ufw allow "$wg_port"/udp comment 'WireGuard'
ufw allow in on wg0 to "$gateway_v4" port 53 comment 'DNS inside the tunnel'
# Devices may reach the internet, but not each other (routed wg0 -> wg0 stays denied).
ufw route allow in on wg0 out on "$wan"
ufw --force enable

echo "== Session API"
id mylo-shield >/dev/null 2>&1 || useradd --system --home /var/lib/mylo-shield --shell /usr/sbin/nologin mylo-shield
install -d -m 755 /opt/mylo-shield
install -m 755 "$here/mylo_shield_gateway.py" /opt/mylo-shield/mylo_shield_gateway.py
install -d -m 750 -o root -g mylo-shield /etc/mylo-shield
# The API only needs the public key; /etc/wireguard (with the private key) stays root-only.
install -m 640 -o root -g mylo-shield /etc/wireguard/server_public.key /etc/mylo-shield/server_public.key
token="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
printf '%s\n' "$(printf '%s' "$token" | sha256sum | cut -d' ' -f1)" > /etc/mylo-shield/token_hashes
chown root:mylo-shield /etc/mylo-shield/token_hashes
chmod 640 /etc/mylo-shield/token_hashes
cat > /etc/mylo-shield/gateway.env <<EOF
MYLO_SERVER_ID=$MYLO_SERVER_ID
MYLO_COUNTRY_CODE=$MYLO_COUNTRY_CODE
MYLO_COUNTRY=$MYLO_COUNTRY
MYLO_CITY=$MYLO_CITY
MYLO_HOSTNAME=$MYLO_HOSTNAME
MYLO_PUBLIC_IPV4=$public_ipv4
MYLO_WG_INTERFACE=wg0
MYLO_WG_PORT=$wg_port
MYLO_WG_PUBLIC_KEY_FILE=/etc/mylo-shield/server_public.key
MYLO_TUNNEL_V4=$tunnel_v4
MYLO_DNS=$gateway_v4
MYLO_SESSION_MINUTES=60
MYLO_TOKEN_HASHES_FILE=/etc/mylo-shield/token_hashes
MYLO_STATE_FILE=/var/lib/mylo-shield/sessions.json
MYLO_LISTEN=127.0.0.1:8080
EOF
chown root:mylo-shield /etc/mylo-shield/gateway.env
chmod 640 /etc/mylo-shield/gateway.env
install -m 644 "$here/mylo-shield-gateway.service" /etc/systemd/system/mylo-shield-gateway.service
systemctl daemon-reload
systemctl enable -q mylo-shield-gateway
systemctl restart mylo-shield-gateway

echo "== Caddy (HTTPS for $MYLO_HOSTNAME)"
sed "s/{MYLO_HOSTNAME}/$MYLO_HOSTNAME/" "$here/Caddyfile.template" > /etc/caddy/Caddyfile
systemctl enable -q caddy
systemctl reload caddy || systemctl restart caddy

cat <<EOF

Mylo Shield test gateway is set up.

  Service URL  : https://$MYLO_HOSTNAME
  Access token : $token
  WireGuard    : $public_ipv4:$wg_port   public key $(cat /etc/wireguard/server_public.key)
  Location     : $MYLO_CITY, $MYLO_COUNTRY ($MYLO_COUNTRY_CODE)

The access token is shown only now. Keep it like a password: store it in a password manager and in the
GitHub secrets MYLO_SHIELD_TEST_URL / MYLO_SHIELD_TEST_TOKEN. Never commit it.

Check it (the certificate can take a minute after DNS points here):
  curl -fsS -H "Authorization: Bearer <token>" https://$MYLO_HOSTNAME/v1/servers
EOF
