import {CONSENT_VERSION,sanitizeReport} from './schema.js';
const $=id=>document.getElementById(id);
let report=null,available=false,challenge=null,widget=null,submitting=false,uncertain=false;
const id=crypto.randomUUID();
function state(text,error=false){$('delivery-status').textContent=text;$('delivery-status').classList.toggle('error',error);}
function update(){ $('report-input').disabled=submitting||uncertain;$('kind').disabled=submitting||uncertain;$('consent').disabled=submitting||uncertain;$('submit').disabled=!report||!available||!challenge||!$('consent').checked||submitting||uncertain;$('copy').disabled=!report;$('submit').textContent=submitting?'正在寄送…':'提交到公开仓库'; }
function parse(){
  try {
    const result=sanitizeReport(JSON.parse($('report-input').value));report=result.data;
    $('payload').textContent=JSON.stringify(report,null,2);$('snapshot-state').textContent='已就绪';
    const rows=[['设备',[report.manufacturer,report.model].filter(Boolean).join(' ')||'未注明'],['应用版本',report.version],['画面与编码',`${report.encodedWidth||'—'}×${report.encodedHeight||'—'} · ${report.videoCodecs||report.videoMime||'未注明'}`],['播放状态',`${report.state||'—'} · ${report.playbackSpeed||1}×`],['掉帧 / 音画差',`${report.droppedFrames??'—'} 帧 / ${report.sdrAvSyncMs??'—'} ms`],['主线程检修耗时',report.sdrPollMainMs==null?'此版本未记录':`${report.sdrPollMainMs.toFixed(2)} ms`]];
    $('summary').replaceChildren(...rows.map(([name,value])=>{const div=document.createElement('div'),dt=document.createElement('dt'),dd=document.createElement('dd');dt.textContent=name;dd.textContent=value;div.append(dt,dd);return div;}));
    $('parse-status').textContent=result.removed.length?`已过滤 ${result.removed.length} 个非检修字段；只会发送预览中列出的内容。`:'只会发送预览中列出的检修字段。';$('parse-status').classList.remove('error');
  } catch(error){report=null;$('snapshot-state').textContent='等待有效报告';$('payload').textContent='尚无有效内容。';$('parse-status').textContent=error.message.startsWith('请')?error.message:'JSON 无效，请从播放器重新复制。';$('parse-status').classList.add('error');}
  // Editing the payload always revokes the earlier consent.
  $('consent').checked=false;update();
}
$('report-input').addEventListener('input',parse);$('consent').addEventListener('change',update);
try {
  const encoded=new URLSearchParams(location.hash.slice(1)).get('report');
  history.replaceState(null,'',location.pathname);
  if(encoded&&encoded.length<45000){const data=atob(encoded.replaceAll('-','+').replaceAll('_','/'));$('report-input').value=new TextDecoder('utf-8',{fatal:true}).decode(Uint8Array.from(data,x=>x.charCodeAt(0)));parse();}
} catch{state('无法读取随附快照，请手动粘贴检修 JSON。',true);}
$('copy').addEventListener('click',async()=>{try{await navigator.clipboard.writeText(JSON.stringify(report,null,2));$('copy').textContent='已复制到本机';}catch{state('复制未完成，可展开预览并手动复制。',true);}});
$('report-form').addEventListener('submit',async event=>{
  event.preventDefault();if($('submit').disabled)return;
  submitting=true;update();state('正在提交；请勿重复点击。');
  try {
    const response=await fetch('/api/playback-report',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({id,kind:$('kind').value,report,consent:true,consentVersion:CONSENT_VERSION,challenge}),signal:AbortSignal.timeout(25000)});
    const result=await response.json();
    if(response.status===201){uncertain=true;state(`已收到回执 #${result.number}。`);$('result-link').href=result.url;$('result-link').hidden=false;$('result-link').textContent='查看公开回执';}
    else{uncertain=!!result.uncertain;state(result.error||'提交未完成，请保留内容。',true);if(result.uncertain&&result.url){$('result-link').href=result.url;$('result-link').textContent='先检查仓库是否已收到';$('result-link').hidden=false;}}
  } catch{uncertain=true;state('提交状态未确认，请先在仓库检查是否已收到，避免重复发送。',true);$('result-link').href=`https://github.com/RoyalLiang/Yingxi-Studio/issues?q=${encodeURIComponent('yingxi-playback-report:'+id)}`;$('result-link').textContent='先检查仓库是否已收到';$('result-link').hidden=false;}
  finally{submitting=false;challenge=null;if(widget!==null)window.turnstile?.reset(widget);update();}
});
try {
  const config=await (await fetch('/api/playback-report',{cache:'no-store'})).json();available=!!config.available;
  if(available&&config.siteKey){
    state('完成勾选与校验后，再点击提交。');
    const script=document.createElement('script');script.src='https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';script.async=true;
    script.onload=()=>{widget=window.turnstile.render('#challenge',{sitekey:config.siteKey,action:'playback-report',cData:id,callback:token=>{challenge=token;update();},'expired-callback':()=>{challenge=null;update();},'error-callback':()=>{challenge=null;state('人机校验暂不可用，请刷新页面重试。',true);update();}});};
    script.onerror=()=>state('人机校验暂不可用，可先复制并保留报告。',true);document.head.append(script);
  } else state('自动上报通道正在准备中，请先复制并保留报告。');
} catch{state('暂时无法连接上报通道，可先复制并保留报告。',true);}update();
