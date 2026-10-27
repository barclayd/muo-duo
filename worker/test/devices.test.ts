import { describe, expect, test } from 'bun:test';
import type { Sighting } from '../src/apple';
import type { Area } from '../src/catalog';
import { applyPatch, type Device, everyone, NEW_PHONE, parsePrefsPatch, type Prefs, queriesFor, register, sees, stockAlerts, wantedIn, watching } from '../src/devices';

const london: Area = { name: 'London', search: 'NW6 5PU', stores: ['R092', 'R226'] };
const manchester: Area = { name: 'Manchester', search: 'Manchester', stores: ['R215'] };
const areas = [london, manchester];

const white256 = 'MK2D4QN/A';
const black256 = 'MK2E4QN/A';
const white1tb = 'MK2H4QN/A';

const config = { parts: [white256, black256], alarm: { enabled: true, from: 6, to: 8 } };
const prefs = (changes: Partial<Prefs>): Prefs => ({ ...NEW_PHONE, ...changes });

const whiteCity: Sighting = { part: white256, storeNumber: 'R226', storeName: 'White City', quote: 'Available Today' };
const arndale: Sighting = { ...whiteCity, storeNumber: 'R215', storeName: 'Manchester Arndale' };
const whiteCity1tb: Sighting = { ...whiteCity, part: white1tb };

const iphone = (token: string, extra: Partial<Device> = {}): Device => ({ platform: 'ios', token, environment: 'production', registeredAt: 't0', ...extra }) as Device;

describe('everyone', () => {
  test("follows the config for phones from before they had their own settings, and when there are none", () => {
    expect(everyone([], config)).toEqual([{ ...config, areasOff: [], muted: false }]);
    expect(everyone([iphone('a')], config)).toEqual([{ ...config, areasOff: [], muted: false }]);
    expect(everyone([iphone('a', { prefs: prefs({ parts: [white1tb] }) })], config)[0]!.parts).toEqual([white1tb]);
  });
});

describe('queriesFor', () => {
  test('asks each area about every colour of the models anyone wants there, taking turns with areas', () => {
    const queries = queriesFor(areas, [prefs({ parts: [white256] })]);
    expect(queries.map((q) => q.key)).toEqual([`${white256}|NW6 5PU`, `${white256}|Manchester`, `${black256}|NW6 5PU`, `${black256}|Manchester`]);
  });

  test("leaves out an area for models only wanted by phones that have turned it off", () => {
    const londonOnly = prefs({ parts: [white1tb], areasOff: ['Manchester'] });
    const keys = queriesFor(areas, [prefs({ parts: [white256] }), londonOnly]).map((q) => q.key);
    expect(keys.filter((k) => k.endsWith('|Manchester'))).toEqual([`${white256}|Manchester`, `${black256}|Manchester`]);
    expect(keys.filter((k) => k.endsWith('|NW6 5PU'))).toHaveLength(4);
  });

  test('asks nothing about an area nobody wants', () => {
    expect(queriesFor(areas, [prefs({ areasOff: ['NW6 5PU', 'Manchester'] })])).toEqual([]);
    expect(wantedIn(london, [prefs({ parts: [] })])).toEqual([]);
  });
});

describe('sees and watching', () => {
  test('a phone sees its models at stores in its areas', () => {
    const sees256InLondon = sees(prefs({ parts: [white256], areasOff: ['Manchester'] }), areas);
    expect(sees256InLondon(whiteCity)).toBe(true);
    expect(sees256InLondon(arndale)).toBe(false);
    expect(sees256InLondon(whiteCity1tb)).toBe(false);
  });

  test('stock is watched if any phone wants it, muted or not', () => {
    const watched = watching([prefs({ parts: [white256], areasOff: ['Manchester'] }), prefs({ parts: [white1tb], muted: true })], areas);
    expect([whiteCity, arndale, whiteCity1tb].map(watched)).toEqual([true, false, true]);
  });
});

describe('stockAlerts', () => {
  const morning = new Date('2026-12-01T06:30:00Z');

  test("tells each phone only about its own models and areas, with its own alarm hours", () => {
    const mine = iphone('a', { prefs: prefs({ parts: [white256] }) });
    const friends = iphone('b', { prefs: prefs({ parts: [white256, white1tb], areasOff: ['Manchester'], alarm: { enabled: false, from: 6, to: 8 } }) });
    const alerts = stockAlerts([mine, friends], [whiteCity, arndale, whiteCity1tb], config, areas, morning);
    expect(alerts).toEqual([
      { device: mine, sightings: [whiteCity, arndale], alarm: true },
      { device: friends, sightings: [whiteCity, whiteCity1tb], alarm: false },
    ]);
  });

  test('skips muted phones and ones that want none of it', () => {
    const muted = iphone('a', { prefs: prefs({ muted: true }) });
    const other = iphone('b', { prefs: prefs({ parts: [white1tb] }) });
    expect(stockAlerts([muted, other], [whiteCity], config, areas, morning)).toEqual([]);
  });

  test('uses the config for phones from before they had their own settings', () => {
    const [alert] = stockAlerts([iphone('a')], [whiteCity], config, areas, morning);
    expect(alert?.alarm).toBe(true);
  });
});

describe('register', () => {
  const id = '0b7e0f6c-3c1e-4c1a-9a51-8d6e5c1f2a3b';
  const registration = { platform: 'ios' as const, token: 'aa', environment: 'production' as const };

  test('starts a new phone from the defaults', () => {
    expect(register([], registration, id, 't1', config)).toEqual([{ ...registration, id, prefs: NEW_PHONE, registeredAt: 't1' }]);
  });

  test("starts a phone from before ids from the config's models and alarm, which were its own", () => {
    const [device] = register([iphone('aa')], registration, id, 't1', config);
    expect(device?.prefs).toEqual({ ...config, areasOff: [], muted: false });
    expect(device?.id).toBe(id);
  });

  test('keeps settings when a phone registers again with a new token', () => {
    const mine = prefs({ parts: [white1tb], muted: true });
    const devices = register([iphone('old', { id, prefs: mine }), iphone('other')], registration, id, 't1', config);
    expect(devices.map((d) => d.token)).toEqual(['other', 'aa']);
    expect(devices[1]?.prefs).toEqual(mine);
  });

  test('replaces a phone by token for apps without ids, leaving its settings alone', () => {
    expect(register([iphone('aa')], registration, undefined, 't1', config)).toEqual([{ ...registration, registeredAt: 't1' }]);
    const mine = prefs({ parts: [white1tb] });
    expect(register([iphone('aa', { id, prefs: mine })], registration, undefined, 't1', config)[0]?.prefs).toEqual(mine);
  });

  test('drops another record holding the same token', () => {
    const devices = register([iphone('old', { id, prefs: NEW_PHONE }), iphone('aa')], registration, id, 't1', config);
    expect(devices).toHaveLength(1);
  });
});

describe('applyPatch', () => {
  test('turns the areas a phone wants into the ones it has turned off', () => {
    expect(applyPatch(NEW_PHONE, { areas: ['Manchester'] }, areas)).toEqual({ ...NEW_PHONE, areasOff: ['NW6 5PU'] });
    expect(applyPatch(NEW_PHONE, { areas: [] }, areas)).toEqual({ ...NEW_PHONE, areasOff: ['NW6 5PU', 'Manchester'] });
  });

  test('keeps what the patch leaves out', () => {
    const mine = prefs({ areasOff: ['Manchester'] });
    expect(applyPatch(mine, { muted: true }, areas)).toEqual({ ...mine, muted: true });
  });

  test('refuses areas that are not configured', () => {
    expect(applyPatch(NEW_PHONE, { areas: ['Leeds'] }, areas)).toBeTypeOf('string');
  });
});

describe('parsePrefsPatch', () => {
  test('accepts known models, an alarm, area searches and mute', () => {
    const body = { parts: [white256, white256], alarm: { enabled: false, from: 7, to: 9 }, areas: ['Manchester'], muted: true };
    expect(parsePrefsPatch(body)).toEqual({ parts: [white256], alarm: { enabled: false, from: 7, to: 9 }, areas: ['Manchester'], muted: true });
    expect(parsePrefsPatch({})).toEqual({});
  });

  test('refuses anything else', () => {
    for (const body of [null, 'x', { parts: ['MG6N4QN/A'] }, { alarm: { enabled: true, from: 9, to: 7 } }, { areas: [1] }, { muted: 'yes' }]) {
      expect(parsePrefsPatch(body)).toBeTypeOf('string');
    }
  });
});
