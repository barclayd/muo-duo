import { describe, expect, test } from 'bun:test';
import type { Sighting } from '../src/apple';
import { answered, asked, CONFIRM_GAP_MS, confirmDue, emptySweeps, failed, outdate, SWEEP_EVERY_MS, SWEEP_TRUSTED_MS, standing, sweepDue } from '../src/sweep';

const london = { search: 'NW6 5PU' };
const south = { search: 'DT1 1XJ' };
const manchester = { search: 'Manchester' };
const areas = [london, south, manchester];
const searches = areas.map((a) => a.search);
const minute = 60_000;

const shield = { error: 'HTTP 541', status: 541 };

// 256GB Star White and Night Sky, and 512GB Star White.
const white256: Sighting = { part: 'MK2D4QN/A', storeNumber: 'R226', storeName: 'White City', quote: 'Available Today' };
const sky256: Sighting = { ...white256, part: 'MK2E4QN/A' };
const white512: Sighting = { ...white256, part: 'MK2F4QN/A' };

describe('sweepDue', () => {
  test('sweeps the area asked about longest ago, five minutes after the last sweep', () => {
    let sweeps = asked(emptySweeps(), london.search, 0);
    expect(sweepDue(sweeps, [london, south], SWEEP_EVERY_MS - 1)).toBeUndefined();
    expect(sweepDue(sweeps, [london, south], SWEEP_EVERY_MS)).toBe(south);
    sweeps = asked(sweeps, south.search, SWEEP_EVERY_MS);
    expect(sweepDue(sweeps, [london, south], 2 * SWEEP_EVERY_MS)).toBe(london);
  });

  test('a 541 rests only its area, and the others keep their turns', () => {
    // Three in a row rest London for 20 minutes, past its next turn at 15.
    let sweeps = emptySweeps();
    for (let i = 0; i < 3; i++) sweeps = failed(asked(sweeps, london.search, 0), london.search, shield, searches, 0);
    expect(sweeps.pausedUntil).toBeUndefined();
    sweeps = asked(asked(sweeps, south.search, 5 * minute), manchester.search, 10 * minute);
    expect(sweepDue(sweeps, areas, 15 * minute)).toBe(south);
    sweeps = asked(sweeps, south.search, 15 * minute);
    expect(sweepDue(sweeps, areas, 20 * minute)).toBe(london);
  });

  test('rests an area for longer each 541 in a row', () => {
    let sweeps = failed(emptySweeps(), london.search, shield, searches, 0);
    sweeps = failed(sweeps, london.search, shield, searches, 5 * minute);
    expect(sweepDue(sweeps, [london], 15 * minute - 1)).toBeUndefined();
    expect(sweepDue(sweeps, [london], 15 * minute)).toBe(london);
    for (let i = 0; i < 5; i++) sweeps = failed(sweeps, london.search, shield, searches, 0);
    expect(sweeps.rests[london.search]?.until).toBe(30 * minute);
  });

  test('stops every sweep once every area has been turned away since the last answer', () => {
    let sweeps = failed(emptySweeps(), london.search, shield, searches, 0);
    sweeps = answered(sweeps, south.search, [], minute);
    sweeps = failed(sweeps, south.search, shield, searches, 2 * minute);
    sweeps = failed(sweeps, manchester.search, shield, searches, 3 * minute);
    expect(sweeps.pausedUntil).toBeUndefined();
    sweeps = failed(sweeps, london.search, shield, searches, 6 * minute);
    expect(sweeps.pausedUntil).toBe(11 * minute);
    // Still nothing answering after the pause, so the next 541 stops them for longer.
    sweeps = failed(sweeps, south.search, shield, searches, 11 * minute);
    expect(sweeps.pausedUntil).toBe(21 * minute);
  });

  test('stops every sweep on 403 or 429', () => {
    for (const status of [403, 429]) {
      const sweeps = failed(emptySweeps(), london.search, { error: `HTTP ${status}`, status }, searches, 0);
      expect(sweepDue(sweeps, areas, 5 * minute - 1)).toBeUndefined();
      expect(sweepDue(sweeps, areas, 5 * minute)).toBe(london);
    }
  });

  test("waits as long as Apple says, up to an hour", () => {
    const told = (retryAfterMs: number) => failed(emptySweeps(), london.search, { error: 'HTTP 503', status: 503, retryAfterMs }, searches, 0).pausedUntil;
    expect(told(12 * minute)).toBe(12 * minute);
    expect(told(minute)).toBe(5 * minute);
    expect(told(24 * 60 * minute)).toBe(60 * minute);
  });

  test('carries on at the usual pace after other failures', () => {
    const sweeps = failed(asked(emptySweeps(), london.search, 0), london.search, { error: 'London: HTTP 503', status: 503 }, searches, 0);
    expect(sweeps.lastError).toBe('London: HTTP 503');
    expect(sweepDue(sweeps, [london], SWEEP_EVERY_MS)).toBe(london);
  });

  test("an answer clears the area's rest, the pause and the error", () => {
    let sweeps = failed(emptySweeps(), south.search, shield, searches, 0);
    sweeps = failed(sweeps, london.search, { error: 'HTTP 429', status: 429 }, searches, 0);
    sweeps = answered(sweeps, london.search, [], 6 * minute);
    expect(sweeps.pausedUntil).toBeUndefined();
    expect(sweeps.lastError).toBeUndefined();
    expect(sweeps.pushbacks).toBe(0);
    expect(sweeps.turnedAway).toEqual([]);
    expect(Object.keys(sweeps.rests)).toEqual([south.search]);
  });

  test('remembers the latest sweeps, newest first', () => {
    let sweeps = failed(emptySweeps(), london.search, shield, searches, 0);
    sweeps = answered(sweeps, south.search, [], minute);
    expect(sweeps.recent).toEqual([
      { at: minute, area: south.search, ok: true, status: 200 },
      { at: 0, area: london.search, ok: false, status: 541 },
    ]);
    for (let i = 0; i < 100; i++) sweeps = answered(sweeps, south.search, [], i);
    expect(sweeps.recent).toHaveLength(60);
  });
});

describe('confirmDue', () => {
  test('sweeps an area with new stock unless it was swept in the last 30 seconds', () => {
    const sweeps = asked(emptySweeps(), london.search, 0);
    expect(confirmDue(sweeps, london.search, CONFIRM_GAP_MS - 1)).toBe(false);
    expect(confirmDue(sweeps, london.search, CONFIRM_GAP_MS)).toBe(true);
    expect(confirmDue(sweeps, south.search, 0)).toBe(true);
  });

  test("doesn't while Apple is pushing back", () => {
    expect(confirmDue(failed(emptySweeps(), south.search, { error: 'HTTP 429', status: 429 }, searches, 0), london.search, minute)).toBe(false);
  });

  test("doesn't while the area is resting", () => {
    const sweeps = failed(emptySweeps(), london.search, shield, searches, 0);
    expect(confirmDue(sweeps, london.search, minute)).toBe(false);
    expect(confirmDue(sweeps, south.search, minute)).toBe(true);
  });
});

describe('standing', () => {
  test("is the area's last sweep until it's too old to go on", () => {
    const sweeps = answered(emptySweeps(), london.search, [white256], 0);
    expect(standing(sweeps, london.search, SWEEP_TRUSTED_MS)).toEqual([white256]);
    expect(standing(sweeps, london.search, SWEEP_TRUSTED_MS + 1)).toEqual([]);
    expect(standing(sweeps, south.search, 0)).toEqual([]);
  });
});

describe('outdate', () => {
  const sweeps = answered(emptySweeps(), london.search, [white256, sky256, white512], 0);

  test("drops the other colours at a capacity when a quick check found none of them anywhere", () => {
    // Asked about 256GB Night Sky, which covers 256GB Star White but says nothing of itself or 512GB.
    expect(standing(outdate(sweeps, london.search, 'MK2E4QN/A'), london.search, 0)).toEqual([sky256, white512]);
  });

  test('leaves other areas alone, and returns the same sweeps when nothing changed', () => {
    expect(outdate(sweeps, south.search, 'MK2E4QN/A')).toBe(sweeps);
    const onlySky = answered(emptySweeps(), london.search, [sky256], 0);
    expect(outdate(onlySky, london.search, 'MK2E4QN/A')).toBe(onlySky);
  });
});
