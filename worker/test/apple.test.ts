import { afterEach, describe, expect, mock, spyOn, test } from 'bun:test';
import { AppleError, fetchStores, forToday, parsePickup } from '../src/apple';
import fixture from './fixtures/pickup-iphone-17.json';
import stores from './fixtures/stores-iphone-17.json';

describe('parsePickup', () => {
  test('returns the stores Apple listed', () => {
    expect(parsePickup(fixture).stores[0]).toEqual({ number: 'R226', name: 'White City', city: 'London', latitude: 51.5074, longitude: -0.22094 });
    expect(parsePickup(fixture).stores.map((s) => s.number)).toEqual(['R226', 'R329', 'R092']);
  });

  test('returns one sighting per available part per store', () => {
    expect(parsePickup(fixture).sightings).toEqual([
      { part: 'MG6M4QN/A', storeNumber: 'R226', storeName: 'White City', quote: 'Available Today' },
      { part: 'MG6N4QN/A', storeNumber: 'R226', storeName: 'White City', quote: 'Available Today' },
      { part: 'MG6K4QN/A', storeNumber: 'R226', storeName: 'White City', quote: 'Available Today' },
    ]);
  });

  test('ignores parts that are listed but not available', () => {
    const json = structuredClone(fixture);
    json.body.PickupMessage.stores[0]!.partsAvailability['MG6M4QN/A']!.pickupDisplay = 'unavailable';
    expect(parsePickup(json).sightings.map((s) => s.part)).toEqual(['MG6N4QN/A', 'MG6K4QN/A']);
  });

  test('returns nothing when no store has stock', () => {
    const json = structuredClone(fixture);
    for (const store of json.body.PickupMessage.stores) store.partsAvailability = {} as never;
    expect(parsePickup(json).sightings).toEqual([]);
  });

  test('throws on an unexpected shape so a changed API counts as a failed check', () => {
    expect(() => parsePickup({ body: {} })).toThrow(AppleError);
  });

  test('throws when Apple lists no stores, rather than reporting no stock', () => {
    expect(() => parsePickup({ body: { PickupMessage: { stores: [] } } })).toThrow(AppleError);
  });

  test("passes on Apple's reason when it can't find the location", () => {
    const json = { body: { PickupMessage: { stores: [], errorMessage: 'There is no store associated with this search.' } } };
    expect(() => parsePickup(json)).toThrow('Apple found no stores: There is no store associated with this search.');
  });
});

describe('parsePickup, for the store-by-store endpoint', () => {
  test('reads every store, each with every part asked about', () => {
    const pickup = parsePickup(stores);
    expect(pickup.stores.map((s) => s.number)).toEqual(['R226', 'R329', 'R092']);
    expect(pickup.sightings.filter((s) => s.storeNumber === 'R329')).toEqual([
      { part: 'MG6N4QN/A', storeNumber: 'R329', storeName: 'Brompton Road', quote: 'Available Tomorrow' },
      { part: 'MG6K4QN/A', storeNumber: 'R329', storeName: 'Brompton Road', quote: 'Available Tomorrow' },
    ]);
  });
});

describe('forToday', () => {
  const sighting = { part: 'MK2D4QN/A', storeNumber: 'R226', storeName: 'White City' };

  test('counts stock to collect today only', () => {
    expect(forToday({ ...sighting, quote: 'Available Today' })).toBe(true);
    expect(forToday({ ...sighting, quote: 'Available Tomorrow' })).toBe(false);
    expect(forToday({ ...sighting, quote: 'Available Wed 28/10' })).toBe(false);
  });
});

describe('fetchStores', () => {
  afterEach(() => mock.restore());

  test('asks about every part near the location in one request', async () => {
    const fetch = spyOn(globalThis, 'fetch').mockResolvedValue(Response.json(stores));
    await fetchStores(['MK2D4QN/A', 'MK2E4QN/A'], 'NW6 5PU');
    const url = new URL(String(fetch.mock.calls[0]![0]));
    expect(url.origin + url.pathname).toBe('https://www.apple.com/uk/shop/retail/pickup-message');
    expect(Object.fromEntries(url.searchParams)).toEqual({ pl: 'true', 'mts.0': 'regular', location: 'NW6 5PU', 'parts.0': 'MK2D4QN/A', 'parts.1': 'MK2E4QN/A' });
  });

  test("passes on Apple's status, so pushback can be told apart", async () => {
    spyOn(globalThis, 'fetch').mockResolvedValue(new Response('', { status: 541 }));
    expect(fetchStores(['MK2D4QN/A'], 'NW6 5PU')).rejects.toMatchObject({ status: 541, message: 'Apple returned HTTP 541 for every store near NW6 5PU' });
  });
});
