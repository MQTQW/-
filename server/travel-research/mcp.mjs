import { readFile } from 'node:fs/promises';
import { limitedJson } from './config.mjs';
const ALLOWED = new Set(['check_login_status', 'search_feeds', 'get_feed_detail']);

export async function createMcpClient(configPath, { fetchImpl = fetch } = {}) {
  const config = JSON.parse(await readFile(configPath, 'utf8'));
  const endpoint = new URL(config.url);
  if (endpoint.protocol !== 'http:' || !['127.0.0.1', '[::1]'].includes(endpoint.hostname) || endpoint.username || endpoint.password ||
      endpoint.pathname !== '/' || endpoint.search || endpoint.hash || typeof config.token !== 'string' || config.token.length < 32) {
    throw Object.assign(new Error('MCP 配置必须使用本机回环服务和访问令牌'), { code: 'INVALID_MCP_CONFIG' });
  }
  let sequence = 0;
  return {
    async call(name, args = {}) {
      if (!ALLOWED.has(name)) throw Object.assign(new Error('此研究工具仅允许读取公开笔记'), { code: 'MCP_TOOL_NOT_ALLOWED' });
      let response;
      try {
        response = await fetchImpl(new URL('/mcp', endpoint), {
          method: 'POST', redirect: 'error', signal: AbortSignal.timeout(120_000),
          headers: { Authorization: `Bearer ${config.token}`, Accept: 'application/json, text/event-stream',
            'Content-Type': 'application/json', 'MCP-Protocol-Version': '2025-06-18' },
          body: JSON.stringify({ jsonrpc: '2.0', id: ++sequence, method: 'tools/call', params: { name, arguments: args } }),
        });
      } catch { throw Object.assign(new Error('本机 MCP 未运行或请求超时'), { code: 'MCP_UNAVAILABLE' }); }
      const rpc = await limitedJson(response, 2 * 1024 * 1024);
      if (rpc.error || rpc.result?.isError) throw Object.assign(new Error('MCP 读取失败，请检查登录状态或稍后重试'), { code: 'MCP_READ_FAILED' });
      for (const part of rpc.result?.content ?? []) {
        if (part.type === 'text') {
          try { const data = JSON.parse(part.text); if (data && typeof data === 'object') return data; } catch {}
        }
      }
      throw Object.assign(new Error('MCP 返回格式无法识别'), { code: 'INVALID_MCP_RESULT' });
    },
  };
}

export function normalizeNote(detail, sourceId, fetchedAt = new Date().toISOString(), expectedPostId) {
  const note = detail?.data?.note;
  if (!note || !/^[a-f0-9]{24}$/u.test(note.noteId ?? '') || detail.feed_id !== note.noteId || typeof note.title !== 'string' ||
      typeof note.desc !== 'string') throw Object.assign(new Error('笔记字段不完整'), { code: 'INVALID_NOTE' });
  if (expectedPostId !== undefined && note.noteId !== expectedPostId)
    throw Object.assign(new Error('笔记详情与请求的帖子 ID 不一致'), { code: 'NOTE_ID_MISMATCH' });
  const timestamp = note.time > 1e11 ? note.time : note.time * 1000;
  const published = new Date(timestamp);
  return { id: sourceId, postId: note.noteId, title: note.title, url: `https://www.xiaohongshu.com/explore/${note.noteId}`,
    publishedAt: Number.isFinite(published.getTime()) ? published.toISOString() : null, fetchedAt,
    body: note.desc.slice(0, 20_000), bodyTruncated: note.desc.length > 20_000,
    images: (note.imageList ?? []).slice(0, 30).map((img, index) => ({ index: index + 1, url: img.urlDefault || img.urlPre || '' })),
    imageEvidence: [] };
}

export async function collectSources(client, { keyword, count = 5, onProgress = () => {} }) {
  if (typeof keyword !== 'string' || !keyword.trim() || keyword.length > 300 || !Number.isInteger(count) || count < 1 || count > 5)
    throw Object.assign(new Error('检索关键词或来源数量无效'), { code: 'INVALID_RETRIEVAL_INPUT' });
  const found = await client.call('search_feeds', { keyword });
  const seen = new Set(); const sources = []; const warnings = [];
  const feeds = (found.feeds ?? []).filter((feed) => {
    if (!/^[a-f0-9]{24}$/u.test(feed.id ?? '') || typeof feed.xsecToken !== 'string' || !feed.xsecToken || seen.has(feed.id)) return false;
    seen.add(feed.id); return true;
  });
  for (const feed of feeds.slice(0, Math.max(1, Math.min(count, 5)))) {
    onProgress(`正在读取第 ${sources.length + 1} 篇攻略`);
    try {
      const detail = await client.call('get_feed_detail', { feed_id: feed.id, xsec_token: feed.xsecToken, load_all_comments: false });
      sources.push(normalizeNote(detail, `S${sources.length + 1}`, new Date().toISOString(), feed.id));
    } catch { warnings.push(`笔记 ${feed.id} 本次未能读取，未纳入候选来源。`); }
  }
  if (!sources.length) throw Object.assign(new Error('本次没有读到攻略正文'), { code: 'NO_SOURCES' });
  return { sources, warnings, resultCount: found.feeds?.length ?? 0 };
}
