/** Set canonical metadata only after the actual Pages/custom origin is known. */
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=fileURLToPath(new URL('../public/',import.meta.url));
const urlFlag=process.argv.indexOf('--url');
if(urlFlag===-1||!process.argv[urlFlag+1])throw Error('Use --url https://verified-host.example');
const parsed=new URL(process.argv[urlFlag+1]);
if(parsed.protocol!=='https:'||parsed.username||parsed.password||parsed.port||parsed.search||parsed.hash||parsed.pathname!=='/')throw Error('Use a plain HTTPS origin without path, query, credentials or port');
const origin=parsed.origin;
const escape=value=>value.replaceAll('&','&amp;').replaceAll('"','&quot;').replaceAll('<','&lt;');
const pages=[['index.html','/','映汐，个人本地播放器。打开自己的片库，在影片之外遇见废土世界。'],['world/index.html','/world/','核爆长潮之后的纪元、聚居、生命谱系与放映站住民。'],['support/index.html','/support/','映汐安装、媒体来源连接、播放操作与问题反馈。'],['privacy/index.html','/privacy/','浏览映汐官网时的信息处理说明。']];
for(const [name,route,description] of pages){
 const file=path.join(root,name);let html=fs.readFileSync(file,'utf8');
 html=html.replace(/<link\b[^>]*rel=["']canonical["'][^>]*>/gi,'').replace(/<meta\b[^>]*property=["']og:[^"']+["'][^>]*>/gi,'');
 const title=html.match(/<title>(.*?)<\/title>/s)?.[1]||'映汐放映站';
 const tags=`<link rel="canonical" href="${escape(origin+route)}"><meta property="og:type" content="website"><meta property="og:locale" content="zh_CN"><meta property="og:site_name" content="映汐放映站"><meta property="og:title" content="${escape(title)}"><meta property="og:description" content="${escape(description)}"><meta property="og:url" content="${escape(origin+route)}">`;
 if(!html.includes('</head>'))throw Error(`Missing head: ${name}`);html=html.replace('</head>',tags+'</head>');fs.writeFileSync(file,html);
}
fs.writeFileSync(path.join(root,'robots.txt'),`User-agent: *\nAllow: /\nSitemap: ${origin}/sitemap.xml\n`);
fs.writeFileSync(path.join(root,'sitemap.xml'),`<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${pages.map(([,route])=>`  <url><loc>${origin+route}</loc></url>`).join('\n')}\n</urlset>\n`);
console.log(`Canonical origin set: ${origin}`);
