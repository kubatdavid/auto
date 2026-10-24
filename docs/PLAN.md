# auto — car diagnostics & drive app: plan

Goal: diagnose problems with the car (Audi A4 B7 2.0 ALT, ELM327 Bluetooth) and with
my driving style, from data collected on every drive. A Pixel 9 running GrapheneOS,
mounted in portrait, logs the drive, shows a live dashboard with a map, stores everything
locally and syncs to my server when online. The server keeps the history and does
trend analysis.

Builds on the prototype in `../obd_logger.py` + `../dashboard.html` (proven against
the car: PIDs, fuel estimate, health checks, driving tips, mechanic report).

---

## 1. Decisions

| # | Decision | Recommendation | Status |
|---|---|---|---|
| D1 | Where the phone syncs to | **`auto.abhishekam.cz`** (public, Let's Encrypt, works over mobile data; serves only `/api/v1/*`, bearer token) **+ `auto.force.local`** (internal-only web dashboard, same backend) | **decided** — `auto.abhishekam.cz` live with Let's Encrypt cert (2026-10-24) |
| D2 | Map / navigation | **Mapy.com map + search inside the app** (MapLibre); turn-by-turn in **Waze** via split-screen button (see §6) | **decided** |
| D3 | Device | **Pixel 9, GrapheneOS, portrait.** GPS + mobile data built in, fast; minSdk 34, target latest | **decided** |
| D4 | Server stack | **PHP 8.5 + Apache vhost + MariaDB (PDO)**, same as the existing `invoice` site — nothing to install, no new service | **decided** |
| D5 | App stack | Kotlin, Jetpack Compose, Room, WorkManager, MapLibre Native; **no Google Play services dependency** (works without sandboxed Play) | **decided** |

Why D1 matters: `*.force.local` is blocked from the internet (`internal_only` guard in
the Caddyfile) and uses Caddy's internal CA, so a phone on mobile data could neither
reach it nor trust its cert without extra work (bundled root CA + WireGuard). The
wildcard `*.abhishekam.cz` already points at the Pi, so `auto.abhishekam.cz` gets a real
certificate with no DNS change.

---

## 2. Architecture

```
 ELM327 ──BT SPP──▶ Pixel 9 (GOS)  ───────────── HTTPS (when online) ──▶ rpi5 (172.17.251.12)
                    ├─ ObdService (foreground)                          Caddy :443
                    │   read-only PID poller                             ├─ auto.abhishekam.cz → /api/v1/* only
                    ├─ GPS (LocationManager)                             └─ auto.force.local   → API + web UI (internal_only)
                    ├─ Room DB (trips, samples, codes)                        │
                    ├─ live analysis (alerts, consumption, eco)               ▼
                    ├─ WorkManager sync (idempotent upload)            Apache :8080 vhost (PHP, /var/www/auto)
                    └─ Compose UI (portrait)                                  │
                                                                              ▼
                                                                       MariaDB `auto`
```

Responsibility split:
- **App**: collect, show live, alert, compute a basic trip summary (works offline).
- **Server**: authoritative analysis per trip + trends across trips. Re-runnable when the
  rules improve (`analysis_version`), so old trips get re-diagnosed.

---

## 3. Android app

### 3.1 Screen layout (portrait phone, car mount)

```
┌──────────────────────────────┐
│ ● live  P0116  13.8V   18:42 │  status bar: link, codes, battery
├──────────────────────────────┤
│   72          2150           │
│  km/h          rpm           │  4 big values, readable at a glance
│   6.4          92            │
│ L/100 km       °C            │
├──────────────────────────────┤
│                              │
│   MAP (MapLibre, in app)     │  live position, track coloured by
│   track · event markers ·    │  consumption, event markers,
│   destination preview        │  "Navigate" → Waze / Organic Maps
│                              │
├──────────────────────────────┤
│ 12.4 km · 0:21 · 7.1 L/100   │  trip strip
│ 0.88 L · eco 86              │
├──────────────────────────────┤
│ ● coolant ● mixture ● codes  │  health chips
├──────────────────────────────┤
│  Drive   Trips   Health  ⚙   │  bottom navigation bar
└──────────────────────────────┘
  Alert banner slides over the top on critical alerts (+ sound/vibration).
  In split-screen (navigation app on top) the app shows only the 4 values + chips.
```

- **Drive**: as above. The map is a weighted slot owned by a `MapPane` composable
  (MapLibre), independent of the gauges; it collapses when the window is short.
- **Trips**: list (date, km, L/100, eco, issues) → trip detail: summary tiles, charts
  (speed/rpm, fuel flow, temperatures), consumption-by-speed bars, findings, tips,
  mechanic report (CS/EN, share as PNG).
- **Health**: car-level view from server trends (fuel trim trend, warm-up time, charging
  voltage, DTC history); falls back to local data offline.
- **Settings**: adapter (pick paired device), server URL + token, fuel price, units,
  auto-start, navigation app (Waze / Organic Maps).

Glanceable while driving: dark theme, ≥ 48 sp for the 4 main values, screen kept on while
recording, no interaction needed during a drive.

### 3.2 Modules / packages

| package | contents |
|---|---|
| `obd` | `ElmConnection` (RFCOMM socket, SPP UUID, channel-2 fallback), `ReadOnlyGuard` (allowlist; service `04` never sent), `Pids` (decoders), `Poller` (fast/slow tiers, `010C1`-style single-reply suffix, reconnect on ignition cycle) |
| `trip` | `TripRecorder` (auto start when ECU answers & rpm > 0, auto end 2 min after ignition off), `FuelModel` (MAF → L/h, overrun cut-off), GPS sampling |
| `analysis` | live: instant/avg consumption, eco score, alerts (overheat, not charging, new DTC); trip summary (port of `analyze()` from the prototype) |
| `data` | Room: `Trip`, `Sample`, `DtcSnapshot`, `SyncState`; DAOs; retention |
| `sync` | Retrofit/OkHttp client, `SyncWorker` (WorkManager, `NetworkType.CONNECTED`), chunked idempotent upload |
| `ui` | Compose screens above, `MapPane` (MapLibre), full/compact layouts by window height |
| `service` | `ObdForegroundService` (`connectedDevice\|location` type, wake lock, notification) |

### 3.3 Logged per sample (~1 Hz)

time, seq, lat, lon, gps_speed, alt, speed, rpm, maf, throttle, coolant, load, stft, ltft,
fuelsys, iat, timing, o2_down, volts, fuel_lph. Plus per trip: DTC/MIL snapshots with
freeze frame at start and every 5 min.

Car-supported PIDs (from `0100`/`0120`): 01 03 04 05 06 07 0C 0D 0E 0F 10 11 12 13 15 1C 20 21 34.

### 3.4 Permissions

`BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION`,
`FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_LOCATION`,
`POST_NOTIFICATIONS`, `INTERNET`, `WAKE_LOCK`.

GrapheneOS notes: location comes from GPS (`LocationManager`, no fused provider needed);
exempt the app from battery optimisation so the foreground service isn't throttled; no
push/FCM needed — sync is pull/push from the app.

---

## 4. Server (rpi5, 172.17.251.12)

What's there (read-only survey): Ubuntu 26.04 on a Pi 5 (4 GB, 17 GB free on SD), Caddy on
:80/:443 (Let's Encrypt for public names, internal CA for `*.force.local`), Apache on
:8080 serving PHP 8.5 sites (`pdo_mysql` loaded), MariaDB, Adminer.

### 4.1 Changes to make (each needs a go-ahead)

1. MariaDB: database `auto`, user `auto`@`localhost` with rights on `auto.*` only.
2. Apache vhost `auto.conf` on :8080 (`ServerName auto.abhishekam.cz`, alias `auto.force.local`),
   `/var/www/auto/{html,log}`, front controller `html/index.php` routing `/api/v1/*`;
   DB credentials in `/var/www/auto/config.php` outside the document root.
4. Caddyfile (backup first as `Caddyfile.bak-auto-YYYYMMDD`, then `caddy validate`, reload).
   **Done 2026-10-24** for `auto.abhishekam.cz` with a placeholder (`/api/v1/*` → 503, rest → 404);
   backup `/etc/caddy/Caddyfile.bak-auto-20261024`. Switch the placeholder to `reverse_proxy` once auto-api runs:
   ```
   auto.abhishekam.cz {
       @api path /api/v1/*
       handle @api { reverse_proxy 127.0.0.1:8080 }
       handle { respond 404 }
   }
   auto.force.local {
       import internal_only
       handle { reverse_proxy 127.0.0.1:8080 }
   }
   ```
4. Internal DNS: `address=/auto.force.local/172.17.251.12` next to the other entries.
5. Import the 4 existing CSV drives from `car/logs/`.

### 4.2 API (v1)

All requests: `Authorization: Bearer <device token>` (token stored hashed). JSON, gzip
accepted. Uploads are idempotent: client trip UUID + sample `seq`, server upserts.

| method | path | purpose |
|---|---|---|
| PUT | `/api/v1/trips/{uuid}` | create/update trip metadata |
| POST | `/api/v1/trips/{uuid}/samples` | batch of samples (≤ 2000 per request) |
| POST | `/api/v1/trips/{uuid}/dtc` | DTC/MIL/freeze-frame snapshot |
| POST | `/api/v1/trips/{uuid}/finish` | trip complete → run analysis |
| GET | `/api/v1/trips?since=` | trip list with summaries |
| GET | `/api/v1/trips/{uuid}/report` | findings, tips, stats, bands |
| GET | `/api/v1/health` | car-level trends + current issues |
| GET | `/api/v1/ping` | connectivity/auth check |

### 4.3 Schema (MariaDB `auto`)

```
vehicle(id, name, vin, engine, fuel, created_at)
device(id, vehicle_id, name, token_hash, last_seen)
trip(uuid PK, vehicle_id, device_id, started_at, ended_at, distance_km, fuel_l,
     summary JSON, analysis_version, synced_at)
sample(trip_uuid, seq, t, lat, lon, gps_speed, alt, speed, rpm, maf, throttle, coolant,
       load, stft, ltft, fuelsys, iat, timing, o2_down, volts, fuel_lph,
       PRIMARY KEY (trip_uuid, seq))
dtc_snapshot(id, trip_uuid, t, mil, stored JSON, pending JSON, freeze JSON)
finding(trip_uuid, rule, severity, value, title, detail)   -- one row per rule hit → trends
```

Size: ~0.2 MB per driving hour. Years of driving fit easily.

### 4.4 Diagnostics the server adds (trends across trips)

| area | signal | flags |
|---|---|---|
| Fuel mixture | LTFT and STFT+LTFT split by idle / low / mid / high load | drift toward ±10 %, idle-only lean (vacuum leak) vs all-load lean (MAF / fuel pressure). **Now: LTFT ≈ +15 % at all loads.** |
| Cooling | time to 80 °C, max/steady temperature | thermostat regression, overheating |
| Charging/battery | running voltage, rest voltage, voltage before start | alternator, battery aging |
| Catalyst | rear O2 stability in steady closed-loop cruise | early warning before P0420 |
| Airflow | peak MAF at full throttle vs rpm | dirty MAF / air filter |
| DTC | history, pending→stored, MIL | new or recurring codes |
| Consumption | L/100 km per speed band vs own history | car got thirstier at the same driving |
| Driving style | eco score, harsh events /100 km, idle %, high-rev %, fuel-cut % | trend + tips |

---

## 5. Build & delivery (GitHub)

- `.github/workflows/android.yml`: on push/PR → `./gradlew lint testDebugUnitTest assembleDebug`
  (upload debug APK artifact); on tag `v*` → `assembleRelease`, signed with keystore from
  secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`), APK
  attached to a GitHub Release.
- Install/update on the phone via **Obtainium** watching GitHub Releases (needs a GitHub
  token if the repo stays private).
- Server code lives in `server/` in the same repo; deployed by rsync to `/var/www/auto`
  (script in `server/deploy.sh`).

Repo layout:
```
app/            Android (Gradle, Kotlin, Compose)
server/         PHP API (html/index.php, src/), schema.sql, apache/auto.conf, deploy.sh, import_csv.php
docs/PLAN.md
.github/workflows/android.yml
```

---

## 6. Map & navigation (D2)

- **In-app map:** MapLibre Native rendering **Mapy.com** raster tiles (`basic`, retina). Live
  position, driven track, destination marker. The Mapy API key is served by the API
  (`GET /api/v1/config`) after token auth, so it never sits in the public repo.
- **Destination search:** Mapy.com `suggest` API (biased to the current position).
- **Turn-by-turn:** "Navigate (Waze)" opens `https://waze.com/ul?ll=…&navigate=yes` with
  `FLAG_ACTIVITY_LAUNCH_ADJACENT`; with the app in split-screen, Waze lands in the other half
  (Waze needs sandboxed Google Play on GrapheneOS). The app switches to a compact layout
  (values + chips) when the window is short.
- Later options: Mapy.com traffic-aware route preview/ETA in the app; Ferrostar for in-app
  guidance if split-screen gets annoying.

---

## 7. Phases

| phase | deliverable | done when |
|---|---|---|
| 0 | decisions D1–D5 | **done** |
| 1 | server: DB, API skeleton (`ping`, trip upload), Caddy sites, CSV import | `curl https://auto.abhishekam.cz/api/v1/ping` with token → 200; old drives in DB |
| 2 | app MVP: adapter connection, read-only poller, Drive screen (portrait, map), Room logging, GPS, auto trip start/stop, GitHub CI APK | a real drive recorded on the phone |
| 3 | sync + server analysis + Trips/Health screens + internal web dashboard | drive shows up on the server with findings; trends visible |
| 4 | map: consumption-coloured track, event markers, trip replay, route preview; nav-app split-screen + compact layout | map in the app; navigation + gauges on one screen |
| 5 | polish: alert sound/TTS, auto-start on adapter connect, retention, mechanic report share | daily-drivable |
