import { base64url, encodeJson, pemToDer, type PushResult } from './push';

export interface FcmDevice {
  platform: 'android';
  token: string;
  registeredAt: string;
}

// The parts of the service account key Firebase downloads that sending needs.
export interface ServiceAccount {
  project_id: string;
  private_key_id: string;
  private_key: string;
  client_email: string;
  token_uri: string;
}

const SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';

// Google's access tokens last an hour; the next is fetched a few minutes before one runs out.
const REFRESH_MARGIN_MS = 5 * 60 * 1000;

const FCM_ERROR = 'type.googleapis.com/google.firebase.fcm.v1.FcmError';

const GONE_REASONS = new Set(['UNREGISTERED', 'SENDER_ID_MISMATCH']);

interface FcmErrorBody {
  error?: { status?: string; message?: string; details?: { '@type'?: string; errorCode?: string }[] };
}

type Access = { token: string; expiresAt: number };

// The key arrives as one secret. One that won't parse is reported without JSON.parse's own message,
// which quotes the text around the fault, so none of the private key reaches the logs.
export function parseServiceAccount(json: string): ServiceAccount {
  let parsed: unknown;
  try {
    parsed = JSON.parse(json);
  } catch {
    throw new Error('FCM_SERVICE_ACCOUNT is not JSON');
  }
  const account = (parsed ?? {}) as Record<string, unknown>;
  const fields = ['project_id', 'private_key_id', 'private_key', 'client_email', 'token_uri'] as const;
  const missing = fields.filter((f) => typeof account[f] !== 'string' || !account[f]);
  if (missing.length) throw new Error(`FCM_SERVICE_ACCOUNT has no ${missing.join(', ')}`);
  return account as unknown as ServiceAccount;
}

export async function signJwt(account: ServiceAccount, issuedAt: number) {
  const iat = Math.floor(issuedAt / 1000);
  const header = { alg: 'RS256', typ: 'JWT', kid: account.private_key_id };
  const claims = { iss: account.client_email, scope: SCOPE, aud: account.token_uri, iat, exp: iat + 3600 };
  const signingInput = `${encodeJson(header)}.${encodeJson(claims)}`;
  const key = await crypto.subtle.importKey('pkcs8', pemToDer(account.private_key), { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
  const signature = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(signingInput));
  return `${signingInput}.${base64url(signature)}`;
}

// Data messages carry only strings, so the APNs alert is flattened into the keys the Android app reads.
// The app shows them itself, which a notification message wouldn't let it do while it's closed.
export function fcmData(payload: object): Record<string, string> {
  const { aps, url, alarm } = payload as { aps?: { alert?: { title?: string; body?: string }; 'thread-id'?: string }; url?: string; alarm?: boolean };
  const thread = aps?.['thread-id'];
  return {
    title: aps?.alert?.title ?? '',
    body: aps?.alert?.body ?? '',
    ...(url ? { url } : {}),
    ...(alarm ? { alarm: 'true' } : {}),
    ...(thread ? { thread } : {}),
  };
}

export class Fcm {
  // Shared, so an alert going to several phones at once waits on one token exchange.
  private access?: Promise<Access>;

  constructor(private readonly account: ServiceAccount) {}

  private async bearer(): Promise<string> {
    const pending = this.access;
    if (pending) {
      const access = await pending;
      if (Date.now() < access.expiresAt - REFRESH_MARGIN_MS) return access.token;
      // Another send got here first and is already fetching the next one.
      if (this.access !== pending) return this.bearer();
    }
    const next = (this.access = this.exchange());
    return (await next).token;
  }

  private exchange() {
    const access = (async (): Promise<Access> => {
      const now = Date.now();
      const res = await fetch(this.account.token_uri, {
        method: 'POST',
        headers: { 'content-type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: await signJwt(this.account, now) }),
      });
      const body = (await res.json().catch(() => ({}))) as { access_token?: string; expires_in?: number; error?: string; error_description?: string };
      if (!res.ok || !body.access_token) throw new Error(`Google token exchange failed: ${res.status} ${body.error_description ?? body.error ?? 'Unknown'}`);
      return { token: body.access_token, expiresAt: now + (body.expires_in ?? 3600) * 1000 };
    })();
    // A failed exchange isn't kept, so the next alert tries again.
    access.catch(() => {
      if (this.access === access) this.access = undefined;
    });
    return access;
  }

  async send(device: FcmDevice, payload: object): Promise<PushResult> {
    const res = await fetch(`https://fcm.googleapis.com/v1/projects/${this.account.project_id}/messages:send`, {
      method: 'POST',
      headers: { authorization: `Bearer ${await this.bearer()}`, 'content-type': 'application/json' },
      // High priority wakes the phone from Doze to show it straight away.
      body: JSON.stringify({ message: { token: device.token, data: fcmData(payload), android: { priority: 'HIGH' } } }),
    });
    if (res.ok) return { ok: true };

    // The access token was refused, so the next send fetches another.
    if (res.status === 401) this.access = undefined;
    const { error } = (await res.json().catch(() => ({}))) as FcmErrorBody;
    const reason = error?.details?.find((d) => d['@type'] === FCM_ERROR)?.errorCode ?? error?.status ?? 'Unknown';
    // A token that isn't one at all comes back as a plain bad request, which says so in its message.
    const malformed = reason === 'INVALID_ARGUMENT' && /registration token/i.test(error?.message ?? '');
    return { ok: false, status: res.status, reason, deviceGone: GONE_REASONS.has(reason) || malformed };
  }
}
