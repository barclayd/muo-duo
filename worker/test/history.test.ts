import { describe, expect, test } from 'bun:test';
import type { Sighting } from '../src/apple';
import { type Episode, recordSeen } from '../src/history';

const GAP = 60_000;
const white = (part = 'MK2D4QN/A'): Sighting => ({ part, storeNumber: 'R226', storeName: 'White City', quote: 'Available Today' });

describe('recordSeen', () => {
  test('returns the same list when nothing was seen, so callers can skip the write', () => {
    const episodes: Episode[] = [];
    expect(recordSeen(episodes, [], 1_000, GAP)).toBe(episodes);
  });

  test('starts an episode the first time stock is seen', () => {
    expect(recordSeen([], [white()], 1_000, GAP)).toEqual([{ part: 'MK2D4QN/A', storeNumber: 'R226', storeName: 'White City', from: 1_000, to: 1_000 }]);
  });

  test('extends it while stock keeps being seen', () => {
    const first = recordSeen([], [white()], 1_000, GAP);
    expect(recordSeen(first, [white()], 1_000 + GAP, GAP)).toEqual([{ ...first[0]!, to: 1_000 + GAP }]);
  });

  test('starts a new one after a gap', () => {
    const first = recordSeen([], [white()], 1_000, GAP);
    const next = recordSeen(first, [white()], 1_001 + GAP, GAP);
    expect(next.map((e) => [e.from, e.to])).toEqual([[1_000, 1_000], [1_001 + GAP, 1_001 + GAP]]);
  });

  test('tracks each model at each store separately', () => {
    const next = recordSeen([], [white('MK2D4QN/A'), white('MK2E4QN/A')], 1_000, GAP);
    expect(next.map((e) => e.part)).toEqual(['MK2D4QN/A', 'MK2E4QN/A']);
  });

  test('keeps only the most recent 50', () => {
    let episodes: readonly Episode[] = [];
    for (let i = 0; i < 60; i++) episodes = recordSeen(episodes, [white()], i * 2 * GAP, GAP);
    expect(episodes).toHaveLength(50);
    expect(episodes.at(-1)!.from).toBe(59 * 2 * GAP);
  });
});
