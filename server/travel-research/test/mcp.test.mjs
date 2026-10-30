import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, realpath, writeFile, rm, access } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { collectSources, createMcpClient, normalizeNote } from '../mcp.mjs';

const firstId = '111111111111111111111111', secondId = '222222222222222222222222';
const detail = (id) => ({ feed_id: id, data: { note: { noteId: id, title: '公开样例', desc: '西湖', time: 1_780_000_000_000, imageList: [] } } });
async function tempDirectory(t) {
  const base = await realpath(os.tmpdir());
  const directory = await mkdtemp(path.join(base, 'myplan-mcp-test-'));
  t.after(async () => { assert.equal(path.dirname(directory), base); await rm(directory, { recursive: true, force: true }); });
  return directory;
}

test('note normalization binds both returned IDs to the requested post', () => {
  assert.equal(normalizeNote(detail(firstId), 'S1', '2026-10-28T00:00:00Z', firstId).postId, firstId);
  assert.throws(() => normalizeNote(detail(secondId), 'S1', undefined, firstId), { code: 'NOTE_ID_MISMATCH' });
  const invalidTime = detail(firstId); invalidTime.data.note.time = 1e100;
  assert.equal(normalizeNote(invalidTime, 'S1').publishedAt, null);
});

test('source collection rejects mismatched details instead of citing a different requested post', async () => {
  const client = { call: async (name) => name === 'search_feeds'
    ? { feeds: [{ id: firstId, xsecToken: 'public-access-fixture' }] } : detail(secondId) };
  await assert.rejects(collectSources(client, { keyword: '杭州攻略', count: 1 }), { code: 'NO_SOURCES' });
  let called = false;
  await assert.rejects(collectSources({ call: async () => { called = true; } }, { keyword: '杭州攻略', count: NaN }), { code: 'INVALID_RETRIEVAL_INPUT' });
  assert.equal(called, false);
});

test('MCP client denies write tools without sending a request', async (t) => {
  const directory = await tempDirectory(t);
  const config = path.join(directory, 'fixture-config.json');
  await writeFile(config, JSON.stringify({ url: 'http://127.0.0.1:18062', token: 't'.repeat(40) }));
  let called = false;
  const client = await createMcpClient(config, { fetchImpl: async () => { called = true; } });
  await assert.rejects(client.call('publish_content', { content: 'not authorized' }), { code: 'MCP_TOOL_NOT_ALLOWED' });
  assert.equal(called, false);
});

test('capture CLI rejects a file whose filename ID differs from its content', async (t) => {
  const directory = await tempDirectory(t);
  await writeFile(path.join(directory, `mcp-detail-${firstId}.json`), JSON.stringify({ data: detail(secondId) }));
  const output = path.join(directory, 'output');
  const cli = fileURLToPath(new URL('../cli.mjs', import.meta.url));
  const child = spawnSync(process.execPath, [cli, '--capture-dir', directory, '--out', output,
    '--config', path.join(directory, 'absent.env')], { encoding: 'utf8', env: { ...process.env, DEEPSEEK_API_KEY: '', AMAP_WEB_SERVICE_KEY: '' } });
  assert.equal(child.status, 1);
  assert.match(child.stderr, /NOTE_ID_MISMATCH/u);
  await assert.rejects(access(output));
});
