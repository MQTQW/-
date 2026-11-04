import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { request as httpRequest } from 'node:http';
import { createLiveServer, loadNativeToken } from '../serve.mjs';

// Fixed non-production credentials only for isolated loopback test servers.
const TOKEN='a'.repeat(64), OTHER='b'.repeat(64);
const POOL={id:'native-research-pool',city:'杭州',sources:[],candidates:[{id:'candidate-a',title:'有来源的候选',category:'sights'}]};
async function fixture(t,{enabled=true,engine:engineOverride={},planner:plannerOverride={}}={}){
  const calls={research:[],plan:[],image:[],connection:0};
  const engine={checkConnection:async()=>{calls.connection++;return{loggedIn:true,message:'已连接'};},research:async input=>{calls.research.push(input);return structuredClone(POOL);},...engineOverride};
  const planner={plan:async input=>{calls.plan.push(input);return{status:'ready',trip:{id:'native-trip',wishes:input.selected},unplaced:[]};},...plannerOverride};
  const media={has:id=>id==='native_image123',get:async id=>{calls.image.push(id);return{mime:'image/png',bytes:Buffer.from([137,80,78,71])};}};
  const app=createLiveServer({engine,planner,media,config:{aiKey:'fixture-ai-key'},port:0,...(enabled?{nativeToken:TOKEN}:{})});
  await new Promise((resolve,reject)=>{app.server.once('error',reject);app.server.listen(0,'127.0.0.1',resolve);});
  t.after(()=>app.close());
  const origin=`http://127.0.0.1:${app.server.address().port}`;
  const request=(route,init={})=>fetch(origin+route,init);
  const headers=(auth,extra={})=>({Authorization:`Bearer ${TOKEN}`,...(auth?{Cookie:auth.cookie,'X-CSRF-Token':auth.csrf}:{}),...extra});
  async function session(native=true){const response=await request(native?'/api/native/session':'/api/session',{headers:native?headers():{}});assert.equal(response.status,200);const body=await response.json();return{cookie:response.headers.get('set-cookie').split(';')[0],csrf:body.csrfToken,response,body};}
  const post=(route,body,auth,extra={})=>request(route,{method:'POST',headers:headers(auth,{'Content-Type':'application/json',...extra}),body:JSON.stringify(body)});
  async function job(id,auth){for(let attempt=0;attempt<30;attempt++){const response=await request(`/api/native/jobs/${id}`,{headers:headers(auth)}),body=await response.json();if(body.status!=='running')return{response,body};await new Promise(resolve=>setTimeout(resolve,5));}throw Error('Stub job did not settle');}
  async function research(auth){const response=await post('/api/native/research',{destination:'杭州',count:1},auth);assert.equal(response.status,202);const{jobId}=await response.json();return{jobId,...await job(jobId,auth)};}
  return{app,calls,origin,request,headers,session,post,job,research};
}

test('native routes are unavailable by default and do not affect web bootstrap',async t=>{
  const f=await fixture(t,{enabled:false});
  for(const route of ['/api/native/session','/api/native/images/native_image123','/api/native/jobs/00000000-0000-0000-0000-000000000000']){
    const response=await f.request(route,{headers:f.headers()});assert.equal(response.status,404);assert.equal((await response.json()).error.code,'NOT_FOUND');
  }
  assert.equal((await f.session(false)).response.status,200);
});

test('every native route requires a correctly formed bearer, including bootstrap and media',async t=>{
  const f=await fixture(t),auth=await f.session();
  for(const route of ['/api/native/session','/api/native/jobs/00000000-0000-0000-0000-000000000000','/api/native/images/native_image123']){
    for(const authorization of [undefined,`Bearer ${OTHER}`,'Bearer short',`Basic ${TOKEN}`,`Bearer ${'é'.repeat(64)}`,`bearer ${TOKEN}`]){
      const response=await f.request(route,{headers:{Cookie:auth.cookie,...(authorization?{Authorization:authorization}:{})}});
      assert.equal(response.status,401);assert.equal((await response.json()).error.code,'NATIVE_AUTH_REQUIRED');
      assert.match(response.headers.get('www-authenticate'),/^Bearer /);
    }
  }
  for(const route of ['/api/native/research','/api/native/plan']){
    const response=await f.post(route,{},auth,{Authorization:''});assert.equal(response.status,401);
  }
  assert.deepEqual(f.calls.image,[]);assert.deepEqual(f.calls.research,[]);assert.deepEqual(f.calls.plan,[]);
});

test('native bootstrap creates a private scoped cookie without returning credentials',async t=>{
  const f=await fixture(t),auth=await f.session();
  assert.match(auth.response.headers.get('set-cookie'),/^myplan_native_session=[a-f0-9]{64}; Path=\/api\/native; HttpOnly; SameSite=Strict;/);
  assert.match(auth.csrf,/^[a-f0-9]{64}$/);
  assert.deepEqual(auth.body.capabilities,{research:true,ai:true,map:false});
  assert.equal(auth.body.connection.loggedIn,true);
  assert.equal(auth.response.headers.get('cache-control'),'no-store');
  assert.equal(JSON.stringify(auth.body).includes(TOKEN),false);
  assert.equal(JSON.stringify(auth.body).includes('fixture-ai-key'),false);
  assert.equal(auth.response.headers.get('access-control-allow-origin'),null);
});

test('native requests reject browser Origin/fetch metadata and foreign Host',async t=>{
  const f=await fixture(t);
  for(const extra of [{Origin:f.origin},{Origin:'https://foreign.example'},{Origin:'null'},{'Sec-Fetch-Site':'cross-site'},{'Sec-Fetch-Site':'same-origin'},{'Sec-Fetch-Site':'same-site'}]){
    const response=await f.request('/api/native/session',{headers:f.headers(null,extra)});assert.equal(response.status,403);assert.equal((await response.json()).error.code,'ORIGIN_REJECTED');
  }
  const status=await new Promise((resolve,reject)=>{const req=httpRequest(f.origin+'/api/native/session',{headers:f.headers(null,{Host:'foreign.example'})},res=>{res.resume();res.once('end',()=>resolve(res.statusCode));});req.once('error',reject);req.end();});
  assert.equal(status,403);
});

test('native POST retains cookie and CSRF requirements without needing Origin',async t=>{
  const f=await fixture(t),auth=await f.session(),body={destination:'杭州',count:1};
  const noCookie=await f.request('/api/native/research',{method:'POST',headers:f.headers(null,{'Content-Type':'application/json','X-CSRF-Token':auth.csrf}),body:JSON.stringify(body)});
  assert.equal(noCookie.status,401);assert.equal((await noCookie.json()).error.code,'SESSION_REQUIRED');
  for(const csrf of ['',OTHER,'é'.repeat(64)]){const rejected=await f.post('/api/native/research',body,auth,{'X-CSRF-Token':csrf});assert.equal(rejected.status,403);assert.equal((await rejected.json()).error.code,'CSRF_REJECTED');}
  assert.equal(f.calls.research.length,0);
  assert.equal((await f.research(auth)).body.status,'done');
});

test('web and native cookies cannot be substituted across channels',async t=>{
  const f=await fixture(t),web=await f.session(false),native=await f.session();
  const webId=web.cookie.split('=')[1],nativeId=native.cookie.split('=')[1];
  for(const cookie of [web.cookie,`myplan_native_session=${webId}`]){const response=await f.request('/api/native/images/native_image123',{headers:f.headers(null,{Cookie:cookie})});assert.equal(response.status,401);assert.equal((await response.json()).error.code,'SESSION_REQUIRED');}
  for(const cookie of [native.cookie,`myplan_live_session=${nativeId}`]){const response=await f.request('/api/images/native_image123',{headers:{Cookie:cookie}});assert.equal(response.status,401);assert.equal((await response.json()).error.code,'SESSION_REQUIRED');}
  // Knowing a native bearer does not relax the existing browser Origin gate.
  const response=await f.post('/api/research',{destination:'杭州',count:1},web);assert.equal(response.status,403);assert.equal((await response.json()).error.code,'ORIGIN_REJECTED');
});

test('native job and research ownership are per session, despite a shared device token',async t=>{
  const f=await fixture(t),owner=await f.session(),other=await f.session(),research=await f.research(owner);
  const outsider=await f.request(`/api/native/jobs/${research.jobId}`,{headers:f.headers(other)});assert.equal(outsider.status,404);assert.equal((await outsider.json()).error.code,'JOB_NOT_FOUND');
  const payload={researchId:POOL.id,selected:[{id:'candidate-a',priority:'must'}],form:{destination:'杭州',days:1}};
  const denied=await f.post('/api/native/plan',payload,other);assert.equal(denied.status,409);assert.equal((await denied.json()).error.code,'RESEARCH_EXPIRED');
  assert.equal(f.calls.plan.length,0);
  const accepted=await f.post('/api/native/plan',payload,owner);assert.equal(accepted.status,202);assert.equal((await f.job((await accepted.json()).jobId,owner)).body.result.status,'ready');
  assert.deepEqual(f.calls.plan[0].selected,payload.selected);assert.deepEqual(f.calls.plan[0].research,POOL);
});

test('native media requires bearer and native cookie; only registered opaque images can be fetched',async t=>{
  const f=await fixture(t),auth=await f.session();
  const image=await f.request('/api/native/images/native_image123',{headers:f.headers(auth)});assert.equal(image.status,200);assert.equal(image.headers.get('content-type'),'image/png');assert.deepEqual(Buffer.from(await image.arrayBuffer()),Buffer.from([137,80,78,71]));
  const unknown=await f.request('/api/native/images/unknown_image123',{headers:f.headers(auth)});assert.equal(unknown.status,404);assert.equal((await unknown.json()).error.code,'IMAGE_NOT_FOUND');
  for(const query of ['?token='+TOKEN,'?url=https://foreign.example/image.png']){const response=await f.request('/api/native/images/native_image123'+query,{headers:f.headers(auth)});assert.equal(response.status,400);assert.equal((await response.json()).error.code,'INVALID_ROUTE');}
  assert.deepEqual(f.calls.image,['native_image123']);
});

test('native endpoint spelling and normalized paths cannot bypass browser protections',async t=>{
  const f=await fixture(t),auth=await f.session();
  for(const route of ['/api/native/native/session','/api/native/%2e%2e/research','/api/native/../research','/api/native/research/','/api/native%2Fresearch']){
    const response=await f.post(route,{destination:'杭州',count:1},auth);assert.notEqual(response.status,202);
  }
  assert.equal(f.calls.research.length,0);
});

test('native active research reuses identical inputs and preserves changed-input conflict',async t=>{
  const f=await fixture(t,{engine:{research:({signal})=>new Promise((_,reject)=>signal.addEventListener('abort',()=>reject(Error('done')),{once:true}))}}),auth=await f.session();
  const payload={destination:'杭州',count:1},first=await f.post('/api/native/research',payload,auth),id=(await first.json()).jobId;
  const repeated=await f.post('/api/native/research',payload,auth);assert.equal(repeated.status,202);assert.equal((await repeated.json()).jobId,id);
  const changed=await f.post('/api/native/research',{destination:'苏州',count:1},auth);assert.equal(changed.status,409);assert.equal((await changed.json()).error.code,'ACTIVE_JOB_CONFLICT');
});

test('native sessions expire and do not recover another session owner after reconnecting',async t=>{
  const f=await fixture(t),auth=await f.session(),research=await f.research(auth),realNow=Date.now;
  try{Date.now=()=>realNow()+4*3_600_000+1000;const response=await f.request(`/api/native/jobs/${research.jobId}`,{headers:f.headers(auth)});assert.equal(response.status,401);assert.equal((await response.json()).error.code,'SESSION_REQUIRED');const next=await f.session();assert.notEqual(next.cookie,auth.cookie);const lost=await f.request(`/api/native/jobs/${research.jobId}`,{headers:f.headers(next)});assert.equal(lost.status,404);}
  finally{Date.now=realNow;}
});

test('native token config accepts only explicit JSON with a 64-character hex token',async t=>{
  const folder=await mkdtemp(path.join(os.tmpdir(),'myplan-native-config-'));
  t.after(()=>rm(folder,{recursive:true,force:true}));
  const file=path.join(folder,'native-connection.json');
  await writeFile(file,JSON.stringify({token:TOKEN}));assert.equal(await loadNativeToken(file),TOKEN);
  for(const value of ['not-json',JSON.stringify({token:'short'}),JSON.stringify({token:TOKEN.toUpperCase()}),'[]',JSON.stringify({token:'é'.repeat(64)}),'x'.repeat(4097)]){
    await writeFile(file,value);await assert.rejects(loadNativeToken(file),error=>error.code==='NATIVE_CONFIG_INVALID'&&!error.message.includes(TOKEN));
  }
  await assert.rejects(loadNativeToken(path.join(folder,'missing.json')),error=>error.code==='NATIVE_CONFIG_INVALID');
  for(const value of ['',null,OTHER+'a'])assert.throws(()=>createLiveServer({nativeToken:value}),error=>error.code==='NATIVE_CONFIG_INVALID');
});
