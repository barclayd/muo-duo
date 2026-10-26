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

// One request per tick, so every queried part is asked about once per interval. Failures back off
// on top of that.
export function tickDelaySeconds(intervalSeconds: number, queries: number, consecutiveFailures: number) {
  return Math.min((intervalSeconds / Math.max(queries, 1)) * 2 ** consecutiveFailures, MAX_INTERVAL_SECONDS);
}
