import { readFile } from 'node:fs/promises';
import { limitedJson } from '../travel-research/config.mjs';
const ALLOWED=new Set(['check_login_status','search_feeds','get_feed_detail']);
export async function createReadOnlyMcpClient(configPath,{fetchImpl=fetch}={}){
  const config=JSON.parse(await readFile(configPath,'utf8'));
  const endpoint=new URL(config.url);
  if(endpoint.protocol!=='http:'||!['127.0.0.1','[::1]'].includes(endpoint.hostname)||endpoint.username||endpoint.password||endpoint.pathname!=='/'||endpoint.search||endpoint.hash||typeof config.token!=='string'||config.token.length<32)throw Object.assign(new Error('本机检索连接配置无效。'),{code:'INVALID_MCP_CONFIG'});
  let sequence=0;
  return {async call(name,args={}){
    if(!ALLOWED.has(name))throw Object.assign(new Error('本接口仅允许读取公开攻略。'),{code:'MCP_TOOL_NOT_ALLOWED'});
    let rpc;
    try{rpc=await limitedJson(await fetchImpl(new URL('/mcp',endpoint),{method:'POST',redirect:'error',signal:AbortSignal.timeout(name==='check_login_status'?30000:120000),headers:{Authorization:`Bearer ${config.token}`,Accept:'application/json, text/event-stream','Content-Type':'application/json','MCP-Protocol-Version':'2025-06-18'},body:JSON.stringify({jsonrpc:'2.0',id:++sequence,method:'tools/call',params:{name,arguments:args}})}),2*1024*1024);}catch{throw Object.assign(new Error('本机小红书连接未运行或请求超时。'),{code:'MCP_UNAVAILABLE'});}
    if(rpc.error||rpc.result?.isError)throw Object.assign(new Error('小红书资料读取未完成。'),{code:'MCP_READ_FAILED'});
    const parts=(rpc.result?.content||[]).filter(p=>p.type==='text'&&typeof p.text==='string');
    for(const part of parts){try{const data=JSON.parse(part.text);if(data&&typeof data==='object')return data;}catch{}}
    if(name==='check_login_status'&&parts.length)return{text:parts.map(p=>p.text).join('\n').slice(0,4000)};
    throw Object.assign(new Error('小红书返回的资料结构暂时无法读取。'),{code:'INVALID_MCP_RESULT'});
  }};
}
