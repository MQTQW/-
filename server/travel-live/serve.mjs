import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { existsSync,readFileSync,writeFileSync,renameSync,mkdirSync,unlinkSync } from 'node:fs';
import { randomBytes,randomUUID,timingSafeEqual,createHash } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadConfig } from '../travel-research/config.mjs';
import { createAmap } from '../travel-research/maps.mjs';
import { createReadOnlyMcpClient } from './mcp-client.mjs';
import { createMediaStore } from './media.mjs';
import { createResearchEngine,ResearchEngineError } from './engine.mjs';
import { createSelectionPlanner,SelectionPlannerError } from './selection-planner.mjs';

const ROOT=fileURLToPath(new URL('../../',import.meta.url));
const COOKIE='myplan_live_session';
const NATIVE_COOKIE='myplan_native_session';
const PUBLIC_ERROR=Symbol('public-travel-error');
const fail=(code,message,status=400)=>Object.assign(new Error(message),{code,status,[PUBLIC_ERROR]:true});
const same=(a,b)=>{if(typeof a!=='string'||typeof b!=='string')return false;const left=Buffer.from(a),right=Buffer.from(b);return left.length===right.length&&timingSafeEqual(left,right);};
const progressMessage=value=>String(typeof value==='string'?value:value?.message||'正在处理').slice(0,300);
const canonical=value=>Array.isArray(value)?value.map(canonical):value&&typeof value==='object'?Object.fromEntries(Object.keys(value).sort().map(key=>[key,canonical(value[key])])):value;
const fingerprint=value=>createHash('sha256').update(JSON.stringify(canonical(value))).digest('hex');
const safeError=error=>{const trusted=error?.[PUBLIC_ERROR]||error instanceof ResearchEngineError||error instanceof SelectionPlannerError;return{code:trusted&&/^[A-Z][A-Z0-9_]{1,63}$/.test(error?.code||'')?error.code:'REQUEST_FAILED',message:trusted&&typeof error?.message==='string'?error.message.slice(0,500):'本次操作未能完成，请重试。'};};
async function bodyJson(req){let size=0,parts=[];for await(const part of req){size+=part.length;if(size>24000)throw fail('BODY_TOO_LARGE','请求过大，请减少所选内容。',413);parts.push(part);}try{const value=JSON.parse(Buffer.concat(parts).toString('utf8'));if(!value||typeof value!=='object'||Array.isArray(value))throw Error();return value;}catch{throw fail('INVALID_JSON','请求内容格式不正确。');}}

/** Native access is disabled unless the operator explicitly supplies a machine-private token. */
export async function loadNativeToken(file){
  try{const bytes=await readFile(file);if(bytes.length>4096)throw Error();const value=JSON.parse(bytes.toString('utf8'));if(!value||Array.isArray(value)||typeof value.token!=='string'||!/^[a-f0-9]{64}$/.test(value.token))throw Error();return value.token;}
  catch{throw fail('NATIVE_CONFIG_INVALID','手机联调配置无效，请重新生成本机连接配置。');}
}

/** Public ingress terminates HTTPS in a private reverse proxy; forwarded headers never select this mode. */
export function validateDeploymentOptions({deploymentOrigin,listenHost='127.0.0.1',nativeToken,budgetFile,
  requestLimitPerMinute=180,researchLimitPerHour=6,planLimitPerHour=12,maxSessions=64}={}){
  let normalizedOrigin;
  if(deploymentOrigin!==undefined){
    try{const value=new URL(deploymentOrigin);if(value.protocol!=='https:'||value.username||value.password||value.pathname!=='/'||value.search||value.hash)throw Error();normalizedOrigin=value.origin;}
    catch{throw fail('DEPLOYMENT_CONFIG_INVALID','部署地址需要使用 HTTPS，且只填写域名和可选端口。');}
    if(typeof nativeToken!=='string'||!/^[a-f0-9]{64}$/.test(nativeToken)||typeof budgetFile!=='string'||!path.isAbsolute(budgetFile))throw fail('DEPLOYMENT_CONFIG_INVALID','部署模式需要独立手机连接码与绝对路径的持久用量文件。');
  }
  if(!['127.0.0.1','0.0.0.0'].includes(listenHost)||(listenHost==='0.0.0.0'&&!normalizedOrigin))throw fail('DEPLOYMENT_CONFIG_INVALID','对外监听仅可在已配置的 HTTPS 部署模式使用。');
  for(const [value,maximum]of [[requestLimitPerMinute,1000],[researchLimitPerHour,120],[planLimitPerHour,120],[maxSessions,256]])if(!Number.isInteger(value)||value<1||value>maximum)throw fail('DEPLOYMENT_CONFIG_INVALID','请求或用量上限配置无效。');
  return{deploymentOrigin:normalizedOrigin,listenHost,budgetFile,requestLimitPerMinute,researchLimitPerHour,planLimitPerHour,maxSessions};
}

function createPaidBudget(file,{researchLimitPerHour,planLimitPerHour}){
  const fresh=()=>({version:1,research:[],plan:[]});
  function write(value){
    const directory=path.dirname(file),temporary=path.join(directory,`.myplan-budget-${randomUUID()}.tmp`);
    try{mkdirSync(directory,{recursive:true});writeFileSync(temporary,JSON.stringify(value),{encoding:'utf8',mode:0o600,flag:'wx'});renameSync(temporary,file);}
    catch{try{if(existsSync(temporary))unlinkSync(temporary);}catch{}throw fail('BUDGET_UNAVAILABLE','用量记录暂时无法保存，为避免超出用量，本次没有开始新任务。',503);}
  }
  let state=fresh();
  if(existsSync(file)){
    try{const bytes=readFileSync(file);if(bytes.length>16384)throw Error();state=JSON.parse(bytes.toString('utf8'));if(!state||state.version!==1)throw Error();for(const kind of ['research','plan'])if(!Array.isArray(state[kind])||state[kind].length>120||state[kind].some(value=>!Number.isSafeInteger(value)||value<0))throw Error();}
    catch{throw fail('BUDGET_CONFIG_INVALID','持久用量记录无法读取，请先检查服务端记录；不会重置用量继续调用。',503);}
  }else write(state);
  return kind=>{
    const now=Date.now(),next={version:1,research:state.research.filter(value=>value>now-3600000),plan:state.plan.filter(value=>value>now-3600000)};
    const limit=kind==='research'?researchLimitPerHour:planLimitPerHour;
    if(next[kind].length>=limit)throw fail('AI_BUDGET_EXCEEDED','本小时的旅行处理次数已用完，请稍后再试。',429);
    next[kind].push(now);write(next);state=next;
  };
}

export function createLiveServer({engine,planner,media,config={},indexFile=path.join(ROOT,'design/travel-live/index.html'),port=8794,jobTimeoutMs=480000,nativeToken,
  deploymentOrigin,listenHost='127.0.0.1',budgetFile,requestLimitPerMinute=180,researchLimitPerHour=6,planLimitPerHour=12,maxSessions=64}={}){
  if(nativeToken!==undefined&&(typeof nativeToken!=='string'||!/^[a-f0-9]{64}$/.test(nativeToken)))throw fail('NATIVE_CONFIG_INVALID','手机联调配置无效，请重新生成本机连接配置。');
  const deployment=validateDeploymentOptions({deploymentOrigin,listenHost,nativeToken,budgetFile,requestLimitPerMinute,researchLimitPerHour,planLimitPerHour,maxSessions});
  const remote=!!deployment.deploymentOrigin,consumeBudget=remote?createPaidBudget(budgetFile,deployment):()=>{};
  const requests={authorized:{since:Date.now(),count:0},unauthorized:{since:Date.now(),count:0}};
  function limitRequest(req,res){
    if(!remote)return;
    const match=typeof req.headers.authorization==='string'&&req.headers.authorization.match(/^Bearer ([a-f0-9]{64})$/),authorized=!!match&&same(match[1],nativeToken),bucket=requests[authorized?'authorized':'unauthorized'],now=Date.now();
    if(now-bucket.since>=60000||now<bucket.since){bucket.since=now;bucket.count=0;}
    const limit=authorized?requestLimitPerMinute:Math.min(60,requestLimitPerMinute);
    if(++bucket.count>limit){res.setHeader('Retry-After',String(Math.max(1,Math.ceil((bucket.since+60000-now)/1000))));throw fail('RATE_LIMITED','请求较多，请稍后再试。',429);}
  }
  const sessions=new Map(),jobs=new Map(),researches=new Map();let connectionCache=null,connectionAt=0,connectionPending=null;
  const expire=()=>{const cutoff=Date.now()-4*3600000;for(const[id,s]of sessions)if(s.seen<cutoff)sessions.delete(id);for(const[id,j]of jobs)if(j.updatedAt<cutoff)jobs.delete(id);for(const[id,r]of researches)if(r.createdAt<cutoff)researches.delete(id);};
  async function connection(force=false){if(!force&&connectionCache&&Date.now()-connectionAt<20000)return connectionCache;if(!connectionPending)connectionPending=Promise.resolve().then(()=>engine.checkConnection()).catch(()=>({loggedIn:false,message:'本机检索服务暂时不可用。'})).then(value=>{connectionCache={loggedIn:value?.loggedIn===true,message:typeof value?.message==='string'?value.message.slice(0,180):'连接状态未确认。'};connectionAt=Date.now();return connectionCache;}).finally(()=>{connectionPending=null;});return connectionPending;}
  function getSession(req,res,create=false,native=false){const cookieName=native?NATIVE_COOKIE:COOKIE,channel=native?'native':'web';const cookies=Object.fromEntries(String(req.headers.cookie||'').split(';').map(v=>v.trim().split('=')).filter(v=>v.length===2));let session=sessions.get(cookies[cookieName]);if(session?.channel!==channel)session=undefined;if(!session&&create){if(sessions.size>=maxSessions)throw fail('SERVICE_BUSY','服务连接较多，请稍后重新连接。',429);const id=randomBytes(32).toString('hex');session={id,csrf:randomBytes(32).toString('hex'),seen:Date.now(),channel};sessions.set(id,session);res.setHeader('Set-Cookie',`${cookieName}=${id}; Path=${native?'/api/native':'/'}; HttpOnly; SameSite=Strict; Max-Age=14400${remote?'; Secure':''}`);}if(session)session.seen=Date.now();return session;}
  function json(res,status,data){res.writeHead(status,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'});res.end(JSON.stringify(data));}
  function ownedJob(id,session){const job=jobs.get(id);if(!job||job.owner!==session.id)throw fail('JOB_NOT_FOUND','这项任务不存在或已过期。',404);return job;}
  function newJob(kind,session,requestKey,run){const active=[...jobs.values()].find(j=>j.owner===session.id&&j.kind===kind&&j.status==='running');if(active){if(active.requestKey===requestKey)return active;throw fail('ACTIVE_JOB_CONFLICT','已有不同条件的任务仍在进行，请先继续查看原任务或等待完成后再提交。',409);}if([...jobs.values()].filter(j=>j.status==='running').length>=3)throw fail('SERVICE_BUSY','本机任务较多，请等待当前任务完成。',429);if(jobs.size>=40){const completed=[...jobs.values()].find(j=>j.status!=='running');if(completed)jobs.delete(completed.id);else throw fail('SERVICE_BUSY','请等待当前任务完成。',429);}
    consumeBudget(kind);
    const controller=new AbortController(),job={id:randomUUID(),owner:session.id,kind,requestKey,status:'running',progress:'任务已建立，准备读取资料。',createdAt:Date.now(),updatedAt:Date.now(),result:null,error:null,controller};jobs.set(job.id,job);
    const timeout=setTimeout(()=>controller.abort(),jobTimeoutMs);timeout.unref?.();
    Promise.resolve().then(()=>run({signal:controller.signal,onProgress:value=>{job.progress=progressMessage(value);job.updatedAt=Date.now();}})).then(result=>{job.result=result;job.status='done';job.progress=kind==='research'?'候选内容已整理完成，请先选择想去与想吃的内容。':'编排已完成，请核对结果。';job.updatedAt=Date.now();}).catch(error=>{job.error=safeError(error);job.status='failed';job.progress=job.error.message;job.updatedAt=Date.now();}).finally(()=>{clearTimeout(timeout);delete job.controller;});return job;
  }
  const server=http.createServer(async(req,res)=>{
    res.setHeader('X-Content-Type-Options','nosniff');res.setHeader('Referrer-Policy','no-referrer');res.setHeader('Cross-Origin-Resource-Policy','same-origin');res.setHeader('X-Frame-Options','DENY');
    try{
      limitRequest(req,res);
      expire();const actualPort=server.address()?.port||port;const host=String(req.headers.host||'');const allowed=new Set([`127.0.0.1:${actualPort}`,`localhost:${actualPort}`]);if(!allowed.has(host))throw fail('HOST_REJECTED','请求地址不受支持。',403);
      const origin=`http://${host}`,url=new URL(req.url,origin),native=url.pathname==='/api/native'||url.pathname.startsWith('/api/native/');
      if(url.origin!==origin)throw fail('HOST_REJECTED','请求地址不受支持。',403);
      if(remote&&!native)throw fail('NOT_FOUND','接口不存在。',404);
      if(native){
        if(nativeToken===undefined)throw fail('NOT_FOUND','接口不存在。',404);
        const auth=req.headers.authorization,match=typeof auth==='string'&&auth.match(/^Bearer ([a-f0-9]{64})$/);
        if(!match||!same(match[1],nativeToken)){res.setHeader('WWW-Authenticate','Bearer realm="MY PLAN native"');throw fail('NATIVE_AUTH_REQUIRED','手机联调连接未授权，请检查本机连接令牌。',401);}
        if(req.headers.origin!==undefined||(req.headers['sec-fetch-site']!==undefined&&req.headers['sec-fetch-site']!=='none'))throw fail('ORIGIN_REJECTED','手机接口仅接受已授权的原生客户端。',403);
      }else if(req.headers.origin&&req.headers.origin!==origin)throw fail('ORIGIN_REJECTED','请从本机旅行页面发起请求。',403);
      const apiPath=native?`/api/${url.pathname.slice('/api/native/'.length)}`:url.pathname;
      if(url.search&&apiPath!=='/api/session')throw fail('INVALID_ROUTE','请求参数不受支持。');
      if(req.method==='GET'&&['/','/index.html'].includes(url.pathname)){getSession(req,res,true);const html=await readFile(indexFile);res.writeHead(200,{'Content-Type':'text/html; charset=utf-8','Cache-Control':'no-store'});res.end(html);return;}
      if(!url.pathname.startsWith('/api/'))throw fail('NOT_FOUND','页面不存在。',404);
      if(req.headers['sec-fetch-site']==='cross-site')throw fail('ORIGIN_REJECTED','请从本机旅行页面发起请求。',403);
      if(req.method==='GET'&&apiPath==='/api/session'){const session=getSession(req,res,true,native);json(res,200,{csrfToken:session.csrf,capabilities:{research:!!config.aiKey,ai:!!config.aiKey,map:!!config.amapKey},connection:await connection(url.searchParams.has('refresh'))});return;}
      const session=getSession(req,res,false,native);if(!session)throw fail('SESSION_REQUIRED','页面连接已过期，请刷新后重试。',401);
      if(req.method==='POST'&&!native&&req.headers.origin!==origin)throw fail('ORIGIN_REJECTED','请从本机旅行页面发起请求。',403);
      if(req.method==='POST'&&!same(req.headers['x-csrf-token'],session.csrf))throw fail('CSRF_REJECTED','页面连接已更新，请刷新后重试。',403);
      if(req.method==='POST'&&apiPath==='/api/research'){
        const body=await bodyJson(req);if(typeof body.destination!=='string'||body.destination.trim().length<1||body.destination.trim().length>40||!Number.isInteger(body.count??3)||(body.count??3)<1||(body.count??3)>3)throw fail('INVALID_RESEARCH_INPUT','请填写城市名称，本轮最多读取3篇攻略。');
        const job=newJob('research',session,fingerprint({destination:body.destination.trim(),count:body.count??3}),async args=>{const result=await engine.research({destination:body.destination.trim(),count:body.count??3,...args});if(researches.size>=40&&!researches.has(result.id))researches.delete(researches.keys().next().value);researches.set(result.id,{owner:session.id,createdAt:Date.now(),result});return result;});json(res,202,{jobId:job.id});return;
      }
      if(req.method==='POST'&&apiPath==='/api/plan'){
        const body=await bodyJson(req);const record=researches.get(body.researchId);if(!record||record.owner!==session.id)throw fail('RESEARCH_EXPIRED','这份研究资料已过期，请重新检索后编排。',409);
        if(!Array.isArray(body.selected)||!body.selected.length||body.selected.length>12||!body.form||typeof body.form!=='object'||Array.isArray(body.form))throw fail('INVALID_PLAN_INPUT','请先选择内容并补充出行条件。');
        const known=new Set(record.result.candidates.map(c=>c.id)),seen=new Set();for(const wish of body.selected){if(!wish||typeof wish.id!=='string'||!known.has(wish.id)||seen.has(wish.id)||!['want','must'].includes(wish.priority))throw fail('INVALID_SELECTION','所选项目已变化，请回到清单重新确认。');seen.add(wish.id);}
        const job=newJob('plan',session,fingerprint({researchId:body.researchId,selected:[...body.selected].sort((a,b)=>a.id.localeCompare(b.id)),form:body.form}),args=>planner.plan({research:record.result,selected:body.selected,form:body.form,...args}));json(res,202,{jobId:job.id});return;
      }
      const match=apiPath.match(/^\/api\/jobs\/([a-f0-9-]{36})$/);if(req.method==='GET'&&match){const j=ownedJob(match[1],session);json(res,200,{id:j.id,kind:j.kind,status:j.status,progress:j.progress,updatedAt:j.updatedAt,...(j.status==='done'?{result:j.result}:{}),...(j.status==='failed'?{error:j.error}:{})});return;}
      const imageMatch=apiPath.match(/^\/api\/images\/([A-Za-z0-9_-]{10,100})$/);if(req.method==='GET'&&imageMatch){if(!media.has(imageMatch[1]))throw fail('IMAGE_NOT_FOUND','配图已过期或暂不可用。',404);const image=await media.get(imageMatch[1]);res.writeHead(200,{'Content-Type':image.mime,'Cache-Control':'private,max-age=900','Content-Security-Policy':"default-src 'none'; sandbox"});res.end(image.bytes);return;}
      throw fail('NOT_FOUND','接口不存在。',404);
    }catch(error){json(res,Number.isInteger(error.status)?error.status:400,{error:safeError(error)});}
  });
  server.headersTimeout=15000;server.requestTimeout=30000;server.keepAliveTimeout=5000;server.maxHeadersCount=64;server.maxRequestsPerSocket=100;
  return {server,close:()=>new Promise(resolve=>{for(const job of jobs.values())job.controller?.abort();server.close(resolve);}),getConnection:connection};
}

async function main(){const values={};for(let i=2;i<process.argv.length;i+=2)values[process.argv[i]]=process.argv[i+1];const mcpPath=values['--mcp-config'];if(!mcpPath)throw fail('CONFIG_REQUIRED','启动时需要指定本机检索连接配置文件。');const nativeToken=values['--native-token-file']===undefined?undefined:await loadNativeToken(values['--native-token-file']);const deployment=validateDeploymentOptions({deploymentOrigin:values['--deployment-origin'],listenHost:values['--listen-host']||'127.0.0.1',nativeToken,budgetFile:values['--budget-file'],requestLimitPerMinute:values['--request-limit-per-minute']===undefined?180:Number(values['--request-limit-per-minute']),researchLimitPerHour:values['--research-limit-per-hour']===undefined?6:Number(values['--research-limit-per-hour']),planLimitPerHour:values['--plan-limit-per-hour']===undefined?12:Number(values['--plan-limit-per-hour'])});const config=await loadConfig(values['--env-file']||path.join(ROOT,'server/travel-research/.env.local'));const mcpClient=await createReadOnlyMcpClient(mcpPath),media=createMediaStore(),engine=createResearchEngine({mcpClient,config,media}),maps=createAmap({apiKey:config.amapKey}),planner=createSelectionPlanner({config,legImpl:config.amapKey?maps.leg:undefined});const port=Number(values['--port']||8794);if(!Number.isInteger(port)||port<1024||port>65535)throw fail('INVALID_PORT','端口无效。');const app=createLiveServer({engine,planner,media,config,port,nativeToken,...deployment});app.server.listen(port,deployment.listenHost,()=>console.log(`MY PLAN travel service ready: ${deployment.deploymentOrigin||`http://127.0.0.1:${port}`}`));}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))main().catch(error=>{console.error(JSON.stringify(safeError(error)));process.exitCode=1;});
