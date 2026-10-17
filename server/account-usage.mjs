// Account identity, credit details and arbitrary backend banners stay on the PC.
export function accountUsage(value, updatedAt = Date.now()) {
  const buckets = value?.rateLimitsByLimitId;
  const legacy = value?.rateLimits;
  const source = buckets?.codex || ((!legacy?.limitId || legacy.limitId === 'codex') ? legacy : null);
  const window = row => {
    if (!row || typeof row.usedPercent !== 'number' || !Number.isFinite(row.usedPercent) || row.usedPercent < 0) return null;
    const usedPercent = Math.min(100, row.usedPercent);
    return { remainingPercent:100-usedPercent,
      windowDurationMins:Number.isSafeInteger(row.windowDurationMins) && row.windowDurationMins > 0 ? row.windowDurationMins : null,
      resetsAt:Number.isSafeInteger(row.resetsAt) && row.resetsAt > 0 ? row.resetsAt : null };
  };
  const primary = window(source?.primary), secondary = window(source?.secondary);
  return {available:!!(primary || secondary), primary, secondary, updatedAt, stale:false};
}
