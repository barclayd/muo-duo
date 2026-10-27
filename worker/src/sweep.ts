import type { Sighting } from './apple';
import { findModel } from './catalog';

// Sweeps ask the older pickup-message endpoint, which lists every watched model at all 12 stores near an
// area in one answer, where a quick check's recommendations name a single store. One area is swept every
// five minutes, taking turns, and an area is swept straight away when a quick check finds new stock there, so
// the alert names every store that has it. Apple is warier of this endpoint, so when it pushes back,
// sweeps stop for a while and the quick checks carry on without them.
//
// Apple's bot shield (HTTP 541) often turns one area away and answers the next a few seconds later, so
// a 541 rests only its own area. All sweeps stop once every area has been turned away in a row, or
// when Apple says 403, 429 or how long to wait.

export interface Sweeps {
  // Each area's latest answer, by its search.
  answers: Record<string, { at: number; sightings: Sighting[] }>;
  // When each area was last asked, answered or not, so they take turns.
  askedAt: Record<string, number>;
  // Areas resting after a 541, until when, and how many 541s in a row they've had.
  rests: Record<string, { until: number; level: number }>;
  // Areas turned away since the last answer.
  turnedAway: string[];
  // Pauses of every sweep in a row, and when sweeping can start again after the latest.
  pushbacks: number;
  pausedUntil?: number;
  lastError?: string;
  // The latest sweeps, newest first, to show how often Apple answers them.
  recent: Outcome[];
}

export interface Outcome {
  at: number;
  area: string;
  ok: boolean;
  status?: number;
}

export interface Failure {
  error: string;
  status?: number;
  // How long Apple asked us to wait, if it said.
  retryAfterMs?: number;
}

// A sweep a minute was turned away (541) after about a quarter of an hour, for 20 minutes at a time, and
// the sweep that confirms new stock with it. Sweeping less often leaves Apple's patience for that one.
export const SWEEP_EVERY_MS = 5 * 60_000;
// New stock in an area sweeps it again, unless it was swept this recently.
export const CONFIRM_GAP_MS = 30_000;
// How long new stock waits for the sweep before it's alerted on without it.
export const CONFIRM_TIMEOUT_MS = 2_000;
export const SWEEP_TIMEOUT_MS = 10_000;
// With three areas taking turns, each is normally swept every 15 minutes. One that hasn't been for this
// long no longer counts.
export const SWEEP_TRUSTED_MS = 20 * 60_000;
const PAUSES_MS = [5, 10, 20, 30].map((minutes) => minutes * 60_000);
// Retry-After is honoured up to an hour, so a stray one can't stop sweeps for days.
const LONGEST_WAIT_MS = 60 * 60_000;
// About five hours of sweeps.
const RECENT_SWEEPS = 60;

export const emptySweeps = (): Sweeps => ({ answers: {}, askedAt: {}, rests: {}, turnedAway: [], pushbacks: 0, recent: [] });

const pause = (inARow: number) => PAUSES_MS[Math.min(inARow, PAUSES_MS.length) - 1]!;

const paused = (sweeps: Sweeps, now: number) => sweeps.pausedUntil !== undefined && now < sweeps.pausedUntil;

export const resting = (sweeps: Sweeps, area: string, now: number) => now < (sweeps.rests[area]?.until ?? -Infinity);

const askedAt = (sweeps: Sweeps, area: string) => sweeps.askedAt[area] ?? -Infinity;

// The area asked about longest ago that isn't resting, once five minutes have passed since the last sweep.
export function sweepDue<A extends { search: string }>(sweeps: Sweeps, areas: readonly A[], now: number): A | undefined {
  if (paused(sweeps, now)) return undefined;
  if (now - Math.max(-Infinity, ...Object.values(sweeps.askedAt)) < SWEEP_EVERY_MS) return undefined;
  const ready = areas.filter((a) => !resting(sweeps, a.search, now));
  if (ready.length === 0) return undefined;
  return ready.reduce((a, b) => (askedAt(sweeps, b.search) < askedAt(sweeps, a.search) ? b : a));
}

export const confirmDue = (sweeps: Sweeps, area: string, now: number) =>
  !paused(sweeps, now) && !resting(sweeps, area, now) && now - askedAt(sweeps, area) >= CONFIRM_GAP_MS;

export const asked = (sweeps: Sweeps, area: string, now: number): Sweeps => ({ ...sweeps, askedAt: { ...sweeps.askedAt, [area]: now } });

const remember = (sweeps: Sweeps, outcome: Outcome) => [outcome, ...sweeps.recent].slice(0, RECENT_SWEEPS);

export function answered(sweeps: Sweeps, area: string, sightings: Sighting[], at: number): Sweeps {
  const { [area]: _, ...rests } = sweeps.rests;
  return {
    answers: { ...sweeps.answers, [area]: { at, sightings } },
    askedAt: sweeps.askedAt,
    rests,
    turnedAway: [],
    pushbacks: 0,
    recent: remember(sweeps, { at, area, ok: true, status: 200 }),
  };
}

// A 541 rests its area, for longer each time in a row. Once every area has been turned away since the
// last answer, or on 403, 429 or a Retry-After, every sweep stops, for longer each time in a row. Any
// other failure just waits for the next turn.
export function failed(sweeps: Sweeps, area: string, { error, status, retryAfterMs = 0 }: Failure, areas: readonly string[], now: number): Sweeps {
  let next: Sweeps = { ...sweeps, lastError: error, recent: remember(sweeps, { at: now, area, ok: false, status }) };
  let everywhere = false;
  if (status === 541) {
    const level = (sweeps.rests[area]?.level ?? 0) + 1;
    const turnedAway = [...new Set([...sweeps.turnedAway, area])];
    next = { ...next, rests: { ...sweeps.rests, [area]: { until: now + pause(level), level } }, turnedAway };
    everywhere = areas.every((a) => turnedAway.includes(a));
  }
  if (!everywhere && status !== 403 && status !== 429 && retryAfterMs === 0) return next;
  const pushbacks = sweeps.pushbacks + 1;
  return { ...next, pushbacks, pausedUntil: now + Math.max(pause(pushbacks), Math.min(retryAfterMs, LONGEST_WAIT_MS)) };
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
