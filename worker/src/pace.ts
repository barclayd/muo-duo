// How fast to poll. It starts at the configured interval. Each time Apple pushes back, the interval
// doubles; after every two minutes without pushback it halves again, never going faster than configured.

export interface Pace {
  intervalSeconds: number;
  changedAt: number;
  reason?: string;
  // Whether "Checking less often" went out, so "Back to full speed" only ever follows one.
  alerted?: boolean;
}

// 429 and 541 (Apple's bot shield) mean slow down, and 403 can be a block. A 503 is usually a passing
// hiccup, so it only counts when the check before it failed too.
const PUSHBACK_STATUSES = new Set([403, 429, 541]);
export const SPEED_UP_AFTER_MS = 2 * 60_000;
export const MAX_INTERVAL_SECONDS = 15 * 60;

export const isPushback = (status: number | undefined, consecutiveFailures: number) =>
  status !== undefined && (PUSHBACK_STATUSES.has(status) || (status === 503 && consecutiveFailures >= 2));

export function slowDown(pace: Pace, targetSeconds: number, reason: string, now: number): Pace {
  const intervalSeconds = Math.min(Math.max(pace.intervalSeconds, targetSeconds) * 2, MAX_INTERVAL_SECONDS);
  return { intervalSeconds, changedAt: now, reason, alerted: pace.alerted };
}

export function speedUp(pace: Pace, targetSeconds: number, now: number): Pace {
  if (pace.intervalSeconds <= targetSeconds || now - pace.changedAt < SPEED_UP_AFTER_MS) return pace;
  const intervalSeconds = Math.max(targetSeconds, Math.ceil(pace.intervalSeconds / 2));
  return intervalSeconds > targetSeconds ? { ...pace, intervalSeconds, changedAt: now } : { intervalSeconds, changedAt: now };
}

// One slow-down sorts itself out in a couple of minutes, so only a second in a row is worth a push.
export const shouldAlertSlowdown = (pace: Pace, targetSeconds: number) => !pace.alerted && pace.intervalSeconds >= targetSeconds * 4;

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
