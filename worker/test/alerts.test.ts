import { describe, expect, test } from 'bun:test';
import { newSightings, stockAlert } from '../src/alerts';
import type { Sighting } from '../src/apple';

const whiteCity256: Sighting = { part: 'MK2E4QN/A', storeNumber: 'R226', storeName: 'White City', quote: 'Available Today' };
const regentStreet256: Sighting = { ...whiteCity256, storeNumber: 'R092', storeName: 'Regent Street' };
const whiteCity512: Sighting = { ...whiteCity256, part: 'MK2F4QN/A' };

describe('newSightings', () => {
  test('returns only part/store pairs not seen last time', () => {
    expect(newSightings([whiteCity256], [whiteCity256, regentStreet256, whiteCity512])).toEqual([regentStreet256, whiteCity512]);
  });

  test('treats a restock after selling out as new', () => {
    const soldOut: Sighting[] = [];
    expect(newSightings(soldOut, [whiteCity256])).toEqual([whiteCity256]);
  });
});

describe('stockAlert', () => {
  test('groups stores by model and links to the first model', () => {
    const alert = stockAlert([whiteCity256, regentStreet256, whiteCity512]);
    expect(alert.aps.alert).toEqual({
      title: 'iPhone Duo in stock',
      body: '256GB Night Sky: White City, Regent Street\n512GB Star White: White City',
    });
    expect(alert.aps['interruption-level']).toBe('time-sensitive');
    expect(alert.url).toBe('https://www.apple.com/uk/shop/buy-iphone/iphone-duo/7.6-inch-display-256gb-night-sky');
  });
});
