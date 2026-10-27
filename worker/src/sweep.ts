import type { Sighting } from './apple';
import { findModel } from './catalog';

// Sweeps ask the older pickup-message endpoint, which lists every watched model at all 12 stores near an
// area in one answer, where a quick check's recommendations name a single store. One area is swept a
// minute, taking turns, and an area is swept straight away when a quick check finds new stock there, so
// the alert names every store that has it. Apple is warier of this endpoint, so when it pushes back,
// sweeps stop for a while and the quick checks carry on without them.

export interface Sweeps {
  // Each area's latest answer, by its search.
  answers: Record<string, { at: number; sightings: Sighting[] }>;
  // When each area was last asked, answered or not, so they take turns.
  askedAt: Record<string, number>;
  // Pushbacks in a row, and when sweeping can start again after the latest.
  pushbacks: number;
  pausedUntil?: number;
  lastError?: string;
}

export const SWEEP_EVERY_MS = 60_000;
// New stock in an area sweeps it again, unless it was swept this recently.
export const CONFIRM_GAP_MS = 30_000;
// How long new stock waits for the sweep before it's alerted on without it.
export const CONFIRM_TIMEOUT_MS = 2_000;
export const SWEEP_TIMEOUT_MS = 10_000;
// Each area is normally swept every few minutes. One that hasn't been for this long no longer counts.
export const SWEEP_TRUSTED_MS = 5 * 60_000;
const PAUSES_MS = [5, 10, 20, 30].map((minutes) => minutes * 60_000);

export const emptySweeps = (): Sweeps => ({ answers: {}, askedAt: {}, pushbacks: 0 });

const paused = (sweeps: Sweeps, now: number) => sweeps.pausedUntil !== undefined && now < sweeps.pausedUntil;

const askedAt = (sweeps: Sweeps, area: string) => sweeps.askedAt[area] ?? -Infinity;

// The area asked about longest ago, once a minute has passed since the last sweep.
export function sweepDue<A extends { search: string }>(sweeps: Sweeps, areas: readonly A[], now: number): A | undefined {
  if (paused(sweeps, now) || areas.length === 0) return undefined;
  if (now - Math.max(-Infinity, ...Object.values(sweeps.askedAt)) < SWEEP_EVERY_MS) return undefined;
  return areas.reduce((a, b) => (askedAt(sweeps, b.search) < askedAt(sweeps, a.search) ? b : a));
}

export const confirmDue = (sweeps: Sweeps, area: string, now: number) => !paused(sweeps, now) && now - askedAt(sweeps, area) >= CONFIRM_GAP_MS;

export const asked = (sweeps: Sweeps, area: string, now: number): Sweeps => ({ ...sweeps, askedAt: { ...sweeps.askedAt, [area]: now } });

export const answered = (sweeps: Sweeps, area: string, sightings: Sighting[], at: number): Sweeps => ({
  answers: { ...sweeps.answers, [area]: { at, sightings } },
  askedAt: sweeps.askedAt,
  pushbacks: 0,
});

// Pushback stops sweeps for longer each time in a row. Any other failure just waits for the next turn.
export function failed(sweeps: Sweeps, error: string, pushback: boolean, now: number): Sweeps {
  if (!pushback) return { ...sweeps, lastError: error };
  const pushbacks = sweeps.pushbacks + 1;
  return { ...sweeps, pushbacks, pausedUntil: now + PAUSES_MS[Math.min(pushbacks, PAUSES_MS.length) - 1]!, lastError: error };
}

// A quick check about one model answers for the other colours at its capacity.
const covers = (asked: string, part: string) => asked !== part && findModel(asked)?.capacity === findModel(part)?.capacity;

// A quick check that found none of the other colours at any store near an area means whatever the
// area's last sweep found of them has gone.
export function outdate(sweeps: Sweeps, area: string, askedAbout: string): Sweeps {
  const answer = sweeps.answers[area];
  const sightings = answer?.sightings.filter((s) => !covers(askedAbout, s.part));
  if (!answer || !sightings || sightings.length === answer.sightings.length) return sweeps;
  return { ...sweeps, answers: { ...sweeps.answers, [area]: { ...answer, sightings } } };
}

// What an area's last sweep found, unless it's too old to go on.
export function standing(sweeps: Sweeps, area: string, now: number): Sighting[] {
  const answer = sweeps.answers[area];
  return answer && now - answer.at <= SWEEP_TRUSTED_MS ? answer.sightings : [];
}
