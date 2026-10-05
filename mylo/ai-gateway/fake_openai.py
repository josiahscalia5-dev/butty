#!/usr/bin/env python3
"""TEST DOUBLE for the OpenAI endpoints the Mylo AI gateway calls. Never deploy this; it is not an AI.

It lets the gateway's tests and Mylo's device test exercise the real path (app → gateway → upstream →
gateway → app) without an OpenAI account. Replies are scripted and say they are test replies: they report
only what the request carried (the page title, how many tabs, whether any page was shared), which is how
the device test proves the app's switchboard decides what leaves the phone.

  /v1/responses                streams Responses API events for a scripted reply
  /v1/realtime/client_secrets  returns an ephemeral-looking secret ("ek_test_…") that expires
"""
import json
import re
import secrets
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LAST = {}


def scripted_reply(payload):
    developer = next((item["content"] for item in payload.get("input", []) if item.get("role") == "developer"), "")
    question = next((item["content"] for item in reversed(payload.get("input", [])) if item.get("role") == "user"), "")
    title = re.search(r"CURRENT PAGE\nTitle: (.*)", developer)
    tabs = len(re.findall(r"OTHER TAB \d+", developer))
    redacted = len(re.findall(r"\[(?:card number|ID number|bank account|link with secret removed)\]", developer))
    if title:
        seen = "I can see the page “%s”" % title.group(1).strip()
    else:
        seen = "No page was shared with me"
    parts = ["Test reply from the Mylo test upstream (not an AI).", seen + "."]
    if tabs:
        parts.append("%d other tab%s shared." % (tabs, "" if tabs == 1 else "s"))
    if redacted:
        parts.append("%d detail%s arrived hidden." % (redacted, "" if redacted == 1 else "s"))
    parts.append("You asked: “%s”" % question.strip()[:120])
    return " ".join(parts)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, format, *args):  # noqa: A002
        sys.stderr.write("[fake-openai] %s\n" % (format % args))

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        payload = json.loads(self.rfile.read(length) or b"{}")
        LAST["path"], LAST["payload"], LAST["authorization"] = self.path, payload, self.headers.get("Authorization")
        if self.headers.get("Authorization") != "Bearer " + self.server.key:
            return self.json(401, {"error": {"message": "bad key"}})
        if self.path == "/v1/responses":
            return self.stream(scripted_reply(payload))
        if self.path == "/v1/realtime/client_secrets":
            seconds = payload.get("expires_after", {}).get("seconds", 600)
            return self.json(200, {"value": "ek_test_" + secrets.token_urlsafe(12), "expires_at": int(time.time()) + seconds,
                                   "session": payload.get("session", {})})
        return self.json(404, {"error": {"message": "not found"}})

    def json(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def stream(self, reply):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True

        def send(event, body):
            self.wfile.write(("event: %s\ndata: %s\n\n" % (event, json.dumps(dict(body, type=event)))).encode())
            self.wfile.flush()

        send("response.created", {"response": {"id": "resp_test"}})
        words = reply.split(" ")
        for index, word in enumerate(words):
            send("response.output_text.delta", {"delta": word + ("" if index == len(words) - 1 else " ")})
            time.sleep(self.server.delay)
        send("response.completed", {"response": {"id": "resp_test", "status": "completed"}})


def serve(port, key, delay=0.0):
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    server.daemon_threads = True
    server.key = key
    server.delay = delay
    return server


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8091
    key = sys.argv[2] if len(sys.argv) > 2 else "test-upstream-key"
    delay = float(sys.argv[3]) if len(sys.argv) > 3 else 0.05
    print("Mylo TEST upstream (not an AI) on 127.0.0.1:%d" % port, flush=True)
    serve(port, key, delay).serve_forever()
