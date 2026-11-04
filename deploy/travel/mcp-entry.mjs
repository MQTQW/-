import {readFileSync} from 'node:fs';
import {spawn} from 'node:child_process';

try {
  const config=JSON.parse(readFileSync('/run/secrets/mcp.json','utf8'));
  if(config.url!=='http://127.0.0.1:18062'||!/^[a-f0-9]{64}$/.test(config.token))throw Error();
  const child=spawn('/app/xhs-mcp',['-headless=true','-port=127.0.0.1:18062'],{
    env:{...process.env,AUTH_TOKEN:config.token},stdio:'inherit',shell:false,
  });
  for(const signal of ['SIGTERM','SIGINT'])process.on(signal,()=>child.kill(signal));
  child.once('error',()=>{console.error('MCP process could not start.');process.exitCode=1;});
  child.once('exit',(code,signal)=>{process.exitCode=code??(signal?1:0);});
} catch {
  console.error('MCP private configuration is missing or invalid.');process.exitCode=1;
}

