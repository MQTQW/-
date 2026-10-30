/* City research and wish-list renderers. Interactions are owned by app.js. */
const dvCategories = {
  sights: {label: '景点', wish: '加入想去', must: '必去', want: '想去', group: '想去的地方'},
  food: {label: '美食', wish: '加入想吃', must: '必吃', want: '想吃', group: '想吃的美食'},
  experiences: {label: '体验', wish: '加入想体验', must: '必体验', want: '想体验', group: '想试的体验'}
};

function dvCategory(value) {
  return dvCategories[value] ? value : 'sights';
}

function dvCatalog(s) {
  const d = s.discovery || {};
  const catalog = typeof Discovery.catalog === 'function' ? Discovery.catalog(d) : Discovery.catalog;
  const cards = new Map((Array.isArray(catalog) ? catalog : []).map(item => [item.id, item]));
  for (const item of Array.isArray(d.custom) ? d.custom : []) {
    if (!item?.id || cards.has(item.id)) continue;
    cards.set(item.id, {...item, region: item.region || '自选地点', description: item.description || '你手动补充的心愿。', sourceIds: [], sourceType: 'manual', photo: null});
  }
  return [...cards.values()];
}

function dvSelected(s) {
  const d = s.discovery || {};
  if (typeof Discovery.selected === 'function') return Discovery.selected(d) || [];
  return dvCatalog(s).filter(item => d.selected?.[item.id]).map(item => ({...item, priority: d.selected[item.id].priority || 'want'}));
}

function dvDuration(value) {
  const minutes = Math.max(0, Math.round(Number(value) || 0));
  if (!minutes) return '时长待定';
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return `${hours ? `${hours}小时` : ''}${rest ? `${rest}分钟` : ''}`;
}

function dvSourceText(item) {
  if (item.manual || item.sourceType === 'manual' || String(item.id).startsWith('custom') || item.origin === 'manual') return '手动补充';
  const ids = Array.isArray(item.sourceIds) ? item.sourceIds : [];
  if (!ids.length) return '补充灵感';
  return ids.map(id => `${id}资料样例`).join(' · ');
}

function dvSourceChips(item) {
  const ids = Array.isArray(item.sourceIds) ? item.sourceIds : [];
  if (!ids.length || item.manual || item.sourceType === 'manual' || String(item.id).startsWith('custom') || item.origin === 'manual') {
    return `<span class="dv-origin">${esc(dvSourceText(item))}</span>`;
  }
  return `<div class="dv-sources" aria-label="攻略依据">${ids.map(id => `<button type="button" data-action="open-discovery-source" data-id="${esc(id)}">${icon('source', 14)}${esc(id)} · ${id === 'S1' ? '图片攻略样例' : id === 'S2' ? '正文路线样例' : '资料样例'}</button>`).join('')}</div>`;
}

function dvPhoto(item) {
  const approved = {pagoda: 'pagoda', temple: 'temple', bridge: 'canal', broken: 'westlake'};
  const key = approved[item.id];
  if (!key || item.photo !== key || !IMAGE_DATA[key]) return '';
  const caption = key === 'westlake' ? '西湖风景 · 相关配图' : item.title;
  return `<figure class="dv-photo"><img src="${IMAGE_DATA[key]}" alt="${esc(caption)}" width="720" height="430" loading="lazy" decoding="async">${key === 'westlake' ? `<figcaption>${esc(caption)}</figcaption>` : ''}</figure>`;
}

function dvResearchState(s) {
  const state = s.research || {};
  return {status: state.status || s.researchStatus || '', step: Math.max(0, Math.min(2, Number(state.step) || 0)), error: state.error || s.error || ''};
}

function cityBody(s) {
  const d = s.discovery || {};
  const city = String(d.city || '');
  const research = dvResearchState(s);
  const running = research.status === 'running';
  const phases = ['整理攻略', '提取地点与体验', '准备选择'];
  let state = '';
  if (running) {
    state = `<section class="dv-research" aria-labelledby="dv-research-title" aria-busy="true"><h2 id="dv-research-title">正在整理${esc(city || '城市')}的攻略样例</h2><p>固定资料的本地流程演示。</p><ol class="dv-research-steps" aria-label="资料整理阶段">${phases.map((label, index) => `<li class="${index < research.step ? 'is-complete' : index === research.step ? 'is-current' : ''}" ${index === research.step ? 'aria-current="step"' : ''}><span aria-hidden="true">${index < research.step ? icon('check', 14) : index + 1}</span><strong>${label}</strong><small>${index < research.step ? '已完成' : index === research.step ? '正在进行' : '等待整理'}</small></li>`).join('')}</ol><p class="dv-research-status" role="status">${phases[research.step]}中…</p></section>`;
  } else if (research.status === 'failed') {
    state = `<section class="dv-research-error" role="alert"><h2>攻略样例还没整理好</h2><p>${esc(research.error || '本次整理暂时中断，城市输入已保留。')}</p><button type="button" class="dv-text-action" data-action="search-city">重新整理${icon('arrow', 16)}</button></section>`;
  } else if (research.status === 'done' || d.researched) {
    state = `<div class="dv-research-ready" role="status">${icon('check', 18)}<span>资料样例已准备好，可以继续挑选。</span></div>`;
  } else if (s.error) {
    state = `<p class="dv-research-error" role="alert">${esc(s.error)}</p>`;
  }
  return `<div class="dv-city"><div class="dv-title"><h1>这次想去哪个城市？</h1><p>先看看当地有什么，再挑出你喜欢的。</p></div><div class="dv-city-field"><label for="dv-city-input">目的地城市</label><input id="dv-city-input" data-discovery="city" type="text" value="${esc(city)}" maxlength="40" autocomplete="off" placeholder="例如：杭州" aria-describedby="dv-city-note" ${running ? 'readonly' : ''}><p id="dv-city-note">当前提供杭州攻略样例，先体验挑选流程。</p></div>${state}<div class="dv-city-sequence"><h2>从想去的城市，到想要的旅行</h2><ol><li><strong>看攻略，找灵感</strong><span>把景点、美食和体验放在一起看。</span></li><li><strong>挑你喜欢的</strong><span>先收进清单，也能手动补充。</span></li><li><strong>再让 AI 安排</strong><span>补充天数和出行条件后，再规划每天。</span></li></ol></div><p class="dv-fixture-note">固定攻略样例 · 非实时检索</p></div>`;
}

function dvCandidateCard(item, s) {
  const category = dvCategory(item.category);
  const labels = dvCategories[category];
  const picked = !!s.discovery?.selected?.[item.id];
  const photo = dvPhoto(item);
  return `<article class="dv-candidate ${photo ? 'dv-with-photo' : 'dv-text-candidate'} ${category === 'food' ? 'dv-food-candidate' : ''} ${picked ? 'is-selected' : ''}" aria-labelledby="dv-candidate-${esc(item.id)}">
    ${photo}
    <div class="dv-candidate-copy"><div class="dv-candidate-heading"><h2 id="dv-candidate-${esc(item.id)}">${esc(item.title)}</h2>${picked ? `<span class="dv-selected-mark">${icon('check', 14)}已加入</span>` : ''}</div>
      <p class="dv-region">${icon('pin', 14)}${esc(item.region || '具体区域待确认')}</p>
      ${item.description ? `<p class="dv-description">${esc(item.description)}</p>` : ''}
      <p class="dv-stay">${icon('clock', 14)}${Number(item.duration) > 0 ? `建议停留约${esc(dvDuration(item.duration))}` : '停留时长待定'}${category === 'food' ? ' · 餐馆之后再定' : ''}</p>
      ${dvSourceChips(item)}
      <div class="dv-card-actions"><button type="button" class="dv-detail-action" data-action="wish-detail" data-id="${esc(item.id)}" aria-label="查看${esc(item.title)}详情">了解更多${icon('arrow', 15)}</button><button type="button" class="dv-wish-action" data-action="toggle-wish" data-id="${esc(item.id)}" aria-pressed="${picked}" aria-label="${picked ? `从清单移除${esc(item.title)}` : `将${esc(item.title)}加入${labels.want}清单`}">${icon(picked ? 'check' : 'plus', 16)}${picked ? '已加入' : labels.wish}</button></div>
    </div>
  </article>`;
}

function discoveryBody(s) {
  const d = s.discovery || {};
  const category = dvCategory(d.category);
  const catalog = dvCatalog(s);
  const query = String(d.query || '').trim();
  const queryText = query.toLocaleLowerCase('zh-CN');
  const items = catalog.filter(item => dvCategory(item.category) === category).filter(item => !queryText || [item.title, item.region, item.description, item.notes].join(' ').toLocaleLowerCase('zh-CN').includes(queryText));
  const categoryCopy = {sights: '从湖边、寺院到运河，挑你想停留的地方。', food: '先选想吃的，不必现在定餐馆。', experiences: '攻略之外，也给旅行留一点自己的兴趣。'};
  const empty = `<section class="dv-empty-results"><h2>${query ? '这类候选里暂时没找到' : '这类候选暂时为空'}</h2><p>${query ? `换个关键词，或把「${esc(query)}」手动补进清单。` : '有自己的想法，也可以手动补充。'}</p>${query ? '<button type="button" class="dv-text-action" data-action="clear-filter">清除筛选</button>' : ''}</section>`;
  return `<div class="dv-discovery"><div class="dv-title"><h1>先挑想去的，再安排行程</h1><div class="dv-city-meta dv-meta-row"><span>${esc(d.city || '杭州')} · 2篇资料样例</span><button type="button" class="dv-text-action" data-action="discovery-sources">查看依据${icon('arrow', 16)}</button></div></div>
    <div class="dv-category-tabs" role="group" aria-label="候选分类">${Object.entries(dvCategories).map(([key, labels]) => `<button type="button" data-action="discovery-category" data-value="${key}" aria-pressed="${key === category}"><span>${labels.label}</span><small>${catalog.filter(item => dvCategory(item.category) === key).length}</small></button>`).join('')}</div>
    <details class="dv-filter" ${query ? 'open' : ''}><summary>${icon('search',16)}按关键词筛选</summary><label for="dv-candidate-query">筛选当前候选</label><div><input id="dv-candidate-query" data-discovery="query" type="search" value="${esc(d.query || '')}" maxlength="100" placeholder="搜索名称、区域或关键词" autocomplete="off"><button type="button" data-action="filter-candidates">${icon('search', 18)}<span>筛选</span></button></div></details>
    <div class="dv-category-intro"><p>${categoryCopy[category]}</p>${query ? `<span>找到${items.length}项</span>` : ''}</div>
    <div class="dv-candidate-list">${items.length ? items.map(item => dvCandidateCard(item, s)).join('') : empty}</div>
    <div class="dv-manual-add"><p>还有别的${category === 'food' ? '想吃的' : category === 'experiences' ? '想体验的' : '想去的地方'}？</p><button type="button" class="dv-text-action" data-action="add-wish">${icon('plus', 17)}手动补充${dvCategories[category].label}</button></div>
    <p class="dv-fixture-note">固定攻略样例 · 非实时检索${category === 'experiences' ? '；补充灵感不归于原帖。' : ''}</p>
  </div>`;
}

function dvBasketRow(item) {
  const category = dvCategory(item.category);
  const labels = dvCategories[category];
  const must = item.priority === 'must';
  return `<article class="dv-basket-row ${must ? 'is-must' : ''}"><div class="dv-basket-main"><button type="button" class="dv-basket-detail" data-action="wish-detail" data-id="${esc(item.id)}"><strong>${esc(item.title)}</strong><span>${esc(item.region || '区域待确认')}${Number(item.duration) > 0 ? ` · 约${esc(dvDuration(item.duration))}` : ''}</span></button><button type="button" class="dv-remove-action" data-action="remove-wish" data-id="${esc(item.id)}" aria-label="移除${esc(item.title)}">${icon('close', 18)}</button></div><div class="dv-basket-row-foot"><span>${esc(dvSourceText(item))}</span><button type="button" class="dv-priority-action" data-action="priority-wish" data-id="${esc(item.id)}" aria-pressed="${must}" aria-label="${esc(item.title)}${must ? '取消' : '设为'}${labels.must}">${must ? icon('check', 14) : ''}${must ? `已标${labels.must}` : `标为${labels.must}`}</button></div></article>`;
}

function basketBody(s) {
  const items = dvSelected(s);
  const pendingRegions = new Set(['门店待定', '自由安排', '自选地点', '具体区域待确认', '区域待确认']);
  const regions = [...new Set(items.map(item => String(item.region || '').trim()).filter(region => region && !pendingRegions.has(region)))];
  const minutes = items.reduce((sum, item) => sum + Math.max(0, Number(item.duration) || 0), 0);
  const unknown = items.filter(item => !Number(item.duration)).length;
  if (!items.length) {
    return `<div class="dv-basket"><div class="dv-title"><h1>我的想去清单</h1><p>把喜欢的内容放在这里，再决定怎么玩。</p></div><section class="dv-empty-basket"><h2>清单里还没有内容</h2><p>先挑几个想去的地方、想吃的美食，<br>或者想试的体验。</p>${button('去挑喜欢的内容', 'back-discovery')}</section><p class="dv-fixture-note">挑好以后，再补充天数和出行条件。</p></div>`;
  }
  return `<div class="dv-basket"><div class="dv-title"><h1>我的想去清单</h1><p>${items.length}项心愿${regions.length ? ` · ${regions.length}个区域` : ''}</p></div><div class="dv-basket-summary"><p>${minutes ? `内容停留合计约${esc(dvDuration(minutes))}` : '内容停留时长待定'}${unknown && minutes ? `，${unknown}项时长待定` : ''}</p><span>未计算交通，也未分配到每天。</span></div><p class="dv-priority-note">特别想去的可以标记${dvCategories.sights.must}，后续调整时不会被静默移除。</p>
    <div class="dv-basket-groups">${Object.entries(dvCategories).map(([key, labels]) => {
      const group = items.filter(item => dvCategory(item.category) === key);
      return group.length ? `<section class="dv-basket-group" aria-labelledby="dv-group-${key}"><div class="dv-group-heading"><h2 id="dv-group-${key}">${labels.group}</h2><span>${group.length}项</span></div>${group.map(dvBasketRow).join('')}</section>` : '';
    }).join('')}</div>
    <button type="button" class="dv-more-wishes" data-action="back-discovery">${icon('plus', 18)}继续挑选喜欢的${icon('arrow', 17)}</button><button type="button" class="dv-evidence-link" data-action="discovery-sources">${icon('source', 16)}<span>查看攻略依据</span>${icon('arrow', 16)}</button><p class="dv-fixture-note">下一步补充天数和出行条件，再让 AI 安排行程。</p>
  </div>`;
}
