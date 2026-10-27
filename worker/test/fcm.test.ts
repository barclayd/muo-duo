import { afterEach, beforeAll, describe, expect, mock, spyOn, test } from 'bun:test';
import { stockAlert, testAlert } from '../src/alerts';
import type { Sighting } from '../src/apple';
import { Fcm, type FcmDevice, fcmData, parseServiceAccount, type ServiceAccount, signJwt } from '../src/fcm';

const fromBase64url = (s: string) => Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0));
const decode = (part: string) => JSON.parse(new TextDecoder().decode(fromBase64url(part)));

const SEND_URL = 'https://fcm.googleapis.com/v1/projects/muo-duo-test/messages:send';

let pair: CryptoKeyPair;
let account: ServiceAccount;

beforeAll(async () => {
  pair = (await crypto.subtle.generateKey(
    { name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' },
    true,
    ['sign', 'verify'],
  )) as CryptoKeyPair;
  const pkcs8 = new Uint8Array(await crypto.subtle.exportKey('pkcs8', pair.privateKey));
  // Wrapped at 64 characters, as in the key files Firebase downloads.
  const pem = `-----BEGIN PRIVATE KEY-----\n${btoa(String.fromCharCode(...pkcs8)).replace(/.{64}/g, '$&\n')}\n-----END PRIVATE KEY-----\n`;
  account = {
    project_id: 'muo-duo-test',
    private_key_id: 'a1b2c3d4',
    private_key: pem,
    client_email: 'firebase-adminsdk@muo-duo-test.iam.gserviceaccount.com',
    token_uri: 'https://oauth2.googleapis.com/token',
  };
});

test('signs an RS256 JWT that verifies against the service account key', async () => {
  const jwt = await signJwt(account, Date.UTC(2026, 9, 27));
  const [header, claims, signature] = jwt.split('.');

  expect(decode(header)).toEqual({ alg: 'RS256', typ: 'JWT', kid: 'a1b2c3d4' });
  expect(decode(claims)).toEqual({
    iss: 'firebase-adminsdk@muo-duo-test.iam.gserviceaccount.com',
    scope: 'https://www.googleapis.com/auth/firebase.messaging',
    aud: 'https://oauth2.googleapis.com/token',
    iat: 1793059200,
    exp: 1793062800,
  });
  const valid = await crypto.subtle.verify('RSASSA-PKCS1-v1_5', pair.publicKey, fromBase64url(signature), new TextEncoder().encode(`${header}.${claims}`));
  expect(valid).toBe(true);
});

describe('parseServiceAccount', () => {
  test('reads the fields sending needs from the downloaded key', () => {
    expect(parseServiceAccount(JSON.stringify({ type: 'service_account', ...account }))).toMatchObject(account);
  });

  test("reports a key that won't parse without quoting any of it", () => {
    expect(() => parseServiceAccount('{"private_key": "-----BEGIN PRIVATE KEY-----\nMIIEvSECRET')).toThrow(new Error('FCM_SERVICE_ACCOUNT is not JSON'));
  });

  test('names the fields a key is missing', () => {
    expect(() => parseServiceAccount('{"project_id": "muo-duo-test"}')).toThrow('FCM_SERVICE_ACCOUNT has no private_key_id, private_key, client_email, token_uri');
    expect(() => parseServiceAccount('null')).toThrow('FCM_SERVICE_ACCOUNT has no project_id');
  });
});

describe('fcmData', () => {
  const whiteCity: Sighting = { part: 'MK2E4QN/A', storeNumber: 'R226', storeName: 'White City', quote: 'Available Today' };

  test('flattens a stock alarm into the strings the Android app reads', () => {
    expect(fcmData(stockAlert([whiteCity], true))).toEqual({
      title: 'iPhone Duo in stock',
      body: '256GB Night Sky: White City',
      url: 'https://www.apple.com/uk/shop/buy-iphone/iphone-duo/7.6-inch-display-256gb-night-sky',
      alarm: 'true',
      thread: 'stock',
    });
  });

  test("leaves out what an alert doesn't have", () => {
    expect(fcmData(stockAlert([whiteCity]))).not.toHaveProperty('alarm');
    expect(fcmData(testAlert(false))).toEqual({ title: 'Muo Duo', body: "Test notification: you're all set." });
  });
});

describe('Fcm', () => {
  afterEach(() => mock.restore());

  const phone: FcmDevice = { platform: 'android', token: 'dXN1:APA91bH-example_token', registeredAt: '2026-10-27T08:00:00.000Z' };

  const fcmError = (status: number, code: string, message: string, errorCode?: string) =>
    Response.json(
      { error: { code: status, message, status: code, details: errorCode ? [{ '@type': 'type.googleapis.com/google.firebase.fcm.v1.FcmError', errorCode }] : [] } },
      { status },
    );

  // Answers every request by its URL.
  const answer = (respond: (url: string) => Response) =>
    spyOn(globalThis, 'fetch').mockImplementation((async (input: string | URL | Request) => respond(String(input))) as typeof fetch);

  // Google's token endpoint hands out ya29.1, ya29.2, …; FCM answers each send with the next of `answers`.
  function google(answers: Response[] = [], expiresIn = 3599) {
    let issued = 0;
    const fetch = answer((url) =>
      url === account.token_uri
        ? Response.json({ access_token: `ya29.${++issued}`, expires_in: expiresIn, token_type: 'Bearer' })
        : (answers.shift() ?? Response.json({ name: 'projects/muo-duo-test/messages/1' })),
    );
    return {
      exchanges: () => fetch.mock.calls.filter(([url]) => String(url) === account.token_uri),
      sends: () => fetch.mock.calls.filter(([url]) => String(url) === SEND_URL).map(([, init]) => init!),
    };
  }

  test('sends a high-priority data message, with one token exchange for phones sent to at once', async () => {
    const { exchanges, sends } = google();
    const fcm = new Fcm(account);

    const results = await Promise.all([fcm.send(phone, testAlert(true)), fcm.send({ ...phone, token: 'c2Vj:APA91bSecondPhone' }, testAlert(true))]);

    expect(results).toEqual([{ ok: true }, { ok: true }]);
    expect(exchanges()).toHaveLength(1);
    const form = new URLSearchParams(String(exchanges()[0][1]!.body));
    expect(form.get('grant_type')).toBe('urn:ietf:params:oauth:grant-type:jwt-bearer');
    expect(form.get('assertion')?.split('.')).toHaveLength(3);
    expect(sends().map((init) => new Headers(init.headers).get('authorization'))).toEqual(['Bearer ya29.1', 'Bearer ya29.1']);
    expect(JSON.parse(String(sends()[0].body))).toEqual({
      message: {
        token: phone.token,
        data: { title: 'Muo Duo test alarm', body: 'This is how stock between your alarm hours will wake you.', alarm: 'true' },
        android: { priority: 'HIGH' },
      },
    });
  });

  test('keeps the access token until it nearly runs out', async () => {
    const lasting = google();
    const fcm = new Fcm(account);
    await fcm.send(phone, testAlert(false));
    await fcm.send(phone, testAlert(false));
    expect(lasting.exchanges()).toHaveLength(1);
    mock.restore();

    // Four minutes is inside the five-minute margin, so each send fetches a new one.
    const expiring = google([], 240);
    const soon = new Fcm(account);
    await soon.send(phone, testAlert(false));
    await soon.send(phone, testAlert(false));
    expect(expiring.exchanges()).toHaveLength(2);
    expect(expiring.sends().map((init) => new Headers(init.headers).get('authorization'))).toEqual(['Bearer ya29.1', 'Bearer ya29.2']);
  });

  test('fetches a new access token after one is refused', async () => {
    const { exchanges } = google([fcmError(401, 'UNAUTHENTICATED', 'Request had invalid authentication credentials.')]);
    const fcm = new Fcm(account);

    expect(await fcm.send(phone, testAlert(false))).toEqual({ ok: false, status: 401, reason: 'UNAUTHENTICATED', deviceGone: false });
    expect(await fcm.send(phone, testAlert(false))).toEqual({ ok: true });
    expect(exchanges()).toHaveLength(2);
  });

  test('tries the token exchange again after it fails', async () => {
    let attempts = 0;
    answer((url) => {
      if (url !== account.token_uri) return Response.json({ name: 'projects/muo-duo-test/messages/1' });
      return ++attempts === 1
        ? Response.json({ error: 'invalid_grant', error_description: 'Invalid JWT Signature.' }, { status: 400 })
        : Response.json({ access_token: 'ya29.1', expires_in: 3599 });
    });
    const fcm = new Fcm(account);

    await expect(fcm.send(phone, testAlert(false))).rejects.toThrow('Google token exchange failed: 400 Invalid JWT Signature.');
    expect(await fcm.send(phone, testAlert(false))).toEqual({ ok: true });
    expect(attempts).toBe(2);
  });

  test("marks a phone gone when Firebase no longer knows its token", async () => {
    google([fcmError(404, 'NOT_FOUND', 'Requested entity was not found.', 'UNREGISTERED')]);
    expect(await new Fcm(account).send(phone, testAlert(false))).toEqual({ ok: false, status: 404, reason: 'UNREGISTERED', deviceGone: true });
  });

  test("marks a phone gone when its token isn't one, but not for other bad requests", async () => {
    google([
      fcmError(400, 'INVALID_ARGUMENT', 'The registration token is not a valid FCM registration token', 'INVALID_ARGUMENT'),
      fcmError(400, 'INVALID_ARGUMENT', 'Invalid value at \'message.data[0].value\'', 'INVALID_ARGUMENT'),
    ]);
    const fcm = new Fcm(account);

    expect(await fcm.send(phone, testAlert(false))).toEqual({ ok: false, status: 400, reason: 'INVALID_ARGUMENT', deviceGone: true });
    expect(await fcm.send(phone, testAlert(false))).toEqual({ ok: false, status: 400, reason: 'INVALID_ARGUMENT', deviceGone: false });
  });

  test("keeps a phone when Firebase is struggling", async () => {
    google([fcmError(503, 'UNAVAILABLE', 'The service is currently unavailable.', 'UNAVAILABLE')]);
    expect(await new Fcm(account).send(phone, testAlert(false))).toEqual({ ok: false, status: 503, reason: 'UNAVAILABLE', deviceGone: false });
  });
});
