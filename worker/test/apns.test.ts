import { expect, test } from 'bun:test';
import { signJwt } from '../src/apns';

const fromBase64url = (s: string) => Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0));

test('signs an ES256 JWT that verifies against the key pair', async () => {
  const pair = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
  const pkcs8 = new Uint8Array(await crypto.subtle.exportKey('pkcs8', pair.privateKey));
  const pem = `-----BEGIN PRIVATE KEY-----\n${btoa(String.fromCharCode(...pkcs8))}\n-----END PRIVATE KEY-----`;

  const jwt = await signJwt({ keyP8: pem, keyId: 'ABC123DEFG', teamId: 'R2GGK3VN2C' }, Date.UTC(2026, 9, 26));
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
