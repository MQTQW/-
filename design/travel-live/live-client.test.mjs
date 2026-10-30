import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const read = file => fs.readFileSync(new URL(file, import.meta.url), 'utf8');
const marker = "document.getElementById('app').addEventListener('click'";
const sourceId = '0123456789abcdef01234567';
const image = {id:'image-one',url:'/api/images/image-one',sourceId,index:0,caption:'攻略配图，非地点实拍确认'};
const source = {id:sourceId,title:'苏州慢游：古城与茶点',author:'测试作者',date:'2026-10-30',url:'https://www.xiaohongshu.com/explore/'+sourceId,bodyExcerpt:'测试数据：古城散步与茶点。',imageCount:1,images:[image]};
const candidate = {id:'candidate-one',title:'古城散步',category:'sights',region:'古城',description:'测试数据中的步行体验。',duration:60,sourceIds:[sourceId],kind:'visit',lng:null,lat:null,locationStatus:'pending',image,photo:null,notes:'具体起终点待确认。'};
const research = {id:'research-result',city:'苏州',fetchedAt:'2026-10-30T06:00:00.000Z',sources:[source],candidates:[candidate],warnings:[]};
const session = {csrfToken:'test-csrf-only-in-memory',capabilities:{research:true,ai:true,map:false},connection:{loggedIn:true,message:''}};
const response = (data,status=200) => ({ok:status>=200&&status<300,status,json:async()=>structuredClone(data)});

function context(fetch) {
 const storage=new Map();
 const scope=vm.createContext({fetch,AbortController,URL,setTimeout,clearTimeout,console,document:{activeElement:{dataset:{},id:''},querySelector:()=>null,querySelectorAll:()=>[],getElementById:()=>({focus(){}})},localStorage:{getItem:key=>storage.get(key)??null,setItem:(key,value)=>storage.set(key,String(value))}});
 const app=read('./app.js');
 vm.runInContext('const IMAGE_DATA={};const PHOTO_CREDITS=[];\n'+['ui.js','live-client.js','model.js','discovery-model.js','create-views.js','result-views.js','discovery-views.js'].map(readFile=>read('./'+readFile)).join('\n')+'\n'+app.slice(0,app.indexOf(marker))+'\nrender=()=>{};const actualPoll=LiveAPI.poll;LiveAPI.poll=(id,options)=>actualPoll(id,{...options,interval:0});',scope);
 return {run:code=>vm.runInContext(code,scope),scope,storage};
}

test('same-origin requests use session CSRF and cookies, never credentials in payloads',async()=>{
 const calls=[];
 const h=context(async(path,options)=>{calls.push({path,options});return response(path==='/api/session'?session:{jobId:'research-job'});});
 await h.run("LiveAPI.research('苏州')");
 assert.deepEqual(calls.map(call=>call.path),['/api/session','/api/research']);
 assert.equal(calls[1].options.credentials,'same-origin');
 assert.equal(calls[1].options.headers['X-CSRF-Token'],session.csrfToken);
 assert.deepEqual(JSON.parse(calls[1].options.body),{destination:'苏州',count:3});
 assert.ok(!calls[1].options.body.includes(session.csrfToken));
});

test('missing CSRF stops mutations and structured API errors retain code/message',async()=>{
 let posts=0;
 const h=context(async(path)=>{if(path==='/api/session')return response({...session,csrfToken:''});posts++;return response({});});
 await assert.rejects(h.run("LiveAPI.research('苏州')"),error=>error.code==='SESSION_NOT_READY');
 assert.equal(posts,0);
 const e=context(async()=>response({error:{code:'LOGIN_REQUIRED',message:'请先登录攻略服务'}},401));
 await assert.rejects(e.run('LiveAPI.session()'),error=>error.code==='LOGIN_REQUIRED'&&error.message==='请先登录攻略服务');
});

test('polling reports server text and returns the done result; failures stay failures',async()=>{
 let ticks=0;
 const h=context(async()=>response(++ticks===1?{status:'running',progress:'读取第2篇攻略'}:{status:'done',progress:'已整理',result:research}));
 h.run('globalThis.updates=[]');
 const result=await h.run("LiveAPI.poll('job-one',{onUpdate:job=>updates.push(job.progress)})");
 assert.equal(result.id,research.id);
 assert.deepEqual(JSON.parse(h.run('JSON.stringify(updates)')),['读取第2篇攻略','已整理']);
 const failed=context(async()=>response({status:'failed',error:{code:'AI_UNAVAILABLE',message:'AI 暂时不可用'}}));
 await assert.rejects(failed.run("LiveAPI.poll('job-two')"),error=>error.code==='AI_UNAVAILABLE'&&error.message==='AI 暂时不可用');
});

test('only opaque same-origin image paths and original HTTPS XHS links are rendered',()=>{
 const h=context(()=>{throw Error('Unexpected network');});
 for(const url of ['https://example.com/a.jpg','data:image/png;base64,x','/api/images/../../secret','/api/images/x?token=secret','//example.com/image']) assert.equal(h.run(`LiveAPI.imageURL(${JSON.stringify(url)})`),'');
 assert.equal(h.run('LiveAPI.imageURL('+JSON.stringify(image)+')'),image.url);
 for(const url of ['javascript:alert(1)','https://xiaohongshu.com.evil.test/post','http://www.xiaohongshu.com/post','https://user:secret@www.xiaohongshu.com/post']) assert.equal(h.run(`LiveAPI.sourceURL(${JSON.stringify(url)})`),'');
 const html=h.run('liveImageMarkup('+JSON.stringify(image)+')');
 assert.match(html,/攻略配图，非地点实拍确认/);
 assert.doesNotMatch(html,/onerror=|https:\/\/upload/);
});

test('live candidate pools start empty; manual wishes are retained and explicitly block initial AI planning',()=>{
 const h=context(()=>{throw Error('Unexpected network');});
 assert.equal(h.run('Discovery.catalog(Discovery.initial()).length'),0);
 h.scope.fixture=research;
 h.run("s.discovery={...Discovery.initial(),city:'苏州',liveResearch:fixture,selected:{'candidate-one':{priority:'must'}}}");
 assert.equal(h.run('Discovery.selected(s.discovery)[0].priority'),'must');
 assert.equal(h.run('Discovery.validatePlan(s.discovery)'),'');
 h.run("s.discovery.custom=[{id:'custom-one',title:'自己想去的面馆',category:'food'}];s.discovery.selected['custom-one']={priority:'want'}");
 assert.match(h.run('Discovery.validatePlan(s.discovery)'),/手动补充.*暂不支持/);
 assert.equal(h.run('Discovery.selected(s.discovery).length'),2);
});

test('research completion displays only returned candidates and metadata, without planning',async()=>{
 const calls=[];
 const h=context(async(path,options)=>{calls.push(path);if(path==='/api/session')return response(session);if(path==='/api/research')return response({jobId:'research-job'});if(path==='/api/jobs/research-job')return response({status:'done',progress:'完成',result:research});throw Error('Unexpected path '+path);});
 h.run("s.discovery.city='苏州'");
 await h.run('startResearch()');
 assert.equal(h.run('s.screen'),'discover');
 assert.equal(h.run('Discovery.catalog(s.discovery).length'),1);
 assert.deepEqual(JSON.parse(h.run('JSON.stringify(s.discovery.selected)')),{});
 assert.equal(h.run('s.trip'),null);
 assert.ok(!calls.includes('/api/plan'));
 const html=h.run('discoveryBody(s)');
 assert.match(html,/古城散步/);
 assert.match(html,/苏州慢游/);
 assert.match(html,/1篇攻略/);
 assert.doesNotMatch(html,/固定攻略样例|S1 ·|雷峰塔/);
 assert.ok([...h.storage.values()].every(value=>!value.includes(session.csrfToken)));
});

test('AI planning submits selected IDs/priority only and preserves editable trip provenance',async()=>{
 let submitted;
 const trip={id:'live-trip',title:'苏州1日行程',version:1,date:null,undated:true,days:[{day:1,date:null,name:'古城',stops:[{...candidate,time:'09:00',selectionId:candidate.id,locked:false,manuallyEdited:false,liveImage:image}]}],sources:[source],wishes:[{id:candidate.id,title:candidate.title,category:'sights',priority:'must',sourceIds:[sourceId]}],constraints:{arrival:'',returnTime:'',hotel:''},budget:{stay:0,food:0,transport:0,tickets:0,misc:0},preparation:{umbrella:false,shoes:false,booking:false}};
 const h=context(async(path,options)=>{if(path==='/api/session')return response(session);if(path==='/api/plan'){submitted=JSON.parse(options.body);return response({jobId:'plan-job'});}if(path==='/api/jobs/plan-job')return response({status:'done',result:{status:'ready',trip,wishes:trip.wishes,warnings:['部分交通尚待核对。'],stats:{legsChecked:1,legRequests:2}}});throw Error('Unexpected path '+path);});
 h.scope.fixture=research;
 h.run("s.discovery={...Discovery.initial(),city:'苏州',liveResearch:fixture,selected:{'candidate-one':{priority:'must'}}};s.form={...Model.fresh().form,destination:'苏州',days:1,date:'',undated:true,hotel:'',arrival:'',returnTime:'',mode:'打车为主'}");
 await h.run('startJob()');
 assert.deepEqual(submitted.selected,[{id:'candidate-one',priority:'must'}]);
 assert.equal(submitted.researchId,research.id);
 assert.equal(submitted.form.mode,'自驾');
 assert.equal(h.run('s.screen'),'detail');
 assert.equal(h.run('s.trip.creationDiscovery.liveResearch.id'),research.id);
 assert.equal(h.run('s.trip.days[0].stops[0].liveImage.url'),image.url);
 assert.equal(h.run('s.trip.backendWarnings[0]'),'部分交通尚待核对。');
 assert.equal(h.run('s.trip.backendStats.legsChecked'),1);
 assert.equal(h.run('s.trip.planJobId'),'plan-job');
 assert.equal(h.run('s.trip.backendWarningsStale'),false);
 assert.equal(h.run('Model.restore().state.trip.id'),'live-trip');
 assert.match(h.run('resultBody(s)'),/攻略配图，非地点实拍确认/);
 assert.match(h.run('sourceBody('+JSON.stringify(sourceId)+')'),/noopener noreferrer/);
});

test('needs_changes and unsupported manual content preserve selections without replacing a saved trip',async()=>{
 let calls=0;
 const h=context(async(path)=>{if(path==='/api/session')return response(session);if(path==='/api/plan'){calls++;return response({jobId:'plan-job'});}return response({status:'done',result:{status:'needs_changes',unplaced:[{id:candidate.id,title:candidate.title}],message:'当前时间容纳不下，请增加天数'}});});
 h.scope.fixture=research;
 h.run("s.discovery={...Discovery.initial(),city:'苏州',liveResearch:fixture,selected:{'candidate-one':{priority:'want'}}};s.trip={id:'old-trip'}");
 await h.run('startJob()');
 assert.equal(h.run('s.trip.id'),'old-trip');
 assert.equal(h.run('s.screen'),'create');
 assert.match(h.run('appError'),/增加天数/);
 assert.equal(h.run('Discovery.selected(s.discovery).length'),1);
 h.run("s.discovery.custom=[{id:'custom-one',title:'面馆',category:'food'}];s.discovery.selected['custom-one']={priority:'want'}");
 await h.run('startJob()');
 assert.equal(calls,1);
 assert.equal(h.run('Discovery.selected(s.discovery).length'),2);
});

test('login-needed sessions block research and expose reconnect without making search calls',async()=>{
 const paths=[];
 const h=context(async(path)=>{paths.push(path);return response({...session,connection:{loggedIn:false,message:'请在服务端登录小红书'}});});
 h.run("s.discovery.city='苏州'");
 await h.run('startResearch()');
 assert.deepEqual(paths,['/api/session']);
 assert.match(h.run('appError'),/服务端登录/);
 assert.match(h.run('liveConnectionMarkup()'),/reconnect-live/);
});

test('network failures retain resumable job IDs, while image failures show text fallback',async()=>{
 const h=context(async()=>{throw Error('Offline');});
 await assert.rejects(h.run("LiveAPI.job('known-job')"),error=>error.code==='NETWORK_ERROR');
 const events={};
 const fallback={hidden:true};
 const img={hidden:false,complete:false,naturalWidth:0,addEventListener:(name,callback)=>events[name]=callback,closest:()=>({querySelector:()=>fallback})};
 h.scope.testRoot={querySelectorAll:()=>[img]};
 h.run('liveBindImages(testRoot)');
 events.error();
 assert.equal(img.hidden,true);
 assert.equal(fallback.hidden,false);
});

test('manual place replacement clears old verification, evidence, coordinates and image while preserving notes; undo restores them',()=>{
 const h=context(()=>{throw Error('Unexpected network');});
 h.scope.card={...candidate,time:'09:00',lng:120.6,lat:31.3,locationStatus:'matched',locked:false,manuallyEdited:false,liveImage:image,notes:'用户已补充的备注',verification:{status:'verified',poiId:'old-poi'},sourceRefs:[{sourceId,evidenceText:'旧地点依据'}]};
 h.run("s.trip={id:'edit-trip',title:'苏州1日行程',version:1,days:[{day:1,stops:[card]}],sources:[],constraints:{},budget:{},preparation:{}};s.history=[];s.screen='detail';layers=[{type:'confirm',id:'candidate-one',place:'自己的实际地点'}];act('commit-manual-place',{dataset:{}})");
 assert.equal(h.run('s.trip.days[0].stops[0].lng'),null);
 assert.equal(h.run('s.trip.days[0].stops[0].liveImage'),null);
 assert.equal(h.run('s.trip.days[0].stops[0].sourceIds.length'),0);
 assert.equal(h.run('s.trip.days[0].stops[0].verification'),null);
 assert.equal(h.run('s.trip.days[0].stops[0].sourceRefs.length'),0);
 assert.equal(h.run('s.trip.days[0].stops[0].notes'),'用户已补充的备注');
 h.run('Model.undo(s)');
 assert.equal(h.run('s.trip.days[0].stops[0].lng'),120.6);
 assert.equal(h.run('s.trip.days[0].stops[0].liveImage.url'),image.url);
 assert.equal(h.run('s.trip.days[0].stops[0].sourceIds[0]'),sourceId);
 assert.equal(h.run('s.trip.days[0].stops[0].verification.status'),'verified');
 assert.equal(h.run('s.trip.days[0].stops[0].verification.poiId'),'old-poi');
 assert.equal(h.run('s.trip.days[0].stops[0].sourceRefs[0].evidenceText'),'旧地点依据');
});

test('a server result that silently drops a wish cannot replace the existing trip',async()=>{
 const h=context(async(path)=>{if(path==='/api/session')return response(session);if(path==='/api/plan')return response({jobId:'plan-job'});return response({status:'done',result:{status:'ready',trip:{id:'bad-result',days:[{stops:[]}],wishes:[]}}});});
 h.scope.fixture=research;
 h.run("s.discovery={...Discovery.initial(),city:'苏州',liveResearch:fixture,selected:{'candidate-one':{priority:'must'}}};s.trip={id:'existing-trip'}");
 await h.run('startJob()');
 assert.equal(h.run('s.trip.id'),'existing-trip');
 assert.equal(h.run('s.job.status'),'failed');
 assert.match(h.run('s.job.error'),/所选清单.*不一致/);
 assert.equal(h.run('Discovery.selected(s.discovery).length'),1);
});

test('transport options show familiar labels and submit backend canonical enum values',()=>{
 const h=context(()=>{throw Error('Unexpected network');});
 for(const [oldValue,canonical,label] of [['公共交通','公共交通','公共交通'],['步行优先','步行','步行优先'],['打车为主','自驾','打车为主']]){
  h.scope.mode=oldValue;
  h.run('s.form.mode=mode');
  assert.match(h.run('createBody(s)'),new RegExp('<option value="'+canonical+'" selected>'+label+'</option>'));
  assert.equal(h.run('cvTransportValue(s.form.mode)'),canonical);
 }
 assert.doesNotMatch(h.run('createBody(s)'),/value="步行优先"|value="打车为主"/);
});

test('validation evidence exports only safe explicit fields and reports actual counters',()=>{
 const secret='DISCARDED_PRIVATE_SENTINEL';
 const fixture=structuredClone(research);
 fixture.stats={sourceCount:1,candidateCount:1,imagesAvailable:2,imagesRead:1,matchedPlaceCount:1,privateKey:secret,usage:{raw:secret}};
 fixture.status={retrieval:'live',ai:'success',map:'partial',privateKey:secret};
 fixture.sources[0]={...fixture.sources[0],url:source.url+'?csrfToken='+secret+'#'+secret,body:secret,bodyExcerpt:secret,cookie:secret};
 fixture.candidates[0]={...fixture.candidates[0],verification:{status:'verified',apiKey:secret,location:{private:secret}},notes:secret,sourceRefs:[{evidenceText:secret}],image:{...image,cdnUrl:'https://cdn.example.com/'+secret}};
 const h=context(()=>{throw Error('Unexpected network');});
 h.scope.fixture=fixture;
 h.run('s.discovery.liveResearch=fixture');
 const record=JSON.parse(h.run('JSON.stringify(liveEvidenceRecord())'));
 assert.deepEqual(Object.keys(record).sort(),['fetchedAt','counts','status','sources','candidates'].sort());
 assert.deepEqual(record.counts,{sourceCount:1,candidateCount:1,imagesAvailable:2,imagesRead:1,matchedPlaceCount:1});
 assert.deepEqual(record.status,{retrieval:'live',ai:'success',map:'partial'});
 assert.deepEqual(Object.keys(record.sources[0]).sort(),['title','author','date','url'].sort());
 assert.equal(record.sources[0].url,source.url);
 assert.deepEqual(Object.keys(record.candidates[0]).sort(),['id','title','category','sourceIds','verificationStatus','image'].sort());
 assert.deepEqual(record.candidates[0].image,{proxyUrl:image.url,caption:'攻略配图，非地点实拍确认'});
 assert.equal(record.candidates[0].verificationStatus,'verified');
 assert.ok(!JSON.stringify(record).includes(secret));
 assert.ok(!JSON.stringify(record).includes('cdn.example.com'));
 assert.match(h.run('liveEvidenceMarkup()'),/联调记录|导出联调记录/);
 assert.match(h.run('liveEvidenceMarkup()'),/服务校验与 AI 读图分别计数/);
 const shell=h.run('shell()'),phoneStart=shell.indexOf('<div class="device"'),notesStart=shell.indexOf('<aside class="design-notes"');
 assert.doesNotMatch(shell.slice(phoneStart,notesStart),/live-evidence/);
 assert.match(shell.slice(notesStart),/live-evidence/);
 h.run('s.discovery.liveResearch=null');
 assert.equal(h.run('liveEvidenceMarkup()'),'');
});

test('poster links use the same proxy URL, remain outside buttons, and preserve error fallback',()=>{
 const h=context(()=>{throw Error('Unexpected network');});
 h.scope.image=image;h.scope.card={...candidate,time:'09:00',locked:false,manuallyEdited:false,liveImage:image};h.scope.source=source;
 h.run("s.trip={id:'image-trip',title:'苏州1日行程',version:1,days:[{day:1,name:'古城',stops:[card]}],sources:[source],constraints:{},budget:{},preparation:{}};s.screen='detail'");
 const html=h.run('liveImageMarkup(image)');
 assert.match(html,/<a[^>]*href="\/api\/images\/image-one"[^>]*target="_blank"[^>]*rel="noopener noreferrer"[^>]*><img[^>]*src="\/api\/images\/image-one"/);
 for(const markup of [h.run('resultBody(s)'),h.run('homeBody(s)'),h.run('liveSourceMarkup(source)')]){
  let buttons=0,anchors=0;
  for(const tag of markup.matchAll(/<(\/?)(button|a)\b[^>]*>/g)){
   const closing=!!tag[1],name=tag[2];
   if(!closing&&name==='a')assert.equal(buttons,0,'Image anchor must not be nested in a button');
   if(!closing&&name==='button')assert.equal(anchors,0,'Detail button must not be nested in an image anchor');
   if(name==='button')buttons+=closing?-1:1;else anchors+=closing?-1:1;
  }
  assert.equal(buttons,0);assert.equal(anchors,0);
 }
 const events={},link={hidden:false,classList:{contains:name=>name==='live-image-link'}},fallback={hidden:true};
 const img={hidden:false,parentElement:link,complete:false,addEventListener:(name,callback)=>events[name]=callback,closest:()=>({querySelector:()=>fallback})};
 h.scope.testRoot={querySelectorAll:()=>[img]};h.run('liveBindImages(testRoot)');events.error();
 assert.equal(link.hidden,true);assert.equal(fallback.hidden,false);
});

function timingTrip(){
 const visit=(id,title,time,duration,extra={})=>({...candidate,id,title,time,duration,selectionId:id,locked:false,manuallyEdited:false,locationStatus:'matched',lng:120.1,lat:30.2,verification:{status:'verified',poiId:id},travelMinutes:null,travelStatus:'unavailable',...extra});
 return {id:'timing-trip',title:'杭州1日行程',version:1,date:'2026-11-07',undated:false,creationForm:{mode:'公共交通'},days:[{day:1,date:'2026-11-07',name:'西湖',routeDirty:true,stops:[visit('curve','曲院风荷','09:00',45),visit('flowers','花港观鱼','10:15',60,{travelMinutes:40,travelStatus:'estimated',travelCheckedAt:'2026-10-30T07:00:00.000Z'}),visit('square','武林广场','18:00',45)],legs:[{fromCandidateId:'curve',toCandidateId:'flowers',durationMinutes:40,status:'estimated',mode:'步行',provider:'amap',checkedAt:'2026-10-30T07:00:00.000Z',scope:'地图接口查询时估计，非未来保证。'}]}],sources:[source],wishes:[],constraints:{arrival:'',returnTime:'',hotel:''},budget:{stay:0,food:0,transport:0,tickets:0,misc:0},preparation:{umbrella:false,shoes:false,booking:false}};
}

function timingHarness(){const h=context(()=>{throw Error('Unexpected network');});h.scope.fixtureTrip=timingTrip();h.run("s.trip=fixtureTrip;s.history=[];s.activeDay=0;s.screen='detail';s.tab='itinerary';s.view='list';layers=[]");return h;}

test('saved day legs expose the real 40-minute estimate against a 30-minute gap without backend warnings',()=>{
 const h=timingHarness();
 assert.equal(h.run('s.trip.backendWarnings'),undefined);
 const list=h.run('resultBody(s)');
 assert.match(list,/估计步行40分钟/);
 assert.match(list,/预留30分钟，地图估计40分钟，请调整/);
 assert.match(list,/datetime="2026-10-30T07:00:00.000Z"/);
 assert.match(list,/仅为查询时估计/);
 assert.match(list,/交通待核对/);
 assert.match(list,/1段交通待核对/);
 h.run("s.tab='overview'");
 assert.match(h.run('resultBody(s)'),/1处交通时间不足，需要调整/);
 assert.equal(h.run('rvTravelIssues(s.trip).conflicts.length'),1);
});

test('only adjacent estimated destination legs display map travel time',()=>{
 const h=timingHarness();
 h.run("s.trip.days[0].legs[0].toCandidateId='square'");
 assert.doesNotMatch(h.run('rvListView(s,s.trip.days[0])'),/估计步行40分钟/);
 h.run("s.trip.days[0].legs[0].toCandidateId='flowers';s.trip.days[0].stops[1].travelStatus='unavailable'");
 assert.doesNotMatch(h.run('rvListView(s,s.trip.days[0])'),/估计步行40分钟/);
 h.run("s.trip.days[0].stops[1].travelStatus='estimated';seedRouteEvidence(s.trip);s.trip.days[0].stops[1].time='11:00'");
 assert.doesNotMatch(h.run('rvListView(s,s.trip.days[0])'),/估计步行40分钟/);
});

test('notes-only editing preserves map evidence and time editing invalidates it; undo restores the estimate',()=>{
 const h=timingHarness();
 h.run("seedRouteEvidence(s.trip);s.trip.backendWarnings=['原交通冲突提醒'];startEditor('flowers');s.editor.notes='仅补充备注';act('commit-editor',{dataset:{}})");
 assert.equal(h.run('s.trip.days[0].legs.length'),1);
 assert.equal(h.run('s.trip.days[0].stops[1].travelStatus'),'estimated');
 assert.equal(h.run('s.trip.backendWarningsStale'),undefined);
 assert.match(h.run('resultBody(s)'),/预留30分钟，地图估计40分钟，请调整/);
 h.run("startEditor('flowers');s.editor.time='11:00';act('commit-editor',{dataset:{}})");
 assert.equal(h.run('s.trip.days[0].legs.length'),0);
 assert.equal(h.run('s.trip.days[0].stops[1].travelMinutes'),null);
 assert.equal(h.run('s.trip.days[0].stops[1].travelStatus'),'unavailable');
 assert.equal(h.run('s.trip.backendWarningsStale'),true);
 assert.match(h.run('resultBody(s)'),/行程已修改/);
 assert.doesNotMatch(h.run('resultBody(s)'),/原交通冲突提醒|地图估计40分钟/);
 h.run("act('undo',{dataset:{}})");
 assert.equal(h.run('s.trip.days[0].stops[1].time'),'10:15');
 assert.equal(h.run('s.trip.days[0].stops[1].notes'),'仅补充备注');
 assert.equal(h.run('s.trip.days[0].legs.length'),1);
 assert.equal(h.run('s.trip.days[0].stops[1].travelStatus'),'estimated');
 assert.match(h.run('resultBody(s)'),/预留30分钟，地图估计40分钟，请调整/);
});

test('manual location changes and rule-based changes invalidate route evidence while undo restores it',()=>{
 for(const action of ['manual','rule','remove']){
  const h=timingHarness();
  h.run('seedRouteEvidence(s.trip)');
  if(action==='manual')h.run("layers=[{type:'confirm',id:'flowers',place:'新的手动地点'}];act('commit-manual-place',{dataset:{}})");
  if(action==='rule')h.run("s.proposal=Model.makeProposal(s,'晚出发一小时','day').proposal;act('apply-ai',{dataset:{}})");
  if(action==='remove')h.run("act('remove',{dataset:{id:'flowers'}})");
  assert.equal(h.run('s.trip.days[0].legs.length'),0,action);
  assert.equal(h.run('s.trip.backendWarningsStale'),true,action);
  assert.doesNotMatch(h.run('resultBody(s)'),/估计步行40分钟/,action);
  h.run("act('undo',{dataset:{}})");
  assert.equal(h.run('s.trip.days[0].legs.length'),1,action);
  assert.match(h.run('resultBody(s)'),/估计步行40分钟/,action);
 }
});

test('cross-day moves invalidate both affected days but leave untouched day evidence intact',()=>{
 const h=timingHarness();
 const extra=structuredClone(timingTrip().days[0]);
 extra.day=2;extra.date='2026-11-08';
 extra.stops=extra.stops.map(node=>({...node,id:node.id+'-day2',selectionId:node.id+'-day2'}));
 extra.legs=extra.legs.map(leg=>({...leg,fromCandidateId:leg.fromCandidateId+'-day2',toCandidateId:leg.toCandidateId+'-day2'}));
 h.scope.extraDay=extra;
 const untouched=structuredClone(extra);untouched.day=3;untouched.date='2026-11-09';untouched.stops=untouched.stops.map(node=>({...node,id:node.id+'-day3'}));untouched.legs=untouched.legs.map(leg=>({...leg,fromCandidateId:leg.fromCandidateId+'-day3',toCandidateId:leg.toCandidateId+'-day3'}));h.scope.untouchedDay=untouched;
 h.run("s.trip.days.push(extraDay,untouchedDay);seedRouteEvidence(s.trip);layers=[{type:'move',id:'flowers',targetDay:1,targetTime:'13:00'}];act('commit-move',{dataset:{}})");
 assert.equal(h.run('s.trip.days[0].legs.length'),0);
 assert.equal(h.run('s.trip.days[1].legs.length'),0);
 assert.equal(h.run('s.trip.days[1].stops.every(node=>node.travelStatus===\'unavailable\')'),true);
 assert.equal(h.run('s.trip.days[2].legs.length'),1);
 assert.equal(h.run('rvTravelIssues(s.trip,2).estimated'),1);
 h.run("act('undo',{dataset:{}})");
 assert.equal(h.run('s.trip.days[0].legs.length'),1);
 assert.equal(h.run('s.trip.days[1].legs.length'),1);
});

test('food candidates can be night markets without restaurant-specific pending labels',()=>{
 const h=timingHarness();
 const food={...candidate,id:'nightmarket',title:'武林夜市',category:'food',kind:'meal',locationStatus:'pending',time:'18:00',duration:60};
 h.scope.food=food;
 h.run("s.discovery={...Discovery.initial(),city:'杭州',category:'food',liveResearch:{id:'food-research',city:'杭州',sources:[],candidates:[food]}};s.trip.days[0].stops=[food]");
 const candidateHTML=h.run('discoveryBody(s)'),tripHTML=h.run('resultBody(s)');
 assert.match(candidateHTML,/武林夜市/);
 assert.match(candidateHTML,/具体地点待核对/);
 assert.doesNotMatch(candidateHTML,/餐馆之后再定|具体餐馆|分店/);
 assert.match(tripHTML,/餐饮地点待确认/);
 assert.match(tripHTML,/补充用餐地点/);
 assert.doesNotMatch(tripHTML,/待选餐馆|选择具体餐馆/);
});

test('TXT export includes current 40-versus-30 conflict, query scope, unknown legs and original source URLs',async()=>{
 const h=timingHarness();
 h.run("seedRouteEvidence(s.trip);s.trip.backendWarnings=['服务返回的交通提醒'];");
 let blob,clicks=0;
 class ExportURL extends URL{static createObjectURL(value){blob=value;return 'blob:mock-export';}static revokeObjectURL(){}}
 h.scope.URL=ExportURL;h.scope.Blob=Blob;h.scope.setTimeout=callback=>{callback();return 1;};
 h.scope.document.createElement=()=>({click(){clicks++;}});
 h.run("exportTrip('txt')");
 const text=await blob.text();
 assert.ok(text.split('\n').length>15,'TXT uses real newlines');
 assert.match(text,/预留30分钟，地图估计40分钟，请调整/);
 assert.match(text,/估计步行40分钟/);
 assert.match(text,/2026-10-30T07:00:00.000Z/);
 assert.match(text,/仅为查询时估计，不保证出行当天交通/);
 assert.match(text,/交通待核对/);
 assert.match(text,/服务返回的交通提醒/);
 assert.ok(text.includes(source.url));
 assert.ok(text.includes(source.title));
 h.run("exportTrip('json')");
 const exported=JSON.parse(await blob.text());
 assert.equal(exported.liveResearch,true);
 assert.equal(exported.trip.days[0].legs[0].durationMinutes,40);
 assert.equal(exported.trip.days[0].stops[1].travelStatus,'estimated');
 assert.equal(exported.trip.backendWarnings[0],'服务返回的交通提醒');
 assert.equal(clicks,2);
});

test('TXT omits invalidated estimates and stale warnings after time edits; undo restores both',()=>{
 const h=timingHarness();
 h.run("seedRouteEvidence(s.trip);s.trip.backendWarnings=['旧提醒：预留30分钟，地图估计40分钟'];startEditor('flowers');s.editor.time='11:00';act('commit-editor',{dataset:{}})");
 const changed=h.run('liveTripTXT(s.trip)');
 assert.match(changed,/2段待核对/);
 assert.match(changed,/旧编排提醒已过期/);
 assert.doesNotMatch(changed,/地图估计40分钟|估计步行40分钟|旧提醒：|2026-10-30T07:00:00.000Z/);
 h.run("act('undo',{dataset:{}})");
 const restored=h.run('liveTripTXT(s.trip)');
 assert.match(restored,/预留30分钟，地图估计40分钟，请调整/);
 assert.match(restored,/估计步行40分钟/);
 assert.match(restored,/旧提醒：/);
 assert.match(restored,/2026-10-30T07:00:00.000Z/);
});

test('legacy manual experience placement also clears exact-place evidence and undo restores it',()=>{
 const h=timingHarness();
 h.run("s.trip.days[0].stops[1].sourceRefs=[{sourceId:'old-source',evidenceText:'旧地点依据'}];s.trip.days[0].stops[1].notes='原有用户备注';layers=[{type:'experience-location',id:'flowers',place:'新的体验地点'}];act('commit-experience-place',{dataset:{}})");
 assert.equal(h.run('s.trip.days[0].stops[1].verification'),null);
 assert.equal(h.run('s.trip.days[0].stops[1].sourceRefs.length'),0);
 assert.equal(h.run('s.trip.days[0].stops[1].sourceIds.length'),0);
 assert.match(h.run('s.trip.days[0].stops[1].notes'),/原有用户备注.*\n手动地点：新的体验地点/);
 h.run("act('undo',{dataset:{}})");
 assert.equal(h.run('s.trip.days[0].stops[1].verification.status'),'verified');
 assert.equal(h.run('s.trip.days[0].stops[1].sourceRefs[0].evidenceText'),'旧地点依据');
});

test('ready plans must match captured day count, calendar and fixed constraints before client provenance is attached',()=>{
 const h=timingHarness();
 h.scope.form={days:1,undated:false,date:'2026-11-07',arrival:'',returnTime:'',hotel:''};
 assert.equal(h.run('livePlanMatchesForm(s.trip,form)'),true);
 for(const change of ["trip.days.push({...trip.days[0]})","trip.undated=true","trip.date='2026-11-08'","trip.days[0].date='2026-11-08'","trip.constraints.arrival='14:00'","trip.constraints.returnTime='19:00'","trip.constraints.hotel='不同的酒店'"]){
  assert.equal(h.run(`(()=>{const trip=Model.clone(s.trip);${change};return livePlanMatchesForm(trip,form)})()`),false,change);
 }
 h.run("s.trip.undated=true;s.trip.date=null;s.trip.days[0].date=null;form.undated=true;form.date='';form.hotel='   '");
 assert.equal(h.run('livePlanMatchesForm(s.trip,form)'),true);
});

test('ACTIVE_JOB_CONFLICT preserves the original plan handle and captured form while keeping the changed draft',async()=>{
 let submitted,posts=0,polls=0;
 const oldTrip={id:'original-job-trip',title:'苏州1日行程',version:1,date:null,undated:true,days:[{day:1,date:null,name:'古城',stops:[{...candidate,time:'09:00',selectionId:candidate.id,locked:false,manuallyEdited:false}]}],sources:[source],wishes:[{id:candidate.id,title:candidate.title,category:'sights',priority:'must',sourceIds:[sourceId]}],constraints:{arrival:'',returnTime:'',hotel:''},budget:{},preparation:{}};
 const h=context(async(path,options)=>{
  if(path==='/api/session')return response(session);
  if(path==='/api/plan'){posts++;submitted=JSON.parse(options.body);return response({error:{code:'ACTIVE_JOB_CONFLICT',message:'已有不同条件的规划任务正在运行。'}},409);}
  if(path==='/api/jobs/original-plan-job'){polls++;return response({status:'done',progress:'原任务完成',result:{status:'ready',trip:oldTrip,wishes:oldTrip.wishes}});}
  throw Error('Unexpected mock path '+path);
 });
 h.scope.fixture=research;
 h.run("s.discovery={...Discovery.initial(),city:'苏州',liveResearch:fixture,selected:{'candidate-one':{priority:'must'}}};const originalForm={...Model.fresh().form,destination:'苏州',days:1,date:'',undated:true,hotel:'',arrival:'',returnTime:''};s.job={jobId:'original-plan-job',status:'paused',resumable:true,form:Model.clone(originalForm),discovery:Model.clone(s.discovery),progress:'原任务正在整理'};s.form={...originalForm,days:2,date:'2026-12-01',undated:false,hotel:'修改后的酒店'};s.discovery.selected['candidate-one'].priority='want'");
 await h.run('startJob()');
 assert.equal(posts,1);assert.equal(polls,0);
 assert.equal(submitted.form.days,2);
 assert.equal(submitted.selected[0].priority,'want');
 assert.equal(h.run('s.job.jobId'),'original-plan-job');
 assert.equal(h.run('s.job.status'),'paused');
 assert.equal(h.run('s.job.resumable'),true);
 assert.equal(h.run('s.job.form.days'),1);
 assert.equal(h.run('s.job.form.undated'),true);
 assert.equal(h.run("s.job.discovery.selected['candidate-one'].priority"),'must');
 assert.equal(h.run('s.form.days'),2);
 assert.equal(h.run('s.form.hotel'),'修改后的酒店');
 assert.equal(h.run("s.discovery.selected['candidate-one'].priority"),'want');
 assert.match(h.run('progressBody(s)'),/1天 · 日期待定/);
 assert.match(h.run('homeBody(s)'),/1天 · 日期待定/);
 assert.doesNotMatch(h.run('progressBody(s)'),/修改后的酒店|12月1日出发/);
 assert.equal(h.run('Model.restore().state.job.jobId'),'original-plan-job');
 await h.run("act('resume-job',{dataset:{}})");
 assert.equal(posts,1);assert.equal(polls,1);
 assert.equal(h.run('s.trip.creationForm.days'),1);
 assert.equal(h.run('s.trip.creationForm.undated'),true);
 assert.equal(h.run('s.trip.creationForm.hotel'),'');
 assert.equal(h.run('s.form.days'),2);
 assert.equal(h.run('s.form.hotel'),'修改后的酒店');
 assert.equal(h.run('s.trip.planJobId'),'original-plan-job');
});

test('ACTIVE_JOB_CONFLICT preserves the original research city/job while keeping the new city input',async()=>{
 let submitted,posts=0,polls=0;
 const originalResearch={...research,id:'original-research',city:'杭州'};
 const h=context(async(path,options)=>{
  if(path==='/api/session')return response(session);
  if(path==='/api/research'){posts++;submitted=JSON.parse(options.body);return response({error:{code:'ACTIVE_JOB_CONFLICT',message:'已有不同城市的攻略任务正在运行。'}},409);}
  if(path==='/api/jobs/original-research-job'){polls++;return response({status:'done',progress:'原城市攻略完成',result:originalResearch});}
  throw Error('Unexpected mock path '+path);
 });
 h.run("s.discovery.city='苏州';s.research={jobId:'original-research-job',status:'paused',city:'杭州',progress:'正在读取杭州攻略'}");
 await h.run('startResearch()');
 assert.equal(posts,1);assert.equal(polls,0);
 assert.equal(submitted.destination,'苏州');
 assert.equal(h.run('s.research.jobId'),'original-research-job');
 assert.equal(h.run('s.research.city'),'杭州');
 assert.equal(h.run('s.research.status'),'paused');
 assert.equal(h.run('s.research.resumable'),true);
 assert.equal(h.run('s.discovery.city'),'苏州');
 assert.match(h.run('cityBody(s)'),/还有一项杭州的攻略任务/);
 assert.match(h.run('cityBody(s)'),/value="苏州"/);
 h.run("s.research.status='running'");
 assert.match(h.run('cityBody(s)'),/正在检索杭州攻略/);
 assert.doesNotMatch(h.run('cityBody(s)'),/正在检索苏州攻略/);
 h.run("s.research.status='paused'");
 await h.run("act('resume-research',{dataset:{}})");
 assert.equal(posts,1);assert.equal(polls,1);
 assert.equal(h.run('s.discovery.city'),'苏州');
 assert.equal(h.run('s.discovery.liveResearch.city'),'杭州');
 assert.equal(h.run('s.discovery.researchedFor'),'杭州');
 assert.equal(h.run('s.screen'),'discover');
 assert.equal(h.run('appError'),'');
});
