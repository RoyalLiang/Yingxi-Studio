import http from 'node:http';
import {createReadStream,existsSync,statSync} from 'node:fs';
import {resolve,extname,sep} from 'node:path';
import {fileURLToPath} from 'node:url';
const root=resolve(process.argv[2]||fileURLToPath(new URL('../public/',import.meta.url)));
const port=Number(process.env.PORT||48173);
const types={'.html':'text/html; charset=utf-8','.css':'text/css; charset=utf-8','.js':'text/javascript; charset=utf-8','.json':'application/json; charset=utf-8','.webp':'image/webp','.webm':'video/webm','.woff2':'font/woff2','.txt':'text/plain; charset=utf-8','.xml':'application/xml; charset=utf-8'};
http.createServer((req,res)=>{
  let path;try{path=resolve(root,'.'+decodeURIComponent(new URL(req.url,'http://localhost').pathname));}catch{res.writeHead(400);res.end();return;}
  if(path!==root&&!path.startsWith(root+sep)){res.writeHead(403);res.end();return;}
  if(existsSync(path)&&statSync(path).isDirectory())path=resolve(path,'index.html');
  let status=200;if(!existsSync(path)){status=404;path=resolve(root,'404.html');}
  res.writeHead(status,{'Content-Type':types[extname(path)]||'application/octet-stream','Cache-Control':'no-store'});
  createReadStream(path).pipe(res);
}).listen(port,'127.0.0.1',()=>console.log(`Local: http://127.0.0.1:${port}`));
