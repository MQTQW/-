import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp,readFile,writeFile,rm,mkdir } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { request as httpRequest } from 'node:http';
import { createLiveServer,validateDeploymentOptions } from '../serve.mjs';

// Isolated loopback fixtures: no provider credentials or external requests.
const TOKEN='c'.repeat(64),DEPLOYMENT='https://travel.example.test';
const POOL={id:'remote-pool',city:'杭州',candidates:[{id:'candidate-A'}],sources:[]};
async function fixture(t,options={}){
  const directory=options.directory||await mkdtemp(path.join(os.tmpdir(),'myplan-remote-'));
  if(!options.directory)t.after(()=>rm(directory,{recursive:true,force:true}));
  const budgetFile=path.join(directory,'budget.json'),calls={research:0,plan:0,connection:0};
  const engine={checkConnection:async()=>{calls.connection++;return{loggedIn:true,message:'已连接'};},research:async()=>{calls.research++;return structuredClone(POOL);},...options.engine};
  const planner={plan:async()=>{calls.plan++;return{status:'ready',trip:{id:'remote-trip'}};},...options.planner};
  const app=createLiveServer({engine,planner,media:{has:()=>false},config:{aiKey:'private-fixture-only'},port:0,
    deploymentOrigin:DEPLOYMENT,nativeToken:TOKEN,budgetFile,...options.settings});
  await new Promise((resolve,reject)=>{app.server.once('error',reject);app.server.listen(0,'127.0.0.1',resolve);});
  t.after(()=>app.close());
  const origin=`http://127.0.0.1:${app.server.address().port}`;
  const headers=(auth,extra={})=>({Authorization:`Bearer ${TOKEN}`,...(auth?{Cookie:auth.cookie,'X-CSRF-Token':auth.csrf}:{}),...extra});
  const request=(route,options={})=>fetch(origin+route,options);
  async function session(extra={}){const response=await request('/api/native/session',{headers:headers(null,extra)}),body=await response.json();return{response,body,cookie:response.headers.get('set-cookie')?.split(';')[0],csrf:body.csrfToken};}
  const post=(route,body,auth,extra={})=>request(route,{method:'POST',headers:headers(auth,{'Content-Type':'application/json',...extra}),body:JSON.stringify(body)});
  async function job(id,auth){for(let i=0;i<30;i++){const response=await request(`/api/native/jobs/${id}`,{headers:headers(auth)}),body=await response.json();if(body.status!=='running')return body;await new Promise(resolve=>setTimeout(resolve,3));}throw Error('Fixture task did not settle');}
  async function research(auth,city='杭州'){const response=await post('/api/native/research',{destination:city,count:1},auth);assert.equal(response.status,202);return job((await response.json()).jobId,auth);}
  return{app,directory,budgetFile,calls,origin,request,headers,session,post,job,research};
}

test('remote binding requires explicit HTTPS origin, bearer and persistent budget',()=>{
  const file=path.resolve(os.tmpdir(),'unused-myplan-remote-validation.json');
  for(const options of [
    {listenHost:'0.0.0.0'},
    {deploymentOrigin:DEPLOYMENT,budgetFile:file},
    {deploymentOrigin:DEPLOYMENT,nativeToken:TOKEN},
    {deploymentOrigin:DEPLOYMENT,nativeToken:TOKEN,budgetFile:'relative.json'},
    ...['http://travel.example.test','https://user:pass@travel.example.test','https://travel.example.test/path','https://travel.example.test?token=private','https://travel.example.test#fragment','bad'].map(deploymentOrigin=>({deploymentOrigin,nativeToken:TOKEN,budgetFile:file})),
    {deploymentOrigin:DEPLOYMENT,nativeToken:TOKEN,budgetFile:file,listenHost:'::'},
    {requestLimitPerMinute:0},{researchLimitPerHour:121},{planLimitPerHour:1.5},{maxSessions:257},
  ])assert.throws(()=>validateDeploymentOptions(options),error=>error.code==='DEPLOYMENT_CONFIG_INVALID');
  const valid=validateDeploymentOptions({deploymentOrigin:DEPLOYMENT+'/',nativeToken:TOKEN,budgetFile:file,listenHost:'0.0.0.0'});
  assert.equal(valid.deploymentOrigin,DEPLOYMENT);assert.equal(valid.listenHost,'0.0.0.0');
  assert.deepEqual([valid.researchLimitPerHour,valid.planLimitPerHour],[6,12]);
  assert.equal(validateDeploymentOptions().listenHost,'127.0.0.1');
});

test('deployment exposes only native API, preserving bearer and Origin rejection',async t=>{
  const f=await fixture(t);
  for(const route of ['/','/index.html','/api/session','/api/research','/health','/mcp','/api/v1/login/qrcode']){
    const response=await f.request(route,{headers:f.headers()});assert.equal(response.status,404);assert.equal((await response.json()).error.code,'NOT_FOUND');
  }
  assert.equal((await f.request('/api/native/session')).status,401);
  assert.equal((await f.session({Origin:DEPLOYMENT})).response.status,403);
  assert.equal((await f.session({'Sec-Fetch-Site':'same-origin'})).response.status,403);
  const foreignHost=await new Promise((resolve,reject)=>{const request=httpRequest(f.origin+'/api/native/session',{headers:f.headers(null,{Host:'travel.example.test'})},response=>{response.resume();response.once('end',()=>resolve(response.statusCode));});request.once('error',reject);request.end();});
  assert.equal(foreignHost,403);
  assert.equal((await f.session()).response.status,200);
});

test('deployment cookie is Secure and scoped regardless of spoofed forwarded headers',async t=>{
  const f=await fixture(t),auth=await f.session({'X-Forwarded-Proto':'http','X-Forwarded-Host':'foreign.example'});
  assert.equal(auth.response.status,200);
  const cookie=auth.response.headers.get('set-cookie');assert.match(cookie,/Path=\/api\/native; HttpOnly; SameSite=Strict;/);assert.match(cookie,/; Secure$/);
  const body=JSON.stringify(auth.body);assert.equal(body.includes(TOKEN),false);assert.equal(body.includes('private-fixture-only'),false);
  assert.equal(auth.response.headers.get('access-control-allow-origin'),null);
  for(const extra of [{'X-CSRF-Token':''},{Cookie:''}]){
    const response=await f.post('/api/native/research',{destination:'杭州',count:1},auth,extra);assert.ok([401,403].includes(response.status));
  }
  assert.equal(f.calls.research,0);
});

test('forwarded HTTPS alone never enables deployment mode or changes a local cookie',async t=>{
  const app=createLiveServer({engine:{checkConnection:async()=>({loggedIn:true})},nativeToken:TOKEN,port:0});
  await new Promise(resolve=>app.server.listen(0,'127.0.0.1',resolve));t.after(()=>app.close());
  const response=await fetch(`http://127.0.0.1:${app.server.address().port}/api/native/session`,{headers:{Authorization:`Bearer ${TOKEN}`,'X-Forwarded-Proto':'https'}});
  assert.equal(response.status,200);assert.doesNotMatch(response.headers.get('set-cookie'),/; Secure/);
});

test('request counters are bounded globally and unauthorized bursts do not spend the authorized allowance',async t=>{
  const f=await fixture(t,{settings:{requestLimitPerMinute:2}});
  assert.equal((await f.request('/api/native/session')).status,401);
  assert.equal((await f.request('/api/native/session')).status,401);
  const denied=await f.request('/api/native/session');assert.equal(denied.status,429);assert.equal((await denied.json()).error.code,'RATE_LIMITED');assert.ok(Number(denied.headers.get('retry-after'))>=1);
  const auth=await f.session();assert.equal(auth.response.status,200);
  assert.equal((await f.request('/api/native/session',{headers:f.headers(auth)})).status,200);
  const limited=await f.request('/api/native/session',{headers:f.headers(auth)});assert.equal(limited.status,429);
  const realNow=Date.now;
  try{const later=realNow()+61000;Date.now=()=>later;assert.equal((await f.request('/api/native/session',{headers:f.headers(auth)})).status,200);}
  finally{Date.now=realNow;}
  assert.equal(f.calls.research,0);
});

test('session capacity cannot grow without bound and existing sessions remain usable',async t=>{
  const f=await fixture(t,{settings:{maxSessions:2}}),a=await f.session(),b=await f.session();
  assert.equal(a.response.status,200);assert.equal(b.response.status,200);
  const full=await f.session();assert.equal(full.response.status,429);assert.equal(full.body.error.code,'SERVICE_BUSY');
  const existing=await f.request('/api/native/session',{headers:f.headers(a)});assert.equal(existing.status,200);
});

test('paid-start limits are separate, persist restart and expire on the rolling hour',async t=>{
  const first=await fixture(t,{settings:{researchLimitPerHour:1,planLimitPerHour:1}}),auth=await first.session();
  await first.research(auth);
  const payload={researchId:POOL.id,selected:[{id:'candidate-A',priority:'want'}],form:{days:1,destination:'杭州'}};
  let response=await first.post('/api/native/plan',payload,auth);assert.equal(response.status,202);await first.job((await response.json()).jobId,auth);
  for(const [route,body]of [['/api/native/research',{destination:'杭州',count:1}],['/api/native/plan',payload]]){response=await first.post(route,body,auth);assert.equal(response.status,429);assert.equal((await response.json()).error.code,'AI_BUDGET_EXCEEDED');}
  assert.deepEqual([first.calls.research,first.calls.plan],[1,1]);
  const ledger=JSON.parse(await readFile(first.budgetFile,'utf8'));assert.equal(ledger.research.length,1);assert.equal(ledger.plan.length,1);assert.equal(JSON.stringify(ledger).includes(TOKEN),false);
  await first.app.close();
  const next=await fixture(t,{directory:first.directory,settings:{researchLimitPerHour:1,planLimitPerHour:1}}),nextAuth=await next.session();
  response=await next.post('/api/native/research',{destination:'苏州',count:1},nextAuth);assert.equal(response.status,429);assert.equal(next.calls.research,0);
  const realNow=Date.now;
  try{const later=realNow()+3600001;Date.now=()=>later;await next.research(nextAuth,'苏州');assert.equal(next.calls.research,1);}
  finally{Date.now=realNow;}
  const updated=JSON.parse(await readFile(first.budgetFile,'utf8'));assert.equal(updated.research.length,1);assert.equal(updated.plan.length,0);
});

test('running duplicate requests and invalid payloads never consume another paid start',async t=>{
  let finish;
  const f=await fixture(t,{settings:{researchLimitPerHour:1},engine:{research:input=>{f.calls.research++;return new Promise((resolve,reject)=>{finish=()=>resolve(structuredClone(POOL));input.signal.addEventListener('abort',()=>reject(Error('fixture closed')),{once:true});});}}}),auth=await f.session();
  const invalid=await f.post('/api/native/research',{destination:'',count:1},auth);assert.equal(invalid.status,400);
  const payload={destination:'杭州',count:1},first=await f.post('/api/native/research',payload,auth),id=(await first.json()).jobId;
  const same=await f.post('/api/native/research',payload,auth);assert.equal(same.status,202);assert.equal((await same.json()).jobId,id);
  const changed=await f.post('/api/native/research',{destination:'苏州',count:1},auth);assert.equal(changed.status,409);
  assert.equal(JSON.parse(await readFile(f.budgetFile,'utf8')).research.length,1);assert.equal(f.calls.research,1);
  finish();await f.job(id,auth);
});

test('corrupt or unwritable persistent ledger fails closed before starting a job',async t=>{
  const f=await fixture(t),auth=await f.session();
  await writeFile(f.budgetFile,'broken-private-record');
  assert.throws(()=>createLiveServer({deploymentOrigin:DEPLOYMENT,nativeToken:TOKEN,budgetFile:f.budgetFile}),error=>error.code==='BUDGET_CONFIG_INVALID'&&!error.message.includes(f.budgetFile));
  await rm(f.budgetFile);await mkdir(f.budgetFile);
  const response=await f.post('/api/native/research',{destination:'杭州',count:1},auth);assert.equal(response.status,503);assert.equal((await response.json()).error.code,'BUDGET_UNAVAILABLE');assert.equal(f.calls.research,0);
});

test('HTTP parser and connection timeouts remain finite in deployment mode',async t=>{
  const f=await fixture(t);
  assert.equal(f.app.server.headersTimeout,15000);assert.equal(f.app.server.requestTimeout,30000);assert.equal(f.app.server.keepAliveTimeout,5000);assert.equal(f.app.server.maxHeadersCount,64);assert.equal(f.app.server.maxRequestsPerSocket,100);
});
