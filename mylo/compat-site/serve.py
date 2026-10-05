#!/usr/bin/env python3
"""Mylo's local website-compatibility test site.

Serves www/ on two ports so the device sees two different sites through `adb reverse`:
  http://localhost:8080   the "app" (pages under test)
  http://127.0.0.1:8081   a separate sign-in server (cross-site OAuth pop-up)
A few endpoints act like a real server: session cookies, redirects, an attachment download.
Test-only: never deploy this.
"""
import http.cookies
import http.server
import json
import os
import socketserver
import sys
import threading
import urllib.parse

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "www")
APP = "http://localhost:8080"


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=ROOT, **kwargs)

    def log_message(self, fmt, *args):
        sys.stderr.write("[%s] %s\n" % (self.server.server_address[1], fmt % args))

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def cookies(self):
        jar = http.cookies.SimpleCookie(self.headers.get("Cookie", ""))
        return {key: morsel.value for key, morsel in jar.items()}

    def send_json(self, body, status=200, headers=()):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        for name, value in headers:
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(data)

    def redirect(self, location, headers=()):
        self.send_response(302)
        self.send_header("Location", location)
        self.send_header("Content-Length", "0")
        for name, value in headers:
            self.send_header(name, value)
        self.end_headers()

    def do_GET(self):
        url = urllib.parse.urlsplit(self.path)
        query = dict(urllib.parse.parse_qsl(url.query))
        if url.path == "/api/login":
            # A server session: HttpOnly, so only the browser's cookie store can carry it.
            return self.send_json({"ok": True}, headers=[("Set-Cookie", "mylo_session=tester; Path=/; HttpOnly; SameSite=Lax")])
        if url.path == "/api/logout":
            return self.send_json({"ok": True}, headers=[("Set-Cookie", "mylo_session=; Path=/; Max-Age=0")])
        if url.path == "/api/whoami":
            return self.send_json({"user": "Mylo tester" if self.cookies().get("mylo_session") == "tester" else None})
        if url.path == "/api/items":
            return self.send_json({"items": [{"id": i, "name": "Item %d" % i} for i in range(1, 6)]})
        if url.path == "/redirect":
            target = query.get("to", "/")
            if not target.startswith("/") or target.startswith("//"):
                target = "/"  # same-site paths only
            return self.redirect(target)
        if url.path == "/files/mylo-sample.txt":
            # Downloads carry the page's session, like a real "export" button.
            signed_in = self.cookies().get("mylo_session") == "tester"
            body = ("Mylo download test file\nsession-cookie: %s\n" % ("yes" if signed_in else "no")).encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Disposition", 'attachment; filename="mylo-sample.txt"')
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if url.path == "/oauth/approve":
            # The sign-in server remembers its own session, then returns to the app with a code.
            state = query.get("state", "")
            back = "%s/callback.html?%s" % (APP, urllib.parse.urlencode({"code": "mylo-code-123", "state": state}))
            return self.redirect(back, headers=[("Set-Cookie", "idp_session=signed-in; Path=/; HttpOnly; SameSite=Lax")])
        if url.path == "/oauth/deny":
            state = query.get("state", "")
            return self.redirect("%s/callback.html?%s" % (APP, urllib.parse.urlencode({"error": "access_denied", "state": state})))
        return super().do_GET()

    def do_POST(self):
        url = urllib.parse.urlsplit(self.path)
        length = int(self.headers.get("Content-Length", "0") or 0)
        body = self.rfile.read(min(length, 5 * 1024 * 1024))
        if url.path == "/api/upload":
            return self.send_json({"received": len(body)})
        self.send_json({"error": "not found"}, status=404)


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main():
    servers = [Server(("127.0.0.1", port), Handler) for port in (8080, 8081)]
    for server in servers[1:]:
        threading.Thread(target=server.serve_forever, daemon=True).start()
    print("Mylo compat site on http://localhost:8080 and http://127.0.0.1:8081", flush=True)
    servers[0].serve_forever()


if __name__ == "__main__":
    main()
