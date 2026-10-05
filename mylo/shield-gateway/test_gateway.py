#!/usr/bin/env python3
"""Tests for the reference gateway against a fake `wg` that records every call (no root or WireGuard needed)."""
import base64
import hashlib
import json
import os
import stat
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mylo_shield_gateway as gw  # noqa: E402

TOKEN = "test-token-not-a-secret"


def key(seed):
    return base64.b64encode(bytes((seed + i) % 256 for i in range(32))).decode()


class GatewayTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.wg_log = os.path.join(self.dir, "wg.log")
        fake_wg = os.path.join(self.dir, "wg")
        with open(fake_wg, "w") as handle:
            handle.write(f"#!/bin/sh\necho \"$@\" >> {self.wg_log}\nif [ \"$6\" = /dev/stdin ]; then cat >> {self.wg_log}; echo >> {self.wg_log}; fi\n")
        os.chmod(fake_wg, stat.S_IRWXU)
        server_key = os.path.join(self.dir, "server_public.key")
        with open(server_key, "w") as handle:
            handle.write(key(1))
        hashes = os.path.join(self.dir, "token_hashes")
        with open(hashes, "w") as handle:
            handle.write(hashlib.sha256(TOKEN.encode()).hexdigest() + "\n")
        os.environ.update({
            "MYLO_SERVER_ID": "xa-test-1", "MYLO_COUNTRY_CODE": "XA", "MYLO_COUNTRY": "Example Country",
            "MYLO_CITY": "Test City", "MYLO_HOSTNAME": "gateway.shield.example", "MYLO_PUBLIC_IPV4": "203.0.113.10",
            "MYLO_WG_PUBLIC_KEY_FILE": server_key, "MYLO_TOKEN_HASHES_FILE": hashes,
            "MYLO_STATE_FILE": os.path.join(self.dir, "state", "sessions.json"), "MYLO_LISTEN": "127.0.0.1:0",
            "MYLO_WG_BINARY": fake_wg, "MYLO_TUNNEL_V4": "10.64.0.0/29", "MYLO_SESSION_MINUTES": "60",
        })
        self.gateway = gw.Gateway(gw.Config())
        self.port = self.gateway.server_address[1]
        threading.Thread(target=self.gateway.serve_forever, daemon=True).start()

    def tearDown(self):
        self.gateway.shutdown()
        self.gateway.server_close()

    def call(self, method, path, body=None, token=TOKEN, headers=None):
        request = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}", method=method,
                                         data=json.dumps(body).encode() if body is not None else None)
        if token:
            request.add_header("Authorization", f"Bearer {token}")
        if body is not None:
            request.add_header("Content-Type", "application/json")
        for name, value in (headers or {}).items():
            request.add_header(name, value)
        try:
            with urllib.request.urlopen(request) as response:
                raw = response.read()
                return response.status, json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            raw = error.read()
            return error.code, json.loads(raw) if raw else None

    def wg_calls(self):
        if not os.path.exists(self.wg_log):
            return ""
        with open(self.wg_log) as handle:
            return handle.read()

    def test_requires_a_valid_token(self):
        self.assertEqual(401, self.call("GET", "/v1/servers", token=None)[0])
        self.assertEqual(401, self.call("GET", "/v1/servers", token="wrong")[0])
        self.assertEqual(401, self.call("POST", "/v1/sessions", {"serverId": "xa-test-1", "publicKey": key(5)}, token="wrong")[0])

    def test_lists_the_one_real_gateway(self):
        status, body = self.call("GET", "/v1/servers")
        self.assertEqual(200, status)
        server = body["servers"][0]
        self.assertEqual("xa-test-1", server["id"])
        self.assertEqual("203.0.113.10:51820", server["endpoint"])
        self.assertEqual(key(1), server["publicKey"])
        self.assertEqual({"host": "203.0.113.10", "port": 443}, server["probe"])
        self.assertEqual(0, server["load"])

    def test_session_adds_the_device_key_with_a_preshared_key_on_stdin(self):
        status, session = self.call("POST", "/v1/sessions", {"serverId": "xa-test-1", "publicKey": key(5)})
        self.assertEqual(201, status)
        self.assertEqual(["10.64.0.2/32"], session["interface"]["addresses"])
        self.assertEqual(["10.64.0.1"], session["interface"]["dns"])
        self.assertEqual(["0.0.0.0/0"], session["peer"]["allowedIps"])
        self.assertEqual("203.0.113.10:51820", session["peer"]["endpoint"])
        self.assertEqual(key(1), session["peer"]["publicKey"])
        self.assertGreater(session["expiresAt"], time.time())
        calls = self.wg_calls()
        self.assertIn(f"set wg0 peer {key(5)} preshared-key /dev/stdin allowed-ips 10.64.0.2/32", calls)
        self.assertIn(session["peer"]["presharedKey"], calls, "the preshared key reached wg through stdin")
        self.assertNotIn(session["peer"]["presharedKey"], calls.splitlines()[0], "…not on the command line")

        status, _ = self.call("DELETE", f"/v1/sessions/{session['sessionId']}")
        self.assertEqual(204, status)
        self.assertIn(f"set wg0 peer {key(5)} remove", self.wg_calls())
        self.assertEqual(404, self.call("DELETE", f"/v1/sessions/{session['sessionId']}")[0])

    def test_rejects_bad_requests(self):
        self.assertEqual(400, self.call("POST", "/v1/sessions", {"serverId": "xa-test-1", "publicKey": "short"})[0])
        self.assertEqual(404, self.call("POST", "/v1/sessions", {"serverId": "elsewhere", "publicKey": key(5)})[0])

    def test_pool_exhaustion_is_reported_not_overcommitted(self):
        # A /29 has 6 hosts; the gateway keeps the first, leaving 5 for devices.
        for seed in range(5):
            self.assertEqual(201, self.call("POST", "/v1/sessions", {"serverId": "xa-test-1", "publicKey": key(10 + seed)})[0])
        self.assertEqual(503, self.call("POST", "/v1/sessions", {"serverId": "xa-test-1", "publicKey": key(20)})[0])

    def test_connection_check_names_the_gateway_only_for_tunnel_traffic(self):
        through_tunnel = self.call("GET", "/v1/connection-check", headers={"X-Forwarded-For": "10.64.0.2"})
        self.assertEqual((200, {"ip": "203.0.113.10", "viaServerId": "xa-test-1"}), through_tunnel)
        direct = self.call("GET", "/v1/connection-check", headers={"X-Forwarded-For": "198.51.100.7"})
        self.assertEqual((200, {"ip": "198.51.100.7", "viaServerId": None}), direct)

    def test_expired_sessions_are_removed(self):
        status, session = self.call("POST", "/v1/sessions", {"serverId": "xa-test-1", "publicKey": key(7)})
        self.assertEqual(201, status)
        self.gateway.sessions.sessions[session["sessionId"]]["expires"] = time.time() - 1
        self.gateway.sessions.expire()
        self.assertEqual(0, self.gateway.sessions.count())
        self.assertIn(f"set wg0 peer {key(7)} remove", self.wg_calls())


if __name__ == "__main__":
    unittest.main()
