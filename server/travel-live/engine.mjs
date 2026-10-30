import { randomUUID } from 'node:crypto';
import { normalizeNote } from '../travel-research/mcp.mjs';
import { extractWithDeepSeek } from '../travel-research/ai-provider.mjs';
import { validateExtraction, mergeCandidates } from '../travel-research/contract.mjs';
import { createAmap } from '../travel-research/maps.mjs';
import { createMediaStore } from './media.mjs';

const MAX_CANDIDATES = 12;
const MAX_MAP_REQUESTS = 8;
const POST_ID = /^[a-f0-9]{24}$/u;
const CATEGORY = { attraction: 'sights', area: 'sights', food: 'food', other: 'experiences' };
const MESSAGES = {
  INVALID_RESEARCH_INPUT: '请填写 1～40 字的城市名称，并选择读取 1～3 篇公开攻略。',
  AI_NOT_CONFIGURED: '尚未配置 DeepSeek API Key，暂时无法解析真实攻略。',
  MCP_NOT_LOGGED_IN: '小红书尚未登录或登录已失效，请先在本机完成登录。',
  MCP_UNAVAILABLE: '本机小红书连接暂时不可用，请稍后重试。',
  MCP_READ_FAILED: '公开攻略本次读取失败，请稍后重试。',
  NO_SOURCES: '本次没有读到可用的公开攻略，请更换城市或稍后重试。',
  NO_CANDIDATES: '本次提取的地点均不属于所填目的地城市，请更换攻略或检索条件。',
  AI_UNAVAILABLE: 'AI 服务暂时无法完成攻略解析，请稍后重试。',
  AI_TIMEOUT: 'AI 攻略解析超时，请稍后重试。',
  AI_INVALID_INPUT: '攻略资料未通过 AI 解析输入检查。',
  AI_INVALID_RESPONSE: 'AI 返回的地点或来源引用未通过校验，请重试。',
  AI_RESPONSE_TOO_LARGE: 'AI 返回内容超过本次解析的大小限制，请重试。',
  ABORTED: '研究任务已取消。',
  RESEARCH_FAILED: '本次攻略研究未能完成，请稍后重试。',
};

export class ResearchEngineError extends Error {
  constructor(code) {
    super(MESSAGES[code] ?? MESSAGES.RESEARCH_FAILED);
    this.name = 'ResearchEngineError';
    this.code = Object.hasOwn(MESSAGES, code) ? code : 'RESEARCH_FAILED';
  }
}

function checkCancelled(signal) {
  if (signal?.aborted) throw new ResearchEngineError('ABORTED');
}

async function cancellable(task, signal) {
  if (signal?.aborted) {
    // The promise argument may already have started before cancellation won.
    Promise.resolve(task).catch(() => {});
    throw new ResearchEngineError('ABORTED');
  }
  if (!signal) return task;
  let cancel;
  const aborted = new Promise((_, reject) => {
    cancel = () => reject(new ResearchEngineError('ABORTED'));
    signal.addEventListener('abort', cancel, { once: true });
  });
  try { return await Promise.race([task, aborted]); }
  finally { signal.removeEventListener('abort', cancel); }
}

function parsedLoginStatus(value, depth = 0) {
  if (depth > 4 || value == null) return null;
  if (typeof value === 'object') {
    for (const key of ['loggedIn', 'isLoggedIn', 'is_logged_in', 'logged_in']) {
      if (typeof value[key] === 'boolean') return value[key];
    }
    for (const key of ['data', 'result', 'status', 'message', 'text']) {
      if (value[key] !== undefined) {
        const parsed = parsedLoginStatus(value[key], depth + 1);
        if (parsed !== null) return parsed;
      }
    }
    for (const part of (Array.isArray(value.content) ? value.content : []).slice(0, 5)) {
      const parsed = part?.type === 'text' ? parsedLoginStatus(part.text, depth + 1) : null;
      if (parsed !== null) return parsed;
    }
    return null;
  }
  if (typeof value !== 'string' || value.length > 4096) return null;
  if (/未登录|尚未登录|登录失效|登录过期|not[\s_-]*logged|logged[\s_-]*out|unauthenticated/iu.test(value)) return false;
  return /已登录|登录成功|logged[\s_-]*in|authenticated/iu.test(value) ? true : null;
}

function validDestination(value) {
  return typeof value === 'string' && value.trim().length >= 1 && value.trim().length <= 40 &&
    /^[\p{L}\p{M}]+(?:[ ·.'-][\p{L}\p{M}]+)*$/u.test(value.trim());
}

function cityKey(value) {
  return String(value).normalize('NFKC').trim().replace(/市$/u, '').toLocaleLowerCase('en-US');
}

function approvedImageUrl(value) {
  if (typeof value !== 'string' || value.length > 8192 || /[\u0000-\u0020\u007f\\]/u.test(value)) return '';
  let url;
  try { url = new URL(value); } catch { return ''; }
  // Inspect the original authority too: URL drops an explicit default :443 port.
  const authority = value.match(/^https?:\/\/([^/?#]+)/iu)?.[1] ?? '';
  const host = url.hostname.toLowerCase();
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.port ||
      authority.includes(':') || value.includes('#') ||
      !['xhscdn.com', 'xiaohongshu.com'].some((domain) => host === domain || host.endsWith(`.${domain}`))) return '';
  url.protocol = 'https:';
  return url.href;
}

function textFilter(secrets) {
  return (value, max = 1000) => {
    let text = typeof value === 'string' ? value : '';
    for (const secret of secrets) if (secret && text.includes(secret)) text = text.split(secret).join('［已隐藏］');
    return text.replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/gu, '')
      .replace(/https?:\/\/[^\s<>"'）)]+/giu, (url) => {
        try {
          const parsed = new URL(url);
          if (/(?:^|\.)(?:xhscdn|xiaohongshu)\.com$/iu.test(parsed.hostname) || /xsec[_-]?token/iu.test(url)) return '［来源链接已隐藏］';
        } catch { /* This is plain text, never an upstream request URL. */ }
        return url;
      })
      .replace(/(?:xsec[_-]?token|authorization|cookie)\s*[:=]\s*[^\s,;]+/giu, '［访问凭据已隐藏］')
      .trim().slice(0, max);
  };
}

function publicAuthor(detail, safeText) {
  const note = detail?.data?.note;
  for (const value of [note?.user?.nickname, note?.user?.nickName, note?.author?.nickname,
    note?.author?.name, note?.nickname]) {
    const name = safeText(value, 100);
    if (name) return name;
  }
  return '公开作者未提供';
}

function enhancedSources(originals, enhanced) {
  const returned = new Map((Array.isArray(enhanced) ? enhanced : []).map((source) => [source?.id, source]));
  let remaining = 3;
  return originals.map((source) => {
    const enriched = returned.get(source.id);
    const imageEvidence = [];
    for (const evidence of (Array.isArray(enriched?.imageEvidence) ? enriched.imageEvidence : []).slice(0, 3)) {
      if (remaining < 1) break;
      if (Number.isInteger(evidence?.imageIndex) && evidence.imageIndex >= 1 &&
          source.images[evidence.imageIndex - 1]?.url && typeof evidence.text === 'string' &&
          evidence.text.length > 0 && evidence.text.length <= 20_000 &&
          !imageEvidence.some((item) => item.imageIndex === evidence.imageIndex)) {
        imageEvidence.push({ imageIndex: evidence.imageIndex, text: evidence.text });
        remaining--;
      }
    }
    // The AI cannot replace source bodies, original identities, or image URLs.
    return { ...source, imageEvidence };
  });
}

function finitePoint(location) {
  return Number.isFinite(location?.longitude) && Math.abs(location.longitude) <= 180 &&
    Number.isFinite(location?.latitude) && Math.abs(location.latitude) <= 90;
}

function cityMetadata(raw, safeText) {
  const city = safeText(raw?.city, 80);
  const citycode = String(raw?.citycode ?? '');
  const adcode = String(raw?.adcode ?? '');
  return {
    ...(city ? { city } : {}),
    ...(/^\d{3,6}$/u.test(citycode) ? { citycode } : {}),
    ...(/^\d{6}$/u.test(adcode) ? { adcode } : {}),
  };
}

function verificationFor(raw, safeText) {
  const scope = '仅匹配地点名称、城市与坐标；开放、预约、门票及菜品尚未核实。';
  if (raw?.status === 'verified' && finitePoint(raw.location)) {
    return { status: 'verified', scope, reason: '地图返回了匹配的地点名称、城市与坐标。',
      location: { longitude: raw.location.longitude, latitude: raw.location.latitude },
      ...cityMetadata(raw, safeText),
      ...(safeText(raw.poiId, 100) ? { poiId: safeText(raw.poiId, 100) } : {}) };
  }
  const status = ['ambiguous', 'not_found'].includes(raw?.status) ? raw.status : 'unavailable';
  const reason = status === 'ambiguous' ? '名称或分店存在差异，需人工确认具体地点。'
    : status === 'not_found' ? '本次未匹配到可用地点，坐标保持未知。' : '地图本次未能完成地点核验，坐标保持未知。';
  const matches = (Array.isArray(raw?.matches) ? raw.matches : []).slice(0, 3).map((match) => ({
    name: safeText(match?.name, 120), city: safeText(match?.city, 80), address: safeText(match?.address, 200),
    ...cityMetadata(match, safeText),
    ...(finitePoint(match?.location) ? { location: { longitude: match.location.longitude, latitude: match.location.latitude } } : {}),
  }));
  return { status, scope, reason, ...(matches.length ? { matches } : {}) };
}

function boundedCounter(value, max = 1_000_000) {
  return Number.isSafeInteger(value) && value >= 0 ? Math.min(value, max) : 0;
}

function balancedCandidates(candidates, maximum) {
  const groups = ['sights', 'food', 'experiences'].map((category) =>
    candidates.filter((candidate) => CATEGORY[candidate.category] === category));
  const selected = [];
  let offset = 0;
  while (selected.length < maximum) {
    let added = false;
    for (const group of groups) {
      if (selected.length >= maximum) break;
      if (group[offset]) { selected.push(group[offset]); added = true; }
    }
    if (!added) break;
    offset++;
  }
  return selected;
}

/** Read public sources and discover cited candidates. Scheduling is a separate user action. */
export function createResearchEngine({ mcpClient, config = {}, media = createMediaStore(),
  extractImpl = extractWithDeepSeek, mapsImpl } = {}) {
  const maps = mapsImpl ?? createAmap({ apiKey: config.amapKey });

  async function readConnection(signal) {
    try {
      if (typeof mcpClient?.call !== 'function') return { loggedIn: false, unavailable: true };
      const status = await cancellable(mcpClient.call('check_login_status', {}), signal);
      checkCancelled(signal);
      return { loggedIn: parsedLoginStatus(status) === true, unavailable: false };
    } catch (error) {
      checkCancelled(signal);
      return { loggedIn: false, unavailable: true };
    }
  }

  async function checkConnection() {
    const state = await readConnection();
    return { loggedIn: state.loggedIn, message: state.unavailable ? MESSAGES.MCP_UNAVAILABLE
      : state.loggedIn ? '小红书已登录，可以读取公开攻略。' : MESSAGES.MCP_NOT_LOGGED_IN };
  }

  async function research({ destination, count = 3, onProgress, signal } = {}) {
    const secrets = new Set([config.aiKey, config.amapKey].filter((value) => typeof value === 'string' && value));
    const safeText = textFilter(secrets);
    const progress = (message) => {
      if (typeof onProgress === 'function') {
        try { Promise.resolve(onProgress(message)).catch(() => {}); } catch { /* Optional observer. */ }
      }
    };
    try {
      if (!validDestination(destination) || !Number.isInteger(count) || count < 1 || count > 3) {
        throw new ResearchEngineError('INVALID_RESEARCH_INPUT');
      }
      destination = destination.trim();
      checkCancelled(signal);
      if (typeof config.aiKey !== 'string' || !config.aiKey.trim()) throw new ResearchEngineError('AI_NOT_CONFIGURED');
      progress('正在检查小红书公开攻略连接');
      const connection = await readConnection(signal);
      if (!connection.loggedIn) throw new ResearchEngineError(connection.unavailable ? 'MCP_UNAVAILABLE' : 'MCP_NOT_LOGGED_IN');
      const topics = [
        { suffix: '旅游攻略', label: '综合攻略' },
        { suffix: '本地美食', label: '本地美食' },
        { suffix: '特色体验', label: '特色体验' },
      ].slice(0, count);
      const feeds = []; const selectedPostIds = new Set(); const reserves = new Map();
      const warnings = [];
      let searchFailures = 0, topicFallbackCount = 0;
      function selectFeed(feed) {
        if (!feed) return false;
        feeds.push(feed); selectedPostIds.add(feed.id); return true;
      }
      for (const topic of topics) {
        checkCancelled(signal);
        progress(`正在检索${destination}的${topic.label}公开笔记`);
        let found, failed = false;
        try { found = await cancellable(mcpClient.call('search_feeds', { keyword: `${destination}${topic.suffix}` }), signal); }
        catch (error) {
          checkCancelled(signal); failed = true; searchFailures++;
          warnings.push(`${topic.label}检索本次未完成；可用时改读已检索到的其他公开攻略，不代表已覆盖该主题。`);
        }
        const topicFeeds = []; const seenInTopic = new Set();
        for (const feed of (Array.isArray(found?.feeds) ? found.feeds : []).slice(0, 200)) {
          const token = feed?.xsecToken ?? feed?.xsec_token;
          if (!POST_ID.test(feed?.id ?? '') || seenInTopic.has(feed.id) || typeof token !== 'string' || !token ||
              token.length > 8192 || /[\u0000-\u001f\u007f]/u.test(token)) continue;
          seenInTopic.add(feed.id); secrets.add(token);
          const normalized = { id: feed.id, token };
          topicFeeds.push(normalized);
          if (!reserves.has(feed.id)) reserves.set(feed.id, normalized);
        }
        const topical = topicFeeds.find((feed) => !selectedPostIds.has(feed.id));
        if (!selectFeed(topical)) {
          const fallback = [...reserves.values()].find((feed) => !selectedPostIds.has(feed.id));
          if (selectFeed(fallback)) topicFallbackCount++;
          if (!failed) warnings.push(`${topic.label}检索未找到新的可读笔记；可用时改读已检索到的其他公开攻略，不代表已覆盖该主题。`);
        }
      }
      // Fill an earlier empty topic slot only from feeds already returned by these
      // searches. Failed detail reads never trigger extra post requests.
      for (const feed of reserves.values()) {
        if (feeds.length >= count) break;
        if (!selectedPostIds.has(feed.id) && selectFeed(feed)) topicFallbackCount++;
      }
      if (searchFailures === topics.length) throw new ResearchEngineError('MCP_READ_FAILED');
      const sources = [];
      for (const [index, feed] of feeds.entries()) {
        checkCancelled(signal);
        progress(`正在读取第 ${index + 1}/${feeds.length} 篇公开攻略的正文和图片目录`);
        try {
          const detail = await cancellable(mcpClient.call('get_feed_detail', {
            feed_id: feed.id, xsec_token: feed.token, load_all_comments: false,
          }), signal);
          const source = normalizeNote(detail, feed.id, new Date().toISOString(), feed.id);
          source.title = safeText(source.title, 300);
          source.author = publicAuthor(detail, safeText);
          source.images = source.images.map((image) => ({ index: image.index, url: approvedImageUrl(image.url) }));
          sources.push(source);
          if (source.bodyTruncated) warnings.push(`第 ${index + 1} 篇攻略正文较长，本次仅解析前 20000 字。`);
        } catch (error) {
          checkCancelled(signal);
          warnings.push(`第 ${index + 1} 篇公开攻略未能读取，未纳入候选来源。`);
        }
      }
      if (!sources.length) throw new ResearchEngineError('NO_SOURCES');
      progress(`已读取 ${sources.length} 篇真实攻略，正在用 AI 解析正文与最多 3 张攻略图片`);
      let result;
      try {
        result = await cancellable(extractImpl({ sources: structuredClone(sources), destination,
          apiKey: config.aiKey, model: config.model, maxImages: 3, signal, discoveryMode: true }), signal);
      } catch (error) {
        checkCancelled(signal);
        throw new ResearchEngineError(Object.hasOwn(MESSAGES, error?.code) && error.code.startsWith('AI_') ? error.code : 'AI_UNAVAILABLE');
      }
      checkCancelled(signal);
      const evidenceSources = enhancedSources(sources, result?.sources);
      let candidates;
      try { candidates = mergeCandidates(validateExtraction(result?.extraction, { sources: evidenceSources }).candidates); }
      catch { throw new ResearchEngineError('AI_INVALID_RESPONSE'); }
      const extractedCount = candidates.length;
      candidates = candidates.filter((candidate) => cityKey(candidate.city) === cityKey(destination));
      const outOfCityCount = extractedCount - candidates.length;
      if (outOfCityCount) {
        if (!candidates.length) throw new ResearchEngineError('NO_CANDIDATES');
        warnings.push(`已剔除 ${outOfCityCount} 个城市与目的地不一致的候选。`);
      }
      const beforeFiltering = candidates.length;
      candidates = candidates.filter((candidate) => candidate.sourceRefs.every((ref) => safeText(ref.evidenceText, 300) === ref.evidenceText));
      if (candidates.length < beforeFiltering) warnings.push('部分候选的来源引用含访问凭据或非公开图片链接，已剔除。');
      const excludedCandidateCount = Math.max(0, candidates.length - MAX_CANDIDATES);
      candidates = balancedCandidates(candidates, MAX_CANDIDATES);
      if (excludedCandidateCount) warnings.push(`本轮在已有景点、美食和体验类别间轮流保留 ${MAX_CANDIDATES} 个有引用的候选，另有 ${excludedCandidateCount} 个未纳入；未补造缺失类别。`);
      if (!candidates.length) warnings.push('本次攻略没有提取出通过来源校验的地点，可以更换检索条件重试。');
      const rejected = boundedCounter(result?.stats?.rejectedCandidates, 40);
      if (rejected) warnings.push(`AI 提出的 ${rejected} 个候选未通过来源或字段校验，已剔除。`);
      const imageWarnings = Array.isArray(result?.stats?.imageWarnings) ? result.stats.imageWarnings : [];
      if (imageWarnings.length) warnings.push(`${Math.min(3, imageWarnings.length)} 张攻略图片未能完成文字识别；未用图片地址冒充文字依据。`);

      let mapRequests = 0, matchedPlaceCount = 0, mapUnavailable = !(mapsImpl || config.amapKey);
      const geographicCount = candidates.filter((candidate) => candidate.category === 'attraction' || candidate.category === 'area').length;
      for (const candidate of candidates) {
        checkCancelled(signal);
        let verification;
        if (candidate.category === 'food' || candidate.category === 'other') {
          verification = { status: 'unverified',
            scope: '美食或体验意向不代表已选定具体场所；地点身份、菜品尚未核实。',
            reason: '美食或体验意向，需确认具体场所；不把菜名或活动自动匹配到商家' };
        } else if (mapUnavailable || mapRequests >= MAX_MAP_REQUESTS) {
          verification = { status: 'unverified', scope: '地点身份和坐标尚未核验。', reason: mapUnavailable
            ? '地图服务尚未配置或本次不可用，坐标保持未知。' : '本轮最多核验 8 个地点，此候选尚未查询地图。' };
        } else {
          mapRequests++;
          progress(`正在核对地点 ${mapRequests}/${Math.min(MAX_MAP_REQUESTS, geographicCount)} 的名称、城市与坐标`);
          try {
            const mapped = await cancellable(maps.verify(structuredClone(candidate)), signal);
            verification = verificationFor(mapped?.verification, safeText);
            if (verification.status === 'unavailable') mapUnavailable = true;
          } catch (error) {
            checkCancelled(signal); mapUnavailable = true;
            verification = verificationFor(null, safeText);
          }
        }
        candidate.verification = verification;
        if (verification.status === 'verified') matchedPlaceCount++;
      }
      const mapStatus = candidates.length && matchedPlaceCount === candidates.length ? 'success'
        : candidates.length && !geographicCount ? 'partial'
        : !mapRequests || mapUnavailable && !matchedPlaceCount ? 'unavailable' : 'partial';
      if (mapStatus !== 'success') warnings.push(mapRequests >= MAX_MAP_REQUESTS && geographicCount > MAX_MAP_REQUESTS
        ? '本轮最多查询 8 个地点；其余地点与未匹配地点保留待确认状态。'
        : '部分地点尚未完成地图身份核验，未知坐标保持空白。');

      const bySource = new Map(sources.map((source) => [source.id, source]));
      const registrations = new Map();
      const selectedImages = new Map();
      function register(sourceId, index) {
        const key = `${sourceId}:${index}`;
        if (registrations.has(key)) return registrations.get(key);
        if (registrations.size >= 12) return null;
        const image = bySource.get(sourceId)?.images[index - 1];
        const registered = image?.url ? media.register(image.url, { sourceId, index }) : null;
        if (registered) registrations.set(key, registered);
        return registered;
      }
      for (const candidate of candidates) {
        const citedImage = candidate.sourceRefs.find((ref) => ref.evidenceType === 'image');
        const sourceId = citedImage?.sourceId ?? candidate.sourceRefs[0].sourceId;
        selectedImages.set(candidate.id, register(sourceId, citedImage?.imageIndex ?? 1));
      }
      for (const source of sources) register(source.id, 1);
      for (const source of sources) for (const image of source.images) register(source.id, image.index);
      const failedImages = new Set(); let imagesAvailable = 0;
      const preflightImages = [...new Map([...registrations.values()].map((image) => [image.id, image])).values()].slice(0, 3);
      if (preflightImages.length) progress('正在检查前 3 张攻略配图是否能安全读取');
      for (const image of preflightImages) {
        checkCancelled(signal);
        try { await cancellable(media.get(image.id), signal); imagesAvailable++; }
        catch (error) { checkCancelled(signal); failedImages.add(image.id); }
      }
      if (failedImages.size) warnings.push(`${failedImages.size} 张攻略配图本次无法读取，相关候选保留无图状态。`);
      const imagesFound = sources.reduce((total, source) => total + source.images.length, 0);
      if (imagesFound > registrations.size) warnings.push('部分攻略图片地址未通过安全检查或超过图片存储数量限制，未提供展示链接。');
      if (registrations.size > preflightImages.length) warnings.push('本轮预读取最多 3 张攻略配图，其余图片在展示时按需读取。');
      const fetchedAt = new Date().toISOString();
      progress(`已整理 ${candidates.length} 个有来源的候选，请先挑选想去的地点`);
      return {
        id: `research-${randomUUID()}`, city: destination, destination, fetchedAt,
        sources: sources.map((source) => ({
          id: source.id, title: safeText(source.title, 300), author: safeText(source.author, 100),
          date: source.publishedAt?.slice(0, 10) ?? null, url: source.url,
          bodyExcerpt: safeText(source.body, 2000), imageCount: source.images.length,
          images: source.images.map((image) => registrations.get(`${source.id}:${image.index}`))
            .filter((image) => image && !failedImages.has(image.id)).map((image) => ({ ...image, caption: '攻略配图' })),
        })),
        candidates: candidates.map((candidate) => {
          const verified = candidate.verification.status === 'verified';
          const image = selectedImages.get(candidate.id);
          const safeImage = image && !failedImages.has(image.id) ? { ...image, caption: '攻略配图，非地点实拍确认' } : null;
          return {
            id: candidate.id, title: safeText(candidate.name, 120), name: safeText(candidate.name, 120),
            category: CATEGORY[candidate.category], region: safeText(candidate.city, 80),
            description: safeText(candidate.sourceRefs[0].evidenceText, 300), duration: candidate.suggestedDurationMinutes,
            sourceIds: [...new Set(candidate.sourceRefs.map((ref) => ref.sourceId))],
            sourceRefs: candidate.sourceRefs.map((ref) => ({ ...ref })), kind: candidate.category === 'food' ? 'meal' : 'visit',
            lng: verified ? candidate.verification.location.longitude : null,
            lat: verified ? candidate.verification.location.latitude : null,
            locationStatus: verified ? 'matched' : 'pending', verification: candidate.verification,
            image: safeImage, photo: null, notes: safeText(candidate.notes, 1000),
          };
        }), warnings,
        stats: {
          sourceCount: sources.length, candidateCount: candidates.length, excludedCandidateCount,
          outOfCityCount, searchQueries: topics.length, searchFailures, topicFallbackCount,
          categoryCounts: Object.fromEntries(['sights', 'food', 'experiences'].map((category) =>
            [category, candidates.filter((candidate) => CATEGORY[candidate.category] === category).length])),
          imagesFound,
          imagesRegistered: registrations.size, imagesAvailable,
          imagesRead: Math.min(3, boundedCounter(result?.stats?.imagesRead, 3),
            evidenceSources.reduce((total, source) => total + source.imageEvidence.length, 0)),
          imagesAttempted: boundedCounter(result?.stats?.imagesAttempted, 3),
          aiRequests: boundedCounter(result?.stats?.aiRequests, 4), rejectedCandidates: rejected,
          mapRequests, matchedPlaceCount,
          usage: Object.fromEntries(['prompt_tokens', 'completion_tokens', 'total_tokens'].map((key) =>
            [key, boundedCounter(result?.usage?.[key], 10_000_000)])),
        }, status: { retrieval: 'live', ai: 'success', map: mapStatus },
      };
    } catch (error) {
      checkCancelled(signal);
      if (error instanceof ResearchEngineError) throw error;
      throw new ResearchEngineError('RESEARCH_FAILED');
    }
  }
  return { checkConnection, research };
}
