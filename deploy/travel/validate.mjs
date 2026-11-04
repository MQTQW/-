import {readFileSync,existsSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
import {validateDomain} from './setup.mjs';

const directory=path.dirname(fileURLToPath(import.meta.url));
export function inspectArtifacts(){
  const read=name=>readFileSync(path.join(directory,name),'utf8'),issues=[];
  const compose=read('compose.yaml'),caddy=read('Caddyfile');
  const published=[...compose.matchAll(/^\s+- "(\d+:\d+)"$/gm)].map(m=>m[1]).sort();
  if(JSON.stringify(published)!==JSON.stringify(['443:443','80:80']))issues.push('Only Caddy HTTP/HTTPS ports may be published.');
  if(!compose.includes('network_mode: service:api')||!compose.includes('/var/lib/myplan/usage-budget.json'))issues.push('Private loopback MCP or persistent budget is missing.');
  if(/privileged:\s*true|SYS_ADMIN|seccomp=unconfined/.test(compose))issues.push('Privileged browser fallback is forbidden.');
  if(!caddy.includes('@native path /api/native/*')||!caddy.includes('header_up Host 127.0.0.1:8794')||!caddy.includes('respond 404'))issues.push('Native-only reverse proxy or fixed internal Host is missing.');
  const profile=JSON.parse(read('mcp/seccomp-chromium.json'));
  if(profile.defaultAction!=='SCMP_ACT_ERRNO')issues.push('Chromium seccomp profile must default to deny.');
  for(const name of ['clone','setns','unshare'])if(!profile.syscalls.some(rule=>rule.action==='SCMP_ACT_ALLOW'&&rule.names.includes(name)))issues.push('Chromium namespace permission missing: '+name);
  const rootIgnore=readFileSync(path.join(directory,'../../.dockerignore'),'utf8');
  for(const marker of ['private','env','state'])if(!rootIgnore.includes(marker))issues.push('Docker context must exclude '+marker+'.');
  for(const name of ['Api.Dockerfile','Mcp.Dockerfile'])if(/FROM\s+\S+:latest\b/.test(read(name)))issues.push('Unpinned latest image in '+name);
  if(!read('Mcp.Dockerfile').includes('USER 1000:1000')||!read('Mcp.Dockerfile').includes('POC_STANDARD_CHROME_BIN=/usr/bin/chromium'))issues.push('Non-root standard Chrome mode is required.');
  return issues;
}
function checkPrivate(){
  const base=path.join(directory,'private');
  for(const name of ['compose.env','mcp.json','native.json','.env.local'])if(!existsSync(path.join(base,name)))throw Error('Private setup is incomplete. Run setup.mjs first.');
  const env=Object.fromEntries(readFileSync(path.join(base,'compose.env'),'utf8').trim().split(/\r?\n/).map(line=>line.split('=')));
  validateDomain(env.TRAVEL_DOMAIN);
  const mcp=JSON.parse(readFileSync(path.join(base,'mcp.json'),'utf8')),native=JSON.parse(readFileSync(path.join(base,'native.json'),'utf8'));
  if(!/^[a-f0-9]{64}$/.test(mcp.token)||!/^[a-f0-9]{64}$/.test(native.token)||mcp.token===native.token||mcp.url!=='http://127.0.0.1:18062')throw Error('Private connection files are invalid.');
  if(!/^DEEPSEEK_API_KEY=.{12,}$/m.test(readFileSync(path.join(base,'.env.local'),'utf8')))throw Error('Configure the server AI key before starting. No key was printed.');
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  try{
    const issues=inspectArtifacts();if(issues.length)throw Error(issues.join('\n'));
    if(process.argv.includes('--static'))console.log('Static deployment checks passed. Docker build/runtime were not checked.');
    else{
      checkPrivate();
      const result=spawnSync('docker',['compose','--env-file','private/compose.env','-f','compose.yaml','config','--quiet'],{cwd:directory,stdio:'pipe',windowsHide:true});
      if(result.error||result.status!==0)throw Error('Docker Compose validation did not pass or Docker is unavailable. Run config --quiet on the target Linux server.');
      console.log('Private configuration and Docker Compose syntax passed. Container startup, TLS and phone access still require deployment.');
    }
  }catch(error){console.error(error.message);process.exitCode=1;}
}

