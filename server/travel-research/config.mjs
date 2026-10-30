import { readFile } from 'node:fs/promises';

export async function loadConfig(file, env = process.env) {
  let text = '';
  try { text = await readFile(file, 'utf8'); } catch (error) { if (error.code !== 'ENOENT') throw error; }
  const entries = {};
  for (const line of text.split(/\r?\n/u)) {
    const match = line.match(/^\s*([A-Z][A-Z_0-9]*)\s*=\s*(.*?)\s*$/u);
    if (!match) continue;
    let value = match[2];
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) value = value.slice(1, -1);
    entries[match[1]] = value.trim();
  }
  const get = (name) => (env[name] ?? entries[name] ?? '').trim();
  const usable = (value) => value.length >= 12 && !/^(?:your_|replace|example|x{6}|<|你的|粘贴|填写|请)/iu.test(value);
  const ai = get('DEEPSEEK_API_KEY'); const amap = get('AMAP_WEB_SERVICE_KEY');
  return { aiKey: usable(ai) ? ai : '', amapKey: usable(amap) ? amap : '', model: get('DEEPSEEK_MODEL') || 'deepseek-flash' };
}

export async function limitedJson(response, maxBytes = 1024 * 1024) {
  if (!response.ok) { await response.body?.cancel(); throw Object.assign(new Error('远程接口暂时不可用'), { code: `HTTP_${response.status}` }); }
  const chunks = []; let total = 0;
  for await (const chunk of response.body) {
    total += chunk.length;
    if (total > maxBytes) throw Object.assign(new Error('接口返回内容过大'), { code: 'RESPONSE_TOO_LARGE' });
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); }
  catch { throw Object.assign(new Error('接口未返回有效 JSON'), { code: 'INVALID_JSON' }); }
}
