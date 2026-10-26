import { expect, test } from 'bun:test';
import { findModel, imageUrl, partsToQuery } from '../src/catalog';

test('asks about every colour at each watched capacity', () => {
  expect(partsToQuery(['MK2E4QN/A'])).toEqual(['MK2D4QN/A', 'MK2E4QN/A']);
  expect(partsToQuery(['MK2D4QN/A', 'MK2E4QN/A', 'MK2F4QN/A', 'MK2G4QN/A'])).toEqual([
    'MK2D4QN/A',
    'MK2E4QN/A',
    'MK2F4QN/A',
    'MK2G4QN/A',
  ]);
});

test("points at Apple's product shot for the model's colour", () => {
  expect(imageUrl(findModel('MK2E4QN/A')!)).toContain('/iphone-duo-finish-select-night-sky-202609_GEO_EMEA?');
  expect(imageUrl(findModel('MK2F4QN/A')!)).toContain('/iphone-duo-finish-select-star-white-202609_GEO_EMEA?');
});
