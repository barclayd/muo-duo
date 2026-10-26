import { isKnownPart } from './catalog';
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

function parseConfigPatch(body: unknown): Partial<Config> | string {
  if (typeof body !== 'object' || body === null) return 'Expected a JSON object';
  const { parts, postcode, intervalSeconds, paused } = body as Record<string, unknown>;
  const patch: Partial<Config> = {};

  if (parts !== undefined) {
    if (!Array.isArray(parts) || !parts.every((p) => typeof p === 'string' && isKnownPart(p))) {
      return 'parts must be a list of known iPhone Duo part numbers';
    }
    patch.parts = [...new Set(parts as string[])];
  }
  if (postcode !== undefined) {
    if (typeof postcode !== 'string' || !/^[A-Z0-9 ]{5,8}$/i.test(postcode.trim())) return 'postcode is not a UK postcode';
    patch.postcode = postcode.trim().toUpperCase();
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
  return patch;
}

export default {
  async fetch(req, env) {
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
        await stub.updateConfig(patch);
        return json(await stub.status());
      }

      case 'POST /check':
        return json(await stub.checkNow());

      case 'POST /test-notification':
        return json(await stub.sendTest());

      default:
        return json({ error: 'Not found' }, 404);
    }
  },

  async scheduled(_controller, env) {
    await checker(env).ensureRunning();
  },
} satisfies ExportedHandler<Env>;
