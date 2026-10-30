import { limitedJson } from './config.mjs';
// Parentheses often identify a branch or an entrance. They are part of identity.
const cleanName = (name) => typeof name === 'string' ? name.normalize('NFKC').replace(/\s/gu,'').trim() : '';
const cleanCity = (name) => String(name ?? '').normalize('NFKC').replace(/\s/gu,'').replace(/市$/u,'');
function nameWithinCity(name, city) {
  let value=cleanName(name);const prefix=cleanCity(city);
  if(prefix&&value.startsWith(prefix+'市')) value=value.slice(prefix.length+1);
  else if(prefix&&value.startsWith(prefix)) value=value.slice(prefix.length);
  // Official POI labels can include a parent scenic area's display prefix.
  // Preserve every branch/entrance suffix and all parentheses.
  const separator=value.indexOf('-');
  if(separator>0&&/(?:风景名胜区|风景区|景区)$/u.test(value.slice(0,separator))) value=value.slice(separator+1);
  return value;
}
const validPoint = (point) => Number.isFinite(point.longitude) && Number.isFinite(point.latitude) && Math.abs(point.longitude) <= 180 && Math.abs(point.latitude) <= 90;
function approximateMeters(a,b) {
  const radians=(value)=>value*Math.PI/180;
  const deltaLat=radians(b.latitude-a.latitude),deltaLon=radians(b.longitude-a.longitude);
  const h=Math.sin(deltaLat/2)**2+Math.cos(radians(a.latitude))*Math.cos(radians(b.latitude))*Math.sin(deltaLon/2)**2;
  return 6371000*2*Math.asin(Math.min(1,Math.sqrt(h)));
}

export function matchPoi(candidate, pois, checkedAt = new Date().toISOString()) {
  const options = (Array.isArray(pois) ? pois : []).filter((poi) => typeof poi.id === 'string' && poi.id && typeof poi.location === 'string').map((poi) => {
    const [longitude, latitude] = poi.location.split(',').map(Number);
    return { poiId: poi.id, name: poi.name ?? '', address: typeof poi.address === 'string' ? poi.address : '',
      city: poi.cityname ?? '', citycode: poi.citycode ?? '', adcode: poi.adcode ?? '', location: { longitude, latitude },
      openingHoursReported: poi.business?.opentime_week || poi.business?.opentime_today || null };
  }).filter((poi) => validPoint(poi.location));
  const candidateName = nameWithinCity(candidate.name,candidate.city);
  const exact = options.filter((poi) => candidateName && nameWithinCity(poi.name,poi.city) === candidateName &&
    cleanCity(poi.city) && cleanCity(poi.city) === cleanCity(candidate.city));
  if (exact.length === 1) return { status: 'verified', provider: 'amap', checkedAt, ...exact[0],
    scope: '仅地点身份和坐标匹配；开放、预约和门票尚未核实' };
  return { status: options.length ? 'ambiguous' : 'not_found', provider: 'amap', checkedAt,
    reason: options.length ? '同名或名称有差异，需确认具体地点；未自动选第一条。' : '本次未匹配到可用地点。', matches: options.slice(0,3) };
}

export function routeMinutes(data, mode) {
  const paths = mode === '公共交通' ? data.route?.transits : data.route?.paths;
  const seconds = (paths ?? []).map((path) => Number(path.cost?.duration ?? path.duration))
    .filter((duration) => Number.isFinite(duration) && duration > 0);
  return seconds.length ? Math.ceil(Math.min(...seconds) / 60) : null;
}

export function createAmap({ apiKey, fetchImpl = fetch, intervalMillis = 1100 } = {}) {
  let previousAt = 0;
  async function request(path, params) {
    if (!apiKey) throw Object.assign(new Error('尚未配置高德 Web 服务 Key'), { code: 'MAP_NOT_CONFIGURED' });
    const delay = Math.max(0, intervalMillis - (Date.now() - previousAt));
    if (delay) await new Promise((resolve) => setTimeout(resolve, delay));
    previousAt = Date.now();
    const url = new URL(path, 'https://restapi.amap.com');
    url.search = new URLSearchParams({ ...params, key: apiKey }).toString();
    let data;
    try { data = await limitedJson(await fetchImpl(url, { redirect: 'error', signal: AbortSignal.timeout(15_000) }), 512*1024); }
    catch { throw Object.assign(new Error('地图接口本次不可用或超时'), { code: 'MAP_UNAVAILABLE' }); }
    if (String(data.status) !== '1') {
      const code = /^\d{5}$/u.test(String(data.infocode)) ? String(data.infocode) : 'UNKNOWN';
      throw Object.assign(new Error(`高德接口返回错误 ${code}，请检查 Key 类型、权限、配额或白名单。`), { code: `AMAP_${code}` });
    }
    return data;
  }
  return {
    async verify(candidate) {
      const data = await request('/v5/place/text', { keywords: candidate.name, region: candidate.city, city_limit: 'true', page_size: '5', show_fields: 'business' });
      return { ...candidate, verification: matchPoi(candidate, data.pois ?? []) };
    },
    async leg(from, to, mode = '公共交通') {
      const origin = from.verification; const destination = to.verification;
      if (origin?.status !== 'verified' || destination?.status !== 'verified') return null;
      const params = { origin: `${origin.location.longitude},${origin.location.latitude}`,
        destination: `${destination.location.longitude},${destination.location.latitude}`, show_fields: 'cost' };
      const transport = mode === '自驾' ? 'driving' : mode === '步行' ? 'walking' : 'transit/integrated';
      if (mode === '公共交通') { params.city1 = origin.citycode || origin.adcode; params.city2 = destination.citycode || destination.adcode; }
      const data = await request(`/v5/direction/${transport}`, params);
      let durationMinutes = routeMinutes(data, mode); let actualMode=mode;
      if (mode==='公共交通' && durationMinutes===null && approximateMeters(origin.location,destination.location)<=2000) {
        const walking=await request('/v5/direction/walking',params);
        durationMinutes=routeMinutes(walking,'步行'); actualMode='步行';
      }
      return { fromCandidateId: from.id, toCandidateId: to.id, durationMinutes, mode:actualMode,
        status: durationMinutes === null ? 'unavailable' : 'verified', provider: 'amap', checkedAt: new Date().toISOString(),
        scope: `${actualMode==='步行'&&mode==='公共交通'?'此段未返回公交方案，改查步行。':''}接口查询时的估计耗时；不代表未来出行当天保证，景区内移动与排队未计入` };
    },
  };
}
