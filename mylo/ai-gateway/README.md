# Mylo AI reference gateway

The Mylo AI service for testing: it holds the OpenAI API key on a server you control and gives the app
only what it needs (streamed answers, and short-lived voice session secrets). Contract:
[`docs/ai/BACKEND_API.md`](../docs/ai/BACKEND_API.md). Standard library Python 3.10+, no dependencies.

## What you need

| Item | Notes |
|---|---|
| An OpenAI API key | From platform.openai.com, on a project with access to the chat model and `gpt-realtime`. It goes in a file on the server, readable only by the gateway's user. Never in the app, the repository or CI logs. |
| A small server with HTTPS | Any VPS behind a TLS reverse proxy (for example Caddy with a DNS name, as in `shield-gateway/`). The app refuses plain HTTP outside debug builds. |
| A Mylo access token | Any long random string you generate (`openssl rand -hex 32`). The gateway stores only its SHA-256 hash. |

## Run it

```sh
sudo install -d -m 700 /etc/mylo-ai
printf '%s\n' 'sk-…your OpenAI key…' | sudo tee /etc/mylo-ai/openai_api_key > /dev/null
TOKEN=$(openssl rand -hex 32); echo "Mylo access token (shown once): $TOKEN"
printf '%s' "$TOKEN" | sha256sum | cut -d' ' -f1 | sudo tee /etc/mylo-ai/token_hashes > /dev/null
sudo chmod 600 /etc/mylo-ai/*

MYLO_LISTEN=127.0.0.1:8090 python3 mylo_ai_gateway.py     # behind your TLS proxy at https://ai.yourdomain.com
```

Settings (environment variables, all optional except the two files):

| Variable | Default | |
|---|---|---|
| `MYLO_OPENAI_KEY_FILE` | `/etc/mylo-ai/openai_api_key` | The OpenAI key |
| `MYLO_TOKEN_HASHES_FILE` | `/etc/mylo-ai/token_hashes` | SHA-256 of each Mylo access token, one per line |
| `MYLO_AI_CHAT_MODEL` | `gpt-5-mini` | Model for typed answers (Responses API) |
| `MYLO_AI_REALTIME_MODEL` | `gpt-realtime` | Realtime voice model; change it here when OpenAI ships a newer one |
| `MYLO_AI_VOICES` / `MYLO_AI_DEFAULT_VOICE` | `marin,cedar` / `marin` | Voices the app may choose |
| `MYLO_AI_SESSION_SECONDS` | `600` | Lifetime of a voice session secret (10–7200) |
| `MYLO_AI_REQUESTS_PER_MINUTE` | `20` | Per access token |
| `MYLO_OPENAI_BASE_URL` | `https://api.openai.com` | Upstream (tests point it at `fake_openai.py`) |

## Give Mylo the address and token

| Value | Where |
|---|---|
| `https://ai.yourdomain.com` | Build: `-Pmylo.ai.apiBaseUrl=…` or `MYLO_AI_API_BASE_URL`; or on a debug build: Voice Mode → ⚙ → *Test service* |
| The access token | Debug builds only: `MYLO_AI_DEV_TOKEN` at build time, or the same *Test service* sheet |

## Tests

`python3 test_gateway.py` runs the gateway against `fake_openai.py`, a test-only stand-in for OpenAI that
is not an AI (its replies say so). CI's `voice` scope uses the same pair so the Android device test can
exercise app → gateway → upstream → app without an OpenAI account.
