import {mkdirSync,existsSync,readdirSync,writeFileSync,chownSync} from 'node:fs';
import path from 'node:path';
import {randomBytes} from 'node:crypto';
import {fileURLToPath} from 'node:url';

export function validateDomain(value){
  if(typeof value!=='string'||value.length>253||value!==value.toLowerCase()||!value.includes('.')||
    !value.split('.').every(label=>/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(label))||
    /^\d+(?:\.\d+){3}$/.test(value)||value.endsWith('.localhost')||value.endsWith('.local')||value.endsWith('.test'))throw Error('A public DNS hostname is required.');
  return value;
}
export function createPrivateDeployment({directory,domain,email}){
  validateDomain(domain);
  if(typeof email!=='string'||email.length>254||! /^[A-Za-z0-9._%+-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\.[A-Za-z]{2,63}$/.test(email))throw Error('A certificate contact email is required.');
  if(existsSync(directory)&&readdirSync(directory).length)throw Error('Private configuration exists; refusing to replace access codes.');
  mkdirSync(directory,{recursive:true,mode:0o700});
  const files={
    'compose.env':`TRAVEL_DOMAIN=${domain}\nTLS_EMAIL=${email}\n`,
    'native.json':JSON.stringify({token:randomBytes(32).toString('hex')},null,2)+'\n',
    'mcp.json':JSON.stringify({url:'http://127.0.0.1:18062',token:randomBytes(32).toString('hex')},null,2)+'\n',
    '.env.local':'# Server-only provider settings. Never copy to Android BuildConfig.\nDEEPSEEK_API_KEY=\nDEEPSEEK_MODEL=deepseek-flash\nAMAP_WEB_SERVICE_KEY=\n',
  };
  for(const[name,value]of Object.entries(files))writeFileSync(path.join(directory,name),value,{flag:'wx',mode:0o600});
  // The images run as UID 1000. Setup as root makes only this dedicated directory readable by that UID.
  if(process.platform!=='win32'&&process.getuid?.()===0){
    for(const name of Object.keys(files))chownSync(path.join(directory,name),1000,1000);
    chownSync(directory,1000,1000);
  }
  return Object.keys(files);
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  try{
    const args=process.argv.slice(2),value=name=>args[args.indexOf(name)+1];
    if(!args.includes('--domain')||!args.includes('--email'))throw Error('Usage: node deploy/travel/setup.mjs --domain travel.your-domain.com --email you@your-domain.com');
    const directory=path.join(path.dirname(fileURLToPath(import.meta.url)),'private');
    createPrivateDeployment({directory,domain:value('--domain'),email:value('--email')});
    console.log('Private configuration created: '+directory);
    console.log('Fill .env.local on the server, then validate and start Compose. Access codes were not printed.');
  }catch(error){console.error(error.message);process.exitCode=1;}
}

