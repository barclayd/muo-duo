import { describe, expect, test } from 'bun:test';
import type { Sighting } from '../src/apple';
import { answered, asked, CONFIRM_GAP_MS, confirmDue, emptySweeps, failed, outdate, SWEEP_EVERY_MS, SWEEP_TRUSTED_MS, standing, sweepDue } from '../src/sweep';

const london = { search: 'NW6 5PU' };
const south = { search: 'DT1 1XJ' };
const minute = 60_000;

// 256GB Star White and Night Sky, and 512GB Star White.
const white256: Sighting = { part: 'MK2D4QN/A', storeNumber: 'R226', storeName: 'White City', quote: 'Available Today' };
const sky256: Sighting = { ...white256, part: 'MK2E4QN/A' };
const white512: Sighting = { ...white256, part: 'MK2F4QN/A' };

describe('sweepDue', () => {
  test('sweeps the area asked about longest ago, a minute after the last sweep', () => {
    let sweeps = asked(emptySweeps(), london.search, 0);
    expect(sweepDue(sweeps, [london, south], SWEEP_EVERY_MS - 1)).toBeUndefined();
    expect(sweepDue(sweeps, [london, south], SWEEP_EVERY_MS)).toBe(south);
    sweeps = asked(sweeps, south.search, SWEEP_EVERY_MS);
    expect(sweepDue(sweeps, [london, south], 2 * SWEEP_EVERY_MS)).toBe(london);
  });

  test('stops sweeping after pushback, for longer each time in a row', () => {
    let sweeps = failed(emptySweeps(), 'London: HTTP 541', true, 0);
    expect(sweepDue(sweeps, [london], 5 * minute - 1)).toBeUndefined();
    expect(sweepDue(sweeps, [london], 5 * minute)).toBe(london);
    sweeps = failed(sweeps, 'London: HTTP 541', true, 0);
    expect(sweepDue(sweeps, [london], 10 * minute - 1)).toBeUndefined();
    for (let i = 0; i < 5; i++) sweeps = failed(sweeps, 'London: HTTP 541', true, 0);
    expect(sweeps.pausedUntil).toBe(30 * minute);
  });

  test('carries on at the usual pace after other failures', () => {
    const sweeps = failed(asked(emptySweeps(), london.search, 0), 'London: HTTP 503', false, 0);
    expect(sweeps.lastError).toBe('London: HTTP 503');
    expect(sweepDue(sweeps, [london], SWEEP_EVERY_MS)).toBe(london);
  });

  test('an answer clears the pause and the error', () => {
    const sweeps = answered(failed(emptySweeps(), 'London: HTTP 541', true, 0), london.search, [], 6 * minute);
    expect(sweeps).toEqual({ answers: { [london.search]: { at: 6 * minute, sightings: [] } }, askedAt: {}, pushbacks: 0 });
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
    expect(confirmDue(failed(emptySweeps(), 'HTTP 429', true, 0), london.search, minute)).toBe(false);
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
