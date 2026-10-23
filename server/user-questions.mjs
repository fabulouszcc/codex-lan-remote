import { getTurns, activeTurn } from './thread-state.mjs';
import { userText } from './turn-timeline.mjs';

export function pendingQuestions(state) {
  const turns = getTurns(state), answered = new Set();
  const active=activeTurn(state);
  if(!active)return [];
  const completed=new Set((active.items||[]).filter(item=>item.type==='userInputResponse'&&item.completed===true).map(item=>String(item.requestId)));
  for (const turn of turns) for (const item of turn.items || []) {
    if (!['userMessage', 'steeringUserMessage'].includes(item.type)) continue;
    if (item.type === 'steeringUserMessage' && item.status && item.status !== 'accepted') continue;
    const text = userText(item.content || item.input).trim();
    if (!text.startsWith('<send_user_message_question_reply>') || !text.endsWith('</send_user_message_question_reply>')) continue;
    try {
      const parsed = JSON.parse(text.slice(34, -35));
      for (const reply of Array.isArray(parsed) ? parsed : [parsed]) answered.add(reply.questionItemId);
    } catch { /* Malformed user text is never treated as a response. */ }
  }
  const rows = (state?.requests || []).filter(r => /requestUserInput/.test(r.type || r.method || '')
    && (!r.params?.turnId||r.params.turnId===active.turnId) && !completed.has(String(r.id??r.requestId))).map(r => ({
    id: r.id ?? r.requestId, kind: 'native', turnId: r.params?.turnId,
    questions: (r.params?.questions || []).map(q => ({ id: q.id, title: q.question, header: q.header,
      isOther: !!q.isOther, isSecret: !!q.isSecret, options: (q.options || []).map(o => ({ label: o.label, description: o.description || '' })) }))
  }));
  for (const turn of [active]) for (const item of turn.items || []) {
    if (item.type !== 'agentMessage') continue;
    const questions = (item.questions || []).map((q, i) => ({ id: JSON.stringify(['request_user_input_async', item.id, i]),
      title: q.title, isOther: true, options: (q.options || []).map(label => ({ label, description: '' })) })).filter(q => !answered.has(q.id));
    if (questions.length) rows.push({ id: item.id, kind: 'async', turnId: turn.turnId, questions });
  }
  return rows.filter(r => r.questions.length);
}

export function validateAnswers(request, answers) {
  if (!answers || typeof answers !== 'object' || Array.isArray(answers) || Object.keys(answers).length !== request.questions.length) throw Error('请回答全部问题');
  for (const q of request.questions) {
    const values = answers[q.id]?.answers;
    if (!Array.isArray(values) || values.length !== 1 || typeof values[0] !== 'string' || !values[0].trim() || values[0].length > 16000) throw Error('回答不能为空或过长');
    if (q.options.length && !q.isOther && !q.options.some(o => o.label === values[0])) throw Error('回答不在可选项中');
  }
  return Object.fromEntries(request.questions.map(q => [q.id, { answers: [...answers[q.id].answers] }]));
}
