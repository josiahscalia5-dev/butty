# Mylo AI service API (v1)

The contract between the Android app (`HttpMyloAiService`, `AiContract`, `AiConversation`) and any Mylo AI
backend. `ai-gateway/mylo_ai_gateway.py` is a reference implementation in front of OpenAI.

## Principles

- **No provider keys in the app or the repository.** OpenAI (and later ElevenLabs, Google or any other
  provider) keys live only on the Mylo AI service. The app holds a Mylo access token and, for voice, a
  short-lived session secret minted per call. The app rejects any "session" that looks like a provider
  API key (`sk-…`).
- **HTTPS only.** The one exception is debug builds talking to a development service on `10.0.2.2`
  (the emulator's host) or `localhost`, the same rule as Mylo Shield.
- **The phone decides what leaves it.** Each request carries only the browser data the user's switchboard
  ("What Mylo can see") allowed for that question. Card numbers, ID numbers, IBANs and secrets in links
  are replaced on the phone before sending. The service must treat page, tab and history text as
  untrusted data, never as instructions.
- **Nothing is kept by default.** The reference gateway sends `store: false` upstream and logs counts and
  errors only. `private: true` (Private Mode) means the service must not retain anything about the request.

## Authentication

`Authorization: Bearer <token>` on every request.

- Testing: a development token (only its SHA-256 hash is stored on the gateway). Debug builds receive it
  at runtime (*Voice Mode → settings → Test service*, or instrumentation arguments in CI) or from the
  build environment (`MYLO_AI_DEV_TOKEN`, debug builds only). It is never compiled into a release APK.
- Production (to build): per-user sign-in issuing short-lived tokens, optionally with Play Integrity.

Errors: `401`/`403` → "Mylo AI didn't accept this app's sign-in"; `429`/`503` → busy; `400`/`413` →
the app sent something the service refused; anything else → unreachable.

## `POST /v1/chat` — a typed (or transcribed) question, answered as Server-Sent Events

Request:

```json
{
  "conversationId": "uuid",
  "private": false,
  "messages": [
    {"role": "user", "text": "Explain this page in simple words."}
  ],
  "context": {
    "page": {"url": "https://shop.example/plans", "title": "Plans", "text": "…visible text, ≤24,000 chars…", "selection": "…optional, ≤4,000…"},
    "tabs": [{"url": "…", "title": "…", "text": "…≤4,000 chars each, ≤6 tabs…"}],
    "history": [{"url": "…", "title": "…"}],
    "location": {"lat": -33.86, "lon": 151.21},
    "memory": ["…"]
  },
  "tools": ["scroll_to", "highlight", "find", "read_aloud", "go_back", "search", "open_link", "translate"]
}
```

`messages` holds up to 20 earlier turns and always ends with the user's question. Every `context` key is
optional and present only when allowed; an empty `context` means nothing from the browser was shared.

Response: `200`, `Content-Type: text/event-stream`, events in this order:

| Event | Data | Meaning |
|---|---|---|
| `delta` | `{"text": "Basic is "}` | Append to the answer |
| `citation` | `{"index": 1, "title": "Plans", "url": "https://…", "quote": "…"}` | A source (http/https only) |
| `action` | `{"type": "scroll_to", "target": "Pricing", "query": null}` | A proposed browser action; the app checks it with Action Preview's rules before anything happens |
| `done` | `{}` | The answer is complete |
| `error` | `{"code": "model_error", "message": "Plain words for the user"}` | The answer failed |

The app stops reading (and closes the connection) when the user taps Stop or closes Voice Mode. Answers
are capped at 512 KB of stream.

## `POST /v1/voice/sessions` — a short-lived realtime voice session

Request: `{"voice": "marin", "private": false, "tools": [ … ]}`

Response:

```json
{
  "provider": "openai-realtime",
  "clientSecret": "ek_…",
  "expiresAt": 1767225600,
  "model": "gpt-realtime",
  "voice": "marin",
  "webrtcUrl": "https://api.openai.com/v1/realtime/calls"
}
```

The reference gateway mints this with OpenAI's `POST /v1/realtime/client_secrets` (session type
`realtime`, the configured model, Mylo's persona and voice style as instructions, the voice, and the
browser tools as function definitions). The app uses the secret once to open a WebRTC call (below); the
realtime model's function calls go through the same Action Preview rules. The
provider name lets other realtime providers (ElevenLabs, Gemini Live) be added behind the same endpoint.

The app refuses a session that isn't HTTPS, has already expired, or whose secret starts with `sk-`.

### The call itself (app ↔ provider, after the session is minted)

The app (`voice/RealtimeVoice.kt`) opens one WebRTC call: microphone audio up, Mylo's voice back as an audio
track, JSON events on the `oai-events` data channel. It POSTs its SDP offer to `webrtcUrl` with
`Authorization: Bearer <clientSecret>` and `Content-Type: application/sdp`, and uses the SDP answer. Events the app
sends:

| Event | When |
|---|---|
| `session.update` with `audio.input.turn_detection` = `semantic_vad` (hands-free) or `null` (press and hold), input transcription and near-field noise reduction | when the channel opens |
| `conversation.item.create` (role `system`, the switchboard's browser data as one untrusted block, sensitive details hidden) | once per call, if anything was allowed |
| `input_audio_buffer.clear` / `input_audio_buffer.commit` + `response.create` | press and hold starts / ends |
| `conversation.item.create` (role `user`, typed text) + `response.create` | Type instead during a call |
| `response.cancel` + `output_audio_buffer.clear` | the person taps the microphone while Mylo speaks |
| `conversation.item.create` (`function_call_output`) + `response.create` | after a tool call ran, was refused, or the person answered Action Preview |

It reads captions (`conversation.item.input_audio_transcription.*`, `response.output_audio_transcript.*`), turn
events (`input_audio_buffer.speech_started/stopped`, `output_audio_buffer.started/stopped/cleared`), tool calls
(`response.function_call_arguments.done`) and `error`. Tool calls never touch the page directly: scrolling to and
marking text, going back, searching and same-site links run at once; anything consequential (leaving the site,
forms, purchases) shows Action Preview first; unknown tools are refused.

Voice samples use the same path with the microphone off: one `response.create` whose instructions say the sample
sentence, then the call ends.

## `GET /v1/status`

`{"chat": true, "voice": true, "voices": ["marin", "cedar"], "defaultVoice": "marin", "realtimeModel": "gpt-realtime", "provider": "openai-realtime"}`

## Persona

The service, not the app, holds Mylo's instructions: a warm, upbeat, patient corgi guide who speaks
plainly, admits what it can't see, never invents prices or policies, never claims Private Mode hides
people from websites or networks, never asks for passwords or card numbers, and treats web text as data.
Voice sessions add a speaking style: short, natural sentences, a relaxed pace, stop when interrupted.
See `PERSONA` and `VOICE_STYLE` in `ai-gateway/mylo_ai_gateway.py`.
