import { createHash } from 'node:crypto';
const text = (value, maximum = 2000) => typeof value === 'string' ? value.slice(0, maximum) : '';
const safeUrl = (value) => {
  try { const url = new URL(value); return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password ? url.href : ''; }
  catch { return ''; }
};
const refs = (value) => Array.isArray(value) ? value.slice(0, 40).map((ref) => ({
  sourceId: text(ref?.sourceId, 80), evidenceType: ref?.evidenceType === 'image' ? 'image' : 'body',
  ...(Number.isInteger(ref?.imageIndex) ? { imageIndex: ref.imageIndex } : {}), evidenceText: text(ref?.evidenceText, 100),
})) : [];
const location = (value) => value && typeof value === 'object' ? { longitude: value.longitude, latitude: value.latitude } : null;

// Only public report fields enter the artifact. Full bodies, image URLs,
// provider payloads and configuration are deliberately omitted.
export function publicReport(report = {}) {
  const dayCount = Number.isInteger(report.dayCount) ? Math.min(14, Math.max(1, report.dayCount)) : 3;
  const sources = (report.sources ?? []).slice(0, 30).map((source) => ({
    id: text(source.id ?? source.sourceId, 80), postId: text(source.postId, 80),
    title: text(source.title, 300), url: safeUrl(source.url ?? source.canonicalUrl),
    publishedAt: text(source.publishedAt, 100), fetchedAt: text(source.fetchedAt, 100),
    bodyCharacters: typeof source.body === 'string' ? source.body.length : Number(source.bodyCharacters) || 0,
    imageCount: Array.isArray(source.images) ? source.images.length : Number(source.imageCount) || 0,
    imagesParsed: Array.isArray(source.imageEvidence) ? source.imageEvidence.length : 0,
  }));
  const candidates = (report.candidates ?? []).slice(0, 80).map((candidate) => ({
    id: text(candidate.id, 100), name: text(candidate.name, 160), city: text(candidate.city, 100), category: text(candidate.category, 40),
    suggestedDurationMinutes: candidate.suggestedDurationMinutes, sourceRefs: refs(candidate.sourceRefs), notes: text(candidate.notes, 1000),
    verification: { status: text(candidate.verification?.status ?? candidate.verificationStatus ?? 'unverified', 60),
      poiId: text(candidate.verification?.poiId ?? candidate.poiId, 100), location: location(candidate.verification?.location ?? candidate.coordinates),
      address: text(candidate.verification?.address, 400), reason: text(candidate.verification?.reason, 400), checkedAt: text(candidate.verification?.checkedAt, 100) },
  }));
  const days = Array.from({ length: dayCount }, (_, index) => {
    const original = report.draft?.days?.find((day) => day.day === index + 1);
    return { day: index + 1, stops: (original?.stops ?? []).slice(0, 12).map((stop) => ({
      candidateId: text(stop.candidateId, 100), title: text(stop.title, 160), startTime: text(stop.startTime, 20), durationMinutes: stop.durationMinutes,
      travelFromPreviousMinutes: Number.isFinite(stop.travelFromPreviousMinutes) ? stop.travelFromPreviousMinutes : null,
      planningBufferMinutes: Number.isFinite(stop.planningBufferMinutes) ? stop.planningBufferMinutes : 0,
      sourceRefs: refs(stop.sourceRefs), notes: text(stop.notes, 1000), verificationStatus: text(stop.verificationStatus, 60), origin: text(stop.origin, 80),
    })) };
  });
  return { version: 1, destination: text(report.destination, 160), dayCount, generatedAt: text(report.generatedAt, 100),
    statuses: Object.fromEntries(['retrieval', 'ai', 'map', 'draft'].map((key) => {
      let value = report.statuses?.[key];
      if(key==='retrieval'&&(report.provenance?.sources==='saved_mcp_capture'||report.retrieval?.mode==='saved_real_mcp_capture')) value='cached';
      if(key==='ai'&&report.provenance?.extraction==='saved_validated_extraction'&&value==='success') value='cached';
      return [key, text(typeof value === 'object' ? value?.status ?? value?.state : value, 100)];
    })), sources, candidates,
    legs: (report.legs ?? []).slice(0, 1000).map((leg) => ({ fromCandidateId: text(leg.fromCandidateId, 100), toCandidateId: text(leg.toCandidateId, 100), status: text(leg.status, 60), durationMinutes: Number.isFinite(leg.durationMinutes) ? leg.durationMinutes : null, mode: text(leg.mode, 60), checkedAt: text(leg.checkedAt, 100) })),
    draft: { days, warnings: (report.draft?.warnings ?? []).map((item) => text(item, 500)),
      alternatives: candidates.filter((candidate) => !days.some((day) => day.stops.some((stop) => stop.candidateId === candidate.id))).map((candidate) => candidate.id) },
    warnings: (report.warnings ?? []).map((item) => text(item, 500)) };
}
export function validateEditorDraft(draft, { candidates, sources, legs = [] }) {
  const ids = new Set(candidates.map((candidate) => candidate.id));
  const sourceIds = new Set(sources.map((source) => source.id));
  const parseTime = (time) => /^(?:[01]\d|2[0-3]):[0-5]\d$/u.test(time ?? '') ? Number(time.slice(0, 2)) * 60 + Number(time.slice(3)) : NaN;
  const issues = [];
  for (const day of draft.days) {
    const used = new Set(); const intervals = [];
    for (const stop of day.stops) {
      const label = '第 ' + day.day + ' 天 · ' + (stop.title || '未命名地点');
      if (!ids.has(stop.candidateId)) issues.push(label + '：候选地点引用缺失。');
      if (used.has(stop.candidateId)) issues.push(label + '：同一天重复加入了这个地点。');
      used.add(stop.candidateId);
      if (!stop.sourceRefs?.length || stop.sourceRefs.some((ref) => !sourceIds.has(ref.sourceId))) issues.push(label + '：来源引用不完整。');
      const start = parseTime(stop.startTime); const duration = stop.durationMinutes;
      if (!Number.isFinite(start)) issues.push(label + '：请填写有效的开始时间。');
      if (!Number.isInteger(duration) || duration < 1 || duration > 720) issues.push(label + '：停留时长须为 1～720 分钟的整数。');
      if (Number.isFinite(start) && Number.isInteger(duration) && duration >= 1) {
        if (start + duration > 1440) issues.push(label + '：活动结束时间超过当天。');
        intervals.push({ start, end: start + duration, title: stop.title, candidateId: stop.candidateId, planningBufferMinutes: stop.planningBufferMinutes });
      }
    }
    intervals.sort((left, right) => left.start - right.start);
    let previous;
    for (const interval of intervals) {
      if (previous && interval.start < previous.end) issues.push('第 ' + day.day + ' 天：“' + previous.title + '”与“' + interval.title + '”时间重叠。');
      if (!previous || interval.end > previous.end) previous = interval;
    }
    for (let index = 1; index < intervals.length; index++) {
      const previous = intervals[index - 1]; const current = intervals[index];
      const leg = legs.find((item) => item.fromCandidateId === previous.candidateId && item.toCandidateId === current.candidateId && item.status === 'verified' && Number.isFinite(item.durationMinutes) && item.durationMinutes >= 0);
      const required = leg ? Math.ceil(leg.durationMinutes) : Number.isFinite(current.planningBufferMinutes) ? current.planningBufferMinutes : 30;
      if (current.start >= previous.end && current.start - previous.end < required) issues.push('第 ' + day.day + ' 天：“' + previous.title + '”到“' + current.title + '”的间隔不足' + (leg ? '地图估算的 ' : '建议缓冲的 ') + required + ' 分钟。');
    }
    for (let index = 1; index < day.stops.length; index++) if (parseTime(day.stops[index].startTime) < parseTime(day.stops[index - 1].startTime)) { issues.push('第 ' + day.day + ' 天：请按开始时间排序后再导出。'); break; }
  }
  return issues;
}
const serialize = (value) => JSON.stringify(value).replace(/[<>&\u2028\u2029]/gu,
  (char) => ({ '<': '\\u003c', '>': '\\u003e', '&': '\\u0026', '\u2028': '\\u2028', '\u2029': '\\u2029' }[char]));

function client() {
  function localDate(value, withTime = false) {
    const date = new Date(value);
    if (!value || !Number.isFinite(date.getTime())) return '日期未记录';
    return new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
      ...(withTime ? { hour: '2-digit', minute: '2-digit', hour12: false } : {}) }).format(date);
  }
  'use strict';
  const data = JSON.parse(document.querySelector('#report-data').textContent);
  const initial = JSON.parse(JSON.stringify(data.draft));
  const state = { day: 1, filter: 'all', edited: false, draft: data.draft };
  const byId = new Map(data.candidates.map((candidate) => [candidate.id, candidate]));
  const sourceById = new Map(data.sources.map((source) => [source.id, source]));
  const $ = (selector) => document.querySelector(selector);
  const make = (tag, className, value) => { const element = document.createElement(tag); if (className) element.className = className; if (value !== undefined) element.textContent = String(value); return element; };
  const say = (message) => { $('#feedback').textContent = message; };
  const minutes = (time) => /^(?:[01]\d|2[0-3]):[0-5]\d$/u.test(time ?? '') ? Number(time.slice(0, 2)) * 60 + Number(time.slice(3)) : NaN;
  const clock = (value) => String(Math.floor(value / 60)).padStart(2, '0') + ':' + String(value % 60).padStart(2, '0');
  const coordinate = (value) => value && Number.isFinite(value.longitude) && Number.isFinite(value.latitude) && Math.abs(value.longitude) <= 180 && Math.abs(value.latitude) <= 90;
  const isMatched = (candidate) => candidate?.verification?.status === 'verified' && candidate.verification.poiId && coordinate(candidate.verification.location);
  const reason = (candidate) => isMatched(candidate) ? '地点已匹配' : ({ ambiguous: '存在同名地点', not_found: '未找到对应地点', unavailable: '地图暂不可用' }[candidate.verification?.status] ?? '待地图核验');
  const category = (value) => ({ attraction: '景点', food: '餐饮', area: '街区 / 区域', other: '其他' }[value] ?? '地点');
  const sourceLink = (ref) => {
    const source = sourceById.get(ref.sourceId);
    if (!source) return make('span', 'reference missing', '来源缺失');
    const link = make('a', 'reference', ref.sourceId + (ref.evidenceType === 'image' ? ' · 图 ' + ref.imageIndex : ' · 正文')); link.href = '#source-' + encodeURIComponent(source.id); return link;
  };
  const sourceList = (references) => { const list = make('div', 'references'); (references ?? []).forEach((ref) => list.append(sourceLink(ref))); return list; };
  const statusLabel = (value) => /not_configured|unconfigured|missing|no_key/u.test(value) ? '待配置' : ({ success: '已完成', complete: '已完成', completed: '已完成', ready: '已完成', live: '真实调用完成', cached: '使用已验证样本', rules: '正文规则提取', body_rules: '正文规则提取', rule_based: '正文规则提取', blocked: '待核验', pending: '待完成', failed: '未完成', unavailable: '暂不可用', skipped: '未执行', partial: '部分完成', disabled: '未启用', not_run: '未执行' }[value] ?? (value || '未记录'));
  const validate = () => validateEditorDraft(state.draft, data);
  function renderValidation() {
    const issues = validate(); const total = state.draft.days.reduce((sum, day) => sum + day.stops.length, 0);
    const box = $('#validation'); box.replaceChildren(); box.hidden = issues.length === 0;
    if (issues.length) { box.append(make('strong', '', '请先修正以下安排')); const list = make('ul'); issues.forEach((issue) => list.append(make('li', '', issue))); box.append(list); }
    $('#export').disabled = total === 0 || issues.length > 0;
    $('#reset').disabled = !state.edited;
    $('#export-help').textContent = issues.length ? '修正时间或引用问题后即可导出。' : total === 0 ? '先从候选地点加入一项，再导出手动草稿。' : '可导出 ' + total + ' 项安排；待核验状态与来源会一并保留。';
    $('#edit-state').textContent = state.edited ? '已在本页修改' : '本地样例 · 可编辑';
  }
  const changed = (message) => { state.edited = true; renderValidation(); if (message) say(message); };
  const invalidateTravel = (day) => day.stops.forEach((stop, index) => { stop.travelFromPreviousMinutes = null; stop.planningBufferMinutes = index === 0 ? 0 : 30; });
  const removeStop = (day, index) => { const title = day.stops[index].title; day.stops.splice(index, 1); invalidateTravel(day); changed('已从第 ' + day.day + ' 天移除“' + title + '”。'); renderDay(); renderCandidates(); };
  function editField(label, kind, value, onInput, attributes = {}) {
    const wrapper = make('label', 'field'); wrapper.append(make('span', 'field-label', label));
    const control = make(kind === 'textarea' ? 'textarea' : 'input'); if (kind !== 'textarea') control.type = kind;
    for (const [key, item] of Object.entries(attributes)) control.setAttribute(key, String(item));
    control.value = value ?? ''; control.addEventListener('input', () => { onInput(control.value); changed(); }); wrapper.append(control); return wrapper;
  }
  function renderDay() {
    const day = state.draft.days.find((item) => item.day === state.day);
    document.querySelectorAll('[data-day]').forEach((button) => { const selected = Number(button.dataset.day) === state.day; button.setAttribute('aria-selected', String(selected)); button.tabIndex = selected ? 0 : -1; });
    $('#day-heading').textContent = '第 ' + state.day + ' 天'; $('#day-count').textContent = day.stops.length ? day.stops.length + ' 项安排' : '尚未加入地点'; $('#sort').disabled = day.stops.length < 2;
    const list = $('#stops'); list.replaceChildren();
    if (!day.stops.length) {
      const empty = make('div', 'empty'); empty.append(make('h3', '', '从一个想去的地方开始'), make('p', '', '这一天还没有安排。可从候选清单选择地点，手动编排；待核验状态会保留。'));
      const jump = make('a', 'text-action', '查看候选地点'); jump.href = '#candidates'; empty.append(jump); list.append(empty);
    }
    day.stops.forEach((stop, index) => {
      const candidate = byId.get(stop.candidateId); const row = make('article', 'stop'); const top = make('div', 'stop-heading');
      const names = make('div'); names.append(make('h3', '', stop.title || candidate?.name || '未知地点'));
      names.append(make('span', 'tag ' + (isMatched(candidate) ? 'matched' : 'pending'), isMatched(candidate) ? '地点已匹配 · 交通仍需核实' : '手动草稿 · 地点与交通待核验'));
      const remove = make('button', 'quiet-button', '移除'); remove.type = 'button'; remove.setAttribute('aria-label', '移除' + (stop.title || '这个地点')); remove.addEventListener('click', () => removeStop(day, index)); top.append(names, remove); row.append(top);
      const inputs = make('div', 'time-fields');
      inputs.append(editField('开始时间', 'time', stop.startTime, (value) => { stop.startTime = value; }, { required: true }));
      inputs.append(editField('停留时长 / 分钟', 'number', stop.durationMinutes, (value) => { stop.durationMinutes = value === '' ? null : Number(value); }, { min: 1, max: 720, step: 1, required: true }));
      row.append(inputs, editField('备注', 'textarea', stop.notes, (value) => { stop.notes = value; }, { rows: 2, maxlength: 1000, placeholder: '记录想做的事，或需要核实的信息' }), sourceList(stop.sourceRefs));
      if (index > 0) row.append(make('p', 'travel-note', Number.isFinite(stop.travelFromPreviousMinutes) ? '上一地点至此的交通估算：' + stop.travelFromPreviousMinutes + ' 分钟；以查询时信息为准。' : '上一地点至此的交通用时待核验；预留 ' + (stop.planningBufferMinutes || 30) + ' 分钟仅为建议缓冲。'));
      list.append(row);
    }); renderValidation();
  }
  function addCandidate(candidate) {
    const day = state.draft.days.find((item) => item.day === state.day);
    if (day.stops.some((stop) => stop.candidateId === candidate.id)) return;
    if (day.stops.length >= 12) { say('一天最多安排 12 项，请先调整已有地点。'); return; }
    const raw = Number(candidate.suggestedDurationMinutes); const duration = Number.isInteger(raw) && raw > 0 && raw <= 720 ? raw : 90;
    const occupied = day.stops.map((stop) => ({ start: minutes(stop.startTime), end: minutes(stop.startTime) + Number(stop.durationMinutes) })).filter((slot) => Number.isFinite(slot.start) && Number.isFinite(slot.end)).sort((left, right) => left.start - right.start);
    let start = 9 * 60;
    for (const slot of occupied) { if (start + duration + 30 <= slot.start) break; if (start < slot.end + 30) start = slot.end + 30; }
    if (start + duration > 1440) { say('当天已没有足够时间，请换一天或缩短已有安排。'); return; }
    day.stops.push({ candidateId: candidate.id, title: candidate.name, startTime: clock(start), durationMinutes: duration, travelFromPreviousMinutes: null, planningBufferMinutes: day.stops.length ? 30 : 0, sourceRefs: JSON.parse(JSON.stringify(candidate.sourceRefs)), notes: candidate.notes || '', verificationStatus: isMatched(candidate) ? 'verified' : 'unverified', origin: 'manual' });
    day.stops.sort((left, right) => minutes(left.startTime) - minutes(right.startTime));
    invalidateTravel(day); changed('已把“' + candidate.name + '”加入第 ' + state.day + ' 天，时间可继续调整。'); renderDay(); renderCandidates();
  }
  function renderCandidates() {
    const holder = $('#candidate-list'); holder.replaceChildren(); const day = state.draft.days.find((item) => item.day === state.day);
    const candidates = data.candidates.filter((candidate) => state.filter === 'all' || (state.filter === 'matched' ? isMatched(candidate) : !isMatched(candidate)));
    $('#candidate-count').textContent = data.candidates.length + ' 个地点'; document.querySelectorAll('[data-filter]').forEach((button) => button.setAttribute('aria-pressed', String(button.dataset.filter === state.filter)));
    if (!candidates.length) holder.append(make('p', 'candidate-empty', state.filter === 'matched' ? '尚无通过地图匹配的地点，切换“待核验”查看候选。' : '暂无候选地点；请完成攻略解析后重新生成样例。'));
    candidates.forEach((candidate) => {
      const item = make('article', 'candidate'); const heading = make('div', 'candidate-heading'); heading.append(make('h3', '', candidate.name), make('span', 'tag ' + (isMatched(candidate) ? 'matched' : 'pending'), reason(candidate)));
      item.append(heading, make('p', 'candidate-meta', [candidate.city, category(candidate.category), '建议停留 ' + (candidate.suggestedDurationMinutes || 90) + ' 分钟'].filter(Boolean).join(' · ')));
      if (candidate.verification?.address) item.append(make('p', 'candidate-address', candidate.verification.address));
      if (candidate.verification?.reason) item.append(make('p', 'candidate-reason', candidate.verification.reason));
      if (candidate.notes) item.append(make('p', 'candidate-note', candidate.notes));
      const evidence = candidate.sourceRefs.find((ref) => ref.evidenceText); if (evidence) item.append(make('blockquote', 'evidence', evidence.evidenceText));
      const bottom = make('div', 'candidate-actions'); bottom.append(sourceList(candidate.sourceRefs));
      const already = day.stops.some((stop) => stop.candidateId === candidate.id); const add = make('button', 'add-button', already ? '已加入当天' : '加入第 ' + state.day + ' 天'); add.type = 'button'; add.setAttribute('aria-label', already ? candidate.name + '已加入当天' : '将' + candidate.name + '加入第 ' + state.day + ' 天'); add.disabled = already || day.stops.length >= 12; add.addEventListener('click', () => addCandidate(candidate)); bottom.append(add); item.append(bottom); holder.append(item);
    });
  }
  function renderSources() {
    const list = $('#source-list'); if (!data.sources.length) list.append(make('p', 'candidate-empty', '没有可追溯的攻略来源。'));
    data.sources.forEach((source) => {
      const article = make('article', 'source'); article.id = 'source-' + encodeURIComponent(source.id); article.tabIndex = -1;
      const heading = make('div', 'source-heading'); const info = make('div'); info.append(make('span', 'source-id', source.id), make('h3', '', source.title || '未命名攻略')); heading.append(info);
      if (source.url) { const link = make('a', 'text-action', '打开原文'); link.href = source.url; link.target = '_blank'; link.rel = 'noopener noreferrer'; link.referrerPolicy = 'no-referrer'; heading.append(link); }
      else heading.append(make('span', 'source-unavailable', '原文链接不可用'));
      article.append(heading, make('p', 'source-meta', [source.publishedAt ? '发布于 ' + localDate(source.publishedAt) : '发布日期未记录', source.bodyCharacters + ' 个正文字符', source.imageCount + ' 张图片', source.imagesParsed ? '已识别 ' + source.imagesParsed + ' 张图片' : '图片尚未解析'].join(' · ')));
      const evidence = data.candidates.flatMap((candidate) => candidate.sourceRefs).find((ref) => ref.sourceId === source.id && ref.evidenceText); if (evidence) article.append(make('p', 'source-excerpt', evidence.evidenceText)); list.append(article);
    });
  }
  function exportDraft() {
    const issues = validate(); if (issues.length) { renderValidation(); say('尚有时间或引用问题，请先修正。'); $('#validation').focus(); return; }
    if (!state.draft.days.some((day) => day.stops.length)) { say('请先加入一个地点。'); return; }
    const candidateIds = new Set(state.draft.days.flatMap((day) => day.stops.map((stop) => stop.candidateId))); const output = JSON.parse(JSON.stringify(data)); output.draft = JSON.parse(JSON.stringify(state.draft));
    output.draft.alternatives = data.candidates.filter((candidate) => !candidateIds.has(candidate.id)).map((candidate) => candidate.id);
    output.draft.validation = { valid: true, issues: [], scope: '本页检查了引用、有效时段、重复地点、时间重叠及交通估算或建议缓冲；未核验营业、预约或实际交通可行性。' };
    output.draft.days.forEach((day) => day.stops.forEach((stop) => { stop.verificationStatus = isMatched(byId.get(stop.candidateId)) ? 'verified' : 'unverified'; }));
    output.editor = { editedAt: new Date().toISOString(), manuallyEdited: state.edited, savedToApp: false };
    const blob = new Blob([JSON.stringify(output, null, 2)], { type: 'application/json;charset=utf-8' }); const url = URL.createObjectURL(blob); const anchor = make('a'); anchor.href = url; anchor.download = 'MY_PLAN-' + (data.destination || '行程').replace(/[\\/:*?"<>|]/gu, '-') + '-草稿.json'; document.body.append(anchor); anchor.click(); anchor.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000); say('已导出 JSON 草稿，来源与待核验标记已保留。');
  }
  function init() {
    const words = ['', '一', '二', '三', '四', '五', '六', '七', '八', '九', '十', '十一', '十二', '十三', '十四']; const title = (data.destination || '旅行') + (words[data.dayCount] || data.dayCount) + '天游'; document.title = title + ' · MY PLAN 本地样例'; $('#trip-title').textContent = title; $('#subtitle').textContent = '攻略来源、地点核验和日程草稿，放在一起检查。';
    for (const [key, name] of [['retrieval', '攻略检索'], ['ai', '内容解析'], ['map', '地点匹配'], ['draft', '日程草稿']]) { const row = make('div', 'stage'); row.append(make('dt', '', name), make('dd', '', statusLabel(data.statuses[key]))); $('#stages').append(row); }
    const notice = [];
    if (/not_configured|unconfigured|missing|rules|rule_based|skipped|not_run/u.test(data.statuses.ai)) notice.push('候选来自正文规则提取，真实 AI 解析与图片识别尚未完成。');
    if (/not_configured|unconfigured|missing|skipped|not_run/u.test(data.statuses.map)) notice.push('地图服务尚未配置，地点和交通仍待核验。');
    if (!data.candidates.some(isMatched)) notice.push('当前没有可自动排入的已匹配地点；可以手动编排草稿，待核验标记会保留。');
    if (notice.length) { $('#notice').hidden = false; $('#notice').textContent = notice.join(' '); }
    const warnings = [...new Set([...(data.warnings ?? []), ...(data.draft.warnings ?? [])])]; if (warnings.length) { $('#warnings-wrap').hidden = false; warnings.forEach((warning) => $('#warnings').append(make('li', '', warning))); }
    for (let day = 1; day <= data.dayCount; day++) {
      const button = make('button', 'day-tab', '第 ' + day + ' 天'); button.type = 'button'; button.id = 'day-tab-' + day; button.dataset.day = day; button.setAttribute('role', 'tab'); button.setAttribute('aria-controls', 'stops'); button.addEventListener('click', () => { state.day = day; renderDay(); renderCandidates(); });
      button.addEventListener('keydown', (event) => { if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return; event.preventDefault(); state.day = event.key === 'Home' ? 1 : event.key === 'End' ? data.dayCount : ((state.day - 1 + (event.key === 'ArrowRight' ? 1 : -1) + data.dayCount) % data.dayCount) + 1; renderDay(); renderCandidates(); $('[data-day="' + state.day + '"]').focus(); }); $('#days').append(button);
    }
    document.querySelectorAll('[data-filter]').forEach((button) => button.addEventListener('click', () => { state.filter = button.dataset.filter; renderCandidates(); }));
    $('#sort').addEventListener('click', () => { const day = state.draft.days.find((item) => item.day === state.day); day.stops.sort((left, right) => minutes(left.startTime) - minutes(right.startTime)); invalidateTravel(day); changed('已按开始时间排序，交通用时需要重新核验。'); renderDay(); });
    $('#reset').addEventListener('click', () => { if (!state.edited) return; state.draft = JSON.parse(JSON.stringify(initial)); state.edited = false; renderDay(); renderCandidates(); say('已恢复生成时的草稿。'); }); $('#export').addEventListener('click', exportDraft);
    renderSources(); renderDay(); renderCandidates(); $('#generated-at').textContent = data.generatedAt ? '样例生成时间：' + localDate(data.generatedAt, true) + '（北京时间）' : '本地生成的验证样例';
  }
  init();
}
const style = `
:root{color-scheme:light;--bg:#f7f8f2;--surface:#fff;--ink:#344331;--muted:#5c6a56;--accent:#65794f;--accent-hover:#536540;--soft:#e7eddc;--line:#dce3d4;--pending:#785632;--pending-bg:#f6efe2;--error:#a04430;--radius:14px;font:15px/1.65 "Microsoft YaHei","PingFang SC",system-ui,sans-serif}
*{box-sizing:border-box}html{scroll-behavior:smooth;scroll-padding-top:24px}body{margin:0;background:var(--bg);color:var(--ink)}button,input,textarea{font:inherit}button,a,input,textarea{-webkit-tap-highlight-color:transparent}button{cursor:pointer}button:disabled{cursor:not-allowed}a{color:var(--accent-hover);text-underline-offset:4px;text-decoration-thickness:1px}button:focus-visible,a:focus-visible,input:focus-visible,textarea:focus-visible{outline:3px solid #86996d;outline-offset:3px}::selection{background:#cedbbd;color:#25341d}input,textarea{caret-color:var(--accent)}[hidden]{display:none!important}.page{width:min(1240px,calc(100% - 72px));margin:0 auto;padding:42px 0 32px}.heading{display:flex;justify-content:space-between;align-items:center;gap:28px}.heading h1{font-size:30px;line-height:1.35;letter-spacing:-.025em;margin:0;font-weight:650}.subtitle{margin:10px 0 0;color:var(--muted);max-width:65ch}.heading-actions{display:flex;flex-direction:column;align-items:flex-end;gap:7px}.export-button{border:0;background:var(--accent);color:#fff;padding:11px 21px;border-radius:12px;font-weight:600;white-space:nowrap}.export-button:hover:not(:disabled){background:var(--accent-hover)}.export-button:disabled{background:#dce3d4;color:#536149}.edit-state{font-size:12px;color:var(--muted)}.export-help{font-size:12px;color:var(--muted);max-width:34ch;text-align:right;margin:0}.stages{display:flex;flex-wrap:wrap;gap:16px 38px;margin:30px 0 22px;padding:17px 0;border-top:1px solid var(--line);border-bottom:1px solid var(--line)}.stage{display:flex;gap:10px}.stage dt{color:var(--muted)}.stage dd{margin:0;font-weight:600}.notice{background:var(--soft);border-radius:12px;padding:15px 18px;margin:0 0 24px;font-size:14px}.workspace{display:grid;grid-template-columns:minmax(0,1.16fr) minmax(0,1fr);gap:40px;align-items:start}.section-heading{display:flex;gap:14px;align-items:center;justify-content:space-between;margin-bottom:14px}.section-heading h2{font-size:21px;line-height:1.4;margin:0;font-weight:650}.section-heading p{color:var(--muted);font-size:13px;margin:0}.section-description{font-size:13px;color:var(--muted);margin:0 0 18px;max-width:67ch}.day-tabs{display:flex;gap:8px;overflow-x:auto;padding:2px 2px 10px;scrollbar-width:thin;scrollbar-color:#aab99a transparent}.day-tab{border:1px solid var(--line);background:transparent;color:var(--muted);padding:8px 17px;border-radius:10px;white-space:nowrap;min-height:42px}.day-tab[aria-selected=true]{background:var(--accent);border-color:var(--accent);color:#fff;font-weight:600}.day-tab:hover:not([aria-selected=true]){background:var(--soft)}.day-meta{display:flex;align-items:center;gap:12px;padding:10px 0 12px}.day-meta h3{font-size:16px;margin:0}.day-count{font-size:13px;color:var(--muted)}.quiet-button{border:0;background:none;color:var(--muted);padding:5px 8px;border-radius:8px;font-size:13px}.quiet-button:hover:not(:disabled){background:var(--soft);color:var(--ink)}.quiet-button:disabled{opacity:.5}.day-meta .quiet-button{margin-left:auto}.empty{padding:32px 26px;background:#eef2e7;border-radius:var(--radius)}.empty h3{font-size:19px;margin:0 0 9px}.empty p{font-size:14px;color:var(--muted);max-width:36ch;margin:0 0 18px}.text-action{font-size:13px;white-space:nowrap;font-weight:600}.stop{padding:22px 0;border-top:1px solid var(--line)}.stop:first-child{border-top:0;padding-top:8px}.stop-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:12px}.stop h3{font-size:18px;line-height:1.45;margin:0 0 6px;overflow-wrap:anywhere}.tag{font-size:11px;line-height:1.6;display:inline-block;padding:2px 7px;border-radius:5px;white-space:normal;max-width:100%}.tag.pending{background:var(--pending-bg);color:var(--pending)}.tag.matched{background:var(--soft);color:#435a31}.time-fields{display:grid;grid-template-columns:1fr 1fr;gap:14px;margin-top:16px}.field{display:flex;flex-direction:column;gap:6px;margin:0 0 13px;min-width:0}.field-label{font-size:12px;color:var(--muted)}.field input,.field textarea{width:100%;min-height:43px;border:1px solid #c5cfbc;border-radius:9px;background:#fff;color:var(--ink);padding:9px 11px;font-variant-numeric:tabular-nums}.field textarea{resize:vertical;min-height:72px;font-size:14px;line-height:1.6}.field input::placeholder,.field textarea::placeholder{color:#62725a}.field input:hover,.field textarea:hover{border-color:#8d9e7d}.field input:invalid{border-color:var(--error)}.references{display:flex;flex-wrap:wrap;gap:6px 10px;min-width:0}.reference{font-size:12px;white-space:nowrap}.reference.missing{color:var(--error)}.travel-note{font-size:12px;color:var(--muted);margin:12px 0 0}.filters{display:flex;gap:4px;flex-wrap:wrap;border-bottom:1px solid var(--line);padding-bottom:12px}.filter{background:transparent;border:0;border-radius:8px;padding:6px 12px;color:var(--muted);font-size:13px;min-height:36px}.filter[aria-pressed=true]{background:var(--soft);color:#435a31;font-weight:600}.filter:hover{background:#eef2e7}.candidate{padding:18px 0;border-bottom:1px solid var(--line)}.candidate-heading{display:flex;align-items:flex-start;gap:12px;justify-content:space-between}.candidate h3{font-size:16px;line-height:1.5;margin:0;overflow-wrap:anywhere}.candidate .tag{flex-shrink:0}.candidate-meta,.candidate-address,.candidate-reason,.candidate-note{font-size:12px;color:var(--muted);margin:6px 0 0;overflow-wrap:anywhere}.candidate-reason{color:var(--pending)}.candidate-note{font-size:13px}.evidence{margin:10px 0 0;color:var(--muted);font-size:12px;padding:0;overflow-wrap:anywhere;max-width:55ch}.evidence:before{content:'来源摘录：';color:#536149}.candidate-actions{display:flex;justify-content:space-between;align-items:center;gap:16px;margin-top:13px}.add-button{background:#eef2e7;border:0;color:#435a31;border-radius:9px;padding:7px 12px;font-size:12px;white-space:nowrap;min-height:36px}.add-button:hover:not(:disabled){background:#dae4cc}.add-button:disabled{color:#68765e;background:#f0f2ec}.candidate-empty{font-size:14px;color:var(--muted);padding:20px 0;max-width:36ch}.validation{background:#f9ebe4;color:#7e3524;padding:16px 19px;border-radius:12px;margin:12px 0 18px;font-size:13px}.validation ul{margin:8px 0 0;padding-left:19px}.feedback{min-height:25px;color:#435a31;font-size:13px;margin:12px 0 0}.sources-section{margin-top:42px;padding-top:24px;border-top:1px solid var(--line)}.source-grid{display:grid;grid-template-columns:1fr 1fr;gap:0 40px}.source{padding:20px 0;border-bottom:1px solid var(--line);scroll-margin-top:24px}.source:target{background:#eef2e7;outline:6px solid #eef2e7;border-radius:6px}.source-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:18px}.source-heading>div{display:flex;align-items:baseline;gap:10px;min-width:0}.source-id{font-size:12px;color:var(--muted);flex-shrink:0}.source h3{font-size:15px;line-height:1.5;font-weight:600;margin:0;overflow-wrap:anywhere}.source-meta{font-size:11px;color:var(--muted);margin:8px 0 0}.source-excerpt{font-size:12px;color:var(--muted);margin:10px 0 0;max-width:65ch;overflow-wrap:anywhere}.source-unavailable{font-size:12px;color:var(--pending);white-space:nowrap}.warnings-wrap{margin-top:26px;color:var(--muted);font-size:13px}.warnings-wrap summary{cursor:pointer;font-weight:600}.warnings-wrap ul{max-width:75ch;padding-left:20px}.page-footer{display:flex;justify-content:space-between;gap:20px;padding-top:26px;color:var(--muted);font-size:11px}.page-footer p{margin:0;max-width:65ch}.reset-button{display:block;margin:16px 0 0 0;padding-left:0;text-decoration:underline;text-underline-offset:4px}.skip{position:absolute;top:-100px;left:16px;background:var(--accent);color:#fff;padding:10px 16px;z-index:2}.skip:focus{top:8px}
@media(max-width:900px){.page{width:calc(100% - 48px);padding-top:30px}.workspace{gap:28px;grid-template-columns:minmax(0,1fr) minmax(0,1fr)}.candidate-heading{flex-direction:column;gap:6px}.source-grid{gap:0 28px}.stages{gap:12px 24px}.stage{font-size:13px;gap:7px}.source-heading{flex-direction:column;gap:7px}}
@media(max-width:680px){.page{width:calc(100% - 36px);padding-top:25px}.heading{align-items:flex-start;flex-direction:column;gap:18px}.heading h1{font-size:26px}.subtitle{font-size:14px}.heading-actions{align-items:flex-start;width:100%}.export-button{width:100%;min-height:46px}.export-help{text-align:left;max-width:none}.stages{display:grid;grid-template-columns:1fr 1fr;gap:12px 18px;margin-top:23px;padding:16px 0}.stage{display:block}.stage dd{margin-top:2px}.notice{font-size:13px;padding:14px 15px;margin-bottom:25px}.workspace{grid-template-columns:1fr;gap:34px}.day-tab{flex:1;padding:8px 14px}.section-heading h2{font-size:20px}.section-description{margin-bottom:12px}.empty{padding:24px 21px}.candidate-heading{flex-direction:row;gap:12px}.candidate-actions{gap:10px}.source-grid{grid-template-columns:1fr}.source-heading{flex-direction:row;gap:15px}.page-footer{flex-direction:column;gap:8px}.sources-section{margin-top:34px}.time-fields{gap:12px}.source-heading>div{align-items:flex-start}.feedback{font-size:12px}.day-meta .quiet-button{font-size:12px}}
@media(prefers-reduced-motion:reduce){html{scroll-behavior:auto}}
@media print{.page{width:100%;padding:0}.heading-actions,.filters,.add-button,.quiet-button,.feedback{display:none}.workspace,.source-grid{display:block}.candidate,.source,.stop{break-inside:avoid}.field input,.field textarea{border:0;padding:0}.stages{margin:16px 0}.notice{background:none;padding:0}}
`;

export function renderReport(report) {
  const data = publicReport(report);
  // HTML parsing normalizes CR/CRLF before CSP checks; hash the same bytes.
  const script = ('const validateEditorDraft = ' + validateEditorDraft.toString() + ';(' + client.toString() + ')();').replace(/\r\n?/gu, '\n');
  const scriptHash = createHash('sha256').update(script).digest('base64');
  return `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="light"><meta name="referrer" content="no-referrer"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'sha256-${scriptHash}'; style-src 'unsafe-inline'; connect-src 'none'; img-src data:; object-src 'none'; base-uri 'none'; form-action 'none'"><title>行程验证样例 · MY PLAN</title><style>${style}</style></head>
<body><a class="skip" href="#draft">跳到日程草稿</a><main class="page">
<header class="heading"><div><h1 id="trip-title">行程验证样例</h1><p id="subtitle" class="subtitle"></p></div><div class="heading-actions"><button id="export" class="export-button" type="button" disabled>导出草稿 JSON</button><span id="edit-state" class="edit-state">本地样例 · 可编辑</span><p id="export-help" class="export-help"></p></div></header>
<dl id="stages" class="stages" aria-label="本轮处理状态"></dl><p id="notice" class="notice" hidden></p>
<div class="workspace"><section id="draft" aria-labelledby="draft-heading"><div class="section-heading"><h2 id="draft-heading">日程草稿</h2><p>编辑后导出，来源一起保留</p></div><p class="section-description">时间与停留时长都是建议。地点匹配不代表营业、门票或预约信息已核实。</p><div id="days" class="day-tabs" role="tablist" aria-label="选择行程天数"></div><div class="day-meta"><h3 id="day-heading"></h3><span id="day-count" class="day-count"></span><button id="sort" type="button" class="quiet-button">按时间排序</button></div><div id="validation" class="validation" tabindex="-1" role="alert" hidden></div><div id="stops" role="tabpanel" aria-label="当天日程"></div><button id="reset" class="quiet-button reset-button" type="button">恢复初始草稿</button><p id="feedback" class="feedback" role="status" aria-live="polite"></p></section>
<section id="candidates" aria-labelledby="candidate-heading"><div class="section-heading"><h2 id="candidate-heading">候选地点</h2><p id="candidate-count"></p></div><p class="section-description">从攻略里提取的想法。先查看核验状态，再加入当前这一天。</p><div class="filters" role="group" aria-label="筛选地点核验状态"><button class="filter" type="button" data-filter="all" aria-pressed="true">全部</button><button class="filter" type="button" data-filter="matched" aria-pressed="false">地点已匹配</button><button class="filter" type="button" data-filter="pending" aria-pressed="false">待核验</button></div><div id="candidate-list"></div></section></div>
<section class="sources-section" aria-labelledby="sources-heading"><div class="section-heading"><h2 id="sources-heading">攻略来源</h2><p>保留原文，方便逐条核对</p></div><div id="source-list" class="source-grid"></div></section>
<details id="warnings-wrap" class="warnings-wrap" hidden><summary>本轮限制与待核实信息</summary><ul id="warnings"></ul></details>
<footer class="page-footer"><p>MY PLAN · 本地验证样例。修改仅保留在当前页面，导出文件不会自动写入 App。</p><p id="generated-at"></p></footer>
</main><script id="report-data" type="application/json">${serialize(data)}</script><script>${script}</script></body></html>`;
}
