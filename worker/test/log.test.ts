import { describe, expect, test } from 'bun:test';
import type { Sighting } from '../src/apple';
import { emptyLog, note, recordStock, type StockLog } from '../src/log';

const whiteCity: Sighting = { part: 'MK2D4QN/A', storeNumber: 'R226', storeName: 'White City', quote: 'Available Today' };
const battersea: Sighting = { ...whiteCity, storeNumber: 'R763', storeName: 'Battersea' };
const all = () => true;
const start = emptyLog('2026-10-26T00:00:00.000Z');

describe('recordStock', () => {
  test('returns the same log when nothing changed, so callers can skip the write', () => {
    expect(recordStock(start, [], all, 't1')).toBe(start);
    const log = recordStock(start, [whiteCity], all, 't1');
    expect(recordStock(log, [whiteCity], all, 't2')).toBe(log);
  });

  test('logs stock coming in', () => {
    expect(recordStock(start, [whiteCity], all, 't1').entries).toEqual([{ at: 't1', kind: 'in', ...whiteCity }]);
  });

  test('logs it going, with when it came in', () => {
    const log = recordStock(recordStock(start, [whiteCity], all, 't1'), [], all, 't2');
    expect(log.entries.at(-1)).toEqual({ at: 't2', kind: 'gone', part: 'MK2D4QN/A', storeNumber: 'R226', storeName: 'White City', since: 't1' });
    expect(log.open).toEqual({});
  });

  test('tracks each store separately', () => {
    let log = recordStock(start, [whiteCity, battersea], all, 't1');
    log = recordStock(log, [battersea], all, 't2');
    expect(log.entries.map((e) => [e.kind, 'storeName' in e ? e.storeName : ''])).toEqual([
      ['in', 'White City'],
      ['in', 'Battersea'],
      ['gone', 'White City'],
    ]);
  });

  test('forgets stock nobody is watching any more without saying it went', () => {
    const log = recordStock(start, [whiteCity], all, 't1');
    const next = recordStock(log, [], (s) => s.part !== whiteCity.part, 't2');
    expect(next.entries).toEqual(log.entries);
    expect(next.open).toEqual({});
  });

  test('keeps only the most recent 200 entries', () => {
    let log: StockLog = start;
    for (let i = 0; i < 150; i++) log = recordStock(recordStock(log, [whiteCity], all, `in${i}`), [], all, `gone${i}`);
    expect(log.entries).toHaveLength(200);
    expect(log.entries.at(-1)!.at).toBe('gone149');
    expect(log.since).toBe(start.since);
  });
});

describe('note', () => {
  test('adds checker events after stock ones', () => {
    const log = note(recordStock(start, [whiteCity], all, 't1'), { kind: 'failing', detail: 'London: HTTP 503' }, 't2');
    expect(log.entries.map((e) => e.kind)).toEqual(['in', 'failing']);
    expect(log.entries.at(-1)).toEqual({ at: 't2', kind: 'failing', detail: 'London: HTTP 503' });
  });
});
