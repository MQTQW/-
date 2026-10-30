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

function rvHasLocation(node) {
  return node.locationStatus !== 'pending' && Number.isFinite(node.lng) && Number.isFinite(node.lat) && Math.abs(node.lng) <= 180 && Math.abs(node.lat) <= 90;
}

function rvLocationLabel(node) {
  if (node.locationStatus === 'pending') return node.kind === 'meal' ? '待选餐馆' : '地点待选';
  if (!rvHasLocation(node)) return node.kind === 'hotel' ? '具体住宿待确认' : '具体位置待确认';
  if (node.locationStatus === 'selected') return '已选择地点';
  if (node.locationStatus === 'manual') return '手动添加地点';
  return '地址已匹配';
}

function rvBadges(node) {
  return `<div class="rv-node-badges">${node.locked ? '<span class="rv-fixed">固定时间</span>' : ''}${node.mustKeep ? '<span class="rv-fixed">你标记的必选</span>' : ''}${node.manuallyEdited ? '<span>已手动修改</span>' : ''}${node.locationStatus === 'pending' ? `<span class="rv-unresolved">${node.kind === 'meal' ? '待选餐馆' : '地点待选'}</span>` : ''}</div>`;
}

function rvScenicCopy(node) {
  const name = node.title || '';
  if (/雷峰|pagoda/.test(`${name} ${node.id}`)) return '沿湖走到塔前，把西湖的开阔景色留给到达后的第一个下午。';
  if (/永福/.test(name)) return '顺着林间小路慢慢走，在寺院和树影间留一段安静的时间。';
  if (/灵隐|temple/.test(`${name} ${node.id}`)) return '走进山林里的寺院，看看古木、石刻和院落。';
  if (/九溪/.test(name)) return '沿溪水与茶园散步，走累了就停下来，感受杭州的山间绿意。';
  if (/小河|canal/.test(`${name} ${node.photo}`)) return '沿运河边的老街慢慢逛，看看临水人家和街巷里的日常。';
  if (/桥/.test(name)) return '走到桥边换个角度看水岸，让沿途风景成为这一天的收尾。';
  if (node.photo === 'westlake') return '沿着湖岸散步，看看远山与水面，为途中小憩留一点余地。';
  return '留些时间看看沿途风景，也给休息和随意走走留出空间。';
}

function rvFeatures(trip) {
  const imageKeys = new Set();
  const subjects = {pagoda: 'pagoda', temple: 'temple', bridge: 'canal', broken: 'westlake', bai: 'westlake'};
  return rvAllStops(trip).filter(({node}) => {
    if (node.kind !== 'visit' || subjects[node.id] !== node.photo || !node.photo || !IMAGE_DATA[node.photo] || imageKeys.has(node.photo)) return false;
    imageKeys.add(node.photo);
    return true;
  }).slice(0, 4);
}

function rvPending(trip) {
  const pending = rvAllStops(trip).filter(({node}) => node.locationStatus === 'pending');
  if (!pending.length) return '';
  return `<section class="rv-pending" aria-label="待确认地点"><div class="rv-pending-heading">${icon('pin', 17)}<strong>${pending.length}处地点待确认</strong></div>${pending.map(({node, dayIndex}) => `<button type="button" data-action="confirm" data-id="${esc(node.id)}"><span><strong>${node.kind === 'meal' ? '待选餐馆' : esc(node.title)}</strong><small>第${dayIndex + 1}天 · ${esc(node.time || '时间待定')}${node.kind === 'meal' ? ` · ${esc(node.title)}` : ''}</small></span><span class="rv-pending-cta">去确认${icon('arrow', 15)}</span></button>`).join('')}</section>`;
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
    ${rvPending(trip)}
    ${features.length ? `<section class="rv-highlights" aria-label="行程亮点"><div class="section-heading"><h2>这趟旅行的亮点</h2>${features.length > 1 ? `<div class="carousel-controls"><button type="button" class="icon-btn" data-action="photo-prev" aria-label="上一张景点卡片">${icon('back', 18)}</button><span id="photo-count" aria-live="polite">${photoIndex + 1}/${features.length}</span><button type="button" class="icon-btn" data-action="photo-next" aria-label="下一张景点卡片">${icon('arrow', 18)}</button></div>` : ''}</div><div class="photo-track rv-photo-track" id="photo-track" aria-label="左右滑动查看景点">${features.map(({node, dayIndex}, index) => `<button type="button" class="photo-card rv-photo-card" data-action="node" data-id="${esc(node.id)}" aria-label="查看${esc(node.title)}的安排"><div class="photo-wrap"><img src="${IMAGE_DATA[node.photo]}" alt="${esc((typeof PHOTO_CREDITS !== 'undefined' && PHOTO_CREDITS.find(c => c.id === node.photo)?.title) || node.title)}" ${index ? 'loading="lazy"' : 'fetchpriority="high"'} width="640" height="400" style="object-position:${node.photo === 'pagoda' ? '65% 45%' : '50% 50%'}"><span class="photo-day">第${dayIndex + 1}天 · ${esc(node.time || '时间待定')}</span></div><div class="photo-caption"><h3>${esc(node.title)}</h3><p>${rvScenicCopy(node)}</p><span>${icon('clock', 14)}${esc(rvDuration(node))}${icon('arrow', 17)}</span></div></button>`).join('')}</div><p class="rv-swipe-hint">${features.length > 1 ? '左右滑动看亮点，点照片查看当天安排' : '点照片查看当天安排'}</p></section>` : ''}
    <section class="quick-facts rv-quick-facts" aria-label="预算与天气"><button type="button" class="fact-row" data-action="budget"><span>${icon('budget', 19)}预算估算</span><strong>${money(rvBudgetTotal(trip.budget))}${icon('arrow', 16)}</strong></button><button type="button" class="fact-row" data-action="weather"><span>${icon('weather', 19)}天气与准备</span><strong>${trip.undated || !days.some(day => day.date) ? '确定日期后查看' : '出发前再核对'}${icon('arrow', 16)}</strong></button></section>
    <section class="rv-daily-summary"><div class="section-heading"><h2>每日速览</h2><span>${days.length}天安排</span></div><div class="day-overview rv-day-overview">${days.map((day, index) => {const visits = (day.stops || []).filter(node => !['arrival', 'hotel', 'return'].includes(node.kind));return `<button type="button" data-action="day-overview" data-index="${index}"><span class="day-number">${index + 1}</span><span class="rv-day-overview-copy"><strong>${esc(day.name || `第${index + 1}天`)}</strong><small>${visits.length ? visits.map(node => esc(node.title)).join(' · ') : '还没有安排，可以从这一天开始添加'}</small>${day.date ? `<small class="rv-day-date">${esc(rvDate(day.date))}</small>` : ''}</span><span class="day-count">${(day.stops || []).length}项${icon('arrow', 15)}</span></button>`;}).join('')}</div></section>
    <p class="bottom-note rv-bottom-note">时间和费用为安排建议；开放、预约与交通仍需在出行前核对。</p>
  </div>`;
}

function rvDayControls(s) {
  const days = s.trip.days || [];
  return `<div class="rv-itinerary-controls"><nav class="day-tabs rv-day-tabs" aria-label="选择行程日期">${days.map((day, index) => `<button type="button" data-action="day" data-index="${index}" aria-pressed="${s.activeDay === index}"><strong>第${index + 1}天</strong>${day.date ? `<span>${esc(rvDate(day.date, true))}</span>` : ''}</button>`).join('')}</nav><div class="rv-view-switch" role="group" aria-label="行程显示方式"><button type="button" data-action="view" data-value="list" aria-pressed="${s.view !== 'map'}">列表</button><button type="button" data-action="view" data-value="map" aria-pressed="${s.view === 'map'}">地图</button></div></div>`;
}

function rvDayHeading(s, day) {
  return `<div class="rv-day-heading"><h1>${esc(day.name || `第${s.activeDay + 1}天`)}</h1><p>${day.date ? `${esc(rvDate(day.date))} · ` : ''}${(day.stops || []).length}项安排${(day.stops || []).some(node => node.locked) ? ' · 含固定时间' : ''}</p></div><div class="rv-day-tools"><button type="button" data-action="add" data-index="${s.activeDay}">${icon('plus', 17)}添加安排</button><button type="button" data-action="ai" data-index="${s.activeDay}">${icon('route', 17)}AI 调整这一天</button></div>`;
}

function rvListView(s, day) {
  const stops = day.stops || [];
  if (!stops.length) return `<div class="rv-empty-day"><h2>给这一天加一项安排</h2><p>可以先记下想去的地方，时间和具体地址稍后再补。</p>${button('添加第一项安排', 'add', {attrs: `data-index="${s.activeDay}"`})}</div>`;
  return `<div class="timeline rv-timeline">${stops.map((node, index) => `${index ? `<div class="leg rv-leg">${icon('route', 14)}<span>交通待核对</span></div>` : ''}<article class="stop rv-stop ${node.locationStatus === 'pending' ? 'pending-stop' : ''} ${s.selectedId === node.id ? 'rv-stop-selected' : ''}" id="node-${esc(node.id)}" data-node-id="${esc(node.id)}"><div class="stop-time">${esc(node.time || '待定')}<span class="timeline-dot" aria-hidden="true"></span></div><div class="stop-info"><div class="stop-heading"><button type="button" class="stop-title" data-action="node" data-id="${esc(node.id)}">${esc(node.title || '未命名安排')}</button><div class="rv-stop-actions"><button type="button" class="icon-btn" data-action="edit" data-id="${esc(node.id)}" aria-label="编辑${esc(node.title)}">${icon('edit', 17)}</button><button type="button" class="icon-btn" data-action="node-menu" data-id="${esc(node.id)}" aria-label="${esc(node.title)}的更多操作">${icon('more', 18)}</button></div></div>${rvBadges(node)}<p class="stop-meta">${esc(rvDuration(node))} · ${rvLocationLabel(node)}</p>${node.notes ? `<p class="stop-note">${esc(node.notes)}</p>` : ''}${node.locationStatus === 'pending' ? `<button type="button" class="inline-confirm" data-action="confirm" data-id="${esc(node.id)}">${node.kind === 'meal' ? '选择具体餐馆' : '选择具体地点'}${icon('arrow', 15)}</button>` : rvHasLocation(node) ? `<button type="button" class="text-btn rv-locate" data-action="locate" data-id="${esc(node.id)}">${icon('pin', 14)}在地图中查看</button>` : ''}${sourceChips(node.sourceIds || [])}</div></article>`).join('')}</div><p class="bottom-note rv-bottom-note">“固定时间”会在调整时保留；手动修改只表示你改过安排，开放和路线仍需核对。</p>`;
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
  return `<div class="rv-map-heading"><h2>地点分布</h2><span>${points.length}处已知位置${unknown.length ? ` · ${unknown.length}项未标点` : ''}</span></div><div class="map-canvas rv-map-canvas"><svg viewBox="0 0 340 260" preserveAspectRatio="none" role="img" aria-label="第${s.activeDay + 1}天地理位置示意，非道路导航"><rect width="340" height="260" fill="var(--map-bg)"/><g class="rv-map-lines">${rvMapLines(points)}</g><path d="M24 42V25H41" class="rv-map-corner"/><text x="24" y="55" class="rv-map-north">北</text></svg>${points.map(({node, n, x, y}) => `<button type="button" class="rv-map-marker ${selected?.id === node.id ? 'is-selected' : ''}" data-action="map-node" data-id="${esc(node.id)}" aria-label="第${n}项，${esc(node.title)}" aria-pressed="${selected?.id === node.id}" style="left:${x / 340 * 100}%;top:${y / 260 * 100}%"><span>${n}</span></button>`).join('')}${!points.length ? '<p class="rv-map-empty">这一天还没有具体位置<br><span>先在安排中选择地点，再查看分布</span></p>' : ''}<div class="map-controls"><button type="button" class="icon-btn" data-action="zoom-in" aria-label="放大地点分布">${icon('plus', 18)}</button><button type="button" class="icon-btn" data-action="zoom-out" aria-label="缩小地点分布">${icon('minus', 18)}</button><button type="button" class="icon-btn" data-action="zoom-reset" aria-label="重置地点分布">${icon('target', 18)}</button></div><span class="map-demo">地点示意 · 非道路导航</span></div>${unknown.length ? `<div class="rv-map-unresolved"><span>${icon('pin', 16)}${unknown.length}项具体位置未定，暂未标点</span>${pending ? `<button type="button" class="text-btn" data-action="confirm" data-id="${esc(pending.id)}">去确认${icon('arrow', 15)}</button>` : ''}</div>` : ''}<div class="map-places rv-map-places" aria-label="选择这一天的安排">${stops.map((node, index) => `<button type="button" data-action="map-node" data-id="${esc(node.id)}" aria-pressed="${selected?.id === node.id}"><span>${index + 1}</span>${esc(node.title)}${!rvHasLocation(node) ? '<small>位置待定</small>' : ''}</button>`).join('')}</div>${selected ? `<section class="map-selection rv-map-selection" aria-label="已选安排"><div class="rv-map-selection-title"><button type="button" data-action="node" data-id="${esc(selected.id)}">${esc(selected.title)}${icon('arrow', 17)}</button></div><p>${esc(selected.time || '时间待定')} · ${esc(rvDuration(selected))}</p>${rvBadges(selected)}<p class="rv-map-position">${rvLocationLabel(selected)}</p>${sourceChips(selected.sourceIds || [])}<div class="rv-map-selection-actions"><button type="button" class="text-btn" data-action="map-list" data-id="${esc(selected.id)}">回列表定位${icon('arrow', 15)}</button><button type="button" class="text-btn" data-action="node" data-id="${esc(selected.id)}">查看安排</button></div></section>` : ''}<p class="bottom-note rv-bottom-note">编号对应当天列表的原顺序。虚线仅连接相邻的已知位置，不代表实际道路、距离或交通耗时。</p>`;
}

function resultBody(s) {
  if (!s.trip) return '';
  if (s.tab !== 'itinerary') return `<div class="rv-result" data-result-view="overview">${rvOverview(s)}</div>`;
  const day = s.trip.days?.[s.activeDay] || s.trip.days?.[0];
  if (!day) return `<div class="rv-result"><p class="intro">行程还没有日期安排。</p></div>`;
  return `<div class="rv-result" data-result-view="${s.view === 'map' ? 'map' : 'list'}">${rvDayControls(s)}${rvDayHeading(s, day)}${s.view === 'map' ? rvMapView(s, day) : rvListView(s, day)}</div>`;
}
