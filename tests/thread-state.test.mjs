import test from 'node:test';
import assert from 'node:assert/strict';
import { ThreadState, getTurns } from '../server/thread-state.mjs';

test('rebuilds state from a snapshot and ordered object/array changes', () => {
  const store = new ThreadState();
  store.apply({ type: 'snapshot', revision: 1, conversationState: { id: 'test', turns: [{ text: 'a' }], requests: [] } });
  store.apply({ type: 'patches', baseRevision: 1, revision: 2, patches: [
    { op: 'replace', path: ['turns', 0, 'text'], value: 'b' },
    { op: 'add', path: ['turns', 1], value: { text: 'c' } },
    { op: 'add', path: ['requests', 0], value: { id: 7 } },
  ] });
  store.apply({ type: 'patches', baseRevision: 2, revision: 3, patches: [{ op: 'remove', path: ['requests', 0] }] });
  assert.deepEqual(store.state, { id: 'test', turns: [{ text: 'b' }, { text: 'c' }], requests: [] });
  assert.equal(store.revision, 3);
});

test('gaps and invalid batches cannot partially mutate state or revision', () => {
  const store = new ThreadState();
  store.apply({ type: 'snapshot', revision: 3, conversationState: { id: 'test', turns: [] } });
  assert.throws(() => store.apply({ type: 'patches', baseRevision: 2, revision: 4, patches: [] }), /gap/);
  assert.throws(() => store.apply({ type: 'patches', baseRevision: 3, revision: 4, patches: [
    { op: 'replace', path: ['id'], value: 'changed' },
    { op: 'replace', path: ['missing', 'text'], value: 'invalid' },
  ] }), /parent/);
  assert.equal(store.state.id, 'test');
  assert.equal(store.revision, 3);
});

test('rejects prototype pollution and invalid array offsets', () => {
  const store = new ThreadState();
  store.apply({ type: 'snapshot', revision: 1, conversationState: { values: [] } });
  for (const path of [['__proto__', 'polluted'], ['constructor', 'prototype', 'polluted'], ['values', -1]]) {
    assert.throws(() => store.apply({ type: 'patches', baseRevision: 1, revision: 2, patches: [{ op: 'add', path, value: true }] }));
  }
  assert.equal({}.polluted, undefined);
  assert.equal(store.revision, 1);
});

test('canonical history order is independent of object insertion order and duplicated entries', () => {
  const state = { turns: [], turnHistory: { kind: 'canonical', history: {
    entitiesByKey: { b: { turnId: 'second' }, a: { turnId: 'first' } },
    islands: [{ entries: [{ value: 'a' }, { value: 'b' }] }, { entries: [{ value: 'b' }] }],
  } } };
  assert.deepEqual(getTurns(state).map(turn => turn.turnId), ['first', 'second']);
});
