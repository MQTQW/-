import { randomBytes } from 'node:crypto';

const MIME_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp', 'image/gif']);
const MESSAGES = Object.freeze({
  IMAGE_NOT_FOUND: '攻略图片不存在。',
  IMAGE_UNAVAILABLE: '攻略图片暂时无法读取。',
  IMAGE_TIMEOUT: '攻略图片读取超时。',
  IMAGE_TOO_LARGE: '攻略图片超过大小限制。',
  IMAGE_FORMAT_REJECTED: '攻略图片的实际格式未通过检查。',
});

function failure(code) {
  return Object.assign(new Error(MESSAGES[code]), { code });
}

/** Only internal, normalized source image URLs may enter this registry. */
function approvedUrl(value) {
  if (typeof value !== 'string' || value.length > 8192 || /[\s\\#]/u.test(value)) return null;
  // URL.port hides explicit default ports. Check the authority before parsing.
  const authority = /^https?:\/\/([^/?#]*)/iu.exec(value)?.[1];
  if (!authority || /[:@]/u.test(authority)) return null;
  let url;
  try { url = new URL(value); } catch { return null; }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.port || url.hash) return null;
  const host = url.hostname.toLowerCase();
  if (!['xhscdn.com', 'xiaohongshu.com'].some((domain) => host === domain || host.endsWith(`.${domain}`))) return null;
  url.protocol = 'https:';
  return url.href;
}

function detectedMime(bytes) {
  if (bytes.length >= 8 && bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))) return 'image/png';
  if (bytes.length >= 3 && bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255) return 'image/jpeg';
  if (bytes.length >= 6 && ['GIF87a', 'GIF89a'].includes(bytes.subarray(0, 6).toString('ascii'))) return 'image/gif';
  if (bytes.length >= 12 && bytes.subarray(0, 4).toString('ascii') === 'RIFF'
    && bytes.subarray(8, 12).toString('ascii') === 'WEBP') return 'image/webp';
  return null;
}

function cancelBody(body) {
  try { Promise.resolve(body?.cancel()).catch(() => {}); } catch { /* Cancellation is best effort. */ }
}

async function readImage(response, maximum, signal) {
  const declared = (response.headers?.get('content-type') ?? '').split(';')[0].trim().toLowerCase();
  if (!MIME_TYPES.has(declared)) {
    cancelBody(response.body);
    throw failure('IMAGE_FORMAT_REJECTED');
  }
  const length = response.headers.get('content-length');
  if (length && /^\d+$/u.test(length) && Number(length) > maximum) {
    cancelBody(response.body);
    throw failure('IMAGE_TOO_LARGE');
  }
  if (!response.body || typeof response.body.getReader !== 'function') throw failure('IMAGE_FORMAT_REJECTED');
  const reader = response.body.getReader();
  const cancelReader = () => { try { Promise.resolve(reader.cancel()).catch(() => {}); } catch {} };
  signal.addEventListener('abort', cancelReader, { once: true });
  const chunks = [];
  let size = 0;
  try {
    while (true) {
      if (signal.aborted) throw failure('IMAGE_TIMEOUT');
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > maximum) {
        cancelReader();
        throw failure('IMAGE_TOO_LARGE');
      }
      chunks.push(Buffer.from(value));
    }
  } finally {
    signal.removeEventListener('abort', cancelReader);
    try { reader.releaseLock(); } catch {}
  }
  if (signal.aborted) throw failure('IMAGE_TIMEOUT');
  const bytes = Buffer.concat(chunks, size);
  const mime = detectedMime(bytes);
  if (!mime || mime !== declared) throw failure('IMAGE_FORMAT_REJECTED');
  return { bytes, mime };
}

/** The HTTP image route accepts registry IDs, never arbitrary image URLs. */
export function createMediaStore({ fetchImpl = fetch, maxBytes = 5 * 1024 * 1024, maxEntries = 36, now = Date.now } = {}) {
  if (typeof fetchImpl !== 'function' || !Number.isSafeInteger(maxBytes) || maxBytes < 1 || maxBytes > 20 * 1024 * 1024
    || !Number.isSafeInteger(maxEntries) || maxEntries < 1 || maxEntries > 100) {
    throw new TypeError('图片存储配置无效。');
  }
  const entries = new Map();
  const registered = new Map();
  let downloads = 0, accessSequence = 0;
  const touch = entry => { entry.access = ++accessSequence; };
  const remove = id => { const entry=entries.get(id);if(!entry)return;registered.delete(entry.key);entries.delete(id); };

  async function download(entry) {
    const controller = new AbortController();
    let timer;
    const timeout = new Promise((_, reject) => {
      timer = setTimeout(() => {
        controller.abort();
        reject(failure('IMAGE_TIMEOUT'));
      }, 15_000);
      timer.unref?.();
    });
    try {
      return await Promise.race([timeout, (async () => {
        let response;
        try {
          response = await fetchImpl(entry.upstreamUrl, {
            method: 'GET', redirect: 'error', credentials: 'omit', signal: controller.signal,
            headers: { Accept: 'image/jpeg, image/png, image/webp, image/gif' },
          });
        } catch { throw failure(controller.signal.aborted ? 'IMAGE_TIMEOUT' : 'IMAGE_UNAVAILABLE'); }
        if (!response?.ok || response.redirected || response.status >= 300 && response.status < 400
          || response.url && approvedUrl(response.url) !== entry.upstreamUrl) {
          cancelBody(response?.body);
          throw failure('IMAGE_UNAVAILABLE');
        }
        return readImage(response, maxBytes, controller.signal);
      })()]);
    } catch (error) {
      controller.abort();
      throw failure(Object.hasOwn(MESSAGES, error?.code) ? error.code : 'IMAGE_UNAVAILABLE');
    } finally { clearTimeout(timer); }
  }

  return {
    register(value, { sourceId, index } = {}) {
      const upstreamUrl = approvedUrl(value);
      if (!upstreamUrl || typeof sourceId !== 'string' || !/^[A-Za-z0-9_-]{1,80}$/u.test(sourceId)
        || !Number.isInteger(index) || index < 1 || index > 50) return null;
      const key = `${sourceId}\0${index}\0${upstreamUrl}`;
      const existing = registered.get(key);
      if (existing && entries.has(existing)) {const entry=entries.get(existing);touch(entry);return { ...entry.public };}
      if(existing)registered.delete(key);
      if (entries.size >= maxEntries) {
        const oldest=[...entries.entries()].filter(([,entry])=>!entry.pending).sort((a,b)=>a[1].access-b[1].access)[0];
        if(!oldest)return null;
        remove(oldest[0]);
      }
      const id = randomBytes(18).toString('base64url');
      const descriptor = { id, url: `/api/images/${id}`, sourceId, index, caption: '攻略配图' };
      const entry={ key, public: descriptor, upstreamUrl, state: 'registered', data: null, pending: null, failureCode: null, failedAt:0 };touch(entry);entries.set(id,entry);
      registered.set(key, id);
      return { ...descriptor };
    },
    has(id) { return typeof id === 'string' && entries.has(id); },
    async get(id) {
      const entry = typeof id === 'string' ? entries.get(id) : null;
      if (!entry) throw failure('IMAGE_NOT_FOUND');
      touch(entry);
      if(entry.state==='failed'&&now()-entry.failedAt>=30000){entry.state='registered';entry.failureCode=null;}
      if (entry.state === 'failed') throw failure(entry.failureCode);
      if (!entry.data && !entry.pending) {
        entry.state = 'pending';
        entry.pending = download(entry).then((data) => {
          entry.data = data;
          entry.state = 'available';
          downloads++;
          return data;
        }).catch((error) => {
          entry.state = 'failed';
          entry.failureCode = error.code;
          entry.failedAt = now();
          throw error;
        }).finally(() => { entry.pending = null; });
      }
      const data = entry.data ?? await entry.pending;
      // Callers receive a copy so serving a response cannot mutate the private cache.
      return { bytes: Buffer.from(data.bytes), mime: data.mime };
    },
    stats() {
      const values = [...entries.values()];
      const cached = values.filter((entry) => entry.state === 'available');
      return { registered: entries.size, cached: cached.length, available: cached.length,
        pending: values.filter((entry) => entry.state === 'pending').length,
        failed: values.filter((entry) => entry.state === 'failed').length,
        bytesCached: cached.reduce((sum, entry) => sum + entry.data.bytes.length, 0), downloads };
    },
  };
}
