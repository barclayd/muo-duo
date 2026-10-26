// Apple's pickup-message-recommendations endpoint. It lists in-stock *similar* models per store
// (for the Duo, the other colour at the same capacity), so we query every watched part and keep
// any watched part that shows up in any response.

const USER_AGENT =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/27.0 Safari/605.1.15';

export interface Sighting {
  part: string;
  storeNumber: string;
  storeName: string;
  quote: string;
}

export class AppleError extends Error {
  constructor(
    message: string,
    readonly status?: number,
  ) {
    super(message);
  }
}

interface PickupResponse {
  body?: {
    PickupMessage?: {
      stores?: {
        storeNumber: string;
        storeName: string;
        partsAvailability?: Record<string, { pickupDisplay?: string; pickupSearchQuote?: string }>;
      }[];
    };
  };
}

export function parsePickup(json: unknown): Sighting[] {
  const stores = (json as PickupResponse).body?.PickupMessage?.stores;
  if (!Array.isArray(stores)) throw new AppleError('Unexpected response shape: no PickupMessage.stores');

  return stores.flatMap((store) =>
    Object.entries(store.partsAvailability ?? {})
      .filter(([, availability]) => availability.pickupDisplay === 'available')
      .map(([part, availability]) => ({
        part,
        storeNumber: store.storeNumber,
        storeName: store.storeName,
        quote: availability.pickupSearchQuote ?? 'Available',
      })),
  );
}

export async function fetchPickup(part: string, postcode: string): Promise<Sighting[]> {
  const url = new URL('https://www.apple.com/uk/shop/pickup-message-recommendations');
  url.search = new URLSearchParams({ fae: 'true', 'mts.0': 'regular', location: postcode, product: part }).toString();

  const res = await fetch(url, {
    headers: {
      Accept: '*/*',
      'Accept-Language': 'en-GB,en;q=0.9',
      'User-Agent': USER_AGENT,
      'x-skip-redirect': 'true',
    },
  });
  if (!res.ok) throw new AppleError(`Apple returned HTTP ${res.status} for ${part}`, res.status);

  return parsePickup(await res.json());
}
