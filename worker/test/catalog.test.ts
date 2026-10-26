import { expect, test } from 'bun:test';
import { partsToQuery } from '../src/catalog';

test('asks about every colour at each watched capacity', () => {
  expect(partsToQuery(['MK2E4QN/A'])).toEqual(['MK2D4QN/A', 'MK2E4QN/A']);
  expect(partsToQuery(['MK2D4QN/A', 'MK2E4QN/A', 'MK2F4QN/A', 'MK2G4QN/A'])).toEqual([
    'MK2D4QN/A',
    'MK2E4QN/A',
    'MK2F4QN/A',
    'MK2G4QN/A',
  ]);
});
