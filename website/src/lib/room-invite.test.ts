import { test } from 'node:test';
import assert from 'node:assert/strict';
import { appLinkFor, readRoomCode } from './room-invite.ts';

test('reads the code from the query string', () => {
  assert.equal(readRoomCode('?code=ab12cd', '/join/'), 'AB12CD');
});

test('reads the code from a /join/<code> path', () => {
  assert.equal(readRoomCode('', '/join/XY99ZZ'), 'XY99ZZ');
  assert.equal(readRoomCode('', '/join/XY99ZZ/'), 'XY99ZZ');
});

test('rejects anything that is not a six character room code', () => {
  assert.equal(readRoomCode('?code=abc', '/join/'), null);
  assert.equal(readRoomCode('?code=<script>', '/join/'), null);
  assert.equal(readRoomCode('', '/join/'), null);
  assert.equal(readRoomCode('', '/privacy'), null);
});

test('builds the deep link the app registers', () => {
  assert.equal(appLinkFor('AB12CD'), 'cards://join/AB12CD');
});
