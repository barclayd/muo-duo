import { describe, expect, test } from 'bun:test';
import { cycleSeconds, isPushback, MAX_INTERVAL_SECONDS, SPEED_UP_AFTER_MS, shouldAlertSlowdown, slowDown, speedUp, tickDelaySeconds } from '../src/pace';

const atTarget = { intervalSeconds: 20, changedAt: 0 };

describe('isPushback', () => {
  test('treats rate limiting and the bot shield as pushback, but not other errors', () => {
    expect([403, 429, 541].map((status) => isPushback(status, 1))).toEqual([true, true, true]);
    expect([404, 500, undefined].map((status) => isPushback(status, 5))).toEqual([false, false, false]);
  });

  test('lets a one-off 503 pass, but not a second failure in a row', () => {
    expect(isPushback(503, 1)).toBe(false);
    expect(isPushback(503, 2)).toBe(true);
  });
});

describe('slowDown', () => {
  test('doubles the interval and records why', () => {
    expect(slowDown(atTarget, 20, 'HTTP 429', 1_000)).toEqual({ intervalSeconds: 40, changedAt: 1_000, reason: 'HTTP 429' });
  });

  test('doubles from the target when the stored pace is faster than it', () => {
    expect(slowDown({ intervalSeconds: 20, changedAt: 0 }, 60, 'HTTP 429', 0).intervalSeconds).toBe(120);
  });

  test('stops at the maximum', () => {
    expect(slowDown({ intervalSeconds: 600, changedAt: 0 }, 20, 'HTTP 429', 0).intervalSeconds).toBe(MAX_INTERVAL_SECONDS);
  });

  test('remembers that the slow-down was announced', () => {
    expect(slowDown({ intervalSeconds: 40, changedAt: 0, alerted: true }, 20, 'HTTP 429', 0).alerted).toBe(true);
  });
});

describe('shouldAlertSlowdown', () => {
  test('stays quiet for one slow-down, and speaks up at the second', () => {
    const once = slowDown(atTarget, 20, 'HTTP 429', 0);
    expect(shouldAlertSlowdown(once, 20)).toBe(false);
    expect(shouldAlertSlowdown(slowDown(once, 20, 'HTTP 429', 0), 20)).toBe(true);
  });

  test('only speaks up once', () => {
    expect(shouldAlertSlowdown({ intervalSeconds: 160, changedAt: 0, alerted: true }, 20)).toBe(false);
  });
});

describe('speedUp', () => {
  const slowed = { intervalSeconds: 80, changedAt: 0, reason: 'HTTP 429' };

  test('waits for two minutes without pushback', () => {
    expect(speedUp(slowed, 20, SPEED_UP_AFTER_MS - 1)).toBe(slowed);
  });

  test('then halves the interval', () => {
    expect(speedUp(slowed, 20, SPEED_UP_AFTER_MS)).toEqual({ intervalSeconds: 40, changedAt: SPEED_UP_AFTER_MS, reason: 'HTTP 429' });
  });

  test('never goes faster than the target, and forgets the slow-down once back at it', () => {
    expect(speedUp({ ...slowed, intervalSeconds: 30, alerted: true }, 20, SPEED_UP_AFTER_MS)).toStrictEqual({ intervalSeconds: 20, changedAt: SPEED_UP_AFTER_MS });
    expect(speedUp(atTarget, 20, SPEED_UP_AFTER_MS)).toBe(atTarget);
  });
});

describe('cycleSeconds', () => {
  test('is the interval when the queries fit in it', () => {
    expect(cycleSeconds(20, 12)).toBe(20);
  });

  test('stretches to one request a second when they do not', () => {
    expect(cycleSeconds(10, 12)).toBe(12);
  });
});

describe('tickDelaySeconds', () => {
  test('spreads one interval across the queried parts', () => {
    expect(tickDelaySeconds(20, 4, 0)).toBe(5);
  });

  test('backs off on failures, up to the maximum', () => {
    expect(tickDelaySeconds(20, 4, 2)).toBe(20);
    expect(tickDelaySeconds(20, 4, 20)).toBe(MAX_INTERVAL_SECONDS);
  });

  test('never asks Apple more than once a second', () => {
    expect(tickDelaySeconds(10, 12, 0)).toBe(1);
    expect(tickDelaySeconds(10, 40, 0)).toBe(1);
  });

  test('copes with nothing to query', () => {
    expect(tickDelaySeconds(20, 0, 0)).toBe(20);
  });
});
