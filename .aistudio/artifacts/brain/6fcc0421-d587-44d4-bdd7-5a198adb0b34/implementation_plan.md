# V2Ray VPN — Exact UI/UX Material 3 Client & Top-15 Auto-Fetcher

A minimal, high-craft Material 3 V2Ray/Xray VPN client engineered to match your uploaded 5-screen **V2Ray VPN** UI/UX reference pixel-for-pixel—featuring the soft sage-mint organic aesthetic, emerald petal connection visualizer, **Fletcher** auto-fetcher with concurrent ping testing for the **Top 15 fastest servers**, custom subscription & clipboard importing, and raw Xray JSON/manual server configuration.

## User Review & Critical Decisions

> [!IMPORTANT]
> **Exact UI/UX Alignment Confirmed**: The plan has been updated to replicate all 5 screens, color tokens, typography, card groupings, and interactive flows shown in your reference image (`/public/assets/aistudio/Screenshot_2026-11-04-22-37-01-86_92460851df6f172a4592fca41cc2d2e6_e6181dc0.jpg`). Approve the plan when you are ready for me to build the application.

- **Exact 5-Screen Architecture**:
  1. **Fetching / Auto-Configure Screen**: Paper-plane squircle logo, *"V2Ray VPN — Fast. Private. Open Internet."*, emerald progress ring, *"Fetching servers... Auto configuring for you"*.
  2. **Server List Screen (`VPN — Auto fetched servers`)**: Top refresh (`↻`) & settings (`⚙`) circular icon buttons, filter pills (`All`, `Recommended`, `Low Ping`, `High Speed`), mint-highlighted top server card with lightning badge (`⚡`), signal bars (green/amber), `ms` ping, and 3-dot actions (`⋮`), displaying the **Top 15 fastest servers**.
  3. **Connected / Tunnel Home Screen**: Organic multi-layered emerald/teal wave aura around the white circular power button (`⏻`), *"Connected"* status + live `00:12:36` timer, active country capsule selector (`[ 🇩🇪 Germany > ]`), 5-row telemetry card (`Protocol`, `Latency`, `Server IP`, `Upload`, `Download`), and soft coral-pink **`■ Disconnect`** pill button.
  4. **Settings Screen**: Grouped white rounded cards for **Configuration** (`Auto Configure`, `Update Interval`, `Custom Subscription`), **Connection** (`Protocol`, `Routing Mode`, `DNS`, `TUN Mode`, `IPv6`), and **App** (`Start on App Open`, `Hide from Recent Apps`, `Clear Cache`).
  5. **Add Server Screen**: 3-way segmented pill tab bar (`Subscription`, `Manual`, `QR Code`), **V2Ray Subscription Link** input with dark forest green **`↓ Add & Fetch`** button, **Import from Clipboard** card, **Subscription Options** (`Auto Update`, `Update Interval`, `Replace Existing`), and **Supported Formats** card (plus Raw Xray JSON & manual fields under the `Manual` tab).

---

## 1. Overview & Core Concept

- **What It Does**: Combines the exact serene sage-and-emerald visual design from your reference screenshot with a real V2Ray/Xray URI & JSON parser, an automated **Fletcher** subscription fetcher and concurrent TCP latency benchmark that curates the **Top 15 fastest servers**, and an Android `VpnService` tunnel engine.
- **Target Audience / Persona**: Users who want a clean, effortless V2Ray/Xray VPN experience that automatically fetches subscription feeds, tests latency across all nodes, and connects to the lowest-ping server with zero clutter.
- **Key Value**: Delivers a one-tap *"Auto configuring for you"* workflow while retaining full power-user control over custom V2Ray/Xray subscription URLs, clipboard links (`vmess://`, `vless://`, `trojan://`, `ss://`, Base64), and raw Xray JSON configs.

---

## 2. User Experience & Visual Design (Matched to Reference Image)

### Visual Identity & Theme Tokens

- **Background Atmosphere**:
  - Soft sage-mint atmospheric gradient (`#F3F9F5` to `#E6F3EC`) with subtle organic translucent pastel sage/mint corner shapes drawn on `Canvas` across all screens.
- **Color Palette**:
  - **Primary Forest Emerald**: `#1F4B3F` / `#235347` (active filter pill, `Add & Fetch` button, active switches, spinner arc, power icon).
  - **Translucent Aura Greens**: Layered organic blobs in `#389B7C`, `#5BB99B`, and `#88D4B8` with soft alpha blending behind the central power button.
  - **Selected Server Highlight**: Soft mint tint `#DFF3E7` for the active/recommended #1 server card.
  - **Cards & Surfaces**: Pure crisp white `#FFFFFF` with `22dp`–`24dp` corner radius, subtle `#E8EFEA` 1dp dividers, and soft ambient elevation.
  - **Disconnect Button**: Soft blush/coral pill surface `#FDE8E8` with crimson stop icon and label `#D33A3C`.
  - **Signal & Latency Indicators**: Emerald green (`#2E9B68`) for low ping (`<90 ms`) and warm amber (`#E5A93C`) for moderate ping (`90–180 ms`).
- **Typography**:
  - **Headings & UI Labels**: `Plus Jakarta Sans` (`SemiBold` / `Bold` for titles like `V2Ray VPN`, `Connected`, `Settings`, `Add Server`, and right-aligned telemetry values; `Regular` / `Medium` for subtitles).
  - **Timers, Ping & JSON Configs**: Tabular numerals for `00:12:36`, `42 ms`, `12.4 MB`, and `JetBrains Mono` inside the Manual Raw Xray JSON editor.

### Screen-by-Screen UX Walkthrough

1. **Fetching / Auto-Configure View**:
   - Triggered on first launch (when `Start on App Open` / `Auto Configure` is active) or during a full Fletcher sweep.
   - Displays the 3D-styled green paper-plane icon inside a white squircle card, **"V2Ray VPN"**, *"Fast. Private. Open Internet."*, smooth emerald circular progress indicator, and live status *"Fetching servers... Auto configuring for you"*.
2. **Connected / Main Dashboard View**:
   - Central organic layered emerald wave visualizer (`Canvas` with gentle breathing rotation/scale animation) surrounding a crisp white circular power button (`⏻`).
   - Displays **"Connected"** (or *"Disconnected"* / *"Connecting..."*), live session duration (`00:12:36`), and the interactive country pill (`🇩🇪 Germany >`) which opens the **Server List View**.
   - **Telemetry Card**: 5-row structured white card displaying `Protocol` (`VLESS (V2Ray)`), `Latency` (`42 ms`), `Server IP` (`auto` or masked endpoint), `Upload` (`12.4 MB`), and `Download` (`98.7 MB`).
   - Bottom **`■ Disconnect`** pill button (or **`⏻ Connect`** when disconnected).
3. **Server List & Top-15 Fletcher Leaderboard (`VPN — Auto fetched servers`)**:
   - Top bar with **"VPN"**, subtitle *"Auto fetched servers"*, **Refresh (`↻`)** button (re-runs the Fletcher subscription fetch + parallel ping test to rank the Top 15 servers), and **Settings (`⚙`)** button (plus a quick `+` or menu action to jump to **Add Server**).
   - Filter pills: **`All`**, **`Recommended`** (Top 15 fastest), **`Low Ping`**, **`High Speed`**.
   - Top selected server rendered in a highlighted mint card (`#DFF3E7`) with a green lightning bolt (`⚡`), followed by a grouped white card listing the ranked servers with country flags, protocol + transport (`V2Ray · Reality`, `V2Ray · VLESS`, `V2Ray · Trojan`, `V2Ray · gRPC`, `V2Ray · WebSocket`, `V2Ray · TCP`), color-coded 3-bar signal strength, latency in `ms`, and a 3-dot menu (`⋮` to Connect, Ping Test, View/Edit Xray JSON, or Delete).
4. **Settings View**:
   - **Configuration**: `Auto Configure` switch, `Update Interval` (`15 min`, `30 min`, `1 hour`, `6 hours`), and `Custom Subscription` row (navigates to **Add Server**).
   - **Connection**: `Protocol` (`Auto (V2Ray)`, `VLESS`, `VMess`, `Trojan`, `Shadowsocks`), `Routing Mode` (`Global`, `Bypass LAN`, `Bypass LAN & Mainland`), `DNS` (`Auto (DoH)`, `Cloudflare 1.1.1.1`, `Google 8.8.8.8`), `TUN Mode` switch, and `IPv6` switch.
   - **App**: `Start on App Open` switch, `Hide from Recent Apps` switch (dynamically applies `ActivityManager.AppTask.setExcludeFromRecents`), and `Clear Cache` action.
5. **Add Server View**:
   - Segmented top tab bar: **`Subscription`** | **`Manual`** | **`QR Code`**.
   - **Subscription Tab** (exact match to screenshot):
     - `V2Ray Subscription Link` card with `https://example.com/sub` input field and dark emerald **`↓ Add & Fetch`** button.
     - `Import from Clipboard` card that parses pasted `vmess://`, `vless://`, `trojan://`, `ss://`, Base64 subscription blobs, or raw Xray JSON.
     - `Subscription Options` card with `Auto Update` switch, `Update Interval` selector (`1 hour >`), and `Replace Existing` switch.
     - `Supported Formats` summary card.
   - **Manual Tab**:
     - Mode toggle between **Structured Server Form** (Name, Country, Protocol, Address, Port, UUID/Password, Transport `Reality/VLESS/gRPC/WS/TCP`, SNI, Public Key) and **Raw Xray JSON Configuration Editor** with live JSON syntax validation.
   - **QR Code Tab**:
     - Paste/decode V2Ray URI payloads or import shareable node configurations.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Fletcher Top-15 Auto-Fetcher & Real TCP Ping Engine**
  - *Chosen Approach*: When the user taps **`↓ Add & Fetch`** or the **`↻`** refresh button, the app downloads the user's subscription URL(s) via OkHttp, decodes Base64 or plain-text URIs (`vmess://`, `vless://`, `trojan://`, `ss://`) and JSON configs, runs concurrent TCP socket connect latency probes across all parsed nodes, and ranks the **Top 15 fastest servers** sorted by lowest `ms` ping.
  - *Why*: Directly fulfills your requirement to auto-fetch servers from user subscription URLs, ping-test them, and list the top 15 fastest nodes while keeping the exact UI/UX from your screenshot.
- **Decision 2: Pre-Loaded Realistic Server Roster + Live Subscription Replacement**
  - *Chosen Approach*: Include the exact server roster seen in your reference design (Germany Reality `42 ms`, Singapore VLESS `55 ms`, United States Trojan `68 ms`, Netherlands VLESS `73 ms`, UK gRPC `81 ms`, Canada WebSocket `96 ms`, France Reality `102 ms`, Japan VLESS `118 ms`, India TCP `135 ms`, Australia WebSocket `148 ms`, up to 15 ranked nodes) as the initial cached state so the UI looks identical out-of-the-box, while **`Add & Fetch`** (with `Replace Existing` toggle) fetches and ping-ranks live servers from any user-entered subscription URL.
  - *Why*: Ensures the app immediately matches your screenshot on launch while supporting real subscription fetching, live TCP latency testing, and custom server/JSON imports.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Architecture & Component Diagram

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                        Jetpack Compose UI (5 Screens)                        │
│ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────┐ ┌──────────┐ │
│ │  1. Splash / │ │  2. VPN List │ │ 3. Connected │ │    4.    │ │  5. Add  │ │
│ │ Auto-Fetch   │ │  (Top 15 &   │ │  Dashboard   │ │ Settings │ │  Server  │ │
│ │ Onboarding   │ │ Filter Pills)│ │ (Wave Canvas)│ │  Screen  │ │  Screen  │ │
│ └──────┬───────┘ └──────┬───────┘ └──────┬───────┘ └────┬─────┘ └────┬─────┘ │
└────────┼────────────────┼────────────────┼──────────────┼────────────┼───────┘
         │                │                │              │            │
         ▼                ▼                ▼              ▼            ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                     VpnViewModel (MVVM / StateFlow)                          │
│  • ConnectionState (Connected/Disconnected, Timer, Live Upload/Download MB)  │
│  • ServerListState (Filter: All / Recommended Top 15 / Low Ping / High Speed)│
│  • FletcherState (Fetching Subscription -> TCP Ping Sweep -> Top 15 Ranking) │
│  • AppSettingsState (Auto Configure, Interval, Protocol, Routing, DNS, TUN)  │
└──────────────┬───────────────────────────┬──────────────────────────┬────────┘
               │                           │                          │
               ▼                           ▼                          ▼
┌────────────────────────────┐ ┌────────────────────────┐ ┌────────────────────┐
│     XrayVpnService         │ │  Fletcher Engine &     │ │  Room Local Store  │
│   (Android VpnService)     │ │  V2Ray/Xray Parser     │ │  (VpnDatabase)     │
│ • System VPN Permission    │ │ • OkHttp Sub Client    │ │ • ServerEntity     │
│ • TUN Session & Notification│ │ • Base64 / URI / JSON  │ │ • SubscriptionDao  │
│ • Live Traffic Counter     │ │ • Concurrent TCP Ping  │ │ • SettingsStore    │
└────────────────────────────┘ └────────────────────────┘ └────────────────────┘
```

### Interactive Component & State Mapping

- **Connected Dashboard (`Screen 3`)**:
  - Tapping the central power button (`⏻`) or bottom **`■ Disconnect`** button toggles the Android `VpnService` tunnel (`XrayVpnService`), starting/stopping the `00:12:36` timer and live `Upload` / `Download` MB counters.
  - Tapping the `[ 🇩🇪 Germany > ]` pill opens the **Server List (`Screen 2`)**.
- **Server List (`Screen 2`)**:
  - Tapping **`All`**, **`Recommended`**, **`Low Ping`**, or **`High Speed`** filters the server list reactively (`Recommended` highlights the Top 15 fastest servers from Fletcher; `Low Ping` filters `< 90 ms`; `High Speed` filters Reality/VLESS/gRPC low-latency nodes).
  - Tapping **`↻`** triggers the **Fetching Servers (`Screen 1`)** overlay/screen or inline sweep, re-pinging all subscription servers and re-ranking the Top 15.
  - Tapping **`⚙`** opens **Settings (`Screen 4`)**.
- **Settings (`Screen 4`) & Add Server (`Screen 5`)**:
  - Tapping `Custom Subscription` in Settings opens **Add Server (`Screen 5`)**.
  - Tapping **`↓ Add & Fetch`** fetches the entered V2Ray subscription URL, decodes all nodes, runs parallel TCP latency tests, optionally replaces old servers when `Replace Existing` is enabled, and navigates to the ranked Top 15 server list.
  - Tapping **`Import from Clipboard`** reads the system clipboard, parses single/multi-line V2Ray URIs or raw Xray JSON configs, benchmarks latency, and adds them to the server list.
