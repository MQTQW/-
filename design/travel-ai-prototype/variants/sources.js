/* 攻略先行：先阅读与选择来源，再检查地点分布和每日安排。 */
function sourcesStart(s, ui) {
  const destination = ui.esc(s.destination || '');
  return {
    body: `
      <section class="a-start" aria-label="先找攻略">
        <div class="a-intro">
          <h1>先看攻略，<br>再决定怎么玩</h1>
          <p>挑你喜欢的内容，再把想去的地方整理成行程。</p>
        </div>

        <div class="a-search-panel">
          <label class="a-field-label" for="a-destination">这次想去哪里</label>
          <div class="a-destination-field">
            ${ui.icon('pin', 23)}
            <input id="a-destination" data-field="destination" type="text" value="${destination}" placeholder="输入城市或目的地" autocomplete="off" aria-label="旅行目的地">
          </div>
          <div class="a-trip-context">${ui.esc(ui.summary(s))}</div>
        </div>

        <details class="a-preferences">
          <summary>
            <span>${ui.icon('calendar', 19)}日期与旅行偏好</span>
            <span class="a-preferences-edit">调整 ${ui.icon('edit', 16)}</span>
          </summary>
          <div class="a-preferences-body">${ui.preferenceFields(s)}</div>
        </details>

        <section class="a-expectations" aria-labelledby="a-before-heading">
          <h2 id="a-before-heading">攻略先读，路线后定</h2>
          <ol class="a-research-steps">
            <li>
              <span class="a-step-number">1</span>
              <div><h3>找到适合这趟旅行的攻略</h3><p>看内容、看出处，再选要参考的几篇。</p></div>
            </li>
            <li>
              <span class="a-step-number">2</span>
              <div><h3>把喜欢的地点放进路线</h3><p>先看地点分布，每天的安排都能修改。</p></div>
            </li>
          </ol>
        </section>
      </section>`,
    footer: `<div class="a-footer">
      ${ui.button('先找旅行攻略', 'collect-sources', { icon: 'search' })}
    </div>`
  };
}

function sourcesSelection(s, ui) {
  const selectedCount = Array.isArray(s.selectedSources) ? s.selectedSources.length : 0;
  const countLabel = selectedCount > 0 ? `已选 ${selectedCount} 篇` : '还没选择攻略';
  return {
    body: `
      <section class="a-selection" aria-label="选择参考攻略">
        <div class="a-intro a-intro-selection">
          <h1>想参考哪些攻略？</h1>
          <p>先读内容，留下你喜欢的几篇。接下来的路线会标出参考出处。</p>
        </div>

        <div class="a-selection-context">${ui.esc(ui.summary(s))}</div>

        <div class="a-sources-heading">
          <h2>为这趟旅行找到的攻略</h2>
          <span class="a-selection-count" role="status" aria-live="polite">${countLabel}</span>
        </div>
        <p class="a-selection-help">${selectedCount > 0 ? '勾选要参考的攻略，也可以打开查看内容。' : '至少选 1 篇，才可以整理路线。'}</p>
        <div class="a-source-catalog">${ui.sourceCards(s)}</div>

        <p class="a-evidence-note">${ui.icon('source', 18)}<span>你选中的攻略会保留在行程里，之后也能回看。</span></p>
      </section>`,
    footer: `<div class="a-footer">
      <p class="a-footer-note">${selectedCount > 0 ? `参考已选的 ${selectedCount} 篇攻略安排路线` : '先选择至少 1 篇攻略'}</p>
      ${ui.button('用选中的攻略规划', 'plan-selected', { icon: 'route', disabled: selectedCount === 0 })}
    </div>`
  };
}

function sourcesDraft(s, ui) {
  const days = Array.isArray(s.days) ? s.days : [];
  const activeDay = Number.isFinite(Number(s.activeDay)) ? Number(s.activeDay) : 0;
  const dayCount = days.length || s.dayCount || 3;
  const dayOverview = days.map((day, index) => {
    const stops = Array.isArray(day.stops) ? day.stops : [];
    const route = stops.map(stop => ui.esc(stop.title || '')).filter(Boolean).join('<span class="a-route-dot" aria-hidden="true">·</span>');
    return `<li class="a-day-overview-row${index === activeDay ? ' a-current-day' : ''}">
      <span class="a-overview-day">第 ${ui.esc(day.day || index + 1)} 天</span>
      <p>${route || '这一天还没有安排地点'}</p>
    </li>`;
  }).join('');

  return {
    body: `
      <section class="a-draft" aria-label="攻略整理成的路线">
        <div class="a-intro a-intro-draft">
          <h1>${ui.esc(s.destination || '目的地')} ${ui.esc(dayCount)} 天路线</h1>
          <p>先看看地点是否顺路，再调整每天想停留的时间。</p>
        </div>
        <div class="a-draft-context">${ui.esc(ui.summary(s))}</div>

        <section class="a-map-section" aria-labelledby="a-map-heading">
          <div class="a-section-heading">
            <h2 id="a-map-heading">先看地点分布</h2>
            <span>按天分组</span>
          </div>
          <div class="a-distribution">${ui.distribution(s)}</div>
        </section>

        ${dayOverview ? `<section class="a-overview" aria-labelledby="a-overview-heading">
          <h2 id="a-overview-heading">每天的路线</h2>
          <ol class="a-day-overview">${dayOverview}</ol>
        </section>` : ''}

        <section class="a-day-details" aria-labelledby="a-details-heading">
          <div class="a-section-heading">
            <h2 id="a-details-heading">把每天安排妥当</h2>
            <span>点击地点可修改</span>
          </div>
          <div class="a-tabs">${ui.dayTabs(s)}</div>
          <div class="a-timeline">${ui.timeline(s)}</div>
        </section>

        <section class="a-route-evidence" aria-labelledby="a-evidence-heading">
          <div class="a-evidence-heading">
            <h2 id="a-evidence-heading">这条路线参考了</h2>
            <button class="a-text-button" type="button" data-action="view-sources">查看攻略 ${ui.icon('arrow', 16)}</button>
          </div>
          <div class="a-source-summary">${ui.sourceSummary(s)}</div>
          <p>每个地点都能找到参考出处，方便你继续判断。</p>
        </section>
      </section>`,
    footer: `<div class="a-footer a-footer-draft">
      ${ui.button('调整偏好', 'change-prefs', { secondary: true, icon: 'edit' })}
      ${ui.button('保存这份行程', 'save', { icon: 'check' })}
    </div>`
  };
}

const SOURCES_CSS = `
  /* 390 px phone: 20 px shell padding leaves 350 px for every section. */
  .a-start,.a-selection,.a-draft {
    --a-ink:#344331;
    --a-soft:#64735E;
    --a-primary:#65794F;
    --a-light:#E7EDDC;
    --a-line:#DFE5D7;
    --a-white:#FFFFFF;
    color:var(--a-ink);
    font-size:15px;
    line-height:1.6;
  }
  .a-start *, .a-selection *, .a-draft * { box-sizing:border-box; }
  .a-intro { margin:0 0 24px; }
  .a-intro h1 { margin:0; font-size:24px; font-weight:700; line-height:1.38; letter-spacing:-.025em; overflow-wrap:anywhere; }
  .a-intro p { margin:11px 0 0; max-width:31ch; font-size:15px; line-height:1.75; color:var(--a-soft); }
  .a-search-panel { padding:20px; border-radius:22px; background:var(--a-white); border:1px solid var(--a-line); }
  .a-field-label { display:block; margin-bottom:10px; color:var(--a-soft); font-size:13px; line-height:1.5; }
  .a-destination-field { display:flex; align-items:center; gap:11px; min-width:0; }
  .a-destination-field>svg { flex:none; color:var(--a-primary); }
  .a-destination-field input { width:100%; min-width:0; border:0; border-radius:0; outline:0; background:transparent; color:var(--a-ink); font:600 24px/1.45 system-ui,-apple-system,"Segoe UI","Microsoft YaHei",sans-serif; caret-color:var(--a-primary); padding:0; }
  .a-destination-field input::placeholder { color:var(--a-soft); font-size:18px; font-weight:400; opacity:1; }
  .a-destination-field:focus-within { outline:2px solid var(--a-primary); outline-offset:7px; border-radius:3px; }
  .a-trip-context { margin-top:17px; padding-top:15px; border-top:1px solid var(--a-line); }
  .a-trip-context>* { margin:0; }
  .a-preferences { margin-top:12px; background:var(--a-white); border:1px solid var(--a-line); border-radius:18px; }
  .a-preferences summary { display:flex; align-items:center; justify-content:space-between; min-height:59px; padding:15px 17px; cursor:pointer; list-style:none; font-size:14px; }
  .a-preferences summary::-webkit-details-marker { display:none; }
  .a-preferences summary>span { display:flex; align-items:center; gap:8px; }
  .a-preferences-edit { font-size:13px; color:var(--a-primary); flex:none; margin-left:8px; }
  .a-preferences[open] summary { border-bottom:1px solid var(--a-line); }
  .a-preferences-body { padding:18px 16px; }
  .a-preferences-body .preferences { margin:0; }
  .a-preferences summary:focus-visible,.a-text-button:focus-visible { outline:2px solid var(--a-primary); outline-offset:3px; }
  .a-expectations { margin-top:30px; }
  .a-expectations h2,.a-sources-heading h2,.a-section-heading h2,.a-overview h2,.a-evidence-heading h2 { margin:0; font-size:17px; line-height:1.5; font-weight:650; letter-spacing:-.015em; }
  .a-research-steps { margin:16px 0 0; padding:0; list-style:none; }
  .a-research-steps li { display:flex; align-items:flex-start; gap:13px; margin:0; padding:0 0 17px; }
  .a-research-steps li+li { padding-top:2px; }
  .a-step-number { display:flex; align-items:center; justify-content:center; width:24px; height:24px; flex:none; margin-top:1px; border:1px solid #B7C5A8; border-radius:50%; color:var(--a-primary); font-size:12px; font-variant-numeric:tabular-nums; }
  .a-research-steps h3 { margin:0; font-size:14px; font-weight:600; line-height:1.7; }
  .a-research-steps p { margin:3px 0 0; font-size:13px; line-height:1.7; color:var(--a-soft); }
  .a-footer { display:grid; gap:8px; width:100%; }
  .a-footer>button { width:100%; min-width:0; }
  .a-footer-note { margin:0 0 2px; text-align:center; color:#64735E; font-size:12px; line-height:1.5; }
  .a-footer-draft { grid-template-columns:.88fr 1.35fr; gap:10px; }
  .a-footer-draft>button { padding-left:10px; padding-right:10px; white-space:nowrap; }
  .a-intro-selection { margin-bottom:17px; }
  .a-selection-context { margin-bottom:26px; }
  .a-selection-context>* { margin:0; }
  .a-sources-heading { display:flex; flex-wrap:wrap; align-items:center; justify-content:space-between; gap:8px; }
  .a-sources-heading h2 { font-size:16px; }
  .a-selection-count { color:var(--a-primary); font-size:13px; line-height:1.5; white-space:nowrap; }
  .a-selection-help { margin:8px 0 15px; color:var(--a-soft); font-size:13px; line-height:1.7; }
  .a-source-catalog { min-width:0; }
  .a-source-catalog .source-list { margin:0; }
  .a-source-catalog .source-card { overflow-wrap:anywhere; }
  .a-evidence-note { display:flex; align-items:flex-start; gap:9px; margin:20px 0 4px; color:var(--a-soft); font-size:13px; line-height:1.7; }
  .a-evidence-note svg { flex:none; margin-top:2px; }
  .a-intro-draft { margin-bottom:14px; }
  .a-draft-context { margin-bottom:25px; }
  .a-draft-context>* { margin:0; }
  .a-section-heading { display:flex; align-items:baseline; justify-content:space-between; gap:10px; margin-bottom:12px; }
  .a-section-heading>span { color:var(--a-soft); font-size:12px; white-space:nowrap; }
  .a-distribution { min-width:0; }
  .a-distribution .distribution { margin:0; }
  .a-distribution svg { display:block; max-width:100%; }
  .a-overview { margin-top:25px; }
  .a-day-overview { padding:0; margin:13px 0 0; list-style:none; }
  .a-day-overview-row { display:grid; grid-template-columns:51px minmax(0,1fr); align-items:start; column-gap:12px; padding:11px 0; border-bottom:1px solid var(--a-line); }
  .a-day-overview-row:first-child { padding-top:0; }
  .a-overview-day { padding-top:1px; color:var(--a-soft); font-size:12px; line-height:1.8; white-space:nowrap; }
  .a-day-overview-row p { margin:0; font-size:13px; line-height:1.85; overflow-wrap:anywhere; }
  .a-route-dot { display:inline; margin:0 6px; color:#8C9A7E; }
  .a-current-day .a-overview-day { color:var(--a-primary); font-weight:650; }
  .a-day-details { margin-top:29px; }
  .a-tabs { margin-bottom:18px; }
  .a-tabs .day-tabs { margin:0; }
  .a-timeline .timeline { margin:0; }
  .a-route-evidence { margin-top:27px; padding:18px; border-radius:22px; background:var(--a-light); }
  .a-evidence-heading { display:flex; align-items:center; justify-content:space-between; gap:8px; margin-bottom:13px; }
  .a-evidence-heading h2 { font-size:15px; }
  .a-text-button { display:inline-flex; justify-content:center; align-items:center; gap:4px; flex:none; padding:3px 0; min-height:44px; border:0; border-radius:3px; background:transparent; color:var(--a-primary); font:inherit; font-size:12px; cursor:pointer; }
  .a-text-button svg { flex:none; }
  .a-source-summary { font-size:13px; line-height:1.7; }
  .a-route-evidence>p { margin:12px 0 0; padding-top:12px; border-top:1px solid #CDD7BF; color:var(--a-soft); font-size:12px; line-height:1.75; }
  @media (hover:hover) {
    .a-preferences summary:hover { background:#F3F6EE; }
    .a-text-button:hover { color:var(--a-ink); text-decoration:underline; text-underline-offset:4px; }
  }
  @media (max-width:360px) {
    .a-search-panel { padding:17px; }
    .a-section-heading { flex-wrap:wrap; gap:4px; }
    .a-footer-draft { grid-template-columns:1fr 1.35fr; gap:8px; }
    .a-footer-draft>button { font-size:13px; }
    .a-evidence-heading { align-items:flex-start; }
  }
`;
