import { messageFiles, fileReference } from './chat-files.mjs';
// Shared projection for live IPC and stored history. Never forward raw items,
// hidden reasoning, encrypted content, media payloads or tool-private metadata.
const toolTypes = new Set(['commandExecution', 'fileChange', 'mcpToolCall', 'dynamicToolCall', 'functionCallOutput', 'webSearch', 'imageView', 'imageGeneration', 'collabAgentToolCall']);
export function userText(input) {
  return (Array.isArray(input) ? input : []).filter(part => ['text','input_text','inputText'].includes(part?.type) && typeof part.text === 'string').map(part => part.text).join('\n');
}
export function turnInput(turn) {
  const messages = turnTimeline(turn.items || []).filter(item => item.type === 'user').map(item => item.text).filter(Boolean);
  return messages.length ? messages.join('\n\n') : userText(turn.params?.input);
}
export function turnTimeline(items = []) {
  // Desktop keeps accepted steering text in a separate item, with input rather
  // than content. A later canonical user item may replace the optimistic one.
  const canonicalUsers = items.filter(item => item?.type === 'userMessage');
  let budget = 24000;
  const clip = (value, limit, row) => {
    if (value == null) return '';
    if (budget <= 0) { row.truncated = true; return ''; }
    const string = typeof value === 'string' ? value : JSON.stringify(value);
    const count = Math.min(limit, Math.max(0, budget)); budget -= Math.min(string.length, count);
    if (string.length > count) row.truncated = true;
    return string.slice(0, count);
  };
  const textContent = value => typeof value === 'string' ? value : Array.isArray(value)
    ? value.filter(block => ['text','input_text','inputText'].includes(block?.type) && typeof block.text === 'string').map(block => block.text).join('\n') : '';
  return items.flatMap((item, index) => {
    if (!item || typeof item !== 'object') return [];
    if (item.type === 'userMessage') { const text=userText(item.content);return [{ id: item.id || `user:${index}`, ...(item.clientId?{clientMessageId:item.clientId}:{}), type: 'user', text, files:messageFiles(text,item.content) }]; }
    if (item.type === 'steeringUserMessage') {
      const replaced = canonicalUsers.some(user =>
        (item.serverUserMessageId && user.id === item.serverUserMessageId) ||
        (item.clientUserMessageId && user.clientId === item.clientUserMessageId) ||
        (item.serverClientUserMessageId && user.clientId === item.serverClientUserMessageId));
      const text=userText(item.input);return replaced ? [] : [{ id: item.id || `steer:${index}`, ...(item.clientUserMessageId?{clientMessageId:item.serverClientUserMessageId||item.clientUserMessageId}:{}), type: 'user', text, files:messageFiles(text,item.input) }];
    }
    if (item.type === 'agentMessage') {const text=item.text || (item.questions || []).map(q => q.title).join('\n\n');return [{ id: item.id || `message:${index}`, type: 'message', text, phase: item.phase, files:messageFiles(text) }];}
    if (!toolTypes.has(item.type)) return [];
    const row = { id: item.id || `tool:${index}`, type: 'tool', toolType: item.type,
      status: item.status || (item.type === 'functionCallOutput' ? 'completed' : ''),
      title: String(item.tool || item.name || ({ commandExecution: '执行命令', fileChange: '修改文件', webSearch: '搜索网页', imageView: '查看图片', imageGeneration: '生成图片' }[item.type]) || '工具调用').slice(0,120) };
    let input = item.command || item.arguments || item.query || item.path || '';
    if (item.type === 'fileChange') input = (item.changes || []).slice(0,12).map(change => `${change.path || ''}\n${String(change.diff || '').slice(0,4000)}`).join('\n\n');
    row.input = clip(input, 6000, row);
    if(item.type==='fileChange')row.files=(item.changes||[]).slice(0,24).flatMap(change=>{const ref=fileReference(change.path);return ref?[{...ref,diff:clip(change.diff,6000,row)}]:[];});
    const output = item.aggregatedOutput ?? textContent(item.contentItems);
    row.output = clip(output || textContent(item.output) || textContent(item.result?.content) || item.error?.message || '', 6000, row);
    if (Number.isInteger(item.exitCode)) row.exitCode = item.exitCode;
    return [row];
  });
}
