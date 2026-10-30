import http from 'node:http';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
const index=fileURLToPath(new URL('./index.html',import.meta.url));
http.createServer(async(req,res)=>{if(req.method!=='GET'||!['/','/index.html'].includes((req.url||'').split('?')[0])){res.writeHead(404);res.end();return;}try{const data=await readFile(index);res.writeHead(200,{'Content-Type':'text/html; charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});res.end(data);}catch{res.writeHead(503);res.end('Build the prototype first.');}}).listen(8791,'127.0.0.1',()=>console.log('Travel result prototype ready at http://127.0.0.1:8791'));
