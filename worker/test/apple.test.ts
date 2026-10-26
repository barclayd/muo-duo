import { describe, expect, test } from 'bun:test';
import { AppleError, parsePickup } from '../src/apple';
import fixture from './fixtures/pickup-iphone-17.json';

describe('parsePickup', () => {
  test('returns one sighting per available part per store', () => {
    expect(parsePickup(fixture)).toEqual([
      { part: 'MG6M4QN/A', storeNumber: 'R226', storeName: 'White City', quote: 'Available Today' },
      { part: 'MG6N4QN/A', storeNumber: 'R226', storeName: 'White City', quote: 'Available Today' },
      { part: 'MG6K4QN/A', storeNumber: 'R226', storeName: 'White City', quote: 'Available Today' },
    ]);
  });

  test('ignores parts that are listed but not available', () => {
    const json = structuredClone(fixture);
    json.body.PickupMessage.stores[0]!.partsAvailability['MG6M4QN/A']!.pickupDisplay = 'unavailable';
    expect(parsePickup(json).map((s) => s.part)).toEqual(['MG6N4QN/A', 'MG6K4QN/A']);
  });

  test('returns nothing when no store has stock', () => {
    const json = structuredClone(fixture);
    for (const store of json.body.PickupMessage.stores) store.partsAvailability = {} as never;
    expect(parsePickup(json)).toEqual([]);
  });

  test('throws on an unexpected shape so a changed API counts as a failed check', () => {
    expect(() => parsePickup({ body: {} })).toThrow(AppleError);
  });
});
