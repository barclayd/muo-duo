import type { Sighting } from './apple';
import { findModel, productUrl } from './catalog';

export const sightingKey = (s: Sighting) => `${s.part}@${s.storeNumber}`;

export function newSightings(previous: readonly Sighting[], current: readonly Sighting[]) {
  const seen = new Set(previous.map(sightingKey));
  return current.filter((s) => !seen.has(sightingKey(s)));
}

// Watched parts in stock across the latest answers. A part shows up in its sibling colours' answers.
export function currentSightings(answers: readonly (readonly Sighting[])[], watched: readonly string[]) {
  const wanted = new Set(watched);
  const found = new Map<string, Sighting>();
  for (const s of answers.flat()) if (wanted.has(s.part)) found.set(sightingKey(s), s);
  return [...found.values()];
}

// Hours in the UK, like Apple's restocks, whatever the Worker's own clock says. From is included, to isn't.
export interface AlarmWindow {
  enabled: boolean;
  from: number;
  to: number;
}

const ukHour = new Intl.DateTimeFormat('en-GB', { timeZone: 'Europe/London', hour: 'numeric', hourCycle: 'h23' });

export function inAlarmWindow(alarm: AlarmWindow, now: Date) {
  const hour = Number(ukHour.format(now));
  return alarm.enabled && hour >= alarm.from && hour < alarm.to;
}

// An alert with `alarm` set goes through the app's notification service extension, which also rings an
// alarm that sounds through silent mode and Focus.
const ringing = (alarm: boolean) => (alarm ? { 'mutable-content': 1 } : {});

export function stockAlert(sightings: readonly Sighting[], alarm = false) {
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
      ...ringing(alarm),
    },
    url: first ? productUrl(first) : undefined,
    ...(alarm && { alarm: true }),
  };
}

export const testAlert = (alarm: boolean) => ({
  aps: {
    alert: alarm ? { title: 'Muo Duo test alarm', body: 'This is how stock between your alarm hours will wake you.' } : { title: 'Muo Duo', body: "Test notification: you're all set." },
    sound: 'default',
    'interruption-level': 'time-sensitive',
    ...ringing(alarm),
  },
  ...(alarm && { alarm: true }),
});

export const healthAlert = (title: string, body: string) => ({
  aps: { alert: { title, body }, sound: 'default', 'thread-id': 'health' },
});
