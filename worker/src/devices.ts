import { type AlarmWindow, DEFAULT_ALARM, inAlarmWindow } from './alerts';
import type { ApnsDevice } from './apns';
import type { Sighting } from './apple';
import { type Area, DEFAULT_PARTS, isKnownPart, MODELS, partsToQuery } from './catalog';
import type { FcmDevice } from './fcm';

// What one phone wants to hear about. Areas are kept as the ones it has turned off, so an area added
// to the config later is on for everyone.
export interface Prefs {
  parts: string[];
  alarm: AlarmWindow;
  areasOff: string[];
  muted: boolean;
}

// A phone as stored. Ones registered by an app from before phones had their own settings have no id
// and follow the config's models and alarm.
export type Device = (ApnsDevice | FcmDevice) & { id?: string; prefs?: Prefs };

// A device as the app registers it; the Checker notes when.
export type Registration = { token: string } & ({ platform: 'ios'; environment: ApnsDevice['environment'] } | { platform: 'android' });

// A phone's settings as the app sends them, with the areas it wants rather than the ones it doesn't.
export interface PrefsPatch {
  parts?: string[];
  alarm?: AlarmWindow;
  areas?: string[];
  muted?: boolean;
}

// One model asked about in one area.
export interface Query {
  key: string;
  area: Area;
  part: string;
}

type Fallback = Pick<Prefs, 'parts' | 'alarm'>;

export const NEW_PHONE: Prefs = { parts: DEFAULT_PARTS, alarm: DEFAULT_ALARM, areasOff: [], muted: false };

export const prefsOf = (device: Device | undefined, fallback: Fallback): Prefs =>
  device?.prefs ?? { parts: fallback.parts, alarm: fallback.alarm, areasOff: [], muted: false };

// What every phone wants. With none registered, what the config says, so there's still something to check.
export const everyone = (devices: readonly Device[], fallback: Fallback) =>
  devices.length > 0 ? devices.map((d) => prefsOf(d, fallback)) : [prefsOf(undefined, fallback)];

// Whether a phone wants to hear about a sighting: one of its models, at a store in one of its areas.
export const sees = (prefs: Prefs, areas: readonly Area[]) => (s: Pick<Sighting, 'part' | 'storeNumber'>) =>
  prefs.parts.includes(s.part) && areas.some((a) => !prefs.areasOff.includes(a.search) && a.stores.includes(s.storeNumber));

// Whether any phone wants to hear about a sighting, muted or not, as a muted phone still shows stock.
export const watching = (everyone: readonly Prefs[], areas: readonly Area[]) => {
  const each = everyone.map((p) => sees(p, areas));
  return (s: Pick<Sighting, 'part' | 'storeNumber'>) => each.some((wants) => wants(s));
};

// The models any phone wants in an area, in catalogue order.
export function wantedIn(area: Area, everyone: readonly Prefs[]) {
  const wanted = new Set(everyone.filter((p) => !p.areasOff.includes(area.search)).flatMap((p) => p.parts));
  return MODELS.map((m) => m.part).filter((part) => wanted.has(part));
}

// Models take turns with areas, so each area comes round every few seconds rather than in a burst.
export function queriesFor(areas: readonly Area[], everyone: readonly Prefs[]): Query[] {
  const asking = areas.map((area) => ({ area, parts: new Set(partsToQuery(wantedIn(area, everyone))) }));
  return MODELS.flatMap(({ part }) => asking.filter((a) => a.parts.has(part)).map(({ area }) => ({ key: `${part}|${area.search}`, area, part })));
}

// Who hears about which of the new stock, and whether it rings their alarm. Muted phones hear nothing.
export function stockAlerts<D extends Device>(devices: readonly D[], fresh: readonly Sighting[], fallback: Fallback, areas: readonly Area[], now: Date) {
  return devices.flatMap((device) => {
    const prefs = prefsOf(device, fallback);
    const sightings = prefs.muted ? [] : fresh.filter(sees(prefs, areas));
    return sightings.length > 0 ? [{ device, sightings, alarm: inAlarmWindow(prefs.alarm, now) }] : [];
  });
}

// Adds or replaces a phone, found by its id or else its token. A phone keeps its settings when it
// registers again. One registered before it had an id starts from the config's models and alarm, which
// were its own until now, and a new one starts from the defaults. An app without ids leaves them alone.
export function register(devices: readonly Device[], registration: Registration, id: string | undefined, at: string, fallback: Fallback): Device[] {
  const existing = (id && devices.find((d) => d.id === id)) || devices.find((d) => d.token === registration.token);
  const prefs = existing?.prefs ?? (id ? (existing ? prefsOf(existing, fallback) : NEW_PHONE) : undefined);
  const others = devices.filter((d) => d !== existing && d.token !== registration.token && !(id && d.id === id));
  return [...others, { ...registration, ...(id ? { id } : {}), ...(prefs ? { prefs } : {}), registeredAt: at }];
}

// Applies the app's changes to a phone's settings. Returns an error for an area that isn't configured.
export function applyPatch(prefs: Prefs, patch: PrefsPatch, areas: readonly Area[]): Prefs | string {
  const searches = areas.map((a) => a.search);
  if (patch.areas?.some((s) => !searches.includes(s))) return `areas must be searches of the configured areas: ${searches.join(', ')}`;
  const { areas: on, ...rest } = patch;
  return { ...prefs, ...rest, ...(on && { areasOff: searches.filter((s) => !on.includes(s)) }) };
}

export function parseAlarm(alarm: unknown): AlarmWindow | string {
  const { enabled, from, to } = (alarm ?? {}) as Record<string, unknown>;
  const hour = (h: unknown): h is number => Number.isInteger(h) && (h as number) >= 0 && (h as number) <= 24;
  if (typeof enabled !== 'boolean' || !hour(from) || !hour(to) || from >= to) {
    return 'alarm must be { enabled, from, to }, with from and to whole UK hours and from before to';
  }
  return { enabled, from, to };
}

export const parseParts = (parts: unknown) =>
  Array.isArray(parts) && parts.every((p) => typeof p === 'string' && isKnownPart(p)) ? [...new Set(parts as string[])] : 'parts must be a list of known iPhone Duo part numbers';

export function parsePrefsPatch(body: unknown): PrefsPatch | string {
  if (typeof body !== 'object' || body === null) return 'Expected a JSON object';
  const { parts, alarm, areas, muted } = body as Record<string, unknown>;
  const patch: PrefsPatch = {};

  if (parts !== undefined) {
    const parsed = parseParts(parts);
    if (typeof parsed === 'string') return parsed;
    patch.parts = parsed;
  }
  if (alarm !== undefined) {
    const parsed = parseAlarm(alarm);
    if (typeof parsed === 'string') return parsed;
    patch.alarm = parsed;
  }
  if (areas !== undefined) {
    if (!Array.isArray(areas) || !areas.every((a) => typeof a === 'string')) return 'areas must be a list of area searches';
    patch.areas = [...new Set(areas as string[])];
  }
  if (muted !== undefined) {
    if (typeof muted !== 'boolean') return 'muted must be true or false';
    patch.muted = muted;
  }
  return patch;
}
