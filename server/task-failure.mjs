// Classify in memory; never forward provider response bodies or billing credentials.
export function taskFailure(turn) {
  const error=turn?.error;if(!error||error.willRetry===true)return '';
  const message=typeof error==='string'?error:JSON.stringify({code:error.codexErrorInfo??error.code,message:error.message,details:error.additionalDetails});
  if(/context.?window|context.?length|maximum.*tokens|too many tokens/i.test(message))return 'contextLimit';
  if(/usage.?limit|insufficient.?quota|quota.?exceed|credit|balance|billing|payment|额度|余额|token.{0,12}(?:exhaust|deplet|insufficient)/i.test(message))return 'quota';
  if(/rate.?limit|too many requests|\b429\b|限流/i.test(message))return 'rateLimit';
  return 'failed';
}
