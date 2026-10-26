import { DurableObject } from 'cloudflare:workers';
import { currentSightings, healthAlert, newSightings, stockAlert } from './alerts';
import { Apns, type ApnsResult, type Device } from './apns';
import { AppleError, fetchPickup, type Sighting } from './apple';
import { DEFAULT_PARTS, MODELS, partsToQuery, productUrl } from './catalog';
import { isPushback, type Pace, slowDown, speedUp, tickDelaySeconds } from './pace';

export interface Config {
  postcode: string;
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

// When each part was last asked about, and its last good answer.
type Results = Record<string, { askedAt: number; sightings: Sighting[] }>;

const DEFAULT_CONFIG: Config = { postcode: 'NW6 5PU', parts: DEFAULT_PARTS, intervalSeconds: 20, paused: false };
const FAILURES_BEFORE_ALERT = 3;
const GAP_BETWEEN_REQUESTS_MS = 2_000;

// iPhone 17 256GB is reliably in stock around London. If even it shows nothing anywhere, Apple is
// probably serving empty results, which would otherwise look exactly like "no Duo in stock".
const CANARY_PART = 'MG6N4QN/A';
const CANARY_INTERVAL_MS = 10 * 60_000;
const CANARY_MISSES_BEFORE_ALERT = 3;

// Single instance that polls Apple on a self-rescheduling alarm and pushes to registered devices.
// Each alarm asks about one part, taking turns, so requests are spread evenly and stock is pushed as
// soon as any one answer shows it. A tick writes only its answer, health and the next alarm, three
// storage rows, so even at 10s this stays well inside the paid plan's 50 million writes a month.
export class Checker extends DurableObject<Env> {
  private running = false;
  private apns?: Apns;

  async status() {
    const alarm = await this.ctx.storage.getAlarm();
    const config = await this.config();
    const health = await this.health();
    const pace = await this.pace(config);
    const slowed = pace.intervalSeconds > config.intervalSeconds;
    return {
      config,
      health,
      pace: { intervalSeconds: slowed ? pace.intervalSeconds : config.intervalSeconds, reason: slowed ? pace.reason : undefined },
      resultsMayBeStale: health.canaryMisses >= CANARY_MISSES_BEFORE_ALERT,
      sightings: await this.sightings(),
      devices: (await this.devices()).length,
      nextCheckAt: alarm === null ? null : new Date(alarm).toISOString(),
      models: MODELS.map((m) => ({ ...m, url: productUrl(m) })),
    };
  }

  async registerDevice(token: string, environment: Device['environment']) {
    const devices = (await this.devices()).filter((d) => d.token !== token);
    devices.push({ token, environment, registeredAt: new Date().toISOString() });
    await this.ctx.storage.put('devices', devices);
    await this.ensureRunning();
  }

  async updateConfig(patch: Partial<Config>) {
    const config = { ...(await this.config()), ...patch };
    // Forget answers about parts no longer asked about, so watching them again can't alert on old stock.
    const queried = new Set(partsToQuery(config.parts));
    const results = Object.fromEntries(Object.entries(await this.results()).filter(([part]) => queried.has(part)));
    await this.ctx.storage.put({ config, results });
    if (config.paused) await this.ctx.storage.deleteAlarm();
    else await this.ensureRunning();
  }

  async checkNow() {
    const config = await this.config();
    await this.runCycle(config, partsToQuery(config.parts));
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

    const queries = partsToQuery(config.parts);
    if (queries.length > 0) {
      const results = await this.results();
      // Ask about whichever part was asked about longest ago, so the parts take turns and one that
      // keeps failing can't hold up the others.
      const next = queries.reduce((a, b) => ((results[b]?.askedAt ?? 0) < (results[a]?.askedAt ?? 0) ? b : a));
      await this.runCycle(config, [next]);
    }

    const pace = await this.pace(config);
    const { consecutiveFailures } = await this.health();
    const delaySeconds = tickDelaySeconds(Math.max(pace.intervalSeconds, config.intervalSeconds), queries.length, consecutiveFailures);
    await this.ctx.storage.setAlarm(Date.now() + delaySeconds * (0.9 + Math.random() * 0.2) * 1000);
  }

  private async runCycle(config: Config, parts: readonly string[]) {
    if (this.running) return;
    this.running = true;

    const health = await this.health();
    const startingPace = await this.pace(config);
    let pace = startingPace;
    const started = Date.now();
    health.lastCheckAt = new Date(started).toISOString();

    const results = await this.results();
    try {
      for (const [i, part] of parts.entries()) {
        if (i > 0) await scheduler.wait(GAP_BETWEEN_REQUESTS_MS);
        // A failed request keeps the part's last good answer.
        results[part] = { sightings: results[part]?.sightings ?? [], askedAt: Date.now() };
        results[part].sightings = await fetchPickup(part, config.postcode);
      }

      const previous = await this.sightings();
      const current = currentSightings(Object.values(results).map((r) => r.sightings), config.parts);
      const fresh = newSightings(previous, current);
      // Only remember sightings once someone has been told, so a failed push retries next time.
      const delivered = fresh.length === 0 || (await this.notify(stockAlert(fresh))).delivered;
      if (delivered && JSON.stringify(current) !== JSON.stringify(previous)) await this.ctx.storage.put('sightings', current);

      const canaryDue = !health.lastCanaryAt || Date.now() - Date.parse(health.lastCanaryAt) >= CANARY_INTERVAL_MS;
      if (canaryDue) await this.canary(config, health);

      if (health.unhealthyAlerted) await this.notify(healthAlert('Stock checker recovered', 'Apple is responding again.'));
      Object.assign(health, { lastSuccessAt: health.lastCheckAt, lastError: undefined, consecutiveFailures: 0, unhealthyAlerted: false });
      pace = speedUp(pace, config.intervalSeconds, Date.now());
      if (startingPace.intervalSeconds > config.intervalSeconds && pace.intervalSeconds <= config.intervalSeconds) {
        await this.notify(healthAlert('Back to full speed', `Checks are every ${config.intervalSeconds}s again.`));
      }
      console.log(JSON.stringify({ event: 'check', ok: true, parts, ms: Date.now() - started, inStock: current.length, fresh: fresh.length, delivered, canaryMisses: health.canaryMisses, intervalSeconds: pace.intervalSeconds }));
    } catch (error) {
      health.consecutiveFailures += 1;
      health.lastError = error instanceof Error ? error.message : String(error);
      console.error(JSON.stringify({ event: 'check', ok: false, parts, ms: Date.now() - started, failures: health.consecutiveFailures, error: health.lastError }));

      if (error instanceof AppleError && isPushback(error.status)) {
        pace = slowDown(pace, config.intervalSeconds, health.lastError, Date.now());
        // Tell once per episode, not on every doubling.
        if (startingPace.intervalSeconds <= config.intervalSeconds) {
          const body = `${health.lastError}, so checks are now every ${pace.intervalSeconds}s. They speed back up after 30 minutes without pushback.`;
          await this.notify(healthAlert('Checking less often', body));
        }
      }

      if (health.consecutiveFailures >= FAILURES_BEFORE_ALERT && !health.unhealthyAlerted) {
        const body = `${health.consecutiveFailures} checks in a row failed. Last error: ${health.lastError}`;
        health.unhealthyAlerted = (await this.notify(healthAlert('Stock checker is failing', body))).delivered;
      }
    } finally {
      await this.ctx.storage.put({ health, results });
      if (pace !== startingPace) await this.ctx.storage.put('pace', pace);
      this.running = false;
    }
  }

  private async canary(config: Config, health: Health) {
    await scheduler.wait(GAP_BETWEEN_REQUESTS_MS);
    const seen = (await fetchPickup(CANARY_PART, config.postcode)).length > 0;
    health.lastCanaryAt = new Date().toISOString();
    health.canaryMisses = seen ? 0 : health.canaryMisses + 1;

    if (seen && health.staleAlerted) {
      await this.notify(healthAlert('Stock results look normal again', 'Apple is returning stock data again.'));
      health.staleAlerted = false;
    } else if (!seen && health.canaryMisses >= CANARY_MISSES_BEFORE_ALERT && !health.staleAlerted) {
      const body = `Apple is answering, but even iPhone 17 shows no stock near ${config.postcode}, so "nothing in stock" may be wrong.`;
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
    return { ...DEFAULT_CONFIG, ...(await this.ctx.storage.get<Partial<Config>>('config')) };
  }

  private async health(): Promise<Health> {
    const defaults: Health = { consecutiveFailures: 0, unhealthyAlerted: false, canaryMisses: 0, staleAlerted: false };
    return { ...defaults, ...(await this.ctx.storage.get<Partial<Health>>('health')) };
  }

  private async pace(config: Config): Promise<Pace> {
    return (await this.ctx.storage.get<Pace>('pace')) ?? { intervalSeconds: config.intervalSeconds, changedAt: 0 };
  }

  private async results() {
    return (await this.ctx.storage.get<Results>('results')) ?? {};
  }

  private async devices() {
    return (await this.ctx.storage.get<Device[]>('devices')) ?? [];
  }

  private async sightings() {
    return (await this.ctx.storage.get<Sighting[]>('sightings')) ?? [];
  }
}
