import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import { createSelectionPlanner, SelectionPlannerError } from '../selection-planner.mjs';

const CONFIG = { aiKey: 'private-test-provider-key', model: 'deepseek-flash' };
const card = (id, extra = {}) => ({ id, title: `地点 ${id}`, category: 'sights', kind: 'visit', duration: 60,
  sourceIds: ['S1'], sourceRefs: [{ sourceId: 'S1', evidenceType: 'body', evidenceText: `地点 ${id}` }],
  lng: null, lat: null, verification: { status: 'unverified' }, locationStatus: 'pending',
  region: '城区', notes: '出发前确认开放安排。', image: null, ...extra });
const research = () => ({ id: 'research-fixture', city: '杭州', sources: [
  { id: 'S1', title: '公开攻略', author: '作者', date: '2026-10-29', url: 'https://www.xiaohongshu.com/explore/public',
    description: '已读取的公开攻略', excerpt: '地点 A、地点 B' },
  { id: 'S2', title: '未使用的攻略', url: 'https://www.xiaohongshu.com/explore/unused' },
], candidates: [card('A'), card('B'), card('not-selected', { title: '未选择的餐厅', category: 'food', kind: 'meal', sourceIds: ['S2'] })] });
const form = extra => ({ destination: '杭州', days: 1, date: '2026-11-07', undated: false,
  arrival: '', returnTime: '', hotel: '', pace: '舒缓', mode: '公共交通', ...extra });
const selected = (ids = ['A']) => ids.map(id => ({ id, priority: id === 'A' ? 'must' : 'want' }));
const stop = (candidateId, time = '09:00', durationMinutes = 60) => ({ candidateId, time, durationMinutes });
const output = (stops = [stop('A')], unplaced = []) => ({ days: [{ day: 1, stops }], unplaced });
const envelope = value => new Response(JSON.stringify({ choices: [{ finish_reason: 'stop', message: {
  content: typeof value === 'string' ? value : JSON.stringify(value) } }], usage: { prompt_tokens: 12, completion_tokens: 5, total_tokens: 17 } }),
  { status: 200, headers: { 'content-type': 'application/json' } });
function setup(responses = [output()], options = {}) {
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push({ url, init, body: JSON.parse(init.body) });
    const next = responses[Math.min(calls.length - 1, responses.length - 1)];
    if (typeof next === 'function') return await next(url, init);
    return envelope(next);
  };
  const planner = createSelectionPlanner({ config: CONFIG, fetchImpl, ...options });
  return { calls, planner, run: extra => planner.plan({ research: research(), selected: selected(), form: form(), ...extra }) };
}
async function rejectsCode(promise, code) {
  await assert.rejects(promise, error => error instanceof SelectionPlannerError && error.code === code);
}

test('real-provider request only contains selected cards and constructs browser-compatible provenance', async () => {
  const { run, calls } = setup();
  const events = [];
  const result = await run({ onProgress: packet => events.push(packet) });
  assert.equal(result.status, 'ready');
  assert.equal(result.stats.aiRequests, 1);
  assert.equal(result.stats.repairRequests, 0);
  assert.equal(calls[0].url, 'https://api.deepseek.com/chat/completions');
  assert.equal(calls[0].init.method, 'POST');
  assert.equal(calls[0].init.redirect, 'error');
  assert.equal(calls[0].init.headers.Authorization, `Bearer ${CONFIG.aiKey}`);
  assert.equal(calls[0].body.model, CONFIG.model);
  assert.equal(calls[0].body.max_tokens, 5000);
  assert.deepEqual(calls[0].body.response_format, { type: 'json_object' });
  assert.equal(calls[0].body.tools, undefined);
  const prompt = JSON.stringify(calls[0].body.messages);
  assert.equal(prompt.includes('未选择的餐厅'), false);
  assert.equal(prompt.includes('not-selected'), false);
  assert.equal(prompt.includes(CONFIG.aiKey), false);
  assert.match(prompt, /不可信数据/);
  const { trip } = result;
  assert.equal(trip.provenance.ai, 'live');
  assert.equal(trip.provenance.model, CONFIG.model);
  assert.equal(trip.provenance.traffic, 'unavailable');
  assert.deepEqual(trip.days[0].stops.map(node => node.id), ['A']);
  assert.equal(trip.days[0].stops[0].selectionId, 'A');
  assert.equal(trip.days[0].stops[0].mustKeep, true);
  assert.equal(trip.days[0].stops[0].travelMinutes, null);
  assert.equal(trip.days[0].stops[0].bufferMinutes, 30);
  assert.equal(trip.days[0].stops[0].photo, null);
  assert.deepEqual(trip.wishes.map(wish => [wish.id, wish.priority]), [['A', 'must']]);
  assert.deepEqual(trip.sources.map(source => source.id), ['S1']);
  assert.deepEqual(trip.budget, { stay: 0, food: 0, transport: 0, tickets: 0, misc: 0 });
  assert.equal(trip.budgetStatus, 'pending');
  assert.match(trip.budgetNote, /不代表免费/);
  assert.match(trip.planningNote, /真实 AI/);
  assert.equal(events[0].stage, 'planning');
  assert.equal(JSON.stringify(events).includes(CONFIG.aiKey), false);
});

test('full selected cards retain priorities, images and only server-owned coordinates and evidence', async () => {
  const pool = research();
  pool.candidates[0] = card('A', { image: { url: '/api/travel/media/image-one', alt: '公开配图' },
    lng: 120.1, lat: 30.2, verification: { status: 'verified', location: { longitude: 120.1, latitude: 30.2 } } });
  const { run } = setup([output([stop('B'), stop('A', '11:00')])]);
  const { trip } = await run({ research: pool, selected: selected(['A', 'B']) });
  assert.deepEqual(trip.days[0].stops.map(node => node.id), ['B', 'A']);
  const a = trip.days[0].stops[1];
  assert.equal(a.lng, 120.1);
  assert.equal(a.lat, 30.2);
  assert.equal(a.locationStatus, 'matched');
  assert.equal(a.liveImage.url, '/api/travel/media/image-one');
  assert.deepEqual(a.sourceRefs, pool.candidates[0].sourceRefs);
  assert.equal(trip.days[0].stops[0].mustKeep, false);
});

test('unknown, duplicate, modified, oversized and missing selections never reach AI', async () => {
  for (const selection of [[], [{ id: 'unknown', priority: 'want' }], [selected()[0], selected()[0]],
    [{ id: 'A', priority: 'must', title: '恶意替换地点' }], [{ id: 'A', priority: 'optional' }],
    Array.from({ length: 13 }, (_, index) => ({ id: String(index), priority: 'want' }))]) {
    const { run, calls } = setup();
    await rejectsCode(run({ selected: selection }), 'INVALID_INPUT');
    assert.equal(calls.length, 0);
  }
});

test('invalid dates, times, day counts and malformed input fail before provider requests', async () => {
  for (const fields of [{ date: '2026-02-29' }, { date: '2026-04-31' }, { date: '2026-1-01' },
    { date: '0000-01-01' }, { date: '9999-12-31', days: 2 }, { date: '', undated: false },
    { undated: 'true' }, { arrival: '24:00' }, { returnTime: '12:60' }, { arrival: '23:45' },
    { returnTime: '23:45' }, { days: 0 }, { days: 6 }, { days: 1.5 }, { hotel: {} }, { pace: [] }]) {
    const { run, calls } = setup();
    await rejectsCode(run({ form: form(fields) }), 'INVALID_INPUT');
    assert.equal(calls.length, 0);
  }
  const { planner } = setup();
  await rejectsCode(planner.plan(), 'INVALID_INPUT');
  await rejectsCode(planner.plan({ research: {}, selected: selected(), form: form() }), 'INVALID_INPUT');
});

test('leap dates, multiple dates and explicit undated planning have no fabricated date', async () => {
  const planned = { days: [{ day: 2, stops: [stop('A')] }, { day: 1, stops: [] }], unplaced: [] };
  const { run } = setup([planned]);
  const dated = await run({ form: form({ date: '2028-02-29', days: '2' }) });
  assert.deepEqual(dated.trip.days.map(day => day.date), ['2028-02-29', '2028-03-01']);
  const undated = await run({ form: form({ date: '', undated: true, days: 2 }) });
  assert.equal(undated.trip.date, null);
  assert.equal(undated.trip.undated, true);
  assert.deepEqual(undated.trip.days.map(day => day.date), [null, null]);
});

test('fixed arrival, hotel and return are server-built and preserve valid buffers', async () => {
  const { run, calls } = setup([output([stop('A', '16:15')])]);
  const { trip } = await run({ form: form({ arrival: '14:00', hotel: '用户预订酒店', returnTime: '18:30' }) });
  assert.deepEqual(trip.days[0].stops.map(node => [node.id, node.time, node.duration]), [
    ['__arrival', '14:00', 30], ['__hotel', '15:00', 45], ['A', '16:15', 60], ['__return', '18:30', 30],
  ]);
  for (const node of trip.days[0].stops.filter(node => node.locked)) {
    assert.equal(node.lng, null); assert.equal(node.lat, null); assert.deepEqual(node.sourceIds, []);
  }
  const payload = JSON.parse(calls[0].body.messages[1].content);
  assert.deepEqual(payload.dailyWindows, [{ day: 1, earliestStart: '16:15', latestEnd: '17:30', available: true }]);
});

test('hotel without arrival defaults to 15:00; arrival without hotel only adds arrival', async () => {
  const hotel = await setup([output([stop('A', '16:15')])]).run({ form: form({ hotel: '酒店' }) });
  assert.equal(hotel.trip.days[0].stops[0].time, '15:00');
  assert.equal(hotel.trip.days[0].stops[0].id, '__hotel');
  const arrival = await setup([output([stop('A', '11:00')])]).run({ form: form({ arrival: '10:00' }) });
  assert.deepEqual(arrival.trip.days[0].stops.map(node => node.id), ['__arrival', 'A']);
});

test('impossible fixed arrival/hotel/return constraints fail before AI', async () => {
  for (const fields of [{ arrival: '23:00', hotel: '酒店' }, { arrival: '14:00', returnTime: '14:15' },
    { arrival: '14:00', hotel: '酒店', returnTime: '16:00' }]) {
    const { run, calls } = setup();
    await rejectsCode(run({ form: form(fields) }), 'INVALID_INPUT');
    assert.equal(calls.length, 0);
  }
});

test('one repair can correct invalid arrangement without replacing or adding selected contents', async () => {
  const { run, calls } = setup([output([stop('not-selected')]), output([stop('A')])]);
  const result = await run();
  assert.equal(result.status, 'ready');
  assert.equal(result.stats.aiRequests, 2);
  assert.equal(result.stats.repairRequests, 1);
  assert.equal(calls[1].body.messages.length, 3);
  assert.equal(calls[1].body.messages[2].content.includes('not-selected'), false);
  assert.deepEqual(result.trip.days[0].stops.map(node => node.id), ['A']);
});

test('unselected places, repetitions, omitted choices and extra AI fields exhaust bounded repair', async () => {
  const badOutputs = [output([stop('not-selected')]), output([stop('A'), stop('A', '11:00')]),
    output([], []), output([stop('A')], [{ candidateId: 'A', reason: '重复' }]),
    output([{ ...stop('A'), title: '模型自造餐馆' }]), { ...output(), sources: ['模型伪造证据'] },
    { days: [{ day: 1, stops: [stop('A')] }, { day: 1, stops: [] }], unplaced: [] },
    output([], [{ candidateId: 'A', reason: '访问 https://example.com' }])];
  for (const invalid of badOutputs) {
    const { run, calls } = setup([invalid]);
    await rejectsCode(run(), 'AI_INVALID_PLAN');
    assert.equal(calls.length, 2);
  }
});

test('overlap, short buffers, unsorted times, cross-midnight, duration and daily windows are rejected', async () => {
  for (const stops of [[stop('A'), stop('B', '09:30')], [stop('A'), stop('B', '10:29')],
    [stop('A', '11:00'), stop('B', '09:00')], [stop('A', '23:30', 90), stop('B')],
    [stop('A', '08:59'), stop('B', '11:00')], [stop('A'), stop('B', '19:30')],
    [stop('A', '24:00'), stop('B')], [stop('A', '09:00', 0), stop('B', '11:00')],
    [stop('A', '09:00', 60.5), stop('B', '11:00')]]) {
    const { run, calls } = setup([output(stops)]);
    await rejectsCode(run({ selected: selected(['A', 'B']) }), 'AI_INVALID_PLAN');
    assert.equal(calls.length, 2);
  }
});

test('AI cannot schedule before arrival, before hotel completion or inside return buffer', async () => {
  for (const [fields, time] of [[{ arrival: '14:00' }, '14:30'],
    [{ arrival: '14:00', hotel: '酒店' }, '16:14'], [{ hotel: '酒店' }, '09:00'],
    [{ returnTime: '18:00' }, '16:01']]) {
    const { run, calls } = setup([output([stop('A', time, 60)])]);
    await rejectsCode(run({ form: form(fields) }), 'AI_INVALID_PLAN');
    assert.equal(calls.length, 2);
  }
});

test('every unplaced choice blocks trip, including optional choices and must choices', async () => {
  for (const missing of ['A', 'B']) {
    const scheduled = missing === 'A' ? 'B' : 'A';
    const { run } = setup([output([stop(scheduled)], [{ candidateId: missing, reason: '当前时间不够，请增加一天。' }])]);
    const result = await run({ selected: selected(['A', 'B']) });
    assert.equal(result.status, 'needs_changes');
    assert.equal(result.trip, null);
    assert.deepEqual(result.unplaced.map(item => item.id), [missing]);
    assert.equal(result.unplaced[0].priority, missing === 'A' ? 'must' : 'want');
    assert.match(result.message, /所有所选内容均已保留/);
    assert.equal(result.stats.aiRequests, 1);
  }
});

test('late first-day arrival can be explicitly left unplaced instead of silently discarded', async () => {
  const { run } = setup([output([], [{ candidateId: 'A', reason: '到达后没有可用游览时间，请增加一天。' }])]);
  const result = await run({ form: form({ arrival: '21:00' }) });
  assert.equal(result.status, 'needs_changes');
  assert.equal(result.trip, null);
});

test('malformed JSON can be repaired once; malformed envelope never creates a fallback trip', async () => {
  const fixed = setup(['not-json', output()]);
  assert.equal((await fixed.run()).status, 'ready');
  assert.equal(fixed.calls.length, 2);
  const malformed = setup([() => new Response(JSON.stringify({ wrong: CONFIG.aiKey }), { status: 200 })]);
  await rejectsCode(malformed.run(), 'AI_INVALID_RESPONSE');
  assert.equal(malformed.calls.length, 2);
});

test('missing config and provider HTTP/network failures have sanitized errors and no sample fallback', async () => {
  const unconfigured = setup([output()], { config: {} });
  await rejectsCode(unconfigured.run(), 'AI_NOT_CONFIGURED');
  assert.equal(unconfigured.calls.length, 0);
  for (const provider of [() => { throw new Error(`Authorization: Bearer ${CONFIG.aiKey}`); },
    () => new Response(JSON.stringify({ error: CONFIG.aiKey }), { status: 401 }),
    () => new Response(JSON.stringify({ error: CONFIG.aiKey }), { status: 503 })]) {
    const { run, calls } = setup([provider]);
    await assert.rejects(run(), error => {
      assert.equal(error.code, 'AI_UNAVAILABLE');
      assert.equal(`${error.message} ${error.stack} ${JSON.stringify(error)}`.includes(CONFIG.aiKey), false);
      assert.equal(error.stats.aiRequests, 1);
      return true;
    });
    assert.equal(calls.length, 1);
  }
});

test('response-size limit and incomplete completion cannot produce a trip', async () => {
  const oversized = setup([() => new Response('', { status: 200, headers: { 'content-length': String(2 * 1024 * 1024) } })]);
  await rejectsCode(oversized.run(), 'AI_INVALID_RESPONSE');
  assert.equal(oversized.calls.length, 2);
  const truncated = setup([() => new Response(JSON.stringify({ choices: [{ finish_reason: 'length',
    message: { content: JSON.stringify(output()) } }] }), { status: 200 })]);
  await rejectsCode(truncated.run(), 'AI_INVALID_RESPONSE');
  assert.equal(truncated.calls.length, 2);
});

test('pre-aborted requests never call provider and cancellation during fetch stays sanitized', async () => {
  const controller = new AbortController(); controller.abort();
  const existing = setup();
  await rejectsCode(existing.run({ signal: controller.signal }), 'ABORTED');
  assert.equal(existing.calls.length, 0);
  const active = new AbortController();
  const { run } = setup([() => { active.abort(); throw new Error(CONFIG.aiKey); }]);
  await rejectsCode(run({ signal: active.signal }), 'ABORTED');
});

test('only adjacent verified selected candidates are sent to map leg checks', async () => {
  const pool = research();
  for (const candidate of pool.candidates.slice(0, 2)) Object.assign(candidate, { lng: 120, lat: 30,
    verification: { status: 'verified', location: { longitude: 120, latitude: 30 } } });
  const requests = [];
  const { run } = setup([output([stop('A'), stop('B', '11:00')])], { legImpl: async (...args) => {
    requests.push(args);
    return { durationMinutes: 70, status: 'verified', provider: 'amap', checkedAt: '2026-10-30T00:00:00Z', mode: '步行',
      privateValue: CONFIG.aiKey };
  } });
  const result = await run({ research: pool, selected: selected(['A', 'B']) });
  assert.equal(requests.length, 1);
  assert.equal(requests[0][0].id, 'A'); assert.equal(requests[0][1].id, 'B');
  assert.equal(requests[0][2], '公共交通');
  assert.equal(result.trip.days[0].stops[1].travelMinutes, 70);
  assert.equal(result.trip.days[0].stops[1].travelStatus, 'estimated');
  assert.equal(result.trip.days[0].stops[1].bufferMinutes, 30);
  assert.equal(result.trip.days[0].legs[0].status, 'estimated');
  assert.equal(result.trip.provenance.traffic, 'partially_estimated');
  assert.equal(result.stats.legsChecked, 1);
  assert.equal(JSON.stringify(result).includes(CONFIG.aiKey), false);
  assert.match(result.warnings.join(' '), /超出预留间隔/);
  assert.equal(result.trip.days[0].stops[1].time, '11:00');
});

test('unverified locations or failed routes leave traffic null without guessing', async () => {
  let routeCalls = 0;
  const pool = research();
  for (const candidate of pool.candidates.slice(0, 2)) Object.assign(candidate, { lng: 120, lat: 30 });
  const { run } = setup([output([stop('A'), stop('B', '11:00')])], { legImpl: async () => {
    routeCalls++; throw new Error(CONFIG.aiKey);
  } });
  const unverified = await run({ research: pool, selected: selected(['A', 'B']) });
  assert.equal(routeCalls, 0);
  assert.equal(unverified.trip.days[0].stops[1].travelMinutes, null);
  assert.equal(unverified.trip.days[0].stops[0].lng, null);
  for (const candidate of pool.candidates.slice(0, 2)) candidate.verification.status = 'verified';
  const failed = await run({ research: pool, selected: selected(['A', 'B']) });
  assert.equal(routeCalls, 1);
  assert.equal(failed.stats.legFailures, 1);
  assert.equal(failed.trip.days[0].stops[1].travelMinutes, null);
  assert.equal(JSON.stringify(failed).includes(CONFIG.aiKey), false);
});

test('a throwing progress consumer cannot expose its error or invalidate planning', async () => {
  const { run } = setup();
  const result = await run({ onProgress: () => { throw new Error(CONFIG.aiKey); } });
  assert.equal(result.status, 'ready');
  assert.ok(result.stats.progressCallbackFailures > 0);
  assert.equal(JSON.stringify(result).includes(CONFIG.aiKey), false);
});

test('trip round-trips through existing browser Model local draft validator', async () => {
  const { trip } = await setup([output([stop('A', '16:15')])]).run({ form: form({ arrival: '14:00', hotel: '酒店', returnTime: '18:30' }) });
  const modelSource = await readFile(new URL('../../../design/travel-live/model.js', import.meta.url), 'utf8');
  const storage = new Map();
  const context = vm.createContext({ localStorage: { getItem: id => storage.get(id) ?? null,
    setItem: (id, value) => storage.set(id, value) } });
  vm.runInContext(`${modelSource}\nglobalThis.modelForTest = Model;`, context);
  const model = context.modelForTest;
  const state = model.fresh(); state.trip = trip;
  assert.equal(model.persist(state).ok, true);
  const restored = model.restore();
  assert.equal(restored.error, '');
  assert.equal(restored.state.trip.id, trip.id);
  assert.deepEqual(Array.from(restored.state.trip.days[0].stops, node => node.id), ['__arrival', '__hotel', 'A', '__return']);
});
