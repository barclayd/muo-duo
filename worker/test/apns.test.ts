import { afterEach, beforeAll, describe, expect, mock, spyOn, test } from 'bun:test';
import { testAlert } from '../src/alerts';
import { Apns, type ApnsConfig, type ApnsDevice, type ProviderToken, signJwt, type TokenStore } from '../src/apns';

const fromBase64url = (s: string) => Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0));

let pair: CryptoKeyPair;
let config: ApnsConfig;

beforeAll(async () => {
  pair = (await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify'])) as CryptoKeyPair;
  const pkcs8 = new Uint8Array(await crypto.subtle.exportKey('pkcs8', pair.privateKey));
  const pem = `-----BEGIN PRIVATE KEY-----\n${btoa(String.fromCharCode(...pkcs8))}\n-----END PRIVATE KEY-----`;
  config = { keyP8: pem, keyId: 'ABC123DEFG', teamId: 'R2GGK3VN2C', topic: 'com.barclayd.muoduo' };
});

test('signs an ES256 JWT that verifies against the key pair', async () => {
  const jwt = await signJwt(config, Date.UTC(2026, 9, 26));
  const [header, claims, signature] = jwt.split('.');

  expect(JSON.parse(new TextDecoder().decode(fromBase64url(header)))).toEqual({ alg: 'ES256', kid: 'ABC123DEFG' });
  expect(JSON.parse(new TextDecoder().decode(fromBase64url(claims)))).toEqual({ iss: 'R2GGK3VN2C', iat: 1792972800 });
  const valid = await crypto.subtle.verify(
    { name: 'ECDSA', hash: 'SHA-256' },
    pair.publicKey,
    fromBase64url(signature),
    new TextEncoder().encode(`${header}.${claims}`),
  );
  expect(valid).toBe(true);
});

describe('Apns', () => {
  afterEach(() => mock.restore());

  const iphone: ApnsDevice = { token: '7f3a9c2e', environment: 'production', registeredAt: '2026-10-27T08:00:00.000Z' };
  const minutes = (n: number) => n * 60 * 1000;

  // APNs accepts every send; returns the authorization each one carried.
  function apple() {
    const sends = spyOn(globalThis, 'fetch').mockImplementation((async (_input: string | URL | Request) => new Response(null)) as typeof fetch);
    return () => sends.mock.calls.map(([, init]) => new Headers(init!.headers).get('authorization'));
  }

  // Stands in for Durable Object storage, recording each token saved.
  function storage(initial?: ProviderToken) {
    let current = initial;
    const saved: ProviderToken[] = [];
    const store: TokenStore = {
      get: async () => current,
      put: async (token) => {
        current = token;
        saved.push(token);
      },
    };
    return { store, saved };
  }

  test('sends to iPhones at once with one token', async () => {
    const authorizations = apple();
    const { store, saved } = storage();
    const apns = new Apns(config, store);

    const results = await Promise.all([1, 2, 3].map((n) => apns.send({ ...iphone, token: `${iphone.token}${n}` }, testAlert(false))));

    expect(results).toEqual([{ ok: true }, { ok: true }, { ok: true }]);
    expect(saved).toHaveLength(1);
    expect(authorizations()).toEqual(Array(3).fill(`bearer ${saved[0].token}`));
  });

  test('keeps a token for 50 minutes, then signs another', async () => {
    const now = spyOn(Date, 'now').mockReturnValue(Date.UTC(2026, 9, 27, 20));
    const authorizations = apple();
    const apns = new Apns(config);

    await apns.send(iphone, testAlert(false));
    now.mockReturnValue(Date.UTC(2026, 9, 27, 20) + minutes(50));
    await apns.send(iphone, testAlert(false));
    now.mockReturnValue(Date.UTC(2026, 9, 27, 20) + minutes(51));
    await apns.send(iphone, testAlert(false));

    const [first, second, third] = authorizations();
    expect(second).toBe(first);
    expect(third).not.toBe(first);
  });

  test('carries on with the stored token after a restart', async () => {
    const authorizations = apple();
    const { store, saved } = storage();

    await new Apns(config, store).send(iphone, testAlert(false));
    await new Apns(config, store).send(iphone, testAlert(false));

    expect(saved).toHaveLength(1);
    expect(authorizations()).toEqual([`bearer ${saved[0].token}`, `bearer ${saved[0].token}`]);
  });

  test('signs a new token when the stored one is too old or from another key', async () => {
    const authorizations = apple();
    const stale = { token: 'stale', issuedAt: Date.now() - minutes(51), keyId: config.keyId, teamId: config.teamId };
    const otherKey = { token: 'revoked', issuedAt: Date.now(), keyId: 'OLDKEY1234', teamId: config.teamId };
    const otherTeam = { token: 'elsewhere', issuedAt: Date.now(), keyId: config.keyId, teamId: 'A1B2C3D4E5' };

    for (const stored of [stale, otherKey, otherTeam]) {
      const { store, saved } = storage(stored);
      await new Apns(config, store).send(iphone, testAlert(false));
      expect(saved).toEqual([{ token: expect.any(String), issuedAt: expect.any(Number), keyId: config.keyId, teamId: config.teamId }]);
      expect(authorizations().at(-1)).toBe(`bearer ${saved[0].token}`);
    }
    expect(authorizations()).not.toContain('bearer stale');
  });

  test('tries signing again after it fails', async () => {
    apple();
    spyOn(crypto.subtle, 'importKey').mockRejectedValueOnce(new Error('signing failed'));
    const apns = new Apns(config);

    await expect(apns.send(iphone, testAlert(false))).rejects.toThrow('signing failed');
    expect(await apns.send(iphone, testAlert(false))).toEqual({ ok: true });
  });

  test('still sends when storage fails', async () => {
    const authorizations = apple();
    const broken: TokenStore = { get: () => Promise.reject(new Error('storage down')), put: () => Promise.reject(new Error('storage down')) };

    expect(await new Apns(config, broken).send(iphone, testAlert(false))).toEqual({ ok: true });
    expect(authorizations()[0]).toStartWith('bearer ey');
  });
});
