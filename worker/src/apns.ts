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

export interface ProviderToken {
  token: string;
  issuedAt: number;
  keyId: string;
  teamId: string;
}

// Where the token is kept so it outlives the Checker restarting, which every deploy does.
export interface TokenStore {
  get(): Promise<ProviderToken | undefined>;
  put(token: ProviderToken): Promise<void>;
}

export class Apns {
  // Shared, so an alert going to several iPhones at once is sent with one token. APNs counts each new
  // token as an update and answers 429 TooManyProviderTokenUpdates to more than one in 20 minutes.
  private jwt?: Promise<ProviderToken>;

  constructor(
    private readonly config: ApnsConfig,
    private readonly store?: TokenStore,
  ) {}

  private async bearer(): Promise<string> {
    const pending = this.jwt;
    if (pending) {
      const jwt = await pending;
      if (Date.now() - jwt.issuedAt <= JWT_LIFETIME_MS) return jwt.token;
      // Another send got here first and is already fetching the next one.
      if (this.jwt !== pending) return this.bearer();
    }
    const next = (this.jwt = this.current());
    // One that failed isn't kept, so the next alert tries again.
    next.catch(() => {
      if (this.jwt === next) this.jwt = undefined;
    });
    return (await next).token;
  }

  // The stored token while it's young enough and from this key, or else a newly signed one. Storage
  // failing costs at most a token update, never the alert.
  private async current(): Promise<ProviderToken> {
    const { keyId, teamId } = this.config;
    const stored = await this.store?.get().catch(() => undefined);
    if (stored && stored.keyId === keyId && stored.teamId === teamId && Date.now() - stored.issuedAt <= JWT_LIFETIME_MS) return stored;
    const issuedAt = Date.now();
    const jwt = { token: await signJwt(this.config, issuedAt), issuedAt, keyId, teamId };
    await this.store?.put(jwt).catch(() => {});
    return jwt;
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
