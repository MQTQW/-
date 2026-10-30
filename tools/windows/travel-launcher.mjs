// Portable Windows launcher: private state is generated under LOCALAPPDATA, never in Git.
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import net from 'node:net';
import {spawn,execFileSync} from 'node:child_process';
import {randomBytes} from 'node:crypto';
import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
const runtimeCandidates=[path.join(root,'runtime'),path.resolve(root,'../runtime')];
const runtime=runtimeCandidates.find(p=>fs.existsSync(p))||runtimeCandidates[0];
const data=path.join(process.env.LOCALAPPDATA||path.join(os.homedir(),'AppData/Local'),'MyPlanTravel');
fs.mkdirSync(data,{recursive:true});
const configPath=path.join(data,'private-service.json');
const cookiePath=path.join(data,'private-cookies.json');
const exe=process.env.MY_PLAN_MCP_EXE||path.join(runtime,'windows/xiaohongshu-mcp-standard.exe');
const chrome=[process.env.MY_PLAN_CHROME,path.join(process.env.PROGRAMFILES||'C:/Program Files','Google/Chrome/Application/chrome.exe'),path.join(process.env['PROGRAMFILES(X86)']||'C:/Program Files (x86)','Google/Chrome/Application/chrome.exe'),path.join(process.env.LOCALAPPDATA||'','Google/Chrome/Application/chrome.exe')].filter(Boolean).find(p=>fs.existsSync(p));
const config=fs.existsSync(configPath)?JSON.parse(fs.readFileSync(configPath,'utf8')):{url:'http://127.0.0.1:18062',token:randomBytes(32).toString('hex')};
if(new URL(config.url).hostname!=='127.0.0.1'||typeof config.token!=='string'||config.token.length<32)throw Error('Invalid machine-local MCP config');
if(!fs.existsSync(configPath))fs.writeFileSync(configPath,JSON.stringify(config,null,2));
const listening=port=>new Promise(resolve=>{const socket=net.connect({host:'127.0.0.1',port},()=>{socket.destroy();resolve(true);});socket.setTimeout(700,()=>{socket.destroy();resolve(false);});socket.once('error',()=>resolve(false));});
function launch(file,args,cwd,env,label){const out=fs.openSync(path.join(data,label+'.out.log'),'a'),error=fs.openSync(path.join(data,label+'.error.log'),'a');const child=spawn(file,args,{cwd,env,windowsHide:true,detached:true,stdio:['ignore',out,error]});child.unref();fs.writeFileSync(path.join(data,label+'.pid'),String(child.pid));return child.pid;}
async function ensure(){
  if(!await listening(18062)){if(!fs.existsSync(exe))throw Error('MCP tool missing: extract the Windows runtime package beside project, or set MY_PLAN_MCP_EXE.');if(!chrome)throw Error('Chrome is required. Install Chrome or set MY_PLAN_CHROME to its executable.');launch(exe,['-headless=true','-port=127.0.0.1:18062'],data,{...process.env,AUTH_TOKEN:config.token,COOKIES_PATH:cookiePath,POC_STANDARD_CHROME_BIN:chrome},'mcp');}
  if(!await listening(8794)){const envPath=path.join(root,'server/travel-research/.env.local');if(!fs.existsSync(envPath)){console.log('服务密钥尚未导入：请按交接文档配置 server/travel-research/.env.local。');console.log('页面可启动，但真实AI调用需要该配置。');}launch(process.execPath,['server/travel-live/serve.mjs','--mcp-config',configPath],root,process.env,'web');}
  console.log('旅行联调页面：http://127.0.0.1:8794');console.log('私有登录配置仅保存在：'+data);
}
async function stopOwned(label,needle){const pidFile=path.join(data,label+'.pid');if(!fs.existsSync(pidFile))return;const pid=Number(fs.readFileSync(pidFile,'utf8'));if(!Number.isInteger(pid)||pid<1)throw Error('Invalid PID file');const script=`$p=Get-CimInstance Win32_Process -Filter 'ProcessId=${pid}';if($null -eq $p){exit 0};if($p.CommandLine -notmatch '${needle}'){Write-Error 'Process owner mismatch';exit 2};Stop-Process -Id ${pid} -ErrorAction Stop`;execFileSync('powershell.exe',['-NoProfile','-Command',script],{windowsHide:true,stdio:'pipe'});}
async function qr(){await ensure();for(let i=0;i<20&&!await listening(18062);i++)await new Promise(resolve=>setTimeout(resolve,500));const res=await fetch(new URL('/api/v1/login/qrcode',config.url),{headers:{Authorization:'Bearer '+config.token},signal:AbortSignal.timeout(45000)});if(!res.ok)throw Error('MCP login endpoint unavailable; check owned service logs.');const value=await res.json(),d=value.data||value;if(d.is_logged_in||d.loggedIn){console.log('小红书已登录，无需重复扫码。');return;}const image=d.img||d.image||d.qrcode;if(typeof image!=='string'||!/^data:image\/(?:png|jpeg);base64,[A-Za-z0-9+/=\r\n]+$/.test(image))throw Error('Login QR response unavailable; retry login script.');const html='<!doctype html><meta charset="utf-8"><title>MY PLAN 小红书登录</title><h1>请用你本人的小红书扫码并确认</h1><p>二维码过期后重新运行扫码脚本。成功后回到旅行页面点击“重新检测”。不要公开分享此页面。</p><img alt="登录二维码" style="max-width:420px" src="'+image+'">';const file=path.join(data,'login-qr.html');fs.writeFileSync(file,html);spawn('explorer.exe',[file],{windowsHide:true,detached:true,stdio:'ignore'}).unref();console.log('二维码页面：'+file);}
try{const action=process.argv[2]||'start';if(action==='start')await ensure();else if(action==='login')await qr();else if(action==='stop'){await stopOwned('web','server[/\\\\]travel-live[/\\\\]serve\\.mjs');await stopOwned('mcp','xiaohongshu-mcp-standard\\.exe');console.log('已停止由此启动器创建并核对归属的服务。');}else if(action==='status')console.log(JSON.stringify({web:await listening(8794),mcp:await listening(18062),privateDirectory:data}));else throw Error('Unknown action');}catch(error){console.error('启动器：'+error.message);process.exitCode=1;}
