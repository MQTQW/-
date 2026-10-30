import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { createReadOnlyMcpClient } from '../mcp-client.mjs';

const TOKEN = 'fixture-token-for-local-stub-only-123456789';
async function configFile(t, overrides = {}) {
  const tempParent = path.resolve(os.tmpdir());
  const directory = await mkdtemp(path.join(tempParent, 'myplan-mcp-test-'));
  t.after(async () => {
    const target = path.resolve(directory);
    assert.ok(target.startsWith(`${tempParent}${path.sep}myplan-mcp-test-`));
    await rm(target, { recursive: true, force: true });
  });
  const file = path.join(directory, 'config.json');
  await writeFile(file, JSON.stringify({ url: 'http://127.0.0.1:54321/', token: TOKEN, ...overrides }));
  return file;
}
const rpc = content => new Response(JSON.stringify({ jsonrpc: '2.0', id: 1, result: { content } }),
  { status: 200, headers: { 'Content-Type': 'application/json' } });

test('MCP permits only login/search/detail reads and supports plain-text login plus JSON records', async t => {
  const file = await configFile(t), requests = [];
  const client = await createReadOnlyMcpClient(file, { fetchImpl: async (url, init) => {
    const body = JSON.parse(init.body); requests.push({ url: String(url), init, body });
    if (body.params.name === 'check_login_status') return rpc([{ type: 'text', text: '已登录，可以读取公开内容。' }]);
    return rpc([{ type: 'text', text: JSON.stringify({ name: body.params.name, publicData: true }) }]);
  } });
  const login = await client.call('check_login_status');
  assert.match(login.text, /已登录/);
  assert.equal((await client.call('search_feeds', { keyword: '杭州' })).name, 'search_feeds');
  assert.equal((await client.call('get_feed_detail', { feed_id: 'public-fixture-id' })).name, 'get_feed_detail');
  for (const name of ['publish_content', 'like_feed', 'post_comment_to_feed', 'delete_feed', 'unknown']) {
    await assert.rejects(client.call(name), error => error.code === 'MCP_TOOL_NOT_ALLOWED');
  }
  assert.equal(requests.length, 3);
  assert.ok(requests.every(request => request.url === 'http://127.0.0.1:54321/mcp'));
  assert.ok(requests.every(request => request.init.method === 'POST' && request.init.redirect === 'error'));
  assert.ok(requests.every(request => request.init.headers.Authorization === `Bearer ${TOKEN}`));
  assert.deepEqual(requests.map(request => request.body.id), [1, 2, 3]);
  assert.deepEqual(requests[1].body.params.arguments, { keyword: '杭州' });
});

test('MCP rejects external, credentialed and malformed endpoints without any fetch', async t => {
  for (const configuration of [{ url: 'https://127.0.0.1:54321/' }, { url: 'http://external.example/' },
    { url: 'http://localhost:54321/' }, { url: 'http://user:pass@127.0.0.1:54321/' },
    { url: 'http://127.0.0.1:54321/path' }, { url: 'http://127.0.0.1:54321/?query=1' },
    { url: 'http://127.0.0.1:54321/#fragment' }, { token: 'short' }]) {
    let fetched = false;
    await assert.rejects(createReadOnlyMcpClient(await configFile(t, configuration), { fetchImpl: async () => {
      fetched = true; throw new Error('Should not fetch.');
    } }), error => error.code === 'INVALID_MCP_CONFIG');
    assert.equal(fetched, false);
  }
});

test('MCP provider errors and malformed research responses remain sanitized', async t => {
  const file = await configFile(t);
  for (const [response, code] of [
    [() => { throw new Error(TOKEN); }, 'MCP_UNAVAILABLE'],
    [() => new Response(TOKEN, { status: 503 }), 'MCP_UNAVAILABLE'],
    [() => new Response(JSON.stringify({ error: { message: TOKEN } }), { status: 200 }), 'MCP_READ_FAILED'],
    [() => new Response(JSON.stringify({ result: { isError: true, content: [{ type: 'text', text: TOKEN }] } }), { status: 200 }), 'MCP_READ_FAILED'],
    [() => rpc([{ type: 'text', text: TOKEN }]), 'INVALID_MCP_RESULT'],
  ]) {
    const client = await createReadOnlyMcpClient(file, { fetchImpl: response });
    await assert.rejects(client.call('search_feeds', { keyword: '杭州' }), error => {
      assert.equal(error.code, code);
      assert.equal(`${error.message} ${error.stack}`.includes(TOKEN), false);
      return true;
    });
  }
});
