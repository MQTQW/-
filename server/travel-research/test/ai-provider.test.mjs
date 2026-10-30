import test from 'node:test';
import assert from 'node:assert/strict';
import { extractWithDeepSeek } from '../ai-provider.mjs';

const MODEL_URL = 'https://api.deepseek.com/chat/completions';
const KEY = 'fixture-api-key-do-not-use';
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+nmf0AAAAASUVORK5CYII=', 'base64');
const source = (overrides = {}) => ({ id: 'S1', title: '杭州样例攻略', body: '断桥适合慢慢散步。', images: [], ...overrides });
const candidate = (overrides = {}) => ({ name: '断桥', city: '杭州', category: 'attraction', suggestedDurationMinutes: 60,
  sourceRefs: [{ sourceId: 'S1', evidenceType: 'body', evidenceText: '断桥适合慢慢散步。' }],
  notes: '停留时间为建议，地点和交通待核验。', ...overrides });
const envelope = (content, { finish = 'stop', status = 200 } = {}) => new Response(JSON.stringify({
  choices: [{ finish_reason: finish, message: { content: typeof content === 'string' ? content : JSON.stringify(content) } }],
  usage: { prompt_tokens: 5, completion_tokens: 7, total_tokens: 12 },
}), { status, headers: { 'Content-Type': 'application/json' } });
const imageResponse = () => new Response(PNG, { headers: { 'Content-Type': 'image/png' } });
const call = (overrides) => extractWithDeepSeek({ apiKey: KEY, destination: '杭州', sources: [source()], ...overrides });

test('missing key fails before any network call', async () => {
  let calls = 0;
  await assert.rejects(call({ apiKey: '', fetchImpl: async () => { calls++; } }), { code: 'AI_NOT_CONFIGURED' });
  assert.equal(calls, 0);
});

test('text extraction sends only source content to the fixed authenticated model endpoint', async () => {
  const inputs = [source({ url: 'https://www.xiaohongshu.com/explore/example?access=private-fixture', arbitrarySecret: 'must-not-transmit' })];
  const result = await call({ sources: inputs, fetchImpl: async (url, options) => {
    assert.equal(url, MODEL_URL);
    assert.equal(options.headers.Authorization, `Bearer ${KEY}`);
    assert.equal(options.redirect, 'error');
    const payload = JSON.parse(options.body);
    assert.equal(payload.model, 'deepseek-flash');
    assert.equal(payload.stream, false);
    assert.equal(payload.messages[1].content.includes('private-fixture'), false);
    assert.equal(payload.messages[1].content.includes('must-not-transmit'), false);
    return envelope({ candidates: [candidate()] });
  } });
  assert.equal(result.extraction.candidates[0].name, '断桥');
  assert.equal(result.stats.imagesAttempted, 0);
  assert.equal(result.stats.aiRequests, 1);
  assert.deepEqual(result.usage, { prompt_tokens: 5, completion_tokens: 7, total_tokens: 12 });
  assert.equal(inputs[0].imageEvidence, undefined);
});

test('discovery mode is optional and leaves the default extraction prompt unchanged', async () => {
  const prompts = [];
  for (const options of [{}, { discoveryMode: false }]) {
    await call({ ...options, fetchImpl: async (_url, request) => {
      prompts.push(JSON.parse(request.body).messages[0].content);
      return envelope({ candidates: [candidate()] });
    } });
  }
  assert.equal(prompts[0], prompts[1]);
  assert.ok(prompts[0].startsWith('你是旅行攻略地点提取器。'));
  assert.equal(prompts[0].includes('帮助用户先挑景点'), false);
  let calls = 0;
  await assert.rejects(call({ discoveryMode: 'true', fetchImpl: async () => { calls++; } }), { code: 'AI_INVALID_INPUT' });
  assert.equal(calls, 0);
});

test('discovery mode accepts literal sourced food and activities and explicitly forbids invented coverage or venues', async () => {
  const body = '午餐吃片儿川，下午参加宋韵点茶，再去断桥散步。';
  const items = [
    candidate({ name: '片儿川', category: 'food', sourceRefs: [{ sourceId: 'S1', evidenceType: 'body', evidenceText: '午餐吃片儿川' }] }),
    candidate({ name: '宋韵点茶', category: 'other', sourceRefs: [{ sourceId: 'S1', evidenceType: 'body', evidenceText: '下午参加宋韵点茶' }] }),
  ];
  const result = await call({ discoveryMode: true, sources: [source({ body })], fetchImpl: async (url, request) => {
    assert.equal(url, MODEL_URL);
    const payload = JSON.parse(request.body);
    const prompt = payload.messages[0].content;
    for (const rule of ['只提取资料明确提及', '菜品或餐饮场所', 'other 仅表示资料明确提及的活动体验',
      '交通换乘、酒店住宿不属于 other', '不得为菜品推断门店或分店', '不得为了类别均衡编造菜品或体验',
      '不能拼接、改写或补全名称', '不生成行程', '不返回坐标、价格、链接', '对应转录的连续原文']) {
      assert.ok(prompt.includes(rule), rule);
    }
    assert.equal(JSON.parse(payload.messages[1].content).sources[0].body, body);
    return envelope({ candidates: items });
  } });
  assert.deepEqual(result.extraction.candidates, items);
  assert.equal(result.stats.imagesRead, 0);
  assert.equal(result.stats.aiRequests, 1);
});

test('discovery mode still rejects invented dishes, activities, and dish-to-restaurant name changes', async () => {
  for (const [name, category, evidenceText] of [
    ['龙井采茶', 'other', '龙井采茶'], ['西湖醋鱼', 'food', '西湖醋鱼'], ['片儿川餐厅', 'food', '午餐吃片儿川'],
  ]) {
    await assert.rejects(call({ discoveryMode: true, sources: [source({ body: '午餐吃片儿川。' })],
      fetchImpl: async () => envelope({ candidates: [candidate({ name, category,
        sourceRefs: [{ sourceId: 'S1', evidenceType: 'body', evidenceText }] })] }),
    }), { code: 'AI_INVALID_RESPONSE' });
  }
});

test('image path upgrades approved HTTP URL, never sends credentials to CDN, and uses inline image data', async () => {
  let phase = 0;
  const inputs = [source({ body: '攻略见图。', images: [{ url: 'http://sns-webpic-qc.xhscdn.com/example.png#ignored' }] })];
  const result = await call({ sources: inputs, fetchImpl: async (url, options) => {
    phase++;
    if (phase === 1) {
      assert.equal(url, 'https://sns-webpic-qc.xhscdn.com/example.png');
      assert.equal(options.redirect, 'error');
      assert.equal(Object.keys(options.headers).some((key) => /authorization|cookie/iu.test(key)), false);
      return imageResponse();
    }
    assert.equal(url, MODEL_URL);
    const payload = JSON.parse(options.body);
    if (phase === 2) {
      const imagePart = payload.messages[1].content.find((part) => part.type === 'image_url');
      assert.equal(imagePart.image_url.url, `data:image/png;base64,${PNG.toString('base64')}`);
      return envelope({ text: '第二天去灵隐寺，留出午休。' });
    }
    const content = JSON.parse(payload.messages[1].content);
    assert.equal(content.sources[0].imageEvidence[0].text, '第二天去灵隐寺，留出午休。');
    return envelope({ candidates: [candidate({ name: '灵隐寺', sourceRefs: [
      { sourceId: 'S1', evidenceType: 'image', imageIndex: 1, evidenceText: '第二天去灵隐寺' },
    ] })] });
  } });
  assert.equal(phase, 3);
  assert.equal(result.stats.imagesAttempted, 1);
  assert.equal(result.stats.imagesRead, 1);
  assert.equal(result.sources[0].imageEvidence[0].imageIndex, 1);
  assert.equal(result.sources[0].imageEvidence[0].method, 'deepseek-vision');
  assert.equal(result.stats.imageWarnings.length, 0);
  assert.equal(result.usage.total_tokens, 24);
});

test('an entire extraction processes at most three images and records unprocessed source images faithfully', async () => {
  let images = 0; let visions = 0;
  const sources = ['S1', 'S2'].map((id) => source({ id, body: '见图', images: Array.from({ length: 9 }, (_, i) => ({ url: `https://sns-img-bd.xhscdn.com/${id}/${i}.png` })) }));
  const result = await call({ sources, fetchImpl: async (url, options) => {
    if (url !== MODEL_URL) { images++; return imageResponse(); }
    const payload = JSON.parse(options.body);
    if (Array.isArray(payload.messages[1].content)) { visions++; return envelope({ text: '灵隐寺' }); }
    return envelope({ candidates: [] });
  } });
  assert.equal(images, 3);
  assert.equal(visions, 3);
  assert.equal(result.sources[0].images.length, 9);
  assert.deepEqual(result.sources[0].imageEvidence.map((item) => item.imageIndex), [1, 2, 3]);
  assert.equal(result.sources[1].imageEvidence.length, 0);
  assert.equal(result.stats.aiRequests, 4);
});

test('discovery OCR gives each short-body source a first-image attempt within the same three-image budget', async () => {
  const sources = ['S1', 'S2', 'S3'].map((id) => source({ id, body: '详见攻略图片。', images:
    Array.from({ length: 4 }, (_, index) => ({ url: `https://sns-img.xhscdn.com/${id}/${index + 1}.png` })) }));
  const originalSources = structuredClone(sources);
  const downloads = []; let visions = 0;
  const result = await call({ sources, discoveryMode: true, fetchImpl: async (url, request) => {
    if (url !== MODEL_URL) { downloads.push(url); return imageResponse(); }
    const payload = JSON.parse(request.body);
    if (Array.isArray(payload.messages[1].content)) { visions++; return envelope({ text: `第 ${visions} 篇攻略的清晰文字` }); }
    return envelope({ candidates: [] });
  } });
  assert.deepEqual(downloads, sources.map((item) => item.images[0].url));
  assert.equal(visions, 3);
  assert.equal(result.stats.imagesAttempted, 3);
  assert.equal(result.stats.imagesRead, 3);
  assert.equal(result.stats.aiRequests, 4);
  assert.deepEqual(result.sources.map((item) => item.imageEvidence.map((image) => image.imageIndex)), [[1], [1], [1]]);
  assert.deepEqual(result.sources.map((item) => item.images.map((image) => image.index)), [[1, 2, 3, 4], [1, 2, 3, 4], [1, 2, 3, 4]]);
  assert.deepEqual(sources, originalSources);
});

test('discovery OCR skips long bodies and retains original second-image indexes after the first-source round', async () => {
  const sources = [source({ id: 'S1', body: '断桥'.repeat(200), images: [{ url: 'https://sns-img.xhscdn.com/skip.png' }] }),
    ...['S2', 'S3'].map((id) => source({ id, body: '见图', images:
      [1, 2].map((index) => ({ url: `https://sns-img.xhscdn.com/${id}/${index}.png` })) }))];
  const downloads = [];
  const result = await call({ sources, discoveryMode: true, fetchImpl: async (url, request) => {
    if (url !== MODEL_URL) { downloads.push(url); return imageResponse(); }
    return envelope(Array.isArray(JSON.parse(request.body).messages[1].content) ? { text: '断桥适合散步。' } : { candidates: [] });
  } });
  assert.deepEqual(downloads, [sources[1].images[0].url, sources[2].images[0].url, sources[1].images[1].url]);
  assert.deepEqual(result.sources.map((item) => item.imageEvidence.map((image) => image.imageIndex)), [[], [1, 2], [1]]);
  assert.equal(result.stats.imagesAttempted, 3);
});

test('long body sources avoid automatic image downloads', async () => {
  const result = await call({ sources: [source({ body: '断桥'.repeat(200), images: [{ url: 'https://sns-img-bd.xhscdn.com/example.png' }] })],
    fetchImpl: async (url) => { assert.equal(url, MODEL_URL); return envelope({ candidates: [] }); } });
  assert.equal(result.stats.imagesAttempted, 0);
});

test('untrusted image hosts, credentials, ports and schemes never reach fetch', async (t) => {
  for (const url of ['https://evil.example/a.png', 'https://xhscdn.com.evil.example/a.png',
    'http://127.0.0.1/a.png', 'http://localhost/a.png', 'https://x.xhscdn.com:8443/a.png',
    'https://name:secret@x.xhscdn.com/a.png', 'file:///C:/private.png', 'https://x.xhscdn.com./a.png']) {
    await t.test(url, async () => {
      let calls = 0;
      const result = await call({ sources: [source({ images: [{ url }] })], fetchImpl: async (target) => {
        calls++; assert.equal(target, MODEL_URL); return envelope({ candidates: [candidate()] });
      } });
      assert.equal(calls, 1);
      assert.equal(result.stats.imagesRead, 0);
      assert.equal(result.stats.imageWarnings[0].code, 'IMAGE_URL_REJECTED');
      assert.equal(JSON.stringify(result.stats).includes('secret@'), false);
    });
  }
});

test('CDN redirects fail closed and fall back to body only', async () => {
  const result = await call({ sources: [source({ images: [{ url: 'https://x.xhscdn.com/a.png' }] })], fetchImpl: async (url, options) => {
    if (url !== MODEL_URL) { assert.equal(options.redirect, 'error'); return new Response(null, { status: 302, headers: { Location: 'http://127.0.0.1/' } }); }
    return envelope({ candidates: [candidate()] });
  } });
  assert.equal(result.stats.imagesRead, 0);
  assert.equal(result.stats.imageWarnings[0].code, 'IMAGE_UNAVAILABLE');
});

test('mismatched MIME or fake image bytes are rejected before vision', async (t) => {
  for (const [label, body, mime] of [['mismatch', PNG, 'image/jpeg'], ['fake', '<script>fixture</script>', 'image/png'], ['html', PNG, 'text/html']]) {
    await t.test(label, async () => {
      let modelCalls = 0;
      const result = await call({ sources: [source({ images: [{ url: 'https://x.xhscdn.com/a.png' }] })], fetchImpl: async (url) => {
        if (url !== MODEL_URL) return new Response(body, { headers: { 'Content-Type': mime } });
        modelCalls++; return envelope({ candidates: [candidate()] });
      } });
      assert.equal(modelCalls, 1);
      assert.equal(result.stats.imageWarnings[0].code, 'IMAGE_FORMAT_REJECTED');
    });
  }
});

test('oversized image bodies are bounded while streaming, without relying on Content-Length', async () => {
  let cancelled = false;
  const result = await call({ sources: [source({ images: [{ url: 'https://x.xhscdn.com/a.png' }] })], fetchImpl: async (url) => {
    if (url === MODEL_URL) return envelope({ candidates: [candidate()] });
    return new Response(new ReadableStream({ start(controller) {
      controller.enqueue(new Uint8Array(8 * 1024 * 1024));
      controller.enqueue(new Uint8Array(1));
    }, cancel() { cancelled = true; } }), { headers: { 'Content-Type': 'image/png' } });
  } });
  assert.equal(result.stats.imageWarnings[0].code, 'IMAGE_TOO_LARGE');
  assert.equal(cancelled, true);
});

test('failed recognition stays an explicit warning and cannot create image evidence', async () => {
  let modelCalls = 0;
  const result = await call({ sources: [source({ images: [{ url: 'https://x.xhscdn.com/a.png' }] })], fetchImpl: async (url) => {
    if (url !== MODEL_URL) return imageResponse();
    modelCalls++;
    return modelCalls === 1 ? envelope({ text: '' }) : envelope({ candidates: [candidate()] });
  } });
  assert.equal(result.stats.imagesDownloaded, 1);
  assert.equal(result.stats.imagesRead, 0);
  assert.equal(result.stats.imageWarnings[0].code, 'IMAGE_NO_TEXT');
  assert.deepEqual(result.sources[0].imageEvidence, []);
});

test('truncated, non-JSON and oversized AI responses are not accepted', async (t) => {
  for (const [label, response, code] of [
    ['truncated', () => envelope({ candidates: [candidate()] }, { finish: 'length' }), 'AI_INVALID_RESPONSE'],
    ['non-json', () => envelope('```json\n{}\n```'), 'AI_INVALID_RESPONSE'],
    ['too-large', () => new Response('ignored', { headers: { 'Content-Length': String(1024 * 1024 + 1) } }), 'AI_RESPONSE_TOO_LARGE'],
  ]) {
    await t.test(label, async () => { await assert.rejects(call({ fetchImpl: async () => response() }), { code }); });
  }
});

test('model output cannot cite another source, an unread image or invented evidence', async (t) => {
  for (const [label, override] of [
    ['unknown source', { sourceRefs: [{ sourceId: 'S9', evidenceType: 'body', evidenceText: '断桥适合慢慢散步。' }] }],
    ['unread image', { sourceRefs: [{ sourceId: 'S1', evidenceType: 'image', imageIndex: 1, evidenceText: '断桥适合慢慢散步。' }] }],
    ['invented quote', { sourceRefs: [{ sourceId: 'S1', evidenceType: 'body', evidenceText: '断桥每天免费开放。' }] }],
    ['quote does not name candidate', { name: '灵隐寺' }],
    ['invented URL', { url: 'https://example.com/' }],
  ]) {
    await t.test(label, async () => { await assert.rejects(call({ fetchImpl: async () => envelope({ candidates: [candidate(override)] }) }), { code: 'AI_INVALID_RESPONSE' }); });
  }
});

test('API failures expose no key, upstream response or arbitrary exception text', async () => {
  await assert.rejects(call({ fetchImpl: async () => new Response(`upstream-private ${KEY}`, { status: 401 }) }), (error) => {
    assert.equal(error.code, 'AI_UNAVAILABLE');
    assert.equal(error.message.includes(KEY), false);
    assert.equal(error.message.includes('upstream-private'), false);
    return true;
  });
  await assert.rejects(call({ fetchImpl: async () => { throw new Error(`bad network ${KEY}`); } }), (error) => {
    assert.equal(error.code, 'AI_UNAVAILABLE');
    assert.equal(error.message.includes(KEY), false);
    return true;
  });
});

test('cancellation stops rather than silently downgrading a cancelled image request', async () => {
  const controller = new AbortController();
  let calls = 0;
  await assert.rejects(call({ signal: controller.signal, sources: [source({ images: [{ url: 'https://x.xhscdn.com/a.png' }] })],
    fetchImpl: async () => { calls++; controller.abort(); throw new Error('cancelled'); } }), { code: 'ABORTED' });
  assert.equal(calls, 1);
});

test('mixed extraction keeps valid candidates and discards invalid ones without exposing raw output', async () => {
  const result = await call({ fetchImpl: async () => envelope({ candidates: [
    candidate(),
    candidate({ url: 'https://invented-fixture.example/' }),
    candidate({ sourceRefs: [{ sourceId: 'S9', evidenceType: 'body', evidenceText: '断桥适合慢慢散步。' }] }),
    null,
  ] }) });
  assert.deepEqual(result.extraction.candidates, [candidate()]);
  assert.equal(result.stats.candidatesProposed, 4);
  assert.equal(result.stats.candidatesAccepted, 1);
  assert.equal(result.stats.rejectedCandidates, 3);
  assert.deepEqual(result.stats.rejectedCandidateIndexes, [1, 2, 3]);
  assert.equal(Object.hasOwn(result, 'raw'), false);
  assert.equal(JSON.stringify(result).includes('invented-fixture'), false);
});

test('all invalid candidates still reject the batch and report only safe rejection counts', async () => {
  await assert.rejects(call({ fetchImpl: async () => envelope({ candidates: [
    candidate({ url: 'https://invented-fixture.example/' }),
    candidate({ name: '灵隐寺' }),
  ] }) }), (error) => {
    assert.equal(error.code, 'AI_INVALID_RESPONSE');
    assert.equal(error.stats.rejectedCandidates, 2);
    assert.equal(error.stats.candidatesAccepted, 0);
    assert.deepEqual(error.stats.rejectedCandidateIndexes, [0, 1]);
    assert.equal(JSON.stringify(error).includes('invented-fixture'), false);
    return true;
  });
});

test('malformed outer frames never become a partial success, while an explicitly empty list stays valid', async (t) => {
  for (const raw of [{ candidates: [candidate()], extra: true }, { candidates: null }, { candidates: {} }]) {
    await t.test(JSON.stringify(raw), async () => {
      await assert.rejects(call({ fetchImpl: async () => envelope(raw) }), { code: 'AI_INVALID_RESPONSE' });
    });
  }
  const empty = await call({ fetchImpl: async () => envelope({ candidates: [] }) });
  assert.deepEqual(empty.extraction.candidates, []);
  assert.equal(empty.stats.rejectedCandidates, 0);
  assert.equal(empty.stats.candidatesProposed, 0);
});

test('checkpoints expose cloned public source material and extraction output, never provider credentials', async () => {
  const packets = []; let modelCalls = 0;
  const result = await call({ sources: [source({ apiKey: KEY, internalValue: 'private-fixture',
    images: [{ url: 'https://x.xhscdn.com/a.png' }] })],
    onCheckpoint: async (packet) => { packets.push(packet); },
    fetchImpl: async (url) => {
      if (url !== MODEL_URL) return imageResponse();
      modelCalls++;
      return modelCalls === 1 ? envelope({ text: '断桥适合慢慢散步。' }) : envelope({ candidates: [candidate()] });
    } });
  assert.deepEqual(packets.map((packet) => packet.stage), ['vision', 'extraction']);
  assert.equal(packets[0].sources[0].imageEvidence.length, 1);
  assert.equal(Object.hasOwn(packets[0], 'raw'), false);
  assert.deepEqual(packets[1].raw, { candidates: [candidate()] });
  assert.equal(JSON.stringify(packets).includes(KEY), false);
  assert.equal(JSON.stringify(packets).includes('private-fixture'), false);
  assert.equal(result.stats.checkpointFailures, 0);
});

test('a checkpoint cannot mutate raw model output or evidence to bypass validation', async () => {
  await assert.rejects(call({ onCheckpoint: (packet) => {
    packet.sources[0].body = '灵隐寺是伪造证据。';
    packet.raw.candidates[0] = candidate();
  }, fetchImpl: async () => envelope({ candidates: [candidate({ name: '灵隐寺' })] }) }), { code: 'AI_INVALID_RESPONSE' });
});

test('throwing or rejecting checkpoint hooks do not erase OCR or prevent validated extraction', async () => {
  let modelCalls = 0; let checkpoints = 0;
  const result = await call({ sources: [source({ images: [{ url: 'https://x.xhscdn.com/a.png' }] })],
    onCheckpoint: async () => { checkpoints++; throw new Error('fixture cache unavailable'); },
    fetchImpl: async (url) => {
      if (url !== MODEL_URL) return imageResponse();
      modelCalls++;
      return modelCalls === 1 ? envelope({ text: '断桥适合慢慢散步。' }) : envelope({ candidates: [candidate()] });
    } });
  assert.equal(checkpoints, 2);
  assert.equal(result.stats.checkpointFailures, 2);
  assert.equal(result.stats.imagesRead, 1);
  assert.equal(result.sources[0].imageEvidence.length, 1);
  assert.equal(result.extraction.candidates.length, 1);
});
