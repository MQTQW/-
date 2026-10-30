import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const read = file => fs.readFileSync(new URL(file,import.meta.url),'utf8');
const modelSource = read('./model.js');
const discoverySource = read('./discovery-model.js');
const appSource = read('./app.js');
const createViewsSource = read('./create-views.js');
const bindingMarker = "document.getElementById('app').addEventListener('click'";
assert.ok(appSource.includes(bindingMarker),'The headless harness must stop before DOM event binding.');

// Run the actual V3 controller, discovery scheduler and persistence. Only render,
// DOM surfaces and wall-clock timers are stubbed; no browser/layout claim is made.
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
  vm.runInContext(`${modelSource}\n${discoverySource}\n${appSource.slice(0,appSource.indexOf(bindingMarker))}\n${createViewsSource}\nrender = () => {};`,context);
  const run = code => vm.runInContext(code,context);
  run(`
    s = Model.fresh(); s.otherTrips = []; s.discovery = Discovery.initial(); layers = [];
    function click(action,dataset={}) { return act(action,{dataset}); }
    function seedTrip(ids=['pagoda','temple','meal'],form={},priorities={}) {
      s.discovery=Discovery.initial(); s.discovery.researched=true;
      for(const id of ids)s.discovery.selected[id]={priority:priorities[id]||'want'};
      s.form={...Model.fresh().form,...form};
      const result=Discovery.plan(s.form,s.discovery);
      if(result.error)throw new Error(result.error);
      result.trip.creationForm=Model.clone(s.form);
      result.trip.creationDiscovery=Model.clone(s.discovery);
      s.trip=result.trip;s.screen='detail';s.editor=null;s.history=[];s.job=null;layers=[];
      return result.trip;
    }
    const calls={plan:0,createTrip:0};
    const realPlan=Discovery.plan,realCreateTrip=Model.createTrip;
    Discovery.plan=(...args)=>{calls.plan++;return realPlan(...args);};
    Model.createTrip=(...args)=>{calls.createTrip++;return realCreateTrip(...args);};
  `);
  const api = {
    run,
    value:code => JSON.parse(JSON.stringify(run(code))),
    pending:() => timers.size,
    tick() {
      const next = timers.entries().next().value;
      assert.ok(next,'Expected a pending controller stage');
      timers.delete(next[0]); next[1]();
    },
    flush() {
      let count = 0;
      while(timers.size) {
        assert.ok(count++ < 24,'Controller timers did not settle after 24 stages');
        api.tick();
      }
    },
    discover() {
      run("click('new-trip'); click('search-city');");
      api.flush();
      assert.equal(run('s.screen'),'discover');
    }
  };
  return api;
}

test('new trips start with city research, which reaches discovery without generating or replacing an itinerary',() => {
  const h = harness();
  h.run(`seedTrip();const existing=JSON.stringify(s.trip);calls.plan=0;calls.createTrip=0;click('new-trip');`);
  assert.equal(h.run('s.screen'),'search');
  assert.equal(h.run('s.job'),null);
  h.run("click('search-city');");
  h.flush();
  assert.equal(h.run('s.screen'),'discover');
  assert.equal(h.run('s.discovery.researched'),true);
  assert.equal(h.run('s.research.status'),'done');
  assert.equal(h.run('calls.plan'),0);
  assert.equal(h.run('calls.createTrip'),0);
  assert.equal(h.run('JSON.stringify(s.trip) === existing'),true);
  assert.deepEqual(h.value('Discovery.selected(s.discovery)'),[]);
  assert.equal(h.run('s.job'),null);
  assert.equal(h.run('Model.restore().state.discovery.researched'),true);
});

test('wish toggles only change the basket and zero selections cannot enter planning conditions',() => {
  const h = harness();
  h.run('seedTrip();const existing=JSON.stringify(s.trip);');
  h.discover();
  h.run("click('toggle-wish',{id:'pagoda'});");
  assert.deepEqual(h.value('Discovery.selected(s.discovery).map(card=>card.id)'),['pagoda']);
  assert.equal(h.run('JSON.stringify(s.trip) === existing'),true);
  h.run("click('toggle-wish',{id:'pagoda'});click('to-conditions');");
  assert.notEqual(h.run('s.screen'),'create');
  assert.match(h.run('appError'),/选择|至少|想去/);
  assert.equal(h.run('s.job'),null);
  assert.equal(h.run('JSON.stringify(s.trip) === existing'),true);
  assert.deepEqual(h.value('Model.restore().state.discovery.selected'),{});
});

test('planning captures selected wishes and priority once; mid-job basket edits cannot add, remove or downgrade its result',() => {
  const h = harness();
  h.discover();
  h.run(`
    click('toggle-wish',{id:'pagoda'});click('priority-wish',{id:'pagoda'});
    click('toggle-wish',{id:'meal'});click('to-conditions');
    s.form=Model.clone(Model.fresh().form);
    click('generate');
    const captured=JSON.stringify(s.job.discovery);
  `);
  assert.equal(h.run('s.screen'),'progress');
  assert.equal(h.run('s.job.status'),'running');
  assert.equal(h.run("s.job.discovery.selected.pagoda.priority"),'must');
  h.run(`
    click('toggle-wish',{id:'pagoda'});click('toggle-wish',{id:'temple'});
    s.form.days=5;s.form.hotel='运行后才改的酒店';
  `);
  assert.notEqual(h.run('JSON.stringify(s.discovery)'),h.run('captured'));
  assert.equal(h.run('JSON.stringify(s.job.discovery) === captured'),true);
  h.flush();
  assert.equal(h.run('s.screen'),'detail');
  assert.equal(h.run('s.job'),null);
  assert.deepEqual(h.value('allNodes().filter(node=>node.selectionId).map(node=>node.id).sort()'),['meal','pagoda']);
  assert.deepEqual(h.value('s.trip.wishes.map(card=>card.id).sort()'),['meal','pagoda']);
  assert.equal(h.run('JSON.stringify(s.trip.creationDiscovery) === captured'),true);
  assert.equal(h.run("findNode('pagoda').mustKeep"),true);
  assert.equal(h.run("findNode('pagoda').priority"),'must');
  assert.equal(h.run('s.trip.days.length'),3);
  assert.equal(h.run('s.trip.constraints.hotel'),'西湖附近酒店（示例）');
  assert.equal(h.run("findNode('meal').locationStatus"),'pending');
  assert.equal(h.run("findNode('meal').lng"),null);
  assert.equal(h.run('Model.restore().error'),'');
});

test('background planning saves a ready draft without replacing the active trip, unsaved editor or source layer',() => {
  const h = harness();
  h.run('seedTrip();const activeTripId=s.trip.id;');
  h.discover();
  h.run(`
    click('toggle-wish',{id:'bridge'});click('to-conditions');s.form.days=4;click('generate');
    click('open-trip');startEditor('pagoda');s.editor.notes='仍在编辑的个人备注';
    click('open-source',{id:'S2'});persist();
    const editorBefore=JSON.stringify(s.editor),layersBefore=JSON.stringify(layers);
  `);
  h.flush();
  assert.equal(h.run('s.trip.id === activeTripId'),true);
  assert.equal(h.run('JSON.stringify(s.editor) === editorBefore'),true);
  assert.equal(h.run('JSON.stringify(layers) === layersBefore'),true);
  assert.equal(h.run('s.job.status'),'done');
  assert.equal(h.run('s.otherTrips.length'),1);
  assert.equal(h.run('s.otherTrips[0].trip.id === s.job.resultId'),true);
  assert.equal(h.run('s.otherTrips[0].trip.days.length'),4);
  assert.deepEqual(h.value('s.otherTrips[0].trip.wishes.map(card=>card.id)'),['bridge']);
  assert.equal(h.run('Model.restore().state.editor.notes'),'仍在编辑的个人备注');
});

test('opening archived trips restores their conditions and wish priorities for both review and new planning',() => {
  const h = harness();
  h.run(`
    seedTrip(['pagoda','meal'],{},{pagoda:'must'});
    const tripAId=s.trip.id;
    startEditor('pagoda');s.editor.notes='A行程已提交的备注';click('commit-editor');
    const aHistory=s.history.length,aNotice=s.lastMutation;
    const formB={...Model.fresh().form,date:'2027-02-02',days:4,arrival:'10:00',returnTime:'15:00',hotel:'B酒店',pace:'紧凑',mode:'打车为主'};
    const discoveryB=Discovery.initial();discoveryB.researched=true;
    discoveryB.custom=[{id:'own-stop',title:'自己找到的展览',category:'experiences',duration:75}];
    discoveryB.selected={bridge:{priority:'must'},'own-stop':{priority:'want'}};
    const tripB=Discovery.plan(formB,discoveryB).trip;tripB.creationForm=Model.clone(formB);
    tripB.creationDiscovery=Model.clone(discoveryB);
    setTrip(tripB);const tripBId=s.trip.id;
    s.discovery=Model.clone(discoveryB);
    click('open-archive',{id:tripAId});click('review-wishes');
  `);
  assert.equal(h.run('s.screen'),'basket');
  assert.equal(h.run('s.trip.id === tripAId'),true);
  assert.deepEqual(h.value('Discovery.selected(s.discovery).map(card=>[card.id,card.priority]).sort()'),[['meal','want'],['pagoda','must']]);
  assert.equal(h.run('s.form.date'),'2026-11-07');
  assert.equal(h.run('s.form.hotel'),'西湖附近酒店（示例）');
  assert.equal(h.run('s.history.length === aHistory'),true);
  assert.equal(h.run('s.lastMutation === aNotice'),true);
  assert.equal(h.run('s.otherTrips.some(item=>item.trip.id===tripBId)'),true);
  h.run("click('open-archive',{id:tripBId});click('new-from-trip');");
  assert.equal(h.run('s.screen'),'basket');
  assert.equal(h.run('s.form.date'),'2027-02-02');
  assert.equal(h.run('s.form.days'),4);
  assert.equal(h.run('s.form.arrival'),'10:00');
  assert.equal(h.run('s.form.hotel'),'B酒店');
  assert.deepEqual(h.value('Discovery.selected(s.discovery).map(card=>[card.id,card.priority])'),[['bridge','must'],['own-stop','want']]);
  assert.equal(h.run("Discovery.selected(s.discovery).find(card=>card.id==='own-stop').duration"),75);
  assert.equal(h.run("Discovery.selected(s.discovery).find(card=>card.id==='own-stop').category"),'experiences');
  assert.deepEqual(h.value('Model.restore().state.discovery.selected'),{bridge:{priority:'must'},'own-stop':{priority:'want'}});
});

test('an overfull plan reports unplaced wishes and retains every choice without creating a false successful draft',() => {
  const h = harness();
  h.run('seedTrip();const existing=JSON.stringify(s.trip);');
  h.discover();
  h.run(`
    s.discovery.custom=[{id:'too-long',title:'全天保留的自选体验',category:'experiences',duration:720}];
    s.discovery.selected={'too-long':{priority:'must'},pagoda:{priority:'want'}};
    click('to-conditions');s.form.days=1;s.form.arrival='14:00';s.form.returnTime='19:00';
    const choices=JSON.stringify(s.discovery.selected);click('generate');
  `);
  h.flush();
  assert.equal(h.run('JSON.stringify(s.trip) === existing'),true);
  assert.equal(h.run('JSON.stringify(s.discovery.selected) === choices'),true);
  assert.equal(h.run('s.otherTrips.length'),0);
  assert.equal(h.run("s.job?.status==='done'"),false);
  assert.match(h.run("appError || s.job?.error || ''"),/排不下|全天保留的自选体验/);
  assert.deepEqual(h.value('Model.restore().state.discovery.selected'),{'too-long':{priority:'must'},pagoda:{priority:'want'}});
});

test('partial research is explicitly disclosed and cannot silently produce an itinerary',() => {
  const h = harness();
  h.run("jobScenario='partial';click('new-trip');click('search-city');");
  h.flush();
  assert.equal(h.run('s.trip'),null);
  assert.equal(h.run('s.job'),null);
  assert.equal(h.run('calls.plan'),0);
  assert.equal(h.run('calls.createTrip'),0);
  assert.equal(h.run('s.research.status'),'failed');
  assert.equal(h.run('s.discovery.researched'),false);
  assert.equal(h.run('s.screen'),'search');
  assert.match(h.run("s.research.error || appError || ''"),/失败|未完成|中断|部分/);
  assert.equal(h.run('Model.restore().error'),'');
  h.run("click('search-city');");
  h.flush();
  assert.equal(h.run('s.research.status'),'done');
  assert.equal(h.run('s.screen'),'discover');
  assert.equal(h.run('s.trip'),null);
  assert.equal(h.run('calls.plan'),0);
});

test('source navigation cannot overwrite an unsaved editor, and returning restores its original draft',() => {
  const h = harness();
  h.run(`
    seedTrip();startEditor('pagoda');s.editor.notes='查过出处后继续写';
    const draftBefore=JSON.stringify(s.editor);
    click('open-source',{id:'S2'});click('source-node',{id:'temple'});click('edit',{id:'temple'});
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

test('explicitly discarding an original draft permits a second editor and survives returning home and reopening',() => {
  const h = harness();
  h.run(`
    seedTrip();startEditor('pagoda');s.editor.notes='用户明确选择丢弃';
    startEditor('temple');click('switch-edit');s.editor.notes='第二站尚未提交的备注';persist();
    click('home');click('reopen');click('resume-editor');
  `);
  assert.equal(h.run('s.editor.id'),'temple');
  assert.equal(h.run('s.editor.notes'),'第二站尚未提交的备注');
  assert.deepEqual(h.value('layers.map(layer=>layer.type)'),['edit']);
  assert.equal(h.run("findNode('pagoda').notes.includes('用户明确选择丢弃')"),false);
  assert.equal(h.run("findNode('temple').notes.includes('第二站尚未提交')"),false);
  assert.equal(h.run('Model.restore().state.editor.id'),'temple');
});

test('replacing a place keeps committed notes and separate location evidence, with an independent preparation undo step',() => {
  const h = harness();
  h.run(`
    seedTrip();startEditor('pagoda');s.editor.notes='自己的集合地点和联系电话';click('commit-editor');
    const original=Model.clone(findNode('pagoda')),beforeVersion=s.trip.version;
    click('confirm',{id:'pagoda'});sheetContent(currentLayer());
    click('choose-location',{id:'broken'});click('commit-location');
  `);
  assert.equal(h.run("findNode('pagoda').title"),'断桥残雪');
  assert.equal(h.run("findNode('pagoda').notes"),'自己的集合地点和联系电话');
  assert.match(h.run("findNode('pagoda').locationNote"),/原景点出处/);
  assert.deepEqual(h.value("findNode('pagoda').sourceIds"),[]);
  assert.equal(h.run('s.trip.version === beforeVersion+1'),true);
  h.run("click('prep',{key:'umbrella'});click('undo');");
  assert.equal(h.run('s.trip.preparation.umbrella'),false);
  assert.equal(h.run("findNode('pagoda').title"),'断桥残雪');
  h.run("click('undo');");
  assert.equal(h.run("findNode('pagoda').title === original.title"),true);
  assert.equal(h.run("findNode('pagoda').lng === original.lng"),true);
  assert.equal(h.run("findNode('pagoda').notes"),'自己的集合地点和联系电话');
  assert.deepEqual(h.value("findNode('pagoda').sourceIds"),['S1','S2']);
});

test('experience places reject blank input and retain the selected activity and personal notes instead of offering unrelated attractions',() => {
  const h = harness();
  h.run(`
    seedTrip(['teabreak'],{},{teabreak:'must'});
    startEditor('teabreak');s.editor.notes='带一本书，安静休息一会儿';click('commit-editor');
    const original=Model.clone(findNode('teabreak')),tripBefore=JSON.stringify(s.trip),historyBefore=s.history.length;
    click('confirm',{id:'teabreak'});
  `);
  assert.equal(h.run('currentLayer().type'),'experience-location');
  assert.doesNotMatch(h.run('sheetContent(currentLayer()).body'),/断桥残雪|拱宸桥|choose-location/);
  h.run("currentLayer().place='   ';click('commit-experience-place');");
  assert.match(h.run('sheetError'),/填写地点/);
  assert.equal(h.run('currentLayer().type'),'experience-location');
  assert.equal(h.run('JSON.stringify(s.trip) === tripBefore'),true);
  assert.equal(h.run('s.history.length === historyBefore'),true);
  h.run("currentLayer().place='  已预订的湖边茶室  ';click('commit-experience-place');");
  assert.deepEqual(h.value('layers'),[]);
  assert.equal(h.run("findNode('teabreak').title === original.title"),true);
  assert.equal(h.run("findNode('teabreak').selectionId"),'teabreak');
  assert.equal(h.run("findNode('teabreak').mustKeep"),true);
  assert.deepEqual(h.value("findNode('teabreak').sourceIds"),[]);
  assert.equal(h.run("findNode('teabreak').experiencePlace"),'已预订的湖边茶室');
  assert.equal(h.run("findNode('teabreak').locationStatus"),'manual');
  assert.equal(h.run("findNode('teabreak').lng"),null);
  assert.equal(h.run("findNode('teabreak').lat"),null);
  assert.match(h.run("findNode('teabreak').notes"),/带一本书，安静休息一会儿/);
  assert.match(h.run("findNode('teabreak').notes"),/手动地点：已预订的湖边茶室/);
  assert.match(h.run("findNode('teabreak').locationNote"),/手动指定.*未核对/);
  assert.deepEqual(h.value('allNodes().filter(node=>node.selectionId).map(node=>node.id)'),['teabreak']);
  assert.equal(h.run('Model.restore().error'),'');
  assert.deepEqual(h.value("Model.restore().state.trip.days.flatMap(day=>day.stops).find(node=>node.id==='teabreak')"),h.value("s.trip.days.flatMap(day=>day.stops).find(node=>node.id==='teabreak')"));
});

test('confirming a meal clears its home pending count while unresolved experiences remain counted and canonical status wins over legacy flags',() => {
  for(const withExperience of [false,true]) {
    const h = harness();
    h.run(`seedTrip(${withExperience ? "['meal','teabreak']" : "['meal']"});`);
    assert.equal(h.run('cvPendingCount(s.trip)'),withExperience ? 2 : 1);
    assert.equal(h.run("findNode('meal').pending"),true);
    h.run(`
      click('confirm',{id:'meal'});sheetContent(currentLayer());
      click('choose-location',{id:'hubin'});click('commit-location');click('home');
    `);
    assert.equal(h.run('s.screen'),'home');
    assert.equal(h.run("findNode('meal').locationStatus"),'selected');
    assert.equal(h.run("findNode('meal').pending"),false);
    assert.equal(h.run('cvPendingCount(s.trip)'),withExperience ? 1 : 0);
    assert.equal(h.run('Model.restore().error'),'');
    assert.equal(h.run('cvPendingCount(Model.restore().state.trip)'),withExperience ? 1 : 0);
    if(withExperience)assert.equal(h.run("findNode('teabreak').locationStatus"),'pending');
    h.run(`
      const legacy=Model.clone(s.trip);
      legacy.days.flatMap(day=>day.stops).find(node=>node.id==='meal').pending=true;
    `);
    assert.equal(h.run('cvPendingCount(legacy)'),withExperience ? 1 : 0);
  }
});
