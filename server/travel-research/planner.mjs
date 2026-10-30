import { ResearchDataError, normalizePlaceName, sourceRefKey, uniqueSourceRefs } from './contract.mjs';

function requireInput(condition, message) {
  if (!condition) throw new ResearchDataError('INVALID_DRAFT_INPUT', message);
}

function coordinatesOf(candidate) {
  const value = candidate.verification?.location ?? candidate.verification?.coordinates ?? candidate.location ?? candidate.coordinates;
  let longitude, latitude;
  if (Array.isArray(value)) [longitude, latitude] = value;
  else if (typeof value === 'string') [longitude, latitude] = value.split(',').map(Number);
  else if (value && typeof value === 'object') {
    longitude = value.longitude ?? value.lng;
    latitude = value.latitude ?? value.lat;
  }
  return Number.isFinite(longitude) && Number.isFinite(latitude) && Math.abs(longitude) <= 180 && Math.abs(latitude) <= 90
    ? { longitude, latitude } : null;
}

export function isVerifiedCandidate(candidate) {
  const verification = candidate?.verification ?? {};
  return (verification.status ?? candidate?.verificationStatus) === 'verified' &&
    typeof (verification.poiId ?? candidate?.poiId) === 'string' && (verification.poiId ?? candidate.poiId).trim().length > 0 &&
    coordinatesOf(candidate) !== null;
}

function dateValue(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/u.test(value)) return null;
  if (Number(value.slice(0, 4)) < 1) return null;
  const parsed = new Date(`${value}T12:00:00.000Z`);
  return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value ? parsed : null;
}

const time = (minutes) => `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
// An evening activity preference, not a claim about opening hours.
const visitWindow = (candidate) => /夜市/u.test(candidate.name) ? { earliest:17*60,latest:21*60 } : { earliest:9*60,latest:18*60 };
function timeValue(value) {
  return typeof value === 'string' && /^(?:[01]\d|2[0-3]):[0-5]\d$/u.test(value)
    ? Number(value.slice(0, 2)) * 60 + Number(value.slice(3)) : null;
}

function distanceSquared(from, to) {
  const a = coordinatesOf(from), b = coordinatesOf(to);
  if (!a || !b) return Infinity;
  // Used only to propose an order, never presented as route distance or traffic time.
  const latitudeCorrection = Math.cos((a.latitude + b.latitude) / 2 * Math.PI / 180);
  return ((a.longitude - b.longitude) * latitudeCorrection) ** 2 + (a.latitude - b.latitude) ** 2;
}

function routeMinutes(legs, from, to) {
  const route = legs.find((leg) => leg?.fromCandidateId === from && leg?.toCandidateId === to &&
    leg.status === 'verified' && Number.isFinite(leg.durationMinutes) && leg.durationMinutes > 0 && leg.durationMinutes <= 1440);
  return route ? Math.ceil(route.durationMinutes) : null;
}

/** Plan only map-matched candidates. Unknown travel time remains null, with a separately labelled planning buffer. */
export function buildDraft({ destination, dayCount, startDate, pace = 'normal', candidates, legs = [] } = {}) {
  requireInput(typeof destination === 'string' && destination.trim().length > 0 && destination.length <= 200, 'destination is required');
  requireInput(Number.isInteger(dayCount) && dayCount >= 1 && dayCount <= 14, 'dayCount must be 1–14');
  requireInput(pace === 'relaxed' || pace === 'normal', 'pace must be relaxed or normal');
  requireInput(Array.isArray(candidates) && candidates.length <= 200 && Array.isArray(legs), 'candidates and legs must be arrays');
  const date = startDate === undefined ? null : dateValue(startDate);
  requireInput(startDate === undefined || date, 'startDate must be a real YYYY-MM-DD date');
  if (date) {
    const last = new Date(date); last.setUTCDate(last.getUTCDate() + dayCount - 1);
    requireInput(last.getUTCFullYear() <= 9999, 'trip dates must stay within years 0001–9999');
  }
  const knownIds = new Set();
  for (const candidate of candidates) {
    requireInput(candidate && typeof candidate.id === 'string' && !knownIds.has(candidate.id), 'candidate IDs must be unique');
    requireInput(typeof candidate.name === 'string' && candidate.name.trim() && Array.isArray(candidate.sourceRefs) && candidate.sourceRefs.length,
      'candidates need a name and source evidence');
    requireInput(Number.isInteger(candidate.suggestedDurationMinutes) && candidate.suggestedDurationMinutes >= 15 &&
      candidate.suggestedDurationMinutes <= 720, 'candidate suggested duration is invalid');
    knownIds.add(candidate.id);
  }
  const days = Array.from({ length: dayCount }, (_, index) => {
    const value = { day: index + 1, stops: [] };
    if (date) { const next = new Date(date); next.setUTCDate(next.getUTCDate() + index); value.date = next.toISOString().slice(0, 10); }
    return value;
  });
  const alternatives = candidates.filter((candidate) => !isVerifiedCandidate(candidate)).map((candidate) => ({
    candidateId: candidate.id, title: candidate.name, sourceRefs: uniqueSourceRefs(candidate.sourceRefs),
    reason: '地点尚未完成唯一地图匹配，保留为待核验备选。', verificationStatus: candidate.verification?.status ?? candidate.verificationStatus ?? 'unverified',
  }));
  const remaining = candidates.filter(isVerifiedCandidate);
  const warnings = [];
  if (alternatives.length) warnings.push(`${alternatives.length} 个地点尚未完成地图匹配，未自动排入日程。`);
  const maximumStops = pace === 'relaxed' ? 3 : 4;
  const planningBuffer = pace === 'relaxed' ? 45 : 30;
  let unknownRoutes = 0;
  for (const day of days) {
    let clock = 9 * 60;
    // Spread available verified places over the requested days rather than packing
    // the first day and leaving later days empty when there is enough material.
    const targetStops = Math.min(maximumStops, Math.ceil(remaining.length / (dayCount - day.day + 1)));
    while (remaining.length && day.stops.length < targetStops) {
      const previous = day.stops.at(-1);
      const previousCandidate = previous ? candidates.find((item) => item.id === previous.candidateId) : null;
      const ranked = remaining.map((candidate, index) => ({ candidate, index,
        score: previousCandidate ? (normalizePlaceName(candidate.city) === normalizePlaceName(previousCandidate.city) ? 0 : 1000) +
          distanceSquared(previousCandidate, candidate) : index,
      })).sort((a, b) => a.score - b.score || a.index - b.index);
      let selected = null;
      for (const item of ranked) {
        const travel = previous ? routeMinutes(legs, previous.candidateId, item.candidate.id) : null;
        const buffer = previous && travel === null ? planningBuffer : 0;
        let start = clock + (travel ?? buffer);
        start = Math.max(start,visitWindow(item.candidate).earliest);
        if (start >= 12 * 60 && start < 13 * 60) start = 13 * 60;
        const end = start + item.candidate.suggestedDurationMinutes;
        if (end <= visitWindow(item.candidate).latest) { selected = { ...item, travel, buffer, start, end }; break; }
      }
      if (!selected) break;
      const candidate = selected.candidate;
      const notes = [candidate.notes,
        /夜市/u.test(candidate.name)?'夜市按晚间活动给出时间建议，具体营业日期和时段仍需核实。':'',
        '到访时间与停留时长为规划建议；营业、预约及临时闭园信息仍需核实。',
        previous && selected.travel === null ? `此段交通待核验；预留 ${selected.buffer} 分钟仅为建议缓冲，不是地图交通耗时。` : '',
        previous && selected.travel !== null ? '此段交通时间为地图接口查询估计，不保证未来出行时相同。' : '',
      ].filter(Boolean).join('\n');
      day.stops.push({
        candidateId: candidate.id, title: candidate.name, startTime: time(selected.start),
        durationMinutes: candidate.suggestedDurationMinutes,
        travelFromPreviousMinutes: selected.travel,
        planningBufferMinutes: selected.buffer,
        sourceRefs: uniqueSourceRefs(candidate.sourceRefs), notes, verificationStatus: 'verified',
      });
      if (previous && selected.travel === null) unknownRoutes++;
      clock = selected.end;
      remaining.splice(selected.index, 1);
    }
  }
  for (const candidate of remaining) alternatives.push({
    candidateId: candidate.id, title: candidate.name, sourceRefs: uniqueSourceRefs(candidate.sourceRefs),
    verificationStatus: 'verified', reason: '未在每日活动数量和建议时间范围内排入，可手动调整后添加。',
  });
  if (unknownRoutes) warnings.push(`${unknownRoutes} 段交通时间尚未核验，草稿仅预留建议缓冲。`);
  if (remaining.length) warnings.push(`${remaining.length} 个已匹配地点留作备选，没有压缩停留时长或强行塞满行程。`);
  if (days.some((day) => day.stops.length === 0)) warnings.push('部分日期尚无可核验的安排，保持留空，未补造地点。');
  warnings.push('地图匹配仅确认地点；日程顺序为规划建议，不代表全局最优路线或营业、预约已核实。');
  const draft = { days, warnings, alternatives };
  return { ...draft, validation: validateDraft(draft, { candidates, dayCount, legs }) };
}

/** Apply route costs to the original day groups/order. Overflow removes the remaining
 * tail of that day: skipping a middle stop would create an unqueried shortcut. */
export function reconcileDraftTimes(draft, { candidates, legs = [], dayCount = draft?.days?.length, pace = 'normal' } = {}) {
  requireInput(validateDraft(draft, { candidates, dayCount }).valid, 'the initial draft is invalid');
  const byId = new Map(candidates.map((candidate) => [candidate.id, candidate]));
  const days = [];
  const alternatives = (draft.alternatives ?? []).map((item) => ({ ...item, sourceRefs: uniqueSourceRefs(item.sourceRefs) }));
  const warnings = (draft.warnings ?? []).filter((warning) =>
    !/^\d+ 段交通时间尚未核验/u.test(warning) && !/^部分日期尚无可核验/u.test(warning));
  let unknownRoutes = 0;
  let overflowCount = 0;
  for (const day of draft.days) {
    const stops = [];
    let clock = 9 * 60;
    for (const [index, previousStop] of day.stops.entries()) {
      const candidate = byId.get(previousStop.candidateId);
      const previous = stops.at(-1);
      const travel = previous ? routeMinutes(legs, previous.candidateId, candidate.id) : null;
      const buffer = previous && travel === null ? (pace === 'relaxed' ? 45 : 30) : 0;
      let start = clock + (travel ?? buffer);
      start = Math.max(start,visitWindow(candidate).earliest);
      if (start >= 12 * 60 && start < 13 * 60) start = 13 * 60;
      const end = start + previousStop.durationMinutes;
      if (end > visitWindow(candidate).latest) {
        for (const item of day.stops.slice(index)) alternatives.push({
          candidateId: item.candidateId, title: item.title, verificationStatus: item.verificationStatus,
          sourceRefs: uniqueSourceRefs(item.sourceRefs),
          reason: '按原先顺序计入交通后超出建议日间范围，保留为备选；没有跳过中间地点改走未查询路段。',
        });
        overflowCount += day.stops.length - index;
        break;
      }
      const notes = [candidate.notes,
        /夜市/u.test(candidate.name)?'夜市按晚间活动给出时间建议，具体营业日期和时段仍需核实。':'',
        '到访时间与停留时长为规划建议；营业、预约及临时闭园信息仍需核实。',
        previous && travel === null ? `此段交通待核验；预留 ${buffer} 分钟仅为建议缓冲，不是地图交通耗时。` : '',
        previous && travel !== null ? '此段交通时间为地图接口查询估计，不保证未来出行时相同。' : '',
      ].filter(Boolean).join('\n');
      stops.push({ ...previousStop, startTime: time(start), travelFromPreviousMinutes: travel,
        planningBufferMinutes: buffer, notes, sourceRefs: uniqueSourceRefs(previousStop.sourceRefs) });
      if (previous && travel === null) unknownRoutes++;
      clock = end;
    }
    days.push({ ...day, stops });
  }
  if (unknownRoutes) warnings.push(`${unknownRoutes} 段交通时间尚未核验，草稿仅预留建议缓冲。`);
  if (overflowCount) warnings.push(`${overflowCount} 个节点计入交通后无法按原顺序排入当天，已保留为备选，未改换未查询的路线。`);
  if (days.some((day) => !day.stops.length)) warnings.push('部分日期尚无可核验的安排，保持留空，未补造地点。');
  const result = { days, alternatives, warnings, reconciliation: { fixedOriginalOrder: true, overflowCount, unknownRoutes } };
  return { ...result, validation: validateDraft(result, { candidates, dayCount, legs }) };
}

/** Validate edited draft structure, citations and timing. Unknown routes produce warnings rather than fake zeroes. */
export function validateDraft(draft, { candidates = [], dayCount, legs } = {}) {
  const issues = [];
  const issue = (severity, code, message, details = {}) => issues.push({ severity, code, message, ...details });
  if (!draft || !Array.isArray(draft.days) || draft.days.length < 1 || draft.days.length > 14) {
    issue('error', 'INVALID_DAYS', '日程必须包含 1～14 天。'); return { valid: false, issues };
  }
  if (dayCount !== undefined && draft.days.length !== dayCount) issue('error', 'DAY_COUNT_MISMATCH', '日程天数与请求不一致。');
  const knownCandidates = new Map(candidates.map((candidate) => [candidate.id, candidate]));
  for (const [index, day] of draft.days.entries()) {
    const dayNumber = index + 1;
    if (day?.day !== dayNumber) issue('error', 'DAY_ORDER', '日期序号必须从 1 连续递增。', { day: dayNumber });
    if (day?.date !== undefined && !dateValue(day.date)) issue('error', 'INVALID_DATE', '日期无效。', { day: dayNumber });
    if (!Array.isArray(day?.stops) || day.stops.length > 6) { issue('error', 'INVALID_STOPS', '每天最多安排 6 个草稿节点。', { day: dayNumber }); continue; }
    let previousEnd = null;
    let previousCandidateId = null;
    for (const [stopIndex, stop] of day.stops.entries()) {
      const details = { day: dayNumber, stopIndex };
      const candidate = knownCandidates.get(stop?.candidateId);
      if (!candidate) issue('error', 'UNKNOWN_CANDIDATE', '节点引用了候选清单之外的地点。', details);
      else if (!isVerifiedCandidate(candidate) || stop.verificationStatus !== 'verified')
        issue('error', 'UNVERIFIED_PLACE', '自动行程包含尚未完成地图匹配的地点。', details);
      if (typeof stop?.title !== 'string' || !stop.title.trim() || stop.title.length > 120)
        issue('error', 'INVALID_TITLE', '节点名称无效。', details);
      const start = timeValue(stop?.startTime);
      const duration = stop?.durationMinutes;
      const validDuration = Number.isInteger(duration) && duration >= 1 && duration <= 720;
      if (start === null || !validDuration) issue('error', 'INVALID_TIME', '开始时间或停留时长无效。', details);
      const end = start !== null && validDuration ? start + duration : null;
      if (end !== null && end > 24 * 60) issue('error', 'CROSSES_MIDNIGHT', '草稿节点不得跨越午夜。', details);
      const travel = stop?.travelFromPreviousMinutes;
      if (travel !== null && (!Number.isInteger(travel) || travel <= 0 || travel > 1440))
        issue('error', 'INVALID_TRAVEL_TIME', '交通时间须为正整数分钟，未知时必须保留 null。', details);
      if (stopIndex === 0 && travel !== null)
        issue('error', 'FIRST_STOP_TRAVEL', '当天起点尚未指定，首个节点不应声称已有交通时间。', details);
      if (!Number.isInteger(stop?.planningBufferMinutes) || stop.planningBufferMinutes < 0 || stop.planningBufferMinutes > 1440)
        issue('error', 'INVALID_BUFFER', '建议缓冲须为非负整数分钟。', details);
      if (previousEnd !== null && start !== null) {
        if (start < previousEnd) issue('error', 'OVERLAP', '相邻活动时间重叠。', details);
        if (Number.isInteger(travel) && travel > 0 && start < previousEnd + travel)
          issue('error', 'TRAVEL_GAP', '相邻节点没有留足已查询的交通时间。', details);
        if (Array.isArray(legs) && travel !== null && routeMinutes(legs, previousCandidateId, stop?.candidateId) !== travel)
          issue('error', 'UNSUPPORTED_TRAVEL_TIME', '交通时间与该方向的已查询路线不一致。', details);
        if (travel === null) {
          issue('warning', 'UNVERIFIED_TRAVEL', '此段交通尚未核验；时间间隔仅为规划建议。', details);
          const buffer = stop?.planningBufferMinutes;
          if (Number.isInteger(buffer) && buffer > 0 && start < previousEnd + buffer)
            issue('warning', 'BUFFER_REDUCED', '编辑后少于原先预留的建议交通缓冲。', details);
        }
      }
      const permittedRefs = new Set((candidate?.sourceRefs ?? []).map(sourceRefKey));
      if (!Array.isArray(stop?.sourceRefs) || !stop.sourceRefs.length || stop.sourceRefs.some((ref) => !permittedRefs.has(sourceRefKey(ref))))
        issue('error', 'INVALID_SOURCE_REF', '节点来源缺失或不属于该地点的已核验来源。', details);
      previousEnd = end;
      previousCandidateId = stop?.candidateId;
    }
  }
  return { valid: !issues.some((item) => item.severity === 'error'), issues };
}
