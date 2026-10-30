import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const modelSource = fs.readFileSync(new URL('./model.js',import.meta.url),'utf8');
const discoverySource = fs.readFileSync(new URL('./discovery-model.js',import.meta.url),'utf8');
const plain = value => JSON.parse(JSON.stringify(value));
function setup(ids = []) {
  const storage = new Map();
  const localStorage = {getItem:key => storage.get(key) ?? null,setItem:(key,value) => storage.set(key,String(value))};
  const context = vm.createContext({localStorage});
  vm.runInContext(`${modelSource}\n${discoverySource}\nglobalThis.api = {Model,Discovery};`,context);
  const {Model:model,Discovery:discovery} = context.api;
  const state = discovery.initial();
  state.researched = true;
  for (const id of ids) state.selected[id] = {priority:'want'};
  return {model,discovery,state,form:model.fresh().form,storage};
}
const selectedStops = trip => trip.days.flatMap(day => day.stops).filter(stop => stop.selectionId);
const fixedStops = trip => trip.days.flatMap(day => day.stops).filter(stop => !stop.selectionId);
function freeze(value) {
  if (value && typeof value === 'object') { Object.values(value).forEach(freeze); Object.freeze(value); }
  return value;
}

test('initial discovery is independent, empty and awaiting retrieval; cards retain evidence boundaries',() => {
  const {discovery} = setup(), first = discovery.initial(), second = discovery.initial();
  first.selected.pagoda = {priority:'must'}; first.custom.push({id:'mine'});
  assert.deepEqual(plain(second),{city:'杭州',category:'sights',query:'',selected:{},custom:[],researched:false});
  const cards = Object.fromEntries(discovery.catalog.map(card => [card.id,card]));
  assert.equal(discovery.catalog.length,8);
  assert.deepEqual(plain(cards.pagoda.sourceIds),['S1','S2']);
  assert.equal(cards.pagoda.photo,'pagoda'); assert.equal(cards.temple.photo,'temple'); assert.equal(cards.bridge.photo,'canal');
  assert.equal(cards.jiuxi.photo,null);
  assert.equal(cards.meal.title,'油爆虾'); assert.equal(cards.meal.kind,'meal');
  assert.equal(cards.meal.locationStatus,'pending'); assert.equal(cards.meal.pending,true);
  assert.equal(cards.meal.lng,null); assert.equal(cards.meal.lat,null); assert.equal(cards.meal.photo,null);
  assert.deepEqual(plain(cards.meal.sourceIds),['S1']);
  assert.deepEqual(plain(cards.canalwalk.sourceIds),['S2']); assert.equal(cards.canalwalk.photo,null); assert.equal(cards.canalwalk.lng,null);
  assert.deepEqual(plain(cards.teabreak.sourceIds),[]); assert.match(cards.teabreak.description,/并非从参考帖子提取/);
});

test('zero, unknown and invalid selections produce clear errors with no generated itinerary',() => {
  const {discovery,state,form} = setup();
  assert.match(discovery.validateSelection(state),/至少一个/);
  assert.equal(discovery.plan(form,state).trip,null);
  state.selected.gone = {priority:'want'};
  assert.match(discovery.validateSelection(state),/已不存在/);
  assert.equal(discovery.selected(state).length,0); assert.equal(discovery.plan(form,state).trip,null);
  delete state.selected.gone; state.selected.pagoda = {priority:'fixed'};
  assert.match(discovery.validateSelection(state),/想去/); assert.equal(discovery.plan(form,state).trip,null);
});

test('only chosen content reaches the generated trip while arrival, hotel and return remain fixed',() => {
  const {discovery,state,form} = setup(['bridge','meal']);
  const {trip,error,unplaced} = discovery.plan(form,state);
  assert.equal(error,''); assert.equal(unplaced.length,0);
  assert.deepEqual(plain(selectedStops(trip).map(stop => stop.selectionId)).sort(),['bridge','meal']);
  assert.deepEqual(plain(fixedStops(trip).map(stop => stop.id)).sort(),['arrival','hotel','return']);
  assert.ok(fixedStops(trip).every(stop => stop.locked && stop.lng === null && stop.sourceIds.length === 0));
  assert.equal(trip.title,'杭州3日行程'); assert.equal(trip.sources.length,2);
  assert.deepEqual(plain(trip.wishes.map(item => item.id)).sort(),['bridge','meal']);
  assert.match(trip.planningNote,/未计算真实路线/);
  assert.ok(!trip.days.flatMap(day => day.stops).some(stop => ['pagoda','temple','jiuxi','yongfu','xiaohe','bai'].includes(stop.id)));
});

test('must-see priority protects content without making its time fixed',() => {
  const {discovery,state,form} = setup(['pagoda','broken']);
  state.selected.pagoda.priority = 'must';
  const result = discovery.plan(form,state);
  assert.equal(result.error,'');
  const must = selectedStops(result.trip).find(stop => stop.id === 'pagoda');
  const want = selectedStops(result.trip).find(stop => stop.id === 'broken');
  assert.equal(must.mustKeep,true); assert.equal(must.locked,false); assert.equal(must.manuallyEdited,false);
  assert.equal(want.mustKeep,false); assert.equal(want.locked,false);
  assert.equal(must.priority,'must');
});

test('food-only planning preserves the dish and never substitutes a restaurant',() => {
  const {discovery,state,form} = setup(['meal']);
  const result = discovery.plan(form,state);
  assert.equal(result.error,'');
  const stops = selectedStops(result.trip);
  assert.equal(stops.length,1); assert.equal(stops[0].id,'meal'); assert.equal(stops[0].title,'油爆虾');
  assert.equal(stops[0].time,'18:00'); assert.equal(stops[0].kind,'meal');
  assert.equal(stops[0].lng,null); assert.equal(stops[0].lat,null); assert.equal(stops[0].locationStatus,'pending');
  assert.deepEqual(plain(stops[0].sourceIds),['S1']); assert.match(stops[0].notes,/尚未选择餐馆/);
  assert.deepEqual(plain(result.trip.sources.map(source => source.id)),['S1']);
});

test('all sample selections fit through regional day grouping and satisfy arrival, buffers and departure',() => {
  const {discovery,state,form,model} = setup();
  for (const card of discovery.catalog) state.selected[card.id] = {priority:'want'};
  const result = discovery.plan(form,state);
  assert.equal(result.error,''); assert.equal(selectedStops(result.trip).length,discovery.catalog.length);
  const allIds = result.trip.days.flatMap(day => day.stops).map(stop => stop.id);
  assert.equal(allIds.length,new Set(allIds).size);
  for (const [dayIndex,day] of result.trip.days.entries()) {
    const optional = day.stops.filter(stop => stop.selectionId);
    const fixedEnd = dayIndex === 0 ? Math.max(...day.stops.filter(stop => ['arrival','hotel'].includes(stop.kind)).map(stop => model.timeMinutes(stop.time) + stop.duration)) : 8.5 * 60;
    const departure = day.stops.find(stop => stop.kind === 'return');
    for (const [index,stop] of optional.entries()) {
      const start = model.timeMinutes(stop.time), end = start + stop.duration;
      assert.ok(start >= fixedEnd + 30); assert.ok(start >= 9 * 60); assert.ok(end <= 20 * 60);
      if (departure) assert.ok(end <= model.timeMinutes(departure.time) - 60);
      if (index) assert.ok(start >= model.timeMinutes(optional[index - 1].time) + optional[index - 1].duration + 30);
      assert.equal(model.validateEditor(result.trip,{...stop,dayIndex,isNew:false}),'');
    }
  }
  assert.ok(result.trip.days[1].stops.some(stop => stop.id === 'temple'));
  assert.ok(result.trip.days[2].stops.some(stop => stop.id === 'bridge'));
});

test('late arrival pushes choices to a feasible later day and preserves the entire arrival window',() => {
  const {discovery,state,form,model} = setup(['temple','meal']);
  const result = discovery.plan({...form,days:2,arrival:'18:00'},state);
  assert.equal(result.error,''); assert.equal(result.trip.days[0].stops.some(stop => stop.selectionId),false);
  assert.equal(result.trip.days[0].stops.find(stop => stop.id === 'hotel').time,'19:00');
  const last = result.trip.days[1].stops;
  assert.deepEqual(plain(last.filter(stop => stop.selectionId).map(stop => stop.id)).sort(),['meal','temple']);
  for (const stop of last.filter(stop => stop.selectionId)) assert.ok(model.timeMinutes(stop.time) + stop.duration <= model.timeMinutes(form.returnTime) - 60);
});

test('manual choices retain their title and duration without borrowing photos, positions or post evidence',() => {
  const {discovery,state,form} = setup();
  state.custom.push({id:'my-cafe',title:'自己的咖啡店',category:'experiences',region:'自行确认',duration:45,sourceIds:['S1'],photo:'temple',lng:120.1,lat:30.2,locationStatus:'matched',notes:'等我确认分店'});
  state.selected['my-cafe'] = {priority:'must'};
  const chosen = discovery.selected(state)[0];
  assert.deepEqual(plain(chosen.sourceIds),[]); assert.equal(chosen.photo,null); assert.equal(chosen.lng,null); assert.equal(chosen.lat,null); assert.equal(chosen.locationStatus,'manual');
  const result = discovery.plan(form,state);
  assert.equal(result.error,'');
  const stop = selectedStops(result.trip)[0];
  assert.equal(stop.title,'自己的咖啡店'); assert.equal(stop.duration,45); assert.equal(stop.notes,'等我确认分店');
  assert.equal(stop.mustKeep,true); assert.equal(stop.locked,false); assert.deepEqual(plain(stop.sourceIds),[]); assert.equal(stop.lng,null); assert.equal(stop.photo,null);
  assert.equal(result.trip.wishes[0].sourceIds.length,0);
  assert.deepEqual(plain(result.trip.sources),[]);
});

test('multiple selected foods occupy distinct lunch or dinner slots; extra foods are reported',() => {
  const {discovery,state,form} = setup(['meal']);
  for (let i = 1; i <= 3; i++) { state.custom.push({id:`food-${i}`,title:`我选的菜${i}`,category:'food',duration:60}); state.selected[`food-${i}`] = {priority:'want'}; }
  const result = discovery.plan({...form,days:2,arrival:'',returnTime:'',hotel:''},state);
  assert.equal(result.error,''); assert.equal(selectedStops(result.trip).length,4);
  for (const day of result.trip.days) assert.deepEqual(plain(day.stops.map(stop => stop.time)),['12:00','18:00']);
  state.custom.push({id:'food-4',title:'第5道菜',category:'food'}); state.selected['food-4'] = {priority:'want'};
  const overflow = discovery.plan({...form,days:2,arrival:'',returnTime:'',hotel:''},state);
  assert.equal(overflow.trip,null); assert.deepEqual(plain(overflow.unplaced),[{id:'food-4',title:'第5道菜'}]); assert.match(overflow.error,/第5道菜/);
});

test('insufficient capacity returns every unplaced wish including optional content and never returns a partial trip',() => {
  const {discovery,state,form} = setup();
  for (let i = 1; i <= 4; i++) { state.custom.push({id:`long-${i}`,title:`长时项目${i}`,category:'sights',duration:500}); state.selected[`long-${i}`] = {priority:i === 1 ? 'must' : 'want'}; }
  const before = JSON.stringify(state);
  const result = discovery.plan(form,state);
  assert.equal(result.trip,null); assert.equal(result.unplaced.length,3);
  assert.deepEqual(plain(result.unplaced.map(item => item.id)),['long-2','long-3','long-4']);
  for (const item of result.unplaced) assert.ok(result.error.includes(item.title));
  assert.equal(JSON.stringify(state),before); assert.equal(Object.keys(state.selected).length,4);
});

test('plans and selected-card reads never mutate form, discovery or cached catalog',() => {
  const {discovery,state,form} = setup(['pagoda','meal','teabreak']);
  const before = JSON.stringify({state,form,catalog:discovery.catalog});
  freeze(state); freeze(form);
  assert.equal(discovery.plan(form,state).error,'');
  const chosen = discovery.selected(state); chosen[0].title = '改名'; chosen[0].sourceIds.push('fiction');
  assert.equal(JSON.stringify({state,form,catalog:discovery.catalog}),before);
  assert.equal(discovery.selected(state)[0].title,'雷峰塔');
});

test('one through five days and undated trips keep the core save/restore contract',() => {
  const {discovery,state,form,model} = setup(['meal']);
  for (let days = 1; days <= 5; days++) {
    const result = discovery.plan({...form,days,arrival:'',hotel:'',returnTime:'',date:'2026-12-30'},state);
    assert.equal(result.error,''); assert.equal(result.trip.days.length,days);
    if (days === 5) assert.equal(result.trip.days[4].date,'2027-01-03');
    const saved = model.fresh(); saved.screen = 'detail'; saved.trip = result.trip; saved.discovery = state;
    assert.equal(model.persist(saved).ok,true); assert.equal(model.restore().error,''); assert.deepEqual(plain(model.restore().state.trip),plain(result.trip));
  }
  const undated = discovery.plan({...form,undated:true,date:''},state);
  assert.equal(undated.error,''); assert.equal(undated.trip.date,null); assert.ok(undated.trip.days.every(day => day.date === null));
});

test('invalid trip constraints or manual selections fail without changing the selection',() => {
  const {discovery,state,form} = setup(['pagoda']);
  for (const patch of [{days:0},{days:6},{date:'2026-02-30'},{arrival:'24:00'},{returnTime:'23:50'},{days:1,returnTime:'14:15'}]) {
    const before = JSON.stringify(state), result = discovery.plan({...form,...patch},state);
    assert.equal(result.trip,null); assert.ok(result.error); assert.equal(JSON.stringify(state),before);
  }
  state.city = '上海'; assert.match(discovery.plan(form,state).error,/杭州/); state.city = '杭州';
  state.custom.push({id:'mine',title:'时长未填好',category:'sights',duration:0}); state.selected.mine = {priority:'want'};
  assert.match(discovery.validateSelection(state),/正整数/);
  state.custom[0].duration = 60; state.custom[0].id = 'arrival';
  assert.match(discovery.validateSelection(state),/标识/); assert.equal(discovery.plan(form,state).trip,null);
});
