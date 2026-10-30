// Restores only the explicitly supported private files from an authenticated local bundle.
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..');
try{
 const bundleFile=process.argv[2]||path.resolve(root,'../MY_PLAN-private-configs.enc');
 const passwordFile=process.argv[3]||path.resolve(root,'../私有配置解密密码.txt');
 const password=fs.readFileSync(passwordFile,'utf8').trim(),box=JSON.parse(fs.readFileSync(bundleFile,'utf8'));
 if(box.version!==1||box.algorithm!=='aes-256-gcm')throw Error('Unsupported bundle');
 const key=crypto.scryptSync(password,Buffer.from(box.salt,'base64'),32);
 const decipher=crypto.createDecipheriv('aes-256-gcm',key,Buffer.from(box.iv,'base64'));decipher.setAuthTag(Buffer.from(box.tag,'base64'));
 const data=JSON.parse(Buffer.concat([decipher.update(Buffer.from(box.data,'base64')),decipher.final()]).toString('utf8'));
 const backup=path.join(process.env.LOCALAPPDATA||path.join(os.homedir(),'AppData/Local'),'MyPlanTravel','private-backups',String(Date.now()));
 const targets={travelEnv:path.join(root,'server/travel-research/.env.local'),androidKeys:path.join(root,'local.properties'),debugKeystore:path.join(os.homedir(),'.android/debug.keystore')};
 if(!Array.isArray(data.files)||data.files.some(x=>!Object.hasOwn(targets,x.kind)||typeof x.data!=='string'))throw Error('Invalid payload');
 for(const entry of data.files){const target=targets[entry.kind];if(fs.existsSync(target)){fs.mkdirSync(backup,{recursive:true});fs.copyFileSync(target,path.join(backup,entry.kind));}fs.mkdirSync(path.dirname(target),{recursive:true});let bytes=Buffer.from(entry.data,'base64');if(entry.kind==='androidKeys'){let old=fs.existsSync(target)?fs.readFileSync(target,'utf8'):'';const lines=bytes.toString('utf8').split(/\r?\n/).filter(x=>/^(volc\.api\.key|deepseek\.key)=/.test(x));for(const line of lines){const name=line.slice(0,line.indexOf('='));old=old.split(/\r?\n/).filter(x=>!x.startsWith(name+'=')).join('\n');old+='\n'+line;}bytes=Buffer.from(old+'\n');}fs.writeFileSync(target,bytes,{mode:0o600});console.log('已恢复：'+target);}
 console.log('未复制旧SDK路径、Cookie或GitHub凭据。请按交接文档配置新机SDK并扫码登录。');
}catch{console.error('私有配置恢复失败：请检查加密包、密码文件和路径。未显示任何密钥。');process.exitCode=1;}
