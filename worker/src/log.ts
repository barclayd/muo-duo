import { sightingKey } from './alerts';
import type { Sighting } from './apple';

// The app's stock log: each watched model coming into stock at a store and going again, and the
// checker stopping and starting, so a quiet night can be told apart from one it missed.
export type LogEntry =
  | ({ at: string; kind: 'in' } & Sighting)
  | { at: string; kind: 'gone'; part: string; storeNumber: string; storeName: string; since: string }
  | ({ at: string } & CheckerNote);

export interface CheckerNote {
  kind: 'failing' | 'recovered' | 'stale' | 'normal' | 'paused' | 'resumed';
  detail?: string;
}

export interface StockLog {
  since: string;
  // What the log last said was in stock, by part@store, and since when.
  open: Record<string, { sighting: Sighting; since: string }>;
  // Oldest first.
  entries: readonly LogEntry[];
}

const KEEP = 200;

export const emptyLog = (since: string): StockLog => ({ since, open: {}, entries: [] });

export function note(log: StockLog, entry: CheckerNote, at: string): StockLog {
  return { ...log, entries: [...log.entries, { ...entry, at }].slice(-KEEP) };
}

// Logs what has come into stock and what has gone since last time. Stock that's only dropped out
// because nobody's watching that model or store any more is forgotten without saying it's gone.
// Returns `log` itself when nothing changed, so callers can skip the write.
export function recordStock(log: StockLog, current: readonly Sighting[], watching: (s: Sighting) => boolean, at: string): StockLog {
  const now = new Map(current.map((s) => [sightingKey(s), s]));
  const open = { ...log.open };
  const added: LogEntry[] = [];

  for (const [key, { sighting, since }] of Object.entries(log.open)) {
    if (now.has(key)) continue;
    delete open[key];
    if (watching(sighting)) {
      const { part, storeNumber, storeName } = sighting;
      added.push({ at, kind: 'gone', part, storeNumber, storeName, since });
    }
  }
  for (const [key, sighting] of now) {
    if (open[key]) continue;
    open[key] = { sighting, since: at };
    added.push({ at, kind: 'in', ...sighting });
  }

  if (Object.keys(open).length === Object.keys(log.open).length && added.length === 0) return log;
  return { since: log.since, open, entries: [...log.entries, ...added].slice(-KEEP) };
}
