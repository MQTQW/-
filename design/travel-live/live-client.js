/* Same-origin API transport. Session credentials remain in memory and cookies. */
const LiveAPI = (() => {
  let csrfToken = '';
  class APIError extends Error {
    constructor(message, code = 'REQUEST_FAILED', status = 0) { super(message); this.name = 'APIError'; this.code = code; this.status = status; }
  }
  async function request(path, {method = 'GET', body, signal} = {}) {
    if (!/^\/api\/(?:session|research|plan|jobs\/[A-Za-z0-9_-]+)$/.test(path)) throw new APIError('请求地址无效。', 'INVALID_PATH');
    const controller = new AbortController();
    const abort = () => controller.abort(signal?.reason);
    if (signal?.aborted) abort(); else signal?.addEventListener('abort', abort, {once: true});
    const timer = setTimeout(() => controller.abort(), 30000);
    try {
      const response = await fetch(path, {method, credentials: 'same-origin', cache: 'no-store', headers: {Accept: 'application/json', ...(body ? {'Content-Type': 'application/json'} : {}), ...(method === 'POST' ? {'X-CSRF-Token': csrfToken} : {})}, ...(body ? {body: JSON.stringify(body)} : {}), signal: controller.signal});
      let data;
      try { data = await response.json(); } catch { throw new APIError('服务返回了无法读取的内容，请稍后重试。', 'INVALID_RESPONSE', response.status); }
      if (!response.ok) throw new APIError(data.error?.message || data.message || '请求没有完成，请重试。', data.error?.code || 'REQUEST_FAILED', response.status);
      return data;
    } catch (error) {
      if (error instanceof APIError) throw error;
      if (signal?.aborted) throw new APIError('已停止查看，服务端任务可能仍在运行。', 'ABORTED');
      throw new APIError(error.name === 'AbortError' ? '连接超时，请重新连接后继续。' : '无法连接本机旅行服务，请确认服务正在运行。', error.name === 'AbortError' ? 'TIMEOUT' : 'NETWORK_ERROR');
    } finally { clearTimeout(timer); signal?.removeEventListener('abort', abort); }
  }
  async function session(options) { const data = await request('/api/session', options); csrfToken = String(data.csrfToken || ''); return data; }
  async function post(path, body, options) { if (!csrfToken) await session(options); if (!csrfToken) throw new APIError('会话尚未就绪，请重新检测连接。', 'SESSION_NOT_READY'); return request(path, {...options, method: 'POST', body}); }
  function delay(ms, signal) { return new Promise((resolve, reject) => { if (signal?.aborted) return reject(new APIError('已停止查看。', 'ABORTED')); const aborted = () => {clearTimeout(timer); reject(new APIError('已停止查看。', 'ABORTED'));}; const timer = setTimeout(() => {signal?.removeEventListener('abort', aborted); resolve();}, ms); signal?.addEventListener('abort', aborted, {once: true}); }); }
  async function poll(id, {onUpdate, signal, interval = 1200, maxWait = 20 * 60 * 1000} = {}) {
    const start = Date.now();
    while (Date.now() - start < maxWait) {
      const job = await request('/api/jobs/' + encodeURIComponent(id), {signal});
      if (!['running', 'done', 'failed'].includes(job.status)) throw new APIError('任务状态无效，请重新连接。', 'INVALID_JOB');
      onUpdate?.(job);
      if (job.status === 'failed') throw new APIError(job.error?.message || '任务没有完成，请重试。', job.error?.code || 'JOB_FAILED');
      if (job.status === 'done') return job.result;
      await delay(interval, signal);
    }
    throw new APIError('任务仍未返回结果，可以稍后继续查看。', 'POLL_TIMEOUT');
  }
  const imageURL = image => {const value = typeof image === 'string' ? image : image?.url; return typeof value === 'string' && /^\/api\/images\/[A-Za-z0-9_-]+$/.test(value) ? value : '';};
  const sourceURL = value => {try {const url = new URL(value); return url.protocol === 'https:' && !url.username && !url.password && /(^|\.)(xiaohongshu\.com|xhslink\.com)$/.test(url.hostname) ? url.href : '';} catch {return '';}};
  return {session, research: (destination, options) => post('/api/research', {destination, count: 3}, options), plan: (researchId, selected, form, options) => post('/api/plan', {researchId, selected, form}, options), job: (id, options) => request('/api/jobs/' + encodeURIComponent(id), options), poll, imageURL, sourceURL, APIError};
})();

function liveImageMarkup(image, {className = '', alt = '攻略配图', caption = true, eager = false} = {}) {
  const url = LiveAPI.imageURL(image);
  if (!url) return '';
  return `<figure class="live-image ${esc(className)}"><a class="live-image-link" href="${esc(url)}" target="_blank" rel="noopener noreferrer" aria-label="在新页面查看${esc(alt)}原尺寸"><img src="${esc(url)}" alt="${esc(alt)}" ${eager ? 'fetchpriority="high"' : 'loading="lazy"'} decoding="async" data-live-image></a><figcaption ${caption ? '' : 'hidden'}>攻略配图，非地点实拍确认</figcaption><p class="live-image-fallback" hidden>配图暂时无法加载，仍可查看文字与攻略来源。</p></figure>`;
}

function liveBindImages(root = document) {
  root.querySelectorAll('img[data-live-image]').forEach(img => {
    const fail = () => {img.hidden = true; if(img.parentElement?.classList.contains('live-image-link'))img.parentElement.hidden=true; const fallback = img.closest('figure')?.querySelector('.live-image-fallback'); if (fallback) fallback.hidden = false;};
    img.addEventListener('error', fail, {once: true});
    if (img.complete && img.naturalWidth === 0) fail();
  });
}
