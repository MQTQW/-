import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('./model.js',import.meta.url),'utf8');
function setup() {
  const storage = new Map();
  const localStorage = {getItem:key => storage.has(key) ? storage.get(key) : null,setItem:(key,value) => storage.set(key,String(value))};
  const context = vm.createContext({localStorage});
  vm.runInContext(`${source}\nglobalThis.model = Model;`,context);
  const model = context.model;
  const state = model.fresh();
  state.trip = model.createTrip(state.form).trip;
  return {model,state,storage,localStorage};
}
const serialized = value => JSON.stringify(value);
const item = (state,id) => state.trip.days.flatMap(day => day.stops).find(stop => stop.id === id);
const edit = (state,id,patch = {}) => { const dayIndex = state.trip.days.findIndex(day => day.stops.some(stop => stop.id === id)); state.editor = {...item(state,id),id,dayIndex,isNew:false,...patch}; return state.editor; };

test('fresh state is independent and supports the full navigation contract',() => {
  const {model} = setup(), first = model.fresh(), second = model.fresh();
  first.form.destination = '上海'; first.history.push({});
  assert.equal(second.form.destination,'杭州'); assert.equal(second.history.length,0);
  assert.equal(second.schema,2); assert.equal(second.screen,'home'); assert.equal(second.tab,'overview'); assert.equal(second.view,'list');
});

test('default creation honors fixed arrival, hotel and final departure without inventing route positions',() => {
  const {state,model} = setup();
  assert.equal(state.trip.days.length,3);
  assert.equal(item(state,'arrival').time,'14:00'); assert.equal(item(state,'hotel').time,'15:00');
  assert.equal(item(state,'pagoda').time,'16:00'); assert.equal(item(state,'meal').time,'18:30');
  assert.equal(state.trip.days[2].stops.at(-1).id,'return'); assert.equal(item(state,'return').time,'16:30');
  for (const id of ['arrival','hotel','return']) { assert.equal(item(state,id).locked,true); assert.equal(item(state,id).lng,null); assert.equal(item(state,id).lat,null); assert.equal(item(state,id).sourceIds.length,0); }
  for (const [dayIndex,day] of state.trip.days.entries()) for (const stop of day.stops) assert.equal(model.validateEditor(state.trip,{...stop,dayIndex,isNew:false}),'');
});

test('one through five days remain conflict-free with unique stop identities and calendar rollover',() => {
  const {model} = setup();
  for (let count = 1; count <= 5; count++) {
    const result = model.createTrip({...model.fresh().form,days:count,date:'2026-12-30'});
    assert.equal(result.error,''); assert.equal(result.trip.days.length,count);
    const stops = result.trip.days.flatMap(day => day.stops);
    assert.equal(stops.length,new Set(stops.map(stop => stop.id)).size);
    for (const [dayIndex,day] of result.trip.days.entries()) for (const stop of day.stops) assert.equal(model.validateEditor(result.trip,{...stop,dayIndex,isNew:false}),'');
    if (count === 5) assert.equal(result.trip.days[4].date,'2027-01-03');
  }
});

test('undated creation does not invent dates; invalid city, dates, durations and constraints fail clearly',() => {
  const {model} = setup();
  const pending = model.createTrip({...model.fresh().form,undated:true,date:''});
  assert.equal(pending.error,''); assert.equal(pending.trip.date,null); assert.ok(pending.trip.days.every(day => day.date === null));
  for (const patch of [{destination:'上海'},{date:'2026-02-30'},{days:0},{days:6},{days:1,returnTime:'14:15'},{arrival:'24:00'},{arrival:'23:15'},{returnTime:'23:50'}]) {
    const result = model.createTrip({...model.fresh().form,...patch}); assert.equal(result.trip,null); assert.ok(result.error);
  }
});

test('late arrival shifts only optional first-day content and never produces morning visits',() => {
  const {model} = setup();
  const result = model.createTrip({...model.fresh().form,arrival:'18:00'});
  assert.equal(result.error,''); assert.equal(result.trip.days[0].stops[0].time,'18:00');
  assert.equal(result.trip.days[0].stops[1].time,'19:00');
  assert.ok(result.trip.days[0].stops.every(stop => model.timeMinutes(stop.time) >= 18 * 60));
});

test('invalid editor commits preserve both committed trip and recoverable editor draft',() => {
  const {state,model} = setup();
  for (const patch of [{title:'  '},{time:'9:00'},{time:'24:00'},{duration:0},{duration:-1},{duration:1.5},{time:'23:30',duration:60},{time:'15:15'},{dayIndex:9}]) {
    edit(state,'pagoda',patch);
    const before = serialized(state);
    const result = model.commitEditor(state);
    assert.equal(result.ok,false); assert.ok(result.error); assert.equal(serialized(state),before);
  }
});

test('display-name and notes editing preserves matched coordinates, evidence and route status',() => {
  const {state,model} = setup();
  state.trip.days.forEach(day => day.routeDirty = false);
  const original = model.clone(item(state,'pagoda'));
  edit(state,'pagoda',{title:'下午去看夕照',notes:'先查了出处再回来继续写',sourceIds:['fiction'],lng:1,lat:1,locationStatus:'selected'});
  assert.equal(model.commitEditor(state).ok,true);
  const committed = item(state,'pagoda');
  assert.equal(committed.title,'下午去看夕照'); assert.equal(committed.notes,'先查了出处再回来继续写');
  assert.equal(committed.lng,original.lng); assert.equal(serialized(committed.sourceIds),serialized(original.sourceIds));
  assert.equal(committed.locationStatus,'matched'); assert.equal(committed.manuallyEdited,true);
  assert.ok(state.trip.days.every(day => day.routeDirty === false)); assert.equal(state.trip.version,2); assert.equal(state.editor,null);
});

test('time changes invalidate only the affected day; overlap with a fixed item is rejected',() => {
  const {state,model} = setup(); state.trip.days.forEach(day => day.routeDirty = false);
  edit(state,'pagoda',{time:'15:15'}); assert.match(model.commitEditor(state).error,/固定安排/);
  edit(state,'pagoda',{time:'16:15'}); assert.equal(model.commitEditor(state).ok,true);
  assert.equal(state.trip.days[0].routeDirty,true); assert.equal(state.trip.days[1].routeDirty,false); assert.equal(state.trip.days[2].routeDirty,false);
});

test('new user stops have independent IDs, no borrowed source and no fabricated coordinates',() => {
  const {state,model} = setup();
  state.editor = {isNew:true,dayIndex:0,title:'自己找的咖啡店',time:'20:00',duration:'45',notes:'待选择分店',sourceIds:['S1'],lng:120,lat:30};
  const result = model.commitEditor(state); assert.equal(result.ok,true);
  const added = item(state,result.id); assert.equal(added.sourceIds.length,0); assert.equal(added.lng,null); assert.equal(added.lat,null);
  assert.equal(added.locationStatus,'manual'); assert.equal(added.manuallyEdited,true); assert.equal(added.duration,45);
  assert.equal(state.trip.days[0].stops.at(-1).id,result.id);
});

test('canceling an editor by discarding its draft cannot modify the trip',() => {
  const {state} = setup(), before = serialized(state.trip);
  edit(state,'pagoda',{notes:'不应提交的草稿'}); state.editor = null;
  assert.equal(serialized(state.trip),before);
});

test('locked removal and moves fail atomically; optional removal has a meaningful undo',() => {
  const {state,model} = setup();
  const before = serialized(state);
  assert.equal(model.removeStop(state,'hotel').ok,false); assert.equal(model.moveStop(state,'return',1,0,'08:00').ok,false);
  assert.equal(serialized(state),before);
  state.activeDay = 1; state.selectedId = 'jiuxi';
  assert.equal(model.removeStop(state,'jiuxi').ok,true); assert.equal(item(state,'jiuxi'),undefined); assert.notEqual(state.selectedId,'jiuxi');
  assert.equal(model.undo(state).ok,true); assert.ok(item(state,'jiuxi')); assert.equal(state.selectedId,'jiuxi'); assert.equal(state.activeDay,1); assert.equal(state.trip.version,3);
});

test('cross-day move validates conflicts and preserves original evidence without rescheduling neighbors',() => {
  const {state,model} = setup(); state.trip.days.forEach(day => day.routeDirty = false);
  const before = serialized(state), original = model.clone(item(state,'temple'));
  assert.equal(model.moveStop(state,'temple',2,0,'09:00').ok,false); assert.equal(serialized(state),before);
  assert.equal(model.moveStop(state,'temple',2,2,'12:30').ok,true);
  assert.equal(state.trip.days[1].stops.some(stop => stop.id === 'temple'),false);
  assert.equal(item(state,'temple').time,'12:30'); assert.equal(item(state,'xiaohe').time,'09:00'); assert.equal(item(state,'return').time,'16:30');
  assert.equal(serialized(item(state,'temple').sourceIds),serialized(original.sourceIds)); assert.equal(item(state,'temple').lng,original.lng);
  assert.equal(state.trip.days[0].routeDirty,false); assert.equal(state.trip.days[1].routeDirty,true); assert.equal(state.trip.days[2].routeDirty,true);
  assert.equal(model.undo(state).ok,true); assert.equal(state.trip.days[1].stops[0].id,'temple');
});

test('chronological reorder requires an explicit valid time and does not use an ambiguous list index',() => {
  const {state,model} = setup();
  assert.equal(model.moveStop(state,'bridge',2,0).ok,false);
  assert.equal(model.moveStop(state,'bridge',2,0,'08:00').ok,true);
  assert.equal(state.trip.days[2].stops[0].id,'bridge'); assert.equal(item(state,'xiaohe').time,'09:00');
});

test('AI preview is pure, describes material changes and applies only after explicit apply',() => {
  const {state,model} = setup(); state.activeDay = 1;
  const before = serialized(state), result = model.makeProposal(state,'少走一点','day');
  assert.equal(result.error,''); assert.equal(serialized(state),before); assert.equal(result.proposal.changes[0].type,'remove'); assert.equal(result.proposal.changes[0].id,'jiuxi');
  state.proposal = result.proposal; assert.equal(model.applyProposal(state).ok,true);
  assert.equal(item(state,'jiuxi'),undefined); assert.equal(item(state,'temple').time,'09:00'); assert.equal(state.trip.version,2); assert.equal(state.proposal,null);
  assert.equal(model.undo(state).ok,true); assert.ok(item(state,'jiuxi')); assert.equal(state.trip.version,3);
});

test('AI delay protects fixed, manually changed and completed stops',() => {
  const {state,model} = setup(); state.activeDay = 1;
  item(state,'temple').locked = true; item(state,'yongfu').manuallyEdited = true;
  const result = model.makeProposal(state,'晚出发一小时','day');
  assert.equal(result.error,''); assert.equal(result.proposal.changes.length,1); assert.equal(result.proposal.changes[0].id,'jiuxi');
  assert.ok(result.proposal.preserved.includes('灵隐寺')); assert.ok(result.proposal.preserved.includes('永福寺'));
  state.proposal = result.proposal; assert.equal(model.applyProposal(state).ok,true); assert.equal(item(state,'temple').time,'09:00'); assert.equal(item(state,'yongfu').time,'11:45'); assert.equal(item(state,'jiuxi').time,'15:00');
  item(state,'jiuxi').completed = true;
  assert.ok(model.makeProposal(state,'少走一点','day').error);
});

test('reducing walking never removes a meal when all visits are fixed or manually edited',() => {
  const {state,model} = setup(); state.activeDay = 0;
  item(state,'pagoda').manuallyEdited = true;
  const before = serialized(state);
  const result = model.makeProposal(state,'少走一点','day');
  assert.equal(result.proposal,null); assert.ok(result.error);
  assert.ok(item(state,'meal')); assert.equal(item(state,'meal').manuallyEdited,false);
  assert.equal(serialized(state),before);
});

test('remaining scope starts at the selected stop and excludes earlier visits',() => {
  const {state,model} = setup(); state.activeDay = 1; state.selectedId = 'jiuxi';
  const result = model.makeProposal(state,'晚出发一小时','remaining');
  assert.equal(result.error,''); assert.equal(result.proposal.changes.length,1); assert.equal(result.proposal.changes[0].id,'jiuxi');
  assert.ok(result.proposal.preserved.includes('灵隐寺'));
});

test('AI scheduling conflicts with fixed return produce no proposal and preserve the original plan',() => {
  const {state,model} = setup(); state.activeDay = 2;
  item(state,'bridge').time = '15:30'; item(state,'bridge').duration = 45;
  const before = serialized(state);
  const result = model.makeProposal(state,'晚出发一小时','day');
  assert.equal(result.proposal,null); assert.match(result.error,/重叠/); assert.equal(serialized(state),before);
});

test('manual changes invalidate old proposals; failed applies are fully atomic',() => {
  const {state,model} = setup(); state.activeDay = 1;
  state.proposal = model.makeProposal(state,'少走一点','day').proposal;
  edit(state,'temple',{notes:'预约已确认'}); assert.equal(model.commitEditor(state).ok,true);
  const before = serialized(state), result = model.applyProposal(state);
  assert.equal(result.ok,false); assert.match(result.error,/过期/); assert.equal(serialized(state),before);
  assert.equal(model.undo(state).ok,true); assert.equal(state.trip.version,3); assert.equal(state.proposal,null);
});

test('tampered proposals cannot change evidence or overwrite a newly fixed stop',() => {
  const {state,model} = setup(); state.activeDay = 1;
  state.proposal = model.makeProposal(state,'晚出发一小时','day').proposal;
  state.proposal.changes[0].after.sourceIds = ['fiction'];
  const before = serialized(state);
  assert.equal(model.applyProposal(state).ok,false); assert.equal(serialized(state),before);
  state.proposal = model.makeProposal(state,'少走一点','day').proposal; item(state,'jiuxi').locked = true;
  const fixed = serialized(state); assert.equal(model.applyProposal(state).ok,false); assert.equal(serialized(state),fixed);
});

test('undo checkpoints are independent and limited to ten committed changes',() => {
  const {state,model} = setup();
  for (let i = 0; i < 12; i++) { edit(state,'pagoda',{notes:`修改 ${i}`}); assert.equal(model.commitEditor(state).ok,true); }
  assert.equal(state.history.length,10); assert.equal(state.history[0].trip.days[0].stops.find(stop => stop.id === 'pagoda').notes,'修改 1');
  state.tab = 'itinerary'; const version = state.trip.version;
  for (let i = 0; i < 10; i++) assert.equal(model.undo(state).ok,true);
  assert.equal(state.trip.version,version+10); assert.equal(state.tab,'itinerary'); assert.equal(item(state,'pagoda').notes,'修改 1'); assert.equal(model.undo(state).ok,false);
});

test('persistent reload preserves pending locations, editor drafts, budget, preparation and proposals',() => {
  const {state,model} = setup(); state.screen = 'detail'; state.activeDay = 1;
  edit(state,'pagoda',{notes:'查出处回来继续'}); state.trip.budget.food = 600; state.trip.preparation.umbrella = true;
  state.proposal = model.makeProposal(state,'晚出发一小时','day').proposal;
  assert.equal(model.persist(state).ok,true);
  const restored = model.restore(); assert.equal(restored.error,''); assert.equal(serialized(restored.state),serialized(state));
  assert.equal(item(restored.state,'meal').locationStatus,'pending'); assert.equal(item(restored.state,'meal').lng,null);
});

test('storage failures preserve in-memory draft and previous saved version; malformed storage is recoverable',() => {
  const {state,model,storage} = setup();
  assert.equal(model.restore().state,null); assert.equal(model.restore().error,'');
  storage.set('unrelated-application','untouched'); assert.equal(model.persist(state).ok,true);
  const saved = storage.get(model.STORAGE_KEY); edit(state,'pagoda',{notes:'尚未落盘'});
  const before = serialized(state); assert.equal(model.persist(state,{forceFailure:true}).ok,false); assert.equal(serialized(state),before); assert.equal(storage.get(model.STORAGE_KEY),saved);
  for (const malformed of ['{broken',JSON.stringify({...state,schema:1}),JSON.stringify({...state,trip:{version:1}})]) {
    storage.set(model.STORAGE_KEY,malformed); const restored = model.restore(); assert.equal(restored.state,null); assert.ok(restored.error); assert.equal(storage.get(model.STORAGE_KEY),malformed); assert.equal(storage.get('unrelated-application'),'untouched');
  }
});
