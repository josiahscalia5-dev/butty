# claude-ui previews

Renders of the production Home and search composables from `tools/home-preview` (desktop Compose,
393×851 dp unless noted). Real-emulator captures come from the `Mylo debug APK` workflow artifacts.

| File | What it shows |
|---|---|
| `01-home-vs-approved-reference.jpg` | First corrected Home next to the approved reference |
| `02-claude-ui-home-vs-approved-reference.jpg` | Home on `claude-ui` next to the approved reference |
| `03-search-provider-mockup.jpg` | Approved search-provider direction (mockup) |
| `04-home-polish-options-A-B-C.jpg` | Polish options A, B and C |
| `05-home-BC-focused-search-provider-sheet.jpg` | Implemented B+C Home, focused search with keyboard, provider sheet |
| `06-home-status-bar-30dp-vs-44dp.jpg` | Home with a 30 dp and a 44 dp status bar |
| `07-device-settings-provider-google-yahoo.jpg` | Real emulator: Settings → Google / Yahoo, "Facebook" typed in the Home box, real results |
| `08-device-relaunch-and-direct-domain.jpg` | Real emulator: Yahoo kept after relaunch; `facebook.com` opened directly |
| `09-private-mode-reference-vs-device.jpg` | Approved Private Mode reference next to the real emulator (Pixel 6, API 35) |
| `10-private-session-device-1.jpg` | Real emulator: normal tab, Private Mode, private new tab, separate cookies and 4/4 trackers blocked |
| `11-private-session-device-2.jpg` | Real emulator: blocked list, Burn on Exit, fresh storage after the burn, back on Home |
| `12-private-lock-tabs-device.jpg` | Real emulator: Lock tabs on, Android's PIN prompt after a relaunch (secure, so black), unlocked |
| `13-private-entrance-animation-device.jpg` | Real emulator recording: the entrance animation, frame by frame (125 ms apart) |
| `14-voice-mode-reference-vs-device.jpg` | Approved Voice Mode reference next to the real emulator (Pixel 6, API 35) |
| `15-voice-chat-and-switchboard-device.jpg` | Real emulator: Type instead without an AI service (nothing sent, and it says so); What Mylo can see with all seven sources |
| `17-voice-realtime-call-device-1.jpg` | Real emulator: a realtime voice call to a TEST provider (not an AI): microphone on, Mylo speaking with a caption, after the scroll-to-Pricing tool, muted |
| `18-voice-realtime-call-device-2.jpg` | Real emulator: the call's turns in Type instead (with privacy receipts), a typed question answered aloud, the page showing the marked Pricing section after hanging up |
| `16-voice-chat-through-test-service-device.jpg` | Real emulator: typed chat through the reference Mylo AI gateway with a TEST upstream (not an AI): answer, privacy receipt with the card number hidden, Current Page off, Allow once |

`03` and `05` show an earlier search-provider direction ("Search with …" control and per-search sheet). It was
replaced: the provider is now chosen only in Settings, and Home's own search box submits to it.
