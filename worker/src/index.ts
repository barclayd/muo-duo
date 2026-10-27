import type { AlarmWindow } from './alerts';
import { type Area, isKnownPart } from './catalog';
import type { Config } from './checker';

export { Checker } from './checker';

const json = (data: unknown, status = 200) => Response.json(data, { status, headers: { 'cache-control': 'no-store' } });

const checker = (env: Env) => env.CHECKER.get(env.CHECKER.idFromName('default'));

function authorised(req: Request, env: Env) {
  if (!env.API_TOKEN) return false;
  const given = new TextEncoder().encode(req.headers.get('authorization') ?? '');
  const expected = new TextEncoder().encode(`Bearer ${env.API_TOKEN}`);
  return given.byteLength === expected.byteLength && crypto.subtle.timingSafeEqual(given, expected);
}

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

function parseAlarm(alarm: unknown): AlarmWindow | string {
  const { enabled, from, to } = (alarm ?? {}) as Record<string, unknown>;
  const hour = (h: unknown): h is number => Number.isInteger(h) && (h as number) >= 0 && (h as number) <= 24;
  if (typeof enabled !== 'boolean' || !hour(from) || !hour(to) || from >= to) {
    return 'alarm must be { enabled, from, to }, with from and to whole UK hours and from before to';
  }
  return { enabled, from, to };
}

function parseConfigPatch(body: unknown): Partial<Config> | string {
  if (typeof body !== 'object' || body === null) return 'Expected a JSON object';
  const { parts, areas, intervalSeconds, paused, alarm } = body as Record<string, unknown>;
  const patch: Partial<Config> = {};

  if (parts !== undefined) {
    if (!Array.isArray(parts) || !parts.every((p) => typeof p === 'string' && isKnownPart(p))) {
      return 'parts must be a list of known iPhone Duo part numbers';
    }
    patch.parts = [...new Set(parts as string[])];
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
    if (!authorised(req, env)) return json({ error: 'Unauthorised' }, 401);

    const stub = checker(env);
    switch (`${req.method} ${new URL(req.url).pathname}`) {
      case 'GET /status':
        return json(await stub.status());

      case 'POST /devices': {
        const { token, environment } = (await req.json().catch(() => ({}))) as Record<string, unknown>;
        if (typeof token !== 'string' || !/^[0-9a-f]{64,200}$/i.test(token)) return json({ error: 'token must be a hex APNs device token' }, 400);
        if (environment !== 'sandbox' && environment !== 'production') return json({ error: 'environment must be sandbox or production' }, 400);
        await stub.registerDevice(token.toLowerCase(), environment);
        return json(await stub.status());
      }

      case 'PUT /config': {
        const patch = parseConfigPatch(await req.json().catch(() => null));
        if (typeof patch === 'string') return json({ error: patch }, 400);
        // Apple is asked about any new area first, so a search that finds the wrong stores is refused.
        const error = await stub.updateConfig(patch);
        if (error) return json({ error }, 400);
        return json(await stub.status());
      }

      case 'POST /check':
        return json(await stub.checkNow());

      case 'POST /test-notification': {
        const { alarm = false, delaySeconds = 0 } = (await req.json().catch(() => ({}))) as Record<string, unknown>;
        if (typeof alarm !== 'boolean') return json({ error: 'alarm must be true or false' }, 400);
        if (!Number.isInteger(delaySeconds) || (delaySeconds as number) < 0 || (delaySeconds as number) > 20) {
          return json({ error: 'delaySeconds must be a whole number between 0 and 20' }, 400);
        }
        if (delaySeconds === 0) return json(await stub.sendTest(alarm));
        // Sent after answering, so there's time to lock the phone first.
        ctx.waitUntil(scheduler.wait((delaySeconds as number) * 1000).then(() => stub.sendTest(alarm)));
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
