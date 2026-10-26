import type { Sighting } from './apple';
import { findModel, productUrl } from './catalog';

export const sightingKey = (s: Sighting) => `${s.part}@${s.storeNumber}`;

export function newSightings(previous: readonly Sighting[], current: readonly Sighting[]) {
  const seen = new Set(previous.map(sightingKey));
  return current.filter((s) => !seen.has(sightingKey(s)));
}

export function stockAlert(sightings: readonly Sighting[]) {
  const stores = new Map<string, string[]>();
  for (const s of sightings) stores.set(s.part, [...(stores.get(s.part) ?? []), s.storeName]);

  const lines = [...stores].map(([part, names]) => `${findModel(part)?.name ?? part}: ${names.join(', ')}`);
  const first = findModel(sightings[0].part);

  return {
    aps: {
      alert: { title: 'iPhone Duo in stock', body: lines.join('\n') },
      sound: 'default',
      'interruption-level': 'time-sensitive',
      'thread-id': 'stock',
    },
    url: first ? productUrl(first) : undefined,
  };
}

export const healthAlert = (title: string, body: string) => ({
  aps: { alert: { title, body }, sound: 'default', 'thread-id': 'health' },
});
