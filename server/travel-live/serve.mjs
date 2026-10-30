import http from 'node:http';
import { readFile } from 'node:fs/promises';
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
const PUBLIC_ERROR=Symbol('public-travel-error');
const fail=(code,message,status=400)=>Object.assign(new Error(message),{code,status,[PUBLIC_ERROR]:true});
const same=(a,b)=>typeof a==='string'&&typeof b==='string'&&a.length===b.length&&timingSafeEqual(Buffer.from(a),Buffer.from(b));
const progressMessage=value=>String(typeof value==='string'?value:value?.message||'正在处理').slice(0,300);
const canonical=value=>Array.isArray(value)?value.map(canonical):value&&typeof value==='object'?Object.fromEntries(Object.keys(value).sort().map(key=>[key,canonical(value[key])])):value;
const fingerprint=value=>createHash('sha256').update(JSON.stringify(canonical(value))).digest('hex');
const safeError=error=>{const trusted=error?.[PUBLIC_ERROR]||error instanceof ResearchEngineError||error instanceof SelectionPlannerError;return{code:trusted&&/^[A-Z][A-Z0-9_]{1,63}$/.test(error?.code||'')?error.code:'REQUEST_FAILED',message:trusted&&typeof error?.message==='string'?error.message.slice(0,500):'本次操作未能完成，请重试。'};};
async function bodyJson(req){let size=0,parts=[];for await(const part of req){size+=part.length;if(size>24000)throw fail('BODY_TOO_LARGE','请求过大，请减少所选内容。',413);parts.push(part);}try{const value=JSON.parse(Buffer.concat(parts).toString('utf8'));if(!value||typeof value!=='object'||Array.isArray(value))throw Error();return value;}catch{throw fail('INVALID_JSON','请求内容格式不正确。');}}

export function createLiveServer({engine,planner,media,config={},indexFile=path.join(ROOT,'design/travel-live/index.html'),port=8794,jobTimeoutMs=480000}={}){
  const sessions=new Map(),jobs=new Map(),researches=new Map();let connectionCache=null,connectionAt=0,connectionPending=null;
  const expire=()=>{const cutoff=Date.now()-4*3600000;for(const[id,s]of sessions)if(s.seen<cutoff)sessions.delete(id);for(const[id,j]of jobs)if(j.updatedAt<cutoff)jobs.delete(id);for(const[id,r]of researches)if(r.createdAt<cutoff)researches.delete(id);};
  async function connection(force=false){if(!force&&connectionCache&&Date.now()-connectionAt<20000)return connectionCache;if(!connectionPending)connectionPending=Promise.resolve().then(()=>engine.checkConnection()).catch(()=>({loggedIn:false,message:'本机检索服务暂时不可用。'})).then(value=>{connectionCache={loggedIn:value?.loggedIn===true,message:typeof value?.message==='string'?value.message.slice(0,180):'连接状态未确认。'};connectionAt=Date.now();return connectionCache;}).finally(()=>{connectionPending=null;});return connectionPending;}
  function getSession(req,res,create=false){const cookies=Object.fromEntries(String(req.headers.cookie||'').split(';').map(v=>v.trim().split('=')).filter(v=>v.length===2));let session=sessions.get(cookies[COOKIE]);if(!session&&create){const id=randomBytes(32).toString('hex');session={id,csrf:randomBytes(32).toString('hex'),seen:Date.now()};sessions.set(id,session);res.setHeader('Set-Cookie',`${COOKIE}=${id}; Path=/; HttpOnly; SameSite=Strict; Max-Age=14400`);}if(session)session.seen=Date.now();return session;}
  function json(res,status,data){res.writeHead(status,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'});res.end(JSON.stringify(data));}
  function ownedJob(id,session){const job=jobs.get(id);if(!job||job.owner!==session.id)throw fail('JOB_NOT_FOUND','这项任务不存在或已过期。',404);return job;}
  function newJob(kind,session,requestKey,run){const active=[...jobs.values()].find(j=>j.owner===session.id&&j.kind===kind&&j.status==='running');if(active){if(active.requestKey===requestKey)return active;throw fail('ACTIVE_JOB_CONFLICT','已有不同条件的任务仍在进行，请先继续查看原任务或等待完成后再提交。',409);}if([...jobs.values()].filter(j=>j.status==='running').length>=3)throw fail('SERVICE_BUSY','本机任务较多，请等待当前任务完成。',429);if(jobs.size>=40){const completed=[...jobs.values()].find(j=>j.status!=='running');if(completed)jobs.delete(completed.id);else throw fail('SERVICE_BUSY','请等待当前任务完成。',429);}
    const controller=new AbortController(),job={id:randomUUID(),owner:session.id,kind,requestKey,status:'running',progress:'任务已建立，准备读取资料。',createdAt:Date.now(),updatedAt:Date.now(),result:null,error:null,controller};jobs.set(job.id,job);
    const timeout=setTimeout(()=>controller.abort(),jobTimeoutMs);timeout.unref?.();
    Promise.resolve().then(()=>run({signal:controller.signal,onProgress:value=>{job.progress=progressMessage(value);job.updatedAt=Date.now();}})).then(result=>{job.result=result;job.status='done';job.progress=kind==='research'?'候选内容已整理完成，请先选择想去与想吃的内容。':'编排已完成，请核对结果。';job.updatedAt=Date.now();}).catch(error=>{job.error=safeError(error);job.status='failed';job.progress=job.error.message;job.updatedAt=Date.now();}).finally(()=>{clearTimeout(timeout);delete job.controller;});return job;
  }
  const server=http.createServer(async(req,res)=>{
    res.setHeader('X-Content-Type-Options','nosniff');res.setHeader('Referrer-Policy','no-referrer');res.setHeader('Cross-Origin-Resource-Policy','same-origin');res.setHeader('X-Frame-Options','DENY');
    try{
      expire();const actualPort=server.address()?.port||port;const host=String(req.headers.host||'');const allowed=new Set([`127.0.0.1:${actualPort}`,`localhost:${actualPort}`]);if(!allowed.has(host))throw fail('HOST_REJECTED','请求地址不受支持。',403);
      const origin=`http://${host}`;if(req.headers.origin&&req.headers.origin!==origin)throw fail('ORIGIN_REJECTED','请从本机旅行页面发起请求。',403);
      const url=new URL(req.url,origin);if(url.search&&url.pathname!=='/api/session')throw fail('INVALID_ROUTE','请求参数不受支持。');
      if(req.method==='GET'&&['/','/index.html'].includes(url.pathname)){getSession(req,res,true);const html=await readFile(indexFile);res.writeHead(200,{'Content-Type':'text/html; charset=utf-8','Cache-Control':'no-store'});res.end(html);return;}
      if(!url.pathname.startsWith('/api/'))throw fail('NOT_FOUND','页面不存在。',404);
      if(req.headers['sec-fetch-site']==='cross-site')throw fail('ORIGIN_REJECTED','请从本机旅行页面发起请求。',403);
      if(req.method==='GET'&&url.pathname==='/api/session'){const session=getSession(req,res,true);json(res,200,{csrfToken:session.csrf,capabilities:{research:!!config.aiKey,ai:!!config.aiKey,map:!!config.amapKey},connection:await connection(url.searchParams.has('refresh'))});return;}
      const session=getSession(req,res);if(!session)throw fail('SESSION_REQUIRED','页面连接已过期，请刷新后重试。',401);
      if(req.method==='POST'&&req.headers.origin!==origin)throw fail('ORIGIN_REJECTED','请从本机旅行页面发起请求。',403);
      if(req.method==='POST'&&!same(req.headers['x-csrf-token'],session.csrf))throw fail('CSRF_REJECTED','页面连接已更新，请刷新后重试。',403);
      if(req.method==='POST'&&url.pathname==='/api/research'){
        const body=await bodyJson(req);if(typeof body.destination!=='string'||body.destination.trim().length<1||body.destination.trim().length>40||!Number.isInteger(body.count??3)||(body.count??3)<1||(body.count??3)>3)throw fail('INVALID_RESEARCH_INPUT','请填写城市名称，本轮最多读取3篇攻略。');
        const job=newJob('research',session,fingerprint({destination:body.destination.trim(),count:body.count??3}),async args=>{const result=await engine.research({destination:body.destination.trim(),count:body.count??3,...args});researches.set(result.id,{owner:session.id,createdAt:Date.now(),result});return result;});json(res,202,{jobId:job.id});return;
      }
      if(req.method==='POST'&&url.pathname==='/api/plan'){
        const body=await bodyJson(req);const record=researches.get(body.researchId);if(!record||record.owner!==session.id)throw fail('RESEARCH_EXPIRED','这份研究资料已过期，请重新检索后编排。',409);
        if(!Array.isArray(body.selected)||!body.selected.length||body.selected.length>12||!body.form||typeof body.form!=='object'||Array.isArray(body.form))throw fail('INVALID_PLAN_INPUT','请先选择内容并补充出行条件。');
        const known=new Set(record.result.candidates.map(c=>c.id)),seen=new Set();for(const wish of body.selected){if(!wish||typeof wish.id!=='string'||!known.has(wish.id)||seen.has(wish.id)||!['want','must'].includes(wish.priority))throw fail('INVALID_SELECTION','所选项目已变化，请回到清单重新确认。');seen.add(wish.id);}
        const job=newJob('plan',session,fingerprint({researchId:body.researchId,selected:[...body.selected].sort((a,b)=>a.id.localeCompare(b.id)),form:body.form}),args=>planner.plan({research:record.result,selected:body.selected,form:body.form,...args}));json(res,202,{jobId:job.id});return;
      }
      const match=url.pathname.match(/^\/api\/jobs\/([a-f0-9-]{36})$/);if(req.method==='GET'&&match){const j=ownedJob(match[1],session);json(res,200,{id:j.id,kind:j.kind,status:j.status,progress:j.progress,updatedAt:j.updatedAt,...(j.status==='done'?{result:j.result}:{}),...(j.status==='failed'?{error:j.error}:{})});return;}
      const imageMatch=url.pathname.match(/^\/api\/images\/([A-Za-z0-9_-]{10,100})$/);if(req.method==='GET'&&imageMatch){if(!media.has(imageMatch[1]))throw fail('IMAGE_NOT_FOUND','配图已过期或暂不可用。',404);const image=await media.get(imageMatch[1]);res.writeHead(200,{'Content-Type':image.mime,'Cache-Control':'private,max-age=900','Content-Security-Policy':"default-src 'none'; sandbox"});res.end(image.bytes);return;}
      throw fail('NOT_FOUND','接口不存在。',404);
    }catch(error){json(res,Number.isInteger(error.status)?error.status:400,{error:safeError(error)});}
  });
  return {server,close:()=>new Promise(resolve=>{for(const job of jobs.values())job.controller?.abort();server.close(resolve);}),getConnection:connection};
}

async function main(){const values={};for(let i=2;i<process.argv.length;i+=2)values[process.argv[i]]=process.argv[i+1];const mcpPath=values['--mcp-config'];if(!mcpPath)throw fail('CONFIG_REQUIRED','启动时需要指定本机检索连接配置文件。');const config=await loadConfig(values['--env-file']||path.join(ROOT,'server/travel-research/.env.local'));const mcpClient=await createReadOnlyMcpClient(mcpPath),media=createMediaStore(),engine=createResearchEngine({mcpClient,config,media}),maps=createAmap({apiKey:config.amapKey}),planner=createSelectionPlanner({config,legImpl:config.amapKey?maps.leg:undefined});const port=Number(values['--port']||8794);if(!Number.isInteger(port)||port<1024||port>65535)throw fail('INVALID_PORT','端口无效。');const app=createLiveServer({engine,planner,media,config,port});app.server.listen(port,'127.0.0.1',()=>console.log(`MY PLAN live research ready: http://127.0.0.1:${port}`));}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))main().catch(error=>{console.error(JSON.stringify(safeError(error)));process.exitCode=1;});
