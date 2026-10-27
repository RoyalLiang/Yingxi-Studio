import {CONSENT_VERSION,sanitizeReport,REPOSITORY} from '../../public/report/schema.js';
const API='https://api.github.com/repos/RoyalLiang/Yingxi-Studio/issues';
const kinds={stutter:'卡顿',black:'无画面',audio:'声音',subtitle:'字幕',other:'其他'};
const headers={'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff'};
function json(status,body){return new Response(JSON.stringify(body),{status,headers});}
function configured(env){return !!(env.REPORT_GITHUB_TOKEN&&env.REPORT_TURNSTILE_SECRET&&env.REPORT_TURNSTILE_SITE_KEY);}
async function readBody(request) {
  if(!request.body)throw Error('empty');
  const reader=request.body.getReader();let bytes=0;const chunks=[];
  const deadline=setTimeout(()=>reader.cancel(),8000);
  try{while(true){const {done,value}=await reader.read();if(done)break;bytes+=value.byteLength;if(bytes>32768){await reader.cancel();throw Error('large');}chunks.push(value);}}
  finally{clearTimeout(deadline);reader.releaseLock();}
  const data=new Uint8Array(bytes);let offset=0;for(const chunk of chunks){data.set(chunk,offset);offset+=chunk.length;}
  return JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(data));
}
export async function onRequestGet({env}){return json(200,{available:configured(env),siteKey:env.REPORT_TURNSTILE_SITE_KEY||null,consentVersion:CONSENT_VERSION});}
export async function onRequestPost({request,env},fetcher=fetch) {
  const origin=new URL(request.url).origin;
  if(request.headers.get('Origin')!==origin)return json(403,{error:'请从上报页提交。'});
  if(!request.headers.get('Content-Type')?.toLowerCase().startsWith('application/json'))return json(415,{error:'只接收播放检修 JSON。'});
  if(!configured(env))return json(503,{error:'上报通道正在准备中；请先复制并保留报告。'});
  let body,data;
  try {
    body=await readBody(request);
    if(body.consent!==true||body.consentVersion!==CONSENT_VERSION)throw Error('consent');
    if(!/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(body.id)||!Object.hasOwn(kinds,body.kind))throw Error('id');
    if(typeof body.challenge!=='string'||body.challenge.length<1||body.challenge.length>2048)throw Error('challenge');
    data=sanitizeReport(body.report).data;
  } catch{return json(400,{error:'报告、知情同意或校验内容无效，报告须小于 32 KiB。'});}
  let verification;
  try {
    const form=new URLSearchParams({secret:env.REPORT_TURNSTILE_SECRET,response:body.challenge});
    const ip=request.headers.get('CF-Connecting-IP');if(ip)form.set('remoteip',ip);
    const response=await fetcher('https://challenges.cloudflare.com/turnstile/v0/siteverify',{method:'POST',body:form,signal:AbortSignal.timeout(8000)});
    if(!response.ok)throw Error('verify');verification=await response.json();
  } catch{return json(503,{error:'人机校验暂时不可用，请稍后重试。'});}
  if(!verification.success||verification.hostname!==new URL(request.url).hostname||verification.action!=='playback-report'||verification.cdata!==body.id)return json(403,{error:'人机校验未通过，请刷新校验后重试。'});
  const marker=`yingxi-playback-report:${body.id}`;
  const labels={version:'应用版本',versionCode:'安装序号',manufacturer:'厂商',model:'设备型号',android:'Android 版本',sdk:'系统 API',source:'信源类型',state:'播放状态',decoder:'视频解码器',audioDecoder:'音频解码器',videoCodecs:'视频编码',videoMime:'视频格式',encodedWidth:'编码宽度',encodedHeight:'编码高度',videoWidth:'显示宽度',videoHeight:'显示高度',encodedFrameRate:'帧率',playbackSpeed:'播放倍速',firstFrameMs:'首帧耗时（ms）',bufferingWaitMs:'缓冲等待（ms）',droppedFrames:'丢帧数',sdrAvSyncMs:'音画差（ms）',playerError:'播放错误',isPlaying:'正在播放',playRequested:'已请求播放',videoRendererEnabled:'视频输出已启用'};
  const details=Object.entries(data).filter(([key,value])=>key!=='report'&&value!==null).map(([key,value])=>{
    const text=typeof value==='boolean'?(value?'是':'否'):typeof value==='object'?JSON.stringify(value):String(value);
    return `| ${labels[key]||key} | ${text.replaceAll('|','\\|').replaceAll('\n',' ')} |`;
  }).join('\n');
  const issue={title:`[播放回执] ${kinds[body.kind]} · ${data.version} · ${data.model||'设备未注明'}`,body:`<!-- ${marker} -->\n\n| 项目 | 记录 |\n| --- | --- |\n${details}`};
  try {
    const response=await fetcher(API,{method:'POST',headers:{Authorization:`Bearer ${env.REPORT_GITHUB_TOKEN}`,Accept:'application/vnd.github+json','Content-Type':'application/json','User-Agent':'yingxi-playback-reports','X-GitHub-Api-Version':'2022-11-28'},body:JSON.stringify(issue),signal:AbortSignal.timeout(12000)});
    if(response.status!==201){console.warn('playback_report_repository_status',response.status);return json(502,{error:'仓库暂未接收报告，请保留内容后重试。'});}
    const result=await response.json();
    if(!Number.isSafeInteger(result.number)||!new RegExp('^https://github\\.com/RoyalLiang/Yingxi-Studio/issues/[0-9]+$').test(result.html_url))throw Error('response');
    return json(201,{url:result.html_url,number:result.number});
  } catch {
    // An interrupted response may follow a successful creation; never retry automatically.
    return json(502,{error:'提交状态未确认，请先在仓库检查是否已收到，避免重复提交。',uncertain:true,url:`${REPOSITORY}/issues?q=${encodeURIComponent(marker)}`});
  }
}
