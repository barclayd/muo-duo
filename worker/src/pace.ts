// How fast to poll. It starts at the configured interval. Each time Apple pushes back, the interval
// doubles; after every half hour without pushback it halves again, never going faster than configured.

export interface Pace {
  intervalSeconds: number;
  changedAt: number;
  reason?: string;
}

// 541 is Apple's bot shield; the others are the usual "slow down" answers.
const PUSHBACK_STATUSES = new Set([403, 429, 503, 541]);
export const SPEED_UP_AFTER_MS = 30 * 60_000;
export const MAX_INTERVAL_SECONDS = 15 * 60;

export const isPushback = (status: number | undefined) => status !== undefined && PUSHBACK_STATUSES.has(status);

export function slowDown(pace: Pace, targetSeconds: number, reason: string, now: number): Pace {
  const intervalSeconds = Math.min(Math.max(pace.intervalSeconds, targetSeconds) * 2, MAX_INTERVAL_SECONDS);
  return { intervalSeconds, changedAt: now, reason };
}

export function speedUp(pace: Pace, targetSeconds: number, now: number): Pace {
  if (pace.intervalSeconds <= targetSeconds || now - pace.changedAt < SPEED_UP_AFTER_MS) return pace;
  const intervalSeconds = Math.max(targetSeconds, Math.ceil(pace.intervalSeconds / 2));
  return { intervalSeconds, changedAt: now, reason: intervalSeconds > targetSeconds ? pace.reason : undefined };
}

// A ceiling on what Apple sees, however many areas and models are watched.
export const MIN_SECONDS_BETWEEN_REQUESTS = 1;

// How long each query takes to come round again: the interval, unless there are more queries than
// fit in it at one request a second.
export const cycleSeconds = (intervalSeconds: number, queries: number) =>
  Math.max(intervalSeconds, queries * MIN_SECONDS_BETWEEN_REQUESTS);

// One request per tick, so every query is asked once per cycle. Failures back off on top of that.
export function tickDelaySeconds(intervalSeconds: number, queries: number, consecutiveFailures: number) {
  const perQuery = cycleSeconds(intervalSeconds, queries) / Math.max(queries, 1);
  return Math.min(perQuery * 2 ** consecutiveFailures, MAX_INTERVAL_SECONDS);
}
