// Set with `wrangler secret put` rather than in wrangler.jsonc, so they never land in git.
interface Env {
  API_TOKEN: string;
  APNS_KEY: string;
  APNS_KEY_ID: string;
}
