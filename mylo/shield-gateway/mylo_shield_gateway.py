#!/usr/bin/env python3
"""Mylo Shield reference gateway: the session API for ONE WireGuard gateway (docs/shield/BACKEND_API.md).

Runs next to WireGuard on the gateway host, behind a TLS reverse proxy (Caddy) on 127.0.0.1. It never
creates client private keys: each device generates its own WireGuard key pair and sends only the public
key. A session adds that public key as a peer with one tunnel address and a fresh preshared key, and is
removed when it expires or the app closes it.

Standard library only. Configuration comes from environment variables (see gateway.env.example).
Logs contain counts and errors only: no client addresses, keys, tokens, DNS names or traffic.
"""
import base64
import hashlib
import hmac
import ipaddress
import json
import os
import secrets
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_BODY = 4096


def env(name, default=None, required=True):
    value = os.environ.get(name, default)
    if required and (value is None or str(value).strip() == ""):
        sys.exit(f"Missing required setting {name}")
    return value.strip() if isinstance(value, str) else value


class Config:
    def __init__(self):
        self.server_id = env("MYLO_SERVER_ID")
        self.country_code = env("MYLO_COUNTRY_CODE").upper()
        self.country = env("MYLO_COUNTRY")
        self.city = env("MYLO_CITY")
        self.hostname = env("MYLO_HOSTNAME")
        self.public_ipv4 = str(ipaddress.IPv4Address(env("MYLO_PUBLIC_IPV4")))
        v6 = env("MYLO_PUBLIC_IPV6", "", required=False)
        self.public_ipv6 = str(ipaddress.IPv6Address(v6)) if v6 else None
        self.wg_interface = env("MYLO_WG_INTERFACE", "wg0")
        self.wg_port = int(env("MYLO_WG_PORT", "51820"))
        self.wg_public_key = read_key(env("MYLO_WG_PUBLIC_KEY_FILE", "/etc/wireguard/server_public.key"))
        self.tunnel_v4 = ipaddress.IPv4Network(env("MYLO_TUNNEL_V4", "10.64.0.0/16"))
        v6net = env("MYLO_TUNNEL_V6", "", required=False)
        self.tunnel_v6 = ipaddress.IPv6Network(v6net) if v6net else None
        self.dns = [str(ipaddress.ip_address(a.strip())) for a in env("MYLO_DNS", "10.64.0.1").split(",") if a.strip()]
        self.session_seconds = int(env("MYLO_SESSION_MINUTES", "60")) * 60
        self.max_sessions = int(env("MYLO_MAX_SESSIONS", "250"))
        self.token_hashes = load_token_hashes(env("MYLO_TOKEN_HASHES_FILE", "/etc/mylo-shield/token_hashes"))
        self.state_file = env("MYLO_STATE_FILE", "/var/lib/mylo-shield/sessions.json")
        self.listen = env("MYLO_LISTEN", "127.0.0.1:8080")
        self.probe_port = int(env("MYLO_PROBE_PORT", "443"))
        self.wg = env("MYLO_WG_BINARY", "wg")
        # The gateway's own tunnel address (first host) is never given to a device.
        self.gateway_v4 = next(self.tunnel_v4.hosts())
        self.gateway_v6 = next(self.tunnel_v6.hosts()) if self.tunnel_v6 else None


def read_key(path):
    with open(path) as handle:
        return valid_key(handle.read().strip())


def valid_key(value):
    try:
        raw = base64.b64decode(value, validate=True)
    except (ValueError, TypeError):
        raise ValueError("not base64")
    if len(raw) != 32:
        raise ValueError("WireGuard keys are 32 bytes")
    return value


def load_token_hashes(path):
    """SHA-256 hex digests of the access tokens, one per line; the tokens themselves are never stored."""
    try:
        with open(path) as handle:
            hashes = {line.strip().lower() for line in handle if line.strip() and not line.startswith("#")}
    except FileNotFoundError:
        sys.exit(f"Missing access-token hash file {path}")
    if not hashes:
        sys.exit("No access tokens configured")
    return hashes


class Sessions:
    """Active sessions, persisted so a restart can remove peers that outlived it."""

    def __init__(self, config):
        self.config = config
        self.lock = threading.Lock()
        self.sessions = {}
        self._load()

    def _load(self):
        try:
            with open(self.config.state_file) as handle:
                self.sessions = json.load(handle)
        except (FileNotFoundError, json.JSONDecodeError):
            self.sessions = {}

    def _save(self):
        directory = os.path.dirname(self.config.state_file)
        os.makedirs(directory, exist_ok=True)
        fd, temporary = tempfile.mkstemp(dir=directory)
        with os.fdopen(fd, "w") as handle:
            json.dump(self.sessions, handle)
        os.replace(temporary, self.config.state_file)

    def _wg(self, *args, stdin=None):
        subprocess.run([self.config.wg, "set", self.config.wg_interface, *args], check=True, input=stdin,
                       stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=10)

    def _free_address(self, network, reserved):
        used = {s["v4"] for s in self.sessions.values()} | {s.get("v6") for s in self.sessions.values()}
        for host in network.hosts():
            if host != reserved and str(host) not in used:
                return host
        return None

    def open(self, device_key):
        with self.lock:
            self._expire_locked()
            if len(self.sessions) >= self.config.max_sessions:
                raise OverflowError("gateway full")
            # One session per device key: a reconnect replaces it.
            for session_id, session in list(self.sessions.items()):
                if session["key"] == device_key:
                    self._remove_locked(session_id)
            v4 = self._free_address(self.config.tunnel_v4, self.config.gateway_v4)
            if v4 is None:
                raise OverflowError("no tunnel addresses left")
            v6 = None
            if self.config.tunnel_v6:
                v6 = self._free_address(self.config.tunnel_v6, self.config.gateway_v6)
            allowed = [f"{v4}/32"] + ([f"{v6}/128"] if v6 else [])
            psk = base64.b64encode(secrets.token_bytes(32)).decode()
            # The preshared key goes to wg on stdin (/dev/stdin), never onto the command line or disk.
            self._wg("peer", device_key, "preshared-key", "/dev/stdin", "allowed-ips", ",".join(allowed), stdin=psk.encode())
            session_id = secrets.token_urlsafe(18)
            expires = int(time.time()) + self.config.session_seconds
            self.sessions[session_id] = {"key": device_key, "v4": str(v4), "v6": str(v6) if v6 else None, "expires": expires}
            self._save()
            return session_id, allowed, psk, expires

    def close(self, session_id):
        with self.lock:
            if session_id not in self.sessions:
                return False
            self._remove_locked(session_id)
            self._save()
            return True

    def expire(self):
        with self.lock:
            if self._expire_locked():
                self._save()

    def _expire_locked(self):
        now = time.time()
        expired = [sid for sid, s in self.sessions.items() if s["expires"] <= now]
        for session_id in expired:
            self._remove_locked(session_id)
        return bool(expired)

    def _remove_locked(self, session_id):
        session = self.sessions.pop(session_id)
        try:
            self._wg("peer", session["key"], "remove")
        except subprocess.CalledProcessError:
            pass  # already gone (for example after a reboot)

    def reconcile(self):
        """At start-up, drop every session: peers added before a restart are removed from WireGuard."""
        with self.lock:
            for session_id in list(self.sessions):
                self._remove_locked(session_id)
            self._save()

    def count(self):
        with self.lock:
            return len(self.sessions)


class Handler(BaseHTTPRequestHandler):
    server_version = "MyloShieldGateway/1"
    sys_version = ""

    def log_message(self, format, *args):  # noqa: A002 - no request logging: it would record client addresses
        pass

    @property
    def gateway(self):
        return self.server

    def client_address_seen(self):
        """The address the request came from. X-Forwarded-For is trusted only from the local TLS proxy."""
        peer = self.client_address[0]
        forwarded = self.headers.get("X-Forwarded-For")
        if forwarded and ipaddress.ip_address(peer).is_loopback:
            peer = forwarded.split(",")[-1].strip()
        return ipaddress.ip_address(peer)

    def authorized(self):
        header = self.headers.get("Authorization", "")
        if not header.startswith("Bearer "):
            return False
        digest = hashlib.sha256(header[len("Bearer "):].strip().encode()).hexdigest()
        return any(hmac.compare_digest(digest, known) for known in self.gateway.config.token_hashes)

    def send_json(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def read_json(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > MAX_BODY:
            raise ValueError("bad body size")
        return json.loads(self.rfile.read(length))

    def do_GET(self):
        config = self.gateway.config
        if self.path == "/v1/connection-check":
            # Requests through the tunnel arrive from a tunnel address: their traffic leaves from this gateway.
            seen = self.client_address_seen()
            in_tunnel = seen in config.tunnel_v4 or (config.tunnel_v6 is not None and seen in config.tunnel_v6)
            if in_tunnel:
                exit_ip = config.public_ipv6 if seen.version == 6 and config.public_ipv6 else config.public_ipv4
                return self.send_json(200, {"ip": exit_ip, "viaServerId": config.server_id})
            return self.send_json(200, {"ip": str(seen), "viaServerId": None})
        if not self.authorized():
            return self.send_json(401, {"error": "unauthorized"})
        if self.path == "/v1/servers":
            return self.send_json(200, {"servers": [self.gateway.server_entry()], "ttlSeconds": 300})
        return self.send_json(404, {"error": "not found"})

    def do_POST(self):
        if not self.authorized():
            return self.send_json(401, {"error": "unauthorized"})
        if self.path != "/v1/sessions":
            return self.send_json(404, {"error": "not found"})
        config = self.gateway.config
        try:
            request = self.read_json()
            if request.get("serverId") != config.server_id:
                return self.send_json(404, {"error": "unknown server"})
            device_key = valid_key(str(request.get("publicKey", "")))
        except (ValueError, json.JSONDecodeError, AttributeError):
            return self.send_json(400, {"error": "bad request"})
        try:
            session_id, allowed, psk, expires = self.gateway.sessions.open(device_key)
        except OverflowError:
            return self.send_json(503, {"error": "gateway full"})
        except (subprocess.SubprocessError, OSError) as error:
            print(f"wg failed: {type(error).__name__}", file=sys.stderr)
            return self.send_json(500, {"error": "gateway error"})
        routes = ["0.0.0.0/0"] + (["::/0"] if len(allowed) > 1 else [])
        self.send_json(201, {
            "sessionId": session_id,
            "serverId": config.server_id,
            "interface": {"addresses": allowed, "dns": config.dns, "mtu": 1280},
            "peer": {
                "publicKey": config.wg_public_key,
                "presharedKey": psk,
                "endpoint": self.gateway.endpoint(),
                "allowedIps": routes,
                "persistentKeepalive": 25,
            },
            "expiresAt": expires,
            "exit": {"ipv4": [config.public_ipv4], "ipv6": [config.public_ipv6] if config.public_ipv6 else []},
        })

    def do_DELETE(self):
        if not self.authorized():
            return self.send_json(401, {"error": "unauthorized"})
        prefix = "/v1/sessions/"
        if not self.path.startswith(prefix):
            return self.send_json(404, {"error": "not found"})
        if not self.gateway.sessions.close(self.path[len(prefix):]):
            return self.send_json(404, {"error": "unknown session"})
        self.send_response(204)
        self.send_header("Content-Length", "0")
        self.end_headers()


class Gateway(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, config):
        host, port = config.listen.rsplit(":", 1)
        super().__init__((host, int(port)), Handler)
        self.config = config
        self.sessions = Sessions(config)

    def endpoint(self):
        return f"{self.config.public_ipv4}:{self.config.wg_port}"

    def server_entry(self):
        config = self.config
        return {
            "id": config.server_id, "countryCode": config.country_code, "country": config.country, "city": config.city,
            "hostname": config.hostname, "endpoint": self.endpoint(), "publicKey": config.wg_public_key,
            "protocol": "wireguard", "ipv6": config.tunnel_v6 is not None,
            "probe": {"host": config.public_ipv4, "port": config.probe_port},
            "load": min(100, round(100 * self.sessions.count() / config.max_sessions)), "available": True,
        }


def main():
    config = Config()
    gateway = Gateway(config)
    gateway.sessions.reconcile()

    def sweeper():
        while True:
            time.sleep(30)
            try:
                gateway.sessions.expire()
            except Exception as error:  # keep sweeping; report the kind of failure only
                print(f"expiry sweep failed: {type(error).__name__}", file=sys.stderr)

    threading.Thread(target=sweeper, daemon=True).start()
    print(f"Mylo Shield gateway {config.server_id} listening on {config.listen}", file=sys.stderr)
    gateway.serve_forever()


if __name__ == "__main__":
    main()
