import { DurableObject } from 'cloudflare:workers';
import { healthAlert, newSightings, sightingKey, stockAlert } from './alerts';
import { Apns, type ApnsResult, type Device } from './apns';
import { fetchPickup, type Sighting } from './apple';
import { DEFAULT_PARTS, MODELS, partsToQuery, productUrl } from './catalog';

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

const DEFAULT_CONFIG: Config = { postcode: 'NW6 5PU', parts: DEFAULT_PARTS, intervalSeconds: 60, paused: false };
const FAILURES_BEFORE_ALERT = 3;
const MAX_BACKOFF_SECONDS = 15 * 60;
const GAP_BETWEEN_REQUESTS_MS = 2_000;

// iPhone 17 256GB is reliably in stock around London. If even it shows nothing anywhere, Apple is
// probably serving empty results, which would otherwise look exactly like "no Duo in stock".
const CANARY_PART = 'MG6N4QN/A';
const CANARY_INTERVAL_MS = 10 * 60_000;
const CANARY_MISSES_BEFORE_ALERT = 3;

// Single instance that polls Apple on a self-rescheduling alarm and pushes to registered devices.
export class Checker extends DurableObject<Env> {
  private running = false;
  private apns?: Apns;

  async status() {
    const alarm = await this.ctx.storage.getAlarm();
    const health = await this.health();
    return {
      config: await this.config(),
      health,
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
    await this.ctx.storage.put('config', config);
    if (config.paused) await this.ctx.storage.deleteAlarm();
    else await this.ensureRunning();
  }

  async checkNow() {
    await this.runCycle();
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

    await this.runCycle();

    const { consecutiveFailures } = await this.health();
    const backoff = Math.min(config.intervalSeconds * 2 ** consecutiveFailures, MAX_BACKOFF_SECONDS);
    const delaySeconds = Math.max(config.intervalSeconds, backoff) * (0.9 + Math.random() * 0.2);
    await this.ctx.storage.setAlarm(Date.now() + delaySeconds * 1000);
  }

  private async runCycle() {
    if (this.running) return;
    this.running = true;

    const config = await this.config();
    const health = await this.health();
    const started = Date.now();
    health.lastCheckAt = new Date(started).toISOString();

    try {
      const current = await this.check(config);
      const fresh = newSightings(await this.sightings(), current);
      // Only remember sightings once someone has been told, so a failed push retries next cycle.
      const delivered = fresh.length === 0 || (await this.notify(stockAlert(fresh))).delivered;
      if (delivered) await this.ctx.storage.put('sightings', current);

      const canaryDue = !health.lastCanaryAt || Date.now() - Date.parse(health.lastCanaryAt) >= CANARY_INTERVAL_MS;
      if (canaryDue) await this.canary(config, health);

      if (health.unhealthyAlerted) await this.notify(healthAlert('Stock checker recovered', 'Apple is responding again.'));
      Object.assign(health, { lastSuccessAt: health.lastCheckAt, lastError: undefined, consecutiveFailures: 0, unhealthyAlerted: false });
      console.log(JSON.stringify({ event: 'check', ok: true, ms: Date.now() - started, inStock: current.length, fresh: fresh.length, delivered, canaryMisses: health.canaryMisses }));
    } catch (error) {
      health.consecutiveFailures += 1;
      health.lastError = error instanceof Error ? error.message : String(error);
      console.error(JSON.stringify({ event: 'check', ok: false, ms: Date.now() - started, failures: health.consecutiveFailures, error: health.lastError }));

      if (health.consecutiveFailures >= FAILURES_BEFORE_ALERT && !health.unhealthyAlerted) {
        const body = `${health.consecutiveFailures} checks in a row failed. Last error: ${health.lastError}`;
        health.unhealthyAlerted = (await this.notify(healthAlert('Stock checker is failing', body))).delivered;
      }
    } finally {
      await this.ctx.storage.put('health', health);
      this.running = false;
    }
  }

  private async check(config: Config) {
    const watched = new Set(config.parts);
    const found = new Map<string, Sighting>();

    for (const [i, part] of partsToQuery(config.parts).entries()) {
      if (i > 0) await scheduler.wait(GAP_BETWEEN_REQUESTS_MS);
      for (const s of await fetchPickup(part, config.postcode)) {
        if (watched.has(s.part)) found.set(sightingKey(s), s);
      }
    }
    return [...found.values()];
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

  private async devices() {
    return (await this.ctx.storage.get<Device[]>('devices')) ?? [];
  }

  private async sightings() {
    return (await this.ctx.storage.get<Sighting[]>('sightings')) ?? [];
  }
}
