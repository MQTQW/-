/* Creation, local draft list and generation-state renderers. Events live in app.js. */
function cvDateText(date, undated) {
  if (undated || !date) return '日期待定';
  const parts = String(date).split('-');
  return parts.length === 3 ? `${Number(parts[1])}月${Number(parts[2])}日出发` : String(date);
}

function cvPendingCount(trip) {
  if (!trip) return 0;
  if (Number.isFinite(trip.pendingCount)) return trip.pendingCount;
  const nodes = Array.isArray(trip.nodes) ? trip.nodes : (Array.isArray(trip.days) ? trip.days : []).flatMap(day => day.nodes || day.stops || []);
  return nodes.filter(node => node.locationStatus ? node.locationStatus === 'pending' : node.pending || node.status === 'pending' || node.status === 'unresolved' || node.confirmed === false).length;
}

function cvDayCount(trip, fallback = 3) {
  if (Array.isArray(trip?.days)) return trip.days.length;
  return Number(trip?.dayCount || trip?.days || fallback);
}

function homeBody(s) {
  const f = s.form || {};
  const trip = s.trip;
  const job = s.job;
  const hasJob = job && ['running', 'paused', 'failed'].includes(job.status);
  const pending = cvPendingCount(trip);
  let content = typeof wishResumeMarkup==='function'?wishResumeMarkup():'';
  if (hasJob) {
    const jobLabels = {running: '行程正在整理', paused: '规划已暂停', failed: '规划需要继续处理'};
    const jobCopy = {running: '可以回到进度页，查看这次规划。', paused: '旅行条件已保留，可以接着整理。', failed: '已保留旅行条件，可以重试或先形成草稿。'};
    content += `<section class="cv-task" aria-label="未完成的规划"><div class="cv-row-title">${icon('clock', 19)}<h2>${jobLabels[job.status]}</h2></div><p>${esc(f.destination || '目的地待填写')} · ${esc(f.days || 3)}天 · ${esc(cvDateText(f.date, f.undated))}</p><p class="cv-secondary-copy">${jobCopy[job.status]}</p><button type="button" class="cv-inline-action" data-action="resume-job">${job.status === 'running' ? '查看进度' : '继续规划'}${icon('arrow', 17)}</button></section>`;
  }
  if (s.editor) {
    content += `<section class="cv-resume" aria-label="未完成的编辑"><div><h2>还有一处修改没完成</h2><p>继续编辑，保留刚才填写的内容。</p></div><button type="button" class="cv-inline-action" data-action="resume-editor">继续编辑${icon('arrow', 17)}</button></section>`;
  }
  if (trip) {
    content += `<button type="button" class="cv-saved-trip" data-action="open-trip"><img src="${IMAGE_DATA.westlake}" alt="" width="78" height="92"><span class="cv-saved-copy"><strong>${esc(trip.title || '杭州旅行草稿')}</strong><span>${esc(cvDateText(trip.date || f.date, trip.undated))} · ${esc(cvDayCount(trip, f.days))}天</span><span class="${pending ? 'cv-pending-copy' : ''}">${pending ? `${pending}处地点待确认` : '查看行程与安排'}</span></span>${icon('arrow', 18)}</button>`;
    content += `<p class="cv-local-note">行程保存在当前浏览器，可随时回来继续修改。</p>`;
  }
  if (!trip && !hasJob && !s.editor && !s.discovery?.researched && !s.research) {
    content = `<section class="cv-empty"><h2>下一次旅行，从这里开始</h2><p>输入一个城市，看看当地的攻略，<br>再挑选自己喜欢的内容。</p>${button('新建行程', 'new-trip')}<button type="button" class="cv-sample-action" data-action="use-scenario">先看看杭州攻略${icon('arrow', 17)}</button></section>`;
  } else {
    content += `<div class="cv-new-trip">${button('新建行程', 'new-trip', {secondary: true})}</div>`;
  }
  return `<div class="cv-home"><div class="cv-screen-title"><h1>我的行程</h1><p>把想去的地方，变成自己的安排。</p></div>${content}<p class="cv-demo-note">杭州演示数据 · 规划与地点均为交互样例</p></div>`;
}

function createBody(s) {
  const f = s.form || {};
  const expanded = !!s.conditionsExpanded;
  const hotel = String(f.hotel || '').trim();
  const conditionSummary = [hotel ? '已填酒店' : '酒店待定', f.arrival ? `${f.arrival}抵达` : '', f.pace || '舒缓'].filter(Boolean).join(' · ');
  const option = (value, current) => `<option value="${esc(value)}" ${value === current ? 'selected' : ''}>${esc(value)}</option>`;
  return `<div class="cv-create">
    <div class="cv-screen-title"><h1>补充条件，再安排行程</h1><p>地点已经由你选好，现在补充时间和已确定的安排。</p></div>
    <div class="dv-choice-summary"><strong>已选 ${Discovery.selected(s.discovery).length} 项内容</strong><p>只使用你选中的景点、美食和体验。还可以返回清单调整。</p><button type="button" class="text-btn" data-action="back-basket">返回我的想去清单</button></div>
    <section class="cv-required" aria-labelledby="cv-required-title">
      <h2 id="cv-required-title">确认行程条件</h2>
      <div class="cv-field"><label for="cv-destination">目的地</label><input id="cv-destination" data-form="destination" type="text" readonly value="${esc(f.destination || '')}" autocomplete="off" maxlength="40" placeholder="填写城市" aria-describedby="cv-destination-note"><p class="cv-field-note" id="cv-destination-note">更换城市请返回发现页重新整理。</p></div>
      <div class="cv-date-days">
        <div class="cv-field"><label for="cv-date">出发日期</label><input id="cv-date" data-form="date" type="date" value="${esc(f.date || '')}" ${f.undated ? 'disabled' : ''} aria-describedby="cv-undated-note"></div>
        <div class="cv-field"><label for="cv-days">玩几天</label><div class="cv-number-field"><input id="cv-days" data-form="days" type="number" inputmode="numeric" min="1" max="5" step="1" value="${esc(f.days || 3)}" aria-describedby="cv-days-note"><span aria-hidden="true">天</span></div></div>
      </div>
      <div class="cv-date-options"><label class="cv-checkbox" for="cv-undated"><input id="cv-undated" data-form="undated" type="checkbox" ${f.undated ? 'checked' : ''}><span id="cv-undated-note">还没定日期</span></label><span id="cv-days-note">支持1–5天</span></div>
    </section>
    <section class="cv-conditions" aria-labelledby="cv-conditions-title">
      <button type="button" class="cv-disclosure" data-action="toggle-conditions" aria-expanded="${expanded}" aria-controls="cv-conditions-panel"><span><strong id="cv-conditions-title">已确定的安排<span>选填</span></strong><small>${esc(conditionSummary)}</small></span><span class="cv-disclosure-arrow ${expanded ? 'is-open' : ''}">${icon('arrow', 18)}</span></button>
      <div id="cv-conditions-panel" class="cv-conditions-panel" ${expanded ? '' : 'hidden'}>
        <div class="cv-field"><label for="cv-hotel">酒店名称或所在区域</label><input id="cv-hotel" data-form="hotel" type="text" value="${esc(f.hotel || '')}" maxlength="100" autocomplete="off" placeholder="还没订，可以留空" aria-describedby="cv-hotel-note"><p class="cv-field-note" id="cv-hotel-note">先记下名称，具体位置留到草稿中确认。</p></div>
        <div class="cv-two-fields"><div class="cv-field"><label for="cv-arrival">首日抵达时间</label><input id="cv-arrival" data-form="arrival" type="time" value="${esc(f.arrival || '')}"></div><div class="cv-field"><label for="cv-return">末日返程时间</label><input id="cv-return" data-form="returnTime" type="time" value="${esc(f.returnTime || '')}"></div></div>
        <div class="cv-two-fields"><div class="cv-field"><label for="cv-pace">旅行节奏</label><select id="cv-pace" data-form="pace">${['舒缓', '适中', '紧凑'].map(value => option(value, f.pace || '舒缓')).join('')}</select></div><div class="cv-field"><label for="cv-mode">主要交通</label><select id="cv-mode" data-form="mode">${['公共交通', '步行优先', '打车为主'].map(value => option(value, f.mode || '公共交通')).join('')}</select></div></div>
      </div>
    </section>
    ${s.error ? `<div class="cv-form-error" role="alert">${esc(s.error)}</div>` : ''}
    <p class="cv-create-end">按已选清单编排；排不下时先说明原因，不会悄悄删掉你的选择。</p>
  </div>`;
}

function progressBody(s) {
  const f = s.form || {};
  const job = s.job || {step: 0, status: 'paused'};
  const step = Math.max(0, Math.min(3, Number(job.step) || 0));
  const stages = [
    ['核对已选内容', '保留你的想去、想吃和必选标记'],
    ['按区域整理', '使用已选内容，不添加未选景点'],
    ['检查时间与固定安排', '核对到达、酒店和返程，具体交通待确认'],
    ['生成可编辑草稿', '按天整理，方便继续调整']
  ];
  const titles = {running: '正在整理这次旅行', paused: '可以接着规划', failed: '这一步没有完成', done: '旅行草稿已整理好'};
  const descriptions = {running: '条件已经记下，正在按步骤形成草稿。', paused: '已保留旅行条件和当前进度。', failed: '旅行条件还在，可以从这一步继续。', done: '接下来可以核对地点，调整每天的安排。'};
  const statusText = job.status === 'paused' ? '已暂停' : job.status === 'failed' ? '需要重试' : job.status === 'done' ? '已完成' : '正在进行';
  let actions = '';
  if (job.status === 'paused') actions = button('继续规划', 'resume-job');
  if (job.status === 'failed') actions = `${button('重试这一步', 'retry-job')}`;
  return `<div class="cv-progress">
    <div class="cv-screen-title"><h1>${titles[job.status] || titles.paused}</h1><p>${descriptions[job.status] || descriptions.paused}</p></div>
    <p class="cv-generation-notice">本地流程演示，不调用真实 AI 或在线检索。</p>
    <ol class="cv-stages" aria-label="规划阶段">${stages.map(([title, description], index) => {
      const complete = job.status === 'done' || index < step;
      const current = index === step && job.status !== 'done';
      return `<li class="${complete ? 'is-complete' : current ? 'is-current' : ''}" ${current ? 'aria-current="step"' : ''}><span class="cv-stage-number" aria-hidden="true">${complete ? icon('check', 16) : index + 1}</span><div><strong>${title}</strong><p>${description}</p>${current ? `<span class="cv-stage-state" role="status">${statusText}</span>` : complete ? '<span class="cv-stage-state">已完成</span>' : ''}</div></li>`;
    }).join('')}</ol>
    ${job.status === 'failed' ? `<div class="cv-generation-error" role="alert"><h2>整理暂时中断</h2><p>${esc(job.error || '本次演示模拟资料整理失败，已保留输入。')}</p></div>` : ''}
    <section class="cv-input-summary" aria-labelledby="cv-summary-title"><h2 id="cv-summary-title">这次旅行的条件</h2><p class="cv-trip-summary">${esc(f.destination || '目的地待填写')} · ${esc(f.days || 3)}天 · ${esc(cvDateText(f.date, f.undated))}</p><dl><div><dt>首日抵达</dt><dd>${esc(f.arrival || '时间待定')}</dd></div><div><dt>酒店</dt><dd>${esc(f.hotel || '暂未确定')}</dd></div><div><dt>节奏与交通</dt><dd>${esc(f.pace || '舒缓')} · ${esc(f.mode || '公共交通')}</dd></div></dl></section>
    ${actions ? `<div class="cv-recovery-actions">${actions}</div>` : ''}
    <div class="cv-progress-links"><button type="button" class="cv-inline-action" data-action="leave-progress">先回我的行程${icon('arrow', 16)}</button>${job.status !== 'done' ? '<button type="button" class="cv-quiet-action" data-action="cancel-job">取消本次规划</button>' : ''}</div>
  </div>`;
}
