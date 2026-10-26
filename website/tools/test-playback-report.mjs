import test from 'node:test';
import assert from 'node:assert/strict';
import {onRequestGet,onRequestPost} from '../functions/api/playback-report.js';
import {sanitizeReport,CONSENT_VERSION} from '../public/report/schema.js';
const origin='https://yingxi-station.pages.dev';
const env={REPORT_GITHUB_TOKEN:'fixture-only-repository-credential',REPORT_TURNSTILE_SECRET:'fixture-only-challenge-credential',REPORT_TURNSTILE_SITE_KEY:'fixture-site-key'};
const id='d7f30180-10c2-4cbb-a0a4-5a172a1118b0';
const report={report:'yingxi-playback-v1',version:'0.2.0-alpha.56',model:'PLY110',source:'SFTP',sdrAvSyncMs:-92,encodedFrameRate:23.976,sdrPollMainMs:1.5,audioDecoder:'ffmpegLavc63.1.101-alac',videoCodecs:'dvhe.05.06',decoder:'mpv.hevc.libplacebo.sdr-bt709',positionMs:Infinity,path:'/private/movie',password:'do-not-forward',headers:{Authorization:'do-not-forward'},url:'https://private.invalid/film'};
function body(extra={}){return {id,kind:'stutter',consent:true,consentVersion:CONSENT_VERSION,challenge:'fixture-token',report,...extra};}
function context(payload=body(),headers={}){return {env,request:new Request(origin+'/api/playback-report',{method:'POST',headers:{Origin:origin,'Content-Type':'application/json',...headers},body:JSON.stringify(payload)})};}
function verify(extra={}){return Response.json({success:true,hostname:'yingxi-station.pages.dev',action:'playback-report',cdata:id,...extra});}

test('allowlist drops private fields, nonfinite numbers and unknown decoder strings',()=>{
 const {data,removed}=sanitizeReport(report);assert.equal(data.sdrAvSyncMs,-92);assert.equal(data.positionMs,null);assert.equal(data.model,'PLY110');assert.equal(data.sdrPollMainMs,1.5);assert.equal(data.audioDecoder,'ffmpegLavc63.1.101-alac');
 for(const key of ['path','password','headers','url']){assert(!Object.hasOwn(data,key));assert(removed.includes(key));}
 assert.equal(sanitizeReport({...report,decoder:'https://private.invalid'}).data.decoder,null);
  assert.equal(sanitizeReport({...report,model:'name@example.invalid'}).data.model,null);
 assert(!JSON.stringify(data).includes('do-not-forward'));
});
test('config exposes no credential and cannot create issues',async()=>{
 const response=await onRequestGet({env});const config=await response.json();assert.equal(config.available,true);assert.equal(config.siteKey,'fixture-site-key');assert(!JSON.stringify(config).includes('credential'));
 assert.equal((await (await onRequestGet({env:{}})).json()).available,false);
});
test('missing configuration fails closed before parsing or transmission',async()=>{
 const response=await onRequestPost({...context(),env:{}},()=>assert.fail('outbound call'));assert.equal(response.status,503);
});
test('explicit current consent and same origin are mandatory',async()=>{
 for(const payload of [body({consent:false}),body({consentVersion:'old'}),body({id:'not-an-id'}),body({kind:'__proto__'})])assert.equal((await onRequestPost(context(payload),()=>assert.fail('outbound call'))).status,400);
 assert.equal((await onRequestPost(context(body(),{Origin:'https://other.invalid'}),()=>assert.fail('outbound call'))).status,403);
});
test('actual streamed bytes enforce 32KiB even without content-length',async()=>{
 const response=await onRequestPost(context(body({padding:'x'.repeat(33000)})),()=>assert.fail('outbound call'));assert.equal(response.status,400);
});
test('failed, wrong host, wrong action or wrong request challenge cannot reach repository',async()=>{
 for(const result of [{success:false},{hostname:'other.invalid'},{action:'different'},{cdata:'different'}]){
  let calls=0;const response=await onRequestPost(context(),async url=>{calls++;assert(url.includes('/siteverify'));return verify(result);});assert.equal(response.status,403);assert.equal(calls,1);
 }
});
test('confirmed form creates exactly one sanitized issue and returns its real URL',async()=>{
 let calls=0;const response=await onRequestPost(context(),async(url,options)=>{
  calls++;if(url.includes('/siteverify')){assert(!options.body.toString().includes('private/movie'));return verify();}
  assert.equal(url,'https://api.github.com/repos/RoyalLiang/Yingxi-Studio/issues');assert.equal(options.headers.Authorization,'Bearer fixture-only-repository-credential');
  const issue=JSON.parse(options.body);assert(issue.body.includes(id));assert(issue.title.includes('PLY110'));assert(!issue.body.includes('do-not-forward'));assert(!issue.body.includes('private/movie'));assert(!issue.body.includes('private.invalid'));
  return Response.json({number:123,html_url:'https://github.com/RoyalLiang/Yingxi-Studio/issues/123'},{status:201});
 });assert.equal(response.status,201);assert.equal(calls,2);assert.equal((await response.json()).number,123);
});
test('unknown delivery is never retried and only exposes a trusted repository search',async()=>{
 let posts=0;const response=await onRequestPost(context(),async url=>{if(url.includes('/siteverify'))return verify();posts++;throw Error('network interrupted after send');});
 const result=await response.json();assert.equal(response.status,502);assert.equal(posts,1);assert.equal(result.uncertain,true);assert(result.url.startsWith('https://github.com/RoyalLiang/Yingxi-Studio/issues?'));assert(!JSON.stringify(result).includes(env.REPORT_GITHUB_TOKEN));
});

// The mobile page has no URL-driven automatic submission. The app supplies a request only after its send action.
const {nativeReportRequest,sendNativeReport}=await import('../public/report/native-report.js');
test('native request requires explicit current consent and strips private fields',()=>{
 for(const value of [body({consent:false}),body({consentVersion:'old'}),body({id:'not-an-id'})])assert.throws(()=>nativeReportRequest(value));
 const request=nativeReportRequest(body());assert.equal(request.id,id);assert.equal(request.consent,true);assert(!Object.hasOwn(request.report,'path'));assert(!Object.hasOwn(request,'challenge'));
});
test('native submission sends one consented sanitized request and preserves confirmed acknowledgement',async()=>{
 let calls=0;const result=await sendNativeReport(body(),'fixture-token',async(url,options)=>{
  calls++;assert.equal(url,'/api/playback-report');const data=JSON.parse(options.body);assert.equal(data.id,id);assert.equal(data.consent,true);assert.equal(data.challenge,'fixture-token');assert(!JSON.stringify(data).includes('do-not-forward'));
  return Response.json({number:123,url:'https://github.com/RoyalLiang/Yingxi-Studio/issues/123'},{status:201});
 });assert.equal(calls,1);assert.equal(result.status,201);assert.equal(result.result.number,123);
});
test('native network uncertainty is not retried and invalid consent cannot call the receiver',async()=>{
 let calls=0;await assert.rejects(sendNativeReport(body(),'fixture-token',async()=>{calls++;throw Error('connection lost');}));assert.equal(calls,1);
 await assert.rejects(sendNativeReport(body({consent:false}),'fixture-token',()=>assert.fail('must not submit')));
 await assert.rejects(sendNativeReport(body(),'',()=>assert.fail('must not submit')));
});

// Exercise the shipped page controller with a local DOM/Turnstile fixture; no public Issue is posted.
test('compact native UI keeps legacy behavior and posts once through verification states',async()=>{
  const old={window:globalThis.window,document:globalThis.document,fetch:globalThis.fetch};
  try{
    for(const nativeUi of [1,2]){
      const navigations=[],posts=[];let options,quiet;
      const status={textContent:'',classList:{toggle(){}}},link={hidden:true};
      globalThis.window={location:{set href(value){navigations.push(value)}},turnstile:{render(_selector,value){options=value}}};
      globalThis.document={body:{classList:{toggle(_name,value){quiet=value}}},getElementById:id=>id==='status'?status:link,createElement:()=>({}),head:{append(script){script.onload()}}};
      globalThis.fetch=async(_url,init)=>{
        if(init?.method==='POST'){posts.push(JSON.parse(init.body));return {status:201,json:async()=>({number:17,url:'https://github.com/RoyalLiang/Yingxi-Studio/issues/17'})}}
        return {ok:true,json:async()=>({available:true,siteKey:'synthetic-widget-key',consentVersion:'2026-10-26'})};
      };
      await import(new URL('../public/report/native-report.js?native-ui-test='+nativeUi,import.meta.url));
      const input={id:'00000000-0000-4000-8000-000000000001',kind:'other',consent:true,consentVersion:'2026-10-26',nativeUi,report:{report:'yingxi-playback-v1',version:'1.2.3'}};
      await window.startPlaybackReport(input);
      assert.equal(quiet,nativeUi===2);assert.equal(options.appearance,nativeUi===2?'interaction-only':'always');
      options['before-interactive-callback']();options['after-interactive-callback']();
      await options.callback('synthetic-challenge');await options.callback('synthetic-challenge');await window.startPlaybackReport(input);
      assert.equal(posts.length,1);assert.equal(posts[0].nativeUi,undefined);
      assert.ok(navigations.at(-1).includes('yingxi-report://receipt?'));
      assert.equal(navigations.some(value=>value.includes('phase=challenge')),nativeUi===2);
      assert.match(status.textContent,/#17/);
    }
  }finally{globalThis.window=old.window;globalThis.document=old.document;globalThis.fetch=old.fetch}
});
