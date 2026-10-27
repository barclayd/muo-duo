# Muo Duo

Push notifications to my iPhone the moment an iPhone Duo is available to collect today at any Apple Store in London, the south of England or Manchester, and an alarm if it's first thing in the morning.

- **`worker/`**: a Cloudflare Worker with one Durable Object that polls two of Apple's pickup endpoints for 20 stores in three areas, one request a second, and sends APNs pushes.
- **`ios/`**: a SwiftUI app with a Liquid Glass design. It shows the checker working live, when an iPhone Duo was last in stock and where, a map of the stores being watched, and which models alert you. Tapping an alert, or Buy in the app, opens the model in the Apple Store app.

## How it works

```
Durable Object alarm (about once a second: 3 areas × 4 colour/capacity queries, each round every ~12s)
  ├─ GET apple.com/uk/shop/pickup-message-recommendations?location=<area>   (one query per alarm, taking turns)
  │    └─ new part@store to collect today?
  │         └─ yes ─▶ GET apple.com/uk/shop/retail/pickup-message (every store in that area, up to 2s)
  │                     └─▶ APNs (time-sensitive, plus an alarm 06:00–08:00) ─▶ iPhone
  └─ once a minute, instead: GET apple.com/uk/shop/retail/pickup-message for the next area   (a sweep)
```

- **Endpoints.** Apple's `fulfillment-messages` endpoint sits behind a bot shield (HTTP 541 without a browser session), so it isn't used. Two others are open:
  - `pickup-message-recommendations` is quick and does most of the checking. For the part asked about, it lists the *other* colours at the same capacity that are in stock near the search; the queried part itself never appears. So the checker queries every colour at each watched capacity and keeps any watched part that comes back. It only ever names one store with stock, however many have it.
  - `retail/pickup-message` answers for every watched part at all 12 stores nearest the search in one go, so it finds every store with stock. It's slower, and Apple is warier of it, so it's asked less often: a sweep.
- **Sweeps.** Once a minute an alarm sweeps one area instead of asking its quick query, taking turns, so each area is swept every 3 minutes. And when a quick check finds new stock, its area is swept before the push goes out, so the alert names every store that has it. That sweep gets 2 seconds; if Apple's slower, the alert goes without it. Stock is the quick checks' latest answers plus each area's last sweep. A sweep stops counting after 5 minutes, and when a quick check finds none of a capacity's other colours anywhere near an area, what the area's last sweep found of them goes straight away. Neither source can cancel stock the other has found, so a disagreement errs towards alerting.
- **Today only.** Only stock Apple quotes as "Available Today" is alerted on or logged. Once the stores close, Apple quotes everything for tomorrow, so in the evening nothing counts until the next day.
- **Alarm hours.** Restocks tend to land first thing. Stock found between 06:00 and 08:00 UK time (`alarm` in the config) also rings an alarm: the push goes through the app's notification service extension, which schedules an [AlarmKit](https://developer.apple.com/documentation/alarmkit) alarm a second later, sounding through silent mode and Focus like the Clock app's. Only one rings at a time. If it can't ring, the notification says why. Apple doesn't document scheduling alarms from a notification service extension, so test it from Settings.
- **Areas.** One search only reaches 12 stores, so the checker runs three:

  | Area | Search | Stores |
  | --- | --- | --- |
  | London | `NW6 5PU` | all 12 in and around London, Watford to Bluewater |
  | South of England | `DT1 1XJ` | Brighton, Basingstoke, Reading, Southampton, Bath, Bristol |
  | Manchester | `Manchester` | Arndale and Trafford Centre |

  Each area lists its store numbers, and answers are filtered to them, so a far-off store Apple adds to an answer is ignored. If Apple leaves a listed store out, the store shows as "not covered" in the app. Milton Keynes and Cambridge are just outside these searches; add another area to cover them.
- **Postcodes, not towns.** Apple's search takes town names too, but not reliably: "Manchester" and "Exeter" work, "Brighton" returns no stores, "Brighton and Hove" is rejected, and "Dorchester" picks Dorchester-on-Thames in Oxfordshire. `PUT /config` test-runs every new search against Apple and refuses one that fails or misses any of its listed stores.
- **One request at a time, alert straight away.** Each alarm asks one query, whichever was asked longest ago, and pushes as soon as the answer shows stock. Requests are spaced at least a second apart (`MIN_SECONDS_BETWEEN_REQUESTS`), so with 12 queries each one comes round every 12s, even with `intervalSeconds` at its 10s minimum. Stock is spotted ~6s after it appears on average.
- **Alerts fire once per part@store.** A sighting is only remembered after a push was delivered, so a failed push retries on the next cycle.
- **Last seen.** Every sighting is kept as a stretch of time a model was in stock at a store. Sightings less than 5 minutes apart join one stretch. The latest 20 are in `/status` as `lastSeen`, alongside `trackingSince`, so the app can say "Not yet" honestly.
- **Stock log.** Each time a watched model comes into stock at a store, or goes again, it's logged with the store, Apple's pickup quote and how long it lasted, alongside the checker's own events: Apple failing and recovering, results looking stale and right again, and pauses. The latest 200 entries are in `/status` as `log`, newest first, with `loggingSince`. A model or store that's no longer watched drops out without being logged as gone.
- **Silence is not "no stock".** After 3 failed checks in a row you get a "Stock checker is failing" push, and a "recovered" push when it comes back. Failures back off exponentially on top of the normal pace, capped at 15 minutes. A response with no stores at all counts as a failure.
- **A canary for empty answers.** Every 10 minutes the checker also asks about iPhone 17 256GB in the first area, where it's reliably in stock. If it shows nothing 3 times running, Apple may be serving empty results, so you get a "Stock results may be stale" push and the app says so.
- **A watchdog.** A cron every 5 minutes restarts the alarm loop if it ever stops.
- **Rate limits.** Apple sends no rate-limit headers and the real ceiling is unknown. Its answers aren't cached (`cdn-cache; desc=MISS`, a new ETag every time), so polling faster really does see changes sooner. Sweeps keep their own pace: if Apple pushes back on one, sweeps stop for 5 minutes, then 10, 20 and 30 if it keeps happening, and the quick checks carry on as they were. If Apple pushes back on a quick check (HTTP 403, 429 or the 541 bot shield), the checker halves its speed. A 503 is usually a passing hiccup, so it only counts when the check before it failed too; a lone failure just delays the next request by a second. After every 2 minutes without pushback the speed doubles again, back up to the configured speed. Pushback that keeps coming keeps halving it, down to checking each query every 15 minutes. A single slow-down sorts itself out without a push; a second in a row pushes "Checking less often", and then "Back to full speed" once it recovers.
- **Cloudflare cost.** This needs the Workers Paid plan. Every alarm is a Durable Object request and writes a few storage rows. At one alarm a second that's about 2.6M requests a month, 1.6M over the 1M included, so roughly $0.24 a month, and around 8M row writes, inside the 50M included. Duration can't exceed what's included: one object in memory all month is about 330,000 GB-s of the 400,000.

## The app

The Worker does all the checking; the app only watches it, polling `/status` every 2 seconds while it's on screen.

- **Live.** The Worker's last 30 requests to Apple are in `/status`. The app replays new ones at the pace they happened, so every check pulses: rings spread out from behind the phone, the toolbar badge ripples, the area's pins ripple on the map, and the ticker along the bottom names the area and model just checked, or "Every store" for a sweep. Tapping the ticker zooms it into a full-screen live view: each area and when it was last asked, Apple's average response time, and the latest requests to Apple as they happen.
- **Stock log.** The home screen shows the latest of the Worker's log, grouped by day: where each model came into stock, at which store, and when it went again, so a night's stock can be read back in the morning. It updates as the app polls, and Show All opens the whole log a day at a time.
- **In stock.** When anything is in stock, a green card appears straight under the colour picker: the capacity and colour, each store that has it and how long it's been there, and a Buy button. Apple's product pages are universal links for the Apple Store app, so Buy opens the model there, already chosen; pick the store for pickup at checkout, since no link can choose it for you. Safari is the fallback if the app isn't installed.
- **Product images.** The hero shows Apple's own product shot for the selected colour, and the whole app takes that colour's look: Night Sky is the dusk desert from its wallpaper, with stars, Star White the dawn one. The images load from Apple's CDN at runtime and are cached on the device; they aren't in this repo.
- **Liquid Glass.** Cards, colour and capacity pickers, the ticker, the toolbar and the full-screen store map's chips and store panel are all glass over a slowly drifting mesh-gradient sky.
- **Status badge.** The badge in the top left says LIVE only while the app can reach the Worker and the Worker is checking Apple. Otherwise it inverts into a solid pill saying what's wrong: OFFLINE (the app can't reach the Worker), PAUSED, FAILING (Apple isn't answering), STOPPED (the Worker's next check is over 2 minutes overdue) or STALE. A slow-down still counts as live, since checks are getting through.
- **Warnings.** If the checker can't reach Apple or the results look stale, a banner says so at the top, since alerts may not arrive. A slow-down after Apple pushes back is less urgent, so it sits at the bottom.
- **Settings** is its own page, pushed from the gear, in the same glass over the same sky. Alerts come first: every size of both colours, showing which ones you'll hear about. Turning one on is a tap, but turning one off asks first, so a stray tap can't quietly stop alerts for a size. Pausing checks asks first too. Then the alarm hours: whether stock first thing rings an alarm, whether alarms are allowed, and a test alarm sent 10 seconds later, so there's time to lock the phone. Below that are the current pace and last good check, notification registration and a test push, and each area, which opens to show its stores.

## Setup

### 1. APNs key

In [Certificates, Identifiers & Profiles → Keys](https://developer.apple.com/account/resources/authkeys/list), create a key with **Apple Push Notifications service (APNs)** enabled. Download the `.p8` and note its Key ID. Keep the `.p8` out of this repo (`*.p8` is gitignored).

### 2. Worker

```sh
cd worker
bun install
bunx wrangler secret put APNS_KEY < ~/Downloads/AuthKey_XXXXXXXXXX.p8
bunx wrangler secret put APNS_KEY_ID          # the 10-character Key ID
openssl rand -hex 32 | tee /dev/tty | bunx wrangler secret put API_TOKEN   # keep this for the app
bunx wrangler deploy
```

Polling starts within 5 minutes of deploying (the cron starts the loop), or immediately once the app registers. Then confirm Apple answers from Cloudflare:

```sh
TOKEN=...   # the API_TOKEN from above
curl -s -X POST -H "Authorization: Bearer $TOKEN" https://muo-duo.barclaysd.workers.dev/check | jq .health
```

`consecutiveFailures: 0` and a `lastSuccessAt` mean it's working. Follow live logs with `bunx wrangler tail`.

### 3. iOS app

```sh
cd ios
cp Config/Secrets.example.xcconfig Config/Secrets.xcconfig   # paste the API_TOKEN in
xcodegen generate
open MuoDuo.xcodeproj
```

Run it on your iPhone (push needs a real device and signing), allow notifications, then use **Settings → Send test notification**. If iOS asks, allow **Time Sensitive** notifications so alerts break through Focus modes, and allow alarms. Then use **Settings → Test Alarm** and lock the phone.

## API

All routes need `Authorization: Bearer <API_TOKEN>`.

| Route | What it does |
| --- | --- |
| `GET /status` | Config, health, pace, sweeps, current sightings, areas with their stores (name, city, coordinates, covered), `lastSeen`, `trackingSince`, `log`, `loggingSince`, `recentChecks`, device count, next check time, models with product and image URLs |
| `POST /devices` | `{ "token": "<hex>", "environment": "sandbox" \| "production" }` |
| `PUT /config` | Any of `{ "parts": [...], "areas": [{ "name", "search", "stores": ["R092", ...] }], "intervalSeconds": 10–3600, "paused": bool, "alarm": { "enabled": bool, "from": 0–24, "to": 0–24 } }`. Up to 6 areas of up to 12 stores each; new searches are checked against Apple first. Alarm hours are UK time, from included, to not. |
| `POST /check` | Run a check now and return the status |
| `POST /test-notification` | Push a test alert to every registered device. `{ "alarm": true }` rings an alarm too; `"delaySeconds": 0–20` sends it that much later. |

## Development

```sh
cd worker
bun test
bun run typecheck   # regenerates worker-configuration.d.ts first
bunx wrangler dev   # needs API_TOKEN in worker/.dev.vars
```

Under `wrangler dev`, Apple answers the local runtime's requests with HTTP 403, though plain `curl` from the same machine gets 200. The deployed Worker isn't affected, so test real checks against the deployment.
