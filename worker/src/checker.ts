import { DurableObject } from 'cloudflare:workers';
import { type AlarmWindow, currentSightings, healthAlert, inAlarmWindow, newSightings, stockAlert, testAlert } from './alerts';
import { Apns, type ApnsDevice, type ProviderToken } from './apns';
import { AppleError, fetchPickup, fetchStores, forToday, type Sighting, type Store } from './apple';
import { type Area, DEFAULT_AREAS, DEFAULT_PARTS, imageUrl, MODELS, partsToQuery, productUrl } from './catalog';
import { Fcm, type FcmDevice, parseServiceAccount } from './fcm';
import { type Episode, recordSeen } from './history';
import { type CheckerNote, emptyLog, note, recordStock, type StockLog } from './log';
import { cycleSeconds, isPushback, MIN_SECONDS_BETWEEN_REQUESTS, type Pace, shouldAlertSlowdown, slowDown, speedUp, tickDelaySeconds } from './pace';
import type { PushResult } from './push';
import { answered, asked, CONFIRM_TIMEOUT_MS, confirmDue, emptySweeps, failed, outdate, resting, SWEEP_EVERY_MS, SWEEP_TIMEOUT_MS, standing, sweepDue, type Sweeps } from './sweep';

type Device = ApnsDevice | FcmDevice;

// A device as the app registers it; the Checker notes when.
type Registration = { token: string } & ({ platform: 'ios'; environment: ApnsDevice['environment'] } | { platform: 'android' });

export interface Config {
  areas: Area[];
  parts: string[];
  intervalSeconds: number;
  paused: boolean;
  // Stock found in these hours also rings an alarm on the phone.
  alarm: AlarmWindow;
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

// A request to Apple as it happened, for the app's live view. Kept in memory only. A sweep asks about
// every watched model, so it has no part.
interface Check {
  at: string;
  area: string;
  part?: string;
  ok: boolean;
  ms: number;
}

const DEFAULT_CONFIG: Config = { areas: DEFAULT_AREAS, parts: DEFAULT_PARTS, intervalSeconds: 20, paused: false, alarm: { enabled: true, from: 6, to: 8 } };
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
// is pushed as soon as any one answer shows it. Every five minutes an alarm sweeps an area's stores instead
// (see sweep.ts). A tick usually writes only its answer, health and the next alarm, three storage
// rows, so even at one request a second this stays well inside the paid plan's 50 million writes a month.
export class Checker extends DurableObject<Env> {
  private running = false;
  private apns?: Apns;
  private fcm?: Fcm;
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
    const log = await this.ctx.storage.get<StockLog>('log');
    const sweeps = await this.sweeps(config);
    const slowed = pace.intervalSeconds > config.intervalSeconds;

    return {
      config,
      health,
      pace: {
        intervalSeconds: cycleSeconds(Math.max(pace.intervalSeconds, config.intervalSeconds), queries.length),
        reason: slowed ? pace.reason : undefined,
      },
      resultsMayBeStale: health.canaryMisses >= CANARY_MISSES_BEFORE_ALERT,
      sweep: {
        everySeconds: SWEEP_EVERY_MS / 1000,
        lastAt: Object.values(sweeps.askedAt).length > 0 ? iso(Math.max(...Object.values(sweeps.askedAt))) : null,
        pausedUntil: sweeps.pausedUntil && sweeps.pausedUntil > Date.now() ? iso(sweeps.pausedUntil) : null,
        lastError: sweeps.lastError ?? null,
        // Areas resting after a 541, and until when.
        resting: Object.fromEntries(config.areas.filter((a) => resting(sweeps, a.search, Date.now())).map((a) => [a.name, iso(sweeps.rests[a.search]!.until)])),
        recent: sweeps.recent.map((o) => ({ at: iso(o.at), area: config.areas.find((a) => a.search === o.area)?.name ?? o.area, ok: o.ok, status: o.status ?? null })),
      },
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
      // Newest first.
      log: (log?.entries ?? []).toReversed(),
      loggingSince: log ? log.since : null,
      recentChecks: this.recentChecks.toReversed(),
      devices: (await this.devices()).length,
      nextCheckAt: alarm === null ? null : iso(alarm),
      models: MODELS.map((m) => ({ ...m, url: productUrl(m), imageUrl: imageUrl(m) })),
    };
  }

  async registerDevice(registration: Registration) {
    const devices = (await this.devices()).filter((d) => d.token !== registration.token);
    devices.push({ ...registration, registeredAt: new Date().toISOString() });
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
    const writes: Record<string, unknown> = { config, results: await this.results(queriesFor(config)), sweeps: await this.sweeps(config) };
    if (config.paused !== previous.paused) {
      const log = (await this.ctx.storage.get<StockLog>('log')) ?? emptyLog(iso(Date.now()));
      writes.log = note(log, { kind: config.paused ? 'paused' : 'resumed' }, iso(Date.now()));
    }
    await this.ctx.storage.put(writes);
    if (config.paused) await this.ctx.storage.deleteAlarm();
    else await this.ensureRunning();
  }

  async checkNow() {
    const config = await this.config();
    await this.runCycle(config, { batch: queriesFor(config) });
    return this.status();
  }

  async sendTest(alarm: boolean) {
    return this.notify(testAlert(alarm));
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
    const sweep = sweepDue(await this.sweeps(config), config.areas, started);
    if (queries.length > 0 && sweep) {
      await this.runCycle(config, { sweep });
    } else if (queries.length > 0) {
      const results = await this.results(queries);
      // Ask whichever query was asked longest ago, so they take turns and one that keeps failing
      // can't hold up the others.
      const next = queries.reduce((a, b) => ((results[b.key]?.askedAt ?? 0) < (results[a.key]?.askedAt ?? 0) ? b : a));
      await this.runCycle(config, { batch: [next] });
    }

    const pace = await this.pace(config);
    const { consecutiveFailures } = await this.health();
    const delaySeconds = tickDelaySeconds(Math.max(pace.intervalSeconds, config.intervalSeconds), queries.length, consecutiveFailures);
    // Timed from the start of this tick, so waiting on Apple doesn't stretch the interval.
    await this.ctx.storage.setAlarm(started + delaySeconds * (0.9 + Math.random() * 0.2) * 1000);
  }

  // A quick check asks about each query in `batch`. A sweep asks about every store in one area and
  // leaves the checker's health and pace to the quick checks.
  private async runCycle(config: Config, tick: { batch: readonly Query[] } | { sweep: Area }) {
    if (this.running) return;
    this.running = true;

    const quick = 'batch' in tick;
    const health = await this.health();
    const startingPace = await this.pace(config);
    let pace = startingPace;
    const started = Date.now();
    if (quick) health.lastCheckAt = iso(started);

    const queries = queriesFor(config);
    const results = await this.results(queries);
    const startingSweeps = await this.sweeps(config);
    let sweeps = startingSweeps;
    const stores = await this.stores();
    let storesChanged = false;
    const storedSeen = await this.ctx.storage.get<Seen>('seen');
    const seen = storedSeen ?? { since: started, episodes: [] };
    let episodes = seen.episodes;
    // Applied to the stored log at the end, so a pause logged while Apple was being asked isn't lost.
    const logChanges: ((log: StockLog) => StockLog)[] = [];
    const logNote = (entry: CheckerNote) => {
      const at = iso(Date.now());
      logChanges.push((log) => note(log, entry, at));
    };
    let asking: { query: Query; at: number } | undefined;

    try {
      const observed: Sighting[] = [];
      const sweep = async (area: Area, timeoutMs: number) => {
        const answer = await this.sweep(config, area, sweeps, timeoutMs);
        sweeps = answer.sweeps;
        observed.push(...answer.sightings);
      };

      if (!quick) await sweep(tick.sweep, SWEEP_TIMEOUT_MS);
      for (const [i, query] of (quick ? tick.batch : []).entries()) {
        if (i > 0) await scheduler.wait(MIN_SECONDS_BETWEEN_REQUESTS * 1000);
        asking = { query, at: Date.now() };
        // A failed request keeps the last good answer.
        results[query.key] = { ...(results[query.key] ?? { sightings: [] }), askedAt: asking.at };

        const answer = await fetchPickup(query.part, query.area.search);
        const watched = new Set(query.area.stores);
        const sightings = answer.sightings.filter((s) => watched.has(s.storeNumber) && forToday(s));
        const missing = query.area.stores.filter((number) => !answer.stores.some((s) => s.number === number));
        results[query.key] = { askedAt: asking.at, sightings, missing };
        if (answer.sightings.length === 0) sweeps = outdate(sweeps, query.area.search, query.part);
        for (const store of answer.stores) {
          if (watched.has(store.number) && JSON.stringify(stores[store.number]) !== JSON.stringify(store)) {
            stores[store.number] = store;
            storesChanged = true;
          }
        }
        observed.push(...sightings);
        this.logCheck(query.area, query.part, true, asking.at);
      }
      asking = undefined;

      const previous = await this.sightings();
      const inStock = () => {
        const swept = config.areas.map((area) => standing(sweeps, area.search, Date.now()));
        return currentSightings([...Object.values(results).map((r) => r.sightings), ...swept], config.parts);
      };
      let current = inStock();
      let fresh = newSightings(previous, current);
      // New stock from a quick check sweeps its area before the alert goes, so the alert lists every
      // store with it, not just the one the quick check named. It's asked without the usual second's
      // gap, as the alert is waiting on it and new stock is rare.
      const confirming = quick ? config.areas.filter((a) => fresh.some((s) => a.stores.includes(s.storeNumber)) && confirmDue(sweeps, a.search, Date.now())) : [];
      for (const area of confirming) await sweep(area, CONFIRM_TIMEOUT_MS);
      if (confirming.length > 0) {
        current = inStock();
        fresh = newSightings(previous, current);
      }

      if (quick && health.consecutiveFailures >= FAILURES_BEFORE_ALERT) logNote({ kind: 'recovered' });
      const watching = (s: Sighting) => config.parts.includes(s.part) && config.areas.some((a) => a.stores.includes(s.storeNumber));
      const answeredAt = iso(Date.now());
      logChanges.push((log) => recordStock(log, current, watching, answeredAt));
      // Only remember sightings once someone has been told, so a failed push retries next time.
      const delivered = fresh.length === 0 || (await this.notify(stockAlert(fresh, inAlarmWindow(config.alarm, new Date())))).delivered;
      if (delivered && JSON.stringify(current) !== JSON.stringify(previous)) await this.ctx.storage.put('sightings', current);
      // History counts only what was seen just now, not answers carried over from earlier checks.
      episodes = recordSeen(episodes, currentSightings([observed], config.parts), Date.now(), EPISODE_GAP_MS);

      if (!quick) {
        console.log(JSON.stringify({ event: 'sweep', area: tick.sweep.name, ms: Date.now() - started, inStock: current.length, fresh: fresh.length, delivered, error: sweeps.lastError }));
        return;
      }

      const canaryDue = !health.lastCanaryAt || Date.now() - Date.parse(health.lastCanaryAt) >= CANARY_INTERVAL_MS;
      if (canaryDue) await this.canary(config, health, logNote);

      if (health.unhealthyAlerted) await this.notify(healthAlert('Stock checker recovered', 'Apple is responding again.'));
      Object.assign(health, { lastSuccessAt: health.lastCheckAt, lastError: undefined, consecutiveFailures: 0, unhealthyAlerted: false });
      pace = speedUp(pace, config.intervalSeconds, Date.now());
      if (startingPace.alerted && pace.intervalSeconds <= config.intervalSeconds) {
        const every = cycleSeconds(config.intervalSeconds, queries.length);
        await this.notify(healthAlert('Back to full speed', `Checks are every ${every}s again.`));
      }
      const missing = tick.batch.flatMap((q) => results[q.key]?.missing ?? []);
      const confirmed = confirming.map((a) => a.name);
      console.log(JSON.stringify({ event: 'check', ok: true, queries: tick.batch.map((q) => q.key), ms: Date.now() - started, inStock: current.length, fresh: fresh.length, confirmed, delivered, missing, canaryMisses: health.canaryMisses, intervalSeconds: pace.intervalSeconds }));
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      // Sweeps catch Apple's errors themselves, so anything here is the checker's own.
      if (!quick) {
        console.error(JSON.stringify({ event: 'sweep', area: tick.sweep.name, error: message }));
        return;
      }
      health.consecutiveFailures += 1;
      health.lastError = asking ? `${asking.query.area.name}: ${message}` : message;
      if (health.consecutiveFailures === FAILURES_BEFORE_ALERT) logNote({ kind: 'failing', detail: health.lastError });
      if (asking) this.logCheck(asking.query.area, asking.query.part, false, asking.at);
      console.error(JSON.stringify({ event: 'check', ok: false, queries: tick.batch.map((q) => q.key), ms: Date.now() - started, failures: health.consecutiveFailures, error: health.lastError }));

      if (error instanceof AppleError && isPushback(error.status, health.consecutiveFailures)) {
        pace = slowDown(pace, config.intervalSeconds, health.lastError, Date.now());
        if (shouldAlertSlowdown(pace, config.intervalSeconds)) {
          const every = cycleSeconds(pace.intervalSeconds, queries.length);
          const body = `${health.lastError}, so checks are now every ${every}s. They speed back up after 2 minutes without pushback.`;
          pace = { ...pace, alerted: (await this.notify(healthAlert('Checking less often', body))).delivered };
        }
      }

      if (health.consecutiveFailures >= FAILURES_BEFORE_ALERT && !health.unhealthyAlerted) {
        const body = `${health.consecutiveFailures} checks in a row failed. Last error: ${health.lastError}`;
        health.unhealthyAlerted = (await this.notify(healthAlert('Stock checker is failing', body))).delivered;
      }
    } finally {
      const writes: Record<string, unknown> = { health, results };
      if (storesChanged) writes.stores = stores;
      if (sweeps !== startingSweeps) writes.sweeps = sweeps;
      if (!storedSeen || episodes !== seen.episodes) writes.seen = { since: seen.since, episodes };
      if (pace !== startingPace) writes.pace = pace;
      const storedLog = await this.ctx.storage.get<StockLog>('log');
      const log = logChanges.reduce((log, change) => change(log), storedLog ?? emptyLog(iso(started)));
      if (log !== storedLog) writes.log = log;
      await this.ctx.storage.put(writes);
      this.running = false;
    }
  }

  // Sweeps an area's stores. Apple's errors are kept with the sweeps rather than thrown, so they
  // don't count against the checker's health.
  private async sweep(config: Config, area: Area, sweeps: Sweeps, timeoutMs: number) {
    const at = Date.now();
    sweeps = asked(sweeps, area.search, at);
    try {
      const answer = await fetchStores(config.parts, area.search, AbortSignal.timeout(timeoutMs));
      const watched = new Set(area.stores);
      const sightings = answer.sightings.filter((s) => watched.has(s.storeNumber) && forToday(s));
      this.logCheck(area, undefined, true, at);
      return { sweeps: answered(sweeps, area.search, sightings, at), sightings };
    } catch (error) {
      const timedOut = error instanceof DOMException && error.name === 'TimeoutError';
      const message = timedOut ? `no answer within ${timeoutMs / 1000}s` : error instanceof Error ? error.message : String(error);
      const apple = error instanceof AppleError ? error : undefined;
      const failure = { error: `${area.name}: ${message}`, status: apple?.status, retryAfterMs: apple?.retryAfterMs };
      this.logCheck(area, undefined, false, at);
      return { sweeps: failed(sweeps, area.search, failure, config.areas.map((a) => a.search), Date.now()), sightings: [] };
    }
  }

  private logCheck(area: Area, part: string | undefined, ok: boolean, at: number) {
    this.recentChecks.push({ at: iso(at), area: area.name, part, ok, ms: Date.now() - at });
    if (this.recentChecks.length > RECENT_CHECKS) this.recentChecks.shift();
  }

  private async canary(config: Config, health: Health, logNote: (entry: CheckerNote) => void) {
    const area = config.areas[0];
    if (!area) return;
    await scheduler.wait(MIN_SECONDS_BETWEEN_REQUESTS * 1000);
    const seen = (await fetchPickup(CANARY_PART, area.search)).sightings.length > 0;
    health.lastCanaryAt = new Date().toISOString();
    if (seen && health.canaryMisses >= CANARY_MISSES_BEFORE_ALERT) logNote({ kind: 'normal' });
    health.canaryMisses = seen ? 0 : health.canaryMisses + 1;
    if (health.canaryMisses === CANARY_MISSES_BEFORE_ALERT) logNote({ kind: 'stale', detail: area.name });

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
    if (devices.length === 0) return { delivered: false, errors: ['No devices registered'] };

    const results = await Promise.all(
      devices.map(async (device) => ({
        device,
        result: await this.push(device, payload).catch((e): PushResult => ({ ok: false, status: 0, reason: String(e), deviceGone: false })),
      })),
    );

    const failures = results.flatMap(({ device, result }) => (result.ok ? [] : [{ device, ...result }]));
    const gone = new Set(failures.filter((f) => f.deviceGone).map((f) => f.device.token));
    if (gone.size) await this.ctx.storage.put('devices', devices.filter((d) => !gone.has(d.token)));
    for (const f of failures) {
      const platform = f.device.platform ?? 'ios';
      console.error(JSON.stringify({ event: 'push', ok: false, platform, status: f.status, reason: f.reason, removed: f.deviceGone }));
    }

    return {
      delivered: failures.length < results.length,
      errors: failures.map((f) => (f.status ? `${f.status} ${f.reason}` : f.reason)),
    };
  }

  // iPhones through APNs, Android phones through Firebase.
  private async push(device: Device, payload: object): Promise<PushResult> {
    if (device.platform === 'android') {
      const fcm = this.getFcm();
      return fcm ? fcm.send(device, payload) : { ok: false, status: 0, reason: 'Firebase secret is not configured', deviceGone: false };
    }
    const apns = this.getApns();
    return apns ? apns.send(device, payload) : { ok: false, status: 0, reason: 'APNs secrets are not configured', deviceGone: false };
  }

  private getApns() {
    const { APNS_KEY, APNS_KEY_ID, APNS_TEAM_ID, APNS_TOPIC } = this.env;
    if (!APNS_KEY || !APNS_KEY_ID) return undefined;
    this.apns ??= new Apns(
      { keyP8: APNS_KEY, keyId: APNS_KEY_ID, teamId: APNS_TEAM_ID, topic: APNS_TOPIC },
      {
        get: () => this.ctx.storage.get<ProviderToken>('apnsToken'),
        put: (token) => this.ctx.storage.put('apnsToken', token),
      },
    );
    return this.apns;
  }

  private getFcm() {
    if (!this.env.FCM_SERVICE_ACCOUNT) return undefined;
    this.fcm ??= new Fcm(parseServiceAccount(this.env.FCM_SERVICE_ACCOUNT));
    return this.fcm;
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

  // Sweeps of the configured areas only, dropping any left over from an earlier config. Sweeps stored
  // before areas rested on their own are filled in from empty.
  private async sweeps(config: Config): Promise<Sweeps> {
    const stored = { ...emptySweeps(), ...(await this.ctx.storage.get<Sweeps>('sweeps')) };
    const searches = new Set(config.areas.map((a) => a.search));
    const keep = <T>(byArea: Record<string, T>) => Object.fromEntries(Object.entries(byArea).filter(([search]) => searches.has(search)));
    return { ...stored, answers: keep(stored.answers), askedAt: keep(stored.askedAt), rests: keep(stored.rests), turnedAway: stored.turnedAway.filter((s) => searches.has(s)) };
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
