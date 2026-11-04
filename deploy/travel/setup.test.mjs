import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,readFileSync,rmSync,statSync} from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {createPrivateDeployment,validateDomain} from './setup.mjs';
import {inspectArtifacts,inspectChromiumProfile,isUnconditionalAllow} from './validate.mjs';

test('deployment creates distinct access codes and leaves provider keys outside images',t=>{
  const root=mkdtempSync(path.join(os.tmpdir(),'myplan-deploy-'));t.after(()=>{
    assert.ok(path.resolve(root).startsWith(path.resolve(os.tmpdir())+path.sep+'myplan-deploy-'));
    rmSync(root,{recursive:true,force:true});
  });
  const directory=path.join(root,'private');
  const files=createPrivateDeployment({directory,domain:'travel.example.com',email:'owner@example.com'});
  assert.deepEqual(files.sort(),['.env.local','compose.env','mcp.json','native.json']);
  const mcp=JSON.parse(readFileSync(path.join(directory,'mcp.json'))),native=JSON.parse(readFileSync(path.join(directory,'native.json')));
  assert.match(mcp.token,/^[a-f0-9]{64}$/);assert.match(native.token,/^[a-f0-9]{64}$/);assert.notEqual(mcp.token,native.token);
  assert.equal(mcp.url,'http://127.0.0.1:18062');
  assert.match(readFileSync(path.join(directory,'.env.local'),'utf8'),/^DEEPSEEK_API_KEY=$/m);
  assert.doesNotMatch(readFileSync(path.join(directory,'compose.env'),'utf8'),new RegExp(native.token));
  assert.throws(()=>createPrivateDeployment({directory,domain:'travel.example.com',email:'owner@example.com'}),/refusing/);
  if(process.platform!=='win32')assert.equal(statSync(path.join(directory,'native.json')).mode&0o777,0o600);
});

test('deployment rejects localhost, IP, URL and environment injection without creating files',()=>{
  for(const value of ['localhost','travel.local','127.0.0.1','https://example.com','example.com:443','example.com\nATTACK=1','EXAMPLE.COM','user@example.com','-bad.example.com'])assert.throws(()=>validateDomain(value));
  assert.equal(validateDomain('travel.example.com'),'travel.example.com');
});

test('deployment templates retain private services, TLS ingress, browser sandbox and secret context boundaries',()=>{
  assert.deepEqual(inspectArtifacts(),[]);
});

test('zero host capabilities retain Chromium filesystem isolation without new unconditional mount or ptrace access',()=>{
  const profile=JSON.parse(readFileSync(new URL('./mcp/seccomp-chromium.json',import.meta.url),'utf8'));
  // Docker omits rules gated on capabilities absent from the container. This projection
  // deliberately retains kernel/argument/architecture conditions instead of treating them as grants.
  const zeroCaps=profile.syscalls.filter(rule=>!rule.includes?.caps?.length);
  const unconditional=new Set(zeroCaps.filter(isUnconditionalAllow).flatMap(rule=>rule.names));
  for(const name of ['chroot','clone','setns','unshare'])assert.ok(unconditional.has(name),name+' must work with cap_drop ALL');
  for(const name of ['mount','ptrace'])assert.ok(!unconditional.has(name),name+' must retain its upstream conditions');
  assert.ok(!zeroCaps.some(rule=>rule.names.includes('mount')));
  // Upstream separately permits ptrace on Linux >=4.8. Do not mistake cap_drop ALL
  // for denying every ptrace operation; its kernel permission checks still apply.
  assert.ok(zeroCaps.some(rule=>rule.names.includes('ptrace')&&rule.includes?.minKernel==='4.8'));
  const previous=structuredClone(profile);
  previous.syscalls[0].names=previous.syscalls[0].names.filter(name=>name!=='chroot');
  assert.ok(inspectChromiumProfile(previous).some(issue=>issue.endsWith(': chroot')));
  assert.deepEqual(inspectChromiumProfile(profile),[]);
});

test('sandbox validation rejects conditional-only namespace and chroot grants',()=>{
  const base={defaultAction:'SCMP_ACT_ERRNO',syscalls:[{names:['chroot','clone','setns','unshare'],action:'SCMP_ACT_ALLOW',args:[],includes:{},excludes:{}}]};
  assert.deepEqual(inspectChromiumProfile(base),[]);
  for(const constraint of [
    {includes:{caps:['CAP_SYS_CHROOT']}},
    {includes:{arches:['amd64']}},
    {includes:{minKernel:'4.8'}},
    {excludes:{caps:['CAP_SYS_ADMIN']}},
    {arches:['SCMP_ARCH_X86_64']},
    {args:[{index:0,value:0,op:'SCMP_CMP_EQ'}]}
  ]){
    const profile=structuredClone(base);Object.assign(profile.syscalls[0],constraint);
    assert.equal(inspectChromiumProfile(profile).length,4,JSON.stringify(constraint));
  }
  for(const name of ['mount','ptrace']){
    const profile=structuredClone(base);profile.syscalls[0].names.push(name);
    assert.match(inspectChromiumProfile(profile)[0],new RegExp('unconditionally allow '+name));
  }
});

