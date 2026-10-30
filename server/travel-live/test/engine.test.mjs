import test from 'node:test';
import assert from 'node:assert/strict';
import { createResearchEngine } from '../engine.mjs';
import { createMediaStore } from '../media.mjs';

const IDS = ['111111111111111111111111', '222222222222222222222222', '333333333333333333333333', '444444444444444444444444'];
const SECRET = 'fixture-private-provider-key';
const TOKEN = 'fixture-private-read-token';
const names = ['西湖', '灵隐寺', '小河直街', '龙井村', '九溪', '浙江省博物馆', '白堤', '苏堤', '断桥', '拱宸桥', '雷峰塔', '北高峰', '宝石山'];
const png = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
const detail = (id, body = names.join('、'), images = []) => ({ feed_id: id, data: { note: {
  noteId: id, title: '杭州旅行公开笔记', desc: body, time: 1_780_000_000_000,
  user: { nickname: '公开作者' }, imageList: images.map((url) => ({ urlDefault: url })),
} } });

function extraction(sources, placeNames = ['西湖'], extra = {}) {
  return { sources, extraction: { candidates: placeNames.map((name) => ({
    name, city: '杭州', category: 'attraction', suggestedDurationMinutes: 60,
    sourceRefs: [{ sourceId: sources[0].id, evidenceType: 'body', evidenceText: name }],
    notes: '停留时长为建议，预约和开放尚未核实。',
  })) }, stats: { aiRequests: 1 }, ...extra };
}

function setup({ login = { is_logged_in: true }, details, feeds, extractImpl, mapsImpl, imageFetch,
  config = { aiKey: SECRET, amapKey: 'fixture-map-key', model: 'deepseek-flash' } } = {}) {
  const calls = [];
  const media = createMediaStore({ fetchImpl: imageFetch ?? (async () => new Response(png, { headers: { 'Content-Type': 'image/png' } })) });
  const mcpClient = { async call(name, args) {
    calls.push({ name, args });
    if (name === 'check_login_status') return login;
    if (name === 'search_feeds') {
      const result = typeof feeds === 'function' ? await feeds(args.keyword) : feeds;
      if (result instanceof Error) throw result;
      return { feeds: result ?? IDS.map((id) => ({ id, xsecToken: TOKEN })) };
    }
    if (name === 'get_feed_detail') {
      const value = details?.[args.feed_id] ?? detail(args.feed_id);
      if (value instanceof Error) throw value;
      return value;
    }
    throw new Error('Any unexpected tool is a test failure');
  } };
  const maps = mapsImpl ?? { async verify(candidate) {
    return { ...candidate, verification: { status: 'verified', poiId: 'safe-poi',
      location: { longitude: 120.1, latitude: 30.2 }, providerSecret: SECRET } };
  }, async leg() { throw new Error('Discovery must never call route planning'); } };
  return { calls, media, engine: createResearchEngine({ mcpClient, config, media,
    extractImpl: extractImpl ?? (async ({ sources }) => extraction(sources)), mapsImpl: maps }) };
}

test('connection status uses read-only login checks and returns fixed sanitized messages', async () => {
  for (const login of [{ data: { is_logged_in: true }, username: SECRET },
    { content: [{ type: 'text', text: `✅ 已登录\n用户名: ${SECRET}` }] }]) {
    const { engine, calls } = setup({ login });
    const result = await engine.checkConnection();
    assert.equal(result.loggedIn, true);
    assert.equal(JSON.stringify(result).includes(SECRET), false);
    assert.deepEqual(calls.map((call) => call.name), ['check_login_status']);
  }
  assert.equal((await setup({ login: { message: '未登录，请先扫码' } }).engine.checkConnection()).loggedIn, false);
  assert.equal((await setup({ login: { data: { is_logged_in: false }, message: '已登录' } }).engine.checkConnection()).loggedIn, false);
  const unavailable = createResearchEngine({ mcpClient: { call: async () => { throw new Error(SECRET); } } });
  assert.deepEqual(await unavailable.checkConnection(), { loggedIn: false, message: '本机小红书连接暂时不可用，请稍后重试。' });
});

test('repeated research has its own image budget and retains current covers while old registry entries expire',async()=>{
  const details={};const {engine,media}=setup({details});
  for(let batch=0;batch<4;batch++){
    for(const id of IDS.slice(0,3))details[id]=detail(id,'西湖',Array.from({length:24},(_,i)=>`https://sns-img.xhscdn.com/batch${batch}-${id}-${i}.png`));
    const result=await engine.research({destination:'杭州',count:3});
    assert.equal(result.stats.imagesRegistered,12);assert.equal(result.stats.imagesAvailable,3);
    assert.ok(media.stats().registered<=36);
    assert.ok(result.candidates[0].image);assert.equal(media.has(result.candidates[0].image.id),true);
    assert.equal((await media.get(result.candidates[0].image.id)).mime,'image/png');
  }
});

test('invalid city or count and missing AI key fail before any retrieval, without a static fallback', async () => {
  const { engine, calls } = setup();
  for (const input of [{ destination: '' }, { destination: '../杭州' }, { destination: '杭'.repeat(41) },
    { destination: '杭州', count: 4 }, { destination: '杭州', count: 0 }, { destination: '杭州', count: NaN }]) {
    await assert.rejects(engine.research(input), { code: 'INVALID_RESEARCH_INPUT' });
  }
  assert.equal(calls.length, 0);
  const missing = setup({ config: {} });
  await assert.rejects(missing.engine.research({ destination: '杭州' }), { code: 'AI_NOT_CONFIGURED' });
  assert.equal(missing.calls.length, 0);
});

test('real retrieval reads at most 3 posts, preserves original IDs/authors, and only returns opaque images', async () => {
  const progress = [];
  const { engine, calls, media } = setup({
    details: Object.fromEntries(IDS.map((id) => [id, detail(id, `西湖很好逛。 ${SECRET} cookie=${TOKEN}`, ['http://sns-img.xhscdn.com/image.png'])])),
  });
  const result = await engine.research({ destination: '杭州', onProgress: (message) => progress.push(message) });
  assert.equal(result.sources.length, 3);
  assert.deepEqual(result.sources.map((source) => source.id), IDS.slice(0, 3));
  assert.equal(result.sources[0].author, '公开作者');
  assert.equal(result.sources[0].url, `https://www.xiaohongshu.com/explore/${IDS[0]}`);
  assert.equal(calls.filter((call) => call.name === 'get_feed_detail').length, 3);
  assert.equal(result.candidates[0].sourceIds[0], IDS[0]);
  assert.match(result.sources[0].images[0].url, /^\/api\/images\/[A-Za-z0-9_-]+$/u);
  assert.equal(media.has(result.candidates[0].image.id), true);
  assert.equal(result.candidates[0].image.caption, '攻略配图，非地点实拍确认');
  assert.equal(result.candidates[0].photo, null);
  assert.equal(result.stats.imagesAvailable, 3);
  for (const forbidden of [SECRET, TOKEN, 'xsec_token', 'xhscdn.com', 'providerSecret']) {
    assert.equal(JSON.stringify(result).includes(forbidden), false, forbidden);
  }
  assert.deepEqual(result.status, { retrieval: 'live', ai: 'success', map: 'success' });
  assert.ok(progress.length > 3 && progress.every((value) => typeof value === 'string'));
  for (const key of ['trip', 'draft', 'days', 'legs', 'itinerary']) assert.equal(Object.hasOwn(result, key), false);
});

test('duplicate search results and mismatched detail IDs are excluded without reading replacement posts', async () => {
  const { engine, calls } = setup({ feeds: [
    { id: IDS[0], xsecToken: TOKEN }, { id: IDS[0], xsecToken: TOKEN },
    { id: IDS[1], xsecToken: TOKEN }, { id: IDS[2], xsecToken: TOKEN }, { id: IDS[3], xsecToken: TOKEN },
  ], details: { [IDS[1]]: detail(IDS[3]) } });
  const result = await engine.research({ destination: '杭州', count: 3 });
  assert.deepEqual(result.sources.map((source) => source.id), [IDS[0], IDS[2]]);
  assert.equal(calls.filter((call) => call.name === 'get_feed_detail').length, 3);
  assert.match(result.warnings.join(' '), /未能读取/u);
  const empty = setup({ feeds: [] });
  await assert.rejects(empty.engine.research({ destination: '杭州' }), { code: 'NO_SOURCES' });
});

test('one to three requested sources use one distinct public post per bounded topic query', async () => {
  const keywords = ['杭州旅游攻略', '杭州本地美食', '杭州特色体验'];
  for (const count of [1, 2, 3]) {
    let discoveryMode;
    const { engine, calls } = setup({ feeds: (keyword) => [
      { id: IDS[keywords.indexOf(keyword)], xsecToken: TOKEN }, { id: IDS[3], xsecToken: TOKEN },
    ], extractImpl: async (options) => {
      discoveryMode = options.discoveryMode;
      return extraction(options.sources);
    } });
    const result = await engine.research({ destination: '杭州', count });
    assert.deepEqual(calls.filter((call) => call.name === 'search_feeds').map((call) => call.args.keyword), keywords.slice(0, count));
    assert.deepEqual(calls.filter((call) => call.name === 'get_feed_detail').map((call) => call.args.feed_id), IDS.slice(0, count));
    assert.equal(result.sources.length, count);
    assert.equal(result.stats.searchQueries, count);
    assert.equal(discoveryMode, true);
    assert.ok(calls.every((call) => ['check_login_status', 'search_feeds', 'get_feed_detail'].includes(call.name)));
  }
});

test('duplicate or failed topic searches reuse seen unique feeds and never exceed the detail budget', async () => {
  const { engine, calls } = setup({ feeds: (keyword) => keyword.endsWith('旅游攻略')
    ? IDS.map((id) => ({ id, xsecToken: TOKEN }))
    : keyword.endsWith('本地美食') ? [{ id: IDS[0], xsecToken: TOKEN }]
      : Object.assign(new Error(SECRET), { code: TOKEN }),
  details: { [IDS[1]]: new Error(SECRET) } });
  const result = await engine.research({ destination: '杭州', count: 3 });
  const detailCalls = calls.filter((call) => call.name === 'get_feed_detail');
  assert.deepEqual(detailCalls.map((call) => call.args.feed_id), IDS.slice(0, 3));
  assert.ok(detailCalls.every((call) => call.args.load_all_comments === false));
  assert.deepEqual(result.sources.map((source) => source.id), [IDS[0], IDS[2]]);
  assert.equal(result.stats.topicFallbackCount, 2);
  assert.equal(result.stats.searchFailures, 1);
  assert.match(result.warnings.join(' '), /本地美食检索未找到新的可读笔记/u);
  assert.match(result.warnings.join(' '), /特色体验检索本次未完成/u);
  assert.match(result.warnings.join(' '), /不代表已覆盖该主题/u);
  assert.equal(JSON.stringify(result).includes(SECRET), false);
  assert.equal(JSON.stringify(result).includes(TOKEN), false);
});

test('a failed first topic can use later returned reserves without an extra search or detail read', async () => {
  const { engine, calls } = setup({ feeds: (keyword) => keyword.endsWith('旅游攻略') ? new Error(SECRET)
    : [{ id: IDS[1], xsecToken: TOKEN }, { id: IDS[2], xsecToken: TOKEN }] });
  const result = await engine.research({ destination: '杭州', count: 2 });
  assert.deepEqual(result.sources.map((source) => source.id), [IDS[1], IDS[2]]);
  assert.equal(calls.filter((call) => call.name === 'search_feeds').length, 2);
  assert.equal(calls.filter((call) => call.name === 'get_feed_detail').length, 2);
  assert.match(result.warnings.join(' '), /综合攻略检索本次未完成/u);
  const failed = setup({ feeds: () => new Error(SECRET) });
  await assert.rejects(failed.engine.research({ destination: '杭州', count: 3 }), { code: 'MCP_READ_FAILED' });
  assert.equal(failed.calls.filter((call) => call.name === 'get_feed_detail').length, 0);
});

test('AI failure and fabricated body or image evidence are errors, never rule-based results', async () => {
  for (const extractImpl of [async () => { throw Object.assign(new Error(SECRET), { code: 'REMOTE_SECRET_PAYLOAD' }); },
    async ({ sources }) => extraction(sources, ['资料里没有的地点']),
    async ({ sources }) => {
      const value = extraction(sources);
      value.extraction.candidates[0].sourceRefs = [{ sourceId: sources[0].id, evidenceType: 'image', imageIndex: 1, evidenceText: '西湖' }];
      return value;
    },
    async ({ sources }) => extraction(sources.map((source) => ({ ...source, body: '无来源地点' })), ['无来源地点']),
  ]) {
    await assert.rejects(setup({ extractImpl }).engine.research({ destination: '杭州' }), (error) => {
      assert.match(error.code, /^AI_(UNAVAILABLE|INVALID_RESPONSE)$/u);
      assert.equal(error.message.includes(SECRET), false);
      assert.equal(Object.hasOwn(error, 'stats'), false);
      return true;
    });
  }
});

test('validated image OCR citations retain original image indexes and select that攻略配图', async () => {
  const { engine } = setup({ details: { [IDS[0]]: detail(IDS[0], '', [
    'https://sns-img.xhscdn.com/first.png', 'https://sns-img.xhscdn.com/second.png',
  ]) }, extractImpl: async ({ sources }) => {
    const enriched = sources.map((source, index) => ({ ...source, imageEvidence: index === 0
      ? [{ imageIndex: 2, text: '灵隐寺值得一去', method: 'deepseek-vision' }] : [] }));
    const value = extraction(enriched, ['灵隐寺'], { stats: { imagesRead: 1, imagesAttempted: 1, aiRequests: 2 } });
    value.extraction.candidates[0].sourceRefs = [{ sourceId: IDS[0], evidenceType: 'image', imageIndex: 2, evidenceText: '灵隐寺值得一去' }];
    return value;
  } });
  const result = await engine.research({ destination: '杭州', count: 1 });
  assert.equal(result.candidates[0].image.index, 2);
  assert.equal(result.candidates[0].sourceRefs[0].imageIndex, 2);
  assert.equal(result.stats.imagesRead, 1);
});

test('duplicate candidates merge citations, category maps correctly, and durations stay suggestions', async () => {
  const { engine } = setup({ extractImpl: async ({ sources }) => {
    const value = extraction(sources, ['西湖', '西湖', '灵隐寺']);
    value.extraction.candidates[1].sourceRefs[0].sourceId = sources[1].id;
    value.extraction.candidates[1].suggestedDurationMinutes = 90;
    value.extraction.candidates[2].category = 'food';
    return value;
  } });
  const result = await engine.research({ destination: '杭州', count: 2 });
  assert.equal(result.candidates.length, 2);
  assert.deepEqual(result.candidates[0].sourceIds, IDS.slice(0, 2));
  assert.equal(result.candidates[0].duration, 60);
  assert.equal(result.candidates[1].category, 'food');
  assert.equal(result.candidates[1].kind, 'meal');
  assert.match(result.candidates[1].verification.scope, /菜品尚未核实/u);
});

test('candidate city must match the requested city, with a 市 suffix allowed and no inferred correction', async () => {
  const partial = setup({ extractImpl: async ({ sources }) => {
    const value = extraction(sources, ['西湖', '灵隐寺']);
    value.extraction.candidates[0].city = '杭州市';
    value.extraction.candidates[1].city = '苏州';
    return value;
  } });
  const result = await partial.engine.research({ destination: '杭州', count: 1 });
  assert.deepEqual(result.candidates.map((candidate) => candidate.title), ['西湖']);
  assert.equal(result.stats.outOfCityCount, 1);
  assert.match(result.warnings.join(' '), /城市与目的地不一致/u);
  const allOutside = setup();
  await assert.rejects(allOutside.engine.research({ destination: '杭洲', count: 1 }), { code: 'NO_CANDIDATES' });
});

test('candidate cap and map request budget preserve unknown coordinates without automatic planning', async () => {
  let mapCalls = 0;
  const { engine } = setup({ extractImpl: async ({ sources }) => extraction(sources, names), mapsImpl: {
    async verify(candidate) { mapCalls++; return { ...candidate, verification: { status: 'verified', location: { longitude: 120, latitude: 30 } } }; },
    async leg() { assert.fail('Research must not schedule or query route legs'); },
  } });
  const result = await engine.research({ destination: '杭州', count: 1 });
  assert.equal(result.candidates.length, 12);
  assert.equal(result.stats.excludedCandidateCount, 1);
  assert.equal(mapCalls, 8);
  assert.equal(result.stats.mapRequests, 8);
  assert.equal(result.status.map, 'partial');
  assert.ok(result.candidates.slice(8).every((candidate) => candidate.lng === null && candidate.lat === null && candidate.locationStatus === 'pending'));
});

test('the candidate cap rotates existing categories without dropping late food/experiences or inventing a category', async () => {
  const food = ['西湖醋鱼', '片儿川'];
  const experiences = ['采茶体验', '宋韵点茶'];
  const allNames = [...names, ...food, ...experiences];
  const queriedNames = []; const progress = [];
  const { engine } = setup({ details: { [IDS[0]]: detail(IDS[0], allNames.join('、')) },
    extractImpl: async ({ sources }) => {
      const value = extraction(sources, allNames);
      for (const item of value.extraction.candidates) {
        if (food.includes(item.name)) item.category = 'food';
        if (experiences.includes(item.name)) item.category = 'other';
      }
      return value;
    }, mapsImpl: { async verify(candidate) {
      queriedNames.push(candidate.name);
      return { ...candidate, verification: { status: 'not_found' } };
    } },
  });
  const result = await engine.research({ destination: '杭州', count: 1, onProgress: (value) => progress.push(value) });
  assert.equal(result.candidates.length, 12);
  assert.deepEqual(result.stats.categoryCounts, { sights: 8, food: 2, experiences: 2 });
  assert.deepEqual(result.candidates.slice(0, 6).map((candidate) => candidate.title),
    [names[0], food[0], experiences[0], names[1], food[1], experiences[1]]);
  assert.equal(result.stats.excludedCandidateCount, 5);
  assert.equal(queriedNames.length, 8);
  assert.ok(queriedNames.every((name) => names.includes(name)));
  assert.deepEqual(progress.filter((value) => value.startsWith('正在核对地点')).map((value) => value.match(/\d+\/\d+/u)[0]),
    ['1/8', '2/8', '3/8', '4/8', '5/8', '6/8', '7/8', '8/8']);
  for (const candidate of result.candidates) {
    assert.equal(candidate.sourceRefs[0].evidenceText, candidate.name);
    assert.equal(candidate.sourceRefs[0].sourceId, IDS[0]);
    assert.equal(candidate.lng, null);
  }
  const sightsOnly = await setup().engine.research({ destination: '杭州', count: 1 });
  assert.deepEqual(sightsOnly.stats.categoryCounts, { sights: 1, food: 0, experiences: 0 });
});

test('food and experience intentions never query or select a business before explicit venue confirmation', async () => {
  const items = [
    { name: '西湖', category: 'attraction' }, { name: '小河直街', category: 'area' },
    { name: '东坡肉', category: 'food' }, { name: '知味观', category: 'food' },
    { name: '宋韵点茶', category: 'other' }, { name: '采茶体验', category: 'other' },
  ];
  const queries = []; const progress = [];
  const { engine } = setup({ details: { [IDS[0]]: detail(IDS[0], items.map((item) => item.name).join('、')) },
    extractImpl: async ({ sources }) => {
      const value = extraction(sources, items.map((item) => item.name));
      value.extraction.candidates.forEach((candidate, index) => { candidate.category = items[index].category; });
      return value;
    }, mapsImpl: { async verify(candidate) {
      queries.push(candidate.name);
      return { ...candidate, verification: { status: 'verified', poiId: 'would-select-business',
        location: { longitude: 120, latitude: 30 }, city: '杭州市', citycode: '0571', adcode: '330100' } };
    } },
  });
  const result = await engine.research({ destination: '杭州', count: 1, onProgress: (value) => progress.push(value) });
  assert.deepEqual(queries, ['西湖', '小河直街']);
  assert.equal(result.stats.mapRequests, 2);
  assert.equal(result.stats.matchedPlaceCount, 2);
  assert.equal(result.status.map, 'partial');
  for (const candidate of result.candidates.filter((item) => item.category !== 'sights')) {
    assert.equal(candidate.verification.status, 'unverified');
    assert.equal(candidate.verification.reason, '美食或体验意向，需确认具体场所；不把菜名或活动自动匹配到商家');
    assert.match(candidate.verification.scope, /菜品尚未核实/u);
    assert.equal(candidate.lng, null);
    assert.equal(candidate.lat, null);
    assert.equal(candidate.locationStatus, 'pending');
    assert.equal(Object.hasOwn(candidate.verification, 'poiId'), false);
    assert.equal(candidate.sourceRefs[0].evidenceText, candidate.name);
    assert.equal(candidate.sourceRefs[0].sourceId, IDS[0]);
  }
  assert.deepEqual(progress.filter((value) => value.startsWith('正在核对地点')).map((value) => value.match(/\d+\/\d+/u)[0]), ['1/2', '2/2']);
});

test('map failure stops further spending and sanitizes provider errors and matches', async () => {
  let mapCalls = 0;
  const { engine } = setup({ extractImpl: async ({ sources }) => extraction(sources, names.slice(0, 4)), mapsImpl: {
    async verify() { mapCalls++; throw Object.assign(new Error(`URL?key=${SECRET}`), { code: SECRET }); },
  } });
  const result = await engine.research({ destination: '杭州', count: 1 });
  assert.equal(mapCalls, 1);
  assert.equal(result.status.map, 'unavailable');
  assert.ok(result.candidates.every((candidate) => candidate.lng === null));
  assert.equal(JSON.stringify(result).includes(SECRET), false);
});

test('public map city metadata survives for transit queries while malformed city codes are dropped', async () => {
  const { engine } = setup({ extractImpl: async ({ sources }) => extraction(sources, ['西湖', '灵隐寺']), mapsImpl: {
    async verify(candidate) {
      return { ...candidate, verification: candidate.name === '西湖' ? {
        status: 'verified', location: { longitude: 120, latitude: 30 },
        city: '杭州市', citycode: '0571', adcode: '330106',
      } : {
        status: 'ambiguous', matches: [
          { name: '灵隐寺', city: '杭州市', citycode: '0571', adcode: 330106 },
          { name: '其他分店', city: '杭州', citycode: `0571?key=${SECRET}`, adcode: '330106&private=1' },
        ],
      } };
    },
  } });
  const result = await engine.research({ destination: '杭州', count: 1 });
  const verified = result.candidates[0].verification;
  assert.equal(verified.city, '杭州市');
  assert.equal(verified.citycode, '0571');
  assert.equal(verified.adcode, '330106');
  const [valid, malformed] = result.candidates[1].verification.matches;
  assert.equal(valid.city, '杭州市');
  assert.equal(valid.citycode, '0571');
  assert.equal(valid.adcode, '330106');
  assert.equal(Object.hasOwn(malformed, 'citycode'), false);
  assert.equal(Object.hasOwn(malformed, 'adcode'), false);
  assert.equal(JSON.stringify(result).includes(SECRET), false);
});

test('failed image preflight keeps candidates and removes failed candidate/source images', async () => {
  const { engine } = setup({ details: { [IDS[0]]: detail(IDS[0], '西湖', ['https://sns-img.xhscdn.com/failed.png']) },
    imageFetch: async () => { throw new Error(`cookie=${TOKEN}`); } });
  const result = await engine.research({ destination: '杭州', count: 1 });
  assert.equal(result.candidates.length, 1);
  assert.equal(result.candidates[0].image, null);
  assert.deepEqual(result.sources[0].images, []);
  assert.equal(result.stats.imagesAvailable, 0);
  assert.match(result.warnings.join(' '), /配图本次无法读取/u);
});

test('no sourced places is an explicit empty result; model statistics cannot leak arbitrary fields', async () => {
  const { engine } = setup({ extractImpl: async ({ sources }) => extraction(sources, [], {
    stats: { private: SECRET, imagesRead: 1000000, aiRequests: 1 }, usage: { total_tokens: 42, private: SECRET },
  }) });
  const result = await engine.research({ destination: '杭州', count: 1 });
  assert.deepEqual(result.candidates, []);
  assert.match(result.warnings.join(' '), /没有提取出/u);
  assert.equal(result.stats.imagesRead, 0);
  assert.equal(result.stats.usage.total_tokens, 42);
  assert.equal(JSON.stringify(result).includes(SECRET), false);
});

test('an aborted research does not retrieve sources or return a partial/static success', async () => {
  const controller = new AbortController(); controller.abort();
  const { engine, calls } = setup();
  await assert.rejects(engine.research({ destination: '杭州', signal: controller.signal }), { code: 'ABORTED' });
  assert.equal(calls.length, 0);
});
