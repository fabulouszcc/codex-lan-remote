import { createHash } from 'node:crypto';

// Keep only digests per authorized session, never copies of chat bodies.
export function mobileStateWindow(threads, previous, version, after) {
  const delta = !!previous && String(previous.version) === after;
  const signatures = new Map();
  const rows = threads.map(thread => {
    const turns = [];
    for (const turn of thread.turns || []) {
      const key = JSON.stringify([thread.id, turn.id]);
      const digest = createHash('sha256').update(JSON.stringify(turn)).digest('hex');
      signatures.set(key, digest);
      if (!delta || previous.signatures.get(key) !== digest) turns.push(turn);
    }
    return { ...thread, turns, turnOrder: (thread.turns || []).map(turn => turn.id) };
  });
  return { payload: { sync: delta ? 'delta' : 'full', ...(delta ? { baseVersion: previous.version } : {}), threads: rows }, baseline: { version, signatures } };
}
