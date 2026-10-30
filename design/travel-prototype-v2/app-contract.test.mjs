import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const modelSource = fs.readFileSync(new URL('./model.js',import.meta.url),'utf8');
const appSource = fs.readFileSync(new URL('./app.js',import.meta.url),'utf8');
const bindingMarker = "document.getElementById('app').addEventListener('click'";
assert.ok(appSource.includes(bindingMarker),'The headless harness must stop before DOM event binding.');

// Exercise the real controller, persistence and model. Only rendering, DOM surfaces
// and timers are stubbed; these tests deliberately make no browser/layout claims.
function harness() {
  const storage = new Map(), timers = new Map();
  let timerId = 0;
  const document = {
    activeElement:{dataset:{},id:''},
    querySelector:() => null,
    querySelectorAll:() => [],
    getElementById:() => ({focus(){}})
  };
  const context = vm.createContext({
    document,
    localStorage:{getItem:key => storage.get(key) ?? null,setItem:(key,value) => storage.set(key,String(value))},
    setTimeout:callback => { timers.set(++timerId,callback); return timerId; },
    clearTimeout:id => timers.delete(id),
    esc:value => String(value ?? ''),icon:() => '',button:() => '',sourceChips:() => ''
  });
  vm.runInContext(`${modelSource}\n${appSource.slice(0,appSource.indexOf(bindingMarker))}\nrender = () => {};`,context);
  const run = code => vm.runInContext(code,context);
  run(`
    s = Model.fresh(); s.otherTrips = [];
    s.trip = Model.createTrip(s.form).trip;
    s.trip.creationForm = Model.clone(s.form);
    s.screen = 'detail'; layers = [];
    function click(action,dataset={}) { return act(action,{dataset}); }
  `);
  return {
    run,
    value:code => JSON.parse(JSON.stringify(run(code))),
    tick() { const next = timers.entries().next().value; assert.ok(next,'Expected a pending job stage'); timers.delete(next[0]); next[1](); }
  };
}

test('background completion saves a ready draft without replacing the active trip, editor or layer stack',() => {
  const h = harness();
  h.run(`
    const activeTripId = s.trip.id;
    startEditor('pagoda'); s.editor.notes = '仍在编辑的个人备注';
    openLayer('source','S2');
    const editorBefore = JSON.stringify(s.editor), layersBefore = JSON.stringify(layers);
    s.job = {status:'running',step:3,scenario:'normal',form:{...Model.clone(s.form),days:4}};
    runJob();
  `);
  h.tick();
  assert.equal(h.run('s.trip.id === activeTripId'),true);
  assert.equal(h.run('JSON.stringify(s.editor) === editorBefore'),true);
  assert.equal(h.run('JSON.stringify(layers) === layersBefore'),true);
  assert.equal(h.run('s.job.status'),'done');
  assert.equal(h.run('s.otherTrips.length'),1);
  assert.equal(h.run('s.otherTrips[0].trip.days.length'),4);
  assert.equal(h.run('s.otherTrips[0].trip.id === s.job.resultId'),true);
  assert.equal(h.run('Model.restore().state.editor.notes'),'仍在编辑的个人备注');
});

test('completion while viewing progress can activate its result and archives the previous trip',() => {
  const h = harness();
  h.run(`const oldTripId=s.trip.id; s.screen='progress'; s.job={status:'running',step:3,scenario:'normal',form:Model.clone(s.form)}; runJob();`);
  h.tick();
  assert.equal(h.run('s.screen'),'detail'); assert.equal(h.run('s.job'),null);
  assert.equal(h.run('s.trip.id !== oldTripId'),true);
  assert.equal(h.run('s.otherTrips[0].trip.id === oldTripId'),true);
});

test('source navigation cannot overwrite an unsaved editor; returning restores the original draft',() => {
  const h = harness();
  h.run(`
    startEditor('pagoda'); s.editor.notes='查过出处后继续写';
    const draftBefore=JSON.stringify(s.editor);
    click('open-source',{id:'S2'});
    click('source-node',{id:'temple'});
    click('edit',{id:'temple'});
  `);
  assert.equal(h.run('currentLayer().type'),'edit-conflict');
  assert.equal(h.run('JSON.stringify(s.editor) === draftBefore'),true);
  assert.equal(h.run("findNode('pagoda').notes === s.editor.notes"),false);
  h.run("click('keep-original-edit');");
  assert.deepEqual(h.value('layers.map(layer=>layer.type)'),['edit']);
  assert.equal(h.run('s.editor.id'),'pagoda');
  assert.equal(h.run('s.editor.notes'),'查过出处后继续写');
  h.run("click('commit-editor');");
  assert.equal(h.run("findNode('pagoda').notes"),'查过出处后继续写');
  assert.equal(h.run('s.editor'),null);
});

test('explicitly discarding the original draft permits switching to another editor',() => {
  const h = harness();
  h.run(`startEditor('pagoda'); s.editor.notes='用户明确选择丢弃'; startEditor('temple'); click('switch-edit');`);
  assert.equal(h.run('s.editor.id'),'temple');
  assert.deepEqual(h.value('layers.map(layer=>layer.type)'),['edit']);
  assert.equal(h.run("findNode('pagoda').notes.includes('用户明确选择丢弃')"),false);
  assert.equal(h.run('Model.restore().state.editor.id'),'temple');
});

test('replacing a place preserves committed user notes and separates location evidence; undo restores the original place',() => {
  const h = harness();
  h.run(`
    startEditor('pagoda'); s.editor.notes='自己的集合地点和联系电话'; click('commit-editor');
    const original=Model.clone(findNode('pagoda')), beforeVersion=s.trip.version;
    click('confirm',{id:'pagoda'}); sheetContent(currentLayer());
    click('choose-location',{id:'broken'}); click('commit-location');
  `);
  assert.equal(h.run("findNode('pagoda').notes"),'自己的集合地点和联系电话');
  assert.equal(h.run("findNode('pagoda').title"),'断桥残雪');
  assert.ok(h.run("findNode('pagoda').locationNote.includes('原景点出处')"));
  assert.deepEqual(h.value("findNode('pagoda').sourceIds"),[]);
  assert.equal(h.run('s.trip.version === beforeVersion+1'),true);
  h.run("click('undo');");
  assert.equal(h.run("findNode('pagoda').title === original.title"),true);
  assert.equal(h.run("findNode('pagoda').lng === original.lng"),true);
  assert.equal(h.run("findNode('pagoda').notes"),'自己的集合地点和联系电话');
  assert.deepEqual(h.value("findNode('pagoda').sourceIds"),['S1','S2']);
});

test('preparation toggles form their own undo step and cannot silently consume an earlier edit',() => {
  const h = harness();
  h.run(`
    const initialNotes=findNode('pagoda').notes;
    startEditor('pagoda'); s.editor.notes='先提交这段备注'; click('commit-editor');
    const beforePrepVersion=s.trip.version;
    click('prep',{key:'umbrella'});
  `);
  assert.equal(h.run('s.trip.preparation.umbrella'),true);
  assert.equal(h.run('s.trip.version === beforePrepVersion+1'),true);
  assert.equal(h.run('s.history.length'),2);
  assert.equal(h.run('Model.restore().state.trip.preparation.umbrella'),true);
  h.run("click('undo');");
  assert.equal(h.run('s.trip.preparation.umbrella'),false);
  assert.equal(h.run("findNode('pagoda').notes"),'先提交这段备注');
  h.run("click('undo');");
  assert.equal(h.run("findNode('pagoda').notes === initialNotes"),true);
});

test('archive switching restores per-trip history, notices and conditions instead of borrowing another trip',() => {
  const h = harness();
  h.run(`
    const tripAId=s.trip.id;
    startEditor('pagoda'); s.editor.notes='A行程的已提交修改'; click('commit-editor');
    const aHistorySize=s.history.length, aNotice=s.lastMutation;
    const formB={...Model.clone(s.form),date:'2027-02-02',days:4,arrival:'10:00',returnTime:'15:00',hotel:'B酒店',pace:'紧凑',mode:'打车为主'};
    const tripB=Model.createTrip(formB).trip; tripB.creationForm=Model.clone(formB);
    s.form=Model.clone(formB); setTrip(tripB);
    const tripBId=s.trip.id;
    click('open-archive',{id:tripAId});
  `);
  assert.equal(h.run('s.trip.id === tripAId'),true);
  assert.equal(h.run('s.history.length === aHistorySize'),true);
  assert.equal(h.run('s.lastMutation === aNotice'),true);
  assert.equal(h.run('s.form.days'),3); assert.equal(h.run('s.form.date'),'2026-11-07');
  assert.equal(h.run('s.form.hotel'),'西湖附近酒店（示例）'); assert.equal(h.run('s.form.pace'),'舒缓');
  assert.equal(h.run('s.otherTrips[0].trip.id === tripBId'),true);
  assert.equal(h.run('Model.restore().state.otherTrips[0].trip.id === tripBId'),true);
  h.run("click('new-from-trip');");
  assert.equal(h.run('s.screen'),'create'); assert.equal(h.run('s.form.date'),'2026-11-07');
  assert.equal(h.run('s.form.arrival'),'14:00'); assert.equal(h.run('s.form.mode'),'公共交通');
  h.run("click('undo');");
  assert.equal(h.run("findNode('pagoda').notes.includes('A行程的已提交修改')"),false);
});

test('opening a stop updates the selected day and AI remaining scope starts exactly at that stop',() => {
  const h = harness();
  h.run(`
    s.activeDay=0; s.selectedId='arrival';
    click('node',{id:'jiuxi'});
  `);
  assert.equal(h.run('s.activeDay'),1); assert.equal(h.run('s.selectedId'),'jiuxi');
  assert.equal(h.run('Model.restore().state.selectedId'),'jiuxi');
  h.run(`
    click('close-sheet'); click('ai',{index:'1'});
    currentLayer().scope='remaining'; currentLayer().prompt='晚出发一小时'; click('preview-ai');
  `);
  assert.equal(h.run('currentLayer().type'),'ai-preview');
  assert.deepEqual(h.value('s.proposal.changes.map(change=>change.id)'),['jiuxi']);
  assert.equal(h.run("findNode('jiuxi').time"),'14:00');
  h.run("click('apply-ai');");
  assert.equal(h.run("findNode('jiuxi').time"),'15:00');
  assert.equal(h.run("findNode('temple').time"),'09:00');
  assert.equal(h.run("findNode('yongfu').time"),'11:45');
  h.run("click('undo');"); assert.equal(h.run("findNode('jiuxi').time"),'14:00');
});

test('using partial research retains fixed constraints and S1-supported places without leaking S2-only content',() => {
  const h = harness();
  h.run(`s.job={status:'failed',step:2,scenario:'partial',form:Model.clone(s.form)}; click('use-partial');`);
  assert.deepEqual(h.value('s.trip.sources.map(source=>source.id)'),['S1']);
  assert.deepEqual(h.value("allNodes().filter(node=>node.kind==='visit').map(node=>node.id)"),['pagoda']);
  assert.deepEqual(h.value("findNode('pagoda').sourceIds"),['S1']);
  assert.equal(h.run('s.trip.days[1].stops.length'),0);
  assert.equal(h.run("allNodes().filter(node=>node.sourceIds.includes('S2')).length"),0);
  assert.deepEqual(h.value("allNodes().filter(node=>node.locked).map(node=>node.id)"),['arrival','hotel','return']);
  assert.equal(h.run("findNode('meal').locationStatus"),'pending');
  assert.equal(h.run("findNode('hotel').lng"),null);
  assert.equal(h.run('Model.restore().error'),'');
  assert.equal(h.run('Model.restore().state.trip.id === s.trip.id'),true);
});
