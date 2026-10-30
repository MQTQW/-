/* Result views only. All changes are handled by the app's delegated actions. */
function rvAllStops(trip) {
  return (trip.days || []).flatMap((day, dayIndex) => (day.stops || []).map((node, index) => ({node, day, dayIndex, index})));
}

function rvDate(date, short = false) {
  if (!date || !/^\d{4}-\d{2}-\d{2}$/.test(date)) return '';
  const [, month, day] = date.split('-');
  return short ? `${Number(month)}/${Number(day)}` : `${Number(month)}月${Number(day)}日`;
}

function rvDateSummary(trip) {
  const days = trip.days || [];
  const first = days.find(day => rvDate(day.date));
  const last = [...days].reverse().find(day => rvDate(day.date));
  if (trip.undated || !first) return `${days.length}天 · 日期待定`;
  return `${rvDate(first.date)}${last && last.date !== first.date ? ` — ${rvDate(last.date)}` : ''} · ${days.length}天`;
}

function rvConstraintValue(value) {
  if (!value) return '';
  if (typeof value === 'string') return value;
  return [value.label || value.title || value.name || value.place, value.time].filter(Boolean).join(' ');
}

function rvConditions(trip) {
  const c = trip.constraints || {};
  const arrival = rvConstraintValue(c.arrival);
  const returning = rvConstraintValue(c.returnTime || c.return);
  const hotel = rvConstraintValue(c.hotel);
  const pieces = [arrival && `${arrival}到达`, returning && `${returning}返程`, hotel].filter(Boolean);
  return `<button type="button" class="rv-conditions" data-action="conditions" aria-label="查看和修改行程条件"><span>${icon('clock', 16)}<span>${pieces.length ? pieces.map(esc).join('<i aria-hidden="true"> · </i>') : '日期、到达与住宿条件'}</span></span>${icon('arrow', 16)}</button>`;
}

function rvDuration(node) {
  return Number(node.duration) > 0 ? `停留 ${Number(node.duration)} 分钟` : '时长待定';
}

function rvRouteSignature(trip, day) {
  return JSON.stringify([day.date || null, trip.creationForm?.mode || '公共交通', (day.stops || []).map(node => [node.id, node.time, Number(node.duration), node.lng ?? null, node.lat ?? null, node.locationStatus || '', node.verification?.status || '', node.verification?.poiId || ''])]);
}

function rvIncomingLeg(trip, day, index) {
  const previous = day.stops?.[index - 1], current = day.stops?.[index];
  if (!previous || !current || current.travelStatus !== 'estimated') return null;
  if (day.routeEvidenceSignature && day.routeEvidenceSignature !== rvRouteSignature(trip, day)) return null;
  return (day.legs || []).find(leg => leg.fromCandidateId === previous.id && leg.toCandidateId === current.id && leg.status === 'estimated' && Number.isFinite(leg.durationMinutes) && leg.durationMinutes > 0 && leg.durationMinutes <= 1440) || null;
}

function rvTravelGap(previous, current) {
  const start = Model.timeMinutes(previous.time), next = Model.timeMinutes(current.time), duration = Number(previous.duration);
  return Number.isFinite(start) && Number.isFinite(next) && Number.isFinite(duration) ? next - start - duration : null;
}

function rvTravelMode(mode) {
  return ({'步行':'步行', '公共交通':'公共交通', '自驾':'驾车'})[mode] || '交通';
}

function rvTravelConflict(previous, current, leg) {
  const gap = rvTravelGap(previous, current);
  if (gap === null || leg.durationMinutes <= gap) return '';
  return `${gap >= 0 ? `预留${gap}分钟` : `安排已重叠${Math.abs(gap)}分钟`}，地图估计${leg.durationMinutes}分钟，请调整`;
}

function rvLegQueryTime(leg) {
  const date = typeof leg.checkedAt === 'string' ? new Date(leg.checkedAt) : null;
  return date && !Number.isNaN(date.getTime()) ? `<time datetime="${esc(date.toISOString())}">${esc(date.toLocaleString('zh-CN'))}</time>` : '查询时间未提供';
}

function rvLegMarkup(trip, day, index) {
  const leg = rvIncomingLeg(trip, day, index);
  if (!leg) return `<div class="leg rv-leg">${icon('route', 14)}<span>交通待核对${day.routeEvidenceStale ? ' · 安排已修改' : ''}</span></div>`;
  const conflict = rvTravelConflict(day.stops[index - 1], day.stops[index], leg);
  return `<div class="leg rv-leg rv-estimated-leg ${conflict ? 'has-conflict' : ''}">${icon('route', 14)}<div><strong>估计${rvTravelMode(leg.mode)}${leg.durationMinutes}分钟</strong><small>查询于 ${rvLegQueryTime(leg)} · 仅为查询时估计</small>${conflict ? `<p class="rv-leg-conflict">${conflict}</p>` : ''}<small>不保证出行当天耗时，景区内移动与排队未计入。</small></div></div>`;
}

function rvTravelIssues(trip, onlyDayIndex = null) {
  const conflicts = []; let pending = 0, estimated = 0;
  (trip.days || []).forEach((day, dayIndex) => {
    if (onlyDayIndex !== null && onlyDayIndex !== dayIndex) return;
    (day.stops || []).forEach((node, index) => {
      if (!index) return;
      const leg = rvIncomingLeg(trip, day, index);
      if (!leg) { pending++; return; }
      estimated++;
      const previous = day.stops[index - 1], message = rvTravelConflict(previous, node, leg);
      if (message) conflicts.push({dayIndex, previous, node, leg, message});
    });
  });
  return {conflicts, pending, estimated};
}

function rvTravelNotice(trip, onlyDayIndex = null) {
  const {conflicts, pending, estimated} = rvTravelIssues(trip, onlyDayIndex);
  const warnings = !trip.backendWarningsStale && Array.isArray(trip.backendWarnings) ? trip.backendWarnings.filter(warning => typeof warning === 'string' && warning.trim()) : [];
  if (!conflicts.length && !pending && !estimated && !warnings.length && !trip.backendWarningsStale) return '';
  return `<section class="rv-travel-notice ${conflicts.length ? 'has-conflict' : ''}" aria-label="交通时间核对">${conflicts.length ? `<h2>${conflicts.length}处交通时间不足，需要调整</h2>${conflicts.map(issue => `<button type="button" data-action="day-overview" data-index="${issue.dayIndex}"><span><strong>第${issue.dayIndex + 1}天 · ${esc(issue.previous.title)} → ${esc(issue.node.title)}</strong><small>${issue.message}</small></span>${icon('arrow', 16)}</button>`).join('')}` : ''}${pending ? `<p>${trip.backendWarningsStale ? '行程已修改，' : ''}${pending}段交通待核对；预留间隔不代表实际耗时。</p>` : ''}${estimated ? '<p>地图耗时仅为查询时估计，不保证出行当天交通。</p>' : ''}${trip.backendWarningsStale && !pending ? '<p>行程已修改，原编排的交通提醒需重新核对。</p>' : ''}${warnings.length ? `<details><summary>查看编排返回的提醒（${warnings.length}）</summary><ul>${warnings.map(warning => `<li>${esc(warning)}</li>`).join('')}</ul></details>` : ''}</section>`;
}

function rvHasLocation(node) {
  return node.locationStatus !== 'pending' && Number.isFinite(node.lng) && Number.isFinite(node.lat) && Math.abs(node.lng) <= 180 && Math.abs(node.lat) <= 90;
}

function rvLocationLabel(node) {
  if (node.locationStatus === 'pending') return node.kind === 'meal' ? '餐饮地点待确认' : '地点待选';
  if (!rvHasLocation(node)) return node.kind === 'hotel' ? '具体住宿待确认' : '具体位置待确认';
  if (node.locationStatus === 'selected') return '已选择地点';
  if (node.locationStatus === 'manual') return '手动添加地点';
  return '地图位置已返回';
}

function rvBadges(node) {
  return `<div class="rv-node-badges">${node.locked ? '<span class="rv-fixed">固定时间</span>' : ''}${node.mustKeep ? '<span class="rv-fixed">你标记的必选</span>' : ''}${node.manuallyEdited ? '<span>已手动修改</span>' : ''}${node.locationStatus === 'pending' ? `<span class="rv-unresolved">${node.kind === 'meal' ? '餐饮地点待确认' : '地点待选'}</span>` : ''}</div>`;
}

function rvScenicCopy(node) {
  return node.description||node.notes||'根据已选清单编排，具体地点与开放安排仍需核对。';
}

function rvFeatures(trip) {
  const used=new Set();
  return rvAllStops(trip).filter(({node})=>{const url=LiveAPI.imageURL(node.liveImage||node.image);if(!url||used.has(url))return false;used.add(url);return true;}).slice(0,6);
}

function rvPending(trip) {
  const pending = rvAllStops(trip).filter(({node}) => node.locationStatus === 'pending');
  if (!pending.length) return '';
  return `<section class="rv-pending" aria-label="待确认地点"><div class="rv-pending-heading">${icon('pin', 17)}<strong>${pending.length}处地点待确认</strong></div>${pending.map(({node, dayIndex}) => `<button type="button" data-action="confirm" data-id="${esc(node.id)}"><span><strong>${node.kind === 'meal' ? '餐饮地点待确认' : esc(node.title)}</strong><small>第${dayIndex + 1}天 · ${esc(node.time || '时间待定')}${node.kind === 'meal' ? ` · ${esc(node.title)}` : ''}</small></span><span class="rv-pending-cta">去确认${icon('arrow', 15)}</span></button>`).join('')}</section>`;
}

function rvBudgetTotal(budget) {
  if (Number.isFinite(budget)) return budget;
  return Object.values(budget || {}).reduce((total, value) => total + (Number(typeof value === 'object' ? value?.amount : value) || 0), 0);
}

function rvOverview(s) {
  const trip = s.trip, days = trip.days || [], features = rvFeatures(trip), sourceCount = (trip.sources || []).length;
  const photoIndex = Math.max(0, Math.min(features.length - 1, Number(s.carouselIndex) || 0));
  return `<div class="rv-overview">
    <header class="rv-trip-heading"><h1>${esc(trip.title || '我的旅行')}</h1><p>${esc(rvDateSummary(trip))}</p></header>
    ${rvConditions(trip)}
    <button type="button" class="rv-source-summary" data-action="sources"><span>${icon('source', 16)}${sourceCount ? `参考了 ${sourceCount} 篇攻略` : '手动规划 · 暂无攻略来源'}</span><span>查看依据${icon('arrow', 15)}</span></button>
    ${rvTravelNotice(trip)}
    ${rvPending(trip)}
    ${features.length ? `<section class="rv-highlights" aria-label="行程攻略配图"><div class="section-heading"><h2>行程中的攻略配图</h2>${features.length>1?`<div class="carousel-controls"><button type="button" class="icon-btn" data-action="photo-prev" aria-label="上一张攻略配图">${icon('back',18)}</button><span id="photo-count" aria-live="polite">${photoIndex+1}/${features.length}</span><button type="button" class="icon-btn" data-action="photo-next" aria-label="下一张攻略配图">${icon('arrow',18)}</button></div>`:''}</div><div class="photo-track rv-photo-track" id="photo-track" aria-label="左右滑动查看攻略配图">${features.map(({node,dayIndex},index)=>`<article class="photo-card rv-photo-card">${liveImageMarkup(node.liveImage||node.image,{className:'live-feature-image',alt:node.title+'的攻略配图',eager:index===0})}<button type="button" class="photo-caption live-feature-details" data-action="node" data-id="${esc(node.id)}" aria-label="查看${esc(node.title)}的安排"><p class="live-feature-time">第${dayIndex+1}天 · ${esc(node.time||'时间待定')}</p><h3>${esc(node.title)}</h3><p>${esc(rvScenicCopy(node))}</p><span>${icon('clock',14)}${esc(rvDuration(node))}${icon('arrow',17)}</span></button></article>`).join('')}</div><p class="rv-swipe-hint">${features.length>1?'左右滑动查看配图，点照片可看原尺寸':'点照片可看原尺寸'} · 配图未确认是地点实拍</p></section>` : ''}
    <section class="quick-facts rv-quick-facts" aria-label="预算与天气"><button type="button" class="fact-row" data-action="budget"><span>${icon('budget', 19)}自填预算</span><strong>${rvBudgetTotal(trip.budget)>0?money(rvBudgetTotal(trip.budget)):'待填写'}${icon('arrow', 16)}</strong></button><button type="button" class="fact-row" data-action="weather"><span>${icon('weather', 19)}天气与准备</span><strong>${trip.undated || !days.some(day => day.date) ? '确定日期后查看' : '出发前再核对'}${icon('arrow', 16)}</strong></button></section>
    <section class="rv-daily-summary"><div class="section-heading"><h2>每日速览</h2><span>${days.length}天安排</span></div><div class="day-overview rv-day-overview">${days.map((day, index) => {const visits = (day.stops || []).filter(node => !['arrival', 'hotel', 'return'].includes(node.kind));return `<button type="button" data-action="day-overview" data-index="${index}"><span class="day-number">${index + 1}</span><span class="rv-day-overview-copy"><strong>${esc(day.name || `第${index + 1}天`)}</strong><small>${visits.length ? visits.map(node => esc(node.title)).join(' · ') : '还没有安排，可以从这一天开始添加'}</small>${day.date ? `<small class="rv-day-date">${esc(rvDate(day.date))}</small>` : ''}</span><span class="day-count">${(day.stops || []).length}项${icon('arrow', 15)}</span></button>`;}).join('')}</div></section>
    <p class="bottom-note rv-bottom-note">时间和费用为安排建议；开放、预约与交通仍需在出行前核对。</p>
  </div>`;
}

function rvDayControls(s) {
  const days = s.trip.days || [];
  return `<div class="rv-itinerary-controls"><nav class="day-tabs rv-day-tabs" aria-label="选择行程日期">${days.map((day, index) => `<button type="button" data-action="day" data-index="${index}" aria-pressed="${s.activeDay === index}"><strong>第${index + 1}天</strong>${day.date ? `<span>${esc(rvDate(day.date, true))}</span>` : ''}</button>`).join('')}</nav><div class="rv-view-switch" role="group" aria-label="行程显示方式"><button type="button" data-action="view" data-value="list" aria-pressed="${s.view !== 'map'}">列表</button><button type="button" data-action="view" data-value="map" aria-pressed="${s.view === 'map'}">地图</button></div></div>`;
}

function rvDayHeading(s, day) {
  return `<div class="rv-day-heading"><h1>${esc(day.name || `第${s.activeDay + 1}天`)}</h1><p>${day.date ? `${esc(rvDate(day.date))} · ` : ''}${(day.stops || []).length}项安排${(day.stops || []).some(node => node.locked) ? ' · 含固定时间' : ''}</p></div><div class="rv-day-tools"><button type="button" data-action="add" data-index="${s.activeDay}">${icon('plus', 17)}添加安排</button><button type="button" data-action="ai" data-index="${s.activeDay}">${icon('route', 17)}规则调整这一天</button></div>`;
}

function rvListView(s, day) {
  const stops = day.stops || [];
  if (!stops.length) return `<div class="rv-empty-day"><h2>给这一天加一项安排</h2><p>可以先记下想去的地方，时间和具体地址稍后再补。</p>${button('添加第一项安排', 'add', {attrs: `data-index="${s.activeDay}"`})}</div>`;
  return `<div class="timeline rv-timeline">${stops.map((node, index) => `${index ? rvLegMarkup(s.trip, day, index) : ''}<article class="stop rv-stop ${node.locationStatus === 'pending' ? 'pending-stop' : ''} ${s.selectedId === node.id ? 'rv-stop-selected' : ''}" id="node-${esc(node.id)}" data-node-id="${esc(node.id)}"><div class="stop-time">${esc(node.time || '待定')}<span class="timeline-dot" aria-hidden="true"></span></div><div class="stop-info"><div class="stop-heading"><button type="button" class="stop-title" data-action="node" data-id="${esc(node.id)}">${esc(node.title || '未命名安排')}</button><div class="rv-stop-actions"><button type="button" class="icon-btn" data-action="edit" data-id="${esc(node.id)}" aria-label="编辑${esc(node.title)}">${icon('edit', 17)}</button><button type="button" class="icon-btn" data-action="node-menu" data-id="${esc(node.id)}" aria-label="${esc(node.title)}的更多操作">${icon('more', 18)}</button></div></div>${rvBadges(node)}<p class="stop-meta">${esc(rvDuration(node))} · ${rvLocationLabel(node)}</p>${node.notes ? `<p class="stop-note">${esc(node.notes)}</p>` : ''}${node.locationStatus === 'pending' ? `<button type="button" class="inline-confirm" data-action="confirm" data-id="${esc(node.id)}">${node.kind === 'meal' ? '补充用餐地点' : '选择具体地点'}${icon('arrow', 15)}</button>` : rvHasLocation(node) ? `<button type="button" class="text-btn rv-locate" data-action="locate" data-id="${esc(node.id)}">${icon('pin', 14)}在地图中查看</button>` : ''}${liveSourceChips(node.sourceIds || [])}</div></article>`).join('')}</div><p class="bottom-note rv-bottom-note">“固定时间”会在调整时保留；手动修改只表示你改过安排，开放和路线仍需核对。</p>`;
}

function rvMapPoints(day, zoom) {
  const nodes = (day.stops || []).map((node, index) => ({node, n: index + 1})).filter(({node}) => rvHasLocation(node));
  if (!nodes.length) return [];
  const xs = nodes.map(({node}) => node.lng), ys = nodes.map(({node}) => node.lat);
  const minX = Math.min(...xs), maxX = Math.max(...xs), minY = Math.min(...ys), maxY = Math.max(...ys);
  const spanX = Math.max(.006, maxX - minX), spanY = Math.max(.006, maxY - minY);
  return nodes.map(point => {
    const x = nodes.length === 1 ? 148 : 65 + (point.node.lng - minX) / spanX * 175;
    const y = nodes.length === 1 ? 122 : 188 - (point.node.lat - minY) / spanY * 125;
    return {...point, x: 153 + (x - 153) * zoom, y: 126 + (y - 126) * zoom};
  });
}

function rvMapLines(points) {
  return points.slice(1).filter((point, index) => point.n === points[index].n + 1).map(point => {
    const previous = points[points.indexOf(point) - 1];
    return `<line x1="${previous.x}" y1="${previous.y}" x2="${point.x}" y2="${point.y}" />`;
  }).join('');
}

function rvMapView(s, day) {
  const stops = day.stops || [], zoom = Math.max(.8, Math.min(1.7, Number(s.mapZoom) || 1));
  const points = rvMapPoints(day, zoom), unknown = stops.filter(node => !rvHasLocation(node));
  const selected = stops.find(node => node.id === s.selectedId) || points[0]?.node || stops[0];
  const pending = unknown.find(node => node.locationStatus === 'pending');
  return `<div class="rv-map-heading"><h2>地点分布</h2><span>${points.length}处已知位置${unknown.length ? ` · ${unknown.length}项未标点` : ''}</span></div><div class="map-canvas rv-map-canvas"><svg viewBox="0 0 340 260" preserveAspectRatio="none" role="img" aria-label="第${s.activeDay + 1}天地理位置示意，非道路导航"><rect width="340" height="260" fill="var(--map-bg)"/><g class="rv-map-lines">${rvMapLines(points)}</g><path d="M24 42V25H41" class="rv-map-corner"/><text x="24" y="55" class="rv-map-north">北</text></svg>${points.map(({node, n, x, y}) => `<button type="button" class="rv-map-marker ${selected?.id === node.id ? 'is-selected' : ''}" data-action="map-node" data-id="${esc(node.id)}" aria-label="第${n}项，${esc(node.title)}" aria-pressed="${selected?.id === node.id}" style="left:${x / 340 * 100}%;top:${y / 260 * 100}%"><span>${n}</span></button>`).join('')}${!points.length ? '<p class="rv-map-empty">这一天还没有具体位置<br><span>先在安排中选择地点，再查看分布</span></p>' : ''}<div class="map-controls"><button type="button" class="icon-btn" data-action="zoom-in" aria-label="放大地点分布">${icon('plus', 18)}</button><button type="button" class="icon-btn" data-action="zoom-out" aria-label="缩小地点分布">${icon('minus', 18)}</button><button type="button" class="icon-btn" data-action="zoom-reset" aria-label="重置地点分布">${icon('target', 18)}</button></div><span class="map-demo">地点示意 · 非道路导航</span></div>${unknown.length ? `<div class="rv-map-unresolved"><span>${icon('pin', 16)}${unknown.length}项具体位置未定，暂未标点</span>${pending ? `<button type="button" class="text-btn" data-action="confirm" data-id="${esc(pending.id)}">去确认${icon('arrow', 15)}</button>` : ''}</div>` : ''}<div class="map-places rv-map-places" aria-label="选择这一天的安排">${stops.map((node, index) => `<button type="button" data-action="map-node" data-id="${esc(node.id)}" aria-pressed="${selected?.id === node.id}"><span>${index + 1}</span>${esc(node.title)}${!rvHasLocation(node) ? '<small>位置待定</small>' : ''}</button>`).join('')}</div>${selected ? `<section class="map-selection rv-map-selection" aria-label="已选安排"><div class="rv-map-selection-title"><button type="button" data-action="node" data-id="${esc(selected.id)}">${esc(selected.title)}${icon('arrow', 17)}</button></div><p>${esc(selected.time || '时间待定')} · ${esc(rvDuration(selected))}</p>${rvBadges(selected)}<p class="rv-map-position">${rvLocationLabel(selected)}</p>${liveSourceChips(selected.sourceIds || [])}<div class="rv-map-selection-actions"><button type="button" class="text-btn" data-action="map-list" data-id="${esc(selected.id)}">回列表定位${icon('arrow', 15)}</button><button type="button" class="text-btn" data-action="node" data-id="${esc(selected.id)}">查看安排</button></div></section>` : ''}<p class="bottom-note rv-bottom-note">编号对应当天列表的原顺序。虚线仅连接相邻的已知位置，不代表实际道路、距离或交通耗时。</p>`;
}

function resultBody(s) {
  if (!s.trip) return '';
  if (s.tab !== 'itinerary') return `<div class="rv-result" data-result-view="overview">${rvOverview(s)}</div>`;
  const day = s.trip.days?.[s.activeDay] || s.trip.days?.[0];
  if (!day) return `<div class="rv-result"><p class="intro">行程还没有日期安排。</p></div>`;
  return `<div class="rv-result" data-result-view="${s.view === 'map' ? 'map' : 'list'}">${rvDayControls(s)}${rvDayHeading(s, day)}${rvTravelNotice(s.trip, s.activeDay)}${s.view === 'map' ? rvMapView(s, day) : rvListView(s, day)}</div>`;
}
