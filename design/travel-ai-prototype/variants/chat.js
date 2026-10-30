function chatStart(s, ui) {
  const e = ui.esc;
  const prompt = s.prompt || '';
  const suggestions = [
    { label: '西湖和喝茶', prompt: '杭州玩3天，想逛西湖、喝茶，不要太赶。' },
    { label: '带爸妈慢慢逛', prompt: '杭州玩3天，带爸妈，少走路、多休息。' },
    { label: '以本地小吃为主', prompt: '杭州玩3天，想找本地小吃，景点少一点。' }
  ];

  return {
    body: `
      <section class="c-chat c-start" aria-label="与行程助手讨论旅行">
        <div class="c-assistant-message c-welcome">
          <div class="c-assistant-name">${ui.icon('spark', 18)}<span>MY PLAN 行程助手</span></div>
          <h2>这次想怎么逛${e(s.destination || '杭州')}？</h2>
          <p>想去的地方、同行的人，或不想太赶的节奏，都可以告诉我。</p>
        </div>

        <div class="c-first-composer">
          <label for="c-trip-prompt">说说你的想法</label>
          <textarea id="c-trip-prompt" name="prompt" data-field="prompt" rows="4" maxlength="360" aria-describedby="c-prompt-help" placeholder="比如：杭州玩3天，想逛西湖、喝茶，不要太赶。">${e(prompt)}</textarea>
          <p id="c-prompt-help">先有一个大致想法就好，生成后还能继续商量。</p>
        </div>

        <div class="c-prompt-suggestions">
          <p class="c-section-label">也可以从这里开始</p>
          <div class="c-suggestion-list">
            ${suggestions.map(item => `<button type="button" class="c-suggestion${prompt === item.prompt ? ' c-selected' : ''}" data-action="use-prompt" data-prompt="${e(item.prompt)}" aria-pressed="${prompt === item.prompt}" aria-label="使用想法：${e(item.prompt)}">${e(item.label)}${ui.icon('plus', 15)}</button>`).join('')}
          </div>
        </div>

        <section class="c-preference-context" aria-label="本次旅行的基本安排">
          <div class="c-context-heading">
            <p class="c-section-label">这次旅行</p>
            <button type="button" class="c-text-action" data-action="change-prefs">改偏好${ui.icon('edit', 15)}</button>
          </div>
          <div class="c-shared-summary">${ui.esc(ui.summary(s))}</div>
        </section>
      </section>`,
    footer: `
      <div class="c-chat-footer c-start-footer">
        <p>先给你一版草稿，再一起调整。</p>
        ${ui.button('生成行程草稿', 'generate', { icon: 'spark' })}
      </div>`
  };
}

function chatDraft(s, ui) {
  const e = ui.esc;
  const dayCount = Math.max(1, Number(s.dayCount) || 3);
  const revised = Number(s.revision) > 0;
  const originalPrompt = s.prompt || `去${s.destination || '杭州'}玩${dayCount}天，想按${s.pace || '舒缓'}的节奏逛一逛。`;
  const lastRevision = s.lastChatRevision || '少走一点路';
  const reply = lastRevision.includes('休息')
    ? '好，把休息时间放宽一些。停留点和时长已更新，可以再看看下面这版。'
    : lastRevision.includes('走')
      ? '好，按少走路的方向调整了停留点和时长。你再看看，这样会不会更合适。'
      : `好，已按「${lastRevision}」更新这版安排。下面可以核对调整后的停留点与时长。`;

  return {
    body: `
      <section class="c-chat c-draft" aria-label="旅行共创对话">
        <div class="c-conversation">
          <div class="c-user-message" aria-label="我的旅行想法"><p>${e(originalPrompt)}</p></div>

          <div class="c-assistant-message c-first-reply">
            <div class="c-assistant-name">${ui.icon('spark', 18)}<span>MY PLAN 行程助手</span></div>
            <p>先安排了 ${dayCount} 天，按${e(s.pace || '舒缓')}节奏串起你想去的地方。想少走路或多休息，都可以继续改。</p>
          </div>

          ${revised ? `
            <div class="c-user-message c-revision-request" aria-label="我提出的调整"><p>${e(lastRevision)}</p></div>
            <div class="c-assistant-message c-revision-reply" role="status" aria-live="polite">
              <div class="c-assistant-name">${ui.icon('spark', 18)}<span>MY PLAN 行程助手</span></div>
              <p>${e(reply)}</p>
            </div>` : ''}
        </div>

        <section class="c-result" aria-label="${revised ? '调整后的' : '助手生成的'}行程草稿">
          <div class="c-result-heading">
            <h2>${revised ? '调整后的行程' : `${e(s.destination || '杭州')}行程草稿`}</h2>
            <span>${revised ? `${ui.icon('check', 14)}已更新` : '待你确认'}</span>
          </div>
          <div class="c-result-summary">${ui.esc(ui.summary(s))}</div>
          ${ui.dayTabs(s)}
          <div class="c-result-timeline">${ui.timeline(s)}</div>

          <details class="c-place-distribution">
            <summary>${ui.icon('route', 18)}<span>看看地点分布</span>${ui.icon('arrow', 16)}</summary>
            <div class="c-distribution-content">${ui.distribution(s)}</div>
          </details>

          <div class="c-result-source">${ui.sourceSummary(s)}</div>
          <div class="c-result-actions">
            <button type="button" class="c-text-action" data-action="open-confirm">${ui.icon('pin', 16)}确认地点</button>
            <button type="button" class="c-text-action" data-action="view-sources">${ui.icon('source', 16)}查看来源</button>
          </div>
        </section>

        <section class="c-follow-up" aria-label="继续调整行程">
          <p>这版哪里还想改？</p>
          <div class="c-adjustment-list">
            <button type="button" class="c-adjustment" data-action="revise" data-prompt="少走一点路">少走一点路${ui.icon('plus', 15)}</button>
            <button type="button" class="c-adjustment" data-action="revise" data-prompt="多留一点休息时间">多留一点休息时间${ui.icon('plus', 15)}</button>
          </div>
        </section>
      </section>`,
    footer: `
      <div class="c-chat-footer c-draft-footer">
        <div class="c-reply-composer">
          <label class="c-visually-hidden" for="c-revision-input">继续告诉助手想怎么调整</label>
          <input id="c-revision-input" name="chatRevision" data-field="chatRevision" type="text" maxlength="160" value="${e(s.chatRevision || '')}" placeholder="还想怎么改？" autocomplete="off">
          <button type="button" class="c-send" data-action="revise" aria-label="发送调整想法">${ui.icon('arrow', 21)}</button>
        </div>
        <div class="c-footer-actions">
          ${ui.button('改偏好', 'change-prefs', { secondary: true })}
          ${ui.button('保存行程', 'save', { icon: 'check' })}
        </div>
      </div>`
  };
}

const CHAT_CSS = `
  .c-chat,
  .c-chat-footer {
    --c-bg: #F7F8F2;
    --c-ink: #344331;
    --c-soft: #64735E;
    --c-primary: #65794F;
    --c-light: #E7EDDC;
    --c-white: #FFFFFF;
    --c-coral: #F9EBE3;
    color: var(--c-ink);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
    font-size: 15px;
    line-height: 1.6;
    min-width: 0;
  }
  .c-chat *, .c-chat-footer * { box-sizing: border-box; }
  .c-chat p, .c-chat h2 { margin: 0; }
  .c-chat button, .c-chat-footer button, .c-chat textarea, .c-chat-footer input {
    font: inherit;
    -webkit-tap-highlight-color: transparent;
    touch-action: manipulation;
  }
  .c-chat button, .c-chat-footer button { cursor: pointer; }
  .c-chat button:focus-visible, .c-chat-footer button:focus-visible,
  .c-chat textarea:focus-visible, .c-chat-footer input:focus-visible,
  .c-place-distribution summary:focus-visible {
    outline: 2px solid var(--c-primary);
    outline-offset: 4px;
  }
  .c-start { display: flex; flex-direction: column; gap: 22px; padding-bottom: 4px; }
  .c-assistant-name {
    display: flex;
    align-items: center;
    gap: 7px;
    margin-bottom: 9px;
    color: var(--c-primary);
    font-size: 13px;
    line-height: 24px;
    font-weight: 600;
  }
  .c-assistant-name svg { flex: 0 0 auto; }
  .c-welcome h2 { font-size: 24px; line-height: 1.4; font-weight: 650; margin: 3px 0 8px; letter-spacing: -.025em; }
  .c-welcome > p { color: var(--c-soft); }
  .c-first-composer { border-radius: 22px; background: var(--c-white); padding: 18px 20px 16px; }
  .c-first-composer label { display: block; margin-bottom: 10px; font-size: 13px; color: var(--c-soft); }
  .c-first-composer textarea {
    display: block;
    width: 100%;
    min-height: 112px;
    max-height: 240px;
    margin: 0;
    padding: 0;
    border: 0;
    border-radius: 0;
    color: var(--c-ink);
    background: transparent;
    line-height: 1.75;
    resize: vertical;
  }
  .c-first-composer textarea::placeholder, .c-reply-composer input::placeholder { color: var(--c-soft); opacity: 1; }
  .c-first-composer > p { margin-top: 9px; font-size: 12px; line-height: 1.6; color: var(--c-soft); }
  .c-section-label { color: var(--c-soft); font-size: 13px; line-height: 22px; }
  .c-prompt-suggestions > .c-section-label { margin-bottom: 10px; }
  .c-suggestion-list, .c-adjustment-list { display: flex; flex-wrap: wrap; gap: 8px; }
  .c-suggestion, .c-adjustment {
    display: inline-flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    min-height: 44px;
    max-width: 100%;
    padding: 10px 13px;
    border: 0;
    border-radius: 14px;
    background: var(--c-white);
    color: var(--c-ink);
    font-size: 13px !important;
    line-height: 22px;
    text-align: left;
  }
  .c-suggestion svg, .c-adjustment svg { flex: none; color: var(--c-primary); }
  .c-suggestion.c-selected { background: var(--c-light); }
  .c-context-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 3px; }
  .c-text-action {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 6px;
    min-height: 44px;
    padding: 6px 0;
    border: 0;
    border-radius: 8px;
    color: var(--c-primary);
    background: transparent;
    font-size: 13px !important;
    line-height: 22px;
    white-space: nowrap;
  }
  .c-shared-summary { font-size: 14px; }
  .c-preference-context { padding-top: 1px; }
  .c-chat-footer > p { margin: 0 0 9px; text-align: center; color: var(--c-soft); font-size: 12px; line-height: 20px; }
  .c-start-footer > button { width: 100%; }
  .c-conversation { display: flex; flex-direction: column; gap: 18px; }
  .c-user-message {
    align-self: flex-end;
    max-width: 88%;
    padding: 13px 16px;
    border-radius: 20px 20px 5px 20px;
    background: var(--c-light);
    overflow-wrap: anywhere;
  }
  .c-assistant-message { min-width: 0; overflow-wrap: anywhere; }
  .c-first-reply > p, .c-revision-reply > p { line-height: 1.7; }
  .c-revision-request { background: var(--c-coral); }
  .c-revision-reply .c-demo-note { margin-top: 8px; color: var(--c-soft); font-size: 12px; line-height: 1.6; }
  /* At 390px: 20px phone gutters + 350px result; its 14px padding leaves 322px for the shared timeline. */
  .c-result { margin-top: 20px; padding: 18px 14px 8px; border-radius: 22px; background: var(--c-white); min-width: 0; }
  .c-result-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 0 3px; }
  .c-result-heading h2 { min-width: 0; font-size: 17px; line-height: 1.5; font-weight: 650; }
  .c-result-heading > span { display: inline-flex; align-items: center; gap: 3px; flex: none; color: var(--c-soft); font-size: 12px; line-height: 22px; }
  .c-result-summary { margin: 5px 3px 15px; color: var(--c-soft); font-size: 12px; }
  .c-result .day-tabs { margin: 0 0 15px; }
  .c-result-timeline { min-width: 0; }
  .c-result .stop-row:last-child { border-bottom: 0; }
  .c-place-distribution { margin: 2px 0 0; }
  .c-place-distribution > summary {
    display: flex;
    align-items: center;
    gap: 7px;
    min-height: 48px;
    padding: 10px 3px;
    border-radius: 8px;
    color: var(--c-ink);
    font-size: 13px;
    cursor: pointer;
    list-style: none;
  }
  .c-place-distribution > summary::-webkit-details-marker { display: none; }
  .c-place-distribution > summary > svg { flex: none; color: var(--c-primary); }
  .c-place-distribution > summary > svg:last-child { margin-left: auto; }
  .c-place-distribution[open] > summary > svg:last-child { transform: rotate(90deg); }
  .c-distribution-content { padding: 2px 0 10px; }
  .c-result-source { margin-top: 3px; }
  .c-result .source-summary { margin: 0; font-size: 12px; }
  .c-result-actions { display: flex; justify-content: space-between; align-items: center; gap: 16px; padding: 3px 3px 0; }
  .c-follow-up { padding: 20px 0 4px; }
  .c-follow-up > p { margin-bottom: 10px; color: var(--c-soft); font-size: 13px; }
  .c-adjustment { background: transparent; border: 1px solid #DDE4D6; padding: 9px 12px; }
  .c-draft-footer { display: flex; flex-direction: column; gap: 10px; }
  .c-reply-composer { display: flex; align-items: center; gap: 8px; padding: 3px 4px 3px 14px; min-height: 50px; border-radius: 16px; background: var(--c-white); border: 1px solid #DDE4D6; }
  .c-reply-composer input {
    display: block;
    width: 100%;
    min-width: 0;
    min-height: 42px;
    padding: 6px 0;
    border: 0;
    border-radius: 0;
    color: var(--c-ink);
    background: transparent;
    font-size: 15px;
    line-height: 24px;
  }
  .c-reply-composer input:focus-visible { outline-offset: 1px; border-radius: 3px; }
  .c-send { display: grid; place-items: center; flex: 0 0 44px; width: 44px; height: 44px; padding: 0; border: 0; border-radius: 12px; background: var(--c-light); color: var(--c-ink); }
  .c-footer-actions { display: grid; grid-template-columns: 1fr 1.85fr; gap: 10px; }
  .c-footer-actions > button { width: 100%; min-width: 0; }
  .c-visually-hidden { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
  @media (hover: hover) {
    .c-suggestion:hover, .c-adjustment:hover, .c-send:hover { background: var(--c-light); }
    .c-text-action:hover { color: var(--c-ink); }
  }
  @media (max-width: 360px) {
    .c-welcome h2 { font-size: 23px; }
    .c-first-composer { padding-right: 16px; padding-left: 16px; }
    .c-result { padding-right: 11px; padding-left: 11px; }
    .c-user-message { max-width: 94%; }
    .c-adjustment-list { gap: 7px; }
    .c-adjustment { gap: 7px; padding-right: 10px; padding-left: 10px; }
  }
`;
