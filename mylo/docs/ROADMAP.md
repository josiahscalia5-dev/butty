# Mylo roadmap

Shipped focus right now: **Mylo Shield**, a real WireGuard VPN client
([docs/shield](shield/README.md)). Its first milestone is one real test gateway; more locations come
only after that works.

The ideas below are Mylo's planned differentiators. **None of them are built yet**, and none should be
claimed in the app or store listing until they are.

| Feature | What it does | Notes for later |
|---|---|---|
| **Mylo Search Prism** | Runs the same query on several search providers side by side and highlights where results overlap or differ. | Builds on the existing provider URLs; it needs per-provider result extraction and a comparison view, and must respect each provider's terms. |
| **Mylo Trail** | Visually saves a whole research journey (searches, pages, back-tracks) as a revisitable trail. | Builds on history and per-tab navigation; stored on the device. |
| **Mylo Site Ledger** | Per-site view of permissions, cookies, storage and trackers, with a one-tap *Forget this site*. | WebView cookie and storage APIs, plus a tracker list with a clear source. |
| **Mylo Checkout Watch** | Notices when a price or an important term changes between the product page and checkout. | Page-content comparison on the device; it must avoid false alarms and never change pages. |
| **Mylo Capsules** | Temporary, isolated browsing sessions whose tabs, cookies, AI context and Shield location are all destroyed together. | Private Mode (shipped) is one such session; its burn already takes registered extras (`privacy/Burnable`). Several capsules at once need WebView profiles; a Shield location per capsule needs per-capsule routing. |
| **Mylo Claim Lens** | Links each claim in a Mylo AI answer to the exact supporting passage, and to sources that conflict. | Requires the AI layer and source-passage anchoring. |
| **Mylo Privacy Switchboard** | Lets the user choose exactly which page, tabs, history, location or saved context Mylo AI may use. | Must exist before any AI feature reads browsing data. |
