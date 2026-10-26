// Apple's pickup-message-recommendations endpoint. For the part asked about it lists, per store, the
// *other* colours at the same capacity that are in stock; the queried part itself is never included.
// So a part's stock only shows up when one of its sibling colours is queried (see partsToQuery). It's
// quick, but it names only one store with stock, however many have it.
//
// The older pickup-message endpoint answers for several parts at once, with every store's stock. It's
// slower and Apple is warier of it, so it's asked less often (see sweep.ts).

const USER_AGENT =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/27.0 Safari/605.1.15';

export interface Sighting {
  part: string;
  storeNumber: string;
  storeName: string;
  quote: string;
}

// Only stock to collect today is alerted on. Once the stores close, Apple quotes everything for
// tomorrow, so from then until midnight nothing counts.
export const forToday = (s: Sighting) => /\btoday\b/i.test(s.quote);

export interface Store {
  number: string;
  name: string;
  city: string;
  latitude: number;
  longitude: number;
}

// The 12 stores nearest the location asked about, however far away, and what they have in stock.
export interface Pickup {
  stores: Store[];
  sightings: Sighting[];
}

export class AppleError extends Error {
  constructor(
    message: string,
    readonly status?: number,
  ) {
    super(message);
  }
}

interface StoreList {
  errorMessage?: string;
  stores?: {
    storeNumber: string;
    storeName: string;
    city?: string;
    storelatitude?: number;
    storelongitude?: number;
    partsAvailability?: Record<string, { pickupDisplay?: string; pickupSearchQuote?: string }>;
  }[];
}

// Both endpoints describe stores the same way; recommendations wraps them in PickupMessage.
interface PickupResponse {
  body?: StoreList & { PickupMessage?: StoreList };
}

export function parsePickup(json: unknown): Pickup {
  const body = (json as PickupResponse).body;
  const message = body?.PickupMessage ?? body;
  const stores = message?.stores;
  if (!Array.isArray(stores)) throw new AppleError('Unexpected response shape: no stores');
  // An empty store list would read as "nothing in stock", so treat it as a failed check instead.
  // Apple says why when it can't place the location, e.g. a town name it doesn't recognise.
  if (stores.length === 0) throw new AppleError(message?.errorMessage ? `Apple found no stores: ${message.errorMessage}` : 'Apple returned no stores');

  return {
    stores: stores.map((store) => ({
      number: store.storeNumber,
      name: store.storeName,
      city: store.city ?? '',
      latitude: Number(store.storelatitude),
      longitude: Number(store.storelongitude),
    })),
    sightings: stores.flatMap((store) =>
      Object.entries(store.partsAvailability ?? {})
        .filter(([, availability]) => availability.pickupDisplay === 'available')
        .map(([part, availability]) => ({
          part,
          storeNumber: store.storeNumber,
          storeName: store.storeName,
          quote: availability.pickupSearchQuote ?? 'Available',
        })),
    ),
  };
}

async function get(url: URL, what: string, signal?: AbortSignal): Promise<Pickup> {
  const res = await fetch(url, {
    headers: {
      Accept: '*/*',
      'Accept-Language': 'en-GB,en;q=0.9',
      'User-Agent': USER_AGENT,
      'x-skip-redirect': 'true',
    },
    signal,
  });
  if (!res.ok) throw new AppleError(`Apple returned HTTP ${res.status} for ${what}`, res.status);

  return parsePickup(await res.json());
}

// `location` is a postcode or a town name. Postcodes are safer: Apple doesn't recognise every town
// (Brighton finds nothing) and muddles ambiguous ones (for Dorchester it measures from Oxfordshire).
export async function fetchPickup(part: string, location: string): Promise<Pickup> {
  const url = new URL('https://www.apple.com/uk/shop/pickup-message-recommendations');
  url.search = new URLSearchParams({ fae: 'true', 'mts.0': 'regular', location, product: part }).toString();
  return get(url, part);
}

// Every part asked about, at each of the 12 stores nearest `location`.
export async function fetchStores(parts: readonly string[], location: string, signal?: AbortSignal): Promise<Pickup> {
  const url = new URL('https://www.apple.com/uk/shop/retail/pickup-message');
  const params = new URLSearchParams({ pl: 'true', 'mts.0': 'regular', location });
  for (const [i, part] of parts.entries()) params.set(`parts.${i}`, part);
  url.search = params.toString();
  return get(url, `every store near ${location}`, signal);
}
