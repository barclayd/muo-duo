import { describe, expect, test } from 'bun:test';
import { currentSightings, inAlarmWindow, newSightings, stockAlert } from '../src/alerts';
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

describe('currentSightings', () => {
  test('combines answers, keeping only watched parts and each part/store once', () => {
    const blackAnswer = [whiteCity256, regentStreet256];
    const whiteAnswer = [whiteCity256, { ...whiteCity256, part: 'MK2D4QN/A' }];
    expect(currentSightings([blackAnswer, whiteAnswer], ['MK2E4QN/A'])).toEqual([whiteCity256, regentStreet256]);
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

  test('only goes through the app to ring an alarm when asked to', () => {
    const quiet = stockAlert([whiteCity256]);
    expect(quiet.aps).not.toHaveProperty('mutable-content');
    expect(quiet).not.toHaveProperty('alarm');
    const ringing = stockAlert([whiteCity256], true);
    expect(ringing.aps['mutable-content']).toBe(1);
    expect(ringing.alarm).toBe(true);
  });
});

describe('inAlarmWindow', () => {
  const sixToEight = { enabled: true, from: 6, to: 8 };

  test('uses UK hours, which are an hour ahead of UTC in summer', () => {
    expect(inAlarmWindow(sixToEight, new Date('2026-07-01T04:59:00Z'))).toBe(false);
    expect(inAlarmWindow(sixToEight, new Date('2026-07-01T05:00:00Z'))).toBe(true);
    expect(inAlarmWindow(sixToEight, new Date('2026-07-01T06:59:00Z'))).toBe(true);
    expect(inAlarmWindow(sixToEight, new Date('2026-07-01T07:00:00Z'))).toBe(false);
  });

  test('and match UTC in winter', () => {
    expect(inAlarmWindow(sixToEight, new Date('2026-12-01T05:59:00Z'))).toBe(false);
    expect(inAlarmWindow(sixToEight, new Date('2026-12-01T06:00:00Z'))).toBe(true);
    expect(inAlarmWindow(sixToEight, new Date('2026-12-01T08:00:00Z'))).toBe(false);
  });

  test('never rings when turned off', () => {
    expect(inAlarmWindow({ ...sixToEight, enabled: false }, new Date('2026-12-01T07:00:00Z'))).toBe(false);
  });
});
