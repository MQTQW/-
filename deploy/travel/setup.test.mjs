import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,readFileSync,rmSync,statSync} from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {createPrivateDeployment,validateDomain} from './setup.mjs';
import {inspectArtifacts} from './validate.mjs';

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

