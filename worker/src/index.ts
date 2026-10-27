import type { Area } from './catalog';
import type { Config } from './checker';
import { parseAlarm, parseParts, parsePrefsPatch } from './devices';

export { Checker } from './checker';

const json = (data: unknown, status = 200) => Response.json(data, { status, headers: { 'cache-control': 'no-store' } });

const checker = (env: Env) => env.CHECKER.get(env.CHECKER.idFromName('default'));

function bearer(req: Request, token: string | undefined) {
  if (!token) return false;
  const given = new TextEncoder().encode(req.headers.get('authorization') ?? '');
  const expected = new TextEncoder().encode(`Bearer ${token}`);
  return given.byteLength === expected.byteLength && crypto.subtle.timingSafeEqual(given, expected);
}

// The owner's key does everything. A friend's can see the status, register its phone, change that
// phone's settings and test it, but pausing, the areas and checking on demand stay with the owner.
type Role = 'owner' | 'friend';

const roleOf = (req: Request, env: Env): Role | undefined => (bearer(req, env.API_TOKEN) ? 'owner' : bearer(req, env.FRIEND_API_TOKEN) ? 'friend' : undefined);

// Each install of the app makes up its own id and sends it with every request.
const DEVICE_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const MAX_AREAS = 6;

const shortText = (value: unknown) => (typeof value === 'string' && /^.{1,40}$/.test(value.trim()) ? value.trim() : undefined);

// Apple lists the 12 stores nearest a search, so an area can watch at most 12.
function parseAreas(areas: unknown): Area[] | string {
  const invalid = `areas must be a list of 1 to ${MAX_AREAS} { name, search, stores }, with up to 12 store numbers like R092`;
  if (!Array.isArray(areas) || areas.length === 0 || areas.length > MAX_AREAS) return invalid;
  const parsed: Area[] = [];
  for (const area of areas) {
    const { name, search, stores } = (area ?? {}) as Record<string, unknown>;
    const storesOk = Array.isArray(stores) && stores.length > 0 && stores.length <= 12 && stores.every((s) => typeof s === 'string' && /^R\d{3,4}$/.test(s));
    const [n, s] = [shortText(name), shortText(search)];
    if (!n || !s || !storesOk) return invalid;
    parsed.push({ name: n, search: s, stores: [...new Set(stores as string[])] });
  }
  if (new Set(parsed.map((a) => a.search.toUpperCase())).size < parsed.length) return 'each area needs its own search';
  return parsed;
}

function parseConfigPatch(body: unknown): Partial<Config> | string {
  if (typeof body !== 'object' || body === null) return 'Expected a JSON object';
  const { parts, areas, intervalSeconds, paused, alarm } = body as Record<string, unknown>;
  const patch: Partial<Config> = {};

  if (parts !== undefined) {
    const parsed = parseParts(parts);
    if (typeof parsed === 'string') return parsed;
    patch.parts = parsed;
  }
  if (areas !== undefined) {
    const parsed = parseAreas(areas);
    if (typeof parsed === 'string') return parsed;
    patch.areas = parsed;
  }
  if (intervalSeconds !== undefined) {
    if (!Number.isInteger(intervalSeconds) || (intervalSeconds as number) < 10 || (intervalSeconds as number) > 3600) {
      return 'intervalSeconds must be a whole number between 10 and 3600';
    }
    patch.intervalSeconds = intervalSeconds as number;
  }
  if (paused !== undefined) {
    if (typeof paused !== 'boolean') return 'paused must be true or false';
    patch.paused = paused;
  }
  if (alarm !== undefined) {
    const parsed = parseAlarm(alarm);
    if (typeof parsed === 'string') return parsed;
    patch.alarm = parsed;
  }
  return patch;
}

export default {
  async fetch(req, env, ctx) {
    const role = roleOf(req, env);
    if (!role) return json({ error: 'Unauthorised' }, 401);
    const header = req.headers.get('x-device-id');
    if (header !== null && !DEVICE_ID.test(header)) return json({ error: 'x-device-id must be a UUID' }, 400);
    const deviceId = header?.toLowerCase();
    const route = `${req.method} ${new URL(req.url).pathname}`;
    if (role === 'friend' && (route === 'PUT /config' || route === 'POST /check')) return json({ error: 'Only the owner can do that' }, 403);
    // Without ids, phones can't be told apart, so a friend's app has to send one.
    if (role === 'friend' && !deviceId && route !== 'GET /status') return json({ error: 'x-device-id is required' }, 400);

    const stub = checker(env);
    // As the phone asking sees it.
    const status = async () => json({ ...(await stub.status(deviceId)), role });
    switch (route) {
      case 'GET /status':
        return status();

      case 'POST /devices': {
        // The iPhone app sends no platform.
        const { token, platform = 'ios', environment } = (await req.json().catch(() => ({}))) as Record<string, unknown>;
        if (platform === 'android') {
          if (typeof token !== 'string' || !/^[\w:-]{20,4096}$/.test(token)) return json({ error: 'token must be an FCM registration token' }, 400);
          await stub.registerDevice({ platform, token }, deviceId);
          return status();
        }
        if (platform !== 'ios') return json({ error: 'platform must be ios or android' }, 400);
        if (typeof token !== 'string' || !/^[0-9a-f]{64,200}$/i.test(token)) return json({ error: 'token must be a hex APNs device token' }, 400);
        if (environment !== 'sandbox' && environment !== 'production') return json({ error: 'environment must be sandbox or production' }, 400);
        await stub.registerDevice({ platform, token: token.toLowerCase(), environment }, deviceId);
        return status();
      }

      // This phone's own models, alarm, areas and mute.
      case 'PUT /device': {
        if (!deviceId) return json({ error: 'x-device-id is required' }, 400);
        const patch = parsePrefsPatch(await req.json().catch(() => null));
        if (typeof patch === 'string') return json({ error: patch }, 400);
        const failure = await stub.updateDevice(deviceId, patch);
        if (failure) return json({ error: failure.error }, failure.status);
        return status();
      }

      case 'PUT /config': {
        const patch = parseConfigPatch(await req.json().catch(() => null));
        if (typeof patch === 'string') return json({ error: patch }, 400);
        // Apple is asked about any new area first, so a search that finds the wrong stores is refused.
        const error = await stub.updateConfig(patch);
        if (error) return json({ error }, 400);
        return status();
      }

      case 'POST /check':
        return json({ ...(await stub.checkNow(deviceId)), role });

      case 'POST /test-notification': {
        const { alarm = false, delaySeconds = 0 } = (await req.json().catch(() => ({}))) as Record<string, unknown>;
        if (typeof alarm !== 'boolean') return json({ error: 'alarm must be true or false' }, 400);
        if (!Number.isInteger(delaySeconds) || (delaySeconds as number) < 0 || (delaySeconds as number) > 20) {
          return json({ error: 'delaySeconds must be a whole number between 0 and 20' }, 400);
        }
        if (delaySeconds === 0) return json(await stub.sendTest(alarm, deviceId));
        // Sent after answering, so there's time to lock the phone first.
        ctx.waitUntil(scheduler.wait((delaySeconds as number) * 1000).then(() => stub.sendTest(alarm, deviceId)));
        return json({ delivered: false, errors: [], sendsAt: new Date(Date.now() + (delaySeconds as number) * 1000).toISOString() }, 202);
      }

      default:
        return json({ error: 'Not found' }, 404);
    }
  },

  async scheduled(_controller, env) {
    await checker(env).ensureRunning();
  },
} satisfies ExportedHandler<Env>;
