import {readFileSync,writeFileSync,renameSync} from 'node:fs';
import {limitedJson} from '../../server/travel-research/config.mjs';

// Run inside the API container. It shares loopback with MCP; no admin port is exposed.
try {
  const config=JSON.parse(readFileSync('/run/secrets/mcp.json','utf8'));
  if(config.url!=='http://127.0.0.1:18062'||!/^[a-f0-9]{64}$/.test(config.token))throw Error();
  const action=process.argv[2];
  if(!['qr','status'].includes(action))throw Error();
  const response=await fetch(config.url+(action==='qr'?'/api/v1/login/qrcode':'/api/v1/login/status'),{
    headers:{Authorization:'Bearer '+config.token},redirect:'error',signal:AbortSignal.timeout(45000),
  });
  const result=await limitedJson(response,2*1024*1024),data=result.data||result;
  if(action==='status'){
    console.log(JSON.stringify({loggedIn:data.is_logged_in===true||data.loggedIn===true}));
  }else if(data.is_logged_in===true||data.loggedIn===true){
    console.log('Already logged in.');
  }else{
    const image=data.img||data.image||data.qrcode;
    const match=typeof image==='string'&&image.match(/^data:image\/(png|jpeg);base64,([A-Za-z0-9+/=\r\n]+)$/);
    if(!match)throw Error();
    const bytes=Buffer.from(match[2],'base64');
    if(bytes.length>1024*1024||bytes.length<8||!(match[1]==='png'?bytes.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10])):bytes[0]===255&&bytes[1]===216))throw Error();
    const file='/var/lib/myplan/login-qr.'+(match[1]==='png'?'png':'jpg');
    writeFileSync(file+'.tmp',bytes,{mode:0o600});renameSync(file+'.tmp',file);
    console.log('QR image saved inside API container: '+file);
    console.log('Copy it over your authenticated SSH connection; never publish it. Scan and confirm with your own Xiaohongshu app.');
  }
}catch{
  console.error('Login helper failed. Check the private MCP configuration, service status, or regenerate an expired QR. No access code was printed.');
  process.exitCode=1;
}
