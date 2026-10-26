export interface Model {
  part: string;
  name: string;
  capacity: string;
  slug: string;
}

// UK part numbers, taken from https://www.apple.com/uk/shop/buy-iphone/iphone-duo
export const MODELS: readonly Model[] = [
  { part: 'MK2D4QN/A', name: '256GB Star White', capacity: '256GB', slug: '256gb-star-white' },
  { part: 'MK2E4QN/A', name: '256GB Night Sky', capacity: '256GB', slug: '256gb-night-sky' },
  { part: 'MK2F4QN/A', name: '512GB Star White', capacity: '512GB', slug: '512gb-star-white' },
  { part: 'MK2G4QN/A', name: '512GB Night Sky', capacity: '512GB', slug: '512gb-night-sky' },
  { part: 'MK2H4QN/A', name: '1TB Star White', capacity: '1TB', slug: '1tb-star-white' },
  { part: 'MK2J4QN/A', name: '1TB Night Sky', capacity: '1TB', slug: '1tb-night-sky' },
  { part: 'MK2K4QN/A', name: '2TB Star White', capacity: '2TB', slug: '2tb-star-white' },
  { part: 'MK2L4QN/A', name: '2TB Night Sky', capacity: '2TB', slug: '2tb-night-sky' },
];

export const DEFAULT_PARTS = ['MK2D4QN/A', 'MK2E4QN/A', 'MK2F4QN/A', 'MK2G4QN/A'];

export const isKnownPart = (part: string) => MODELS.some((m) => m.part === part);

export const findModel = (part: string) => MODELS.find((m) => m.part === part);

export const productUrl = (model: Model) =>
  `https://www.apple.com/uk/shop/buy-iphone/iphone-duo/7.6-inch-display-${model.slug}`;

// The pickup endpoint reports in-stock *similar* models rather than the one asked about, so
// watching a model means asking about every colour at its capacity.
export function partsToQuery(watched: readonly string[]) {
  const capacities = new Set(watched.map((part) => findModel(part)?.capacity));
  return MODELS.filter((m) => capacities.has(m.capacity)).map((m) => m.part);
}
