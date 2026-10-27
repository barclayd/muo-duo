// What the APNs and Firebase senders share: how a send turned out, and the pieces of signing a JWT.

export type PushResult = { ok: true } | { ok: false; status: number; reason: string; deviceGone: boolean };

export const base64url = (bytes: ArrayBuffer | Uint8Array) =>
  btoa(String.fromCharCode(...new Uint8Array(bytes)))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');

export const encodeJson = (value: object) => base64url(new TextEncoder().encode(JSON.stringify(value)));

// The key inside a PEM file, as Web Crypto's pkcs8 import wants it.
export const pemToDer = (pem: string) => Uint8Array.from(atob(pem.replace(/-----[^-]+-----|\s/g, '')), (c) => c.charCodeAt(0));
