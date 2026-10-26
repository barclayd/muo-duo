# Muo Duo

Push notifications to my iPhone the moment an iPhone Duo is available for pickup near NW6 5PU.

- **`worker/`**: a Cloudflare Worker with one Durable Object that polls Apple's pickup endpoint about every 60 seconds and sends APNs pushes.
- **`ios/`**: a small SwiftUI app that registers for push, shows status, and lets you pick which models to watch. Tapping an alert opens the product page.

## How it works

```
Durable Object alarm (~60s ± 10%)
  └─ GET apple.com/uk/shop/pickup-message-recommendations   (one per colour at each watched capacity, 2s apart)
       └─ new part@store seen? ── yes ─▶ APNs (time-sensitive) ─▶ iPhone
```

- **Endpoint.** Apple's `fulfillment-messages` endpoint sits behind a bot shield (HTTP 541 without a browser session), so it isn't used. `pickup-message-recommendations` is open. It reports what's available at nearby stores, and often lists *similar* models (such as the other colour) rather than the one asked for. So the checker queries both colours at each watched capacity and keeps any watched part that comes back.
- **Alerts fire once per part@store.** A sighting is only remembered after a push was delivered, so a failed push retries on the next cycle.
- **Silence is not "no stock".** After 3 failed checks in a row you get a "Stock checker is failing" push, and a "recovered" push when it comes back. Failures back off exponentially, capped at 15 minutes.
- **A watchdog.** A cron every 5 minutes restarts the alarm loop if it ever stops.
- **Rate limits.** Apple sends no rate-limit headers. 10 requests at 30s intervals from Cloudflare (LHR) all returned 200. The real ceiling is unknown, so the defaults stay modest (4 requests a minute).

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
| `GET /status` | Config, health, current sightings, device count, next check time, models |
| `POST /devices` | `{ "token": "<hex>", "environment": "sandbox" \| "production" }` |
| `PUT /config` | Any of `{ "parts": [...], "postcode": "...", "intervalSeconds": 30–3600, "paused": bool }` |
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
