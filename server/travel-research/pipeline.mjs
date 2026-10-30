import { extractFromBodies } from './body-candidates.mjs';
import { ResearchDataError, validateExtraction, mergeCandidates } from './contract.mjs';
import { buildDraft, reconcileDraftTimes, isVerifiedCandidate } from './planner.mjs';
import { createAmap } from './maps.mjs';
import { extractWithDeepSeek } from './ai-provider.mjs';

export function validateResearchInput({ destination = '杭州', dayCount = 3, pace = 'normal', transport = '公共交通', maxCandidates = 12 } = {}) {
  if (typeof destination !== 'string' || !destination.trim() || destination.length > 200 || /[\u0000-\u001f\u007f]/u.test(destination) ||
      !Number.isInteger(dayCount) || dayCount < 1 || dayCount > 14 || !['relaxed', 'normal'].includes(pace) ||
      !['公共交通', '步行', '自驾'].includes(transport) || !Number.isInteger(maxCandidates) || maxCandidates < 1 || maxCandidates > 20) {
    throw new ResearchDataError('INVALID_RESEARCH_INPUT', '请检查目的地、1～14 天天数、节奏、交通方式和候选数量。');
  }
  return { destination: destination.trim(), dayCount, pace, transport, maxCandidates };
}

const IMAGE_WARNING_CODES = new Set(['IMAGE_URL_REJECTED', 'IMAGE_UNAVAILABLE', 'IMAGE_TIMEOUT', 'IMAGE_FORMAT_REJECTED',
  'IMAGE_TOO_LARGE', 'IMAGE_RECOGNITION_INVALID', 'IMAGE_NO_TEXT', 'AI_TIMEOUT', 'AI_UNAVAILABLE', 'AI_INVALID_RESPONSE',
  'AI_RESPONSE_TOO_LARGE', 'ABORTED']);
function safeAiStats(raw, usage, sources) {
  if (!raw || typeof raw !== 'object') return null;
  const result = {};
  for (const name of ['imagesAttempted', 'imagesDownloaded', 'imagesRead', 'imageBytesDownloaded', 'aiRequests', 'rejectedCandidates', 'candidatesProposed', 'candidatesAccepted']) {
    if (Number.isSafeInteger(raw[name]) && raw[name] >= 0) result[name] = raw[name];
  }
  const knownSources = new Map(sources.map((source) => [source.id, source]));
  result.imageWarnings = (Array.isArray(raw.imageWarnings) ? raw.imageWarnings : []).slice(0, 50).map((item) => {
    const source = knownSources.get(item?.sourceId);
    const imageIndex = source && Number.isInteger(item?.imageIndex) && item.imageIndex >= 1 && item.imageIndex <= source.images.length ? item.imageIndex : null;
    return { sourceId: source?.id ?? null, imageIndex,
      code: IMAGE_WARNING_CODES.has(item?.code) ? item.code : 'IMAGE_UNAVAILABLE', message: '图片本次未能完成解析。' };
  });
  result.usage = {};
  for (const name of ['prompt_tokens', 'completion_tokens', 'total_tokens']) {
    if (Number.isSafeInteger(usage?.[name]) && usage[name] >= 0) result.usage[name] = usage[name];
  }
  return result;
}
const imageWarningText = (item) => `${item.sourceId && item.imageIndex ? `${item.sourceId} 第 ${item.imageIndex} 张图片` : '部分攻略图片'}：解析未完成（${item.code}）。`;
const safeMapCode = (error) => /^(?:AMAP_\d{5}|MAP_(?:UNAVAILABLE|NOT_CONFIGURED))$/u.test(error?.code ?? '') ? error.code : 'MAP_UNAVAILABLE';

function normalizedLeg(record, from, to, transport) {
  const valid = record?.fromCandidateId === from.id && record?.toCandidateId === to.id && record.status === 'verified' &&
    Number.isFinite(record.durationMinutes) && record.durationMinutes > 0 && record.durationMinutes <= 1440;
  return { fromCandidateId: from.id, toCandidateId: to.id, durationMinutes: valid ? Math.ceil(record.durationMinutes) : null,
    mode: ['步行','公共交通','自驾'].includes(record?.mode)?record.mode:transport, status: valid ? 'verified' : 'unavailable', provider: 'amap',
    checkedAt: new Date().toISOString(), scope: `${record?.mode==='步行'&&transport==='公共交通'?'此段未返回公交方案，已查询步行方案。':''}接口查询时的估计耗时；不代表未来出行当天保证，景区内移动与排队未计入` };
}

export async function researchTravel({ sources, destination = '杭州', dayCount = 3, pace = 'normal', transport = '公共交通',
  config = {}, maxCandidates = 12, onProgress = () => {}, onAiCheckpoint, savedExtraction, aiImpl = extractWithDeepSeek, mapsImpl } = {}) {
  const input = validateResearchInput({ destination, dayCount, pace, transport, maxCandidates });
  destination = input.destination;
  if (!Array.isArray(sources) || !sources.length || sources.length > 8 || sources.some((source) => !source ||
      typeof source.body !== 'string' || source.body.length > 30_000 || !Array.isArray(source.images) || source.images.length > 50)) {
    throw new ResearchDataError('INVALID_RESEARCH_INPUT', '请提供 1～8 篇结构有效的攻略来源。');
  }
  validateExtraction({ candidates: [] }, { sources });
  const warnings = [];
  const statuses = { retrieval: 'success', ai: 'not_configured', map: 'not_configured', routes: 'not_configured', draft: 'blocked' };
  let extraction; let aiStats = null;
  if(savedExtraction) {
    extraction=validateExtraction(savedExtraction.raw,{sources});
    if(savedExtraction.method==='deepseek') statuses.ai='success';
    aiStats=safeAiStats(savedExtraction.stats,savedExtraction.stats?.usage,sources);
    warnings.push('复用本机此前的提取结果，仅重新查询地图；未再次调用模型或重新识图。');
  } else if (config.aiKey) {
    onProgress('正在解析正文和选定的攻略图片');
    try {
      const result = await aiImpl({ sources, destination, apiKey: config.aiKey, model: config.model, maxImages: 3, onCheckpoint:onAiCheckpoint });
      aiStats = safeAiStats(result.stats, result.usage, sources);
      const validated = validateExtraction(result.extraction, { sources: result.sources });
      sources = result.sources; extraction = validated; statuses.ai = 'success';
      if (aiStats?.rejectedCandidates) warnings.push(`AI 输出中有 ${aiStats.rejectedCandidates} 项候选未通过来源或格式校验，已剔除；仅保留有依据的地点。`);
    } catch (error) {
      aiStats = safeAiStats(error?.stats, error?.usage, sources) ?? aiStats;
      statuses.ai = 'failed'; warnings.push('AI 解析本次未完成；当前候选来自正文规则匹配，未冒充 AI 结果。');
    }
    warnings.push(...(aiStats?.imageWarnings ?? []).map(imageWarningText));
  }
  if (!extraction) {
    extraction = validateExtraction(extractFromBodies(sources, destination), { sources });
    if (!config.aiKey) warnings.push('未配置 DeepSeek API Key：只运行杭州样例的正文关键词提取，图片文字尚未解析。');
  }
  const categoryPriority={attraction:0,food:1,area:2,other:3};
  let candidates = mergeCandidates(extraction.candidates).sort((a, b) => categoryPriority[a.category]-categoryPriority[b.category]||b.sourceRefs.length-a.sourceRefs.length);
  const excludedCandidateCount = Math.max(0, candidates.length - maxCandidates);
  candidates = candidates.slice(0, maxCandidates);
  if (excludedCandidateCount) warnings.push(`为限制首轮调用量，本次只处理前 ${maxCandidates} 个候选；另有 ${excludedCandidateCount} 个尚未核验。`);
  const maps = mapsImpl ?? createAmap({ apiKey: config.amapKey });
  let mapServiceUnavailable = false;
  if (config.amapKey) {
    statuses.map = candidates.length ? 'success' : 'partial'; statuses.routes = 'partial';
    for (let index = 0; index < candidates.length; index++) {
      if (mapServiceUnavailable) {
        candidates[index].verification = { status: 'unavailable', reason: '地图接口暂时不可用，未继续消耗调用。' }; continue;
      }
      onProgress(`正在匹配地点 ${index + 1}/${candidates.length}`);
      try {
        const matched = await maps.verify(candidates[index]);
        candidates[index] = { ...candidates[index], verification: matched?.verification ?? { status: 'unavailable', reason: '地图结果不完整' } };
        if (!isVerifiedCandidate(candidates[index])) statuses.map = 'partial';
      } catch (error) {
        statuses.map = 'partial'; mapServiceUnavailable = true;
        candidates[index].verification = { status: 'unavailable', reason: '地图查询未完成' };
        warnings.push(`地图核验未完成（${safeMapCode(error)}）；没有将失败当作匹配成功。`);
      }
    }
  } else {
    candidates = candidates.map((candidate) => ({ ...candidate, verification: { status: 'unverified', reason: '未配置高德 Web 服务 Key，地点坐标尚未匹配。' } }));
    warnings.push('未配置高德 Web 服务 Key：没有核验地点和交通耗时，候选不会自动填入三天日程。');
  }
  const initialDraft = buildDraft({ destination, dayCount, pace, candidates });
  const legs = []; let queriedLegCount = 0;
  if (config.amapKey && !mapServiceUnavailable && initialDraft.days.some((day) => day.stops.length)) {
    const byId = new Map(candidates.map((candidate) => [candidate.id, candidate]));
    statuses.routes = 'success';
    for (const day of initialDraft.days) for (let index = 1; index < day.stops.length; index++) {
      const from = byId.get(day.stops[index - 1].candidateId), to = byId.get(day.stops[index].candidateId);
      onProgress(`正在查询第 ${day.day} 天的相邻地点交通`); queriedLegCount++;
      let record = null;
      try { record = await maps.leg(from, to, transport); }
      catch { /* Raw provider errors can contain request URLs or credentials. */ }
      const leg = normalizedLeg(record, from, to, transport); legs.push(leg);
      if (leg.status !== 'verified') {
        statuses.routes = 'partial'; warnings.push(`第 ${day.day} 天一段交通本次未查到，保留未知与建议缓冲。`);
      }
    }
  }
  const draft = reconcileDraftTimes(initialDraft, { candidates, legs, dayCount, pace });
  const hasStops = draft.days.some((day) => day.stops.length);
  const incomplete = statuses.map !== 'success' || statuses.routes !== 'success' ||
    draft.days.some((day) => !day.stops.length) || draft.reconciliation.overflowCount > 0 || !draft.validation.valid;
  statuses.draft = !hasStops ? 'blocked' : incomplete ? 'partial' : 'ready';
  return { version: 1, destination, dayCount, pace, transport, generatedAt: new Date().toISOString(), statuses,
    extractionMethod: statuses.ai === 'success' ? 'deepseek' : 'body_rules', sources, candidates, legs, draft, warnings,
    stats: { sourceCount: sources.length, candidateCount: candidates.length, excludedCandidateCount,
      matchedPlaceCount: candidates.filter(isVerifiedCandidate).length, queriedLegCount,
      availableLegCount: legs.filter((leg) => leg.status === 'verified').length, ai: aiStats } };
}
