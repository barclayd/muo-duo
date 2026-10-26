import type { Sighting } from './apple';

// A stretch of time a model was in stock at a store, from the first check that saw it to the last.
export interface Episode {
  part: string;
  storeNumber: string;
  storeName: string;
  from: number;
  to: number;
}

const KEEP = 50;

// Extends each model/store's latest episode if it was seen again within `gapMs`, otherwise starts a
// new one. Returns `episodes` itself when nothing was seen, so callers can tell nothing changed.
export function recordSeen(episodes: readonly Episode[], seen: readonly Sighting[], now: number, gapMs: number) {
  if (seen.length === 0) return episodes;
  const next = [...episodes];
  for (const s of seen) {
    const i = next.findLastIndex((e) => e.part === s.part && e.storeNumber === s.storeNumber);
    const latest = next[i];
    if (latest && now - latest.to <= gapMs) next[i] = { ...latest, to: now };
    else next.push({ part: s.part, storeNumber: s.storeNumber, storeName: s.storeName, from: now, to: now });
  }
  return next.slice(-KEEP);
}
