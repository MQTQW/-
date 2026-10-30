import test from 'node:test';
import assert from 'node:assert/strict';
import { request as httpRequest } from 'node:http';
import { createLiveServer } from '../serve.mjs';

const PRIVATE = 'fixture-provider-key-never-expose';
const MAP_KEY = 'fixture-map-key-never-expose';
const POOL = { id: 'research-owned-pool', city: '杭州', sources: [], candidates: [
  { id: 'candidate-A', title: '真实候选', category: 'sights', kind: 'visit', duration: 60, sourceIds: [] },
] };
async function fixture(t, overrides = {}) {
  const calls = { research: [], plan: [], images: [] };
  const engine = {
    checkConnection: async () => ({ loggedIn: true, message: '本机已登录。' }),
    research: async input => { calls.research.push(input); input.onProgress('正在整理公开内容'); return structuredClone(POOL); },
    ...overrides.engine,
  };
  const planner = { plan: async input => { calls.plan.push(input); return { status: 'ready',
    trip: { id: 'trip-fixture', wishes: input.selected }, unplaced: [], stats: { aiRequests: 0 } }; }, ...overrides.planner };
  const media = { has: id => id === 'image_opaque123', get: async id => { calls.images.push(id);
    return { mime: 'image/png', bytes: Buffer.from([137, 80, 78, 71]) }; }, ...overrides.media };
  const app = createLiveServer({ engine, planner, media, config: { aiKey: PRIVATE, amapKey: MAP_KEY }, port: 0 });
  await new Promise((resolve, reject) => { app.server.once('error', reject); app.server.listen(0, '127.0.0.1', resolve); });
  t.after(() => app.close());
  const origin = `http://127.0.0.1:${app.server.address().port}`;
  const request = (pathname, init = {}) => fetch(origin + pathname, init);
  async function session() {
    const response = await request('/api/session');
    const body = await response.json();
    return { response, body, cookie: response.headers.get('set-cookie').split(';')[0], csrf: body.csrfToken };
  }
  const post = (pathname, body, auth, extraHeaders = {}) => request(pathname, { method: 'POST',
    headers: { 'Content-Type': 'application/json', Origin: origin, Cookie: auth.cookie,
      'x-csrf-token': auth.csrf, ...extraHeaders }, body: typeof body === 'string' ? body : JSON.stringify(body) });
  async function job(id, auth) {
    for (let attempt = 0; attempt < 30; attempt++) {
      const response = await request(`/api/jobs/${id}`, { headers: { Cookie: auth.cookie } });
      const value = await response.json();
      if (value.status !== 'running') return { response, body: value };
      await new Promise(resolve => setTimeout(resolve, 5));
    }
    throw new Error('Local stub job did not settle.');
  }
  async function research(auth) {
    const response = await post('/api/research', { destination: '杭州', count: 1 }, auth);
    assert.equal(response.status, 202);
    const { jobId } = await response.json();
    return { jobId, ...(await job(jobId, auth)) };
  }
  return { app, calls, origin, request, session, post, job, research };
}

test('session bootstraps private cookie, CSRF and boolean capabilities without disclosing config', async t => {
  const f = await fixture(t), auth = await f.session();
  assert.equal(auth.response.status, 200);
  assert.match(auth.response.headers.get('set-cookie'), /HttpOnly; SameSite=Strict/);
  assert.match(auth.csrf, /^[a-f0-9]{64}$/u);
  assert.deepEqual(auth.body.capabilities, { research: true, ai: true, map: true });
  assert.equal(auth.body.connection.loggedIn, true);
  const serialized = JSON.stringify(auth.body);
  assert.equal(serialized.includes(PRIVATE), false);
  assert.equal(serialized.includes(MAP_KEY), false);
  assert.equal(auth.response.headers.get('cache-control'), 'no-store');
  assert.equal(auth.response.headers.get('x-content-type-options'), 'nosniff');
});

test('POST requires same-origin cookie and CSRF; foreign Host, Origin and fetch site are rejected', async t => {
  const f = await fixture(t), auth = await f.session(), payload = { destination: '杭州', count: 1 };
  const noSession = await f.request('/api/research', { method: 'POST', headers: { Origin: f.origin,
    'Content-Type': 'application/json', 'x-csrf-token': auth.csrf }, body: JSON.stringify(payload) });
  assert.equal(noSession.status, 401);
  assert.equal((await f.post('/api/research', payload, auth, { 'x-csrf-token': 'incorrect' })).status, 403);
  assert.equal((await f.post('/api/research', payload, auth, { Origin: 'https://foreign.example' })).status, 403);
  const foreignHostStatus = await new Promise((resolve, reject) => {
    const request = httpRequest(`${f.origin}/api/session`, { headers: { Host: 'foreign.example' } }, response => {
      response.resume(); response.once('end', () => resolve(response.statusCode));
    });
    request.once('error', reject); request.end();
  });
  assert.equal(foreignHostStatus, 403);
  assert.equal((await f.request('/api/session', { headers: { 'Sec-Fetch-Site': 'cross-site' } })).status, 403);
  const missingOrigin = await f.request('/api/research', { method: 'POST', headers: { Cookie: auth.cookie,
    'Content-Type': 'application/json', 'x-csrf-token': auth.csrf }, body: JSON.stringify(payload) });
  assert.equal(missingOrigin.status, 403);
  assert.equal(f.calls.research.length, 0);
});

test('oversized and malformed JSON requests fail before any injected engine call', async t => {
  const f = await fixture(t), auth = await f.session();
  const oversized = await f.post('/api/research', { destination: '杭州', padding: 'x'.repeat(24_001) }, auth);
  assert.equal(oversized.status, 413);
  assert.equal((await oversized.json()).error.code, 'BODY_TOO_LARGE');
  for (const input of ['{broken', '[]', 'null']) {
    const response = await f.post('/api/research', input, auth);
    assert.equal(response.status, 400);
    assert.equal((await response.json()).error.code, 'INVALID_JSON');
  }
  assert.equal(f.calls.research.length, 0);
});

test('plan uses the server-owned pool; unknown and repeated selections never reach planner', async t => {
  const f = await fixture(t), auth = await f.session();
  const research = await f.research(auth);
  assert.equal(research.body.status, 'done');
  for (const selection of [[{ id: 'forged', priority: 'must' }],
    [{ id: 'candidate-A', priority: 'want' }, { id: 'candidate-A', priority: 'must' }],
    [{ id: 'candidate-A', priority: 'invalid' }]]) {
    const response = await f.post('/api/plan', { researchId: POOL.id, selected: selection, form: {} }, auth);
    assert.equal(response.status, 400);
    assert.equal((await response.json()).error.code, 'INVALID_SELECTION');
  }
  assert.equal(f.calls.plan.length, 0);
  const response = await f.post('/api/plan', { researchId: POOL.id, selected: [{ id: 'candidate-A', priority: 'must' }],
    research: { candidates: [{ id: 'forged' }] }, form: { destination: '杭州', days: 1, undated: true } }, auth);
  assert.equal(response.status, 202);
  const planned = await f.job((await response.json()).jobId, auth);
  assert.equal(planned.body.result.status, 'ready');
  assert.deepEqual(f.calls.plan[0].research, POOL);
  assert.equal(f.calls.plan[0].selected[0].id, 'candidate-A');
});

test('sessions isolate jobs and research records and expire after four idle hours', async t => {
  const f = await fixture(t), owner = await f.session(), other = await f.session();
  assert.notEqual(owner.cookie, other.cookie);
  const research = await f.research(owner);
  const outsider = await f.request(`/api/jobs/${research.jobId}`, { headers: { Cookie: other.cookie } });
  assert.equal(outsider.status, 404);
  assert.equal((await outsider.json()).error.code, 'JOB_NOT_FOUND');
  const unauthorizedPlan = await f.post('/api/plan', { researchId: POOL.id,
    selected: [{ id: 'candidate-A', priority: 'want' }], form: {} }, other);
  assert.equal(unauthorizedPlan.status, 409);
  assert.equal((await unauthorizedPlan.json()).error.code, 'RESEARCH_EXPIRED');
  const realNow = Date.now;
  try {
    const advanced = realNow() + 4 * 3_600_000 + 1_000;
    Date.now = () => advanced;
    const expired = await f.request(`/api/jobs/${research.jobId}`, { headers: { Cookie: owner.cookie } });
    assert.equal(expired.status, 401);
    assert.equal((await expired.json()).error.code, 'SESSION_REQUIRED');
    const renewed = await f.session();
    assert.notEqual(renewed.cookie, owner.cookie);
  } finally { Date.now = realNow; }
  assert.equal(f.calls.plan.length, 0);
});

test('running jobs deduplicate per session and service caps global concurrency at three', async t => {
  const f = await fixture(t, { engine: { research: ({ signal }) => new Promise((_, reject) => {
    signal.addEventListener('abort', () => reject(Object.assign(new Error('取消'), { code: 'ABORTED' })), { once: true });
  }) } });
  const accounts = await Promise.all([f.session(), f.session(), f.session(), f.session()]);
  const submit = auth => f.post('/api/research', { destination: '杭州', count: 1 }, auth);
  const first = await submit(accounts[0]), firstId = (await first.json()).jobId;
  assert.equal(first.status, 202);
  const repeated = await submit(accounts[0]);
  assert.equal(repeated.status, 202);
  assert.equal((await repeated.json()).jobId, firstId);
  for (const account of accounts.slice(1, 3)) assert.equal((await submit(account)).status, 202);
  const busy = await submit(accounts[3]);
  assert.equal(busy.status, 429);
  assert.equal((await busy.json()).error.code, 'SERVICE_BUSY');
});

test('image endpoint only resolves registered opaque IDs and never accepts arbitrary remote URLs', async t => {
  const f = await fixture(t), auth = await f.session(), headers = { Cookie: auth.cookie };
  const known = await f.request('/api/images/image_opaque123', { headers });
  assert.equal(known.status, 200);
  assert.equal(known.headers.get('content-type'), 'image/png');
  assert.equal(known.headers.get('content-security-policy'), "default-src 'none'; sandbox");
  assert.deepEqual(Buffer.from(await known.arrayBuffer()), Buffer.from([137, 80, 78, 71]));
  const unknown = await f.request('/api/images/unknown_image123', { headers });
  assert.equal(unknown.status, 404);
  assert.equal((await unknown.json()).error.code, 'IMAGE_NOT_FOUND');
  const remote = await f.request('/api/images/image_opaque123?url=https://foreign.example/image.png', { headers });
  assert.equal(remote.status, 400);
  assert.deepEqual(f.calls.images, ['image_opaque123']);
});

test('changed city or source count cannot reuse a different active research job',async t=>{
  const f=await fixture(t,{engine:{research:({signal})=>new Promise((_,reject)=>signal.addEventListener('abort',()=>reject(Error('done')),{once:true}))}}),auth=await f.session();
  const first=await f.post('/api/research',{destination:'杭州',count:1},auth),id=(await first.json()).jobId;
  for(const body of [{destination:'苏州',count:1},{destination:'杭州',count:2}]){const response=await f.post('/api/research',body,auth);assert.equal(response.status,409);assert.equal((await response.json()).error.code,'ACTIVE_JOB_CONFLICT');}
  const same=await f.post('/api/research',{count:1,destination:'杭州'},auth);assert.equal(same.status,202);assert.equal((await same.json()).jobId,id);
});

test('active plans reuse only identical choices and conditions, independent of object-key order',async t=>{
  let finish;const f=await fixture(t,{planner:{plan:({selected,signal})=>new Promise((resolve,reject)=>{finish=()=>resolve({status:'ready',trip:{id:'test',wishes:selected}});signal.addEventListener('abort',()=>reject(Error('done')),{once:true});})}}),auth=await f.session();await f.research(auth);
  const body={researchId:POOL.id,selected:[{id:'candidate-A',priority:'want'}],form:{days:1,destination:'杭州'}};
  const first=await f.post('/api/plan',body,auth),id=(await first.json()).jobId;
  for(const changed of [{...body,form:{days:2,destination:'杭州'}},{...body,selected:[{id:'candidate-A',priority:'must'}]}]){const response=await f.post('/api/plan',changed,auth);assert.equal(response.status,409);assert.equal((await response.json()).error.code,'ACTIVE_JOB_CONFLICT');}
  const same=await f.post('/api/plan',{form:{destination:'杭州',days:1},selected:body.selected,researchId:POOL.id},auth);assert.equal(same.status,202);assert.equal((await same.json()).jobId,id);
  finish();assert.equal((await f.job(id,auth)).body.status,'done');
});

test('HTTP job errors never expose arbitrary coded upstream messages or private configuration', async t => {
  const f = await fixture(t, { engine: { research: async () => {
    throw Object.assign(new Error(`upstream Authorization: Bearer ${PRIVATE}`), { code: 'UPSTREAM_FAILED' });
  } } });
  const auth = await f.session();
  const failed = await f.research(auth);
  assert.equal(failed.body.status, 'failed');
  assert.equal(typeof failed.body.error.code, 'string');
  assert.equal(JSON.stringify(failed.body).includes(PRIVATE), false);
  assert.equal(JSON.stringify(failed.body).includes('Authorization'), false);
});
