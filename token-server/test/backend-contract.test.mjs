import test from 'node:test';
import assert from 'node:assert/strict';
import { boundedTokenTtl, healthPayload, publicHealth, validLiveKitServer } from '../build-test/backendContract.js';

const fixture = { LIVEKIT_URL: 'wss://fixture.invalid', LIVEKIT_API_KEY: 'test-key-never-real', LIVEKIT_API_SECRET: 'test-secret-never-real', FIREBASE_PROJECT_ID: 'test-project', GEMINI_API_KEY: 'test-ai-never-real' };
test('health payload never contains configured endpoints, credentials or account/project data', () => {
  const payload = healthPayload(fixture);
  assert.equal(payload.ready, true);
  const serialized = JSON.stringify(payload);
  for (const value of Object.values(fixture)) assert.equal(serialized.includes(value), false);
  assert.equal(payload.credentialPairVerified, false);
  assert.equal(payload.capabilities.tokenAuthentication, 'firebase-id-token');
});
test('readiness does not claim routing or offline Supertonic capabilities', () => {
  const payload = healthPayload(fixture);
  assert.equal(payload.capabilities.routingProxyConfigured, false);
  assert.equal(JSON.stringify(payload).includes('supertonic'), false);
});
test('health GET/HEAD contract is public but returns no tokens and no HEAD body', async () => {
  const get = publicHealth('GET', fixture);
  assert.equal(get.status, 200);
  const body = await get.text();
  assert.equal(body.includes('participantToken'), false);
  assert.equal(publicHealth('HEAD', fixture).body, null);
  assert.equal(publicHealth('POST', fixture).status, 405);
});
test('missing Firebase or LiveKit configuration produces degraded readiness', () => {
  assert.equal(publicHealth('GET', { ...fixture, LIVEKIT_API_SECRET: '' }).status, 503);
  assert.equal(healthPayload({ ...fixture, FIREBASE_PROJECT_ID: '' }).ready, false);
});
test('LiveKit URL requires WSS and excludes embedded credentials, query and fragment', () => {
  assert.equal(validLiveKitServer('wss://fixture.invalid'), true);
  for (const url of [undefined, '', 'https://fixture.invalid', 'ws://fixture.invalid', 'wss://user:password@fixture.invalid', 'wss://fixture.invalid?token=fake', 'wss://fixture.invalid#fake']) assert.equal(validLiveKitServer(url), false);
});
test('token TTL keeps the previous default and cannot become negative or unbounded', () => {
  for (const value of [undefined, '', 'NaN', '-1', '0']) assert.equal(boundedTokenTtl(value), 14400);
  assert.equal(boundedTokenTtl('60'), 300);
  assert.equal(boundedTokenTtl('3600'), 3600);
  assert.equal(boundedTokenTtl('99999999'), 14400);
});
