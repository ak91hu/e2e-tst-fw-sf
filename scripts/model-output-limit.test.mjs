import test from 'node:test';
import assert from 'node:assert/strict';
import { modelOutputLimit } from './model-output-limit.mjs';

test('subscription request preserves modern and compatibility workflow allowances', () => {
  const modern = JSON.parse('{"model":"configured","max_completion_tokens":2048,"messages":[]}');
  const compatibility = JSON.parse('{"model":"configured","max_tokens":8192,"messages":[]}');
  assert.equal(modelOutputLimit(modern), 2048);
  assert.equal(modelOutputLimit(compatibility), 8192);
  assert.equal(modelOutputLimit({ messages: [] }), 4096);
});
test('malformed or ambiguous allowances fail before provider dispatch', () => {
  for (const invalid of [0, -1, 127, 65537, 1.5, '2048', null, false, NaN, Infinity]) {
    assert.throws(() => modelOutputLimit({ max_completion_tokens: invalid }));
  }
  assert.throws(() => modelOutputLimit({ max_completion_tokens: 4096, max_tokens: 8192 }));
  assert.throws(() => modelOutputLimit('{"max_completion_tokens":2048}'));
});
test('explicit small and large allowances remain bounded without reverting to 16000', () => {
  assert.equal(modelOutputLimit({ max_completion_tokens: 128 }), 128);
  assert.equal(modelOutputLimit({ max_completion_tokens: 65536 }), 65536);
});
