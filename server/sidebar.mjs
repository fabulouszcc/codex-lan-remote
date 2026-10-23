import { readFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import path from 'node:path';
export const PINNED_SECTION_ID='01984de2-8f74-7c91-a3b2-5c5e937cf318';

// Read only selected desktop sidebar fields; never forward the persisted state.
export async function desktopSidebar(root = process.env.CODEX_HOME || path.join(homedir(), '.codex')) {
  try { return JSON.parse(await readFile(path.join(root, '.codex-global-state.json'), 'utf8')); } catch { return {}; }
}
export function groupThreads(threads, state = {}) {
  const projects = Object.values(state['local-projects'] || {});
  const assignments = state['thread-project-assignments'] || {};
  const projectless = new Set(state['projectless-thread-ids'] || []);
  const order = state['project-order'] || [];
  const manual = state['sidebar-project-thread-orders'] || {};
  const prefs = state['electron-persisted-atom-state']?.['flat-project-sidebar-preferences-v1'] || {};
  const pinned=state._desktopPinnedIds??state['pinned-thread-ids']??[];
  const pinnedProjects=state['pinned-project-ids']||[];
  const unified=state['electron-persisted-atom-state']?.['unified-sidebar-project-order-v1'];
  const projectOrder=Array.isArray(unified)?unified.filter(id=>typeof id==='string'&&id.startsWith('codex:project:')).map(id=>id.slice(14)):order;
  const normalized = value => String(value || '').replaceAll('\\', '/').replace(/\/$/, '').toLowerCase();
  const result = threads.map((thread, index) => {
    const assigned = assignments[thread.id];
    const project = projects.find(p => p.id === (assigned?.projectId || thread.projectId)) || (!projectless.has(thread.id) && !assigned && projects.filter(p => (p.rootPaths || []).some(root => normalized(thread.cwd) === normalized(root) || normalized(thread.cwd).startsWith(normalized(root) + '/'))).sort((a,b) => Math.max(...b.rootPaths.map(x=>x.length))-Math.max(...a.rootPaths.map(x=>x.length)))[0]);
    const section = thread.section;
    const pinIndex=pinned.indexOf(thread.id),isPinned=pinIndex>=0||section?.id===PINNED_SECTION_ID;
    const projectPinIndex=project?pinnedProjects.indexOf(project.id):-1;
    const groupId = section?.id || (project ? 'project:' + project.id : 'chats');
    const positions = manual[project?.id] || [];
    const manualIndex = Array.isArray(positions) ? positions.indexOf(thread.id) : -1;
    return { id:thread.id, name:thread.name || thread.preview?.slice(0,70) || '未命名聊天', updatedAt:thread.updatedAt,
      groupId:isPinned?'pinned':groupId, groupName:isPinned?'置顶':section?.name || project?.name || '其他会话',pinned:isPinned,projectPinned:!isPinned&&!section&&projectPinIndex>=0,
      groupOrder:isPinned?-20000:section ? -1 : projectPinIndex>=0?-10000+projectPinIndex:project ? (projectOrder.indexOf(project.id) < 0 ? projectOrder.length : projectOrder.indexOf(project.id)) : projectOrder.length + 1,
      order:isPinned?(pinIndex<0?index:pinIndex):prefs.chatSortMode === 'manual' && manualIndex >= 0 ? manualIndex : index };
  });
  if(prefs.projectSortMode==='updated_at'){
    const newest=new Map();for(const row of result)if(row.groupId.startsWith('project:')&&!row.projectPinned)newest.set(row.groupId,Math.max(newest.get(row.groupId)||0,Number(row.updatedAt)||0));
    const ids=[...newest.keys()].sort((a,b)=>newest.get(b)-newest.get(a));for(const row of result)if(row.groupId.startsWith('project:')&&!row.projectPinned)row.groupOrder=ids.indexOf(row.groupId);
  }
  if(prefs.mode==='list'||prefs.mode==='connection')for(const row of result)if(row.groupId==='chats'||row.groupId.startsWith('project:')){row.groupId='chats';row.groupName=prefs.mode==='connection'?'这台电脑':'全部会话';row.groupOrder=order.length+1;}
  return result.sort((a,b) => a.groupOrder-b.groupOrder || a.groupId.localeCompare(b.groupId) || a.order-b.order);
}
