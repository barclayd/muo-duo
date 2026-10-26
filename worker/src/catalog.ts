export interface Model {
  part: string;
  name: string;
  capacity: string;
  colour: string;
  slug: string;
}

// UK part numbers, taken from https://www.apple.com/uk/shop/buy-iphone/iphone-duo
export const MODELS: readonly Model[] = [
  { part: 'MK2D4QN/A', name: '256GB Star White', capacity: '256GB', colour: 'Star White', slug: '256gb-star-white' },
  { part: 'MK2E4QN/A', name: '256GB Night Sky', capacity: '256GB', colour: 'Night Sky', slug: '256gb-night-sky' },
  { part: 'MK2F4QN/A', name: '512GB Star White', capacity: '512GB', colour: 'Star White', slug: '512gb-star-white' },
  { part: 'MK2G4QN/A', name: '512GB Night Sky', capacity: '512GB', colour: 'Night Sky', slug: '512gb-night-sky' },
  { part: 'MK2H4QN/A', name: '1TB Star White', capacity: '1TB', colour: 'Star White', slug: '1tb-star-white' },
  { part: 'MK2J4QN/A', name: '1TB Night Sky', capacity: '1TB', colour: 'Night Sky', slug: '1tb-night-sky' },
  { part: 'MK2K4QN/A', name: '2TB Star White', capacity: '2TB', colour: 'Star White', slug: '2tb-star-white' },
  { part: 'MK2L4QN/A', name: '2TB Night Sky', capacity: '2TB', colour: 'Night Sky', slug: '2tb-night-sky' },
];

export const DEFAULT_PARTS = ['MK2D4QN/A', 'MK2E4QN/A', 'MK2F4QN/A', 'MK2G4QN/A'];

// Where to look, and which of the stores Apple lists there to watch. Apple answers with the 12 stores
// nearest `search` however far away they are, so `stores` keeps out the ones in other regions.
export interface Area {
  name: string;
  search: string;
  stores: string[];
}

export const DEFAULT_AREAS: Area[] = [
  {
    name: 'London',
    search: 'NW6 5PU',
    // Regent Street, Covent Garden, Brompton Road, Battersea, White City, Brent Cross, Stratford City,
    // Bentall Centre (Kingston), Bromley, Watford, Lakeside, Bluewater
    stores: ['R092', 'R245', 'R329', 'R762', 'R226', 'R163', 'R410', 'R227', 'R496', 'R527', 'R242', 'R113'],
  },
  {
    name: 'South of England',
    // Dorchester, Dorset. Its 12 nearest stores include every one in the south outside London, with
    // Brighton 9th; from Exeter, Brighton is 12th and would be the first to drop off the list.
    search: 'DT1 1XJ',
    // Brighton, Basingstoke, Reading, Southampton, Bath, Bristol (Cribbs Causeway)
    stores: ['R244', 'R482', 'R176', 'R174', 'R335', 'R393'],
  },
  {
    name: 'Manchester',
    search: 'Manchester',
    // Manchester Arndale, Trafford Centre
    stores: ['R215', 'R136'],
  },
];

export const isKnownPart = (part: string) => MODELS.some((m) => m.part === part);

export const findModel = (part: string) => MODELS.find((m) => m.part === part);

export const productUrl = (model: Model) =>
  `https://www.apple.com/uk/shop/buy-iphone/iphone-duo/7.6-inch-display-${model.slug}`;

// Apple's transparent product shot for the model's colour, as on the buy page.
export const imageUrl = (model: Model) =>
  `https://store.storeimages.cdn-apple.com/1/as-images.apple.com/is/iphone-duo-finish-select-${model.colour.toLowerCase().replace(' ', '-')}-202609_GEO_EMEA?wid=940&hei=1112&fmt=png-alpha`;

// The pickup endpoint never reports the part asked about, only its in-stock sibling colours, so
// watching a model means asking about every colour at its capacity.
export function partsToQuery(watched: readonly string[]) {
  const capacities = new Set(watched.map((part) => findModel(part)?.capacity));
  return MODELS.filter((m) => capacities.has(m.capacity)).map((m) => m.part);
}
