/* Low-frequency mobile result pages. Events are delegated by the application shell. */

function auxBudgetValue(value) {
  const number = Number(value);
  return Number.isFinite(number) ? Math.min(100000, Math.max(0, number)) : 0;
}

function auxItineraryPlaces(s) {
  const catalog = Array.isArray(s.places) ? s.places : [];
  const places = new Map();
  (Array.isArray(s.days) ? s.days : []).forEach((day, index) => {
    (Array.isArray(day.stops) ? day.stops : []).forEach(stop => {
      const match = catalog.find(place =>
        (stop.id && place.id === stop.id) || (stop.title && place.title === stop.title)
      ) || {};
      const key = String(stop.id || match.id || stop.title || '');
      if (!key) return;
      const previous = places.get(key);
      const sourceIds = [...new Set([
        ...(Array.isArray(match.sourceIds) ? match.sourceIds : []),
        ...(Array.isArray(stop.sourceIds) ? stop.sourceIds : []),
        ...(previous ? previous.sourceIds : [])
      ])];
      const dayLabel = `第${day.day || index + 1}天`;
      places.set(key, {
        ...match,
        ...stop,
        sourceIds,
        auxDays: [...new Set([...(previous ? previous.auxDays : []), dayLabel])]
      });
    });
  });
  if (!places.size) {
    catalog.forEach((place, index) => places.set(String(place.id || place.title || index), {
      ...place,
      sourceIds: Array.isArray(place.sourceIds) ? place.sourceIds : [],
      auxDays: []
    }));
  }
  return [...places.values()];
}

function budgetView(s, u) {
  const categories = [
    {key: 'stay', label: '住宿', note: '按整趟行程填写'},
    {key: 'food', label: '餐饮', note: '正餐、饮品与小食'},
    {key: 'transport', label: '交通', note: '市内通勤与接驳'},
    {key: 'tickets', label: '门票与预约', note: '待核对，不代表免费'},
    {key: 'misc', label: '其他', note: '留给临时开销'}
  ];
  const budget = s.budget || {};
  const total = categories.reduce((sum, item) => sum + auxBudgetValue(budget[item.key]), 0);
  const rows = categories.map(item => `
    <div class="aux-budget-row">
      <div class="aux-budget-label">
        <label for="aux-budget-${item.key}">${u.esc(item.label)}</label>
        <p id="aux-budget-note-${item.key}">${u.esc(item.note)}</p>
      </div>
      <div class="aux-money-field">
        <span aria-hidden="true">¥</span>
        <input id="aux-budget-${item.key}" type="number" inputmode="decimal"
          min="0" max="100000" step="1" value="${auxBudgetValue(budget[item.key])}"
          data-budget="${item.key}" aria-label="${u.esc(item.label)}预算，人民币元"
          aria-describedby="aux-budget-note-${item.key}" autocomplete="off">
      </div>
    </div>`).join('');
  return {
    title: '行程预算',
    body: `<div class="aux-page aux-budget-page">
      <p class="aux-intro">预算草稿 · 示例金额<br>按整趟行程合计，可逐项调整。</p>
      <section class="aux-budget-total" aria-labelledby="aux-budget-total-label">
        <h2 id="aux-budget-total-label">预算合计</h2>
        <p id="budget-total" class="aux-total-value" aria-live="polite" aria-atomic="true">${u.esc(u.formatMoney(total))}</p>
        <p class="aux-note">人民币 · 整趟行程</p>
      </section>
      <section class="aux-budget-fields" aria-label="编辑分类预算">${rows}</section>
      <p class="aux-footnote">这些金额用于安排预算，未接入实时报价。住宿、车票及门票费用请按实际预订信息更新；门票与预约项目待核对，填写 0 元不代表免费。</p>
    </div>`,
    footer: u.button('应用预算', 'apply-budget')
  };
}

function weatherView(s, u) {
  const isNear = s.weatherPreview === 'near';
  const demo = [
    {weather: '多云', icon: 'cloud', high: 24, low: 17, rain: '降雨概率 20%'},
    {weather: '小雨', icon: 'rain', high: 22, low: 16, rain: '降雨概率 60%'},
    {weather: '晴', icon: 'sun', high: 25, low: 18, rain: '降雨概率 10%'}
  ];
  const days = Array.isArray(s.days) ? s.days : [];
  const forecast = demo.map((item, index) => {
    const day = days[index] || {};
    const rawDate = String(day.date || day.dateLabel || '');
    const dateParts = rawDate.match(/^\d{4}-(\d{2})-(\d{2})$/);
    const date = dateParts ? `${Number(dateParts[1])}月${Number(dateParts[2])}日` : rawDate;
    return `<li class="aux-weather-row">
      <div class="aux-weather-date">
        <strong>${u.esc(date || `第${day.day || index + 1}天`)}</strong>
        <span>${date ? `第${u.esc(day.day || index + 1)}天` : '日期待定'}</span>
      </div>
      <div class="aux-weather-condition">
        <span class="aux-weather-symbol" aria-hidden="true">${u.icon(item.icon, 23)}</span>
        <span>${u.esc(item.weather)}</span>
      </div>
      <div class="aux-weather-detail">
        <strong>${item.high}° <span>/ ${item.low}°</span></strong>
        <span>${u.esc(item.rain)}</span>
      </div>
    </li>`;
  }).join('');
  return {
    title: '天气与出行准备',
    body: `<div class="aux-page aux-weather-page">
      ${isNear ? `
        <p class="aux-intro">展示示例，非实时预报。<br>以下日期、天气、气温与降雨概率仅用于查看界面。</p>
        <section aria-labelledby="aux-weather-heading">
          <div class="aux-section-heading"><h2 id="aux-weather-heading">三日天气示例</h2><span>高温 / 低温</span></div>
          <ul class="aux-weather-list" aria-label="三日天气展示示例，非实时预报">${forecast}</ul>
        </section>
        <p class="aux-footnote">临近出发时，再根据实际天气安排衣物、雨具和室内备选地点。</p>
      ` : `
        <section class="aux-weather-empty" aria-labelledby="aux-weather-heading">
          <span class="aux-empty-symbol" aria-hidden="true">${u.icon('weather', 36)}</span>
          <h2 id="aux-weather-heading">临近出发再看天气</h2>
          <p>距离出发还早，暂不显示逐日预测。现在可以先准备随身物品。</p>
        </section>
        <section class="aux-prep-preview" aria-labelledby="aux-prep-heading">
          <h2 id="aux-prep-heading">先把出行准备好</h2>
          <ul class="aux-simple-list">
            <li>证件、预约信息与充电设备</li>
            <li>舒适步行鞋与便携雨具</li>
            <li>出发前复查天气和开放时间</li>
          </ul>
        </section>
      `}
    </div>`,
    footer: u.button('查看出行准备清单', 'weather-prep')
  };
}

function relationsView(s, u) {
  const mode = s.relationMode === 'source' ? 'source' : 'place';
  const sources = Array.isArray(s.sources) ? s.sources : [];
  const places = auxItineraryPlaces(s);
  const selected = sources.find(source => source.id === s.relationSelected) || sources[0];
  const toggle = `<div class="aux-segment" role="group" aria-label="查看关联的方式">
    <button type="button" class="aux-segment-button" data-action="relation-mode" data-mode="place" aria-pressed="${mode === 'place'}">按地点</button>
    <button type="button" class="aux-segment-button" data-action="relation-mode" data-mode="source" aria-pressed="${mode === 'source'}">按攻略</button>
  </div>`;
  let content;
  if (mode === 'source') {
    const choices = sources.map(source => `
      <button type="button" class="aux-source-select" data-action="relation-select" data-id="${u.esc(source.id)}" aria-pressed="${selected && selected.id === source.id}">
        <span class="aux-source-select-copy"><strong>${u.esc(source.title || '未命名攻略')}</strong><span>${u.esc(source.author || '作者未提供')}</span></span>
        <span class="aux-source-select-mark" aria-hidden="true">${selected && selected.id === source.id ? u.icon('check', 20) : u.icon('arrow', 18)}</span>
      </button>`).join('');
    const linked = selected ? places.filter(place => place.sourceIds.includes(selected.id)) : [];
    const linkedRows = linked.map(place => `
      <li class="aux-linked-place"><span aria-hidden="true">${u.icon('pin', 18)}</span><div><h3>${u.esc(place.title || '未命名地点')}</h3><p>${u.esc(place.auxDays.join(' · ') || '已加入当前行程')}</p></div></li>`).join('');
    content = `<section aria-label="选择一篇攻略"><div class="aux-source-picker">${choices || '<p class="aux-empty-copy">当前行程还没有关联攻略。</p>'}</div></section>
      ${selected ? `<section class="aux-source-context" aria-labelledby="aux-relation-summary">
        <h2 id="aux-relation-summary">这篇攻略关联 ${linked.length} 个地点</h2>
        <p>${u.esc(selected.description || selected.excerpt || '选择攻略后，可在这里查看它与行程地点的关联。')}</p>
        ${selected.excerpt && selected.description && selected.excerpt !== selected.description ? `<p class="aux-relation-excerpt">${u.esc(selected.excerpt)}</p>` : ''}
        <ul class="aux-linked-places">${linkedRows || '<li class="aux-empty-copy">这篇攻略暂未关联行程地点。</li>'}</ul>
      </section>` : ''}`;
  } else {
    const rows = places.map(place => `
      <li class="aux-place-relation">
        <div class="aux-place-relation-title"><h3>${u.esc(place.title || '未命名地点')}</h3><span>${u.esc(place.auxDays.join(' · '))}</span></div>
        <div class="aux-place-sources">${place.sourceIds.length ? u.sourceChips(place.sourceIds) : '<span class="aux-note">尚未关联攻略</span>'}</div>
      </li>`).join('');
    content = `<section aria-labelledby="aux-relations-heading"><div class="aux-section-heading"><h2 id="aux-relations-heading">行程地点</h2><span>${places.length} 个</span></div>
      <ul class="aux-place-relations">${rows || '<li class="aux-empty-copy">添加行程地点后，这里会显示对应攻略。</li>'}</ul>
    </section>`;
  }
  return {
    title: '地点与攻略关联',
    body: `<div class="aux-page aux-relations-page"><p class="aux-intro">从地点找到参考攻略，也能看看一篇攻略支持了哪些行程安排。</p>${toggle}${content}</div>`,
    footer: u.button('返回行程', 'back', {secondary: true})
  };
}

function sourcesView(s, u) {
  const sources = Array.isArray(s.sources) ? s.sources : [];
  const cards = sources.map(source => {
    const description = source.description || source.excerpt || '暂无内容摘要。';
    return `<article class="aux-source-card">
      <div class="aux-source-card-heading"><span aria-hidden="true">${u.icon('source', 20)}</span><h2>${u.esc(source.title || '未命名攻略')}</h2></div>
      <p class="aux-source-meta"><span>${u.esc(source.author || '作者未提供')}</span><span>${u.esc(source.date || '发布日期未提供')}</span></p>
      <p class="aux-source-description">${u.esc(description)}</p>
      ${source.excerpt && source.description && source.excerpt !== source.description ? `<div class="aux-source-excerpt"><h3>相关片段</h3><p>${u.esc(source.excerpt)}</p></div>` : ''}
      <div class="aux-source-card-action">${u.button('查看相关地点', 'source-places', {secondary: true, attrs: `data-id="${u.esc(source.id)}"`})}</div>
    </article>`;
  }).join('');
  return {
    title: '攻略来源',
    body: `<div class="aux-page aux-sources-page"><p class="aux-intro">当前行程参考了 ${sources.length} 篇攻略。<br>打开相关地点，可以追溯它们在行程中的安排。</p>
      <div class="aux-source-cards">${cards || '<p class="aux-empty-copy">当前行程还没有添加攻略来源。</p>'}</div>
      <p class="aux-footnote">此处列出行程使用的攻略内容来源。开放时间、预约及费用仍需在出发前核对。</p>
    </div>`,
    footer: u.button('返回行程', 'back', {secondary: true})
  };
}

const AUX_CSS = `
.aux-page{display:grid;gap:24px;min-width:0;padding:4px 0 12px;color:var(--ink);font-size:15px;line-height:1.65;overflow-wrap:anywhere}
.aux-page,.aux-page *{box-sizing:border-box}
.aux-page p,.aux-page h2,.aux-page h3,.aux-page ul{margin:0}
.aux-page h2{font-size:18px;font-weight:650;line-height:1.45;letter-spacing:0}
.aux-page h3{font-size:15px;font-weight:650;line-height:1.5}
.aux-page ul{padding:0;list-style:none}
.aux-page svg{display:block;flex:none}
.aux-page button,.aux-page input{font:inherit}
.aux-page button{touch-action:manipulation;-webkit-tap-highlight-color:transparent}
.aux-page button:focus-visible,.aux-page input:focus-visible{outline:3px solid var(--primary);outline-offset:3px}
.aux-page ::selection{color:var(--ink);background:var(--light)}
.aux-intro{color:var(--muted);font-size:15px;line-height:1.7}
.aux-note{color:var(--muted);font-size:13px;line-height:1.5}
.aux-footnote{color:var(--muted);font-size:13px;line-height:1.7}
.aux-budget-total{padding:22px;border-radius:22px;background:var(--light)}
.aux-budget-total h2{font-size:15px;font-weight:550}
.aux-budget-total .aux-total-value{margin:7px 0 6px;font-size:36px;font-weight:700;line-height:1.2;letter-spacing:-.025em;font-variant-numeric:tabular-nums}
.aux-budget-fields{display:grid}
.aux-budget-row{display:grid;grid-template-columns:minmax(0,1fr) 112px;gap:16px;align-items:center;padding:17px 0;border-bottom:1px solid var(--line)}
.aux-budget-row:first-child{padding-top:0}
.aux-budget-row:last-child{padding-bottom:0;border-bottom:0}
.aux-budget-label label{display:block;font-weight:650;line-height:1.5}
.aux-budget-label p{margin-top:4px;color:var(--muted);font-size:13px;line-height:1.5}
.aux-money-field{position:relative;min-width:0}
.aux-money-field>span{position:absolute;left:12px;top:50%;transform:translateY(-50%);color:var(--muted);pointer-events:none}
.aux-money-field input{width:100%;min-width:0;min-height:48px;padding:10px 11px 10px 27px;border:1px solid var(--line);border-radius:14px;background:var(--surface);color:var(--ink);font-size:16px;line-height:1.4;font-variant-numeric:tabular-nums;text-align:right;caret-color:var(--primary)}
.aux-section-heading{display:flex;align-items:baseline;justify-content:space-between;gap:12px;margin-bottom:12px}
.aux-section-heading>span{flex:none;color:var(--muted);font-size:13px}
.aux-weather-empty{display:grid;justify-items:start;gap:14px;padding:24px 22px;background:var(--surface);border-radius:22px}
.aux-empty-symbol{display:block;color:var(--primary);margin-bottom:4px}
.aux-weather-empty h2{font-size:22px;line-height:1.4}
.aux-weather-empty p{color:var(--muted);font-size:15px;line-height:1.7}
.aux-prep-preview h2{margin-bottom:13px}
.aux-simple-list{display:grid;gap:12px}
.aux-simple-list li{position:relative;padding-left:16px;color:var(--muted)}
.aux-simple-list li::before{content:'';position:absolute;left:1px;top:11px;width:4px;height:4px;background:var(--muted);border-radius:50%}
.aux-weather-list{overflow:hidden;border-radius:22px;background:var(--surface)}
.aux-weather-row{display:grid;grid-template-columns:minmax(64px,.9fr) minmax(58px,.8fr) minmax(0,1.4fr);gap:8px;align-items:center;padding:19px 16px;border-bottom:1px solid var(--line)}
.aux-weather-row:last-child{border-bottom:0}
.aux-weather-date,.aux-weather-detail{display:grid;gap:4px;min-width:0}
.aux-weather-date strong{font-size:15px;font-weight:650;line-height:1.4}
.aux-weather-date>span,.aux-weather-detail>span{color:var(--muted);font-size:12px;line-height:1.5}
.aux-weather-condition{display:grid;gap:5px;justify-items:center;font-size:13px;line-height:1.4}
.aux-weather-symbol{color:var(--primary)}
.aux-weather-detail{text-align:right;font-variant-numeric:tabular-nums}
.aux-weather-detail strong{font-size:18px;line-height:1.4;font-weight:650;white-space:nowrap}
.aux-weather-detail strong span{color:var(--muted);font-size:15px;font-weight:400}
.aux-weather-detail>span{font-size:12px;white-space:nowrap}
.aux-segment{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:4px;padding:4px;background:var(--light);border-radius:16px}
.aux-segment-button{min-height:46px;padding:10px 12px;border:0;border-radius:12px;background:transparent;color:var(--muted);font-size:15px;font-weight:600;line-height:1.4}
.aux-segment-button[aria-pressed=true]{background:var(--surface);color:var(--ink)}
.aux-place-relations{display:grid}
.aux-place-relation{display:grid;gap:10px;padding:17px 0;border-bottom:1px solid var(--line)}
.aux-place-relation:first-child{padding-top:2px}
.aux-place-relation:last-child{border-bottom:0;padding-bottom:0}
.aux-place-relation-title{display:flex;align-items:baseline;justify-content:space-between;gap:14px}
.aux-place-relation-title>span{flex:none;max-width:43%;color:var(--muted);font-size:12px;line-height:1.5;text-align:right}
.aux-place-sources{display:flex;flex-wrap:wrap;align-items:center;gap:8px;min-width:0}
.aux-source-picker{display:grid;gap:10px}
.aux-source-select{display:flex;align-items:center;justify-content:space-between;gap:16px;width:100%;min-height:76px;padding:15px 16px;border:1px solid var(--line);border-radius:18px;background:var(--surface);color:var(--ink);text-align:left}
.aux-source-select[aria-pressed=true]{border-color:var(--primary);background:var(--light)}
.aux-source-select-copy{display:grid;gap:5px;min-width:0}
.aux-source-select-copy strong{font-size:15px;font-weight:650;line-height:1.55;overflow-wrap:anywhere}
.aux-source-select-copy>span{color:var(--muted);font-size:13px;line-height:1.4}
.aux-source-select-mark{color:var(--primary);flex:none}
.aux-source-context>p{margin-top:12px;color:var(--muted);font-size:14px;line-height:1.75}
.aux-source-context .aux-relation-excerpt{padding:14px 16px;border-radius:16px;background:var(--surface);color:var(--ink)}
.aux-page .aux-linked-places{display:grid;margin-top:19px}
.aux-linked-place{display:flex;align-items:flex-start;gap:12px;padding:14px 0;border-top:1px solid var(--line)}
.aux-linked-place>span{margin-top:3px;color:var(--primary);flex:none}
.aux-linked-place p{margin-top:4px;color:var(--muted);font-size:13px}
.aux-empty-copy{padding:18px 0;color:var(--muted);font-size:15px;line-height:1.7}
.aux-source-cards{display:grid;gap:18px}
.aux-source-card{padding:21px 20px;background:var(--surface);border-radius:22px;min-width:0}
.aux-source-card-heading{display:flex;align-items:flex-start;gap:10px}
.aux-source-card-heading>span{margin-top:3px;color:var(--primary);flex:none}
.aux-source-card-heading h2{min-width:0;font-size:18px;line-height:1.55}
.aux-page .aux-source-meta{display:flex;flex-wrap:wrap;gap:5px 14px;margin-top:10px;color:var(--muted);font-size:12px;line-height:1.5}
.aux-page .aux-source-description{margin-top:16px;font-size:15px;line-height:1.8}
.aux-source-excerpt{margin-top:16px;padding-top:15px;border-top:1px solid var(--line)}
.aux-source-excerpt h3{margin-bottom:6px;font-size:13px;font-weight:550;color:var(--muted)}
.aux-source-excerpt p{color:var(--muted);font-size:14px;line-height:1.8}
.aux-source-card-action{margin-top:20px}
.aux-source-card-action>button{width:100%;min-height:46px;border-radius:14px}
@media(hover:hover) and (pointer:fine){.aux-source-select:hover,.aux-segment-button:hover{outline:1px solid var(--primary);outline-offset:1px}.aux-money-field input:hover{border-color:var(--primary)}}
@media(max-width:350px){.aux-budget-row{grid-template-columns:minmax(0,1fr) 104px;gap:12px}.aux-weather-row{padding:18px 12px;gap:6px;grid-template-columns:minmax(58px,.9fr) minmax(50px,.8fr) minmax(0,1.4fr)}.aux-weather-date strong{font-size:14px}.aux-weather-detail strong{font-size:17px}.aux-source-card{padding:20px 18px}}
`;
