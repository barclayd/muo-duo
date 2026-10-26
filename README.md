# Muo Duo

Push notifications to my iPhone the moment an iPhone Duo is available for pickup at any Apple Store in London, the south of England or Manchester.

- **`worker/`**: a Cloudflare Worker with one Durable Object that polls Apple's pickup endpoint for 22 stores in three areas, one request a second, and sends APNs pushes.
- **`ios/`**: a SwiftUI app with a Liquid Glass design. It shows the checker working live, when an iPhone Duo was last in stock and where, a map of the stores being watched, and which models alert you. Tapping an alert opens the product page.

## How it works

```
Durable Object alarm (about once a second: 3 areas × 4 colour/capacity queries, each round every ~12s)
  └─ GET apple.com/uk/shop/pickup-message-recommendations?location=<area>   (one query per alarm, taking turns)
       └─ new part@store seen? ── yes ─▶ APNs (time-sensitive) ─▶ iPhone
```

- **Endpoint.** Apple's `fulfillment-messages` endpoint sits behind a bot shield (HTTP 541 without a browser session), so it isn't used. `pickup-message-recommendations` is open. For the part asked about, it lists the *other* colours at the same capacity that are in stock at the 12 stores nearest the search; the queried part itself never appears. So the checker queries every colour at each watched capacity and keeps any watched part that comes back.
- **Areas.** One search only reaches 12 stores, so the checker runs three:

  | Area | Search | Stores |
  | --- | --- | --- |
  | London | `NW6 5PU` | all 12 in and around London, Watford to Bluewater |
  | South of England | `DT1 1XJ` | Brighton, Basingstoke, Reading, Southampton, Bath, Bristol, Exeter, Plymouth |
  | Manchester | `Manchester` | Arndale and Trafford Centre |

  Each area lists its store numbers, and answers are filtered to them, so a far-off store Apple adds to an answer is ignored. If Apple leaves a listed store out, the store shows as "not covered" in the app. Milton Keynes and Cambridge are just outside these searches; add another area to cover them.
- **Postcodes, not towns.** Apple's search takes town names too, but not reliably: "Manchester" and "Exeter" work, "Brighton" returns no stores, "Brighton and Hove" is rejected, and "Dorchester" picks Dorchester-on-Thames in Oxfordshire. `PUT /config` test-runs every new search against Apple and refuses one that fails or misses any of its listed stores.
- **One request at a time, alert straight away.** Each alarm asks one query, whichever was asked longest ago, and pushes as soon as the answer shows stock. Requests are spaced at least a second apart (`MIN_SECONDS_BETWEEN_REQUESTS`), so with 12 queries each one comes round every 12s, even with `intervalSeconds` at its 10s minimum. Stock is spotted ~6s after it appears on average.
- **Alerts fire once per part@store.** A sighting is only remembered after a push was delivered, so a failed push retries on the next cycle.
- **Last seen.** Every sighting is kept as a stretch of time a model was in stock at a store. Sightings less than 5 minutes apart join one stretch. The latest 20 are in `/status` as `lastSeen`, alongside `trackingSince`, so the app can say "Not yet" honestly.
- **Silence is not "no stock".** After 3 failed checks in a row you get a "Stock checker is failing" push, and a "recovered" push when it comes back. Failures back off exponentially on top of the normal pace, capped at 15 minutes. A response with no stores at all counts as a failure.
- **A canary for empty answers.** Every 10 minutes the checker also asks about iPhone 17 256GB in the first area, where it's reliably in stock. If it shows nothing 3 times running, Apple may be serving empty results, so you get a "Stock results may be stale" push and the app says so.
- **A watchdog.** A cron every 5 minutes restarts the alarm loop if it ever stops.
- **Rate limits.** Apple sends no rate-limit headers and the real ceiling is unknown. Its answers aren't cached (`cdn-cache; desc=MISS`, a new ETag every time), so polling faster really does see changes sooner. If Apple pushes back (HTTP 403, 429, 503 or the 541 bot shield), the checker halves its speed and pushes a "Checking less often" alert. Further pushback keeps halving it, down to checking each query every 15 minutes. After every 30 minutes without pushback it doubles again, back up to the configured speed, and pushes "Back to full speed" when it gets there.
- **Cloudflare cost.** This needs the Workers Paid plan. Every alarm is a Durable Object request and writes a few storage rows. At one alarm a second that's about 2.6M requests a month, 1.6M over the 1M included, so roughly $0.24 a month, and around 8M row writes, inside the 50M included. Duration can't exceed what's included: one object in memory all month is about 330,000 GB-s of the 400,000.

## The app

The Worker does all the checking; the app only watches it, polling `/status` every 2 seconds while it's on screen.

- **Live.** The Worker's last 30 requests to Apple are in `/status`. The app replays new ones at the pace they happened, so every check pulses: rings spread out from behind the phone, the toolbar badge ripples, the area's pins ripple on the map, and the ticker along the bottom names the area and model just checked.
- **Product images.** The hero shows Apple's own product shot for the selected colour, and the whole app takes that colour's look: Night Sky is the dusk desert from its wallpaper, with stars, Star White the dawn one. The images load from Apple's CDN at runtime and are cached on the device; they aren't in this repo.
- **Liquid Glass.** Cards, colour and capacity pickers, the ticker, the toolbar and the full-screen store map's chips and store panel are all glass over a slowly drifting mesh-gradient sky.
- **Settings** has pause, the current pace, notification registration and a test push, and the areas and stores being watched.

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

Run it on your iPhone (push needs a real device and signing), allow notifications, then use **Settings → Send test notification**. If iOS asks, allow **Time Sensitive** notifications so alerts break through Focus modes.

## API

All routes need `Authorization: Bearer <API_TOKEN>`.

| Route | What it does |
| --- | --- |
| `GET /status` | Config, health, pace, current sightings, areas with their stores (name, city, coordinates, covered), `lastSeen`, `trackingSince`, `recentChecks`, device count, next check time, models with product and image URLs |
| `POST /devices` | `{ "token": "<hex>", "environment": "sandbox" \| "production" }` |
| `PUT /config` | Any of `{ "parts": [...], "areas": [{ "name", "search", "stores": ["R092", ...] }], "intervalSeconds": 10–3600, "paused": bool }`. Up to 6 areas of up to 12 stores each; new searches are checked against Apple first. |
| `POST /check` | Run a check now and return the status |
| `POST /test-notification` | Push a test alert to every registered device |

## Development

```sh
cd worker
bun test
bun run typecheck   # regenerates worker-configuration.d.ts first
bunx wrangler dev   # needs API_TOKEN in worker/.dev.vars
```

Under `wrangler dev`, Apple answers the local runtime's requests with HTTP 403, though plain `curl` from the same machine gets 200. The deployed Worker isn't affected, so test real checks against the deployment.
