// Applies the desktop's observed Immer-style JSON patches with array paths.
// A gap requires a fresh snapshot; callers must not silently apply later patches.
export class ThreadState {
  state = null;
  revision = null;
  apply(change) {
    if (!Number.isSafeInteger(change.revision) || change.revision < 0) throw new Error('Invalid state revision');
    if (change.type === 'snapshot') {
      if (!change.conversationState || typeof change.conversationState !== 'object') throw new Error('Invalid snapshot');
      this.state = structuredClone(change.conversationState);
      this.revision = change.revision;
      return this.state;
    }
    if (change.type !== 'patches' || this.state === null || change.baseRevision !== this.revision || change.revision <= this.revision) {
      throw new Error('State revision gap: fetch a fresh snapshot');
    }
    if (!Array.isArray(change.patches)) throw new Error('Invalid state patches');
    let next = structuredClone(this.state);
    for (const patch of change.patches) next = applyPatch(next, patch);
    this.state = next;
    this.revision = change.revision;
    return this.state;
  }
}

function applyPatch(root, patch) {
  if (!['add', 'remove', 'replace'].includes(patch.op) || !Array.isArray(patch.path)) throw new Error('Unsupported state patch');
  if (patch.path.some(key => !['string', 'number'].includes(typeof key) || ['__proto__', 'prototype', 'constructor'].includes(String(key)))) throw new Error('Unsafe patch path');
  if (patch.path.length === 0) {
    if (patch.op === 'remove') throw new Error('Cannot remove root state');
    return structuredClone(patch.value);
  }
  let parent = root;
  for (const key of patch.path.slice(0, -1)) {
    if (!parent || typeof parent !== 'object' || !Object.hasOwn(parent, key)) throw new Error('Missing patch parent');
    parent = parent[key];
  }
  if (!parent || typeof parent !== 'object') throw new Error('Invalid patch parent');
  const key = patch.path.at(-1);
  if (Array.isArray(parent)) {
    const index = Number(key);
    const maximum = patch.op === 'add' ? parent.length : parent.length - 1;
    if (!Number.isInteger(index) || index < 0 || index > maximum) throw new Error('Invalid patch array index');
    if (patch.op === 'add') parent.splice(index, 0, structuredClone(patch.value));
    else if (patch.op === 'remove') parent.splice(index, 1);
    else parent[index] = structuredClone(patch.value);
  } else {
    if (patch.op !== 'add' && !Object.hasOwn(parent, key)) throw new Error('Missing patch target');
    if (patch.op === 'remove') delete parent[key];
    else parent[key] = structuredClone(patch.value);
  }
  return root;
}

export function getTurns(state) {
  if (state?.turnHistory?.kind !== 'canonical') return state?.turns || [];
  const history = state.turnHistory.history;
  const result = [];
  const seen = new Set();
  for (const island of history?.islands || []) {
    for (const entry of island.entries || []) {
      const key = entry.value;
      if (seen.has(key)) continue;
      const turn = history.entitiesByKey?.[key];
      if (turn) { seen.add(key); result.push(turn); }
    }
  }
  return result;
}

import { turnTimeline, turnInput } from './turn-timeline.mjs';
export function summarizeThread(state) {
  const turns = getTurns(state);
  const active=turns.findLast(turn=>turn.status==='inProgress');
  let activity;
  if(active){
    const items=active.items||[],last=items.at(-1),reasoning=items.findLast(item=>item.type==='reasoning');
    const visible=reasoning?.summary;
    const summary=(typeof visible==='string'?visible:Array.isArray(visible)?visible.map(part=>typeof part==='string'?part:typeof part?.text==='string'?part.text:'').join('\n'):'').slice(0,2400);
    activity={label:state?.requests?.length?'等待处理审批或输入…':last?.type==='commandExecution'&&last.status==='inProgress'?'正在执行工具…':'正在思考…',summary};
  }
  return {
    activity,
    id: state?.id, cwd: state?.cwd, runtimeStatus: state?.threadRuntimeStatus?.type,
    turnCount: turns.length,
    turns: turns.map(turn => ({
      id: turn.turnId, status: turn.status, startedAtMs: turn.turnStartedAtMs, durationMs: turn.durationMs,
      input: turnInput(turn),
      agentMessages: (turn.items || []).filter(item => item.type === 'agentMessage').map(item => item.text),
      timeline: turnTimeline(turn.items),
      itemTypes: (turn.items || []).map(item => item.type),
      toolResults: (turn.items || []).filter(item => ['commandExecution', 'fileChange'].includes(item.type)).map(item => ({
        id: item.id, type: item.type, status: item.status, exitCode: item.exitCode,
        ...(item.type === 'fileChange' ? { changeCount: item.changes?.length ?? 0 } : {}),
      })),
    })),
    pendingRequests: (state?.requests || []).map(request => ({ type: request.type, id: request.id, keys: Object.keys(request) })),
  };
}
