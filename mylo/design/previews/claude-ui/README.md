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

`03` and `05` show an earlier search-provider direction ("Search with …" control and per-search sheet). It was
replaced: the provider is now chosen only in Settings, and Home's own search box submits to it.
