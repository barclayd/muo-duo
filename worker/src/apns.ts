import { base64url, encodeJson, pemToDer, type PushResult } from './push';

// Devices registered before the Android app have no platform: they're all iPhones.
export interface ApnsDevice {
  platform?: 'ios';
  token: string;
  environment: 'sandbox' | 'production';
  registeredAt: string;
}

export interface ApnsConfig {
  keyP8: string;
  keyId: string;
  teamId: string;
  topic: string;
}

const HOSTS = {
  sandbox: 'https://api.sandbox.push.apple.com',
  production: 'https://api.push.apple.com',
};

// APNs rejects tokens older than an hour and throttles ones refreshed more often than every 20 minutes.
const JWT_LIFETIME_MS = 50 * 60 * 1000;

const GONE_REASONS = new Set(['BadDeviceToken', 'Unregistered', 'DeviceTokenNotForTopic']);

const importP8 = (pem: string) => crypto.subtle.importKey('pkcs8', pemToDer(pem), { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign']);

export async function signJwt(config: Pick<ApnsConfig, 'keyP8' | 'keyId' | 'teamId'>, issuedAt: number) {
  const signingInput = `${encodeJson({ alg: 'ES256', kid: config.keyId })}.${encodeJson({ iss: config.teamId, iat: Math.floor(issuedAt / 1000) })}`;
  // Web Crypto emits the raw r||s signature that JWS ES256 expects.
  const signature = await crypto.subtle.sign(
    { name: 'ECDSA', hash: 'SHA-256' },
    await importP8(config.keyP8),
    new TextEncoder().encode(signingInput),
  );
  return `${signingInput}.${base64url(signature)}`;
}

export class Apns {
  private jwt?: { token: string; issuedAt: number };

  constructor(private readonly config: ApnsConfig) {}

  private async bearer() {
    const now = Date.now();
    if (!this.jwt || now - this.jwt.issuedAt > JWT_LIFETIME_MS) {
      this.jwt = { token: await signJwt(this.config, now), issuedAt: now };
    }
    return this.jwt.token;
  }

  async send(device: ApnsDevice, payload: object): Promise<PushResult> {
    const res = await fetch(`${HOSTS[device.environment]}/3/device/${device.token}`, {
      method: 'POST',
      headers: {
        authorization: `bearer ${await this.bearer()}`,
        'apns-topic': this.config.topic,
        'apns-push-type': 'alert',
        'apns-priority': '10',
        'content-type': 'application/json',
      },
      body: JSON.stringify(payload),
    });
    if (res.ok) return { ok: true };

    const reason = ((await res.json().catch(() => ({}))) as { reason?: string }).reason ?? 'Unknown';
    return { ok: false, status: res.status, reason, deviceGone: res.status === 410 || GONE_REASONS.has(reason) };
  }
}
