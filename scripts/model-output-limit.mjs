/** Preserve the Java workflow's output allowance at the subscription adapter boundary. */
export function modelOutputLimit(body) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) throw new Error('Invalid model request');
  const modern = body.max_completion_tokens;
  const legacy = body.max_tokens;
  if (modern !== undefined && legacy !== undefined) throw new Error('Supply one output token limit');
  const limit = modern !== undefined ? modern : legacy !== undefined ? legacy : 4096;
  if (!Number.isSafeInteger(limit) || limit < 128 || limit > 65536) throw new Error('Invalid output token limit');
  return limit;
}
