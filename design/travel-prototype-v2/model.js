/* Local, deterministic prototype model. No AI, route, weather or POI service calls. */
const Model = (() => {
  'use strict';
  const STORAGE_KEY = 'MY_PLAN_TRAVEL_V2_20261030';
  const clone = value => value == null ? value : JSON.parse(JSON.stringify(value));
  const now = () => new Date().toISOString();
  const timeMinutes = value => typeof value === 'string' && /^([01]\d|2[0-3]):[0-5]\d$/.test(value) ? Number(value.slice(0, 2)) * 60 + Number(value.slice(3)) : NaN;
  const formatTime = value => Number.isInteger(value) && value >= 0 && value < 1440 ? `${String(Math.floor(value / 60)).padStart(2, '0')}:${String(value % 60).padStart(2, '0')}` : '';
  const uid = prefix => `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 9)}`;
  const sources = [
    {id:'S1',title:'本J人耗时6h做的杭州攻略，满意的睡不着',author:'豆鲨包',date:'2026.05.18',description:'图片中的时段、餐饮和晚间活动，作为安排参考。',excerpt:'图文攻略用于参考地点和活动；本原型不复现完整帖子。'},
    {id:'S2',title:'杭州3天2夜懒人攻略｜西湖、灵隐、九溪路线',author:'奶油小方',date:'2026.07.30',description:'西湖、寺庙茶园、运河三条线路，适合分区安排。',excerpt:'正文提到断桥、白堤、雷峰塔、灵隐、永福、九溪、小河直街和拱宸桥。'}
  ];
  const fresh = () => ({schema:2,screen:'home',theme:'light',tab:'overview',view:'list',activeDay:0,selectedId:null,form:{prompt:'杭州三天，下午两点到，酒店已订，不要太赶。',destination:'杭州',date:'2026-11-07',undated:false,days:3,arrival:'14:00',returnTime:'16:30',hotel:'西湖附近酒店（示例）',pace:'舒缓',mode:'公共交通'},trip:null,editor:null,proposal:null,history:[],job:null});
  const validDate = value => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) && !Number.isNaN(Date.parse(`${value}T00:00:00Z`)) && new Date(`${value}T00:00:00Z`).toISOString().slice(0, 10) === value;
  const plusDate = (value, offset) => { const date = new Date(`${value}T12:00:00Z`); date.setUTCDate(date.getUTCDate() + offset); return date.toISOString().slice(0, 10); };
  const stop = (id, title, time, duration, extra = {}) => ({id,title,time,duration,notes:'',sourceIds:[],locationStatus:'manual',lng:null,lat:null,photo:null,locked:false,manuallyEdited:false,kind:'visit',...extra});
  const poi = (id, title, time, duration, lng, lat, photo, notes, sourceIds = ['S2']) => stop(id,title,time,duration,{lng,lat,photo,notes,sourceIds,locationStatus:'matched'});
  const fixtures = () => ({
    pagoda:poi('pagoda','雷峰塔景区','16:00',90,120.1501,30.2318,'pagoda','登塔属于这一站的游览内容；开放与门票需另行核对。',['S1','S2']),
    meal:stop('meal','晚餐 · 杭鸽油爆虾（门店待定）','18:30',60,{kind:'meal',sourceIds:['S1'],locationStatus:'pending',notes:'原资料未明确具体分店，保留待定；不据此计算路线。'}),
    temple:poi('temple','灵隐寺','09:00',120,120.1015,30.2403,'temple','出发前核对预约与开放安排。'),
    yongfu:poi('yongfu','永福寺','11:45',60,120.0980,30.2370,'temple','寺院与山林间慢慢走。'),
    jiuxi:poi('jiuxi','九溪烟树','14:00',90,120.1129,30.2076,'westlake','按体力调整步行，预留休息空档。'),
    xiaohe:poi('xiaohe','小河直街','09:00',90,120.1371,30.3171,'canal','逛逛沿河小店。'),
    bridge:poi('bridge','拱宸桥','11:00',45,120.1398,30.3212,'canal','在运河边停一会儿。'),
    broken:poi('broken','断桥残雪','09:00',40,120.1513,30.2582,'westlake','沿湖散步，按体力调整停留。'),
    bai:poi('bai','白堤','10:10',60,120.1456,30.2556,'westlake','给拍照与休息留空档。')
  });
  function dayError(day) {
    const sorted = [...day.stops].sort((a,b) => timeMinutes(a.time) - timeMinutes(b.time));
    for (let index = 0; index < sorted.length; index++) {
      const item = sorted[index];
      const start = timeMinutes(item.time);
      if (!Number.isFinite(start)) return `「${item.title}」的开始时间无效，请使用 00:00–23:59。`;
      if (!Number.isInteger(Number(item.duration)) || Number(item.duration) < 1) return `「${item.title}」的停留时长须为正整数分钟。`;
      if (start + Number(item.duration) > 1440) return `「${item.title}」跨越午夜，请缩短时长或换一天安排。`;
      const previous = sorted[index - 1];
      if (previous && timeMinutes(previous.time) + Number(previous.duration) > start) return `「${item.title}」与${previous.locked ? '固定安排' : '安排'}「${previous.title}」时间重叠，请调整时间或换一天。`;
    }
    return '';
  }
  function createTrip(input) {
    const form = {...fresh().form,...input};
    if (!/^杭州(?:市)?$/.test(String(form.destination).trim())) return {trip:null,error:'本地演示目前仅支持杭州，请把目的地改为“杭州”。'};
    const count = Number(form.days);
    if (!Number.isInteger(count) || count < 1 || count > 5) return {trip:null,error:'行程天数请选择 1–5 天。'};
    if (!form.undated && !validDate(form.date)) return {trip:null,error:'请选择有效的出发日期，或勾选“日期未定”。'};
    for (const [key,label] of [['arrival','到达'],['returnTime','返程']]) if (form[key] && !Number.isFinite(timeMinutes(form[key]))) return {trip:null,error:`${label}时间无效，请使用 00:00–23:59。`};
    const data = fixtures();
    const plans = count <= 3 ? [['pagoda','meal'],['temple','yongfu','jiuxi'],['xiaohe','bridge']].slice(0,count) : count === 4 ? [['pagoda','meal'],['temple','yongfu','jiuxi'],['xiaohe','bridge'],['broken','bai']] : [['pagoda','meal'],['temple','yongfu'],['jiuxi'],['xiaohe','bridge'],['broken','bai']];
    if (count === 5) data.jiuxi.time = '09:00';
    const names = count === 5 ? ['抵达与西湖','寺庙慢游','九溪休闲线','运河烟火线','西湖与返程'] : ['抵达与西湖','寺庙与茶园','运河烟火线','西湖与返程'];
    const days = plans.map((ids,index) => ({day:index+1,date:form.undated ? null : plusDate(form.date,index),name:names[index],stops:ids.map(id => data[id]),routeDirty:true}));
    const first = days[0];
    const arrival = form.arrival ? timeMinutes(form.arrival) : null;
    const fixed = [];
    if (arrival !== null) fixed.push(stop('arrival','抵达杭州',form.arrival,30,{kind:'arrival',locked:true,notes:'用户填写的到达约束；尚未关联具体车站或机场。'}));
    if (String(form.hotel || '').trim()) {
      const hotelTime = arrival === null ? 15 * 60 : arrival + 60;
      if (!formatTime(hotelTime) || hotelTime + 45 > 1440) return {trip:null,error:'到达后当天已没有足够时间办理入住，请调整到达时间或住宿条件。'};
      fixed.push(stop('hotel',String(form.hotel).trim(),formatTime(hotelTime),45,{kind:'hotel',locked:true,notes:'用户填写的住宿名称；尚未确认具体地址与分店。'}));
    }
    if (fixed.length) {
      const fixedEnd = Math.max(...fixed.map(item => timeMinutes(item.time) + item.duration));
      data.pagoda.time = formatTime(Math.max(16 * 60,fixedEnd + 15));
      data.meal.time = formatTime(Math.max(18 * 60 + 30,timeMinutes(data.pagoda.time) + data.pagoda.duration + 30));
    }
    first.stops = [...fixed,...first.stops.filter(item => Number.isFinite(timeMinutes(item.time)) && timeMinutes(item.time) + item.duration <= 1440)];
    if (form.returnTime) {
      const last = days[count-1];
      const departure = stop('return','返程',form.returnTime,30,{kind:'return',locked:true,notes:'用户填写的返程约束；请自行预留到站和候车时间。'});
      if (timeMinutes(departure.time) + departure.duration > 1440) return {trip:null,error:'返程安排跨越午夜，请调整返程时间。'};
      const fixedConflict = last.stops.find(item => item.locked && timeMinutes(item.time) + item.duration > timeMinutes(departure.time));
      if (fixedConflict) return {trip:null,error:`返程时间与固定安排「${fixedConflict.title}」冲突，请调整条件。`};
      last.stops = [...last.stops.filter(item => timeMinutes(item.time) + item.duration <= timeMinutes(departure.time)),departure];
    }
    for (const day of days) { day.stops.sort((a,b) => timeMinutes(a.time) - timeMinutes(b.time)); const error = dayError(day); if (error) return {trip:null,error}; }
    return {trip:{id:uid('trip'),title:`杭州${count}日行程`,date:form.undated ? null : form.date,undated:Boolean(form.undated),days,version:1,budget:{stay:880,food:480,transport:180,tickets:0,misc:100},preparation:{umbrella:false,shoes:false,booking:false},sources:clone(sources),constraints:{arrival:form.arrival || '',returnTime:form.returnTime || '',hotel:String(form.hotel || '').trim()},updatedAt:now()},error:''};
  }
  const locate = (trip,id) => { for (let dayIndex = 0; dayIndex < (trip?.days?.length || 0); dayIndex++) { const index = trip.days[dayIndex].stops.findIndex(item => item.id === id); if (index !== -1) return {dayIndex,index,stop:trip.days[dayIndex].stops[index]}; } return null; };
  function snapshot(state) {
    const checkpoint = {trip:clone(state.trip),activeDay:state.activeDay,selectedId:state.selectedId};
    state.history = [...(Array.isArray(state.history) ? state.history : []),checkpoint].slice(-10);
    return clone(checkpoint);
  }
  function validateEditor(trip, editor) {
    if (!trip || !editor) return '没有可提交的编辑内容。';
    if (!Number.isInteger(Number(editor.dayIndex)) || !trip.days[Number(editor.dayIndex)]) return '请选择有效的日期。';
    if (typeof editor.title !== 'string' || !editor.title.trim()) return '请填写安排名称。';
    if (!Number.isFinite(timeMinutes(editor.time))) return '开始时间无效，请使用 00:00–23:59。';
    if (!Number.isInteger(Number(editor.duration)) || Number(editor.duration) < 1) return '停留时长须为正整数分钟。';
    if (timeMinutes(editor.time) + Number(editor.duration) > 1440) return '这项安排跨越午夜，请缩短时长或换一天。';
    const found = editor.isNew ? null : locate(trip,editor.id);
    if (!editor.isNew && !found) return '这项安排已不存在，请关闭后重试。';
    if (found?.stop.locked && found.dayIndex !== Number(editor.dayIndex)) return '固定安排不能移动；请先明确解除固定。';
    const candidate = {id:found?.stop.id || '__new__',title:editor.title.trim(),time:editor.time,duration:Number(editor.duration),locked:Boolean(editor.locked)};
    return dayError({stops:[...trip.days[Number(editor.dayIndex)].stops.filter(item => item.id !== found?.stop.id),candidate]});
  }
  const touch = state => { state.trip.version += 1; state.trip.updatedAt = now(); };
  function commitEditor(state) {
    const editor = state.editor;
    const error = validateEditor(state.trip,editor);
    if (error) return {ok:false,error};
    const found = editor.isNew ? null : locate(state.trip,editor.id);
    const dayIndex = Number(editor.dayIndex);
    const item = {...(found ? clone(found.stop) : stop(uid('manual'),'','',30)),title:editor.title.trim(),time:editor.time,duration:Number(editor.duration),notes:String(editor.notes || ''),locked:Boolean(editor.locked),manuallyEdited:true};
    const changed = !found || ['title','time','duration','notes','locked'].some(key => item[key] !== found.stop[key]) || dayIndex !== found.dayIndex;
    if (!changed) { state.editor = null; return {ok:true,error:'',unchanged:true}; }
    snapshot(state);
    const routeChanged = !found || item.time !== found.stop.time || item.duration !== found.stop.duration || dayIndex !== found.dayIndex;
    if (found) { state.trip.days[found.dayIndex].stops.splice(found.index,1); if (routeChanged) state.trip.days[found.dayIndex].routeDirty = true; }
    state.trip.days[dayIndex].stops.push(item);
    state.trip.days[dayIndex].stops.sort((a,b) => timeMinutes(a.time) - timeMinutes(b.time));
    if (routeChanged) state.trip.days[dayIndex].routeDirty = true;
    if (item.kind === 'arrival') state.trip.constraints.arrival = item.time;
    if (item.kind === 'return') state.trip.constraints.returnTime = item.time;
    if (item.kind === 'hotel') state.trip.constraints.hotel = item.title;
    state.activeDay = dayIndex; state.selectedId = item.id; state.editor = null;
    touch(state);
    return {ok:true,error:'',id:item.id};
  }
  function removeStop(state,id) {
    const found = locate(state.trip,id);
    if (!found) return {ok:false,error:'这项安排已不存在。'};
    if (found.stop.locked) return {ok:false,error:'固定安排不能移除；请先明确解除固定。'};
    snapshot(state);
    const day = state.trip.days[found.dayIndex];
    day.stops.splice(found.index,1); day.routeDirty = true;
    if (state.selectedId === id) state.selectedId = day.stops[found.index]?.id || day.stops[found.index-1]?.id || null;
    touch(state);
    return {ok:true,error:''};
  }
  function moveStop(state,id,targetDayIndex,targetIndex,targetTime) {
    const found = locate(state.trip,id);
    const dayIndex = Number(targetDayIndex);
    if (!found) return {ok:false,error:'这项安排已不存在。'};
    if (found.stop.locked) return {ok:false,error:'固定安排不能移动；请先明确解除固定。'};
    if (!Number.isInteger(dayIndex) || !state.trip.days[dayIndex]) return {ok:false,error:'请选择有效的目标日期。'};
    if (targetTime == null && found.dayIndex === dayIndex) return {ok:false,error:'调整顺序时请明确选择新的开始时间。'};
    const time = targetTime == null ? found.stop.time : targetTime;
    const error = validateEditor(state.trip,{...found.stop,dayIndex,time,isNew:false});
    if (error) return {ok:false,error};
    if (found.dayIndex === dayIndex && time === found.stop.time) return {ok:false,error:'开始时间没有变化，请选择新的时间。'};
    snapshot(state);
    state.trip.days[found.dayIndex].stops.splice(found.index,1);
    const moved = {...found.stop,time,manuallyEdited:true};
    state.trip.days[dayIndex].stops.push(moved);
    state.trip.days[dayIndex].stops.sort((a,b) => timeMinutes(a.time) - timeMinutes(b.time));
    state.trip.days[found.dayIndex].routeDirty = true; state.trip.days[dayIndex].routeDirty = true;
    state.activeDay = dayIndex; state.selectedId = id; touch(state);
    return {ok:true,error:''};
  }
  function undo(state) {
    if (!state.history?.length) return {ok:false,error:'暂无可以撤销的修改。'};
    const checkpoint = state.history[state.history.length-1];
    if (!checkpoint.trip) return {ok:false,error:'无法恢复这份历史记录。'};
    const version = Math.max(state.trip?.version || 0,checkpoint.trip.version || 0) + 1;
    state.history.pop(); state.trip = clone(checkpoint.trip); state.trip.version = version; state.trip.updatedAt = now();
    state.activeDay = Math.max(0,Math.min(checkpoint.activeDay || 0,state.trip.days.length-1));
    state.selectedId = locate(state.trip,checkpoint.selectedId) ? checkpoint.selectedId : state.trip.days[state.activeDay].stops[0]?.id || null;
    state.proposal = null; state.editor = null;
    return {ok:true,error:''};
  }
  const protectedStop = item => item.locked || item.manuallyEdited || item.completed || item.skipped || item.status === 'completed' || ['arrival','hotel','return'].includes(item.kind);
  function makeProposal(state,prompt,scope = 'day') {
    if (!state.trip) return {proposal:null,error:'请先创建一份行程。'};
    if (!['day','remaining','trip'].includes(scope)) return {proposal:null,error:'请选择这一天、今天剩余或整趟行程。'};
    const text = String(prompt || '').trim();
    const delay = /晚出发|晚起|推迟|延后|晚[一1]小时/.test(text);
    const reduce = /少走|休息|轻松|删减|减少|不要太赶/.test(text);
    if (!delay && !reduce) return {proposal:null,error:'本地规则演示支持“少走一点”和“晚出发一小时”，请选一个明确的调整。'};
    const activeDay = Number(state.activeDay) || 0;
    const selected = locate(state.trip,state.selectedId);
    const cutoff = scope === 'remaining' ? timeMinutes(state.remainingFrom || (selected?.dayIndex === activeDay ? selected.stop.time : '00:00')) : 0;
    const indexes = scope === 'trip' ? state.trip.days.map((_,index) => index) : [activeDay];
    const changes = [], preserved = [];
    for (const dayIndex of indexes) {
      const day = state.trip.days[dayIndex];
      if (!day) return {proposal:null,error:'请选择有效的日期。'};
      const eligible = day.stops.filter(item => !protectedStop(item) && timeMinutes(item.time) >= cutoff);
      preserved.push(...day.stops.filter(item => protectedStop(item) || timeMinutes(item.time) < cutoff).map(item => item.title));
      if (delay) {
        for (const item of eligible) {
          const newTime = formatTime(timeMinutes(item.time) + 60);
          if (!newTime) return {proposal:null,error:`「${item.title}」顺延后跨越午夜，暂不能应用；请缩短或移除可选安排。`};
          changes.push({id:item.id,type:'update',dayIndex,before:clone(item),after:{...clone(item),time:newTime},reason:'在保留固定和手动修改安排的前提下，开始时间顺延 1 小时。'});
        }
      } else {
        const visits = eligible.filter(item => item.kind === 'visit');
        const item = visits.find(item => item.id === 'jiuxi') || visits[visits.length-1];
        if (item) changes.push({id:item.id,type:'remove',dayIndex,before:clone(item),after:null,reason:'减少一站游览，为休息留下空档；其余安排保持原时间。'});
      }
    }
    if (!changes.length) return {proposal:null,error:'这个范围内没有可调整的安排；固定项、已完成项与手动修改均已保留。'};
    const proposed = clone(state.trip);
    for (const change of changes) { const day = proposed.days[change.dayIndex]; const index = day.stops.findIndex(item => item.id === change.id); if (change.type === 'remove') day.stops.splice(index,1); else day.stops[index] = clone(change.after); }
    for (const index of indexes) { const error = dayError(proposed.days[index]); if (error) return {proposal:null,error:`顺延无法排下：${error} 固定安排未移动，请先删减或换一天。`}; }
    const scopeLabel = scope === 'trip' ? '整趟行程' : `第 ${activeDay+1} 天${scope === 'remaining' ? '剩余安排' : ''}`;
    return {proposal:{baseVersion:state.trip.version,scope,prompt:text,changes,summary:delay ? `${scopeLabel}：${changes.length} 项安排顺延 1 小时。` : `${scopeLabel}：移除 ${changes.map(change => change.before.title).join('、')}，保留休息空档。`,preserved:[...new Set(preserved)],createdAt:now()},error:''};
  }
  function applyProposal(state, proposal = state.proposal) {
    if (!state.trip || !proposal || !Array.isArray(proposal.changes) || !proposal.changes.length) return {ok:false,error:'没有可应用的建议。'};
    if (proposal.baseVersion !== state.trip.version) return {ok:false,error:'建议已过期：行程已修改，请根据当前行程重新生成建议。'};
    const proposed = clone(state.trip), affected = new Set(), seen = new Set();
    for (const change of proposal.changes) {
      const found = locate(proposed,change.id);
      if (!found || seen.has(change.id) || JSON.stringify(found.stop) !== JSON.stringify(change.before)) return {ok:false,error:'建议已过期：相关安排已变化，请重新生成。'};
      if (protectedStop(found.stop)) return {ok:false,error:`「${found.stop.title}」已固定、完成或手动修改，请重新生成建议。`};
      seen.add(change.id); affected.add(found.dayIndex);
      if (change.type === 'remove') proposed.days[found.dayIndex].stops.splice(found.index,1);
      else if (change.type === 'update' && change.after?.id === change.id) {
        const allowed = {...clone(found.stop),time:change.after.time,duration:change.after.duration};
        if (JSON.stringify(allowed) !== JSON.stringify(change.after)) return {ok:false,error:'建议含有不支持的地点或证据变更，请重新生成。'};
        proposed.days[found.dayIndex].stops[found.index] = allowed;
      } else return {ok:false,error:'建议内容不完整，请重新生成。'};
    }
    for (const index of affected) { const error = dayError(proposed.days[index]); if (error) return {ok:false,error}; proposed.days[index].stops.sort((a,b) => timeMinutes(a.time) - timeMinutes(b.time)); proposed.days[index].routeDirty = true; }
    snapshot(state); state.trip = proposed; touch(state);
    if (!locate(state.trip,state.selectedId)) state.selectedId = state.trip.days[state.activeDay]?.stops[0]?.id || null;
    state.proposal = null;
    return {ok:true,error:''};
  }
  function persist(state, options = {}) {
    try {
      if (options.forceFailure) throw new Error('演示保存失败');
      if (typeof localStorage === 'undefined') throw new Error('无法访问本机存储');
      localStorage.setItem(STORAGE_KEY,JSON.stringify(state));
      return {ok:true,error:''};
    } catch (_) { return {ok:false,error:'保存到本机失败；当前内容仍在内存中，请重试或导出。'}; }
  }
  function validTrip(trip) {
    if (!trip || typeof trip.id !== 'string' || typeof trip.title !== 'string' || !Number.isInteger(trip.version) || trip.version < 1 || !Array.isArray(trip.days) || !trip.days.length || trip.days.length > 5 || !Array.isArray(trip.sources) || !trip.constraints || !trip.budget || !trip.preparation) return false;
    const ids = new Set(), sourceIds = new Set(trip.sources.map(source => source?.id));
    return trip.days.every(day => Array.isArray(day.stops) && !dayError(day) && day.stops.every(item => {
      if (typeof item.id !== 'string' || !item.id || ids.has(item.id) || typeof item.title !== 'string' || !item.title.trim() || !Array.isArray(item.sourceIds) || item.sourceIds.some(id => !sourceIds.has(id)) || !['matched','pending','selected','manual'].includes(item.locationStatus) || !['arrival','hotel','visit','meal','return'].includes(item.kind) || typeof item.locked !== 'boolean' || typeof item.manuallyEdited !== 'boolean') return false;
      if (!((item.lng === null && item.lat === null) || (Number.isFinite(item.lng) && Number.isFinite(item.lat) && Math.abs(item.lng) <= 180 && Math.abs(item.lat) <= 90))) return false;
      ids.add(item.id); return true;
    }));
  }
  function restore() {
    try {
      if (typeof localStorage === 'undefined') return {state:null,error:'无法读取本机存储，请保持页面打开或导出草稿。'};
      const raw = localStorage.getItem(STORAGE_KEY);
      if (raw === null) return {state:null,error:''};
      const saved = JSON.parse(raw);
      if (!saved || saved.schema !== 2 || !['home','create','progress','detail'].includes(saved.screen) || !saved.form || typeof saved.form !== 'object' || !Array.isArray(saved.history) || (saved.trip !== null && !validTrip(saved.trip))) throw new Error('invalid saved draft');
      const state = {...fresh(),...saved,form:{...fresh().form,...saved.form}};
      state.history = state.history.filter(item => item && validTrip(item.trip)).slice(-10);
      state.activeDay = state.trip ? Math.max(0,Math.min(Number(state.activeDay) || 0,state.trip.days.length-1)) : 0;
      if (state.selectedId && !locate(state.trip,state.selectedId)) state.selectedId = null;
      if (state.proposal && (!Number.isInteger(state.proposal.baseVersion) || !Array.isArray(state.proposal.changes))) state.proposal = null;
      return {state,error:''};
    } catch (_) { return {state:null,error:'本机草稿暂时无法读取；原存储未删除，可新建行程或稍后重试。'}; }
  }
  return {fresh,createTrip,validateEditor,commitEditor,removeStop,moveStop,makeProposal,applyProposal,undo,snapshot,persist,restore,clone,timeMinutes,formatTime,STORAGE_KEY};
})();
