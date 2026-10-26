import {CONSENT_VERSION,REPOSITORY,sanitizeReport} from './schema.js';
const idPattern=/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
export function nativeReportRequest(input) {
  if(input?.consent!==true||input.consentVersion!==CONSENT_VERSION||typeof input.id!=='string'||!idPattern.test(input.id)||!['stutter','black','audio','subtitle','other'].includes(input.kind))throw Error('请返回播放器，确认公开范围后重新提交。');
  return {id:input.id,kind:input.kind,consent:true,consentVersion:CONSENT_VERSION,report:sanitizeReport(input.report).data};
}
export async function sendNativeReport(input,challenge,fetcher=fetch) {
  const request=nativeReportRequest(input);
  if(typeof challenge!=='string'||!challenge.length||challenge.length>2048)throw Error('安全校验无效，请保留检修内容。');
  const body=JSON.stringify({...request,challenge});
  if(new TextEncoder().encode(body).length>32768)throw Error('报告过大，请先复制并保留检修内容。');
  const response=await fetcher('/api/playback-report',{method:'POST',headers:{'Content-Type':'application/json'},body,signal:AbortSignal.timeout(25000)});
  return {status:response.status,result:await response.json()};
}

if(typeof window!=='undefined'){
  const status=document.getElementById('status'),resultLink=document.getElementById('result');
  let started=false;
  function state(text,error=false){status.textContent=text;status.classList.toggle('error',error);}
  function link(url,title){
    if(!url.startsWith(REPOSITORY+'/issues'))return;
    resultLink.href=url;resultLink.textContent=title;resultLink.hidden=false;
  }
  // Opening this page alone never loads a report or sends one.
  window.startPlaybackReport=async input=>{
    if(started)return;started=true;
    let request;
    try{request=nativeReportRequest(input);}catch(error){state(error.message,true);return;}
    const search=REPOSITORY+'/issues?q='+encodeURIComponent('yingxi-playback-report:'+request.id);
    async function send(token){
      state('正在寄送回执，请勿重复点击。');
      try{
        const {status:code,result}=await sendNativeReport(request,token);
        if(code===201&&Number.isSafeInteger(result.number)&&new RegExp('^https://github\\.com/RoyalLiang/Yingxi-Studio/issues/[0-9]+$').test(result.url)){
          state('已上报，回执 #'+result.number+'。');link(result.url,'查看公开回执');window.location.href='yingxi-report://receipt?id='+encodeURIComponent(request.id)+'&number='+result.number;
        }else{state(result.error||'上报未完成，请保留检修内容。',true);if(result.uncertain)link(search,'先检查仓库是否已收到');}
      }catch{state('提交结果未确认，请先检查仓库，避免重复发送。',true);link(search,'检查是否已收到');}
    }
    try{
      const response=await fetch('/api/playback-report',{cache:'no-store',signal:AbortSignal.timeout(15000)});
      const config=await response.json();
      if(!response.ok||!config.available||!config.siteKey){state('自动上报通道尚未准备好，可先复制并保留检修内容。',true);return;}
      if(config.consentVersion!==CONSENT_VERSION){state('公开范围告知已更新，请更新应用后再上报。',true);return;}
      state('正在安全校验；必要时请在此完成校验。');
      const script=document.createElement('script');script.src='https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';script.async=true;
      script.onload=()=>{let sent=false;window.turnstile.render('#challenge',{sitekey:config.siteKey,action:'playback-report',cData:request.id,size:'compact',theme:'dark',
        callback:token=>{if(!sent){sent=true;send(token);}},
        'error-callback':()=>state('安全校验未完成，请保留内容后稍后重试。',true),
        'expired-callback':()=>{if(!sent)state('安全校验已过期，请保留内容后稍后重试。',true);}
      });};
      script.onerror=()=>state('安全校验暂不可用，请先复制并保留检修内容。',true);document.head.append(script);
    }catch{state('未能连接上报通道，请先复制并保留检修内容。',true);}
  };
}

