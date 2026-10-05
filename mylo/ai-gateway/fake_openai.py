#!/usr/bin/env python3
"""TEST DOUBLE for the OpenAI endpoints the Mylo AI gateway calls. Never deploy this; it is not an AI.

It lets the gateway's tests and Mylo's device test exercise the real path (app → gateway → upstream →
gateway → app) without an OpenAI account. Replies are scripted and say they are test replies: they report
only what the request carried (the page title, how many tabs, whether any page was shared), which is how
the device test proves the app's switchboard decides what leaves the phone.

  /v1/responses                streams Responses API events for a scripted reply
  /v1/realtime/client_secrets  returns an ephemeral-looking secret ("ek_test_…") that expires
  /v1/realtime/calls           answers a WebRTC offer (needs aiortc): a scripted voice "conversation" over the
                               "oai-events" data channel, with a tone as the voice; every reply says it is a test
  /test/realtime               what the app sent on the event channel (for the device test)
"""
import json
import re
import secrets
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LAST = {}
ISSUED = set()


def scripted_reply(payload):
    developer = next((item["content"] for item in payload.get("input", []) if item.get("role") == "developer"), "")
    last = next((item["content"] for item in reversed(payload.get("input", [])) if item.get("role") == "user"), "")
    images = 0
    if isinstance(last, list):
        images = sum(1 for part in last if part.get("type") == "input_image" and str(part.get("image_url", "")).startswith("data:image/jpeg;base64,"))
        question = " ".join(part.get("text", "") for part in last if part.get("type") == "input_text")
    else:
        question = last
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
    if "APPROXIMATE LOCATION" in developer:
        parts.append("An approximate location was shared.")
    memories = len(re.findall(r"\n- ", developer.split("THINGS THE PERSON ASKED MYLO TO REMEMBER", 1)[1])) if "THINGS THE PERSON ASKED MYLO TO REMEMBER" in developer else 0
    if memories:
        parts.append("%d remembered thing%s shared." % (memories, "" if memories == 1 else "s"))
    if images:
        parts.append("A screenshot of the page arrived.")
    parts.append("You asked: “%s”" % question.strip()[:120])
    return " ".join(parts)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, format, *args):  # noqa: A002
        sys.stderr.write("[fake-openai] %s\n" % (format % args))

    def do_GET(self):
        if self.path == "/test/realtime":
            return self.json(200, {"events": list(REALTIME_LOG)})
        return self.json(404, {"error": {"message": "not found"}})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        if self.path == "/v1/realtime/calls":
            offer = self.rfile.read(length).decode()
            token = self.headers.get("Authorization", "")[len("Bearer "):]
            if token not in ISSUED:
                return self.json(401, {"error": {"message": "unknown session secret"}})
            try:
                answer = realtime_answer(offer)
            except Exception as error:  # noqa: BLE001 - reported to the test log
                REALTIME_LOG.append("error: %s" % error)
                return self.json(500, {"error": {"message": str(error)}})
            data = answer.encode()
            self.send_response(201)
            self.send_header("Content-Type", "application/sdp")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        payload = json.loads(self.rfile.read(length) or b"{}")
        LAST["path"], LAST["payload"], LAST["authorization"] = self.path, payload, self.headers.get("Authorization")
        if self.headers.get("Authorization") != "Bearer " + self.server.key:
            return self.json(401, {"error": {"message": "bad key"}})
        if self.path == "/v1/responses":
            return self.stream(scripted_reply(payload))
        if self.path == "/v1/realtime/client_secrets":
            seconds = payload.get("expires_after", {}).get("seconds", 600)
            secret = "ek_test_" + secrets.token_urlsafe(12)
            ISSUED.add(secret)
            return self.json(200, {"value": secret, "expires_at": int(time.time()) + seconds, "session": payload.get("session", {})})
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


# Realtime voice (test double) ----------------------------------------------------------------------------

REALTIME_LOG = []
_LOOP = None
_PEERS = []


def _loop():
    global _LOOP
    if _LOOP is None:
        import asyncio
        _LOOP = asyncio.new_event_loop()
        threading.Thread(target=_LOOP.run_forever, daemon=True).start()
    return _LOOP


def realtime_answer(offer):
    import asyncio
    return asyncio.run_coroutine_threadsafe(_answer(offer), _loop()).result(20)


async def _answer(offer):
    import asyncio
    import fractions
    from aiortc import MediaStreamTrack, RTCPeerConnection, RTCSessionDescription
    from aiortc.contrib.media import MediaBlackhole
    import av

    class Voice(MediaStreamTrack):
        """Mylo's 'voice' in the test: a soft 440 Hz tone while speaking, silence otherwise."""
        kind = "audio"

        def __init__(self):
            super().__init__()
            self.speaking = False
            self.pts = 0
            self.start = None

        async def recv(self):
            import math
            rate, samples = 48000, 960
            if self.start is None:
                self.start = time.time()
            wait = self.start + self.pts / rate - time.time()
            if wait > 0:
                await asyncio.sleep(wait)
            pcm = bytearray()
            for i in range(samples):
                value = int(6000 * math.sin(2 * math.pi * 440 * (self.pts + i) / rate)) if self.speaking else 0
                pcm += int(value).to_bytes(2, "little", signed=True)
            frame = av.AudioFrame(format="s16", layout="mono", samples=samples)
            frame.planes[0].update(bytes(pcm))
            frame.sample_rate = rate
            frame.pts = self.pts
            frame.time_base = fractions.Fraction(1, rate)
            self.pts += samples
            return frame

    pc = RTCPeerConnection()
    _PEERS.append(pc)
    voice = Voice()
    pc.addTrack(voice)
    sink = MediaBlackhole()
    state = {"title": None, "typed": None, "speaking": None, "calls": 0}

    @pc.on("track")
    def on_track(track):
        sink.addTrack(track)
        asyncio.ensure_future(sink.start())

    @pc.on("datachannel")
    def on_channel(channel):
        def send(event):
            channel.send(json.dumps(event))

        async def speak(text, tool=None, slow=0.12):
            """A scripted spoken answer: captions word by word with the tone playing, then maybe a tool call."""
            state["calls"] += 1
            response = "resp_test_%d" % state["calls"]
            send({"type": "response.created", "response": {"id": response}})
            send({"type": "output_audio_buffer.started", "response_id": response})
            voice.speaking = True
            state["speaking"] = response
            words = text.split(" ")
            for index, word in enumerate(words):
                if state["speaking"] != response:
                    return  # interrupted
                send({"type": "response.output_audio_transcript.delta", "response_id": response, "delta": word + ("" if index == len(words) - 1 else " ")})
                await asyncio.sleep(slow)
            send({"type": "response.output_audio_transcript.done", "response_id": response, "transcript": text})
            if tool:
                send({"type": "response.function_call_arguments.done", "response_id": response, "call_id": "call_test_%d" % state["calls"],
                      "name": tool[0], "arguments": json.dumps(tool[1])})
            send({"type": "response.done", "response": {"id": response, "status": "completed"}})
            voice.speaking = False
            state["speaking"] = None
            send({"type": "output_audio_buffer.stopped", "response_id": response})

        async def person_speaks(words):
            send({"type": "input_audio_buffer.speech_started", "audio_start_ms": 0})
            await asyncio.sleep(0.6)
            send({"type": "input_audio_buffer.speech_stopped", "audio_end_ms": 600})
            send({"type": "conversation.item.input_audio_transcription.completed", "item_id": "item_test", "transcript": words})

        async def handle(event):
            kind = event.get("type")
            item = event.get("item") or {}
            if kind == "session.update":
                send({"type": "session.updated", "session": event.get("session", {})})
                detection = (((event.get("session") or {}).get("audio") or {}).get("input") or {}).get("turn_detection")
                REALTIME_LOG.append("session.update hands-free=%s" % bool(detection))
                if detection:
                    await asyncio.sleep(2.0)  # the "person" asks once the page context has arrived
                    await person_speaks("(test speech) Where is the pricing?")
                    await speak("(test voice, not an AI) Let me find the pricing on %s for you." % (state["title"] or "this page"),
                                tool=("scroll_to", {"target": "Pricing"}))
            elif kind == "conversation.item.create" and item.get("type") == "message" and item.get("role") == "system":
                text = "".join(part.get("text", "") for part in item.get("content", []))
                match = re.search(r"CURRENT PAGE\nTitle: (.*)", text)
                state["title"] = "\u201c%s\u201d" % match.group(1).strip() if match else None
                hidden = len(re.findall(r"\[(?:card number|ID number|bank account|link with secret removed)\]", text))
                REALTIME_LOG.append("context title=%s hidden=%d" % (match.group(1).strip() if match else "-", hidden))
            elif kind == "conversation.item.create" and item.get("role") == "user":
                state["typed"] = "".join(part.get("text", "") for part in item.get("content", []))
                REALTIME_LOG.append("typed %s" % state["typed"])
            elif kind == "conversation.item.create" and item.get("type") == "function_call_output":
                output = json.loads(item.get("output") or "{}")
                REALTIME_LOG.append("tool %s ok=%s" % (item.get("call_id"), output.get("ok")))
                state["tool"] = output
            elif kind == "input_audio_buffer.commit":
                REALTIME_LOG.append("commit")
                send({"type": "conversation.item.input_audio_transcription.completed", "item_id": "item_hold", "transcript": "(test speech, held) Tell me about this page"})
            elif kind == "response.create":
                instructions = (event.get("response") or {}).get("instructions")
                if instructions:
                    REALTIME_LOG.append("sample")
                    await speak("(test voice) " + instructions.split(":", 1)[-1].strip(), slow=0.05)
                elif state.get("tool") is not None:
                    output, state["tool"] = state["tool"], None
                    await speak("(test voice) Done. " + ("The pricing is on your screen now." if output.get("ok") else "I couldn't find it on this page."))
                elif state["typed"]:
                    typed, state["typed"] = state["typed"], None
                    await speak("(test voice, not an AI) You typed: %s. Here is a longer answer so there is time to interrupt me, "
                                "one word at a time, slowly, slowly, slowly, until you tap the microphone." % typed, slow=0.35)
                else:
                    await speak("(test voice) Here is what I heard you hold to say.")
            elif kind == "response.cancel":
                REALTIME_LOG.append("cancel")
                if state["speaking"]:
                    state["speaking"] = None
                    voice.speaking = False
                    send({"type": "response.done", "response": {"id": "cancelled", "status": "cancelled"}})
            elif kind == "output_audio_buffer.clear":
                REALTIME_LOG.append("clear")
                send({"type": "output_audio_buffer.cleared"})

        @channel.on("message")
        def on_message(message):
            try:
                event = json.loads(message)
            except ValueError:
                return
            asyncio.ensure_future(handle(event))

        REALTIME_LOG.append("channel %s" % channel.label)
        send({"type": "session.created", "session": {}})

    await pc.setRemoteDescription(RTCSessionDescription(sdp=offer, type="offer"))
    await pc.setLocalDescription(await pc.createAnswer())
    REALTIME_LOG.append("answered")
    return pc.localDescription.sdp


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
