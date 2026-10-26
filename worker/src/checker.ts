import { DurableObject } from 'cloudflare:workers';
import { currentSightings, healthAlert, newSightings, stockAlert } from './alerts';
import { Apns, type ApnsResult, type Device } from './apns';
import { AppleError, fetchPickup, type Sighting, type Store } from './apple';
import { type Area, DEFAULT_AREAS, DEFAULT_PARTS, imageUrl, MODELS, partsToQuery, productUrl } from './catalog';
import { type Episode, recordSeen } from './history';
import { cycleSeconds, isPushback, MIN_SECONDS_BETWEEN_REQUESTS, type Pace, slowDown, speedUp, tickDelaySeconds } from './pace';

export interface Config {
  areas: Area[];
  parts: string[];
  intervalSeconds: number;
  paused: boolean;
}

export interface Health {
  lastCheckAt?: string;
  lastSuccessAt?: string;
  lastError?: string;
  consecutiveFailures: number;
  unhealthyAlerted: boolean;
  lastCanaryAt?: string;
  canaryMisses: number;
  staleAlerted: boolean;
}

// One model asked about in one area.
interface Query {
  key: string;
  area: Area;
  part: string;
}

// When each query was last asked, its last good answer (stores in its area only), and any of the
// area's stores Apple left out of that answer, which therefore aren't being watched.
type Results = Record<string, { askedAt: number; sightings: Sighting[]; missing?: string[] }>;

// Every stretch of stock seen since tracking began, for "last seen in stock".
interface Seen {
  since: number;
  episodes: readonly Episode[];
}

// A check as it happened, for the app's live view. Kept in memory only.
interface Check {
  at: string;
  area: string;
  part: string;
  ok: boolean;
  ms: number;
}

const DEFAULT_CONFIG: Config = { areas: DEFAULT_AREAS, parts: DEFAULT_PARTS, intervalSeconds: 20, paused: false };
const FAILURES_BEFORE_ALERT = 3;
const RECENT_CHECKS = 30;
const LAST_SEEN = 20;

// Seen again within this long counts as the same stretch of stock.
const EPISODE_GAP_MS = 5 * 60_000;

// iPhone 17 256GB is reliably in stock around London. If even it shows nothing anywhere, Apple is
// probably serving empty results, which would otherwise look exactly like "no Duo in stock".
const CANARY_PART = 'MG6N4QN/A';
const CANARY_INTERVAL_MS = 10 * 60_000;
const CANARY_MISSES_BEFORE_ALERT = 3;

// Models take turns with areas, so each area comes round every few seconds rather than in a burst.
function queriesFor(config: Config): Query[] {
  return partsToQuery(config.parts).flatMap((part) => config.areas.map((area) => ({ key: `${part}|${area.search}`, area, part })));
}

const iso = (ms: number) => new Date(ms).toISOString();

// Single instance that polls Apple on a self-rescheduling alarm and pushes to registered devices.
// Each alarm asks about one model in one area, taking turns, so requests are spread evenly and stock
// is pushed as soon as any one answer shows it. A tick usually writes only its answer, health and the
// next alarm, three storage rows, so even at one request a second this stays well inside the paid
// plan's 50 million writes a month.
export class Checker extends DurableObject<Env> {
  private running = false;
  private apns?: Apns;
  private recentChecks: Check[] = [];

  async status() {
    const alarm = await this.ctx.storage.getAlarm();
    const config = await this.config();
    const health = await this.health();
    const pace = await this.pace(config);
    const queries = queriesFor(config);
    const results = await this.results(queries);
    const stores = await this.stores();
    const seen = await this.ctx.storage.get<Seen>('seen');
    const slowed = pace.intervalSeconds > config.intervalSeconds;

    return {
      config,
      health,
      pace: {
        intervalSeconds: cycleSeconds(Math.max(pace.intervalSeconds, config.intervalSeconds), queries.length),
        reason: slowed ? pace.reason : undefined,
      },
      resultsMayBeStale: health.canaryMisses >= CANARY_MISSES_BEFORE_ALERT,
      sightings: await this.sightings(),
      areas: config.areas.map((area) => {
        const missing = new Set(queries.filter((q) => q.area === area).flatMap((q) => results[q.key]?.missing ?? []));
        return {
          name: area.name,
          search: area.search,
          // A store's name and place come from Apple's answers, so they're missing until it's been asked.
          stores: area.stores.map((number) => {
            const store: Store | undefined = stores[number];
            return { number, name: store?.name ?? number, city: store?.city, latitude: store?.latitude, longitude: store?.longitude, covered: !missing.has(number) };
          }),
        };
      }),
      lastSeen: (seen?.episodes ?? [])
        .toSorted((a, b) => b.to - a.to)
        .slice(0, LAST_SEEN)
        .map((e) => ({ ...e, from: iso(e.from), to: iso(e.to) })),
      trackingSince: seen ? iso(seen.since) : null,
      recentChecks: this.recentChecks.toReversed(),
      devices: (await this.devices()).length,
      nextCheckAt: alarm === null ? null : iso(alarm),
      models: MODELS.map((m) => ({ ...m, url: productUrl(m), imageUrl: imageUrl(m) })),
    };
  }

  async registerDevice(token: string, environment: Device['environment']) {
    const devices = (await this.devices()).filter((d) => d.token !== token);
    devices.push({ token, environment, registeredAt: new Date().toISOString() });
    await this.ctx.storage.put('devices', devices);
    await this.ensureRunning();
  }

  // Returns an error if Apple doesn't list a new area's stores near its search.
  async updateConfig(patch: Partial<Config>): Promise<string | undefined> {
    const previous = await this.config();
    for (const area of patch.areas ?? []) {
      if (previous.areas.some((a) => a.search === area.search)) continue;
      const answer = await fetchPickup(CANARY_PART, area.search).catch((e: Error) => e);
      if (answer instanceof Error) return `${area.name}: ${answer.message}`;
      const missing = area.stores.filter((number) => !answer.stores.some((s) => s.number === number));
      if (missing.length > 0) return `${area.name}: Apple doesn't list ${missing.join(', ')} among the 12 stores nearest ${area.search}`;
    }

    const config = { ...previous, ...patch };
    // Forget answers to queries no longer asked, so asking them again can't alert on old stock.
    await this.ctx.storage.put({ config, results: await this.results(queriesFor(config)) });
    if (config.paused) await this.ctx.storage.deleteAlarm();
    else await this.ensureRunning();
  }

  async checkNow() {
    const config = await this.config();
    await this.runCycle(config, queriesFor(config));
    return this.status();
  }

  async sendTest() {
    return this.notify({
      aps: {
        alert: { title: 'Muo Duo', body: "Test notification: you're all set." },
        sound: 'default',
        'interruption-level': 'time-sensitive',
      },
    });
  }

  async ensureRunning() {
    if ((await this.config()).paused) return;
    if ((await this.ctx.storage.getAlarm()) === null) await this.ctx.storage.setAlarm(Date.now());
  }

  async alarm() {
    const config = await this.config();
    if (config.paused) return;
    const started = Date.now();

    const queries = queriesFor(config);
    if (queries.length > 0) {
      const results = await this.results(queries);
      // Ask whichever query was asked longest ago, so they take turns and one that keeps failing
      // can't hold up the others.
      const next = queries.reduce((a, b) => ((results[b.key]?.askedAt ?? 0) < (results[a.key]?.askedAt ?? 0) ? b : a));
      await this.runCycle(config, [next]);
    }

    const pace = await this.pace(config);
    const { consecutiveFailures } = await this.health();
    const delaySeconds = tickDelaySeconds(Math.max(pace.intervalSeconds, config.intervalSeconds), queries.length, consecutiveFailures);
    // Timed from the start of this tick, so waiting on Apple doesn't stretch the interval.
    await this.ctx.storage.setAlarm(started + delaySeconds * (0.9 + Math.random() * 0.2) * 1000);
  }

  private async runCycle(config: Config, batch: readonly Query[]) {
    if (this.running) return;
    this.running = true;

    const health = await this.health();
    const startingPace = await this.pace(config);
    let pace = startingPace;
    const started = Date.now();
    health.lastCheckAt = iso(started);

    const queries = queriesFor(config);
    const results = await this.results(queries);
    const stores = await this.stores();
    let storesChanged = false;
    const storedSeen = await this.ctx.storage.get<Seen>('seen');
    const seen = storedSeen ?? { since: started, episodes: [] };
    let episodes = seen.episodes;
    let asking: { query: Query; at: number } | undefined;

    try {
      const observed: Sighting[] = [];
      for (const [i, query] of batch.entries()) {
        if (i > 0) await scheduler.wait(MIN_SECONDS_BETWEEN_REQUESTS * 1000);
        asking = { query, at: Date.now() };
        // A failed request keeps the last good answer.
        results[query.key] = { ...(results[query.key] ?? { sightings: [] }), askedAt: asking.at };

        const answer = await fetchPickup(query.part, query.area.search);
        const watched = new Set(query.area.stores);
        const sightings = answer.sightings.filter((s) => watched.has(s.storeNumber));
        const missing = query.area.stores.filter((number) => !answer.stores.some((s) => s.number === number));
        results[query.key] = { askedAt: asking.at, sightings, missing };
        for (const store of answer.stores) {
          if (watched.has(store.number) && JSON.stringify(stores[store.number]) !== JSON.stringify(store)) {
            stores[store.number] = store;
            storesChanged = true;
          }
        }
        observed.push(...sightings);
        this.logCheck(query, true, asking.at);
      }
      asking = undefined;

      const previous = await this.sightings();
      const current = currentSightings(Object.values(results).map((r) => r.sightings), config.parts);
      const fresh = newSightings(previous, current);
      // Only remember sightings once someone has been told, so a failed push retries next time.
      const delivered = fresh.length === 0 || (await this.notify(stockAlert(fresh))).delivered;
      if (delivered && JSON.stringify(current) !== JSON.stringify(previous)) await this.ctx.storage.put('sightings', current);
      // History counts only what was seen just now, not answers carried over from earlier checks.
      episodes = recordSeen(episodes, currentSightings([observed], config.parts), Date.now(), EPISODE_GAP_MS);

      const canaryDue = !health.lastCanaryAt || Date.now() - Date.parse(health.lastCanaryAt) >= CANARY_INTERVAL_MS;
      if (canaryDue) await this.canary(config, health);

      if (health.unhealthyAlerted) await this.notify(healthAlert('Stock checker recovered', 'Apple is responding again.'));
      Object.assign(health, { lastSuccessAt: health.lastCheckAt, lastError: undefined, consecutiveFailures: 0, unhealthyAlerted: false });
      pace = speedUp(pace, config.intervalSeconds, Date.now());
      if (startingPace.intervalSeconds > config.intervalSeconds && pace.intervalSeconds <= config.intervalSeconds) {
        const every = cycleSeconds(config.intervalSeconds, queries.length);
        await this.notify(healthAlert('Back to full speed', `Checks are every ${every}s again.`));
      }
      const missing = batch.flatMap((q) => results[q.key]?.missing ?? []);
      console.log(JSON.stringify({ event: 'check', ok: true, queries: batch.map((q) => q.key), ms: Date.now() - started, inStock: current.length, fresh: fresh.length, delivered, missing, canaryMisses: health.canaryMisses, intervalSeconds: pace.intervalSeconds }));
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      health.consecutiveFailures += 1;
      health.lastError = asking ? `${asking.query.area.name}: ${message}` : message;
      if (asking) this.logCheck(asking.query, false, asking.at);
      console.error(JSON.stringify({ event: 'check', ok: false, queries: batch.map((q) => q.key), ms: Date.now() - started, failures: health.consecutiveFailures, error: health.lastError }));

      if (error instanceof AppleError && isPushback(error.status)) {
        pace = slowDown(pace, config.intervalSeconds, health.lastError, Date.now());
        // Tell once per episode, not on every doubling.
        if (startingPace.intervalSeconds <= config.intervalSeconds) {
          const every = cycleSeconds(pace.intervalSeconds, queries.length);
          const body = `${health.lastError}, so checks are now every ${every}s. They speed back up after 30 minutes without pushback.`;
          await this.notify(healthAlert('Checking less often', body));
        }
      }

      if (health.consecutiveFailures >= FAILURES_BEFORE_ALERT && !health.unhealthyAlerted) {
        const body = `${health.consecutiveFailures} checks in a row failed. Last error: ${health.lastError}`;
        health.unhealthyAlerted = (await this.notify(healthAlert('Stock checker is failing', body))).delivered;
      }
    } finally {
      const writes: Record<string, unknown> = { health, results };
      if (storesChanged) writes.stores = stores;
      if (!storedSeen || episodes !== seen.episodes) writes.seen = { since: seen.since, episodes };
      if (pace !== startingPace) writes.pace = pace;
      await this.ctx.storage.put(writes);
      this.running = false;
    }
  }

  private logCheck(query: Query, ok: boolean, at: number) {
    this.recentChecks.push({ at: iso(at), area: query.area.name, part: query.part, ok, ms: Date.now() - at });
    if (this.recentChecks.length > RECENT_CHECKS) this.recentChecks.shift();
  }

  private async canary(config: Config, health: Health) {
    const area = config.areas[0];
    if (!area) return;
    await scheduler.wait(MIN_SECONDS_BETWEEN_REQUESTS * 1000);
    const seen = (await fetchPickup(CANARY_PART, area.search)).sightings.length > 0;
    health.lastCanaryAt = new Date().toISOString();
    health.canaryMisses = seen ? 0 : health.canaryMisses + 1;

    if (seen && health.staleAlerted) {
      await this.notify(healthAlert('Stock results look normal again', 'Apple is returning stock data again.'));
      health.staleAlerted = false;
    } else if (!seen && health.canaryMisses >= CANARY_MISSES_BEFORE_ALERT && !health.staleAlerted) {
      const body = `Apple is answering, but even iPhone 17 shows no stock around ${area.name}, so "nothing in stock" may be wrong.`;
      health.staleAlerted = (await this.notify(healthAlert('Stock results may be stale', body))).delivered;
    }
  }

  private async notify(payload: object) {
    const devices = await this.devices();
    const apns = this.getApns();
    if (!apns) return { delivered: false, errors: ['APNs secrets are not configured'] };
    if (devices.length === 0) return { delivered: false, errors: ['No devices registered'] };

    const results = await Promise.all(
      devices.map(async (device) => ({
        device,
        result: await apns
          .send(device, payload)
          .catch((e): ApnsResult => ({ ok: false, status: 0, reason: String(e), deviceGone: false })),
      })),
    );

    const failures = results.flatMap(({ device, result }) => (result.ok ? [] : [{ device, ...result }]));
    const gone = new Set(failures.filter((f) => f.deviceGone).map((f) => f.device.token));
    if (gone.size) await this.ctx.storage.put('devices', devices.filter((d) => !gone.has(d.token)));
    for (const f of failures) {
      console.error(JSON.stringify({ event: 'push', ok: false, status: f.status, reason: f.reason, removed: f.deviceGone }));
    }

    return {
      delivered: failures.length < results.length,
      errors: failures.map((f) => `${f.status} ${f.reason}`),
    };
  }

  private getApns() {
    const { APNS_KEY, APNS_KEY_ID, APNS_TEAM_ID, APNS_TOPIC } = this.env;
    if (!APNS_KEY || !APNS_KEY_ID) return undefined;
    this.apns ??= new Apns({ keyP8: APNS_KEY, keyId: APNS_KEY_ID, teamId: APNS_TEAM_ID, topic: APNS_TOPIC });
    return this.apns;
  }

  private async config(): Promise<Config> {
    // Before areas, the config had a single postcode. It was the London default, so it's dropped.
    const { postcode: _, ...stored } = (await this.ctx.storage.get<Partial<Config> & { postcode?: string }>('config')) ?? {};
    return { ...DEFAULT_CONFIG, ...stored };
  }

  private async health(): Promise<Health> {
    const defaults: Health = { consecutiveFailures: 0, unhealthyAlerted: false, canaryMisses: 0, staleAlerted: false };
    return { ...defaults, ...(await this.ctx.storage.get<Partial<Health>>('health')) };
  }

  private async pace(config: Config): Promise<Pace> {
    return (await this.ctx.storage.get<Pace>('pace')) ?? { intervalSeconds: config.intervalSeconds, changedAt: 0 };
  }

  // Answers to the given queries only, dropping any left over from an earlier config.
  private async results(queries: readonly Query[]): Promise<Results> {
    const stored = (await this.ctx.storage.get<Results>('results')) ?? {};
    return Object.fromEntries(queries.flatMap((q) => (stored[q.key] ? [[q.key, stored[q.key]]] : [])));
  }

  // Names and places of the watched stores, as Apple last described them.
  private async stores() {
    return (await this.ctx.storage.get<Record<string, Store>>('stores')) ?? {};
  }

  private async devices() {
    return (await this.ctx.storage.get<Device[]>('devices')) ?? [];
  }

  private async sightings() {
    return (await this.ctx.storage.get<Sighting[]>('sightings')) ?? [];
  }
}
