import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createAssistHandler } from '../app.mjs';
import { validateRequest, validateResult } from '../schema.mjs';

const token = 'travel-test-access-code-at-least-32-characters';
const env = { DEEPSEEK_API_KEY: 'fake-key-no-real-provider-call', ASSIST_ACCESS_TOKEN: token };
const request = { operation: 'itinerary', destination: '测试城市', title: '旅行草稿', startDate: '2030-10-28',
  dayCount: 2, timeZoneId: 'Asia/Shanghai', transport: '公共交通', pace: '适中', notes: '安排休息' };
const stop = { title: '区域散步', location: '测试城市中心区域', startTime: '09:00', durationMinutes: 60,
  notes: '时间为建议，请自行核实地点开放与交通信息。' };
const result = { days: [{ day: 1, stops: [stop] }, { day: 2, stops: [stop] }] };
const completion = (value = result, padding = '') => new Response(JSON.stringify({ choices: [
  { finish_reason: 'stop', message: { content: JSON.stringify(value) } }], padding }));

async function service(t, options = {}) {
  const server = createServer(createAssistHandler({ env, fetchImpl: async () => completion(), ...options }));
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise((resolve) => { server.closeAllConnections(); server.close(resolve); }));
  const base = `http://127.0.0.1:${server.address().port}`;
  return (value = request, authorization = `Bearer ${token}`) => fetch(`${base}/v1/assist`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: authorization },
    body: typeof value === 'string' ? value : JSON.stringify(value),
  });
}

test('itinerary goes through private assist handler and returns only an editable day/stop draft', async (t) => {
  let payload;
  const send = await service(t, { fetchImpl: async (url, options) => {
    assert.equal(url, 'https://api.deepseek.com/chat/completions');
    payload = JSON.parse(options.body); return completion();
  } });
  assert.deepEqual(await (await send()).json(), result);
  assert.equal(payload.messages[1].content, JSON.stringify(request));
  assert.ok(payload.messages[0].content.includes('不得声称已查询'));
  assert.ok(payload.messages[0].content.includes('只是建议'));
  assert.equal(payload.max_tokens, 2800);
  assert.equal((await send(request, 'Bearer wrong')).status, 401);
});

test('invalid itinerary fields are rejected before upstream while parse request limits remain unchanged', async (t) => {
  let calls = 0;
  const send = await service(t, { rateLimit: 100, fetchImpl: async () => { calls++; return completion(); } });
  for (const changed of [{ dayCount: 0 }, { dayCount: 15 }, { dayCount: 1.5 }, { destination: '' },
    { destination: 'x'.repeat(201) }, { title: 'x'.repeat(121) }, { notes: 'x'.repeat(1001) },
    { startDate: '2030-02-30' }, { startDate: '9999-12-31' }, { timeZoneId: 'Not/A_Zone' },
    { transport: '飞机' }, { pace: '任意' }]) assert.equal((await send({ ...request, ...changed })).status, 400);
  assert.equal((await send('x'.repeat(13 * 1024))).status, 413);
  assert.equal(calls, 0);
  assert.equal(validateRequest({ ...request, notes: undefined }).notes, '');
});

test('itinerary requires exact day count, consecutive day numbers and 1 to 6 stops', () => {
  const invalid = [
    { days: result.days.slice(0, 1) },
    { days: [{ day: 2, stops: [stop] }, result.days[1]] },
    { days: [{ day: '1', stops: [stop] }, result.days[1]] },
    { days: [{ day: 1, stops: [] }, result.days[1]] },
    { days: [{ day: 1, stops: Array(7).fill(stop) }, result.days[1]] },
  ];
  for (const value of invalid) assert.throws(() => validateResult(value, request));
});

test('invalid time, duration, lengths, coordinates and conflicting times cannot become itinerary suggestions', () => {
  for (const changed of [{ startTime: '24:00' }, { startTime: '9:00' }, { durationMinutes: 0 },
    { durationMinutes: 721 }, { durationMinutes: 1.5 }, { durationMinutes: '60' }, { location: '' },
    { title: 'x'.repeat(121) }, { location: 'x'.repeat(201) }, { notes: 'x'.repeat(1001) }, { lat: 30.1 }])
    assert.throws(() => validateResult({ days: [{ day: 1, stops: [{ ...stop, ...changed }] }, result.days[1]] }, request));
  assert.throws(() => validateResult({ days: [{ day: 1, stops: [stop, { ...stop, startTime: '09:30' }] }, result.days[1]] }, request));
  assert.throws(() => validateResult({ days: [{ day: 1, stops: [{ ...stop, startTime: '23:30' }] }, result.days[1]] }, request));
  assert.deepEqual(validateResult(result, request), result);
});

test('large but bounded 14-day itinerary fits its own response limit without expanding parse/split limits', async (t) => {
  const large = { days: Array.from({ length: 14 }, (_, index) => ({ day: index + 1,
    stops: Array.from({ length: 6 }, (_, stopIndex) => ({ ...stop, startTime: `${String(8 + stopIndex * 2).padStart(2, '0')}:00`, notes: '建'.repeat(1000) })) })) };
  let parse = false;
  const send = await service(t, { fetchImpl: async () => parse ? completion({ draft: null, question: '请补充时间' }, 'x'.repeat(40_000)) : completion(large) });
  const response = await send({ ...request, dayCount: 14 });
  assert.equal(response.status, 200); assert.equal((await response.json()).days.length, 14);
  parse = true;
  assert.equal((await send({ operation: 'parse', text: '读书', now: '2030-10-28T15:00:00+08:00', timeZone: 'Asia/Shanghai' })).status, 502);
});

test('invalid model itinerary returns a safe error rather than partial or invented fallback data', async (t) => {
  const send = await service(t, { fetchImpl: async () => completion({ days: [], providerSecret: 'never expose' }) });
  const response = await send();
  assert.equal(response.status, 502); assert.deepEqual(await response.json(), { error: { code: 'INVALID_ASSIST_RESPONSE' } });
});

test('itinerary timeout aborts the provider call using a separate bounded deadline', async (t) => {
  const send = await service(t, { timeoutMillis: 1000, itineraryTimeoutMillis: 20,
    fetchImpl: async (_url, { signal }) => new Promise((_resolve, reject) => signal.addEventListener('abort',
      () => reject(new Error('fake provider internal information')), { once: true })) });
  const response = await send();
  assert.equal(response.status, 504); assert.deepEqual(await response.json(), { error: { code: 'ASSIST_TIMEOUT' } });
});
