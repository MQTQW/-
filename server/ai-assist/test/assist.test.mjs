import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createAssistHandler } from '../app.mjs';
import { validateRequest, validateResult } from '../schema.mjs';

const token = 'private-test-access-code-32-characters';
const env = { DEEPSEEK_API_KEY: 'test-provider-key', ASSIST_ACCESS_TOKEN: token };
const request = { operation: 'parse', text: '明天八点读书', now: '2026-10-26T15:00:00+08:00', timeZone: 'Asia/Shanghai' };
const draft = { title: '读书', category: '学习', repeatMode: 'ONCE', date: '2026-10-27', reminderTimes: ['08:00'], customDays: [], focusMinutes: null };
const result = { draft, question: null };
const completion = (value = result, finish = 'stop') => new Response(JSON.stringify({ choices: [{ finish_reason: finish, message: { content: JSON.stringify(value) } }] }));

async function service(t, options = {}) {
  const server = createServer(createAssistHandler({ env, fetchImpl: async () => completion(), ...options }));
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise((resolve) => { server.closeAllConnections(); server.close(resolve); }));
  const base = `http://127.0.0.1:${server.address().port}`;
  return (value = request, headers = {}, path = '/v1/assist') => fetch(base + path, {
    method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, ...headers },
    body: typeof value === 'string' ? value : JSON.stringify(value),
  });
}

test('parse validates a timezone-bound draft and never executes data changes', async (t) => {
  let upstream;
  const send = await service(t, { fetchImpl: async (url, options) => {
    upstream = { url, options, payload: JSON.parse(options.body) }; return completion();
  } });
  const response = await send();
  assert.equal(response.status, 200); assert.deepEqual(await response.json(), result);
  assert.equal(upstream.url, 'https://api.deepseek.com/chat/completions');
  assert.equal(upstream.options.headers.Authorization, 'Bearer test-provider-key');
  assert.equal(upstream.payload.model, 'deepseek-flash');
  assert.deepEqual(upstream.payload.response_format, { type: 'json_object' });
  assert.equal(upstream.payload.messages[1].role, 'user');
  assert.equal(response.headers.get('cache-control'), 'no-store');
});

test('private endpoint rejects missing/wrong auth before spending upstream calls', async (t) => {
  let calls = 0;
  const send = await service(t, { fetchImpl: async () => { calls++; return completion(); } });
  for (const authorization of ['', 'Bearer wrong', `Basic ${token}`]) assert.equal((await send(request, { Authorization: authorization })).status, 401);
  assert.equal(calls, 0);
});

test('health response does not disclose configured keys and unconfigured service rejects requests', async (t) => {
  const send = await service(t, { env: {} });
  assert.deepEqual(await (await send()).json(), { error: { code: 'SERVICE_UNAVAILABLE' } });
  const server = createServer(createAssistHandler({ env: {} }));
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise((resolve) => { server.closeAllConnections(); server.close(resolve); }));
  assert.deepEqual(await (await fetch(`http://127.0.0.1:${server.address().port}/health`)).json(), { ok: true });
});

test('malformed JSON, oversize data and wrong MIME do not reach upstream', async (t) => {
  let calls = 0;
  const send = await service(t, { fetchImpl: async () => { calls++; return completion(); } });
  assert.equal((await send('{')).status, 400);
  assert.equal((await send('a'.repeat(13 * 1024))).status, 413);
  assert.equal((await send(request, { 'Content-Type': 'text/plain' })).status, 415);
  assert.equal((await send({ ...request, text: 'x'.repeat(1001) })).status, 400);
  assert.equal(calls, 0);
});

test('date, zone and recurrence validation rejects impossible or unsupported values', () => {
  for (const altered of [{ ...request, now: '2026-02-30T15:00:00+08:00' }, { ...request, timeZone: 'Europe/London' }, { ...request, timeZone: 'Invalid/Zone' }])
    assert.throws(() => validateRequest(altered));
  for (const altered of [{ ...draft, date: '2026-02-30' }, { ...draft, reminderTimes: ['24:00'] }, { ...draft, repeatMode: 'MONTHLY' },
    { ...draft, repeatMode: 'CUSTOM', date: null, customDays: [] }, { ...draft, repeatMode: 'EVERY_DAY', date: null, customDays: [1] },
    { ...draft, focusMinutes: 30 }, { ...draft, customDays: ['1'] }, { ...draft, reminderTimes: ['08:00', '08:00'] }, { ...draft, date: '2026-10-26', reminderTimes: ['14:00'] }])
    assert.throws(() => validateResult({ draft: altered, question: null }, request));
});

test('partial plans must ask a question and unsupported recurrence may return no draft', () => {
  assert.deepEqual(validateResult({ draft: null, question: '可以改成每周指定星期吗？' }, request), { draft: null, question: '可以改成每周指定星期吗？' });
  const partial = { draft: { ...draft, date: null, reminderTimes: [] }, question: '想在哪天几点提醒？' };
  assert.deepEqual(validateResult(partial, request), partial);
  assert.throws(() => validateResult({ ...partial, question: null }, request));
});

test('unsupported recurrence asks locally before any model call; next month remains a one-time date', async (t) => {
  let calls = 0;
  const send = await service(t, { rateLimit: 20, fetchImpl: async () => { calls++; return completion(); } });
  for (const text of ['每月1号八点交房租', '隔周周一八点开会', '每年十月一号提醒我', '每隔三天八点喝水', '每两周整理房间', '每2周整理房间']) {
    const response = await send({ ...request, text });
    assert.equal(response.status, 200);
    const value = await response.json();
    assert.equal(value.draft, null); assert.ok(value.question.includes('每周指定星期'));
  }
  assert.equal(calls, 0);
  assert.equal((await send({ ...request, text: '下个月1号八点交房租' })).status, 200);
  assert.equal(calls, 1);
});

test('split accepts bounded actionable steps and rejects duplicate/empty suggestions', async (t) => {
  const steps = { steps: [{ title: '阅读一个知识点', focusMinutes: 15 }, { title: '写一个例子', focusMinutes: 25 }] };
  const split = { ...request, operation: 'split', text: '学 Kotlin', context: { title: '学 Kotlin', description: '' } };
  const send = await service(t, { fetchImpl: async () => completion(steps) });
  assert.deepEqual(await (await send(split)).json(), steps);
  assert.throws(() => validateResult({ steps: [steps.steps[0], steps.steps[0]] }, split));
  assert.throws(() => validateResult({ steps: [] }, split));
  assert.throws(() => validateRequest({ ...split, context: undefined }));
});

test('upstream errors and content are sanitized', async (t) => {
  const send = await service(t, { fetchImpl: async () => new Response('secret provider response: test-provider-key', { status: 401 }) });
  const response = await send();
  assert.equal(response.status, 502); assert.deepEqual(await response.json(), { error: { code: 'ASSIST_UNAVAILABLE' } });
});

test('empty/truncated/invalid model JSON is never a usable plan', async (t) => {
  let count = 0;
  const responses = [completion(result, 'length'), new Response(JSON.stringify({ choices: [{ finish_reason: 'stop', message: { content: '' } }] })), completion({ ...result, unexpected: true })];
  const send = await service(t, { fetchImpl: async () => responses[count++] });
  for (let i = 0; i < 3; i++) assert.equal((await send()).status, 502);
});

test('minute and daily quotas run before upstream calls; spoofed forwarded IP cannot evade quota', async (t) => {
  let now = 0; let calls = 0;
  const send = await service(t, { clock: () => now, rateLimit: 1, dailyLimit: 2, fetchImpl: async () => { calls++; return completion(); } });
  assert.equal((await send()).status, 200);
  assert.equal((await send(request, { 'X-Forwarded-For': '1.2.3.4' })).status, 429);
  now = 60_001; assert.equal((await send()).status, 200);
  now = 120_001; assert.equal((await send()).status, 429);
  now = 86_400_001; assert.equal((await send()).status, 200);
  assert.equal(calls, 3);
});

test('global concurrent requests are bounded and capacity is released', async (t) => {
  let entered; let release;
  const started = new Promise((resolve) => { entered = resolve; });
  const gate = new Promise((resolve) => { release = resolve; });
  const send = await service(t, { maxConcurrent: 1, fetchImpl: async () => { entered(); await gate; return completion(); } });
  const first = send(); await started;
  assert.equal((await send()).status, 429);
  release(); assert.equal((await first).status, 200);
  assert.equal((await send()).status, 200);
});

test('single-instance daily budget caps otherwise allowed requests', async (t) => {
  let now = 0; let calls = 0;
  const send = await service(t, { clock: () => now, globalDailyLimit: 1,
    fetchImpl: async () => { calls++; return completion(); } });
  assert.equal((await send()).status, 200);
  now = 60_001;
  assert.equal((await send()).status, 429);
  now = 86_400_001;
  assert.equal((await send()).status, 200);
  assert.equal(calls, 2);
});

test('upstream timeout aborts work and returns only a safe error', async (t) => {
  const send = await service(t, { timeoutMillis: 20, fetchImpl: async (_url, { signal }) => new Promise((_resolve, reject) => {
    signal.addEventListener('abort', () => reject(new Error('secret upstream request')), { once: true });
  }) });
  const response = await send();
  assert.equal(response.status, 504); assert.deepEqual(await response.json(), { error: { code: 'ASSIST_TIMEOUT' } });
});
