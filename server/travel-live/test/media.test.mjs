import test from 'node:test';
import assert from 'node:assert/strict';
import { createMediaStore } from '../media.mjs';

const png = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 0]);
const response = (bytes = png, headers = {}) => new Response(bytes, { headers: { 'content-type': 'image/png', ...headers } });
const metadata = { sourceId: 'S1', index: 1 };
const source = 'http://sns-img.xhscdn.com/real-public-image?signature=private-upstream';

test('only internal approved host URLs register; SSRF, credentials, ports and fragments are rejected', () => {
  const store = createMediaStore({ fetchImpl: () => assert.fail('registration must be lazy') });
  for (const url of [
    'http://127.0.0.1/image', 'https://169.254.169.254/image', 'https://xhscdn.com.evil.test/image',
    'file:///a.png', 'data:image/png,foo', 'https://user:secret@xhscdn.com/a',
    'https://xhscdn.com:443/a', 'http://xhscdn.com:80/a', 'https://xhscdn.com:8080/a',
    'https://xhscdn.com/a#fragment', 'https://xhscdn.com/a#', 'https://xhscdn.com\\@evil.test/a',
    ' https://xhscdn.com/a', 'https://xhscdn.com\n.evil.test/a', 'https://xhscdn.com./a',
  ]) assert.equal(store.register(url, metadata), null, url);
  assert.equal(store.register('https://xhscdn.com/a', { sourceId: '../source', index: 1 }), null);
  assert.equal(store.register('https://xhscdn.com/a', { sourceId: 'S1', index: 0 }), null);
  assert.equal(store.has('https://xhscdn.com/a'), false);
});

test('register returns opaque descriptors, normalizes HTTP, deduplicates and keeps credentials out of fetch', async () => {
  let seen;
  const store = createMediaStore({ fetchImpl: async (url, options) => { seen = { url, options }; return response(); } });
  const image = store.register(source, metadata);
  assert.match(image.id, /^[A-Za-z0-9_-]{24}$/u);
  assert.equal(image.url, `/api/images/${image.id}`);
  assert.equal(image.caption, '攻略配图');
  assert.equal(JSON.stringify(image).includes('private-upstream'), false);
  assert.deepEqual(store.register(source.replace('http:', 'https:'), metadata), image);
  const data = await store.get(image.id);
  assert.equal(data.mime, 'image/png');
  assert.deepEqual(data.bytes, png);
  assert.equal(seen.url, source.replace('http:', 'https:'));
  assert.equal(seen.options.redirect, 'error');
  assert.equal(seen.options.credentials, 'omit');
  assert.deepEqual(Object.keys(seen.options.headers), ['Accept']);
});

test('registry and bytes are bounded; concurrent reads share download and returned bytes do not mutate cache', async () => {
  let requests = 0;
  const store = createMediaStore({ maxEntries: 1, maxBytes: png.length, fetchImpl: async () => { requests++; return response(); } });
  const image = store.register(source, metadata);
  const [one, two] = await Promise.all([store.get(image.id), store.get(image.id)]);
  one.bytes[0] = 0;
  assert.deepEqual(two.bytes, png);
  assert.deepEqual((await store.get(image.id)).bytes, png);
  assert.equal(requests, 1);
  assert.deepEqual(store.stats(), { registered: 1, cached: 1, available: 1, pending: 0, failed: 0, bytesCached: png.length, downloads: 1 });
  const other=store.register('https://xhscdn.com/other',{sourceId:'S1',index:2});
  assert.ok(other);assert.equal(store.has(image.id),false);assert.equal(store.has(other.id),true);
  assert.equal(store.stats().registered,1);assert.equal(store.stats().bytesCached,0);
});

test('new research images replace least-recently-used entries and reverse keys never point to evicted IDs', async()=>{
  const store=createMediaStore({maxEntries:2,fetchImpl:async()=>response()});
  const a=store.register('https://xhscdn.com/a',{sourceId:'S1',index:1});
  const b=store.register('https://xhscdn.com/b',{sourceId:'S2',index:1});
  await store.get(a.id);
  const c=store.register('https://xhscdn.com/c',{sourceId:'S3',index:1});
  assert.ok(c);assert.equal(store.has(a.id),true);assert.equal(store.has(b.id),false);
  const freshB=store.register('https://xhscdn.com/b',{sourceId:'S2',index:1});
  assert.notEqual(freshB.id,b.id);assert.equal(store.stats().registered,2);
  assert.deepEqual((await store.get(freshB.id)).bytes,png);
});

test('in-flight downloads are not evicted and the registry accepts new images once they settle',async()=>{
  let finish;
  const store=createMediaStore({maxEntries:1,fetchImpl:()=>new Promise(resolve=>{finish=()=>resolve(response());})});
  const a=store.register('https://xhscdn.com/a',{sourceId:'S1',index:1});const pending=store.get(a.id);
  assert.equal(store.register('https://xhscdn.com/b',{sourceId:'S2',index:1}),null);
  finish();await pending;
  assert.ok(store.register('https://xhscdn.com/b',{sourceId:'S2',index:1}));
});

test('temporary image failures can be retried without exposing or changing upstream URLs',async()=>{
  let clock=0,calls=0;const store=createMediaStore({now:()=>clock,fetchImpl:async()=>{if(++calls===1)throw Error('private');return response();}});
  const a=store.register('https://xhscdn.com/a',{sourceId:'S1',index:1});await assert.rejects(store.get(a.id));
  await assert.rejects(store.get(a.id));assert.equal(calls,1);clock=30001;
  assert.equal((await store.get(a.id)).mime,'image/png');assert.equal(calls,2);
});

test('oversized image fails with header or streamed body, and errors expose no source URL', async () => {
  for (const headers of [{ 'content-length': '99999999' }, {}]) {
    const store = createMediaStore({ maxBytes: 8, fetchImpl: async () => response(png, headers) });
    const image = store.register(source, metadata);
    await assert.rejects(store.get(image.id), (error) => error.code === 'IMAGE_TOO_LARGE' && !error.message.includes('private-upstream'));
    assert.equal(store.stats().available, 0);
    assert.equal(store.stats().failed, 1);
  }
});

test('content magic must match a supported declared MIME, including SVG and HTML rejection', async () => {
  for (const [bytes, mime] of [[png, 'image/jpeg'], [png, 'text/html'], [Buffer.from('<svg/>'), 'image/svg+xml'], [Buffer.from('<html>'), 'image/png']]) {
    const store = createMediaStore({ fetchImpl: async () => response(bytes, { 'content-type': mime }) });
    const image = store.register(source, metadata);
    await assert.rejects(store.get(image.id), { code: 'IMAGE_FORMAT_REJECTED' });
  }
});

test('JPEG, PNG, GIF and WEBP signatures are accepted only with their matching MIME', async () => {
  for (const [bytes, mime] of [
    [Buffer.from([255, 216, 255, 224]), 'image/jpeg'], [png, 'image/png'],
    [Buffer.from('GIF89a'), 'image/gif'], [Buffer.from('RIFF0000WEBP'), 'image/webp'],
  ]) {
    const store = createMediaStore({ fetchImpl: async () => response(bytes, { 'content-type': `${mime}; charset=binary` }) });
    const image = store.register(source, metadata);
    assert.equal((await store.get(image.id)).mime, mime);
  }
});

test('redirect responses or followed redirects are rejected, even when a custom fetch ignores redirect:error', async () => {
  for (const override of [
    { ok: false, status: 302 }, { redirected: true }, { url: 'https://127.0.0.1/private' },
    { url: 'https://xhscdn.com/different' },
  ]) {
    const store = createMediaStore({ fetchImpl: async () => {
      const result = response();
      for (const [key, value] of Object.entries(override)) Object.defineProperty(result, key, { value });
      return result;
    } });
    const image = store.register(source, metadata);
    await assert.rejects(store.get(image.id), { code: 'IMAGE_UNAVAILABLE' });
  }
});

test('arbitrary IDs never fetch and upstream error payloads are sanitized and not repeatedly fetched', async () => {
  let calls = 0;
  const store = createMediaStore({ fetchImpl: async () => { calls++; throw new Error('secret cookie and token and private URL'); } });
  await assert.rejects(store.get('https://xhscdn.com/a'), { code: 'IMAGE_NOT_FOUND' });
  assert.equal(calls, 0);
  const image = store.register(source, metadata);
  for (let attempt = 0; attempt < 2; attempt++) {
    await assert.rejects(store.get(image.id), (error) => error.code === 'IMAGE_UNAVAILABLE'
      && !/secret|cookie|token|URL/u.test(error.message));
  }
  assert.equal(calls, 1);
});

test('15 second deadline also bounds fetches and streamed bodies that ignore abort', async (context) => {
  context.mock.timers.enable({ apis: ['setTimeout'] });
  for (const fetchImpl of [
    async () => new Promise(() => {}),
    async () => new Response(new ReadableStream({ pull: () => new Promise(() => {}) }), {
      headers: { 'content-type': 'image/png' },
    }),
  ]) {
    const store = createMediaStore({ fetchImpl });
    const image = store.register(source, metadata);
    const rejection = assert.rejects(store.get(image.id), { code: 'IMAGE_TIMEOUT' });
    await Promise.resolve();
    await Promise.resolve();
    context.mock.timers.tick(15_000);
    await rejection;
    assert.equal(store.stats().failed, 1);
    assert.equal(store.stats().bytesCached, 0);
  }
});
