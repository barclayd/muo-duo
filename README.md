# Muo Duo

Push notifications to my iPhone the moment an iPhone Duo is available for pickup near NW6 5PU.

- **`worker/`**: a Cloudflare Worker with one Durable Object that polls Apple's pickup endpoint, checking each model every 20 seconds, and sends APNs pushes.
- **`ios/`**: a small SwiftUI app that registers for push, shows status, and lets you pick which models to watch. Tapping an alert opens the product page.

## How it works

```
Durable Object alarm (every ~5s ± 10%: 20s ÷ 4 colour/capacity queries)
  └─ GET apple.com/uk/shop/pickup-message-recommendations   (one query per alarm, taking turns)
       └─ new part@store seen? ── yes ─▶ APNs (time-sensitive) ─▶ iPhone
```

- **Endpoint.** Apple's `fulfillment-messages` endpoint sits behind a bot shield (HTTP 541 without a browser session), so it isn't used. `pickup-message-recommendations` is open. For the part asked about, it lists the *other* colours at the same capacity that are in stock at the 12 nearest stores (about 20 miles from NW6); the queried part itself never appears. So the checker queries every colour at each watched capacity and keeps any watched part that comes back.
- **One request at a time, alert straight away.** Each alarm asks about one part, whichever was asked about longest ago, and pushes as soon as that answer shows stock. Requests are evenly spaced (one every ~5s for four parts) rather than bursts, each part is re-checked every 20s, and stock is spotted ~10s after it appears on average.
- **Alerts fire once per part@store.** A sighting is only remembered after a push was delivered, so a failed push retries on the next cycle.
- **Silence is not "no stock".** After 3 failed checks in a row you get a "Stock checker is failing" push, and a "recovered" push when it comes back. Failures back off exponentially on top of the normal pace, capped at 15 minutes. A response with no stores at all counts as a failure.
- **A canary for empty answers.** Every 10 minutes the checker also asks about iPhone 17 256GB, which is reliably in stock around London. If it shows nothing 3 times running, Apple may be serving empty results, so you get a "Stock results may be stale" push and the app says so.
- **A watchdog.** A cron every 5 minutes restarts the alarm loop if it ever stops.
- **Rate limits.** Apple sends no rate-limit headers and the real ceiling is unknown. Its answers aren't cached (`cdn-cache; desc=MISS`, a new ETag every time), so polling faster really does see changes sooner. If Apple pushes back (HTTP 403, 429, 503 or the 541 bot shield), the checker halves its speed and pushes a "Checking less often" alert. Further pushback keeps halving it, down to checking each model every 15 minutes. After every 30 minutes without pushback it doubles again, back up to the configured speed, and pushes "Back to full speed" when it gets there.
- **Cloudflare cost.** This needs the Workers Paid plan. Every alarm is a Durable Object request and writes three storage rows (its answer, health, the next alarm). At 20s that's about 0.5M requests and 1.6M row writes a month, inside the 1M and 50M included. At the 10s minimum it's about 1.05M requests, roughly a cent over. Duration can't exceed what's included: one object in memory all month is about 330,000 GB-s of the 400,000. The 10s floor is there to keep Apple's request rate sane, not for cost.

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

Run it on your iPhone (push needs a real device and signing), allow notifications, then tap **Send test notification**. If iOS asks, allow **Time Sensitive** notifications so alerts break through Focus modes.

## API

All routes need `Authorization: Bearer <API_TOKEN>`.

| Route | What it does |
| --- | --- |
| `GET /status` | Config, health, current pace, current sightings, device count, next check time, models |
| `POST /devices` | `{ "token": "<hex>", "environment": "sandbox" \| "production" }` |
| `PUT /config` | Any of `{ "parts": [...], "postcode": "...", "intervalSeconds": 10–3600, "paused": bool }` |
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
