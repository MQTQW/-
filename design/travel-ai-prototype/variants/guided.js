function guidedStart(s, ui) {
  const destination = String(s.destination || '');
  const dateText = s.date ? String(s.date).replace(/-/g, '.') : '待定日期';
  const dayCount = ui.esc(String(s.dayCount || 3));
  const pace = ui.esc(String(s.pace || '舒缓'));
  const mode = ui.esc(String(s.mode || '公共交通'));

  return {
    body: `
      <div class="g-start">
        <section class="g-intro">
          <h1>先定好这趟旅行</h1>
          <p>选好目的地和出行偏好，再看每天怎么走。</p>
        </section>

        <section class="g-destination" aria-labelledby="g-destination-label">
          <div class="g-field-heading">
            <label id="g-destination-label" for="g-destination">目的地</label>
            <span>必填</span>
          </div>
          <div class="g-destination-box">
            <span class="g-field-icon" aria-hidden="true">${ui.icon('pin', 22)}</span>
            <input id="g-destination" name="destination" data-field="destination" type="text"
              value="${ui.esc(destination)}" placeholder="输入城市，例如杭州" autocomplete="off"
              aria-describedby="g-destination-help" required>
          </div>
          <p id="g-destination-help" class="g-field-help">生成后可以逐天调整安排。</p>
        </section>

        <details class="g-preferences">
          <summary>
            <span class="g-preference-copy">
              <span class="g-control-title">出行偏好</span>
              <span class="g-preference-values">${ui.esc(dateText)}出发 · ${dayCount}天<br>${pace} · ${mode}</span>
            </span>
            <span class="g-disclosure" aria-hidden="true">${ui.icon('arrow', 20)}</span>
          </summary>
          <div class="g-preference-fields">${ui.preferenceFields(s)}</div>
        </details>

        <div class="g-manual">
          <p>已经有自己的安排？</p>
          ${ui.button('从空白行程开始', 'start-manual', { secondary: true, icon: 'plus' })}
        </div>
      </div>`,
    footer: `
      <div class="g-start-footer">
        ${ui.button('开始规划', 'generate', { icon: 'spark' })}
      </div>`
  };
}

function guidedDraft(s, ui) {
  const destination = ui.esc(String(s.destination || '目的地'));
  const dayCount = ui.esc(String(s.dayCount || 3));
  const dateText = s.date ? `${String(s.date).replace(/-/g, '.')} 出发` : '出发日期待定';

  return {
    body: `
      <div class="g-draft">
        <section class="g-trip-overview">
          <div class="g-overview-heading">
            <h1>${destination} ${dayCount}天行程</h1>
            <span class="g-draft-status">草稿</span>
          </div>
          <p class="g-summary">${ui.esc(ui.summary(s))}</p>
          <p class="g-date-line">
            <span aria-hidden="true">${ui.icon('calendar', 18)}</span>
            <span>${ui.esc(dateText)}</span>
          </p>
        </section>

        <details class="g-reference">
          <summary>
            <span class="g-reference-icon" aria-hidden="true">${ui.icon('source', 21)}</span>
            <span class="g-reference-copy">
              <span class="g-control-title">攻略依据</span>
              <span class="g-control-description">查看参考摘要与出处</span>
            </span>
            <span class="g-disclosure" aria-hidden="true">${ui.icon('arrow', 20)}</span>
          </summary>
          <div class="g-reference-body">
            ${ui.sourceSummary(s)}
            <div class="g-source-action">${ui.button('查看参考攻略', 'view-sources', { secondary: true })}</div>
          </div>
        </details>

        <section class="g-days" aria-labelledby="g-days-title">
          <div class="g-day-heading">
            <h2 id="g-days-title">每日安排</h2>
            <p>点地点可修改</p>
          </div>
          ${ui.dayTabs(s)}
          <div class="g-timeline">${ui.timeline(s)}</div>
        </section>

        <details class="g-distribution">
          <summary>
            <span aria-hidden="true">${ui.icon('route', 21)}</span>
            <span class="g-control-title">查看地点分布</span>
            <span class="g-disclosure" aria-hidden="true">${ui.icon('arrow', 20)}</span>
          </summary>
          <div class="g-distribution-body">${ui.distribution(s)}</div>
        </details>
      </div>`,
    footer: `
      <div class="g-draft-footer">
        ${ui.button('调整偏好', 'change-prefs', { secondary: true })}
        ${ui.button('保存这份行程', 'save')}
      </div>`
  };
}

const GUIDED_CSS = `
.g-start, .g-draft, .g-start-footer, .g-draft-footer {
  --g-ink: #344331;
  --g-soft: #64735E;
  --g-primary: #65794F;
  --g-light: #E7EDDC;
  --g-white: #fff;
  min-width: 0;
  color: var(--g-ink);
  font-size: 15px;
  line-height: 1.5;
}
.g-start h1, .g-draft h1 {
  margin: 0;
  font-size: 24px;
  font-weight: 650;
  line-height: 1.35;
  letter-spacing: -.025em;
  overflow-wrap: anywhere;
}
.g-intro p {
  margin: 10px 0 0;
  color: var(--g-soft);
  line-height: 1.65;
}
.g-destination {
  margin-top: 28px;
  padding: 20px;
  background: var(--g-white);
  border-radius: 22px;
}
.g-field-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.g-field-heading label {
  font-size: 16px;
  font-weight: 600;
}
.g-field-heading > span {
  color: var(--g-soft);
  font-size: 13px;
}
.g-destination-box {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 56px;
  padding: 0 14px;
  border: 1px solid rgba(101, 121, 79, .28);
  border-radius: 14px;
}
.g-field-icon {
  display: flex;
  flex: 0 0 auto;
  color: var(--g-primary);
}
.g-destination-box input {
  width: 100%;
  min-width: 0;
  padding: 12px 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--g-ink);
  font: inherit;
  font-size: 18px;
  font-weight: 550;
  caret-color: var(--g-primary);
}
.g-destination-box input::placeholder {
  color: var(--g-soft);
  font-size: 16px;
  font-weight: 400;
  opacity: 1;
}
.g-destination-box:focus-within {
  outline: 2px solid var(--g-primary);
  outline-offset: 2px;
}
.g-field-help {
  margin: 10px 0 0;
  color: var(--g-soft);
  font-size: 13px;
}
.g-preferences, .g-reference {
  background: var(--g-white);
  border-radius: 22px;
}
.g-preferences {
  margin-top: 16px;
}
.g-preferences > summary, .g-reference > summary, .g-distribution > summary {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 72px;
  padding: 18px 20px;
  border-radius: 22px;
  cursor: pointer;
  list-style: none;
  -webkit-tap-highlight-color: transparent;
}
.g-preferences > summary::-webkit-details-marker,
.g-reference > summary::-webkit-details-marker,
.g-distribution > summary::-webkit-details-marker {
  display: none;
}
.g-preferences > summary:focus-visible,
.g-reference > summary:focus-visible,
.g-distribution > summary:focus-visible {
  outline: 2px solid var(--g-primary);
  outline-offset: 3px;
}
.g-preference-copy, .g-reference-copy {
  display: grid;
  gap: 5px;
  min-width: 0;
}
.g-control-title {
  font-size: 16px;
  font-weight: 600;
}
.g-preference-values, .g-control-description {
  color: var(--g-soft);
  font-size: 14px;
  font-weight: 400;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.g-disclosure {
  display: flex;
  flex: 0 0 auto;
  margin-left: auto;
  color: var(--g-soft);
}
.g-preferences[open] > summary .g-disclosure,
.g-reference[open] > summary .g-disclosure,
.g-distribution[open] > summary .g-disclosure {
  transform: rotate(90deg);
}
.g-preference-fields {
  padding: 2px 20px 20px;
}
.g-manual {
  margin-top: 26px;
  padding-bottom: 4px;
}
.g-manual p {
  margin: 0 0 10px;
  color: var(--g-soft);
  text-align: center;
  font-size: 14px;
}
.g-manual button, .g-start-footer button, .g-draft-footer button, .g-source-action button {
  width: 100%;
}
.g-trip-overview {
  padding: 2px 0 20px;
}
.g-overview-heading {
  display: flex;
  align-items: center;
  gap: 10px;
}
.g-overview-heading h1 {
  flex: 1;
  min-width: 0;
}
.g-draft-status {
  flex: 0 0 auto;
  padding: 4px 9px;
  border-radius: 8px;
  background: var(--g-light);
  color: var(--g-ink);
  font-size: 12px;
}
.g-summary {
  margin: 9px 0 0;
  color: var(--g-soft);
  font-size: 14px;
  overflow-wrap: anywhere;
}
.g-date-line {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 13px 0 0;
  color: var(--g-soft);
  font-size: 14px;
  font-variant-numeric: tabular-nums;
}
.g-date-line > span:first-child, .g-reference-icon, .g-distribution > summary > span:first-child {
  display: flex;
  flex: 0 0 auto;
  color: var(--g-primary);
}
.g-reference > summary {
  gap: 11px;
  min-height: 82px;
}
.g-reference-body {
  padding: 0 20px 20px;
}
.g-source-action {
  margin-top: 16px;
}
.g-days {
  margin-top: 26px;
}
.g-day-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}
.g-day-heading h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 650;
  line-height: 1.4;
  letter-spacing: -.02em;
}
.g-day-heading p {
  margin: 0;
  color: var(--g-soft);
  font-size: 13px;
}
.g-timeline {
  margin-top: 18px;
}
.g-distribution {
  margin: 20px 0 4px;
  border-radius: 22px;
  background: var(--g-light);
}
.g-distribution > summary {
  min-height: 64px;
}
.g-distribution-body {
  padding: 0 16px 16px;
}
.g-draft-footer {
  display: grid;
  grid-template-columns: 1fr 1.5fr;
  gap: 10px;
  align-items: stretch;
}
.g-draft-footer button {
  min-height: 50px;
  height: 100%;
  padding-right: 10px;
  padding-left: 10px;
}
`;
